import CodexBarCore
import CryptoKit
import Foundation
import Security
import Testing

struct InkLANHTTPSTests {
    @Test(arguments: [
        "10.0.0.1",
        "10.255.255.254",
        "172.16.0.1",
        "172.31.255.254",
        "192.168.0.1",
        "169.254.1.1",
    ])
    func `accepts only supported private or link local IPv4 addresses`(address: String) {
        #expect(InkPrivateLANAddress.isAllowedIPv4(address))
    }

    @Test(arguments: [
        "127.0.0.1",
        "172.15.0.1",
        "172.32.0.1",
        "192.0.2.1",
        "8.8.8.8",
        "100.64.0.1",
        "192.168.1",
        "192.168.1.999",
        "not-an-address",
    ])
    func `rejects loopback public overlay and malformed IPv4 addresses`(address: String) {
        #expect(!InkPrivateLANAddress.isAllowedIPv4(address))
    }

    @Test
    func `generated TLS identity is stable private and pinned`() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("InkLANHTTPSTests-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = FileInkTLSIdentityStore(directory: root)

        let first = try store.loadOrCreate()
        let second = try store.loadOrCreate()
        let identityURL = root.appendingPathComponent("identity.p12")
        let metadataURL = root.appendingPathComponent("identity.json")
        let identityPermissions = try #require(
            FileManager.default.attributesOfItem(atPath: identityURL.path)[.posixPermissions] as? NSNumber)
        let metadataPermissions = try #require(
            FileManager.default.attributesOfItem(atPath: metadataURL.path)[.posixPermissions] as? NSNumber)

        #expect(first.certificateSHA256.count == 64)
        let certificatePinIsHex = first.certificateSHA256.allSatisfy { character in
            character.isHexDigit
        }
        #expect(certificatePinIsHex)
        #expect(first.certificateSHA256 == second.certificateSHA256)
        #expect(first.hostID == second.hostID)
        #expect(identityPermissions.intValue & 0o777 == 0o600)
        #expect(metadataPermissions.intValue & 0o777 == 0o600)
    }

    @Test
    func `LAN TLS listener serves only through the paired certificate and gateway`() async throws {
        let address = try #require(InkPrivateLANAddress.currentIPv4())
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("InkLANHTTPSTests-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let identity = try FileInkTLSIdentityStore(directory: root).loadOrCreate()
        let token = "fixture-reader-token-that-is-long-enough"
        let fixture = Data(#"{"schemaVersion":1}"#.utf8)
        let port = UInt16.random(in: 50000...60000)
        let gateway = InkUsageHostGateway(token: token, externalHost: "\(address):\(port)") { fixture }
        let server = InkLANHTTPSServer(gateway: gateway, port: port)
        let endpoint = try await server.start(identity: identity, address: address)
        defer { server.stop() }
        let url = try #require(URL(string: "\(endpoint.baseURL)/dashboard/v1/snapshot"))
        let session = URLSession(
            configuration: .ephemeral,
            delegate: PinnedCertificateSessionDelegate(pin: identity.certificateSHA256),
            delegateQueue: nil)
        defer { session.invalidateAndCancel() }

        var request = URLRequest(url: url)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let (body, response) = try await session.data(for: request)

        #expect((response as? HTTPURLResponse)?.statusCode == 200)
        #expect(body == fixture)

        let loopbackURL = try #require(URL(string: "https://127.0.0.1:\(port)/dashboard/v1/snapshot"))
        var loopbackRequest = URLRequest(url: loopbackURL)
        loopbackRequest.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        await #expect(throws: (any Error).self) {
            _ = try await session.data(for: loopbackRequest)
        }

        let wrongPinSession = URLSession(
            configuration: .ephemeral,
            delegate: PinnedCertificateSessionDelegate(pin: String(repeating: "0", count: 64)),
            delegateQueue: nil)
        defer { wrongPinSession.invalidateAndCancel() }
        await #expect(throws: (any Error).self) {
            _ = try await wrongPinSession.data(for: request)
        }
    }
}

private final class PinnedCertificateSessionDelegate: NSObject, URLSessionDelegate, @unchecked Sendable {
    private let pin: String

    init(pin: String) {
        self.pin = pin
    }

    func urlSession(
        _ session: URLSession,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping @Sendable (URLSession.AuthChallengeDisposition, URLCredential?) -> Void)
    {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              let trust = challenge.protectionSpace.serverTrust,
              let certificates = SecTrustCopyCertificateChain(trust) as? [SecCertificate],
              let certificate = certificates.first
        else {
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }
        let digest = SHA256.hash(data: SecCertificateCopyData(certificate) as Data)
        let actual = digest.map { String(format: "%02x", $0) }.joined()
        guard actual == self.pin else {
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }
        completionHandler(.useCredential, URLCredential(trust: trust))
    }
}
