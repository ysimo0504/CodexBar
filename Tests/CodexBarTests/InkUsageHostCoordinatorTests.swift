import CodexBarCore
import Foundation
import Testing
@testable import CodexBar

@MainActor
struct InkUsageHostCoordinatorTests {
    @Test
    func `enable is idempotent and disable couples listener with Serve`() async throws {
        let suite = "InkUsageHostCoordinatorTests-\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let tokenStore = MemoryTokenStore()
        let server = FakeServer(port: 49152)
        let tailscale = FakeTailscale()
        let coordinator = InkUsageHostCoordinator(
            defaults: defaults,
            tokenStore: tokenStore,
            tailscale: tailscale,
            healthChecker: HealthyHealthChecker(),
            monitorLifecycle: false,
            snapshotProvider: { Data("{}".utf8) },
            serverFactory: { _ in server })

        coordinator.setEnabled(true)
        coordinator.setEnabled(true)
        await self.waitUntil { coordinator.state == .tailnetReady(host: "mac.tailnet.ts.net") }
        #expect(server.startCount == 1)
        #expect(defaults.bool(forKey: "inkUsageHostEnabled"))

        coordinator.setEnabled(false)
        await self.waitUntil { await tailscale.resetCount == 1 }
        #expect(server.stopCount == 1)
        #expect(coordinator.state == .disabled)
    }

    @Test
    func `rotation stores a new token and changes only the safe fingerprint`() async throws {
        let defaults = try #require(UserDefaults(suiteName: "InkUsageHostCoordinatorTests-\(UUID().uuidString)"))
        let tokenStore = MemoryTokenStore(token: String(repeating: "a", count: 43))
        let coordinator = InkUsageHostCoordinator(
            defaults: defaults,
            tokenStore: tokenStore,
            tailscale: FakeTailscale(),
            healthChecker: HealthyHealthChecker(),
            monitorLifecycle: false,
            snapshotProvider: { Data("{}".utf8) },
            serverFactory: { _ in FakeServer(port: 49153) })
        coordinator.setEnabled(true)
        await self.waitUntil { coordinator.tokenFingerprint != nil }
        let old = tokenStore.token
        let oldFingerprint = coordinator.tokenFingerprint

        coordinator.rotateToken()
        await self.waitUntil { tokenStore.token != old }
        #expect(coordinator.tokenFingerprint != oldFingerprint)
        #expect(coordinator.state.summary.contains(tokenStore.token ?? "never") == false)
    }

    @Test
    func `sleep wake and network recovery reconcile without restarting listener`() async throws {
        let defaults = try #require(UserDefaults(suiteName: "InkUsageHostCoordinatorTests-\(UUID().uuidString)"))
        let server = FakeServer(port: 49154)
        let tailscale = FakeTailscale()
        let coordinator = InkUsageHostCoordinator(
            defaults: defaults,
            tokenStore: MemoryTokenStore(),
            tailscale: tailscale,
            healthChecker: HealthyHealthChecker(),
            monitorLifecycle: false,
            snapshotProvider: { Data("{}".utf8) },
            serverFactory: { _ in server })
        coordinator.setEnabled(true)
        await self.waitUntil { await tailscale.reconcileCount == 1 }

        coordinator.handleWillSleep()
        #expect(coordinator.state == .sleeping)
        coordinator.handleDidWake()
        await self.waitUntil { await tailscale.reconcileCount == 2 }
        coordinator.handleNetworkAvailable()
        await self.waitUntil { await tailscale.reconcileCount == 3 }

        #expect(server.startCount == 1)
        #expect(coordinator.state == .tailnetReady(host: "mac.tailnet.ts.net"))
    }

    @Test
    func `disable cannot be overwritten by a cancelled reconcile failure`() async throws {
        let defaults = try #require(UserDefaults(suiteName: "InkUsageHostCoordinatorTests-\(UUID().uuidString)"))
        let tailscale = BlockingTailscale()
        let coordinator = InkUsageHostCoordinator(
            defaults: defaults,
            tokenStore: MemoryTokenStore(),
            tailscale: tailscale,
            healthChecker: HealthyHealthChecker(),
            monitorLifecycle: false,
            snapshotProvider: { Data("{}".utf8) },
            serverFactory: { _ in FakeServer(port: 49155) })
        coordinator.setEnabled(true)
        await self.waitUntil { await tailscale.didStart }

        coordinator.setEnabled(false)
        await self.waitUntil { coordinator.state == .disabled }
        await Task.yield()

        #expect(coordinator.state == .disabled)
        #expect(coordinator.nextRetryAt == nil)
    }

