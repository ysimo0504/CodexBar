import CodexBarCore
import Foundation
import Testing
@testable import CodexBar

@MainActor
struct InkDashboardSnapshotTests {
    @Test
    func `snapshot is schema v1 and redacts identity and diagnostics`() throws {
        let record = InkDashboardSnapshot.Record(
            provider: .codex,
            name: "Codex",
            source: "oauth",
            status: nil,
            snapshot: UsageSnapshot(
                primary: RateWindow(
                    usedPercent: 25,
                    windowMinutes: 300,
                    resetsAt: nil,
                    resetDescription: nil),
                secondary: nil,
                updatedAt: Date(timeIntervalSince1970: 1_700_000_000),
                identity: ProviderIdentitySnapshot(
                    providerID: .codex,
                    accountEmail: "private@example.com",
                    accountOrganization: "Secret Org",
                    loginMethod: "plus")),
            credits: CreditsSnapshot(
                remaining: 12.5,
                events: [],
                updatedAt: Date(timeIntervalSince1970: 1_700_000_000)),
            hasError: true,
            sortKey: 0)
        let data = try InkDashboardSnapshot.encode(
            records: [record],
            generatedAt: Date(timeIntervalSince1970: 1_700_000_100),
            refreshSeconds: 60,
            appVersion: "1.2.3")
        let text = try #require(String(data: data, encoding: .utf8))
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let providers = json["providers"] as? [[String: Any]],
              let provider = providers.first,
              let identity = provider["identity"] as? [String: Any],
              let error = provider["error"] as? [String: Any]
        else {
            Issue.record("Snapshot shape is invalid")
            return
        }

        #expect(json["schemaVersion"] as? Int == 1)
        #expect(json["staleAfterSeconds"] as? Int == 180)
        #expect(identity["accountEmail"] as? String == "redacted@example.com")
        #expect(!text.contains("private@example.com"))
        #expect(!text.contains("Secret Org"))
        #expect(!text.contains("raw failure"))
        #expect(error["reason"] as? String == "provider-unavailable")
    }
}
