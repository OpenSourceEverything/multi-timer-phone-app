package com.ose.multitimer;

/**
 * State vocabulary for one durable timer-cue diagnostic record.
 *
 * <p>The ledger describes what the timer engine observed and attempted. Audio-player
 * callbacks add later evidence about whether the configured output actually started
 * and completed.</p>
 */
final class DiagnosticEvent {
    enum State {
        SCHEDULED,
        DISPATCHED,
        EXECUTED,
        AUDIO_STARTED,
        COMPLETED,
        FAILED,
        /** An expected cue was withdrawn because its clock run or profile is no longer active. */
        CANCELLED
    }

    private DiagnosticEvent() {
        // Utility class.
    }

    /** Returns whether a transition preserves the forward-only event lifecycle. */
    static boolean canTransition(State current, State next) {
        if (current == null || next == null) {
            return false;
        }
        if (current == next) {
            return true;
        }
        if (current == State.COMPLETED || current == State.FAILED
                || current == State.CANCELLED) {
            return false;
        }
        // A cue can only be withdrawn before the engine has dispatched it.
        if (next == State.CANCELLED) {
            return current == State.SCHEDULED;
        }
        if (next == State.FAILED) {
            return true;
        }
        return rank(next) > rank(current);
    }

    private static int rank(State state) {
        switch (state) {
            case SCHEDULED:
                return 0;
            case DISPATCHED:
                return 1;
            case EXECUTED:
                return 2;
            case AUDIO_STARTED:
                return 3;
            case COMPLETED:
                return 4;
            case FAILED:
            case CANCELLED:
                return 5;
            default:
                throw new AssertionError("Unhandled diagnostic state: " + state);
        }
    }
}
