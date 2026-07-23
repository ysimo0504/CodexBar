import Foundation

public struct InkTailscaleNodeStatus: Equatable, Sendable {
    public let backendState: String
    public let dnsName: String?
    public let isOnline: Bool
    public let keyExpiry: Date?

    public var isConnected: Bool {
        self.backendState == "Running" && self.isOnline && self.dnsName != nil
    }
}

public enum InkTailscaleStatusParser {
    public static func parse(_ data: Data) throws -> InkTailscaleNodeStatus {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw InkTailscaleServeError.invalidStatus
        }
        let backendState = root["BackendState"] as? String ?? "Unknown"
        let local = root["Self"] as? [String: Any]
        let dnsName = self.canonicalDNSName(local?["DNSName"] as? String)
        let online = (local?["Online"] as? Bool) ?? (backendState == "Running")
        let expiry = (local?["KeyExpiry"] as? String).flatMap(self.parseDate)
        return InkTailscaleNodeStatus(
            backendState: backendState,
            dnsName: dnsName,
            isOnline: online,
            keyExpiry: expiry)
    }

    public static func hasExactServeMapping(_ data: Data, dnsName: String, localPort: UInt16) throws -> Bool {
        let object = try JSONSerialization.jsonObject(with: data)
        let expectedHost = "\(dnsName.lowercased()):443"
        let expectedPath = InkUsageHostGateway.snapshotPath
        let expectedBackend = "http://127.0.0.1:\(localPort)"
        return self.containsExactMapping(
            object,
            host: expectedHost,
            path: expectedPath,
            backend: expectedBackend)
    }

    private static func containsExactMapping(_ value: Any, host: String, path: String, backend: String) -> Bool {
        guard let root = value as? [String: Any],
              let web = root["Web"] as? [String: Any]
        else {
            return false
        }
        let site = web.first { key, _ in key.lowercased() == host }?.value as? [String: Any]
        guard let handlers = site?["Handlers"] as? [String: Any],
              let handler = handlers[path] as? [String: Any]
        else {
            return false
        }
        return (handler["Proxy"] as? String) == backend
    }

    private static func canonicalDNSName(_ raw: String?) -> String? {
        guard var value = raw?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased(), !value.isEmpty else {
            return nil
        }
        while value.hasSuffix(".") {
            value.removeLast()
        }
        return value.isEmpty ? nil : value
    }

    private static func parseDate(_ value: String) -> Date? {
        ISO8601DateFormatter().date(from: value)
    }
}

public enum InkTailscaleServeCommand {
    public static func apply(localPort: UInt16) -> [String] {
        [
            "serve",
            "--bg",
            "--yes",
            "--https=443",
            "--set-path=\(InkUsageHostGateway.snapshotPath)",
            "http://127.0.0.1:\(localPort)",
        ]
    }

    public static let reset = [
        "serve",
        "--https=443",
        "--set-path=\(InkUsageHostGateway.snapshotPath)",
        "off",
    ]
    public static let nodeStatus = ["status", "--json"]
    public static let serveStatus = ["serve", "status", "--json"]
}

public enum InkTailscaleServeError: Error, Equatable, Sendable {
    case cliMissing
    case cliBroken
    case invalidStatus
    case backendDisconnected
    case keyExpired
    case dnsNameMissing
    case mappingMismatch
    case permissionDenied

    public var diagnostic: String {
        switch self {
        case .cliMissing: "Tailscale CLI not found"
        case .cliBroken: "Tailscale CLI unavailable"
        case .invalidStatus: "Tailscale status unavailable"
        case .backendDisconnected: "Tailscale is disconnected"
        case .keyExpired: "Tailscale key expired"
        case .dnsNameMissing: "MagicDNS hostname unavailable"
        case .mappingMismatch: "Tailscale Serve mapping mismatch"
        case .permissionDenied: "Tailscale Serve permission denied"
        }
    }
}

public struct InkTailscaleReconcileResult: Equatable, Sendable {
    public let dnsName: String
    public let didApplyMapping: Bool

    public init(dnsName: String, didApplyMapping: Bool) {
        self.dnsName = dnsName
        self.didApplyMapping = didApplyMapping
    }
}

