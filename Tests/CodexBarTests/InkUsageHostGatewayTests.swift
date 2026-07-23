import Foundation
import Testing
@testable import CodexBarCore

struct InkUsageHostGatewayTests {
    private static let token = "fixture-reader-token-that-is-long-enough"
    private static let fixture = Data(#"{"schemaVersion":1,"providers":[]}"#.utf8)

    @Test
    func `only authenticated snapshot route returns the fixture`() async {
        let gateway = Self.gateway()
        let response = await gateway.handle(Self.request())

        #expect(response.statusCode == 200)
        #expect(response.body == Self.fixture)
        #expect(Self.header("Cache-Control", in: response) == "no-store")
    }

    @Test(arguments: [
        "/usage",
        "/cost",
        "/health",
        "/dashboard/v1/missing",
        "/dashboard/v1/snapshot?token=forbidden",
        "/",
    ])
    func `all non snapshot paths fail closed`(path: String) async {
        let response = await Self.gateway().handle(Self.request(target: path))
        #expect(response.statusCode == 404)
        #expect(response.body == Data(#"{"error":"not-found"}"#.utf8))
    }

    @Test(arguments: ["get", "POST", "PUT", "DELETE", "HEAD", "OPTIONS"])
    func `all non get snapshot methods are rejected`(method: String) async {
        let response = await Self.gateway().handle(Self.request(method: method))
        #expect(response.statusCode == 405)
    }

    @Test
    func `missing wrong query and malformed credentials are rejected`() async {
        let gateway = Self.gateway()
        let missing = await gateway.handle(Self.request(authorization: nil))
        let wrong = await gateway.handle(Self.request(authorization: "Bearer wrong"))
        let basic = await gateway.handle(Self.request(authorization: "Basic \(Self.token)"))

        for response in [missing, wrong, basic] {
            #expect(response.statusCode == 401)
            #expect(Self.header("WWW-Authenticate", in: response) == "Bearer")
        }
    }

    @Test
    func `duplicate host and authorization fail before snapshot production`() async {
        let calls = LockedCounter()
        let gateway = InkUsageHostGateway(token: Self.token) {
            calls.increment()
            return Self.fixture
        }
        let duplicateHost = await gateway.handle(InkUsageHostRequest(
            method: "GET",
            target: InkUsageHostGateway.snapshotPath,
            headers: [
                ("Host", "127.0.0.1"),
                ("Host", "localhost"),
                ("Authorization", "Bearer \(Self.token)"),
            ]))
        let duplicateAuthorization = await gateway.handle(InkUsageHostRequest(
            method: "GET",
            target: InkUsageHostGateway.snapshotPath,
            headers: [
                ("Host", "127.0.0.1"),
                ("Authorization", "Bearer \(Self.token)"),
                ("authorization", "Bearer \(Self.token)"),
            ]))

        #expect(duplicateHost.statusCode == 400)
        #expect(duplicateAuthorization.statusCode == 400)
        #expect(calls.value == 0)
    }

    @Test
    func `host policy accepts only loopback or the exact MagicDNS name`() async {
        let gateway = Self.gateway(externalHost: "mac.example.ts.net.")
        let loopback = await gateway.handle(Self.request(host: "127.0.0.1:43123"))
        let magicDNS = await gateway.handle(Self.request(host: "mac.example.ts.net"))
        let magicDNS443 = await gateway.handle(Self.request(host: "mac.example.ts.net:443"))
        let evil = await gateway.handle(Self.request(host: "evil.example.ts.net"))
        let suffixAttack = await gateway.handle(Self.request(host: "mac.example.ts.net.evil.test"))
        let wrongPort = await gateway.handle(Self.request(host: "mac.example.ts.net:8443"))
        let forwarded = await gateway.handle(Self.request(
            host: "evil.test",
            extraHeaders: [("X-Forwarded-Host", "mac.example.ts.net")]))

        #expect(loopback.statusCode == 200)
        #expect(magicDNS.statusCode == 200)
        #expect(magicDNS443.statusCode == 200)
        for response in [evil, suffixAttack, wrongPort, forwarded] {
            #expect(response.statusCode == 403)
        }
    }

    @Test
    func `private LAN authority requires the exact paired address and port`() async {
        let gateway = Self.gateway(externalHost: "192.168.31.42:43121")
        let exact = await gateway.handle(Self.request(host: "192.168.31.42:43121"))
        let missingPort = await gateway.handle(Self.request(host: "192.168.31.42"))
        let wrongPort = await gateway.handle(Self.request(host: "192.168.31.42:43122"))
        let wrongAddress = await gateway.handle(Self.request(host: "192.168.31.43:43121"))

        #expect(exact.statusCode == 200)
        for response in [missingPort, wrongPort, wrongAddress] {
            #expect(response.statusCode == 403)
        }
    }

    @Test
    func `token rotation invalidates the previous token without restarting the gateway`() async {
        let gateway = Self.gateway()
        let previous = await gateway.handle(Self.request())
        await gateway.updateToken("replacement-reader-token-that-is-long-enough")
        let oldAfterRotation = await gateway.handle(Self.request())
        let replacement = await gateway.handle(Self.request(
            authorization: "Bearer replacement-reader-token-that-is-long-enough"))

        #expect(previous.statusCode == 200)
        #expect(oldAfterRotation.statusCode == 401)
        #expect(replacement.statusCode == 200)
    }

    @Test
    func `snapshot failures expose only a safe classification`() async throws {
        struct SensitiveFailure: Error {}
        let gateway = InkUsageHostGateway(token: Self.token) {
            throw SensitiveFailure()
        }
        let response = await gateway.handle(Self.request())
        let body = try #require(String(data: response.body, encoding: .utf8))

        #expect(response.statusCode == 500)
        #expect(body == #"{"error":"snapshot-unavailable"}"#)
        #expect(!body.contains(Self.token))
    }

    private static func gateway(externalHost: String? = nil) -> InkUsageHostGateway {
        InkUsageHostGateway(token: self.token, externalHost: externalHost) {
            Self.fixture
        }
    }

    private static func request(
        method: String = "GET",
        target: String = InkUsageHostGateway.snapshotPath,
        host: String = "127.0.0.1",
        authorization: String? = "Bearer \(token)",
        extraHeaders: [(String, String)] = []) -> InkUsageHostRequest
    {
        var headers = [("Host", host)] + extraHeaders
        if let authorization {
            headers.append(("Authorization", authorization))
        }
        return InkUsageHostRequest(method: method, target: target, headers: headers)
    }

    private static func header(_ name: String, in response: InkUsageHostResponse) -> String? {
        response.headers.first { $0.0.caseInsensitiveCompare(name) == .orderedSame }?.1
    }
}

private final class LockedCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var storage = 0

    var value: Int {
        self.lock.withLock { self.storage }
    }

    func increment() {
        self.lock.withLock { self.storage += 1 }
    }
}
