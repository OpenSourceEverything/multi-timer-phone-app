package com.ose.multitimer

import org.junit.Assert.*
import org.junit.Test

/** Contract tests for snapshots and fresh identities, independent of Android UI. */
class SharedProfileTest {
    @Test
    fun periodicConversionPreservesEveryFlagWithNewIdentity() {
        val original = BeepProfile.periodic("  My minute  ", 500L, 60_000L, false)
        original.beepOnCue = false
        original.speakOnCompletion = true
        original.speakCurrentTime = true
        original.speakElapsedTime = true
        original.speakRemainingTime = true
        original.speakOnCue = false
        original.speakClockNameWithSpeech = true
        // Preserve the original unnormalized value: export preview is read-only.
        original.name = "  My minute  "
        val originalId = original.id

        val snapshot = SharedProfile.fromProfile(original)
        val imported = snapshot.toNewProfile()

        assertEquals("My minute", imported.name)
        assertEquals("  My minute  ", original.name)
        assertEquals(originalId, original.id)
        assertNotEquals(originalId, imported.id)
        assertEquals(original.mode, imported.mode)
        assertEquals(original.periodMillis, imported.periodMillis)
        assertEquals(original.beepDurationMillis, imported.beepDurationMillis)
        assertEquals(original.beepOnCue, imported.beepOnCue)
        assertEquals(original.beepOnCompletion, imported.beepOnCompletion)
        assertEquals(original.speakOnCompletion, imported.speakOnCompletion)
        assertEquals(original.speakCurrentTime, imported.speakCurrentTime)
        assertEquals(original.speakElapsedTime, imported.speakElapsedTime)
        assertEquals(original.speakRemainingTime, imported.speakRemainingTime)
        assertEquals(original.speakOnCue, imported.speakOnCue)
        assertEquals(original.speakClockNameWithSpeech, imported.speakClockNameWithSpeech)
        assertTrue(imported.reusable)
        assertNull(imported.ownerClockId)
    }

    @Test
    fun scriptedSnapshotSurvivesLaterSourceAndImportedObjectChanges() {
        val original = BeepProfile.scriptedEvents("Focus", 500L, listOf(
            CueEvent.create(0L, "Start", 0L),
            CueEvent.create(30_000L, null, 750L),
        ), true)
        val snapshot = SharedProfile.fromProfile(original)
        original.events[0].speechText = "Changed after preview"
        original.events[1].offsetMillis = 99_000L
        original.events.clear()

        val imported = snapshot.toNewProfile()
        assertEquals("Start", imported.events[0].speechText)
        assertEquals(30_000L, imported.events[1].offsetMillis)
        assertEquals(listOf(0L, 30_000L), imported.scriptOffsetsMillis)
        imported.events[0].speechText = "Changed after import"
        assertEquals("Start", snapshot.events[0].speechText)
    }

    @Test
    fun repeatedImportsGenerateIndependentProfileAndEventIds() {
        val original = BeepProfile.scriptedEvents("Start", 500L,
            listOf(CueEvent.create(0L, "Go", 500L)), false)
        val originalEventId = original.events[0].id
        val snapshot = SharedProfile.fromProfile(original)
        val first = snapshot.toNewProfile()
        val second = snapshot.toNewProfile()

        assertNotEquals(first.id, second.id)
        assertNotEquals(original.id, first.id)
        assertNotEquals(originalEventId, first.events[0].id)
        assertNotEquals(first.events[0].id, second.events[0].id)
        assertEquals(originalEventId, original.events[0].id)
    }

    @Test
    fun ownedProfilesAreRejectedWithoutChangingTheirOwnership() {
        val original = BeepProfile.periodic("Private", 500L, 1_000L, false)
        original.reusable = false
        original.ownerClockId = "running-clock"
        assertThrows(IllegalArgumentException::class.java) { SharedProfile.fromProfile(original) }
        assertFalse(original.reusable)
        assertEquals("running-clock", original.ownerClockId)

        // Even inconsistent reusable=true/owner-present data must not leak out.
        original.reusable = true
        assertThrows(IllegalArgumentException::class.java) { SharedProfile.fromProfile(original) }
    }

    @Test
    fun inconsistentStoredOffsetsAreRejectedWithoutRepairingTheOriginal() {
        val original = BeepProfile.scripted("Broken", 500L, listOf(1_000L), false)
        original.scriptOffsetsMillis[0] = 2_000L
        assertThrows(IllegalArgumentException::class.java) { SharedProfile.fromProfile(original) }
        assertEquals(2_000L, original.scriptOffsetsMillis[0])
        assertEquals(1_000L, original.events[0].offsetMillis)
    }

