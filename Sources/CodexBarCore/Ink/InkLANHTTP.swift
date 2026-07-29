#if canImport(Darwin) && canImport(Network)
import Darwin
import Foundation
import Network

public struct InkLANEndpoint: Equatable, Sendable {
    public let address: String
    public let port: UInt16
    public let scheme: String

    public init(address: String, port: UInt16, scheme: String = "http") {
        self.address = address
        self.port = port
        self.scheme = scheme
    }

    public var authority: String {
        "\(self.address):\(self.port)"
    }

    public var baseURL: String {
        "\(self.scheme)://\(self.authority)"
    }
}

public enum InkPrivateLANAddress {
    public static func isAllowedIPv4(_ value: String) -> Bool {
        let components = value.split(separator: ".", omittingEmptySubsequences: false)
        guard components.count == 4 else { return false }
        let octets = components.compactMap { component -> UInt8? in
            guard !component.isEmpty,
                  component.allSatisfy(\.isNumber),
                  let value = UInt8(component)
            else {
                return nil
            }
            return value
        }
        guard octets.count == 4 else { return false }
        switch (octets[0], octets[1]) {
        case (10, _):
            return true
        case (172, 16...31):
            return true
        case (192, 168):
            return true
        case (169, 254):
            return true
        default:
            return false
        }
    }

    public static func currentIPv4() -> String? {
        var pointer: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&pointer) == 0, let first = pointer else { return nil }
        defer { freeifaddrs(pointer) }

        var candidates: [(name: String, address: String)] = []
        var current: UnsafeMutablePointer<ifaddrs>? = first
        while let interface = current?.pointee {
            defer { current = interface.ifa_next }
            guard let address = interface.ifa_addr,
                  address.pointee.sa_family == UInt8(AF_INET),
                  (interface.ifa_flags & UInt32(IFF_UP)) != 0,
                  (interface.ifa_flags & UInt32(IFF_LOOPBACK)) == 0
            else {
                continue
            }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            let length = socklen_t(address.pointee.sa_len)
            guard getnameinfo(
                address,
                length,
                &host,
                socklen_t(host.count),
                nil,
                0,
                NI_NUMERICHOST) == 0
            else {
                continue
            }
            guard let value = String(
                bytes: host.prefix { $0 != 0 }.map { UInt8(bitPattern: $0) },
                encoding: .utf8)
            else {
                continue
            }
            guard self.isAllowedIPv4(value) else { continue }
            candidates.append((String(cString: interface.ifa_name), value))
        }

        return candidates.min { lhs, rhs in
            let lhsPriority = lhs.name == "en0" ? 0 : 1
            let rhsPriority = rhs.name == "en0" ? 0 : 1
            if lhsPriority != rhsPriority { return lhsPriority < rhsPriority }
            return lhs.name < rhs.name
        }?.address
    }
}

public protocol InkLANHTTPServing: Sendable {
    func start(address: String) async throws -> InkLANEndpoint
    func stop()
}

public final class InkLANHTTPServer: InkLANHTTPServing, @unchecked Sendable {
    private final class StartContinuation: @unchecked Sendable {
        private let lock = NSLock()
        private var continuation: CheckedContinuation<InkLANEndpoint, Error>?

        init(_ continuation: CheckedContinuation<InkLANEndpoint, Error>) {
            self.continuation = continuation
        }

        func resume(_ result: Result<InkLANEndpoint, Error>) {
            let continuation = self.lock.withLock {
                defer { self.continuation = nil }
                return self.continuation
            }
            continuation?.resume(with: result)
        }
    }

    private final class ConnectionContext: @unchecked Sendable {
        var data = Data()
    }

    public static let defaultPort: UInt16 = 43121

    private let gateway: InkUsageHostGateway
    private let port: UInt16
    private let queue = DispatchQueue(label: "com.ysimo.codexbar.ink.lan-http")
    private let lock = NSLock()
    private var listener: NWListener?
    private var connectionCount = 0
    private let maximumConnections = 16

