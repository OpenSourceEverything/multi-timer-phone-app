# Multi Timer

A small native Android app for running multiple independent clocks and reusable audible cue profiles.

## Clock types

- **Stopwatch** counts up from zero.
- **Timer** counts down from a duration.
- **Countdown** counts down to a selected calendar date and time.

Each clock can be started, paused, reset, edited, or deleted without affecting
the others. Edit changes the name and beep profile without disturbing progress;
changing a timer duration or countdown target resets and pauses only that clock.
Running clocks are persisted through process recreation. A foreground
notification keeps timing and audible cues active while the app is in the
background.

Timer and fixed-period inputs use separate `HH`, `MM`, and `SS` fields. Tap a
field, type two digits, and focus advances to the next field.

## Beep profiles

A profile sets the beep length (50–10,000 ms) and one of two schedules:

- **Fixed period:** beep every duration, such as one minute.
- **Scripted entries:** speak text, beep, or do both at offsets from the clock
  start. Enter pipe rows such as:

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

Profiles can also beep when a duration timer or date countdown finishes. Choose
**Silent** on a clock to disable its sounds. Beeps use the device alarm audio
stream, so alarm volume and Do Not Disturb settings apply.

For a 30-minute timer that beeps every minute:

1. Open **Profiles**, add a **Fixed period** profile, set **Beep every** to
   `00:01:00`, choose the beep length, and save it.
2. Add a Timer, enter `3000` to display `00:30:00`, select that profile, and tap
   **Add**.
3. Tap **Start**. Use **Edit** later to change its name, duration, or profile.

## Build and install

Requirements: JDK 17 and Android SDK 36.

On Windows PowerShell:

```text
$env:JAVA_HOME = 'C:\path\to\jdk-17'
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

The application ID is `com.ose.multitimer`.

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

## Distribute one build to multiple phones through Google Play

An Android App Bundle (`.aab`) is uploaded to Play; phones install the generated
APK split set from Play. Use the same application ID and signing/upload key for
every update, and increase `versionCode` for each uploaded bundle.

For the next release, update `app/build.gradle` before building, for example:

```groovy
versionCode 3
versionName '1.1.0'
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
