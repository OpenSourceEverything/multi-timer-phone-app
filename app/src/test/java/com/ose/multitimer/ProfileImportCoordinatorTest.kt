package com.ose.multitimer

import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises import decisions with a manually advanced worker instead of sleeps.
 *
 * Holding worker actions in a queue lets a test deliver an older decode after a
 * newer request, or pause immediately before persistence. These tests prove the
 * coordinator's state rules; they do not construct Android views or claim that
 * SharedPreferences is crash-durable.
 */
class ProfileImportCoordinatorTest {
    @Test
    fun completeDecodeProducesPreviewBeforeAnyProfileIsWritten() {
        val worker = QueuedExecutor()
        val saved = mutableListOf<List<BeepProfile>>()
        val observed = mutableListOf<ProfileImportCoordinator.State>()
        val profile = periodicProfile("Read me")
        var decodedFirstByte = -1
        val coordinator = coordinator(worker, saved, decode = { bytes ->
            decodedFirstByte = bytes[0].toInt()
            listOf(profile)
        }) {
            observed.add(it)
        }

        val originalBytes = byteArrayOf(7)
        coordinator.load(originalBytes)
        originalBytes[0] = 9 // Asynchronous decoding must receive the copied byte.

        assertTrue(coordinator.state is ProfileImportCoordinator.State.Loading)
        assertTrue(saved.isEmpty())
        worker.runNext()

        val preview = coordinator.state as ProfileImportCoordinator.State.Preview
        assertEquals(listOf("Read me"), preview.profiles.map { it.name })
        assertEquals(setOf(0), preview.selectedIndices)
        assertEquals(2, observed.size)
        assertTrue(observed[0] is ProfileImportCoordinator.State.Loading)
        assertTrue(observed[1] is ProfileImportCoordinator.State.Preview)
        assertEquals(7, decodedFirstByte)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun olderDecodeCannotReplaceANewerPreview() {
        val worker = QueuedExecutor()
        val saved = mutableListOf<List<BeepProfile>>()
        val coordinator = coordinator(worker, saved, decode = { bytes ->
            listOf(periodicProfile(if (bytes[0].toInt() == 1) "Old" else "New"))
        })

        val olderRequest = coordinator.load(byteArrayOf(1))
        val newerRequest = coordinator.load(byteArrayOf(2))
        assertTrue(newerRequest > olderRequest)

        // Deliver the first request after the second one has already started.
        worker.runNext()
        val stillLoading = coordinator.state as ProfileImportCoordinator.State.Loading
        assertEquals(newerRequest, stillLoading.requestId)

        worker.runNext()
        val preview = coordinator.state as ProfileImportCoordinator.State.Preview
        assertEquals(newerRequest, preview.requestId)
        assertEquals("New", preview.profiles.single().name)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun cancellingLoadInvalidatesItsLateResultAndDoesNotWrite() {
        val worker = QueuedExecutor()
        val saved = mutableListOf<List<BeepProfile>>()
        val coordinator = coordinator(worker, saved, decode = { listOf(periodicProfile("Late")) })

        coordinator.load(byteArrayOf(1))
        assertTrue(coordinator.cancel())
        val idle = coordinator.state as ProfileImportCoordinator.State.Idle
        worker.runNext()

        assertEquals(idle, coordinator.state)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun cancellingPreviewBeforeConfirmationDoesNotWrite() {
        val worker = QueuedExecutor()
        val saved = mutableListOf<List<BeepProfile>>()
        val coordinator = coordinator(worker, saved, decode = { listOf(periodicProfile("Preview")) })
        coordinator.load(byteArrayOf(1))
        worker.runNext()

        assertTrue(coordinator.state is ProfileImportCoordinator.State.Preview)
        assertTrue(coordinator.cancel())
        assertTrue(coordinator.state is ProfileImportCoordinator.State.Idle)
        assertFalse(coordinator.confirm())
        assertTrue(worker.isEmpty())
        assertTrue(saved.isEmpty())
    }

    @Test
    fun equalNamesRemainIndependentlySelectableByPreviewRow() {
        val worker = QueuedExecutor()
        val saved = mutableListOf<List<BeepProfile>>()
        val coordinator = coordinator(worker, saved, decode = {
            listOf(periodicProfile("Same name"), periodicProfile("Same name"))
        })
        coordinator.load(byteArrayOf(1))
        worker.runNext()

        val preview = coordinator.state as ProfileImportCoordinator.State.Preview
        assertEquals(2, preview.selectedCount)
        assertTrue(coordinator.setSelected(0, false))
        assertFalse(coordinator.setSelected(2, false))
        assertTrue(coordinator.confirm())
        worker.runNext()

        assertEquals(1, saved.single().size)
        assertEquals("Same name", saved.single().single().name)
        assertTrue(coordinator.state is ProfileImportCoordinator.State.Success)
    }

    @Test
    fun emptySelectionCannotStartAPersistentWrite() {
        val worker = QueuedExecutor()
        val saved = mutableListOf<List<BeepProfile>>()
        val coordinator = coordinator(worker, saved, decode = { listOf(periodicProfile("One")) })
        coordinator.load(byteArrayOf(1))
        worker.runNext()

        assertTrue(coordinator.setSelected(0, false))
        assertFalse(coordinator.confirm())
        assertTrue(coordinator.state is ProfileImportCoordinator.State.Preview)
        assertTrue(worker.isEmpty())
        assertTrue(saved.isEmpty())
    }

    @Test
    fun confirmationRunsOnceAndCreatesFreshProfileAndEventIds() {
        val worker = QueuedExecutor()
        val saved = mutableListOf<List<BeepProfile>>()
        val scripted = scriptedProfile("Same", "Begin")
        val coordinator = coordinator(worker, saved, decode = {
            listOf(scripted, scriptedProfile("Same", "Begin"))
        })
        coordinator.load(byteArrayOf(1))
        worker.runNext()

        assertTrue(coordinator.confirm())
        assertTrue(coordinator.state is ProfileImportCoordinator.State.Saving)
        assertFalse(coordinator.confirm())
        assertFalse(coordinator.cancel())
        assertThrows(IllegalStateException::class.java) {
            coordinator.load(byteArrayOf(2))
        }
        assertTrue(saved.isEmpty())

        worker.runNext()
        val imported = saved.single()
        assertEquals(2, imported.size)
        assertNotNull(imported[0].id)
        assertNotNull(imported[0].events.single().id)
        assertFalse(imported[0].id == imported[1].id)
        assertFalse(imported[0].events.single().id == imported[1].events.single().id)
        val success = coordinator.state as ProfileImportCoordinator.State.Success
        assertEquals(2, success.importedCount)
    }

    @Test
    fun invalidWholeBundleNeverReachesPreviewOrStorage() {
        val worker = QueuedExecutor()
        val saved = mutableListOf<List<BeepProfile>>()
        val coordinator = coordinator(worker, saved, decode = {
            throw IllegalArgumentException("profiles[1] is invalid")
        })
        coordinator.load(byteArrayOf(1))
        worker.runNext()

        val error = coordinator.state as ProfileImportCoordinator.State.Error
        assertTrue(error.message.contains("profiles[1]"))
        assertTrue(saved.isEmpty())
    }

    @Test
    fun oversizedDocumentIsRejectedBeforeDecoderReceivesBytes() {
        val worker = QueuedExecutor()
        val saved = mutableListOf<List<BeepProfile>>()
        var decoderCalled = false
        val coordinator = coordinator(worker, saved, decode = {
            decoderCalled = true
            listOf(periodicProfile("Must not appear"))
        })
        val oversized = ByteArray(ProfileSharingLimits.MAX_DOCUMENT_BYTES + 1)

        coordinator.load(oversized)
        worker.runNext()

        val error = coordinator.state as ProfileImportCoordinator.State.Error
        assertTrue(error.message.contains("exceeds"))
        assertFalse(decoderCalled)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun failedBatchWriteProducesErrorRatherThanSuccess() {
        val worker = QueuedExecutor()
        val coordinator = ProfileImportCoordinator(
            worker = worker,
            appendProfiles = { throw IllegalStateException("storage unavailable") },
            decodeBundle = { listOf(periodicProfile("One")) },
        )
        coordinator.load(byteArrayOf(1))
        worker.runNext()
        assertTrue(coordinator.confirm())
        worker.runNext()

        val error = coordinator.state as ProfileImportCoordinator.State.Error
        assertEquals("storage unavailable", error.message)
    }

    /** Builds the coordinator with an observable fake batch writer. */
    private fun coordinator(
        worker: QueuedExecutor,
        saved: MutableList<List<BeepProfile>>,
        decode: (ByteArray) -> List<SharedProfile>,
        listener: (ProfileImportCoordinator.State) -> Unit = {},
    ): ProfileImportCoordinator {
        return ProfileImportCoordinator(
            worker = worker,
            appendProfiles = { batch -> saved.add(batch) },
            decodeBundle = decode,
            onStateChanged = listener,
        )
    }

    /** Makes a valid periodic snapshot without involving Android storage. */
    private fun periodicProfile(name: String): SharedProfile {
        return SharedProfile(
            name = name,
            mode = BeepProfile.Mode.PERIODIC,
            beepDurationMillis = 500L,
            periodMillis = 1_000L,
            events = emptyList(),
            beepOnCue = true,
            beepOnCompletion = false,
            speakOnCompletion = false,
            speakCurrentTime = false,
            speakElapsedTime = false,
            speakRemainingTime = false,
            speakOnCue = false,
            speakClockNameWithSpeech = false,
        )
    }

    /** Makes a valid scripted snapshot whose cue IDs must be new on import. */
    private fun scriptedProfile(name: String, speech: String): SharedProfile {
        return SharedProfile(
            name = name,
            mode = BeepProfile.Mode.SCRIPTED,
            beepDurationMillis = 500L,
            periodMillis = 0L,
            events = listOf(SharedCue(0L, speech, 0L)),
            beepOnCue = false,
            beepOnCompletion = false,
            speakOnCompletion = false,
            speakCurrentTime = false,
            speakElapsedTime = false,
            speakRemainingTime = false,
            speakOnCue = true,
            speakClockNameWithSpeech = false,
        )
    }

    /** Queues work so tests control completion order without timing sleeps. */
    private class QueuedExecutor : Executor {
        private val tasks = ArrayList<Runnable>()

        override fun execute(command: Runnable) {
            tasks.add(command)
        }

        fun runNext() {
            check(tasks.isNotEmpty()) { "No queued worker task" }
            tasks.removeAt(0).run()
        }

        fun isEmpty(): Boolean = tasks.isEmpty()
    }
}