    @Test
    func `rapid reenable waits for the prior Serve removal`() async throws {
        let defaults = try #require(UserDefaults(suiteName: "InkUsageHostCoordinatorTests-\(UUID().uuidString)"))
        let tailscale = ResetGateTailscale()
        let server = FakeServer(port: 49156)
        let coordinator = InkUsageHostCoordinator(
            defaults: defaults,
            tokenStore: MemoryTokenStore(),
            tailscale: tailscale,
            healthChecker: HealthyHealthChecker(),
            monitorLifecycle: false,
            snapshotProvider: { Data("{}".utf8) },
            serverFactory: { _ in server })
        coordinator.setEnabled(true)
        await self.waitUntil { await tailscale.reconcileCount == 1 }

        coordinator.setEnabled(false)
        await self.waitUntil { await tailscale.resetStarted }
        coordinator.setEnabled(true)
        await Task.yield()
        #expect(await tailscale.reconcileCount == 1)

        await tailscale.releaseReset()
        await self.waitUntil { coordinator.state == .tailnetReady(host: "mac.tailnet.ts.net") }
        #expect(await tailscale.reconcileCount == 2)
        #expect(coordinator.state == .tailnetReady(host: "mac.tailnet.ts.net"))
        #expect(server.startCount == 2)
    }

    private func waitUntil(_ condition: @escaping @MainActor () async -> Bool) async {
        for _ in 0..<100 {
            if await condition() { return }
            await Task.yield()
        }
    }
}

private final class MemoryTokenStore: ReaderTokenStoring, @unchecked Sendable {
    private let lock = NSLock()
    private var storedToken: String?
    init(token: String? = nil) {
        self.storedToken = token
    }

    var token: String? {
        self.lock.withLock { self.storedToken }
    }

    func load() throws -> String? {
        self.token
    }

    func save(_ token: String) throws {
        self.lock.withLock { self.storedToken = token }
    }

    func delete() throws {
        self.lock.withLock { self.storedToken = nil }
    }
}

private final class FakeServer: InkLoopbackServing, @unchecked Sendable {
    let port: UInt16
    private(set) var startCount = 0
    private(set) var stopCount = 0
    init(port: UInt16) {
        self.port = port
    }

    func start() async throws -> UInt16 {
        self.startCount += 1; return self.port
    }

    func stop() {
        self.stopCount += 1
    }
}

private actor FakeTailscale: InkTailscaleServing {
    private(set) var resetCount = 0
    private(set) var reconcileCount = 0
    func reconcile(localPort: UInt16, now: Date) async throws -> InkTailscaleReconcileResult {
        self.reconcileCount += 1
        return InkTailscaleReconcileResult(dnsName: "mac.tailnet.ts.net", didApplyMapping: false)
    }

    func reset() async {
        self.resetCount += 1
    }
}

private actor BlockingTailscale: InkTailscaleServing {
    private(set) var didStart = false

    func reconcile(localPort: UInt16, now: Date) async throws -> InkTailscaleReconcileResult {
        self.didStart = true
        try await Task.sleep(for: .seconds(60))
        throw InkTailscaleServeError.cliBroken
    }

    func reset() async {}
}

private actor ResetGateTailscale: InkTailscaleServing {
    private(set) var reconcileCount = 0
    private(set) var resetStarted = false
    private var resetContinuation: CheckedContinuation<Void, Never>?

    func reconcile(localPort: UInt16, now: Date) async throws -> InkTailscaleReconcileResult {
        self.reconcileCount += 1
        return InkTailscaleReconcileResult(dnsName: "mac.tailnet.ts.net", didApplyMapping: false)
    }

    func reset() async {
        self.resetStarted = true
        await withCheckedContinuation { continuation in
            self.resetContinuation = continuation
        }
    }

    func releaseReset() {
        self.resetContinuation?.resume()
        self.resetContinuation = nil
    }
}

private struct HealthyHealthChecker: InkUsageHostHealthChecking {
    func check(dnsName: String, token: String) async -> InkUsageHostHealth {
        .healthy
    }
}
