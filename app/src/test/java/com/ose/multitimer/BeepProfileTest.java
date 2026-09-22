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
        assertEquals(3, first.size());
        assertEquals(BeepProfile.DEFAULT_EVERY_MINUTE_ID, first.get(0).id);
        assertEquals(BeepProfile.DEFAULT_FOCUS_CUES_ID, first.get(1).id);
        assertEquals(BeepProfile.DEFAULT_COMPLETION_ONLY_ID, first.get(2).id);
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

        BeepProfile scripted = BeepProfile.scripted(
                "Long", BeepProfile.MAX_BEEP_DURATION_MILLIS,
                Arrays.asList(1_000L, 2_000L), true);
        assertEquals(BeepProfile.Mode.SCRIPTED, scripted.mode);
        assertEquals(0L, scripted.periodMillis);
        assertEquals(Arrays.asList(1_000L, 2_000L), scripted.scriptOffsetsMillis);
        assertTrue(scripted.beepOnCompletion);
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
