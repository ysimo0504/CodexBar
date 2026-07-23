#if canImport(Darwin) && canImport(Network) && canImport(Security)
import Crypto
import Darwin
import Foundation
import Network
import Security

public struct InkLANEndpoint: Equatable, Sendable {
    public let address: String
    public let port: UInt16

    public init(address: String, port: UInt16) {
        self.address = address
        self.port = port
    }

    public var authority: String {
        "\(self.address):\(self.port)"
    }

    public var baseURL: String {
        "https://\(self.authority)"
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

public struct InkTLSIdentityMaterial: @unchecked Sendable {
    let securityIdentity: SecIdentity?
    public let certificateSHA256: String
    public let hostID: String

    public init(
        securityIdentity: SecIdentity,
        certificateSHA256: String,
        hostID: String)
    {
        self.securityIdentity = securityIdentity
        self.certificateSHA256 = certificateSHA256
        self.hostID = hostID
    }

    package init(testCertificateSHA256: String, hostID: String) {
        self.securityIdentity = nil
        self.certificateSHA256 = testCertificateSHA256
        self.hostID = hostID
    }
}

public protocol InkTLSIdentityStoring: Sendable {
    func loadOrCreate() throws -> InkTLSIdentityMaterial
}

public enum InkTLSIdentityStoreError: Error, Equatable {
    case opensslUnavailable
    case generationFailed
    case invalidIdentity
    case storageFailed
}

public final class FileInkTLSIdentityStore: InkTLSIdentityStoring, @unchecked Sendable {
    private struct Metadata: Codable {
        let hostID: String
    }

    private let directory: URL
    private let opensslPath: String
    private let fileManager: FileManager
    private let lock = NSLock()

    public init(
        directory: URL,
        opensslPath: String = "/usr/bin/openssl",
        fileManager: FileManager = .default)
    {
        self.directory = directory
        self.opensslPath = opensslPath
        self.fileManager = fileManager
    }

    public static func applicationDefault(fileManager: FileManager = .default) -> FileInkTLSIdentityStore {
        let root = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? fileManager.homeDirectoryForCurrentUser.appendingPathComponent("Library/Application Support")
        return FileInkTLSIdentityStore(
            directory: root
                .appendingPathComponent("CodexBar", isDirectory: true)
                .appendingPathComponent("InkUsageHost", isDirectory: true),
            fileManager: fileManager)
    }

    public func loadOrCreate() throws -> InkTLSIdentityMaterial {
        try self.lock.withLock {
            try self.prepareDirectory()
            let identityURL = self.directory.appendingPathComponent("identity.p12")
            let metadataURL = self.directory.appendingPathComponent("identity.json")
            if !self.fileManager.fileExists(atPath: identityURL.path) {
                try self.generateIdentity(at: identityURL)
            }
            let metadata = try self.loadOrCreateMetadata(at: metadataURL)
            return try Self.importIdentity(from: identityURL, hostID: metadata.hostID)
        }
    }

    private func prepareDirectory() throws {
        do {
            try self.fileManager.createDirectory(
                at: self.directory,
                withIntermediateDirectories: true,
                attributes: [.posixPermissions: 0o700])
            try self.fileManager.setAttributes([.posixPermissions: 0o700], ofItemAtPath: self.directory.path)
        } catch {
            throw InkTLSIdentityStoreError.storageFailed
        }
    }

    private func generateIdentity(at destination: URL) throws {
        guard self.fileManager.isExecutableFile(atPath: self.opensslPath) else {
            throw InkTLSIdentityStoreError.opensslUnavailable
        }
        let temporaryDirectory = self.fileManager.temporaryDirectory
            .appendingPathComponent("codexbar-ink-tls-\(UUID().uuidString)", isDirectory: true)
        do {
            try self.fileManager.createDirectory(
                at: temporaryDirectory,
                withIntermediateDirectories: false,
                attributes: [.posixPermissions: 0o700])
        } catch {
            throw InkTLSIdentityStoreError.storageFailed
        }
        defer { try? self.fileManager.removeItem(at: temporaryDirectory) }

        let keyURL = temporaryDirectory.appendingPathComponent("key.pem")
        let certificateURL = temporaryDirectory.appendingPathComponent("certificate.pem")
        let packageURL = temporaryDirectory.appendingPathComponent("identity.p12")
        try self.runOpenSSL([
            "req",
            "-x509",
            "-newkey",
            "rsa:2048",
            "-sha256",
            "-nodes",
            "-days",
            "3650",
            "-subj",
            "/CN=CodexBar Ink Usage Host",
            "-addext",
            "basicConstraints=critical,CA:FALSE",
            "-addext",
            "keyUsage=critical,digitalSignature,keyEncipherment",
            "-addext",
            "extendedKeyUsage=serverAuth",
            "-keyout",
            keyURL.path,
            "-out",
            certificateURL.path,
        ])
        try self.runOpenSSL([
            "pkcs12",
            "-export",
            "-inkey",
            keyURL.path,
            "-in",
            certificateURL.path,
            "-out",
            packageURL.path,
            "-passout",
            "pass:\(Self.packagePassword)",
        ])
        do {
            try self.fileManager.moveItem(at: packageURL, to: destination)
            try self.fileManager.setAttributes([.posixPermissions: 0o600], ofItemAtPath: destination.path)
        } catch {
            throw InkTLSIdentityStoreError.storageFailed
        }
    }

    private func runOpenSSL(_ arguments: [String]) throws {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: self.opensslPath)
        process.arguments = arguments
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        do {
            try process.run()
            process.waitUntilExit()
        } catch {
            throw InkTLSIdentityStoreError.generationFailed
        }
        guard process.terminationReason == .exit, process.terminationStatus == 0 else {
            throw InkTLSIdentityStoreError.generationFailed
        }
    }

    private func loadOrCreateMetadata(at url: URL) throws -> Metadata {
        if self.fileManager.fileExists(atPath: url.path) {
            do {
                return try JSONDecoder().decode(Metadata.self, from: Data(contentsOf: url))
            } catch {
                throw InkTLSIdentityStoreError.invalidIdentity
            }
        }
        let metadata = Metadata(hostID: UUID().uuidString.lowercased())
        do {
            try JSONEncoder().encode(metadata).write(to: url, options: .atomic)
            try self.fileManager.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
            return metadata
        } catch {
            throw InkTLSIdentityStoreError.storageFailed
        }
    }

    private static func importIdentity(from url: URL, hostID: String) throws -> InkTLSIdentityMaterial {
        let data: Data
        do {
            data = try Data(contentsOf: url)
        } catch {
            throw InkTLSIdentityStoreError.storageFailed
        }
        var imported: CFArray?
        let options = [kSecImportExportPassphrase as String: Self.packagePassword] as CFDictionary
        guard SecPKCS12Import(data as CFData, options, &imported) == errSecSuccess,
              let items = imported as? [[String: Any]],
              let identity = Self.checkedIdentity(items.first?[kSecImportItemIdentity as String]),
              let certificate = Self.certificate(for: identity)
        else {
            throw InkTLSIdentityStoreError.invalidIdentity
        }
        let certificateData = SecCertificateCopyData(certificate) as Data
        let digest = SHA256.hash(data: certificateData)
        let pin = digest.map { String(format: "%02x", $0) }.joined()
        return InkTLSIdentityMaterial(
            securityIdentity: identity,
            certificateSHA256: pin,
            hostID: hostID)
    }

    private static func checkedIdentity(_ value: Any?) -> SecIdentity? {
        guard let value else { return nil }
        let reference = value as CFTypeRef
        guard CFGetTypeID(reference) == SecIdentityGetTypeID() else { return nil }
        return unsafeDowncast(reference, to: SecIdentity.self)
    }

    private static func certificate(for identity: SecIdentity) -> SecCertificate? {
        var certificate: SecCertificate?
        guard SecIdentityCopyCertificate(identity, &certificate) == errSecSuccess else { return nil }
        return certificate
    }

    private static let packagePassword = "codexbar-ink-local"
}

public protocol InkLANHTTPSServing: Sendable {
    func start(identity: InkTLSIdentityMaterial, address: String) async throws -> InkLANEndpoint
    func stop()
}

public final class InkLANHTTPSServer: InkLANHTTPSServing, @unchecked Sendable {
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
    private let queue = DispatchQueue(label: "com.ysimo.codexbar.ink.lan-https")
    private let lock = NSLock()
    private var listener: NWListener?
    private var allowedAddress: String?
    private var connectionCount = 0
    private let maximumConnections = 16

