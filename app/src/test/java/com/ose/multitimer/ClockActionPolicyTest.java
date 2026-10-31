package com.ose.multitimer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

/** Verifies clock-card decisions without constructing an Android Activity. */
public final class ClockActionPolicyTest {
    /** A paused timer can start, then offers pause after it starts. */
    @Test
    public void pausedAndRunningTimerOfferStartThenPause() {
        ClockInstance timer = clock(ClockInstance.Type.TIMER);

        assertEquals(ClockActionPolicy.Action.START, ClockActionPolicy.actionFor(timer));

        timer.running = true;
        assertEquals(ClockActionPolicy.Action.PAUSE, ClockActionPolicy.actionFor(timer));
    }

    /** A completed timer waits for reset instead of advertising a no-op Start. */
    @Test
    public void completedTimerHasNoStartOrPauseAction() {
        ClockInstance timer = clock(ClockInstance.Type.TIMER);
        timer.completed = true;

        assertEquals(ClockActionPolicy.Action.UNAVAILABLE, ClockActionPolicy.actionFor(timer));
    }

    /** A completed stopwatch remains startable so it can keep accumulating. */
    @Test
    public void completedStopwatchCanStartAnotherSegment() {
        ClockInstance stopwatch = clock(ClockInstance.Type.STOPWATCH);
        stopwatch.completed = true;

        assertEquals(ClockActionPolicy.Action.START, ClockActionPolicy.actionFor(stopwatch));
    }

    /** A running clock offers Pause even if stale state also says completed. */
    @Test
    public void runningStateTakesPrecedenceOverCompletionFlag() {
        ClockInstance countdown = clock(ClockInstance.Type.COUNTDOWN);
        countdown.running = true;
        countdown.completed = true;

        assertEquals(ClockActionPolicy.Action.PAUSE, ClockActionPolicy.actionFor(countdown));
    }

    /** The policy rejects a missing model rather than hiding a caller bug. */
    @Test
    public void nullClockIsRejected() {
        assertThrows(NullPointerException.class, () -> ClockActionPolicy.actionFor(null));
    }

    /** Creates a valid finite or unbounded clock for decision-only assertions. */
    private static ClockInstance clock(ClockInstance.Type type) {
        long configuredMillis = type == ClockInstance.Type.STOPWATCH ? 0L : 60_000L;
        return ClockInstance.create(type, "Test clock", configuredMillis, null, 1_000L);
    }
}
