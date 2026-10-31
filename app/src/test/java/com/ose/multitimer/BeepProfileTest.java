package com.ose.multitimer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public final class BeepProfileTest {
    @Test
    public void profileNameDoesNotCountAsAnAudioAction() {
        BeepProfile profile = BeepProfile.periodic(
                "Speak elapsed 3 min", 500L, 180_000L, false);
        profile.beepOnCue = false;
        profile.speakOnCue = true;
        profile.speakElapsedTime = false;

        assertFalse(profile.hasConfiguredAudioAction());

        profile.speakElapsedTime = true;
        assertTrue(profile.hasConfiguredAudioAction());
    }

    @Test
    public void delayedPeriodicWindowsReturnEveryCrossedCueWithoutReplayingBoundary() {
        BeepProfile profile = BeepProfile.periodic("Every second", 100L, 1_000L, false);

        // A delayed tick spans three cues. Returning only the first would lose
        // scheduled actions even though the clock's elapsed arithmetic is correct.
        List<CueEvent> delayed = profile.eventsBetween(0L, 3_500L);
        assertEquals(3, delayed.size());
        assertEquals(1_000L, delayed.get(0).offsetMillis);
        assertEquals(2_000L, delayed.get(1).offsetMillis);
        assertEquals(3_000L, delayed.get(2).offsetMillis);

        List<CueEvent> next = profile.eventsBetween(3_500L, 4_000L);
        assertEquals(1, next.size());
        assertEquals(4_000L, next.get(0).offsetMillis);
        // The scheduler can examine the same instant again; an exclusive lower
        // boundary prevents replay. No assertion here claims that audio was heard.
        assertTrue(profile.eventsBetween(4_000L, 4_000L).isEmpty());
    }

    @Test
    public void advancingOneAttachedProfileDoesNotConsumeAnotherProfilesCues() {
        BeepProfile fast = BeepProfile.periodic("Thirty seconds", 100L, 30_000L, false);
        BeepProfile slow = BeepProfile.periodic("Minute", 100L, 60_000L, false);
        ClockInstance clock = ClockInstance.create(
                ClockInstance.Type.STOPWATCH, "Shared clock", 0L, null, 0L);
        clock.setAttachedProfileIds(Arrays.asList(fast.id, slow.id));
        clock.start(0L);

        // The fast profile acknowledges its first cue. The slower profile has
        // not examined that interval, so its checkpoint must remain independent.
        clock.setCueElapsedForProfile(fast.id, 30_000L);
        List<CueEvent> fastDue = fast.eventsBetween(
                clock.cueElapsedForProfile(fast.id), clock.cueElapsedAt(60_000L));
        assertEquals(1, fastDue.size());
        clock.setCueElapsedForProfile(fast.id, fastDue.get(0).offsetMillis);
        List<CueEvent> slowDue = slow.eventsBetween(
                clock.cueElapsedForProfile(slow.id), clock.cueElapsedAt(60_000L));

        assertEquals(60_000L, fastDue.get(0).offsetMillis);
        assertEquals(0L, clock.cueElapsedForProfile(slow.id));
        assertEquals(1, slowDue.size());
        assertEquals(60_000L, slowDue.get(0).offsetMillis);
    }

    @Test
    public void scriptedCueUsesExclusiveInclusiveBoundaries() {
        BeepProfile profile = BeepProfile.scripted(
                "Steps", 250L, Arrays.asList(1_000L, 3_000L, 10_000L), true);

        assertFalse(profile.hasCueBetween(0L, 999L));
        assertTrue(profile.hasCueBetween(0L, 1_000L));
        assertFalse(profile.hasCueBetween(1_000L, 2_999L));
        assertTrue(profile.hasCueBetween(1_000L, 3_000L));
        assertFalse(profile.hasCueBetween(3_000L, 3_000L));
        assertTrue(profile.hasCueBetween(2_000L, 10_000L));
        assertFalse(profile.hasCueBetween(10_000L, 20_000L));
    }

    @Test
    public void firstCueBetweenReturnsOneDueOffsetAtATime() {
        BeepProfile periodic = BeepProfile.periodic("Fast", 500L, 1_000L, false);
        assertEquals(1_000L, periodic.firstCueBetween(0L, 3_500L));
        assertEquals(2_000L, periodic.firstCueBetween(1_000L, 3_500L));
        assertEquals(BeepProfile.NO_CUE, periodic.firstCueBetween(3_000L, 3_000L));

        BeepProfile scripted = BeepProfile.scripted(
                "Script", 500L, Arrays.asList(1_000L, 2_500L), false);
        assertEquals(2_500L, scripted.firstCueBetween(1_000L, 3_000L));
    }

    @Test
    public void eventsBetweenReturnsAllArbitraryCuesInOrder() {
        BeepProfile scripted = BeepProfile.scripted(
                "Script", 500L, Arrays.asList(1_000L, 2_500L, 4_000L), false);

        List<CueEvent> events = scripted.eventsBetween(0L, 4_000L);

        assertEquals(3, events.size());
        assertEquals(1_000L, events.get(0).offsetMillis);
        assertEquals(2_500L, events.get(1).offsetMillis);
        assertEquals(4_000L, events.get(2).offsetMillis);
    }

    @Test
    public void completionOnlyScriptMayHaveNoOffsets() {
        BeepProfile profile = BeepProfile.scripted(
                "Done", 750L, Collections.emptyList(), true);
        profile.validate();
        assertFalse(profile.hasCueBetween(0L, Long.MAX_VALUE));
    }

    @Test
    public void jsonRoundTripPreservesPeriodicAndScriptedProfiles() {
        BeepProfile periodic = BeepProfile.periodic(
                "Every \"minute\"", 321L, 60_000L, true);
        BeepProfile periodicCopy = BeepProfile.fromJson(periodic.toJson());
        assertEquals(periodic.id, periodicCopy.id);
        assertEquals(periodic.name, periodicCopy.name);
        assertEquals(periodic.mode, periodicCopy.mode);
        assertEquals(periodic.beepDurationMillis, periodicCopy.beepDurationMillis);
        assertEquals(periodic.periodMillis, periodicCopy.periodMillis);
        assertEquals(periodic.scriptOffsetsMillis, periodicCopy.scriptOffsetsMillis);
        assertEquals(periodic.beepOnCompletion, periodicCopy.beepOnCompletion);

        BeepProfile scripted = BeepProfile.scripted(
                "Steps", 654L, Arrays.asList(1_000L, 9_000L), false);
        BeepProfile scriptedCopy = BeepProfile.fromJson(scripted.toJson());
        assertEquals(scripted.scriptOffsetsMillis, scriptedCopy.scriptOffsetsMillis);
        assertEquals(BeepProfile.Mode.SCRIPTED, scriptedCopy.mode);
    }

    @Test
    public void defaultProfilesHaveStableUniqueIdsAndAreFreshInstances() {
        List<BeepProfile> first = BeepProfile.defaultProfiles();
        List<BeepProfile> second = BeepProfile.defaultProfiles();
        assertEquals(6, first.size());
        assertEquals(BeepProfile.DEFAULT_EVERY_MINUTE_ID, first.get(0).id);
        assertEquals(BeepProfile.DEFAULT_FOCUS_CUES_ID, first.get(1).id);
        assertEquals(BeepProfile.DEFAULT_COMPLETION_ONLY_ID, first.get(2).id);
        assertEquals(BeepProfile.DEFAULT_CURRENT_TIME_ID, first.get(3).id);
        assertEquals(BeepProfile.DEFAULT_ELAPSED_TIME_ID, first.get(4).id);
        assertEquals(BeepProfile.DEFAULT_REMAINING_TIME_ID, first.get(5).id);
        assertFalse(first.get(3).beepOnCue);
        assertTrue(first.get(3).speakCurrentTime);
        assertFalse(first.get(4).beepOnCue);
        assertTrue(first.get(4).speakElapsedTime);
        assertFalse(first.get(5).beepOnCue);
        assertTrue(first.get(5).speakRemainingTime);
        assertFalse(first.get(0) == second.get(0));
    }

    @Test
    public void factoriesMapFieldsAndAcceptInclusiveBeepDurationBounds() {
        BeepProfile periodic = BeepProfile.periodic(
                "Fast", BeepProfile.MIN_BEEP_DURATION_MILLIS, 1_000L, false);
        assertEquals(BeepProfile.Mode.PERIODIC, periodic.mode);
        assertEquals(1_000L, periodic.periodMillis);
        assertEquals(Collections.emptyList(), periodic.scriptOffsetsMillis);
        assertFalse(periodic.beepOnCompletion);
        assertTrue(periodic.speakOnCue);

        BeepProfile scripted = BeepProfile.scripted(
                "Long", BeepProfile.MAX_BEEP_DURATION_MILLIS,
                Arrays.asList(1_000L, 2_000L), true);
        assertEquals(BeepProfile.Mode.SCRIPTED, scripted.mode);
        assertEquals(0L, scripted.periodMillis);
        assertEquals(Arrays.asList(1_000L, 2_000L), scripted.scriptOffsetsMillis);
        assertTrue(scripted.beepOnCompletion);
    }

    @Test
    public void speechOptionsSurviveJsonRoundTrip() {
        BeepProfile profile = BeepProfile.periodic("Spoken", 500L, 30_000L, false);
        profile.beepOnCue = false;
        profile.speakOnCue = true;
        profile.speakOnCompletion = true;
        profile.speakCurrentTime = true;
        profile.speakElapsedTime = true;
        profile.speakRemainingTime = true;
        profile.speakClockNameWithSpeech = true;
        profile.reusable = false;
        profile.ownerClockId = "clock-1";

        BeepProfile copy = BeepProfile.fromJson(profile.toJson());

        assertFalse(copy.beepOnCue);
        assertTrue(copy.speakOnCue);
        assertTrue(copy.speakOnCompletion);
        assertTrue(copy.speakCurrentTime);
        assertTrue(copy.speakElapsedTime);
        assertTrue(copy.speakRemainingTime);
        assertTrue(copy.speakClockNameWithSpeech);
        assertFalse(copy.reusable);
        assertEquals("clock-1", copy.ownerClockId);
    }

    @Test
    public void copyAsCreatesIndependentProfileWithNewNameAndId() {
        BeepProfile original = BeepProfile.scripted(
                "Shared", 500L, Arrays.asList(30_000L, 60_000L), false);

        BeepProfile copy = original.copyAs("Shared for Timer 2");

        assertEquals("Shared for Timer 2", copy.name);
        assertTrue(!original.id.equals(copy.id));
        assertTrue(copy.reusable);
        assertEquals(original.scriptOffsetsMillis, copy.scriptOffsetsMillis);
        copy.scriptOffsetsMillis.set(0, 45_000L);
        assertEquals(30_000L, original.scriptOffsetsMillis.get(0).longValue());
    }

    @Test(expected = IllegalArgumentException.class)
    public void scriptedOffsetsMustBeStrictlyIncreasing() {
        BeepProfile.scripted("Bad", 100L, Arrays.asList(2_000L, 1_000L), false);
    }

    @Test(expected = IllegalArgumentException.class)
    public void periodicPeriodMustBePositive() {
        BeepProfile.periodic("Bad", 100L, 0L, false);
    }

    @Test(expected = IllegalArgumentException.class)
    public void beepDurationBelowMinimumIsRejected() {
        BeepProfile.scripted(
                "Bad", BeepProfile.MIN_BEEP_DURATION_MILLIS - 1L,
                Collections.singletonList(1_000L), false);
    }

    @Test(expected = IllegalArgumentException.class)
    public void beepDurationAboveMaximumIsRejected() {
        BeepProfile.scripted(
                "Bad", BeepProfile.MAX_BEEP_DURATION_MILLIS + 1L,
                Collections.singletonList(1_000L), false);
    }
}
