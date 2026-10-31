import Foundation

/// Preserve numeric tokens until schema validation. Foundation's normal map
/// decoding can hide duplicate members, and Int decoding may accept 1.0 as 1.
indirect enum StrictJSONValue {
    case object([String: StrictJSONValue])
    case array([StrictJSONValue])
    case string(String)
    case number(String)
    case boolean(Bool)
    case null
}

/// Small bounded JSON reader for the documented v1 contract. It validates the
/// original UTF-8 and escaped Unicode before constructing any portable model.
enum StrictJSON {
    static func parse(_ data: Data) throws -> StrictJSONValue {
        try requireProfile(data.count <= ProfileSharingLimits.documentBytes,
                           "Profile bundle exceeds 262144 UTF-8 bytes")
        try requireProfile(String(data: data, encoding: .utf8) != nil, "Profile bundle is not valid UTF-8")
        var parser = Reader(bytes: Array(data))
        let value = try parser.value(depth: 0)
        parser.whitespace()
        try parser.check(parser.index == parser.bytes.count, "Unexpected trailing JSON content")
        return value
    }

    /// index always points at the next unread byte. Container depth increases
    /// before descent, so a seventeenth container never enters the call stack.
    private struct Reader {
        let bytes: [UInt8]
        var index = 0

        mutating func whitespace() {
            while index < bytes.count && [9, 10, 13, 32].contains(bytes[index]) { index += 1 }
        }

        func check(_ condition: Bool, _ message: String) throws {
            if !condition { throw ProfileFormatError("JSON byte \(index): \(message)") }
        }

        mutating func take() throws -> UInt8 {
            try check(index < bytes.count, "Unexpected end of input")
            defer { index += 1 }
            return bytes[index]
        }

        mutating func expect(_ byte: UInt8) throws {
            let actual = try take()
            try check(actual == byte, "Expected '\(Character(UnicodeScalar(byte)))'")
        }

        mutating func value(depth: Int) throws -> StrictJSONValue {
            whitespace()
            try check(index < bytes.count, "Expected a JSON value")
            switch bytes[index] {
            case 123:
                try check(depth < ProfileSharingLimits.nestingDepth, "Nesting depth exceeds 16")
                return try object(depth: depth + 1)
            case 91:
                try check(depth < ProfileSharingLimits.nestingDepth, "Nesting depth exceeds 16")
                return try array(depth: depth + 1)
            case 34: return .string(try string())
            case 116: try literal("true"); return .boolean(true)
            case 102: try literal("false"); return .boolean(false)
            case 110: try literal("null"); return .null
            case 45, 48...57: return .number(try number())
            default: throw ProfileFormatError("JSON byte \(index): Expected a JSON value")
            }
        }

        mutating func literal(_ text: String) throws {
            for byte in text.utf8 { try expect(byte) }
        }

        mutating func object(depth: Int) throws -> StrictJSONValue {
            try expect(123)
            whitespace()
            var fields: [String: StrictJSONValue] = [:]
            if index < bytes.count && bytes[index] == 125 { index += 1; return .object(fields) }
            while true {
                whitespace()
                let key = try string()
                try check(fields[key] == nil, "Duplicate member '\(key)'")
                whitespace()
                try expect(58)
                fields[key] = try value(depth: depth)
                whitespace()
                if index < bytes.count && bytes[index] == 125 { index += 1; break }
                try expect(44)
            }
            return .object(fields)
        }

        mutating func array(depth: Int) throws -> StrictJSONValue {
            try expect(91)
            whitespace()
            var items: [StrictJSONValue] = []
            if index < bytes.count && bytes[index] == 93 { index += 1; return .array(items) }
            while true {
                items.append(try value(depth: depth))
                whitespace()
                if index < bytes.count && bytes[index] == 93 { index += 1; break }
                try expect(44)
            }
            return .array(items)
        }

        /// Decode escapes explicitly: unpaired UTF-16 surrogate escapes must
        /// fail, not silently become replacement characters in a Swift String.
        mutating func string() throws -> String {
            try expect(34)
            var decoded: [UInt8] = []
            while true {
                let byte = try take()
                if byte == 34 { return String(decoding: decoded, as: UTF8.self) }
                try check(byte >= 0x20, "Unescaped control character")
                if byte != 92 { decoded.append(byte); continue }
                switch try take() {
                case 34: decoded.append(34)
                case 92: decoded.append(92)
                case 47: decoded.append(47)
                case 98: decoded.append(8)
                case 102: decoded.append(12)
                case 110: decoded.append(10)
                case 114: decoded.append(13)
                case 116: decoded.append(9)
                case 117:
                    var scalar = try hexQuad()
                    if (0xD800...0xDBFF).contains(scalar) {
                        try check(index + 1 < bytes.count && bytes[index] == 92 && bytes[index + 1] == 117,
                                  "Unpaired Unicode surrogate")
                        index += 2
                        let low = try hexQuad()
                        try check((0xDC00...0xDFFF).contains(low), "Unpaired Unicode surrogate")
                        scalar = 0x10000 + ((scalar - 0xD800) << 10) + low - 0xDC00
                    } else {
                        try check(!(0xDC00...0xDFFF).contains(scalar), "Unpaired Unicode surrogate")
                    }
                    guard let character = UnicodeScalar(scalar) else {
                        throw ProfileFormatError("JSON byte \(index): Invalid Unicode scalar")
                    }
                    decoded.append(contentsOf: String(character).utf8)
                default: throw ProfileFormatError("JSON byte \(index): Invalid string escape")
                }
            }
        }

        mutating func hexQuad() throws -> UInt32 {
            var result: UInt32 = 0
            for _ in 0..<4 {
                let byte = try take()
                let digit: UInt32
                switch byte {
                case 48...57: digit = UInt32(byte - 48)
                case 65...70: digit = UInt32(byte - 65 + 10)
                case 97...102: digit = UInt32(byte - 97 + 10)
                default: throw ProfileFormatError("JSON byte \(index): Invalid Unicode escape")
                }
                result = (result << 4) | digit
            }
            return result
        }

        /// Parse JSON's number grammar but retain its original notation.
        /// The profile schema later requires an integer token without . or e.
        mutating func number() throws -> String {
            let start = index
            if bytes[index] == 45 { index += 1 }
            try check(index < bytes.count, "Incomplete number")
            if bytes[index] == 48 {
                index += 1
                try check(index == bytes.count || !(48...57).contains(bytes[index]), "Leading zero in number")
            } else {
                try check((49...57).contains(bytes[index]), "Invalid number")
                digits()
            }
            if index < bytes.count && bytes[index] == 46 {
                index += 1
                let fraction = index
                digits()
                try check(index > fraction, "Incomplete fraction")
            }
            if index < bytes.count && [69, 101].contains(bytes[index]) {
                index += 1
                if index < bytes.count && [43, 45].contains(bytes[index]) { index += 1 }
                let exponent = index
                digits()
                try check(index > exponent, "Incomplete exponent")
            }
            return String(decoding: bytes[start..<index], as: UTF8.self)
        }

        mutating func digits() {
            while index < bytes.count && (48...57).contains(bytes[index]) { index += 1 }
        }
    }
}
