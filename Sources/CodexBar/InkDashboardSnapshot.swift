import CodexBarCore
import Foundation

@MainActor
enum InkDashboardSnapshot {
    struct Record {
        let provider: UsageProvider
        let name: String
        let source: String
        let status: ProviderStatus?
        let snapshot: UsageSnapshot?
        let credits: CreditsSnapshot?
        let hasError: Bool
        let sortKey: Int
    }

    static func encode(store: UsageStore, settings: SettingsStore, appVersion: String?) throws -> Data {
        let records = store.enabledProvidersForDisplay().enumerated().map { index, provider in
            Record(
                provider: provider,
                name: store.metadata(for: provider).displayName,
                source: store.sourceLabel(for: provider),
                status: store.status(for: provider),
                snapshot: store.presentationSnapshot(for: provider),
                credits: provider == .codex ? store.credits : nil,
                hasError: store.error(for: provider) != nil,
                sortKey: index * 10)
        }
        return try self.encode(
            records: records,
            generatedAt: Date(),
            refreshSeconds: Int(settings.refreshFrequency.seconds ?? 0),
            appVersion: appVersion)
    }

    static func encode(
        records: [Record],
        generatedAt: Date,
        refreshSeconds: Int,
        appVersion: String?) throws -> Data
    {
        let payload = Payload(
            schemaVersion: 1,
            generatedAt: generatedAt,
            staleAfterSeconds: max(180, refreshSeconds * 3),
            host: Host(codexBarVersion: appVersion, refreshIntervalSeconds: refreshSeconds),
            providers: records.map(self.providerPayload))
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.sortedKeys]
        return try encoder.encode(payload)
    }

    private static func providerPayload(_ record: Record) -> Provider {
        let snapshot = record.snapshot
        let identity = snapshot?.identity(for: record.provider)
        let windows = self.windows(snapshot: snapshot, provider: record.provider)
        let status = record.status.map { value in
            let level = switch value.indicator {
            case .none: "ok"
            case .minor, .maintenance: "warning"
            case .major, .critical: "critical"
            case .unknown: "unknown"
            }
            return Status(
                level: level,
                label: value.indicator.label,
                updatedAt: value.updatedAt)
        }
        return Provider(
            id: record.provider.rawValue,
            name: record.name,
            enabled: true,
            source: record.source.isEmpty ? "unknown" : record.source,
            status: status,
            identity: self.identity(identity),
            windows: windows,
            credits: record.credits.map { Credits(remaining: $0.remaining, unit: "credits") },
            cost: nil,
            display: Display(accentColor: "#6E6E6E", sortKey: record.sortKey, priority: "normal"),
            error: record.hasError ? ErrorPayload(
                code: 1,
                message: "Temporarily unavailable",
                kind: "runtime",
                reason: "provider-unavailable") : nil,
            updatedAt: [snapshot?.updatedAt, record.credits?.updatedAt, record.status?.updatedAt]
                .compactMap(\.self).max())
    }

    private static func identity(_ identity: ProviderIdentitySnapshot?) -> Identity? {
        guard let identity else { return nil }
        let email: String? = if let value = identity.accountEmail,
                                let at = value.lastIndex(of: "@")
        {
            "redacted\(value[at...])"
        } else if identity.accountEmail != nil {
            "redacted"
        } else {
            nil
        }
        guard email != nil || identity.loginMethod != nil else { return nil }
        return Identity(accountEmail: email, plan: identity.loginMethod)
    }

    private static func windows(snapshot: UsageSnapshot?, provider: UsageProvider) -> [Window] {
        guard let snapshot else { return [] }
        let metadata = ProviderDescriptorRegistry.descriptor(for: provider).metadata
        var values: [(String, String, RateWindow)] = []
        if let primary = snapshot.primary { values.append(("session", metadata.sessionLabel, primary)) }
        if let secondary = snapshot.secondary { values.append(("weekly", metadata.weeklyLabel, secondary)) }
        if let tertiary = snapshot.tertiary { values.append(("tertiary", metadata.opusLabel ?? "Tertiary", tertiary)) }
        values += (snapshot.extraRateWindows ?? []).map { ($0.id, $0.title, $0.window) }
        return values.map { kind, label, value in
            let used = min(100, max(0, value.usedPercent))
            return Window(
                kind: kind,
                label: label,
                usedPercent: used,
                remainingPercent: 100 - used,
                resetAt: value.resetsAt)
        }
    }

    private struct Payload: Encodable {
        let schemaVersion: Int
        let generatedAt: Date
        let staleAfterSeconds: Int
        let host: Host
        let providers: [Provider]
    }

    private struct Host: Encodable {
        let codexBarVersion: String?
        let refreshIntervalSeconds: Int
    }

    private struct Provider: Encodable {
        let id: String
        let name: String
        let enabled: Bool
        let source: String
        let status: Status?
        let identity: Identity?
        let windows: [Window]
        let credits: Credits?
        let cost: Cost?
        let display: Display
        let error: ErrorPayload?
        let updatedAt: Date?
    }

    private struct Status: Encodable { let level: String; let label: String; let updatedAt: Date? }
    private struct Identity: Encodable { let accountEmail: String?; let plan: String? }
    private struct Window: Encodable {
        let kind: String
        let label: String
        let usedPercent: Double
        let remainingPercent: Double
        let resetAt: Date?
    }

    private struct Credits: Encodable { let remaining: Double; let unit: String }
    private struct Cost: Encodable { let todayUSD: Double?; let last30DaysUSD: Double? }
    private struct Display: Encodable { let accentColor: String; let sortKey: Int; let priority: String }
    private struct ErrorPayload: Encodable { let code: Int; let message: String; let kind: String; let reason: String }
}
