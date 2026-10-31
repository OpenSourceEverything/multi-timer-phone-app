# Multi Timer

A small native Android app for running multiple independent clocks and reusable audible cue profiles.

## Clock types

- **Stopwatch** counts up from zero.
- **Timer** counts down from a duration.
- **Countdown** counts down to a selected calendar date and time.

Each clock can be started, paused, reset, edited, or deleted without affecting
the others. Edit changes the name and audible profiles without disturbing progress;
changing a timer duration or countdown target resets and pauses only that clock.
Running clocks are persisted through process recreation. The app uses a
foreground service and a partial wake lock for background cue processing, but
Android or manufacturer battery rules can still delay cues with the screen off.
On Samsung phones, see [screen-off cue troubleshooting](#screen-off-cue-troubleshooting).

Timer and fixed-period inputs use separate `HH`, `MM`, and `SS` fields. Tap a
field, type two digits, and focus advances to the next field.

## Beep profiles

A profile sets the beep length (50–10,000 ms) and one of two schedules. A clock
can use multiple profiles at once, so (for example) one profile can cue every
30 seconds while another cues every minute. Each profile keeps its own schedule
checkpoint, so overlapping schedules do not skip one another.

- **Fixed period:** repeat one cue at an arbitrary interval, such as 30 seconds
  or one minute.
- **Scripted entries:** define any number of arbitrary cues. Each cue can speak
  text, beep, or do both at its own offset from the clock start. Enter pipe rows
  such as:

  ```text
  00:00 | say "Start" | beep
  00:30 | say "Rest"
  01:00 | say "Next set" | beep 750
  ```

  A small TOML-like form is also accepted:

  ```text
  [[event]]
  at = "01:30"
  say = "Resume"
  beep = true
  ```

  `beep` without a value uses the profile's default beep length. Times are
  absolute offsets from the start of the clock, and event rows are sorted by
  time.

Profiles can also beep or speak when a duration timer or date countdown finishes.
Built-in profiles include **Beep (Every Minute)**, **Beep At Completion**,
**Speak Current Time (Every Minute)**, **Time Elapsed (Spoken Aloud)**, and
**Time Remaining (Spoken Aloud)**. The speech profiles do not beep. Time
remaining applies to timers and date countdowns; time elapsed applies to every
clock. Spoken cues do not say the clock name by default; enable that option
in the profile when overlapping clocks need to identify themselves. This applies
to scripted text and spoken time values. Choose
**Silent** on a clock to disable its sounds.
Beeps use the device alarm audio stream, so alarm volume and Do Not Disturb
settings apply.

Profiles are reusable, but they can also be duplicated. Use **Duplicate** in
the profile list to create an independent copy before changing one timer's
behavior. The profile editor separates the schedule, scheduled actions, and
finish actions; beep and speech can be combined.

For a 30-minute timer that beeps every minute:

1. Open **Reusable profiles**, add a **Fixed period** profile, set **Repeat every** to
   `00:01:00`, choose the beep length, and save it.
2. Add a Timer, enter `3000` to display `00:30:00`, select that profile, and tap
   **Add**.
3. Tap **Start**. Use **Edit** later to change its name, duration, or profile.

When editing or creating a clock, select multiple reusable profiles to combine
schedules, or use **Create reusable profile…** directly from the clock form.
Use **Customize for this timer…** when you want a private copy that only this
timer can use. In a profile editor,
**Scripted entries** is the simple custom-cue path: add as many timed rows as
needed. First choose **Beep**, **Speak**, or both; each choice reveals only its
relevant settings. **Speak when a timer or countdown finishes** adds the spoken end event,
and the spoken-time selector can announce the current time, elapsed time, or
remaining time at each scheduled cue.

From a clock form, select one profile and choose **Customize for this timer…**
to clone it automatically. The clone is attached to that clock only, so its
interval or actions can change without affecting other clocks. Timer-owned
copies are hidden from the global Profiles template list.

## Screen-off cue behavior

Repeated cues may be delayed on some Android devices after the screen locks.
MultiTimer records diagnostic evidence for missed and late cues, but Android
and device-manufacturer power rules can still delay background work. No manual
battery-setting change is a prerequisite or a guaranteed fix. See
[postmortem diagnostics](docs/tasks/002-postmortem-diagnostics.md) for the
tested Samsung behavior and the current reliability work.

## Share reusable profiles

Export selected reusable profiles as a JSON file, then import that file later.
Import shows a preview and saves only after you confirm. See the
[profile sharing guide](docs/user-guide/profile-sharing.md) for the steps and a
real-device demonstration.

## SwiftUI profile companion

The [native companion](ios/MultiTimerProfiles/README.md) edits the same portable
profiles with separate draft/saved state, a bounded visual cue preview and local
file exchange. Its hosted Xcode build, strict codec and editor/visual-preview
tests and native local-file import/export are verified. The
[illustrated companion guide](docs/user-guide/companion.md) shows the controls;
the verified two-app workflow is recorded in the
[sharing roadmap](docs/tasks/profile-sharing/README.md). The Android timing
engine remains in the Android app. No account or network service is required
for the local-file workflow.

## Build and install

Requirements: JDK 17, Android SDK platform 36 and SDK Build Tools 35.0.0.
Use the checked-in Gradle wrapper; a separate system Gradle install is not needed.

On Linux/macOS, set ANDROID_HOME to your existing SDK installation (or configure
sdk.dir in an untracked local.properties file), then run:

```sh
export ANDROID_HOME='/path/to/Android/Sdk'
bash gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The SDK location above is a placeholder, not an installation command. On a
fresh machine, install the platform and build tools using Android Studio's SDK
Manager. Keep local.properties out of Git.

On Windows PowerShell:

```text
$env:JAVA_HOME = 'C:\path\to\jdk-17'
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

Debug and release builds use the same application ID, `com.ose.multitimer`.
Installing a debug build with `adb install -r` updates the main app in place,
so timer and profile data stay in that package instead of creating a second
debug app. Debug and release APKs cannot update one another when their signing
certificates differ; do not install a debug APK over a Play-signed build.
Android CI checks that the debug APK keeps this package ID and exposes exactly
one Multi Timer launcher.

Tap **Export diagnostics** on the main screen to share a ZIP containing a
readable timer-event report and structured JSON evidence. It includes timer and
profile names plus device state; review the destination before sharing. The
report explains observed event and audio transitions, with confidence levels
for incomplete or late cues. It cannot prove that a specific Android power
restriction caused a missed sound.

Test reports are under app/build/reports/tests/testDebugUnitTest; lint reports
are app/build/reports/lint-results-debug.html and .txt. The
[Android checks workflow](.github/workflows/android.yml) runs the same tasks
on pushes to main and pull requests, and retains reports. Its status is the
result of an actual hosted run, not the presence of the workflow file.
These checks do not replace physical-device audio, screen-lock or service
recovery testing. They require no Play account or release signing key.

Run the Activity lifecycle tests only on a disposable emulator. Do not leave a
daily-use phone attached: Gradle's connected-test teardown can uninstall the
main app package and clear its private timer/profile data. The tests hold
profile-import work while Android recreates the real app screen:

```sh
bash gradlew --no-daemon connectedDebugAndroidTest
```

The tests import and remove one uniquely named profile. They distinguish Activity recreation from process
death; passing them does not prove recovery after Android kills the process.

The main screen displays the compiled version name and Play version code, for
example `v1.5.0 (build 9)`. The version name is user-facing; the version code
is Play's numeric update-order identifier.

## Build the Play bundle

Release signing reads `%USERPROFILE%\.android\ose-multitimer-upload.properties`.
That private file supplies `storeFile`, `storePassword`, `keyAlias`, and
`keyPassword`; neither it nor the upload keystore belongs in source control.

```text
.\gradlew.bat clean testDebugUnitTest lintDebug bundleRelease
```

Upload `app\build\outputs\bundle\release\app-release.aab` to the Play Console
internal-testing track. Before the first upload, back up the upload keystore and
credential in approved encrypted storage and confirm that `com.ose.multitimer`
is the intended permanent package identity.

The timing service uses the `specialUse` foreground-service type. In Play
Console, describe its user-started background timers and audible cue schedules
under **Policy > App content**, and provide the requested demonstration video.

## In-app update prompts

Play-installed builds use Google Play's in-app update flow. When a newer bundle
is available, the app asks the user to start a flexible update, downloads it in
the background, and offers **Restart now** when installation is ready. If Play
requires an immediate flow, the app uses that flow instead. Debug or sideloaded
APKs do not receive this prompt because Google Play does not manage their
updates.

## Distribute one build to multiple phones through Google Play

An Android App Bundle (`.aab`) is uploaded to Play; phones install the generated
APK split set from Play. Use the same application ID and signing/upload key for
every update, and increase `versionCode` for each uploaded bundle.

Read versionName and versionCode from [app/build.gradle](app/build.gradle) for
the source being packaged, and compare with the last uploaded Play version.
For each subsequent upload, the bundle must use a higher version code. For
example, these values describe a historical build, not a new upload candidate:

```groovy
versionCode 5
versionName '1.1.2'
```

Then build the signed bundle from this directory:

```text
set JAVA_HOME=C:\path\to\jdk-17
gradlew.bat clean testDebugUnitTest lintDebug bundleRelease
```

The result is `app\build\outputs\bundle\release\app-release.aab`. Upload it in
Play Console under **Test and release → Internal testing**, create the release,
add release notes, review, and start the internal rollout. Do not upload the
debug APK or a bundle signed with a different key.

On each phone, open the tester opt-in link while signed into an invited Google
account, join the test, and install the app from Google Play once. Later
releases then appear in Play automatically when auto-update is enabled. A
manual check is **Play Store → profile → Manage apps and device → Updates
available**. Auto-update is controlled on each phone under **Play Store →
profile → Settings → Network preferences → Auto-update apps**.

Play does not instantly force-update every personal device: rollout eligibility,
network settings, charging, and Play's update checks can delay installation.
The internal-test installation must come from Play; a sideloaded debug APK is
not enrolled in the Play update path.

## Maintaining the app

See [profile-sharing instructions](docs/profile-sharing.md) for the local
export/import workflow and its cancellation/identity rules.

Portable profile exchange is being developed incrementally. The
[version-one format](docs/profile-format.md) and
[shared examples](profiles/fixtures/v1/README.md) define reusable templates
without local clock identities. Android now has local JSON export and
preview/confirm import in the reusable-profile screen. Automated checks and
basic Android 12 device flows passed; main-screen configuration-change
rotation was checked, while rotation during import and import-in-progress
lifecycle checks remain open. The [task index](docs/tasks/profile-sharing/README.md)
tracks the detailed Android work and the separate planned SwiftUI companion.

Start with the [engineering task index](docs/tasks/README.md) for scoped work
and [contribution guidelines](CONTRIBUTING.md) for readable code, explanatory
comments and meaningful tests. Keep ordinary timing offline and preserve
existing behavior before introducing new architectural layers.
