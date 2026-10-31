import Foundation

/// Bounded input before decoding. File-provider metadata may be absent or
/// inaccurate, so only actual streamed bytes decide whether a file is allowed.
enum ProfileFileIO {
    static func readBounded(_ input: InputStream) throws -> Data {
        input.open()
        defer { input.close() }
        var result = Data()
        var buffer = [UInt8](repeating: 0, count: 8192)
        while true {
            let allowance = min(buffer.count, ProfileSharingLimits.documentBytes - result.count + 1)
            let count = input.read(&buffer, maxLength: allowance)
            if count < 0 { throw input.streamError ?? ProfileFormatError("Could not read the chosen file") }
            if count == 0 { return result }
            try requireProfile(count <= ProfileSharingLimits.documentBytes - result.count,
                               "Profile bundle exceeds 262144 UTF-8 bytes")
            result.append(contentsOf: buffer.prefix(count))
        }
    }

    /// Security-scope access lasts through coordinated reading/validation and
    /// is always released. Sandbox-local URLs need no extra grant; a false
    /// startAccessing result alone is not proof that a local file is denied.
    static func loadValidated(_ url: URL) throws -> [SharedProfile] {
        #if os(iOS) || os(macOS)
        let hasScope = url.startAccessingSecurityScopedResource()
        defer { if hasScope { url.stopAccessingSecurityScopedResource() } }
        let coordinator = NSFileCoordinator(filePresenter: nil)
        var coordinationError: NSError?
        var result: Result<[SharedProfile], Error>?
        coordinator.coordinate(readingItemAt: url, options: [], error: &coordinationError) { availableURL in
            result = Result { try readValidated(availableURL) }
        }
        if let coordinationError { throw coordinationError }
        guard let result else { throw ProfileFormatError("The file provider did not supply a readable document") }
        return try result.get()
        #else
        return try readValidated(url)
        #endif
    }

    private static func readValidated(_ url: URL) throws -> [SharedProfile] {
        guard let input = InputStream(url: url) else { throw ProfileFormatError("Could not open the chosen file") }
        return try ProfileBundleCodec.decode(readBounded(input))
    }
}
