import Foundation

public enum InkHTTPParserError: Error, Equatable {
    case incomplete
    case oversized
    case malformed
    case bodyNotAllowed
}

public enum InkHTTPParser {
    public static let maximumHeaderBytes = 16 * 1024

    public static func parse(_ data: Data) throws -> InkUsageHostRequest {
        guard data.count <= self.maximumHeaderBytes else { throw InkHTTPParserError.oversized }
        let delimiter = Data("\r\n\r\n".utf8)
        guard let delimiterRange = data.range(of: delimiter) else {
            throw InkHTTPParserError.incomplete
        }
        guard delimiterRange.upperBound == data.endIndex else { throw InkHTTPParserError.bodyNotAllowed }
        guard let text = String(data: data[..<delimiterRange.lowerBound], encoding: .utf8) else {
            throw InkHTTPParserError.malformed
        }
        let lines = text.components(separatedBy: "\r\n")
        guard let requestLine = lines.first else { throw InkHTTPParserError.malformed }
        let parts = requestLine.split(separator: " ", omittingEmptySubsequences: false)
        guard parts.count == 3,
              !parts[0].isEmpty,
              parts[1].hasPrefix("/"),
              parts[2] == "HTTP/1.1"
        else {
            throw InkHTTPParserError.malformed
        }

        var headers: [(String, String)] = []
        for line in lines.dropFirst() {
            guard let separator = line.firstIndex(of: ":") else { throw InkHTTPParserError.malformed }
            let name = String(line[..<separator]).trimmingCharacters(in: .whitespaces)
            let value = String(line[line.index(after: separator)...]).trimmingCharacters(in: .whitespaces)
            guard !name.isEmpty, name.allSatisfy(Self.isHeaderNameCharacter) else {
                throw InkHTTPParserError.malformed
            }
            headers.append((name, value))
        }
        if headers.contains(where: { name, value in
            name.caseInsensitiveCompare("content-length") == .orderedSame && value != "0"
        }) || headers.contains(where: { name, _ in
            name.caseInsensitiveCompare("transfer-encoding") == .orderedSame
        }) {
            throw InkHTTPParserError.bodyNotAllowed
        }
        return InkUsageHostRequest(method: String(parts[0]), target: String(parts[1]), headers: headers)
    }

    private static func isHeaderNameCharacter(_ character: Character) -> Bool {
        character.unicodeScalars.allSatisfy { scalar in
            switch scalar.value {
            case 48...57, 65...90, 97...122:
                true
            default:
                "!#$%&'*+-.^_`|~".unicodeScalars.contains(scalar)
            }
        }
    }
}
