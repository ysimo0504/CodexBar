import CodexBarCore
import Foundation
import Testing

struct InkHTTPParserTests {
    @Test
    func `parser preserves duplicate security headers`() throws {
        let data = Data([
            "GET /dashboard/v1/snapshot HTTP/1.1",
            "Host: localhost",
            "Host: attacker.example",
            "Authorization: Bearer one",
            "Authorization: Bearer two",
            "",
            "",
        ].joined(separator: "\r\n").utf8)
        let request = try InkHTTPParser.parse(data)

        #expect(request.method == "GET")
        #expect(request.target == "/dashboard/v1/snapshot")
        #expect(request.headers.count(where: { $0.0.lowercased() == "host" }) == 2)
        #expect(request.headers.count(where: { $0.0.lowercased() == "authorization" }) == 2)
    }

    @Test
    func `parser rejects bodies malformed lines and oversized input`() {
        #expect(throws: Error.self) {
            try InkHTTPParser.parse(Data("POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1\r\n\r\nx".utf8))
        }
        #expect(throws: InkHTTPParserError.bodyNotAllowed) {
            try InkHTTPParser.parse(Data("GET / HTTP/1.1\r\nHost: localhost\r\n\r\nsmuggled".utf8))
        }
        #expect(throws: Error.self) {
            try InkHTTPParser.parse(Data("GET / HTTP/1.1\r\nBadHeader\r\n\r\n".utf8))
        }
        #expect(throws: InkHTTPParserError.malformed) {
            try InkHTTPParser.parse(Data("GET / HTTP/1.1\r\nHöst: localhost\r\n\r\n".utf8))
        }
        #expect(throws: Error.self) {
            try InkHTTPParser.parse(Data(repeating: 65, count: InkHTTPParser.maximumHeaderBytes + 1))
        }
    }
}
