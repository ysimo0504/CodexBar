import CodexBarCore
import Foundation
import Testing

struct InkTailscaleServeTests {
    @Test
    func `status parser requires a running backend and canonicalizes MagicDNS`() throws {
        let data = Data(#"{"BackendState":"Running","Self":{"DNSName":"MacBook.tailnet.ts.net.","Online":true}}"#.utf8)
        let status = try InkTailscaleStatusParser.parse(data)

        #expect(status.backendState == "Running")
        #expect(status.dnsName == "macbook.tailnet.ts.net")
        #expect(status.isConnected)
    }

    @Test
    func `disconnected and malformed status fail closed`() throws {
        let stopped = try InkTailscaleStatusParser.parse(Data(#"{"BackendState":"Stopped"}"#.utf8))
        #expect(!stopped.isConnected)
        #expect(throws: Error.self) {
            try InkTailscaleStatusParser.parse(Data("not-json".utf8))
        }
    }

    @Test
    func `serve verifier requires the exact path and loopback backend`() throws {
        let good = Data(
            #"{"Web":{"macbook.tailnet.ts.net:443":{"Handlers":{"/dashboard/v1/snapshot":{"Proxy":"http://127.0.0.1:49152"}}}}}"#
                .utf8)
        #expect(try InkTailscaleStatusParser.hasExactServeMapping(
            good,
            dnsName: "macbook.tailnet.ts.net",
            localPort: 49152))

        let wildcardPath = Data(
            #"{"Web":{"macbook.tailnet.ts.net:443":{"Handlers":{"/":{"Proxy":"http://127.0.0.1:49152"}}}}}"#
                .utf8)
        #expect(!((try? InkTailscaleStatusParser.hasExactServeMapping(
            wildcardPath,
            dnsName: "macbook.tailnet.ts.net",
            localPort: 49152)) ?? true))

        let wildcardBind = Data(
            #"{"Web":{"macbook.tailnet.ts.net:443":{"Handlers":{"/dashboard/v1/snapshot":{"Proxy":"http://0.0.0.0:49152"}}}}}"#
                .utf8)
        #expect(!((try? InkTailscaleStatusParser.hasExactServeMapping(
            wildcardBind,
            dnsName: "macbook.tailnet.ts.net",
            localPort: 49152)) ?? true))
    }

    @Test
    func `serve command exposes no bearer secret`() {
        let command = InkTailscaleServeCommand.apply(localPort: 49152)
        let joined = command.joined(separator: " ")
        #expect(joined == "serve --bg --yes --https=443 --set-path=/dashboard/v1/snapshot http://127.0.0.1:49152")
        #expect(!joined.contains("Bearer"))
        #expect(!joined.contains("token"))
    }

    @Test
    func `disable command removes only the Ink path mapping`() {
        #expect(InkTailscaleServeCommand.reset == [
            "serve",
            "--https=443",
            "--set-path=/dashboard/v1/snapshot",
            "off",
        ])
        #expect(InkTailscaleServeClient.candidatePaths.contains(
            "/Applications/Tailscale.app/Contents/MacOS/tailscale"))
    }

    @Test
    func `reconcile is idempotent when the exact mapping already exists`() async throws {
        let script = TailscaleCommandScript(mappingInitiallyValid: true)
        let client = InkTailscaleServeClient(binary: "/fixture/tailscale") { _, arguments in
            try await script.run(arguments)
        }
        let result = try await client.reconcile(localPort: 49152, now: Date(timeIntervalSince1970: 0))

        #expect(result == InkTailscaleReconcileResult(
            dnsName: "macbook.tailnet.ts.net",
            didApplyMapping: false))
        #expect(await script.commands.count == 2)
    }

    @Test
    func `reconcile repairs and verifies a mismatched mapping`() async throws {
        let script = TailscaleCommandScript(mappingInitiallyValid: false)
        let client = InkTailscaleServeClient(binary: "/fixture/tailscale") { _, arguments in
            try await script.run(arguments)
        }
        let result = try await client.reconcile(localPort: 49152, now: Date(timeIntervalSince1970: 0))
        let commands = await script.commands

        #expect(result.didApplyMapping)
        #expect(commands.count == 4)
        #expect(commands[2] == InkTailscaleServeCommand.apply(localPort: 49152))
        #expect(!commands.flatMap(\.self).joined().contains("secret"))
    }

    @Test
    func `permission failures keep an actionable non secret diagnosis`() async {
        let client = InkTailscaleServeClient(binary: "/fixture/tailscale") { _, _ in
            throw SubprocessRunnerError.nonZeroExit(code: 1, stderr: "permission denied by policy")
        }

        do {
            _ = try await client.reconcile(localPort: 49152, now: Date())
            Issue.record("Expected permission failure")
        } catch let error as InkTailscaleServeError {
            #expect(error == .permissionDenied)
            #expect(error.diagnostic == "Tailscale Serve permission denied")
        } catch {
            Issue.record("Unexpected error: \(error)")
        }
    }
}

private actor TailscaleCommandScript {
    private let mappingInitiallyValid: Bool
    private(set) var commands: [[String]] = []

    init(mappingInitiallyValid: Bool) {
        self.mappingInitiallyValid = mappingInitiallyValid
    }

    func run(_ arguments: [String]) throws -> SubprocessResult {
        self.commands.append(arguments)
        if arguments == InkTailscaleServeCommand.nodeStatus {
            return SubprocessResult(
                stdout: #"{"BackendState":"Running","Self":{"DNSName":"macbook.tailnet.ts.net.","Online":true}}"#,
                stderr: "")
        }
        if arguments == InkTailscaleServeCommand.serveStatus {
            let shouldBeValid = self.mappingInitiallyValid || self.commands.count >= 4
            let path = shouldBeValid ? "/dashboard/v1/snapshot" : "/wrong"
            return SubprocessResult(
                stdout: #"{"Web":{"macbook.tailnet.ts.net:443":{"Handlers":{"\#(path)":{"Proxy":"http://127.0.0.1:49152"}}}}}"#,
                stderr: "")
        }
        if arguments == InkTailscaleServeCommand.apply(localPort: 49152) {
            return SubprocessResult(stdout: "", stderr: "")
        }
        throw InkTailscaleServeError.cliBroken
    }
}
