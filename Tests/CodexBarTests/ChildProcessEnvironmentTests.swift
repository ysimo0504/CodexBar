import Foundation
import Testing
@testable import CodexBarCore

struct ChildProcessEnvironmentTests {
    @Test
    func `reader credentials are removed while unrelated values survive`() {
        let sanitized = ChildProcessEnvironment.sanitized([
            "CODEXBAR_DASHBOARD_TOKEN": "legacy-secret",
            "CODEXBAR_READER_SECRET_NEXT": "future-secret",
            "CODEXBAR_READER_SECRET_TOKEN_V2": "future-secret-2",
            "CODEXBAR_READER_SETTING": "safe",
            "PATH": "/usr/bin",
        ])

        #expect(sanitized["CODEXBAR_DASHBOARD_TOKEN"] == nil)
        #expect(sanitized["CODEXBAR_READER_SECRET_NEXT"] == nil)
        #expect(sanitized["CODEXBAR_READER_SECRET_TOKEN_V2"] == nil)
        #expect(sanitized["CODEXBAR_READER_SETTING"] == "safe")
        #expect(sanitized["PATH"] == "/usr/bin")
    }

    @Test
    func `subprocess runner applies the secret scrub at its launch seam`() async throws {
        let result = try await SubprocessRunner.run(
            binary: "/usr/bin/env",
            arguments: [],
            environment: [
                "CODEXBAR_DASHBOARD_TOKEN": "legacy-secret",
                "CODEXBAR_READER_SECRET_TEST": "future-secret",
                "SAFE_MARKER": "present",
            ],
            timeout: 5,
            label: "child-environment-scrub-test")

        #expect(result.stdout.contains("SAFE_MARKER=present"))
        #expect(!result.stdout.contains("legacy-secret"))
        #expect(!result.stdout.contains("future-secret"))
        #expect(!result.stdout.contains("CODEXBAR_DASHBOARD_TOKEN"))
        #expect(!result.stdout.contains("CODEXBAR_READER_SECRET_TEST"))
    }

    @Test
    func `tty environment enrichment cannot restore reader credentials`() {
        let environment = TTYCommandRunner.enrichedEnvironment(
            baseEnv: [
                "CODEXBAR_DASHBOARD_TOKEN": "legacy-secret",
                "CODEXBAR_READER_SECRET_TEST": "future-secret",
                "PATH": "/usr/bin",
            ],
            loginPATH: ["/usr/bin"],
            home: "/tmp/codexbar-test-home")

        #expect(environment["CODEXBAR_DASHBOARD_TOKEN"] == nil)
        #expect(environment["CODEXBAR_READER_SECRET_TEST"] == nil)
        #expect(environment["HOME"] == "/tmp/codexbar-test-home")
    }
}
