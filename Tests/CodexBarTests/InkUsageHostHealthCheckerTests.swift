import CodexBarCore
import Foundation
import Testing

struct InkUsageHostHealthCheckerTests {
    @Test
    func `health request uses exact HTTPS snapshot path and bearer header`() async {
        let token = String(repeating: "s", count: 43)
        let checker = InkUsageHostHealthChecker { request in
            #expect(request.url?.absoluteString == "https://mac.tailnet.ts.net/dashboard/v1/snapshot")
            #expect(request.value(forHTTPHeaderField: "Authorization") == "Bearer \(token)")
            return 200
        }
        #expect(await checker.check(dnsName: "mac.tailnet.ts.net", token: token) == .healthy)
    }

    @Test(arguments: [(401, InkUsageHostHealth.unauthorized), (403, .forbidden), (500, .unavailable)])
    func `classifies HTTP status`(argument: (Int, InkUsageHostHealth)) async {
        let checker = InkUsageHostHealthChecker { _ in argument.0 }
        #expect(await checker.check(dnsName: "mac.tailnet.ts.net", token: "secret") == argument.1)
    }

    @Test
    func `TLS errors have a non secret diagnostic`() async {
        let token = "top-secret-reader-token"
        let checker = InkUsageHostHealthChecker { _ in
            throw URLError(.serverCertificateUntrusted)
        }
        let result = await checker.check(dnsName: "mac.tailnet.ts.net", token: token)
        #expect(result == .tlsFailure)
        #expect(!result.diagnostic.contains(token))
    }
}
