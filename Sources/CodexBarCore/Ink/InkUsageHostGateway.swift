import Foundation

public struct InkUsageHostRequest: Sendable {
    public let method: String
    public let target: String
    public let headers: [(String, String)]

    public init(method: String, target: String, headers: [(String, String)]) {
        self.method = method
        self.target = target
        self.headers = headers
    }
}

public struct InkUsageHostResponse: Sendable {
    public let statusCode: Int
    public let reason: String
    public let body: Data
    public let headers: [(String, String)]

    public init(statusCode: Int, reason: String, body: Data, headers: [(String, String)] = []) {
        self.statusCode = statusCode
        self.reason = reason
        self.body = body
        self.headers = headers
    }

    public var serialized: Data {
        var header = "HTTP/1.1 \(self.statusCode) \(self.reason)\r\n"
        header += "Content-Type: application/json; charset=utf-8\r\n"
        header += "Content-Length: \(self.body.count)\r\n"
        header += "Connection: close\r\n"
        for (name, value) in self.headers {
            header += "\(name): \(value)\r\n"
        }
        header += "\r\n"
        var data = Data(header.utf8)
        data.append(self.body)
        return data
    }
}

public actor InkUsageHostGateway {
    public typealias SnapshotProvider = @Sendable () async throws -> Data

    public static let snapshotPath = "/dashboard/v1/snapshot"

    private var token: String
    private var externalAuthority: (name: String, port: Int?)?
    private let snapshotProvider: SnapshotProvider

    public init(token: String, externalHost: String? = nil, snapshotProvider: @escaping SnapshotProvider) {
        self.token = token
        self.externalAuthority = Self.canonicalExternalAuthority(externalHost)
        self.snapshotProvider = snapshotProvider
    }

    public func updateToken(_ token: String) {
        self.token = token
    }

    public func updateExternalHost(_ host: String?) {
        self.externalAuthority = Self.canonicalExternalAuthority(host)
    }

    public func handle(_ request: InkUsageHostRequest) async -> InkUsageHostResponse {
        let hostHeaders = Self.headerValues(named: "host", in: request.headers)
        guard hostHeaders.count == 1 else {
            return Self.error(status: 400, reason: "Bad Request", code: "invalid-request")
        }
        guard self.isAllowedHost(hostHeaders[0]) else {
            return Self.error(status: 403, reason: "Forbidden", code: "forbidden-host")
        }

        let authorizationHeaders = Self.headerValues(named: "authorization", in: request.headers)
        guard authorizationHeaders.count <= 1 else {
            return Self.error(status: 400, reason: "Bad Request", code: "invalid-request")
        }

        guard request.target == Self.snapshotPath else {
            return Self.error(status: 404, reason: "Not Found", code: "not-found")
        }
        guard request.method == "GET" else {
            return Self.error(status: 405, reason: "Method Not Allowed", code: "method-not-allowed")
        }
        guard let authorization = authorizationHeaders.first,
              Self.isAuthorized(authorization, token: self.token)
        else {
            return Self.error(
                status: 401,
                reason: "Unauthorized",
                code: "unauthorized",
                extraHeaders: [("WWW-Authenticate", "Bearer")])
        }

        do {
            let snapshot = try await self.snapshotProvider()
            return InkUsageHostResponse(
                statusCode: 200,
                reason: "OK",
                body: snapshot,
                headers: [("Cache-Control", "no-store")])
        } catch {
            return Self.error(
                status: 500,
                reason: "Internal Server Error",
                code: "snapshot-unavailable")
        }
    }

    private func isAllowedHost(_ raw: String) -> Bool {
        guard let parsed = Self.parseHost(raw) else { return false }
        switch parsed.name {
        case "127.0.0.1", "localhost", "localhost.", "[::1]":
            return true
        default:
            guard let expected = self.externalAuthority, parsed.name == expected.name else { return false }
            if let expectedPort = expected.port {
                return parsed.port == expectedPort
            }
            return parsed.port == nil || parsed.port == 443
        }
    }

    private static func headerValues(named name: String, in headers: [(String, String)]) -> [String] {
        headers.compactMap { key, value in
            key.caseInsensitiveCompare(name) == .orderedSame ? value : nil
        }
    }

    private static func canonicalExternalAuthority(_ raw: String?) -> (name: String, port: Int?)? {
        guard let value = raw?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased(),
              !value.isEmpty
        else {
            return nil
        }
        if var parsed = Self.parseHost(value) {
            if parsed.name.hasSuffix(".") {
                parsed.name.removeLast()
            }
            return parsed.name.isEmpty ? nil : parsed
        }
        return nil
    }

    private static func parseHost(_ raw: String) -> (name: String, port: Int?)? {
        let value = raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !value.isEmpty, !value.contains(",") else { return nil }
        if value.hasPrefix("[") {
            guard let closing = value.firstIndex(of: "]") else { return nil }
            let name = String(value[...closing])
            let suffix = String(value[value.index(after: closing)...])
            if suffix.isEmpty {
                return (name, nil)
            }
            guard suffix.hasPrefix(":"), let port = Int(suffix.dropFirst()), (1...65535).contains(port) else {
                return nil
            }
            return (name, port)
        }

        let parts = value.split(separator: ":", omittingEmptySubsequences: false)
        switch parts.count {
        case 1:
            return (String(parts[0]), nil)
        case 2:
            guard let port = Int(parts[1]), (1...65535).contains(port) else { return nil }
            return (String(parts[0]), port)
        default:
            return nil
        }
    }

    private static func isAuthorized(_ authorization: String, token: String) -> Bool {
        let prefix = "Bearer "
        guard authorization.hasPrefix(prefix) else { return false }
        let supplied = Array(authorization.dropFirst(prefix.count).utf8)
        let expected = Array(token.utf8)
        let count = max(supplied.count, expected.count)
        var difference = supplied.count ^ expected.count
        for index in 0..<count {
            let left = index < supplied.count ? supplied[index] : 0
            let right = index < expected.count ? expected[index] : 0
            difference |= Int(left ^ right)
        }
        return difference == 0
    }

    private static func error(
        status: Int,
        reason: String,
        code: String,
        extraHeaders: [(String, String)] = []) -> InkUsageHostResponse
    {
        let body = Data(#"{"error":"\#(code)"}"#.utf8)
        return InkUsageHostResponse(
            statusCode: status,
            reason: reason,
            body: body,
            headers: [("Cache-Control", "no-store")] + extraHeaders)
    }
}
