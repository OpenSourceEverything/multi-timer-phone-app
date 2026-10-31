package com.ose.multitimer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/** Verifies that multiple asynchronous audio actions produce one honest event outcome. */
public final class DiagnosticAudioTrackerTest {
    @Test
    public void completesOnlyAfterEveryAudioActionCompletes() {
        List<Observation> observations = new ArrayList<>();
        DiagnosticAudioTracker tracker = newTracker(observations);

        tracker.register("event-1", 2);
        tracker.onStarted("event-1", "beep started");
        tracker.onCompleted("event-1", "beep completed");

        assertEquals(1, tracker.pendingCount());
        assertEquals(DiagnosticEvent.State.AUDIO_STARTED, observations.get(0).state);

        tracker.onCompleted("event-1", "speech completed");

        assertEquals(0, tracker.pendingCount());
        assertEquals(DiagnosticEvent.State.COMPLETED, observations.get(1).state);
    }

    @Test
    public void failureIsTerminalAndLaterCallbacksAreIgnored() {
        List<Observation> observations = new ArrayList<>();
        DiagnosticAudioTracker tracker = newTracker(observations);

        tracker.register("event-2", 1);
        tracker.onFailed("event-2", "TTS rejected");
        tracker.onCompleted("event-2", "late completion");

        assertEquals(0, tracker.pendingCount());
        assertEquals(1, observations.size());
        assertEquals(DiagnosticEvent.State.FAILED, observations.get(0).state);
        assertTrue(observations.get(0).detail.contains("rejected"));
    }

    private static DiagnosticAudioTracker newTracker(List<Observation> observations) {
        return new DiagnosticAudioTracker(
                (eventId, state, wallMillis, elapsedMillis, detail) ->
                        observations.add(new Observation(state, detail)),
                new DiagnosticAudioTracker.TimeSource() {
                    @Override
                    public long wallMillis() {
                        return 1_000L;
                    }

                    @Override
                    public long elapsedMillis() {
                        return 2_000L;
                    }
                });
    }

    private static final class Observation {
        private final DiagnosticEvent.State state;
        private final String detail;

        private Observation(DiagnosticEvent.State state, String detail) {
            this.state = state;
            this.detail = detail;
        }
    }
}
