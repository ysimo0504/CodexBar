import Foundation

public enum InkUsageHostHealth: Equatable, Sendable {
    case healthy
    case unauthorized
    case forbidden
    case tlsFailure
    case unavailable

    public var diagnostic: String {
        switch self {
        case .healthy: "Usage Host HTTPS is healthy"
        case .unauthorized: "Usage Host token was rejected (401)"
        case .forbidden: "Usage Host request was forbidden (403)"
        case .tlsFailure: "Usage Host HTTPS certificate failed"
        case .unavailable: "Usage Host HTTPS is unavailable"
        }
    }
}

public protocol InkUsageHostHealthChecking: Sendable {
    func check(dnsName: String, token: String) async -> InkUsageHostHealth
}

public struct InkUsageHostHealthChecker: InkUsageHostHealthChecking, Sendable {
    public typealias Transport = @Sendable (URLRequest) async throws -> Int

    private let transport: Transport

    public init(transport: @escaping Transport = Self.liveTransport) {
        self.transport = transport
    }

    public func check(dnsName: String, token: String) async -> InkUsageHostHealth {
        guard let url = URL(string: "https://\(dnsName)\(InkUsageHostGateway.snapshotPath)") else {
            return .unavailable
        }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = 10
        request.cachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        do {
            return switch try await self.transport(request) {
            case 200: .healthy
            case 401: .unauthorized
            case 403: .forbidden
            default: .unavailable
            }
        } catch let error as URLError {
            return switch error.code {
            case .serverCertificateHasBadDate,
                 .serverCertificateUntrusted,
                 .serverCertificateHasUnknownRoot,
                 .serverCertificateNotYetValid,
                 .clientCertificateRejected,
                 .clientCertificateRequired,
                 .secureConnectionFailed:
                .tlsFailure
            default:
                .unavailable
            }
        } catch {
            return .unavailable
        }
    }

    public static func liveTransport(_ request: URLRequest) async throws -> Int {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.urlCache = nil
        let session = URLSession(
            configuration: configuration,
            delegate: InkNoRedirectSessionDelegate.shared,
            delegateQueue: nil)
        defer { session.finishTasksAndInvalidate() }
        let (_, response) = try await session.data(for: request)
        return (response as? HTTPURLResponse)?.statusCode ?? 0
    }
}

private final class InkNoRedirectSessionDelegate: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    static let shared = InkNoRedirectSessionDelegate()

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void)
    {
        completionHandler(nil)
    }
}
