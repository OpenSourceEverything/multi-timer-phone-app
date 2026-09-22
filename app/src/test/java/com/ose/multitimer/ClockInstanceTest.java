package com.ose.multitimer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ClockInstanceTest {
    @Test
    public void stopwatchAccumulatesOnlyWhileRunning() {
        ClockInstance clock = ClockInstance.create(
                ClockInstance.Type.STOPWATCH, "Laps", 99L, null, 1_000L);

        assertEquals(0L, clock.configuredMillis);
        assertEquals(0L, clock.valueAt(5_000L));
        clock.start(2_000L);
        assertEquals(500L, clock.valueAt(2_500L));
        assertEquals(500L, clock.cueElapsedAt(2_500L));

        clock.pause(3_000L);
        assertEquals(1_000L, clock.valueAt(9_000L));
        assertEquals(1_000L, clock.cueElapsedAt(9_000L));

        clock.start(10_000L);
        assertEquals(1_750L, clock.valueAt(10_750L));
        assertEquals(1_750L, clock.cueElapsedAt(10_750L));
        assertFalse(clock.settleCompletion(Long.MAX_VALUE));
    }

    @Test
    public void timerPausesResumesAndClampsAtZero() {
        ClockInstance clock = ClockInstance.create(
                ClockInstance.Type.TIMER, "Tea", 10_000L, "profile", 0L);

        clock.start(1_000L);
        assertEquals(7_000L, clock.valueAt(4_000L));
        assertEquals(3_000L, clock.cueElapsedAt(4_000L));
        clock.pause(4_000L);
        assertEquals(7_000L, clock.valueAt(8_000L));

        clock.start(8_000L);
        assertEquals(2_000L, clock.valueAt(13_000L));
        assertEquals(8_000L, clock.cueElapsedAt(13_000L));
        assertEquals(0L, clock.valueAt(20_000L));
        assertEquals(10_000L, clock.cueElapsedAt(20_000L));
    }

    @Test
    public void settleCompletionTransitionsExactlyOnceAndFreezesFinalCueTime() {
        ClockInstance clock = ClockInstance.create(
                ClockInstance.Type.TIMER, "Short", 10_000L, null, 0L);
        clock.start(0L);

        assertFalse(clock.settleCompletion(9_999L));
        assertTrue(clock.running);
        assertTrue(clock.settleCompletion(10_000L));
        assertFalse(clock.running);
        assertTrue(clock.completed);
        assertEquals(0L, clock.valueAtAnchorMillis);
        assertEquals(10_000L, clock.cueElapsedAtAnchorMillis);
        assertFalse(clock.settleCompletion(20_000L));
        assertEquals(10_000L, clock.cueElapsedAt(20_000L));
    }

    @Test
    public void pauseAtDeadlinePreservesPendingCompletionBeepState() {
        ClockInstance clock = ClockInstance.create(
                ClockInstance.Type.TIMER, "Short", 1_000L, "profile", 0L);
        clock.start(0L);

        clock.pause(1_000L);

        assertFalse(clock.running);
        assertTrue(clock.completed);
        assertFalse(clock.completionBeepPlayed);
        assertEquals(0L, clock.valueAtAnchorMillis);
        assertEquals(1_000L, clock.cueElapsedAtAnchorMillis);
    }

    @Test
    public void countdownDelayedStartDoesNotMoveAbsoluteTarget() {
        ClockInstance clock = ClockInstance.create(
                ClockInstance.Type.COUNTDOWN, "Launch", 10_000L, null, 0L);

        clock.start(5_000L);
        assertEquals(1_000L, clock.valueAt(9_000L));
        assertEquals(4_000L, clock.cueElapsedAt(9_000L));
        assertTrue(clock.settleCompletion(10_000L));
        assertEquals(5_000L, clock.cueElapsedAtAnchorMillis);
    }

    @Test
    public void countdownResumeCatchesUpButExcludesPausedTimeFromCues() {
        ClockInstance clock = ClockInstance.create(
                ClockInstance.Type.COUNTDOWN, "Launch", 10_000L, null, 0L);
        clock.start(0L);
        clock.pause(2_000L);
        assertEquals(8_000L, clock.valueAt(8_000L));
        assertEquals(2_000L, clock.cueElapsedAt(8_000L));

        clock.start(9_000L);
        assertEquals(1_000L, clock.valueAt(9_000L));
        assertEquals(2_000L, clock.cueElapsedAt(9_000L));
        assertTrue(clock.settleCompletion(10_000L));
        assertEquals(3_000L, clock.cueElapsedAtAnchorMillis);
    }

    @Test
    public void resetRestoresConfigurationAndCueBookkeeping() {
        ClockInstance timer = ClockInstance.create(
                ClockInstance.Type.TIMER, "Timer", 8_000L, null, 0L);
        timer.start(1_000L);
        timer.lastCueElapsedMillis = 2_000L;
        timer.pause(4_000L);
        timer.completionBeepPlayed = false;
        timer.reset(9_000L);

        assertEquals(8_000L, timer.valueAt(20_000L));
        assertEquals(0L, timer.cueElapsedAt(20_000L));
        assertEquals(0L, timer.lastCueElapsedMillis);
        assertFalse(timer.running);
        assertFalse(timer.completed);

        ClockInstance countdown = ClockInstance.create(
                ClockInstance.Type.COUNTDOWN, "Date", 12_000L, null, 0L);
        countdown.reset(10_000L);
        assertEquals(2_000L, countdown.valueAt(10_000L));
        countdown.reset(12_001L);
        assertEquals(0L, countdown.valueAt(12_001L));
        assertTrue(countdown.completed);
    }

    @Test
    public void reconfigureResetsOnlyWhenTheTimeSettingChanges() {
        ClockInstance timer = ClockInstance.create(
                ClockInstance.Type.TIMER, "Timer", 8_000L, "profile", 0L);
        timer.start(1_000L);
        timer.lastCueElapsedMillis = 2_000L;

        assertFalse(timer.reconfigure(8_000L, 4_000L));
        assertTrue(timer.running);
        assertEquals(5_000L, timer.valueAt(4_000L));

        assertTrue(timer.reconfigure(20_000L, 4_000L));
        assertFalse(timer.running);
        assertFalse(timer.completed);
        assertEquals(20_000L, timer.valueAt(40_000L));
        assertEquals(0L, timer.cueElapsedAt(40_000L));
        assertEquals(0L, timer.lastCueElapsedMillis);
        assertFalse(timer.completionBeepPlayed);
    }

    @Test
    public void backwardsWallReadingDoesNotReverseRelativeClocks() {
        ClockInstance stopwatch = ClockInstance.create(
                ClockInstance.Type.STOPWATCH, "Watch", 0L, null, 5_000L);
        stopwatch.start(5_000L);
        assertEquals(0L, stopwatch.valueAt(4_000L));

        ClockInstance timer = ClockInstance.create(
                ClockInstance.Type.TIMER, "Timer", 5_000L, null, 5_000L);
        timer.start(5_000L);
        assertEquals(5_000L, timer.valueAt(4_000L));
    }

    @Test
    public void runningRelativeClockUsesMonotonicTimeWhenWallClockMoves() {
        ClockInstance timer = ClockInstance.create(
                ClockInstance.Type.TIMER, "Timer", 10_000L, null, 100_000L, 50_000L);
        timer.start(100_000L, 50_000L);

        assertEquals(7_500L, timer.valueAt(90_000L, 52_500L));
        assertEquals(2_500L, timer.cueElapsedAt(90_000L, 52_500L));
    }

    @Test
    public void jsonRoundTripPreservesAllPersistedFieldsAndEscapesText() {
        ClockInstance original = ClockInstance.create(
                ClockInstance.Type.TIMER, "Tea \"and\" toast\nnow", 8_000L, " p-1 ", 50L);
        original.start(100L);
        original.lastCueElapsedMillis = 250L;

        ClockInstance restored = ClockInstance.fromJson(original.toJson());

        assertNotNull(restored.id);
        assertEquals(original.id, restored.id);
        assertEquals(original.name, restored.name);
        assertEquals("p-1", restored.profileId);
        assertEquals(original.type, restored.type);
        assertEquals(original.configuredMillis, restored.configuredMillis);
        assertEquals(original.valueAtAnchorMillis, restored.valueAtAnchorMillis);
        assertEquals(original.cueElapsedAtAnchorMillis, restored.cueElapsedAtAnchorMillis);
        assertEquals(original.anchorWallTimeMillis, restored.anchorWallTimeMillis);
        assertEquals(original.lastCueElapsedMillis, restored.lastCueElapsedMillis);
        assertEquals(original.running, restored.running);
        assertEquals(original.completed, restored.completed);
        assertEquals(original.completionBeepPlayed, restored.completionBeepPlayed);

        ClockInstance noProfile = ClockInstance.create(
                ClockInstance.Type.STOPWATCH, "No profile", 0L, null, 0L);
        assertNull(ClockInstance.fromJson(noProfile.toJson()).profileId);
    }

    @Test(expected = IllegalArgumentException.class)
    public void timerRequiresPositiveDuration() {
        ClockInstance.create(ClockInstance.Type.TIMER, "Bad", 0L, null, 0L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void duplicateJsonKeysAreRejected() {
        ClockInstance.fromJson("{\"id\":\"a\",\"id\":\"b\"}");
    }
}
