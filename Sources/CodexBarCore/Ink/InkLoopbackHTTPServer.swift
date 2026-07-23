#if canImport(Network)
import Foundation
import Network

public enum InkLoopbackHTTPServerError: Error, Equatable {
    case alreadyRunning
    case listenerFailed
    case portUnavailable
}

public protocol InkLoopbackServing: Sendable {
    func start() async throws -> UInt16
    func stop()
}

public final class InkLoopbackHTTPServer: InkLoopbackServing, @unchecked Sendable {
    private final class StartContinuation: @unchecked Sendable {
        private let lock = NSLock()
        private var continuation: CheckedContinuation<UInt16, Error>?

        init(_ continuation: CheckedContinuation<UInt16, Error>) {
            self.continuation = continuation
        }

        func resume(_ result: Result<UInt16, Error>) {
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

    private let gateway: InkUsageHostGateway
    private let queue = DispatchQueue(label: "com.ysimo.codexbar.ink.usage-host")
    private let lock = NSLock()
    private var listener: NWListener?
    private var connectionCount = 0
    private let maximumConnections = 16

    public init(gateway: InkUsageHostGateway) {
        self.gateway = gateway
    }

    public var localPort: UInt16? {
        self.lock.withLock { self.listener?.port?.rawValue }
    }

    public func start() async throws -> UInt16 {
        guard self.lock.withLock({ self.listener == nil }) else {
            throw InkLoopbackHTTPServerError.alreadyRunning
        }
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: .ipv4(.loopback), port: .any)
        let listener = try NWListener(using: parameters)
        self.lock.withLock { self.listener = listener }
        listener.newConnectionHandler = { [weak self] connection in
            self?.accept(connection)
        }
        return try await withCheckedThrowingContinuation { continuation in
            let box = StartContinuation(continuation)
            listener.stateUpdateHandler = { [weak self, weak listener] state in
                switch state {
                case .ready:
                    guard let port = listener?.port?.rawValue else {
                        box.resume(.failure(InkLoopbackHTTPServerError.portUnavailable))
                        return
                    }
                    box.resume(.success(port))
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
        connection.receive(
            minimumIncompleteLength: 1,
            maximumLength: 4096)
        { [weak self, weak connection] data, _, isComplete, error in
            guard let self, let connection else { return }
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
