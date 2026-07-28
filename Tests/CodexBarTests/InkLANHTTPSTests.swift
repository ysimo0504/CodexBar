import CodexBarCore
import Foundation
import Testing

struct InkLANHTTPTests {
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
    func `LAN HTTP listener serves the snapshot without credentials`() async throws {
        let address = try #require(InkPrivateLANAddress.currentIPv4())
        let fixture = Data(#"{"schemaVersion":1}"#.utf8)
        let port = UInt16.random(in: 50000...60000)
        let gateway = InkUsageHostGateway(externalHost: "\(address):\(port)") { fixture }
        let server = InkLANHTTPServer(gateway: gateway, port: port)
        let endpoint = try await server.start(address: address)
        defer { server.stop() }
        let url = try #require(URL(string: "\(endpoint.baseURL)/dashboard/v1/snapshot"))

        let (body, response) = try await URLSession.shared.data(from: url)

        #expect((response as? HTTPURLResponse)?.statusCode == 200)
        #expect(body == fixture)
        #expect(endpoint.baseURL.hasPrefix("http://"))
    }
}