public protocol InkTailscaleServing: Sendable {
    func reconcile(localPort: UInt16, now: Date) async throws -> InkTailscaleReconcileResult
    func reset() async
}

public actor InkTailscaleServeClient: InkTailscaleServing {
    public typealias Runner = @Sendable (_ binary: String, _ arguments: [String]) async throws -> SubprocessResult

    package static let candidatePaths = [
        "/Applications/Tailscale.app/Contents/MacOS/tailscale",
        "/Applications/Tailscale.app/Contents/MacOS/Tailscale",
        "/usr/local/bin/tailscale",
        "/opt/homebrew/bin/tailscale",
    ]

    private let binary: String?
    private let runner: Runner

    public init(
        binary: String? = InkTailscaleServeClient.discoverBinary(),
        runner: @escaping Runner = InkTailscaleServeClient.liveRunner)
    {
        self.binary = binary
        self.runner = runner
    }

    public func reconcile(localPort: UInt16, now: Date) async throws -> InkTailscaleReconcileResult {
        guard let binary else { throw InkTailscaleServeError.cliMissing }
        let nodeResult: SubprocessResult
        do {
            nodeResult = try await self.runner(binary, InkTailscaleServeCommand.nodeStatus)
        } catch {
            throw Self.classify(error, fallback: .cliBroken)
        }
        let status: InkTailscaleNodeStatus
        do {
            status = try InkTailscaleStatusParser.parse(Data(nodeResult.stdout.utf8))
        } catch {
            throw InkTailscaleServeError.invalidStatus
        }
        guard status.backendState == "Running", status.isOnline else {
            throw InkTailscaleServeError.backendDisconnected
        }
        if let expiry = status.keyExpiry, expiry <= now { throw InkTailscaleServeError.keyExpired }
        guard let dnsName = status.dnsName else { throw InkTailscaleServeError.dnsNameMissing }

        let current = try? await self.runner(binary, InkTailscaleServeCommand.serveStatus)
        if let current,
           (try? InkTailscaleStatusParser.hasExactServeMapping(
               Data(current.stdout.utf8),
               dnsName: dnsName,
               localPort: localPort)) == true
        {
            return InkTailscaleReconcileResult(dnsName: dnsName, didApplyMapping: false)
        }
        do {
            _ = try await self.runner(binary, InkTailscaleServeCommand.apply(localPort: localPort))
            let verified = try await self.runner(binary, InkTailscaleServeCommand.serveStatus)
            guard try InkTailscaleStatusParser.hasExactServeMapping(
                Data(verified.stdout.utf8),
                dnsName: dnsName,
                localPort: localPort)
            else {
                throw InkTailscaleServeError.mappingMismatch
            }
        } catch let error as InkTailscaleServeError {
            throw error
        } catch {
            throw Self.classify(error, fallback: .mappingMismatch)
        }
        return InkTailscaleReconcileResult(dnsName: dnsName, didApplyMapping: true)
    }

    public func reset() async {
        guard let binary else { return }
        _ = try? await self.runner(binary, InkTailscaleServeCommand.reset)
    }

    public static func discoverBinary(fileManager: FileManager = .default) -> String? {
        self.candidatePaths.first(where: fileManager.isExecutableFile(atPath:))
    }

    public static func liveRunner(binary: String, arguments: [String]) async throws -> SubprocessResult {
        try await SubprocessRunner.run(
            binary: binary,
            arguments: arguments,
            environment: ProcessInfo.processInfo.environment,
            timeout: 15,
            maxOutputBytes: 512 * 1024,
            label: "ink-tailscale")
    }

    private static func classify(
        _ error: Error,
        fallback: InkTailscaleServeError) -> InkTailscaleServeError
    {
        guard let processError = error as? SubprocessRunnerError,
              case let .nonZeroExit(_, stderr) = processError
        else {
            return fallback
        }
        let message = stderr.lowercased()
        let permissionMarkers = ["permission", "access denied", "not permitted", "requires sudo"]
        return permissionMarkers.contains(where: message.contains) ? .permissionDenied : fallback
    }
}
