import SwiftUI
import UniformTypeIdentifiers

/// Export-only document adapter. Encoding already finished on a worker before
/// the system exporter is presented; rendering the view performs no file work.
struct ProfileDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }
    let data: Data

    init(data: Data) { self.data = data }

    init(configuration: ReadConfiguration) throws {
        guard let bytes = configuration.file.regularFileContents else {
            throw ProfileFormatError("Expected a regular JSON document")
        }
        try requireProfile(bytes.count <= ProfileSharingLimits.documentBytes, "Profile document is too large")
        data = bytes
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        try requireProfile(data.count <= ProfileSharingLimits.documentBytes, "Profile document is too large")
        return FileWrapper(regularFileWithContents: data)
    }
}