    public init(gateway: InkUsageHostGateway, port: UInt16 = InkLANHTTPSServer.defaultPort) {
        self.gateway = gateway
        self.port = port
    }

    public func start(identity: InkTLSIdentityMaterial, address: String) async throws -> InkLANEndpoint {
        guard InkPrivateLANAddress.isAllowedIPv4(address),
              let securityIdentity = identity.securityIdentity,
              let protocolIdentity = sec_identity_create(securityIdentity),
              let listenerPort = NWEndpoint.Port(rawValue: self.port)
        else {
            throw InkLoopbackHTTPServerError.listenerFailed
        }
        guard self.lock.withLock({ self.listener == nil }) else {
            throw InkLoopbackHTTPServerError.alreadyRunning
        }

        let tlsOptions = NWProtocolTLS.Options()
        sec_protocol_options_set_local_identity(tlsOptions.securityProtocolOptions, protocolIdentity)
        sec_protocol_options_set_min_tls_protocol_version(tlsOptions.securityProtocolOptions, .TLSv12)
        let parameters = NWParameters(tls: tlsOptions, tcp: NWProtocolTCP.Options())
        parameters.requiredLocalEndpoint = .hostPort(host: .ipv4(.any), port: listenerPort)
        let listener = try NWListener(using: parameters)
        self.lock.withLock {
            self.listener = listener
            self.allowedAddress = address
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
            defer {
                self.listener = nil
                self.allowedAddress = nil
            }
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
                guard self.isAllowedLocalEndpoint(connection.currentPath?.localEndpoint) else {
                    connection.cancel()
                    return
                }
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

    private func isAllowedLocalEndpoint(_ endpoint: NWEndpoint?) -> Bool {
        guard case let .hostPort(host, _) = endpoint else { return false }
        let expected = self.lock.withLock { self.allowedAddress }
        return expected == String(describing: host)
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
