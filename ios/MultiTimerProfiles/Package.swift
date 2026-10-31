// swift-tools-version: 5.9
import PackageDescription

// A second build entry point for the exact same portable sources. This runs
// codec tests on Linux/macOS; the Xcode scheme still owns SwiftUI/simulator tests.
let package = Package(
    name: "MultiTimerProfileCore",
    platforms: [.macOS(.v13), .iOS(.v17)],
    products: [.library(name: "MultiTimerProfileCore", targets: ["MultiTimerProfileCore"])],
    targets: [
        .target(name: "MultiTimerProfileCore", path: "Sources",
                exclude: ["ContentView.swift", "MultiTimerProfilesApp.swift", "CompanionModel.swift",
                          "PreviewController.swift", "ProfileEditorView.swift", "ProfilePreviewView.swift",
                          "ProfileDocument.swift", "ImportPreviewView.swift"],
                sources: ["SharedProfile.swift", "StrictJSON.swift", "ProfileBundleCodec.swift",
                          "ProfileDraft.swift", "ProfileLibrary.swift", "ProfilePreview.swift", "ProfileFileIO.swift"]),
        .testTarget(name: "MultiTimerProfileCoreTests", dependencies: ["MultiTimerProfileCore"],
                    path: "Tests", exclude: ["CompanionBuildTests.swift", "CompanionModelTests.swift"],
                    sources: ["ProfileBundleCodecTests.swift", "ProfileEditorTests.swift", "ProfileFileIOTests.swift"])
    ]
)
