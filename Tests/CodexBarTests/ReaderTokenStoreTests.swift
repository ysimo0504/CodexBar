import Foundation
import Security
import Testing
@testable import CodexBarCore

struct ReaderTokenStoreTests {
    @Test
    func `generated token carries at least 256 random bits`() throws {
        let first = try ReaderTokenGenerator.generate()
        let second = try ReaderTokenGenerator.generate()

        #expect(first != second)
        #expect(first.utf8.count >= 43)
        #expect(first.range(of: #"^[A-Za-z0-9_-]+$"#, options: .regularExpression) != nil)
        #expect(ReaderTokenGenerator.shortFingerprint(first).count == 8)
        #expect(!ReaderTokenGenerator.shortFingerprint(first).contains(first))
    }

    @Test
    func `keychain query is dedicated non synchronizing and can be made no UI`() {
        var query = KeychainReaderTokenStore.query(returnData: true)
        KeychainNoUIQuery.apply(to: &query)

        #expect(query[kSecAttrService as String] as? String == KeychainReaderTokenStore.service)
        #expect(query[kSecAttrAccount as String] as? String == KeychainReaderTokenStore.account)
        #expect(query[kSecAttrSynchronizable as String] as? Bool == false)
        #expect(query[kSecReturnData as String] as? Bool == true)
        #expect(query[kSecUseAuthenticationContext as String] != nil)
        #expect(query[kSecUseAuthenticationUI as String] != nil)
    }
}
