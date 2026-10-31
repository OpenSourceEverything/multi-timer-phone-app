package com.ose.multitimer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Tests the forward-only lifecycle contract used by the durable diagnostic ledger. */
public final class DiagnosticEventTest {
    @Test
    public void acceptsExpectedForwardTransitions() {
        assertTrue(DiagnosticEvent.canTransition(
                DiagnosticEvent.State.SCHEDULED, DiagnosticEvent.State.DISPATCHED));
        assertTrue(DiagnosticEvent.canTransition(
                DiagnosticEvent.State.DISPATCHED, DiagnosticEvent.State.EXECUTED));
        assertTrue(DiagnosticEvent.canTransition(
                DiagnosticEvent.State.EXECUTED, DiagnosticEvent.State.AUDIO_STARTED));
        assertTrue(DiagnosticEvent.canTransition(
                DiagnosticEvent.State.AUDIO_STARTED, DiagnosticEvent.State.COMPLETED));
        assertTrue(DiagnosticEvent.canTransition(
                DiagnosticEvent.State.DISPATCHED, DiagnosticEvent.State.FAILED));
        assertTrue(DiagnosticEvent.canTransition(
                DiagnosticEvent.State.SCHEDULED, DiagnosticEvent.State.CANCELLED));
    }

    @Test
    public void rejectsBackwardAndTerminalTransitions() {
        assertFalse(DiagnosticEvent.canTransition(
                DiagnosticEvent.State.EXECUTED, DiagnosticEvent.State.SCHEDULED));
        assertFalse(DiagnosticEvent.canTransition(
                DiagnosticEvent.State.COMPLETED, DiagnosticEvent.State.FAILED));
        assertFalse(DiagnosticEvent.canTransition(
                DiagnosticEvent.State.FAILED, DiagnosticEvent.State.EXECUTED));
        assertFalse(DiagnosticEvent.canTransition(
                DiagnosticEvent.State.CANCELLED, DiagnosticEvent.State.DISPATCHED));
        assertFalse(DiagnosticEvent.canTransition(
                DiagnosticEvent.State.DISPATCHED, DiagnosticEvent.State.CANCELLED));
    }
}
