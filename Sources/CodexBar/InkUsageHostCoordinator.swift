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
        case localReady(port: UInt16)
        case tailnetReady(host: String)
        case degraded(String)

        var summary: String {
            switch self {
            case .disabled: "Disabled"
            case .sleeping: "Sleeping"
            case .starting: "Starting…"
            case let .localReady(port): "Local listener ready on 127.0.0.1:\(port)"
            case let .tailnetReady(host): "Ready at https://\(host)"
            case let .degraded(message): message
            }
        }
    }

    typealias SnapshotProvider = @MainActor @Sendable () throws -> Data
    typealias ServerFactory = @Sendable (InkUsageHostGateway) -> any InkLoopbackServing

    private static let enabledDefaultsKey = "inkUsageHostEnabled"
    private let defaults: UserDefaults
    private let tokenStore: any ReaderTokenStoring
    private let tailscale: any InkTailscaleServing
    private let healthChecker: any InkUsageHostHealthChecking
    private let snapshotProvider: SnapshotProvider
    private let serverFactory: ServerFactory
    private let pathMonitor = NWPathMonitor()
    private let pathQueue = DispatchQueue(label: "com.ysimo.codexbar.ink.path-monitor")
    private var observers: [NSObjectProtocol] = []
    private var server: (any InkLoopbackServing)?
    private var gateway: InkUsageHostGateway?
    private var token: String?
    private var listenerPort: UInt16?
    private var operationTask: Task<Void, Never>?
    private var operationGeneration = 0
    private var retryTask: Task<Void, Never>?
    private var serveResetTask: Task<Void, Never>?
    private var serveResetGeneration = 0
    private var isSleeping = false
    private var hasStarted = false

    private(set) var state: State = .disabled
    private(set) var tokenFingerprint: String?
    private(set) var magicDNSName: String?
    private(set) var nextRetryAt: Date?
    var isEnabled: Bool

    init(
        defaults: UserDefaults = .standard,
        tokenStore: any ReaderTokenStoring = KeychainReaderTokenStore(),
        tailscale: any InkTailscaleServing = InkTailscaleServeClient(),
        healthChecker: any InkUsageHostHealthChecking = InkUsageHostHealthChecker(),
        monitorLifecycle: Bool = true,
        snapshotProvider: @escaping SnapshotProvider,
        serverFactory: @escaping ServerFactory = { InkLoopbackHTTPServer(gateway: $0) })
    {
        self.defaults = defaults
        self.tokenStore = tokenStore
        self.tailscale = tailscale
        self.healthChecker = healthChecker
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
            self.stop(resetServe: true)
        }
    }

    func retryNow() {
        guard self.hasStarted, self.isEnabled, !self.isSleeping else { return }
        self.retryTask?.cancel()
        self.retryTask = nil
        self.nextRetryAt = nil
        if self.server == nil {
            self.start()
        } else {
            self.reconcile()
        }
    }

    func rotateToken() {
        guard self.hasStarted, self.isEnabled else { return }
        self.operationGeneration &+= 1
        let generation = self.operationGeneration
        self.operationTask?.cancel()
        self.operationTask = Task { [weak self] in
            guard let self else { return }
            defer { self.finishOperation(generation) }
            do {
                let token = try ReaderTokenGenerator.generate()
                try self.tokenStore.save(token)
                self.token = token
                self.tokenFingerprint = ReaderTokenGenerator.shortFingerprint(token)
                await self.gateway?.updateToken(token)
                self.reconcile()
            } catch {
                guard self.isEnabled, !Task.isCancelled else { return }
                self.state = .degraded("Reader token unavailable")
                self.scheduleRetry()
            }
        }
    }

    func copyReaderToken() {
        guard let token else { return }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(token, forType: .string)
    }

    func prepareForTermination() {
        self.hasStarted = false
        self.stop(resetServe: false)
        self.pathMonitor.cancel()
        for observer in self.observers {
            NotificationCenter.default.removeObserver(observer)
        }
        self.observers.removeAll()
    }

    private func start() {
        guard self.operationTask == nil, self.server == nil, !self.isSleeping else { return }
        self.state = .starting
        self.operationGeneration &+= 1
        let generation = self.operationGeneration
        self.operationTask = Task { [weak self] in
            guard let self else { return }
            defer { self.finishOperation(generation) }
            do {
                let resetGeneration = self.serveResetGeneration
                if let serveResetTask = self.serveResetTask {
                    await serveResetTask.value
                    guard self.isEnabled, !Task.isCancelled else { return }
                    if self.serveResetGeneration == resetGeneration {
                        self.serveResetTask = nil
                    }
                }
                let token = try self.loadOrCreateToken()
                let snapshotProvider = self.snapshotProvider
                let gateway = InkUsageHostGateway(token: token) {
                    try await snapshotProvider()
                }
                let server = self.serverFactory(gateway)
                let port = try await server.start()
                guard self.isEnabled, !Task.isCancelled else {
                    server.stop()
                    return
                }
                self.token = token
                self.tokenFingerprint = ReaderTokenGenerator.shortFingerprint(token)
                self.gateway = gateway
                self.server = server
                self.listenerPort = port
                self.state = .localReady(port: port)
                await self.reconcile(localPort: port)
            } catch is CancellationError {
                return
            } catch {
                guard self.isEnabled, !Task.isCancelled else { return }
                self.state = .degraded("Local Usage Host unavailable")
                self.scheduleRetry()
            }
        }
    }

    private func reconcile() {
        guard let port = self.currentPort else { return }
        self.operationGeneration &+= 1
        let generation = self.operationGeneration
        self.operationTask?.cancel()
        self.operationTask = Task { [weak self] in
            guard let self else { return }
            defer { self.finishOperation(generation) }
            await self.reconcile(localPort: port)
        }
    }

    private func reconcile(localPort: UInt16) async {
        do {
            let result = try await self.tailscale.reconcile(localPort: localPort, now: Date())
            guard self.isEnabled, !Task.isCancelled else { return }
            self.magicDNSName = result.dnsName
            await self.gateway?.updateExternalHost(result.dnsName)
            guard let token = self.token else {
                self.state = .degraded("Reader token unavailable")
                self.scheduleRetry()
                return
            }
            let health = await self.healthChecker.check(dnsName: result.dnsName, token: token)
            guard health == .healthy else {
                self.state = .degraded(health.diagnostic)
                self.scheduleRetry()
                return
            }
            self.state = .tailnetReady(host: result.dnsName)
            self.retryTask?.cancel()
            self.retryTask = nil
            self.nextRetryAt = nil
        } catch let error as InkTailscaleServeError {
            guard self.isEnabled, !Task.isCancelled else { return }
            self.state = .degraded(error.diagnostic)
            self.scheduleRetry()
        } catch {
            guard self.isEnabled, !Task.isCancelled else { return }
            self.state = .degraded("Tailscale Serve unavailable")
            self.scheduleRetry()
        }
    }

    private var currentPort: UInt16? {
        self.listenerPort
    }

    private func stop(resetServe: Bool) {
        self.operationGeneration &+= 1
        self.operationTask?.cancel()
        self.operationTask = nil
        self.retryTask?.cancel()
        self.retryTask = nil
        self.nextRetryAt = nil
        self.server?.stop()
        self.server = nil
        self.listenerPort = nil
        self.gateway = nil
        self.token = nil
        self.tokenFingerprint = nil
        self.magicDNSName = nil
        self.state = .disabled
        if resetServe {
            self.serveResetGeneration &+= 1
            self.serveResetTask = Task { [tailscale = self.tailscale] in await tailscale.reset() }
        }
    }

    private func loadOrCreateToken() throws -> String {
        if let token = try self.tokenStore.load() { return token }
        let token = try ReaderTokenGenerator.generate()
        try self.tokenStore.save(token)
        return token
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
            Task { @MainActor [weak self] in self?.handleNetworkAvailable() }
        }
        self.pathMonitor.start(queue: self.pathQueue)
    }
}
