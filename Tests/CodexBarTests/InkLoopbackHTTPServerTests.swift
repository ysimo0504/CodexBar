import CodexBarCore
import Foundation
import Testing

struct InkLoopbackHTTPServerTests {
    @Test
    func `listener binds loopback and serves only authenticated snapshot`() async throws {
        let token = String(repeating: "x", count: 43)
        let fixture = Data(#"{"schemaVersion":1}"#.utf8)
        let gateway = InkUsageHostGateway(token: token) { fixture }
        let server = InkLoopbackHTTPServer(gateway: gateway)
        let port = try await server.start()
        defer { server.stop() }
        let url = try #require(URL(string: "http://127.0.0.1:\(port)/dashboard/v1/snapshot"))

        var authorized = URLRequest(url: url)
        authorized.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let (body, response) = try await URLSession.shared.data(for: authorized)
        #expect((response as? HTTPURLResponse)?.statusCode == 200)
        #expect(body == fixture)

        let (_, rejected) = try await URLSession.shared.data(from: url)
        #expect((rejected as? HTTPURLResponse)?.statusCode == 401)
    }
}
