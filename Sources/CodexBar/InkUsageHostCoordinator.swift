import AppKit
import CodexBarCore
import Foundation
import Network
import Observation

@MainActor
@Observable
final class InkUsageHostCoordinator {
    enum State: Equatable {
        case disabled
        case sleeping
        case starting
        case lanReady(url: String)
        case degraded(String)

        var summary: String {
            switch self {
            case .disabled: "Disabled"
            case .sleeping: "Sleeping"
            case .starting: "Starting…"
            case let .lanReady(url): "Ready at \(url)"
            case let .degraded(message): message
            }
        }
    }

    typealias SnapshotProvider = @MainActor @Sendable () throws -> Data
    typealias ServerFactory = @Sendable (InkUsageHostGateway) -> any InkLANHTTPServing
    typealias AddressProvider = @Sendable () -> String?

    private static let enabledDefaultsKey = "inkUsageHostEnabled"
    private let defaults: UserDefaults
    private let addressProvider: AddressProvider
    private let snapshotProvider: SnapshotProvider
    private let serverFactory: ServerFactory
    private let pathMonitor = NWPathMonitor()
    private let pathQueue = DispatchQueue(label: "com.ysimo.codexbar.ink.path-monitor")
    private var observers: [NSObjectProtocol] = []
    private var server: (any InkLANHTTPServing)?
    private var endpoint: InkLANEndpoint?
    private var operationTask: Task<Void, Never>?
    private var operationGeneration = 0
    private var retryTask: Task<Void, Never>?
    private var isSleeping = false
    private var hasStarted = false

    private(set) var state: State = .disabled
    private(set) var hostURL: String?
    private(set) var nextRetryAt: Date?
    var isEnabled: Bool

    init(
        defaults: UserDefaults = .standard,
        addressProvider: @escaping AddressProvider = { InkPrivateLANAddress.currentIPv4() },
        monitorLifecycle: Bool = true,
        snapshotProvider: @escaping SnapshotProvider,
        serverFactory: @escaping ServerFactory = { InkLANHTTPServer(gateway: $0) })
    {
        self.defaults = defaults
        self.addressProvider = addressProvider
        self.snapshotProvider = snapshotProvider
        self.serverFactory = serverFactory
        self.isEnabled = defaults.bool(forKey: Self.enabledDefaultsKey)
        if monitorLifecycle {
            self.installLifecycleMonitoring()
        }
    }

    func startIfEnabled() {
        self.hasStarted = true
        guard self.isEnabled else { return }
        self.start()
    }

    func setEnabled(_ enabled: Bool) {
        self.hasStarted = true
        self.isEnabled = enabled
        self.defaults.set(enabled, forKey: Self.enabledDefaultsKey)
        if enabled {
            self.start()
        } else {
            self.stop()
        }
    }

    func retryNow() {
        guard self.hasStarted, self.isEnabled, !self.isSleeping else { return }
        guard self.operationTask == nil else { return }
        self.retryTask?.cancel()
        self.retryTask = nil
        self.nextRetryAt = nil
        let address = self.addressProvider()
        if self.server != nil, self.endpoint?.address == address {
            return
        }
        self.restart()
    }

    func prepareForTermination() {
        self.hasStarted = false
        self.stop()
        self.pathMonitor.cancel()
        for observer in self.observers {
            NotificationCenter.default.removeObserver(observer)
        }
        self.observers.removeAll()
    }

    private func start() {
        guard self.operationTask == nil, self.server == nil, !self.isSleeping else { return }
        guard let address = self.addressProvider(), InkPrivateLANAddress.isAllowedIPv4(address) else {
            self.state = .degraded("Private LAN unavailable")
            self.scheduleRetry()
            return
        }

        self.state = .starting
        self.operationGeneration &+= 1
        let generation = self.operationGeneration
        self.operationTask = Task { [weak self] in
            guard let self else { return }
            defer { self.finishOperation(generation) }
            do {
                let snapshotProvider = self.snapshotProvider
                let gateway = InkUsageHostGateway {
                    try await snapshotProvider()
                }
                let server = self.serverFactory(gateway)
                let endpoint = try await server.start(address: address)
                guard generation == self.operationGeneration, self.isEnabled, !Task.isCancelled else {
                    server.stop()
                    return
                }
                await gateway.updateExternalHost(endpoint.authority)
                self.endpoint = endpoint
                self.server = server
                self.hostURL = endpoint.baseURL
                self.state = .lanReady(url: endpoint.baseURL)
                self.retryTask?.cancel()
                self.retryTask = nil
                self.nextRetryAt = nil
            } catch is CancellationError {
                return
            } catch {
                guard generation == self.operationGeneration, self.isEnabled, !Task.isCancelled else { return }
                self.state = .degraded("LAN Usage Host unavailable")
                self.scheduleRetry()
            }
        }
    }

    private func restart() {
        self.operationGeneration &+= 1
        self.operationTask?.cancel()
        self.operationTask = nil
        self.server?.stop()
        self.server = nil
        self.endpoint = nil
        self.hostURL = nil
        self.start()
    }

    private func stop() {
        self.operationGeneration &+= 1
        self.operationTask?.cancel()
        self.operationTask = nil
        self.retryTask?.cancel()
        self.retryTask = nil
        self.nextRetryAt = nil
        self.server?.stop()
        self.server = nil
        self.endpoint = nil
        self.hostURL = nil
        self.state = .disabled
    }

    private func scheduleRetry() {
        guard self.hasStarted, self.isEnabled, self.retryTask == nil, !self.isSleeping else { return }
        self.nextRetryAt = Date().addingTimeInterval(30)
        self.retryTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(30))
            guard !Task.isCancelled else { return }
            self?.retryAfterDelay()
        }
    }

    private func retryAfterDelay() {
        self.retryTask = nil
        self.nextRetryAt = nil
        self.retryNow()
    }

    private func finishOperation(_ generation: Int) {
        guard self.operationGeneration == generation else { return }
        self.operationTask = nil
    }

    func handleWillSleep() {
        guard self.hasStarted else { return }
        self.isSleeping = true
        self.operationGeneration &+= 1
        self.operationTask?.cancel()
        self.operationTask = nil
        self.retryTask?.cancel()
        self.retryTask = nil
        self.nextRetryAt = nil
        self.server?.stop()
        self.server = nil
        self.endpoint = nil
        self.hostURL = nil
        if self.isEnabled {
            self.state = .sleeping
        }
    }

    func handleDidWake() {
        guard self.hasStarted else { return }
        self.isSleeping = false
        self.retryNow()
    }

    func handleNetworkAvailable() {
        self.retryNow()
    }

    private func installLifecycleMonitoring() {
        let center = NSWorkspace.shared.notificationCenter
        self.observers.append(center.addObserver(
            forName: NSWorkspace.willSleepNotification,
            object: nil,
            queue: .main)
        { [weak self] _ in
            MainActor.assumeIsolated {
                self?.handleWillSleep()
            }
        })
        self.observers.append(center.addObserver(
            forName: NSWorkspace.didWakeNotification,
            object: nil,
            queue: .main)
        { [weak self] _ in
            MainActor.assumeIsolated {
                self?.handleDidWake()
            }
        })
        self.pathMonitor.pathUpdateHandler = { [weak self] path in
            guard path.status == .satisfied else { return }
            Task { @MainActor [weak self] in self?.handleNetworkAvailable()
            }
        }
        self.pathMonitor.start(queue: self.pathQueue)
    }
}
