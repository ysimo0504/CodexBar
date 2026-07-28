import CodexBarCore
import Foundation
import Testing
@testable import CodexBar

@MainActor
struct InkUsageHostCoordinatorTests {
    @Test
    func `enable is idempotent and disable stops the LAN listener`() async throws {
        let suite = "InkUsageHostCoordinatorTests-\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let server = FakeLANServer(port: 43121)
        let coordinator = Self.coordinator(defaults: defaults, server: server)

        coordinator.setEnabled(true)
        coordinator.setEnabled(true)
        await self.waitUntil { coordinator.state == .lanReady(url: "http://192.168.31.42:43121") }

        #expect(server.startCount == 1)
        #expect(defaults.bool(forKey: "inkUsageHostEnabled"))
        #expect(coordinator.hostURL == "http://192.168.31.42:43121")

        coordinator.setEnabled(false)
        #expect(server.stopCount == 1)
        #expect(coordinator.state == .disabled)
        #expect(coordinator.hostURL == nil)
    }

    @Test
    func `sleep wake and private address change restart the listener`() async throws {
        let defaults = try #require(UserDefaults(suiteName: "InkUsageHostCoordinatorTests-\(UUID().uuidString)"))
        let address = MutableAddress("192.168.31.42")
        let server = FakeLANServer(port: 43121)
        let coordinator = Self.coordinator(defaults: defaults, address: address, server: server)
        coordinator.setEnabled(true)
        await self.waitUntil {
            coordinator.state == .lanReady(url: "http://192.168.31.42:43121")
        }

        coordinator.handleWillSleep()
        #expect(coordinator.state == .sleeping)
        #expect(server.stopCount == 1)
        coordinator.handleDidWake()
        await self.waitUntil {
            server.startCount == 2 &&
                coordinator.state == .lanReady(url: "http://192.168.31.42:43121")
        }

        address.value = "10.0.0.8"
        coordinator.handleNetworkAvailable()
        await self.waitUntil {
            coordinator.state == .lanReady(url: "http://10.0.0.8:43121")
        }

        #expect(server.startCount == 3)
        #expect(server.stopCount == 2)
    }

    @Test
    func `public or unavailable addresses fail closed without opening a listener`() throws {
        let defaults = try #require(UserDefaults(suiteName: "InkUsageHostCoordinatorTests-\(UUID().uuidString)"))
        let server = FakeLANServer(port: 43121)
        let coordinator = Self.coordinator(
            defaults: defaults,
            address: MutableAddress("203.0.113.8"),
            server: server)

        coordinator.setEnabled(true)

        #expect(coordinator.state == .degraded("Private LAN unavailable"))
        #expect(server.startCount == 0)
        #expect(coordinator.nextRetryAt != nil)
    }

    @Test
    func `disable cannot be overwritten by a cancelled listener start`() async throws {
        let defaults = try #require(UserDefaults(suiteName: "InkUsageHostCoordinatorTests-\(UUID().uuidString)"))
        let server = BlockingLANServer()
        let coordinator = InkUsageHostCoordinator(
            defaults: defaults,
            addressProvider: { "192.168.31.42" },
            monitorLifecycle: false,
            snapshotProvider: { Data("{}".utf8) },
            serverFactory: { _ in server })
        coordinator.setEnabled(true)
        await self.waitUntil { server.didStart }

        coordinator.setEnabled(false)
        await self.waitUntil { coordinator.state == .disabled }
        await Task.yield()

        #expect(coordinator.state == .disabled)
        #expect(coordinator.nextRetryAt == nil)
    }

    @Test
    func `network availability does not restart an in flight listener`() async throws {
        let defaults = try #require(UserDefaults(suiteName: "InkUsageHostCoordinatorTests-\(UUID().uuidString)"))
        let server = BlockingLANServer()
        let coordinator = InkUsageHostCoordinator(
            defaults: defaults,
            addressProvider: { "192.168.31.42" },
            monitorLifecycle: false,
            snapshotProvider: { Data("{}".utf8) },
            serverFactory: { _ in server })
        coordinator.setEnabled(true)
        await self.waitUntil { server.didStart }

        coordinator.handleNetworkAvailable()
        await Task.yield()

        #expect(server.startCount == 1)
        coordinator.setEnabled(false)
    }

    private static func coordinator(
        defaults: UserDefaults,
        address: MutableAddress = MutableAddress("192.168.31.42"),
        server: FakeLANServer) -> InkUsageHostCoordinator
    {
        InkUsageHostCoordinator(
            defaults: defaults,
            addressProvider: { address.value },
            monitorLifecycle: false,
            snapshotProvider: { Data("{}".utf8) },
            serverFactory: { _ in server })
    }

    private func waitUntil(_ condition: @escaping @MainActor () async -> Bool) async {
        for _ in 0..<200 {
            if await condition() { return }
            await Task.yield()
        }
    }
}

private final class MutableAddress: @unchecked Sendable {
    private let lock = NSLock()
    private var storage: String?

    init(_ value: String?) {
        self.storage = value
    }

    var value: String? {
        get { self.lock.withLock { self.storage } }
        set { self.lock.withLock { self.storage = newValue } }
    }
}

private final class FakeLANServer: InkLANHTTPServing, @unchecked Sendable {
    let port: UInt16
    private(set) var startCount = 0
    private(set) var stopCount = 0

    init(port: UInt16) {
        self.port = port
    }

    func start(address: String) async throws -> InkLANEndpoint {
        self.startCount += 1
        return InkLANEndpoint(address: address, port: self.port)
    }

    func stop() {
        self.stopCount += 1
    }
}

private final class BlockingLANServer: InkLANHTTPServing, @unchecked Sendable {
    private let lock = NSLock()
    private var started = false
    private var starts = 0

    var didStart: Bool {
        self.lock.withLock { self.started }
    }

    var startCount: Int {
        self.lock.withLock { self.starts }
    }

    func start(address: String) async throws -> InkLANEndpoint {
        self.lock.withLock {
            self.started = true
            self.starts += 1
        }
        try await Task.sleep(for: .seconds(60))
        return InkLANEndpoint(address: address, port: 43121)
    }

    func stop() {}
}
