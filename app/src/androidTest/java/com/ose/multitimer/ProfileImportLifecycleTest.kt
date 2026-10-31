package com.ose.multitimer

import android.Manifest
import android.app.AlertDialog
import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.Intents.init
import androidx.test.espresso.intent.Intents.release
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.io.File
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import org.hamcrest.Matchers.containsString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * Exercises profile import while Android destroys and recreates MainActivity.
 *
 * These are instrumentation tests rather than plain JVM tests because they
 * launch the real Activity and call ActivityScenario.recreate(). A manually
 * controlled executor pauses background work at exact boundaries, so the test
 * never races a fast import with a screen rotation or relies on Thread.sleep.
 */
@RunWith(AndroidJUnit4::class)
class ProfileImportLifecycleTest {
    /**
     * Android 13+ asks for notification permission before MainActivity can
     * finish launching. On Android 12 and earlier this permission is not a
     * runtime permission, so asking UiAutomation to grant it throws instead
     * of helping the test.
     */
    @get:Rule
    val notificationPermission: TestRule =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            TestRule { base, _ -> base }
        }

    @Test
    fun recreatingWhileLoadingDiscardsTheLateDecodeAndPreservesProfiles() {
        val profileName = uniqueProfileName("Loading")
        val bundleFile = createBundleFile(profileName)
        val initialIds = reusableStore().loadReusableProfiles().map { it.id }.toSet()
        val worker = HoldingExecutorService()
        var scenario: ActivityScenario<MainActivity>? = null
        init()

        try {
            scenario = launchWith(worker)
            chooseImportFile(bundleFile)

            // Run only the file read. The coordinator's decode is now queued,
            // while its Loading state and progress dialog are still active.
            assertTrue("Expected the file-read task", worker.runNext())
            scenario.onActivity { activity ->
                // ActivityScenario runs this check on MainActivity's main
                // thread, after the file-read callback already posted there.
                // Inspect the real coordinator and dialog directly. Espresso
                // can lose the Activity window while Android replaces a dialog.
                val coordinator = importCoordinator(activity)
                assertTrue(
                    "The selected file should now be held in Loading",
                    coordinator?.state is ProfileImportCoordinator.State.Loading,
                )
                assertProgressMessage(activity, "Validating every profile")
            }
            assertEquals("Decode must still be held by the test", 1, worker.queuedCount)

            scenario.recreate()
            assertTrue("Expected the held decode task", worker.runNext())

            // MainActivity cancels Loading on destruction. Completing the old
            // decode afterward must not save a profile or reopen its preview.
            assertEquals(initialIds, reusableStore().loadReusableProfiles().map { it.id }.toSet())
            scenario.onActivity { activity ->
                assertNull(
                    "A late decode must not reopen import preview",
                    privateActivityField(activity, "profilePreviewDialog"),
                )
            }
        } finally {
            scenario?.close()
            // Finish any task left behind by a failed assertion before removing
            // this test's uniquely named data and temporary input file.
            worker.runRemaining()
            removeProfilesNamed(profileName)
            bundleFile.delete()
            release()
        }
    }

    @Test
    fun recreatingWhileSavingLetsTheBatchFinishExactlyOnce() {
        val profileName = uniqueProfileName("Saving")
        val bundleFile = createBundleFile(profileName)
        val initialIds = reusableStore().loadReusableProfiles().map { it.id }.toSet()
        val worker = HoldingExecutorService()
        var scenario: ActivityScenario<MainActivity>? = null
        init()

        try {
            scenario = launchWith(worker)
            chooseImportFile(bundleFile)

            // Advance the read, then the decode. Preview is the user's review
            // point; no persistent profile exists until its Import button.
            assertTrue("Expected the file-read task", worker.runNext())
            scenario.onActivity { activity ->
                assertTrue(
                    "The selected file should now be held in Loading",
                    importCoordinator(activity)?.state is ProfileImportCoordinator.State.Loading,
                )
                assertProgressMessage(activity, "Validating every profile")
            }
            assertTrue("Expected the profile-decode task", worker.runNext())

            // The worker now holds the actual AppStore append operation. This
            // creates a deterministic window after Saving starts but before
            // the write, the exact point that cannot be hit reliably by hand.
            scenario.onActivity { activity ->
                val coordinator = importCoordinator(activity)
                assertTrue(
                    "A valid bundle should be waiting for confirmation",
                    coordinator?.state is ProfileImportCoordinator.State.Preview,
                )
                val previewDialog = privateActivityField(activity, "profilePreviewDialog")
                    as? AlertDialog
                assertTrue("The real import preview should be visible", previewDialog?.isShowing == true)
                val confirmButton = checkNotNull(previewDialog)
                    .getButton(AlertDialog.BUTTON_POSITIVE)
                assertEquals("Import selected (1)", confirmButton.text.toString())

                // Click the actual dialog button on the UI thread. This tests
                // user confirmation without depending on window focus while
                // the previous progress dialog is being replaced.
                confirmButton.performClick()
                assertTrue(
                    "Confirmation should start the non-cancellable batch save",
                    coordinator?.state is ProfileImportCoordinator.State.Saving,
                )
                assertProgressMessage(activity, "Saving the selected profiles")
            }
            assertEquals("The profile write must still be held", 1, worker.queuedCount)

            scenario.recreate()
            assertTrue("Expected the held profile-write task", worker.runNext())

            // Activity destruction cannot roll back a write already underway.
            // The contract is one completed append, with no replay or duplicate.
            val savedProfiles = reusableStore().loadReusableProfiles()
                .filter { it.name == profileName }
            assertEquals(1, savedProfiles.size)
            assertEquals(initialIds + savedProfiles.single().id,
                reusableStore().loadReusableProfiles().map { it.id }.toSet())
            scenario.onActivity { activity ->
                assertNull(
                    "A recreated Activity must not replay the completed preview",
                    privateActivityField(activity, "profilePreviewDialog"),
                )
            }
        } finally {
            scenario?.close()
            worker.runRemaining()
            removeProfilesNamed(profileName)
            bundleFile.delete()
            release()
        }
    }

    /** Launches the real app and installs a worker that the test can advance. */
    private fun launchWith(worker: HoldingExecutorService): ActivityScenario<MainActivity> {
        val launched = ActivityScenario.launch(MainActivity::class.java)
        launched.onActivity { activity ->
            // Keep production code unchanged: reflection replaces only this
            // Activity instance's private worker with the test-controlled one.
            val executorField = MainActivity::class.java
                .getDeclaredField("profileFileExecutor")
            executorField.isAccessible = true
            executorField.set(activity, worker)
        }
        return launched
    }

    /** Reads a private Activity field so lifecycle assertions stay tied to its owner. */
    private fun privateActivityField(activity: MainActivity, fieldName: String): Any? {
        val field = MainActivity::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(activity)
    }

    /** Returns the coordinator currently attached to this Activity, if import started. */
    private fun importCoordinator(activity: MainActivity): ProfileImportCoordinator? =
        privateActivityField(activity, "profileImportCoordinator") as? ProfileImportCoordinator

    /** Checks progress text without relying on the Android window's focus state. */
    private fun assertProgressMessage(activity: MainActivity, messagePart: String) {
        val dialog = privateActivityField(activity, "profileTransferDialog") as? AlertDialog
        assertTrue("Expected the profile-transfer progress dialog", dialog?.isShowing == true)
        val messageView = checkNotNull(dialog).window?.decorView
            ?.findViewById<TextView>(android.R.id.message)
        assertTrue(
            "Expected progress text containing '$messagePart'",
            checkNotNull(messageView).text.toString().contains(messagePart),
        )
    }

    /** Stubs Android's picker result so the test follows the real import UI. */
    private fun chooseImportFile(bundleFile: File) {
        val resultData = Intent().setData(Uri.fromFile(bundleFile))
        intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(
            Instrumentation.ActivityResult(Activity.RESULT_OK, resultData),
        )
        onView(withText("Reusable profiles")).perform(
            androidx.test.espresso.action.ViewActions.click(),
        )
        onView(withText("Import file")).perform(
            androidx.test.espresso.action.ViewActions.click(),
        )
        onView(withText(containsString("Checking its size"))).check(matches(isDisplayed()))
    }

    /** Creates a valid one-profile JSON document with a name unique to this run. */
    private fun createBundleFile(profileName: String): File {
        val localProfile = BeepProfile.periodic(profileName, 500L, 60_000L, false)
        val bytes = ProfileBundleCodec.encode(
            listOf(SharedProfile.fromProfile(localProfile)),
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(context.cacheDir, "${UUID.randomUUID()}.json").apply {
            writeBytes(bytes)
        }
    }

    /** Opens the app's existing preferences without clearing unrelated user data. */
    private fun reusableStore(): AppStore {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences(AppStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
        return AppStore(preferences)
    }

    /** Removes only this test's imported template; clocks and other profiles remain untouched. */
    private fun removeProfilesNamed(profileName: String) {
        val store = reusableStore()
        store.loadReusableProfiles()
            .filter { it.name == profileName }
            .forEach { store.deleteProfile(it.id) }
    }

    private fun uniqueProfileName(stage: String): String =
        "Lifecycle $stage ${UUID.randomUUID()}"

    /**
     * An ExecutorService whose submitted work waits in a queue until the test
     * explicitly runs it. Shutdown still preserves already-submitted work, as
     * the real single-thread executor does when MainActivity is destroyed.
     */
    private class HoldingExecutorService : AbstractExecutorService() {
        private val pendingTasks = ArrayDeque<Runnable>()
        private var shutdown = false

        val queuedCount: Int
            @Synchronized get() = pendingTasks.size

        @Synchronized
        override fun execute(command: Runnable) {
            check(!shutdown) { "No new work may be submitted after shutdown" }
            pendingTasks.addLast(command)
        }

        fun runNext(): Boolean {
            val next = synchronized(this) {
                if (pendingTasks.isEmpty()) null else pendingTasks.removeFirst()
            } ?: return false
            next.run()
            return true
        }

        fun runRemaining() {
            while (runNext()) {
                // Each held task is completed once; any later stage it submits
                // is added to this same queue and is drained by the next loop.
            }
        }

        @Synchronized
        override fun shutdown() {
            shutdown = true
        }

        @Synchronized
        override fun shutdownNow(): MutableList<Runnable> {
            shutdown = true
            return ArrayList(pendingTasks).also { pendingTasks.clear() }
        }

        @Synchronized
        override fun isShutdown(): Boolean = shutdown

        @Synchronized
        override fun isTerminated(): Boolean = shutdown && pendingTasks.isEmpty()

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = isTerminated
    }
}
