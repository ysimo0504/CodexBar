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
    typealias ServerFactory = @Sendable (InkUsageHostGateway) -> any InkLANHTTPSServing
    typealias AddressProvider = @Sendable () -> String?

    private static let enabledDefaultsKey = "inkUsageHostEnabled"
    private let defaults: UserDefaults
    private let tokenStore: any ReaderTokenStoring
    private let identityStore: any InkTLSIdentityStoring
    private let addressProvider: AddressProvider
    private let snapshotProvider: SnapshotProvider
    private let serverFactory: ServerFactory
    private let pathMonitor = NWPathMonitor()
    private let pathQueue = DispatchQueue(label: "com.ysimo.codexbar.ink.path-monitor")
    private var observers: [NSObjectProtocol] = []
    private var server: (any InkLANHTTPSServing)?
    private var gateway: InkUsageHostGateway?
    private var token: String?
    private var identity: InkTLSIdentityMaterial?
    private var endpoint: InkLANEndpoint?
    private var operationTask: Task<Void, Never>?
    private var operationGeneration = 0
    private var retryTask: Task<Void, Never>?
    private var isSleeping = false
    private var hasStarted = false

    private(set) var state: State = .disabled
    private(set) var tokenFingerprint: String?
    private(set) var certificateFingerprint: String?
    private(set) var hostID: String?
    private(set) var pairingURL: String?
    private(set) var pairingPayload: String?
    private(set) var nextRetryAt: Date?
    var isEnabled: Bool

    init(
        defaults: UserDefaults = .standard,
        tokenStore: any ReaderTokenStoring = KeychainReaderTokenStore(),
        identityStore: any InkTLSIdentityStoring = FileInkTLSIdentityStore.applicationDefault(),
        addressProvider: @escaping AddressProvider = { InkPrivateLANAddress.currentIPv4() },
        monitorLifecycle: Bool = true,
        snapshotProvider: @escaping SnapshotProvider,
        serverFactory: @escaping ServerFactory = { InkLANHTTPSServer(gateway: $0) })
    {
        self.defaults = defaults
        self.tokenStore = tokenStore
        self.identityStore = identityStore
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
        self.retryTask?.cancel()
        self.retryTask = nil
        self.nextRetryAt = nil
        let address = self.addressProvider()
        if self.server != nil, self.endpoint?.address == address {
            return
        }
        self.restart()
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
                guard generation == self.operationGeneration, self.isEnabled, !Task.isCancelled else { return }
                self.token = token
                self.tokenFingerprint = ReaderTokenGenerator.shortFingerprint(token)
                await self.gateway?.updateToken(token)
                self.refreshPairingPayload()
            } catch {
                guard generation == self.operationGeneration, self.isEnabled, !Task.isCancelled else { return }
                self.state = .degraded("Reader token unavailable")
                self.scheduleRetry()
            }
        }
    }

    func copyReaderToken() {
        self.copyToPasteboard(self.token)
    }

    func copyPairingURL() {
        self.copyToPasteboard(self.pairingURL)
    }

    func copyCertificateFingerprint() {
        self.copyToPasteboard(self.certificateFingerprint)
    }

    func copyHostID() {
        self.copyToPasteboard(self.hostID)
    }

    func copyPairingPayload() {
        self.copyToPasteboard(self.pairingPayload)
    }

    private func copyToPasteboard(_ value: String?) {
        guard let value else { return }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(value, forType: .string)
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
                let token = try self.loadOrCreateToken()
                let identity = try self.identityStore.loadOrCreate()
                let snapshotProvider = self.snapshotProvider
                let gateway = InkUsageHostGateway(token: token) {
                    try await snapshotProvider()
                }
                let server = self.serverFactory(gateway)
                let endpoint = try await server.start(identity: identity, address: address)
                guard generation == self.operationGeneration, self.isEnabled, !Task.isCancelled else {
                    server.stop()
                    return
                }
                await gateway.updateExternalHost(endpoint.authority)
                self.token = token
                self.identity = identity
                self.endpoint = endpoint
                self.gateway = gateway
                self.server = server
                self.tokenFingerprint = ReaderTokenGenerator.shortFingerprint(token)
                self.certificateFingerprint = identity.certificateSHA256
                self.hostID = identity.hostID
                self.pairingURL = endpoint.baseURL
                self.refreshPairingPayload()
                self.state = .lanReady(url: endpoint.baseURL)
                self.retryTask?.cancel()
                self.retryTask = nil
                self.nextRetryAt = nil
            } catch is CancellationError {
                return
            } catch is InkTLSIdentityStoreError {
                guard generation == self.operationGeneration, self.isEnabled, !Task.isCancelled else { return }
                self.state = .degraded("TLS identity unavailable")
                self.scheduleRetry()
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
        self.gateway = nil
        self.pairingURL = nil
        self.pairingPayload = nil
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
        self.gateway = nil
        self.token = nil
        self.identity = nil
        self.endpoint = nil
        self.tokenFingerprint = nil
        self.certificateFingerprint = nil
        self.hostID = nil
        self.pairingURL = nil
        self.pairingPayload = nil
        self.state = .disabled
    }

    private func loadOrCreateToken() throws -> String {
        if let token = try self.tokenStore.load() { return token }
        let token = try ReaderTokenGenerator.generate()
        try self.tokenStore.save(token)
        return token
    }

    private func refreshPairingPayload() {
        guard let endpoint, let token, let identity else {
            self.pairingPayload = nil
            return
        }
        let object: [String: Any] = [
            "version": 1,
            "baseURL": endpoint.baseURL,
            "hostID": identity.hostID,
            "certificateSHA256": identity.certificateSHA256,
            "token": token,
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]),
              let payload = String(data: data, encoding: .utf8)
        else {
            self.pairingPayload = nil
            return
        }
        self.pairingPayload = payload
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
        self.gateway = nil
        self.endpoint = nil
        self.pairingURL = nil
        self.pairingPayload = nil
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
