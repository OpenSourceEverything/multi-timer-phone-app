import SwiftUI

/// Native companion entry point. Android continues to own background timers;
/// this app edits portable profile settings and previews them in the foreground.
@main
struct MultiTimerProfilesApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
