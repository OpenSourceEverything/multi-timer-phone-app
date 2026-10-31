package com.ose.multitimer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Map;

/** Checks that the exported diagnosis follows evidence rather than a guessed OS cause. */
public final class DiagnosticReportTest {
    /** A withdrawn expectation is normal lifecycle evidence, not a missing callback. */
    @Test
    public void cancelledScheduledCueIsNotReportedAsFailure() {
        DiagnosticReport.EventRow cancelled = event(DiagnosticEvent.State.CANCELLED);
        cancelled.transitions.add(step(DiagnosticEvent.State.CANCELLED, 900L,
                "Clock paused, reset, deleted or profile detached"));
        DiagnosticReport.Diagnosis diagnosis = DiagnosticReport.diagnose(cancelled, 50_000L);
        assertEquals("CANCELLED", diagnosis.category);
        assertEquals("high", diagnosis.confidence);
        DiagnosticReport.Output report = DiagnosticReport.render(
                Arrays.asList(cancelled), 1, Arrays.asList(), 0, 50_000L,
                Map.of(), Map.of());
        assertTrue(report.text.contains("No classified problems"));
    }

    /** A silent configuration must not be confused with a failed TTS engine. */
    @Test
    public void distinguishesNoAudioActionFromPlaybackFailure() {
        DiagnosticReport.EventRow silent = event(DiagnosticEvent.State.FAILED);
        silent.transitions.add(step(DiagnosticEvent.State.EXECUTED, 1_000L,
                "Cue actions queued by TimingService"));
        silent.transitions.add(step(DiagnosticEvent.State.FAILED, 1_000L,
                "No enabled beep or speech action for cue"));
        assertEquals("NO_AUDIO_ACTION", DiagnosticReport.diagnose(silent, 50_000L).category);

        DiagnosticReport.EventRow failed = event(DiagnosticEvent.State.FAILED);
        failed.transitions.add(step(DiagnosticEvent.State.EXECUTED, 1_000L, "Queued"));
        failed.transitions.add(step(DiagnosticEvent.State.FAILED, 1_100L,
                "TTS engine rejected utterance"));
        assertEquals("AUDIO_FAILED", DiagnosticReport.diagnose(failed, 50_000L).category);
    }

    /** Late dispatch is measured at dispatch, not at audio completion. */
    @Test
    public void classifiesLateDispatchWithoutBlamingAndroid() {
        DiagnosticReport.EventRow late = event(DiagnosticEvent.State.COMPLETED);
        late.transitions.add(step(DiagnosticEvent.State.SCHEDULED, 900L, "Expected"));
        late.transitions.add(new DiagnosticReport.TransitionRow(
                DiagnosticEvent.State.DISPATCHED, 4_000L, 13_000L, "Observed"));
        late.transitions.add(step(DiagnosticEvent.State.COMPLETED, 4_500L, "Finished"));
        DiagnosticReport.Diagnosis diagnosis = DiagnosticReport.diagnose(late, 50_000L);
        assertEquals("CALLBACK_LATE", diagnosis.category);
        assertEquals("high", diagnosis.confidence);
        assertTrue(diagnosis.explanation.contains("3000 ms"));
    }

    /** A scheduled row without a callback is uncertain while a clock could have changed. */
    @Test
    public void overdueScheduledRowHasLowConfidence() {
        DiagnosticReport.Diagnosis diagnosis = DiagnosticReport.diagnose(
                event(DiagnosticEvent.State.SCHEDULED), 50_000L);
        assertEquals("CALLBACK_NOT_RECEIVED", diagnosis.category);
        assertEquals("low", diagnosis.confidence);
    }

    /** Prior process exit may narrow a missing-callback report, but confidence stays low. */
    @Test
    public void correlatesProcessExitWithoutClaimingCausation() {
        DiagnosticReport.EventRow missing = event(DiagnosticEvent.State.FAILED);
        missing.transitions.add(step(DiagnosticEvent.State.SCHEDULED, 500L, "Expected"));
        missing.transitions.add(step(DiagnosticEvent.State.FAILED, 2_000L,
                "Startup found an overdue cue still marked scheduled"));
        DiagnosticReport.SnapshotRow exit = new DiagnosticReport.SnapshotRow(
                "exit", "process_exit_history", 2_000L, 20_000L,
                "{\"available\":true,\"entries\":[{\"timestampMillis\":750}]}");
        DiagnosticReport.Output output = DiagnosticReport.render(
                Arrays.asList(missing), 1, Arrays.asList(exit), 1, 50_000L,
                Map.of(), Map.of());
        assertTrue(output.text.contains("PROCESS_TERMINATED [low]"));
    }