    @Test
    fun eventListIsDetachedAndCannotBeMutatedThroughJavaCollections() {
        val supplied = mutableListOf(SharedCue(0L, "Go", 0L))
        val snapshot = shared(BeepProfile.Mode.SCRIPTED, 0L, supplied)
        supplied.clear()
        assertEquals(1, snapshot.events.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.events as MutableList<SharedCue>).clear()
        }
    }

    @Test
    fun periodicAndScriptedSchedulesEnforceTheirDifferentRules() {
        val first = SharedCue(0L, "Go", 0L)
        assertThrows(IllegalArgumentException::class.java) { shared(period = 0L) }
        assertThrows(IllegalArgumentException::class.java) { shared(events = listOf(first)) }
        assertThrows(IllegalArgumentException::class.java) {
            shared(BeepProfile.Mode.SCRIPTED, 1L, listOf(first))
        }
        assertThrows(IllegalArgumentException::class.java) {
            shared(BeepProfile.Mode.SCRIPTED, 0L, listOf(first, first))
        }
        assertThrows(IllegalArgumentException::class.java) {
            shared(BeepProfile.Mode.SCRIPTED, 0L, listOf(SharedCue(10L, "Later", 0L), first))
        }
        // A completion-only profile needs no artificial scheduled event.
        assertTrue(shared(BeepProfile.Mode.SCRIPTED, 0L).events.isEmpty())
    }

    @Test
    fun portableTimeAndBeepBoundsDoNotCoerceOrOverflow() {
        val max = ProfileSharingLimits.MAX_TIME_MILLIS
        assertEquals(max, shared(period = max).periodMillis)
        assertThrows(IllegalArgumentException::class.java) { shared(period = Long.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { SharedCue(-1L, "Go", 0L) }
        assertThrows(IllegalArgumentException::class.java) { SharedCue(max + 1L, "Go", 0L) }
        assertThrows(IllegalArgumentException::class.java) { shared(beep = 49L) }
        assertThrows(IllegalArgumentException::class.java) { shared(beep = 10_001L) }
        assertEquals(50L, shared(beep = 50L).beepDurationMillis)
        assertEquals(10_000L, shared(beep = 10_000L).beepDurationMillis)
        assertEquals(1L, SharedCue(0L, null, 1L).beepDurationMillis)
        assertThrows(IllegalArgumentException::class.java) { SharedCue(0L, null, 10_001L) }
    }

    @Test
    fun textBoundsCountUtf8BytesAndRejectMalformedUnicode() {
        // Each accented character uses two UTF-8 bytes but one UTF-16 code unit.
        assertEquals(256, shared(name = "é".repeat(128)).name.toByteArray().size)
        assertThrows(IllegalArgumentException::class.java) { shared(name = "é".repeat(129)) }
        assertThrows(IllegalArgumentException::class.java) { shared(name = " \t\n ") }
        assertEquals("Go", SharedCue(0L, "  Go \n", 0L).speechText)
        assertEquals(2_000, SharedCue(0L, "é".repeat(1_000), 0L).speechText!!.toByteArray().size)
        assertThrows(IllegalArgumentException::class.java) {
            SharedCue(0L, "é".repeat(1_001), 0L)
        }
        assertThrows(IllegalArgumentException::class.java) { shared(name = "\uD800") }
    }

    @Test
    fun eventMustHaveAnActionAndEventCountIsBounded() {
        assertThrows(IllegalArgumentException::class.java) { SharedCue(0L, null, 0L) }
        assertThrows(IllegalArgumentException::class.java) { SharedCue(0L, "  ", 500L) }
        val maximum = List(ProfileSharingLimits.MAX_EVENT_COUNT) {
            SharedCue(it.toLong(), "Go", 0L)
        }
        assertEquals(256, shared(BeepProfile.Mode.SCRIPTED, 0L, maximum).events.size)
        assertThrows(IllegalArgumentException::class.java) {
            shared(BeepProfile.Mode.SCRIPTED, 0L, maximum + SharedCue(256L, "End", 0L))
        }
    }

    private fun shared(
        mode: BeepProfile.Mode = BeepProfile.Mode.PERIODIC,
        period: Long = 60_000L,
        events: List<SharedCue> = emptyList(),
        beep: Long = 500L,
        name: String = "Profile",
    ) = SharedProfile(name, mode, beep, period, events,
        true, false, false, false, false, false, true, false)
}