    public init(gateway: InkUsageHostGateway, port: UInt16 = InkLANHTTPServer.defaultPort) {
        self.gateway = gateway
        self.port = port
    }

    public func start(address: String) async throws -> InkLANEndpoint {
        guard InkPrivateLANAddress.isAllowedIPv4(address),
              let listenerPort = NWEndpoint.Port(rawValue: self.port)
        else {
            throw InkLoopbackHTTPServerError.listenerFailed
        }
        guard self.lock.withLock({ self.listener == nil }) else {
            throw InkLoopbackHTTPServerError.alreadyRunning
        }

        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: NWEndpoint.Host(address), port: listenerPort)
        let listener = try NWListener(using: parameters)
        self.lock.withLock {
            self.listener = listener
        }
        listener.newConnectionHandler = { [weak self] connection in
            self?.accept(connection)
        }
        let endpoint = InkLANEndpoint(address: address, port: self.port)
        return try await withCheckedThrowingContinuation { continuation in
            let box = StartContinuation(continuation)
            listener.stateUpdateHandler = { [weak self] state in
                switch state {
                case .ready:
                    box.resume(.success(endpoint))
                case .failed:
                    self?.stop()
                    box.resume(.failure(InkLoopbackHTTPServerError.listenerFailed))
                case .cancelled:
                    box.resume(.failure(InkLoopbackHTTPServerError.listenerFailed))
                default:
                    break
                }
            }
            listener.start(queue: self.queue)
        }
    }

    public func stop() {
        let listener = self.lock.withLock { () -> NWListener? in
            defer { self.listener = nil }
            return self.listener
        }
        listener?.cancel()
    }

    private func accept(_ connection: NWConnection) {
        let accepted = self.lock.withLock { () -> Bool in
            guard self.connectionCount < self.maximumConnections else { return false }
            self.connectionCount += 1
            return true
        }
        guard accepted else {
            self.send(Self.errorResponse(status: 503, reason: "Service Unavailable"), on: connection)
            return
        }
        connection.stateUpdateHandler = { [weak self, weak connection] state in
            guard let self else { return }
            switch state {
            case .ready:
                guard let connection else { return }
                let context = ConnectionContext()
                self.receive(on: connection, context: context)
                self.queue.asyncAfter(deadline: .now() + 5) { [weak connection] in
                    connection?.cancel()
                }
            case .failed, .cancelled:
                self.lock.withLock { self.connectionCount = max(0, self.connectionCount - 1) }
            default:
                break
            }
        }
        connection.start(queue: self.queue)
    }

    private func receive(on connection: NWConnection, context: ConnectionContext) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 4096) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let data { context.data.append(data) }
            if context.data.count > InkHTTPParser.maximumHeaderBytes {
                self.send(Self.errorResponse(status: 431, reason: "Request Header Fields Too Large"), on: connection)
                return
            }
            if context.data.range(of: Data("\r\n\r\n".utf8)) != nil {
                let request: InkUsageHostRequest
                do {
                    request = try InkHTTPParser.parse(context.data)
                } catch {
                    self.send(Self.errorResponse(status: 400, reason: "Bad Request"), on: connection)
                    return
                }
                Task {
                    let response = await self.gateway.handle(request)
                    self.send(response.serialized, on: connection)
                }
                return
            }
            if isComplete || error != nil {
                connection.cancel()
                return
            }
            self.receive(on: connection, context: context)
        }
    }

    private func send(_ data: Data, on connection: NWConnection) {
        connection.send(content: data, completion: .contentProcessed { _ in
            connection.cancel()
        })
    }

    private static func errorResponse(status: Int, reason: String) -> Data {
        InkUsageHostResponse(
            statusCode: status,
            reason: reason,
            body: Data(#"{"error":"invalid-request"}"#.utf8),
            headers: [("Cache-Control", "no-store")]).serialized
    }
}
#endif