    /** A missing lock without an app release is shown as unknown, not blamed on Android. */
    @Test
    public void reportsUnknownWakeLockCauseWhenNoReleaseWasObserved() {
        DiagnosticReport.SnapshotRow missingLock = new DiagnosticReport.SnapshotRow(
                "lock-gap", "wake_lock_missing_unknown", 2_000L, 20_000L,
                "{\"causeClassification\":\"UNKNOWN\","
                        + "\"outcome\":\"no matching release\"}");
        DiagnosticReport.Output output = DiagnosticReport.render(
                Arrays.asList(), 0, Arrays.asList(missingLock), 1, 50_000L,
                Map.of(), Map.of());
        assertTrue(output.text.contains(
                "Wake-lock loss observed; cause UNKNOWN: no matching app release was recorded."));
    }

    /** A recovered lock does not reveal whether it was enforced during the sleep gap. */
    @Test
    public void reportsUnknownSchedulerGapCauseEvenWhenLockWasHeldAtRecovery() {
        DiagnosticReport.SnapshotRow schedulerGap = new DiagnosticReport.SnapshotRow(
                "gap", "scheduler_gap", 2_000L, 20_000L,
                "{\"causeClassification\":\"UNKNOWN\","
                        + "\"wakeLockHeldAtObservation\":true}");
        DiagnosticReport.Output output = DiagnosticReport.render(
                Arrays.asList(), 0, Arrays.asList(schedulerGap), 1, 50_000L,
                Map.of(), Map.of());
        assertTrue(output.text.contains("Scheduler gap observed; cause UNKNOWN: app-side state "
                + "cannot show whether the device honored its wake-lock request."));
        Map<String, Object> report = JsonSupport.parseObject(output.json);
        java.util.List<?> snapshots = (java.util.List<?>) report.get("snapshots");
        Map<?, ?> exportedSnapshot = (Map<?, ?>) snapshots.get(0);
        Map<?, ?> exportedDetails = (Map<?, ?>) exportedSnapshot.get("details");
        assertEquals("UNKNOWN", exportedDetails.get("causeClassification"));
    }

    /** Both archive members include bounded selection counts and valid structured evidence. */
    @Test
    public void rendersReadableAndStructuredEvidence() {
        DiagnosticReport.EventRow silent = event(DiagnosticEvent.State.FAILED);
        silent.transitions.add(step(DiagnosticEvent.State.FAILED, 1_000L,
                "No enabled beep or speech action for cue"));
        DiagnosticReport.SnapshotRow snapshot = new DiagnosticReport.SnapshotRow(
                "snap-1", "audio_failure", 1_200L, 9_000L, "{\"powerSaveMode\":false}");
        DiagnosticReport.Output output = DiagnosticReport.render(
                Arrays.asList(silent), 3, Arrays.asList(snapshot), 7, 50_000L,
                Map.of("clock", "Walk"), Map.of("profile", "Speak elapsed"));
        assertTrue(output.text.contains("Events included: 1 of 3; snapshots: 1 of 7"));
        assertTrue(output.text.contains("Walk / Speak elapsed"));
        Map<String, Object> json = JsonSupport.parseObject(output.json);
        assertEquals(3L, json.get("totalEvents"));
        assertEquals(7L, json.get("totalSnapshots"));
        assertEquals(1, ((java.util.List<?>) json.get("events")).size());
        assertEquals(1, ((java.util.List<?>) json.get("snapshots")).size());
    }

    /** Builds one representative event with an expected wall time of one second. */
    private static DiagnosticReport.EventRow event(DiagnosticEvent.State state) {
        return new DiagnosticReport.EventRow(
                "event", "clock", "profile", 1_000L, 10_000L, state);
    }

    /** Builds one representative persisted lifecycle transition. */
    private static DiagnosticReport.TransitionRow step(
            DiagnosticEvent.State state, long wallMillis, String detail) {
        return new DiagnosticReport.TransitionRow(state, wallMillis, 10_000L, detail);
    }
}
