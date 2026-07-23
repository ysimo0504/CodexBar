import Foundation

#if os(macOS)
import Security

public protocol ReaderTokenStoring: Sendable {
    func load() throws -> String?
    func save(_ token: String) throws
    func delete() throws
}

public enum ReaderTokenStoreError: LocalizedError, Sendable {
    case randomGenerationFailed(OSStatus)
    case invalidStoredToken
    case keychain(OSStatus)

    public var errorDescription: String? {
        switch self {
        case let .randomGenerationFailed(status):
            "Reader token generation failed (\(status))."
        case .invalidStoredToken:
            "The stored reader token is invalid."
        case let .keychain(status):
            "Reader token Keychain operation failed (\(status))."
        }
    }
}

public enum ReaderTokenGenerator {
    public static func generate() throws -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        let status = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        guard status == errSecSuccess else {
            throw ReaderTokenStoreError.randomGenerationFailed(status)
        }
        return Data(bytes)
            .base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    public static func shortFingerprint(_ token: String) -> String {
        var hash: UInt64 = 1_469_598_103_934_665_603
        for byte in token.utf8 {
            hash ^= UInt64(byte)
            hash &*= 1_099_511_628_211
        }
        return String(format: "%08llx", hash & 0xFFFF_FFFF)
    }
}

public struct KeychainReaderTokenStore: ReaderTokenStoring {
    package static let service = "com.ysimo.codexbar.ink.usage-host"
    package static let account = "reader-token-v1"

    public init() {}

    public func load() throws -> String? {
        var query = Self.query(returnData: true)
        KeychainNoUIQuery.apply(to: &query)
        var result: CFTypeRef?
        let status = KeychainSecurity.copyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound {
            return nil
        }
        guard status == errSecSuccess else {
            throw ReaderTokenStoreError.keychain(status)
        }
        guard let data = result as? Data,
              let token = String(data: data, encoding: .utf8),
              token.utf8.count >= 32
        else {
            throw ReaderTokenStoreError.invalidStoredToken
        }
        return token
    }

    public func save(_ token: String) throws {
        guard token.utf8.count >= 32 else {
            throw ReaderTokenStoreError.invalidStoredToken
        }
        let data = Data(token.utf8)
        var updateQuery = Self.query(returnData: false)
        KeychainNoUIQuery.apply(to: &updateQuery)
        let updateStatus = KeychainSecurity.update(
            updateQuery as CFDictionary,
            [kSecValueData as String: data] as CFDictionary)
        if updateStatus == errSecSuccess {
            return
        }
        guard updateStatus == errSecItemNotFound else {
            throw ReaderTokenStoreError.keychain(updateStatus)
        }

        var addQuery = Self.query(returnData: false)
        addQuery[kSecValueData as String] = data
        addQuery[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        KeychainNoUIQuery.apply(to: &addQuery)
        let addStatus = KeychainSecurity.add(addQuery as CFDictionary, nil)
        guard addStatus == errSecSuccess else {
            throw ReaderTokenStoreError.keychain(addStatus)
        }
    }

    public func delete() throws {
        var query = Self.query(returnData: false)
        KeychainNoUIQuery.apply(to: &query)
        let status = KeychainSecurity.delete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw ReaderTokenStoreError.keychain(status)
        }
    }

    package static func query(returnData: Bool) -> [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: self.service,
            kSecAttrAccount as String: self.account,
            kSecAttrSynchronizable as String: false,
        ]
        if returnData {
            query[kSecReturnData as String] = true
            query[kSecMatchLimit as String] = kSecMatchLimitOne
        }
        return query
    }
}
#endif
