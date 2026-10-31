import XCTest

/// Launch the built app through XCTest's separate UI-test process. A model
/// unit test alone would not prove that the SwiftUI target installs and opens.
final class CompanionLaunchTests: XCTestCase {
    override func setUp() {
        super.setUp()
        // One failed navigation must stop this case. Otherwise later taps can
        // hit an unrelated Cancel button and obscure the original screen.
        continueAfterFailure = false
    }
    func testNativeCompanionLaunches() {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.staticTexts["companion-title"].waitForExistence(timeout: 10))
    }

    /// Drive real controls. The profile name is unique and only that row is
    /// created; no reset/clear-data launch option exists in the product app.
    func testCreateEditPreviewAndCancelThroughSwiftUI() {
        let app = XCUIApplication()
        app.launch()
        let newButton = app.buttons["new-profile"]
        XCTAssertTrue(newButton.waitForExistence(timeout: 10))
        newButton.tap()
        let name = app.textFields["profile-name"]
        XCTAssertTrue(name.waitForExistence(timeout: 5))
        replace(name, with: "Quick cues \(UUID().uuidString.prefix(6))")
        let chosenName = name.value as! String
        replace(app.textFields["profile-period"], with: "0.1")
        screenshot(app, name: "companion-editor")
        app.buttons["save-profile"].tap()
        let row = app.buttons["saved-profile-\(chosenName)"]
        XCTAssertTrue(row.waitForExistence(timeout: 10))
        screenshot(app, name: "companion-library")
        row.tap()
        // Dismiss the number keyboard without relying on physical-key input.
        app.swipeUp()
        let preview = app.buttons["preview-profile"]
        if !preview.isHittable { app.swipeUp() }
        XCTAssertTrue(preview.waitForExistence(timeout: 5))
        preview.tap()
        XCTAssertTrue(app.buttons["start-preview"].waitForExistence(timeout: 5))
        app.buttons["start-preview"].tap()
        XCTAssertTrue(app.staticTexts["Preview running"].waitForExistence(timeout: 5))
        app.buttons["stop-preview"].tap()
        XCTAssertTrue(app.staticTexts["Preview stopped"].waitForExistence(timeout: 5))
        screenshot(app, name: "companion-preview")
        app.buttons["Done"].tap()
        app.buttons["Cancel"].tap()
        XCTAssertTrue(row.waitForExistence(timeout: 5))
    }

    private func replace(_ field: XCUIElement, with text: String) {
        field.tap()
        field.press(forDuration: 1.2)
        let selectAll = XCUIApplication().menuItems["Select All"]
        if selectAll.waitForExistence(timeout: 2) {
            selectAll.tap()
            field.typeText(text)
        } else {
            let current = field.value as? String ?? ""
            field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: current.count) + text)
        }
    }

    /// Exercise the actual SwiftUI/system-picker path, not only the codec. The
    /// host script prepared harmless Documents files in this disposable app.
    func testSystemFileImportPreviewCancelConfirmAndExport() {
        let app = XCUIApplication()
        app.launch()
        // Cancelling the native picker must also preserve the visible library,
        // separately from cancelling a decoded import preview later below.
        let rows = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "saved-profile-"))
        XCTAssertTrue(app.buttons["import-file"].waitForExistence(timeout: 10))
        let originalCount = rows.count
        app.buttons["import-file"].tap()
        let cancelPicker = app.buttons["Cancel"].firstMatch
        XCTAssertTrue(cancelPicker.waitForExistence(timeout: 20))
        cancelPicker.tap()
        XCTAssertTrue(app.buttons["import-file"].waitForExistence(timeout: 10))
        XCTAssertEqual(rows.count, originalCount)
        openLocalDocument("Exchange example", app: app)
        XCTAssertTrue(app.navigationBars["Preview import"].waitForExistence(timeout: 10), app.debugDescription)
        screenshot(app, name: "companion-import-preview")
        app.buttons["Cancel"].tap()
        XCTAssertTrue(app.buttons["import-file"].waitForExistence(timeout: 5))

        openLocalDocument("Exchange example", app: app)
        XCTAssertTrue(app.buttons["confirm-import"].waitForExistence(timeout: 10))
        app.buttons["confirm-import"].tap()
        let imported = app.buttons["saved-profile-Exchange example"]
        XCTAssertTrue(imported.waitForExistence(timeout: 10))
        screenshot(app, name: "companion-import-confirmed")
        // Make a genuine editor change before exporting to the Android app.
        // The test does not rewrite the portable file behind the UI's back.
        imported.tap()
        replace(app.textFields["profile-name"], with: "Exchange example edited")
        // Submit the name before scrolling. An application-wide swipe can
        // otherwise start over the keyboard and never reach the form.
        app.keyboards.buttons["Return"].tap()
        let offset = app.textFields.matching(NSPredicate(format: "identifier BEGINSWITH %@", "cue-offset-")).firstMatch
        XCTAssertGreaterThan(app.collectionViews.count, 0)
        let form = app.collectionViews.element(boundBy: app.collectionViews.count - 1)
        for _ in 0..<6 {
            if offset.exists && offset.isHittable { break }
            form.swipeUp()
        }
        XCTAssertTrue(offset.isHittable, app.debugDescription)
        replace(offset, with: "1")
        screenshot(app, name: "companion-script-edited")
        app.buttons["save-profile"].tap()
        XCTAssertTrue(app.buttons["saved-profile-Exchange example edited"].waitForExistence(timeout: 10))
        for name in ["Every minute – café 🍵", "Exchange example edited", "Done", "Elapsed time"] {
            let choice = app.buttons["export-choice-\(name)"]
            if !choice.isHittable { app.swipeDown() }
            XCTAssertTrue(choice.waitForExistence(timeout: 5))
            choice.tap()
        }
        let export = app.buttons["export-selected"]
        if !export.isHittable { app.swipeUp() }
        export.tap()
        let save = app.buttons.matching(NSPredicate(format: "label IN %@", ["Move", "Export", "Save"])).firstMatch
        // The export button first prepares bytes on a worker. Wait for the
        // destination sheet before querying folder controls; otherwise a query
        // can observe a disappearing picker from the preceding import.
        XCTAssertTrue(save.waitForExistence(timeout: 20), app.debugDescription)
        // On a fresh simulator there is no iCloud account. Save to the app's
        // Files-visible Documents folder using the system destination chooser.
        chooseLocalFolder(app)
        // FileDocument's exporter uses Move on this iOS runtime; other system
        // versions use Export or Save. These are destination confirmations,
        // not the disabled JSON documents inside the folder chooser.
        XCTAssertTrue(save.waitForExistence(timeout: 10), app.debugDescription)
        screenshot(app, name: "companion-export-picker")
        save.tap()
        XCTAssertTrue(app.staticTexts["Selected profiles exported."].waitForExistence(timeout: 10))
        screenshot(app, name: "companion-export-confirmed")
    }

    private func openLocalDocument(_ name: String, app: XCUIApplication) {
        let open = app.buttons["import-file"]
        if !open.isHittable { app.swipeUp() }
        XCTAssertTrue(open.waitForExistence(timeout: 5))
        open.tap()
        let file = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", name)).firstMatch
        if !file.waitForExistence(timeout: 2) { chooseLocalFolder(app) }
        XCTAssertTrue(file.waitForExistence(timeout: 10), app.debugDescription)
        // Files renders the name as a label inside a document cell. Activate
        // the cell, rather than assuming its text's center is the file target.
        let cell = app.cells.containing(.staticText, identifier: file.label).firstMatch
        XCTAssertTrue(cell.waitForExistence(timeout: 5), app.debugDescription)
        cell.tap()
    }

    private func chooseLocalFolder(_ app: XCUIApplication) {
        // A provider can reopen directly in this folder. Otherwise wait for
        // the actual system tab bar; a two-second optional lookup raced the
        // first Files launch and silently left the test on No Recents.
        let currentFolder = app.navigationBars.buttons.matching(
            NSPredicate(format: "label BEGINSWITH %@", "MultiTimerProfiles, Actions Menu")).firstMatch
        let browse = app.tabBars.buttons["Browse"].firstMatch
        let local = app.staticTexts["On My iPhone"].firstMatch
        let folder = app.cells.containing(.staticText, identifier: "MultiTimerProfiles").firstMatch
        // Browse can itself reopen the last folder instead of Locations.
        // Re-evaluate the visible state after each navigation. Requiring the
        // Locations label after Browse failed even while the file was visible.
        for _ in 0..<30 {
            if currentFolder.waitForExistence(timeout: 1) { return }
            if folder.exists && folder.isHittable { folder.tap(); continue }
            if local.exists && local.isHittable { local.tap(); continue }
            if browse.exists && !browse.isSelected { browse.tap() }
        }
        XCTFail("Could not reach the local app folder: " + app.debugDescription)
    }

    /// Keep actual simulator captures in the XCTest result for later review;
    /// their presence is evidence of rendered screens, not an audio benchmark.
    private func screenshot(_ app: XCUIApplication, name: String) {
        // Capture the displayed simulator pixels directly. Asking the app for
        // another hierarchy-backed snapshot immediately after Files dismisses
        // can time out even after the export completion assertion passed.
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
