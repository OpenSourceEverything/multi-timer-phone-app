package com.ose.multitimer

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * Drives the real Activity's selection, streams, preview and confirmation.
 *
 * Run on a disposable emulator. Android's document-picker result is stubbed,
 * but the app itself reads/writes the selected files. The physical-device
 * picker has separate evidence; this test must not claim to exercise it.
 * An optional companion export can replace the self-round-trip input, allowing
 * CI to join the two apps without a production fixture-loading backdoor.
 */
@RunWith(AndroidJUnit4::class)
class ProfileFileExchangeTest {
    @get:Rule
    val notificationPermission: TestRule =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
        } else TestRule { base, _ -> base }

    @Test
    fun selectedFileRoundTripPreservesProfilesAndAttachedRunningClock() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = AppStore(context.getSharedPreferences(AppStore.PREFERENCES_NAME, Context.MODE_PRIVATE))
        store.ensureDefaultProfiles()
        val before = store.loadReusableProfiles().associate { it.id to it.toJson().toString() }
        // Android, simulator setup and both CI collectors read this one public
        // example. It includes Unicode in names and speech without requiring
        // XCTest's keyboard synthesizer to type characters it cannot generate.
        val expected = instrumentation.context.assets.open("exchange-examples.json")
            .use { ProfileBundleCodec.decode(it.readBytes()) }
        val originals = expected.map { it.toNewProfile() }
        val createdIds = originals.map { it.id }.toMutableSet()
        store.appendReusableProfiles(originals)
        // Completion-only means this ten-minute clock produces no sound while
        // we exercise file I/O. Its identity/attachment must survive importing.
        val clock = ClockInstance.create(ClockInstance.Type.TIMER,
            "Exchange check ${UUID.randomUUID()}", 600_000L,
            originals[2].id, System.currentTimeMillis(), SystemClock.elapsedRealtime())
        clock.start(System.currentTimeMillis(), SystemClock.elapsedRealtime())
        store.putClockInstance(clock)
        val output = File(context.cacheDir, "exchange-${UUID.randomUUID()}.json")
        val input = File(context.cacheDir, "import-${UUID.randomUUID()}.json")
        var scenario: ActivityScenario<MainActivity>? = null
        Intents.init()
        try {
            Intents.intending(hasAction(Intent.ACTION_CREATE_DOCUMENT)).respondWith(
                Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(output))))
            val active = ActivityScenario.launch(MainActivity::class.java)
            scenario = active
            active.onActivity { activity ->
                button(activity.window.decorView, "Reusable profiles").performClick()
                val list = requireNotNull(dialog(activity, "profilesDialog")).window!!.decorView
                descendants(list).filterIsInstance<CheckBox>()
                    .filter { check -> expected.any { it.name == check.text.toString() } }
                    .forEach { it.isChecked = true }
                button(list, "Export selected").performClick()
            }
            await("Actual export stream did not finish") {
                output.length() > 0 && transferFinished(active)
            }
            assertProfiles(expected, ProfileBundleCodec.decode(output.readBytes()))
            // Only public examples leave the emulator. No local IDs, user
            // library, diagnostics or account data enter the workflow output.
            instrumentation.sendStatus(0, Bundle().apply {
                putString("multitimer_export_base64", Base64.encodeToString(output.readBytes(), Base64.NO_WRAP))
            })
            val external = InstrumentationRegistry.getArguments().getString("exchangeInputBase64")
            val incoming = if (external == null) output.readBytes() else Base64.decode(external, Base64.DEFAULT)
            val incomingProfiles = ProfileBundleCodec.decode(incoming)
            if (external != null) {
                // Require the promised real UI edit, not just a Swift encode.
                val edited = JSONObject(String(ProfileBundleCodec.encode(expected), Charsets.UTF_8))
                val scripted = edited.getJSONArray("profiles").getJSONObject(1)
                scripted.put("name", "Exchange example edited")
                scripted.getJSONArray("events").getJSONObject(0).put("offsetMillis", 1_000)
                assertProfiles(ProfileBundleCodec.decode(edited.toString().toByteArray()), incomingProfiles)
            }
            val unchangedIds = store.loadReusableProfiles().map { it.id }.toSet()
            input.writeText("invalid JSON")
            chooseImport(active, input)
            await("Malformed import should show an error") {
                var failed = false
                active.onActivity { activity ->
                    val field = MainActivity::class.java.getDeclaredField("profileImportCoordinator")
                    field.isAccessible = true
                    failed = (field.get(activity) as? ProfileImportCoordinator)?.state is ProfileImportCoordinator.State.Error
                }
                failed
            }
            assertEquals(unchangedIds, store.loadReusableProfiles().map { it.id }.toSet())
            onView(withText("OK")).perform(click())
            input.writeBytes(incoming)
            chooseImport(active, input)
            await("Preview was not delivered") { hasPreview(active) }
            active.onActivity { activity ->
                requireNotNull(dialog(activity, "profilePreviewDialog"))
                    .getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            }
            assertEquals(unchangedIds, store.loadReusableProfiles().map { it.id }.toSet())
            chooseImport(active, input)
            await("Second preview was not delivered") { hasPreview(active) }
            active.onActivity { activity ->
                requireNotNull(dialog(activity, "profilePreviewDialog"))
                    .getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            await("Confirmed batch was not persisted") {
                store.loadReusableProfiles().size == unchangedIds.size + incomingProfiles.size
            }
            val copies = store.loadReusableProfiles().filter { it.id !in unchangedIds }
            createdIds.addAll(copies.map { it.id })
            assertProfiles(incomingProfiles, copies.map { SharedProfile.fromProfile(it) })
            assertTrue(copies.none { copy -> originals.any { it.id == copy.id } })
            val oldEventIds = originals.flatMap { it.events }.map { it.id }.toSet()
            assertTrue(copies.flatMap { it.events }.none { it.id in oldEventIds })
            before.forEach { (id, json) -> assertEquals(json, store.getProfile(id).toJson().toString()) }
            val preserved = requireNotNull(store.getClockInstance(clock.id))
            assertTrue(preserved.running)
            assertEquals(clock.profileIds, preserved.profileIds)
            assertTrue(preserved.valueAt(System.currentTimeMillis(), SystemClock.elapsedRealtime()) in 1..600_000L)
        } finally {
            scenario?.close()
            store.deleteClockInstance(clock.id)
            createdIds.forEach { store.deleteProfile(it) }
            // Reload remaining clocks after removing this test's clock; never
            // clear another profile, stop all timers or reset app preferences.
            context.startForegroundService(Intent(context, TimingService::class.java).setAction(TimingService.ACTION_SYNC))
            output.delete()
            input.delete()
            Intents.release()
        }
    }

    /** Compare all portable fields while allowing the library's row ordering. */
    private fun assertProfiles(expected: List<SharedProfile>, actual: List<SharedProfile>) {
        assertArrayEquals(ProfileBundleCodec.encode(expected.sortedBy { it.name }),
            ProfileBundleCodec.encode(actual.sortedBy { it.name }))
    }

    /** Deliver a controlled document URI, then use the Activity's real buttons. */
    private fun chooseImport(scenario: ActivityScenario<MainActivity>, input: File) {
        Intents.intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(
            Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(input))))
        scenario.onActivity { activity ->
            button(activity.window.decorView, "Reusable profiles").performClick()
            button(requireNotNull(dialog(activity, "profilesDialog")).window!!.decorView, "Import file").performClick()
        }
    }

    /** Observe this Activity's actual modal owner without adding a product test hook. */
    private fun dialog(activity: MainActivity, name: String): AlertDialog? =
        MainActivity::class.java.getDeclaredField(name).let { it.isAccessible = true; it.get(activity) as? AlertDialog }

    /** Walk programmatic controls, including buttons nested in profile rows. */
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    /** Require exactly one intended button instead of clicking an ambiguous match. */
    private fun button(root: View, text: String): Button = descendants(root).filterIsInstance<Button>()
        .single { it.text.toString() == text }

    /** Read visible preview state on its owning Android main thread. */
    private fun hasPreview(scenario: ActivityScenario<MainActivity>): Boolean {
        var result = false
        scenario.onActivity { result = dialog(it, "profilePreviewDialog")?.isShowing == true }
        return result
    }

    /** Progress is gone when I/O finishes; an error confirmation is also terminal. */
    private fun transferFinished(scenario: ActivityScenario<MainActivity>): Boolean {
        var result = false
        scenario.onActivity {
            val progress = dialog(it, "profileTransferDialog")
            result = progress == null || progress.getButton(AlertDialog.BUTTON_POSITIVE)?.isShown == true
        }
        return result
    }

    /** Bounded asynchronous-I/O wait, not a claim about timer precision. */
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            if (condition()) return
            SystemClock.sleep(25)
        }
        fail(message)
    }
}
