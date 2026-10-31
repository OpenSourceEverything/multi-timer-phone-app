package com.ose.multitimer;

import java.util.Objects;

/**
 * Selects the one clock action that is valid for a clock's current state.
 *
 * <p>The Activity uses this decision both to render the clock-card button and
 * to handle its click. Keeping the rule in one small, Android-free class stops
 * the displayed control from drifting away from the action it performs. This
 * policy does not mutate the clock or save it; those side effects stay in the
 * Activity and {@link ClockInstance}.</p>
 */
final class ClockActionPolicy {
    /** The action available to a person for the clock's current state. */
    enum Action {
        /** Start or resume a paused clock. */
        START,
        /** Pause a running clock. */
        PAUSE,
        /** A completed finite clock cannot be started until it is reset. */
        UNAVAILABLE
    }

    /** Utility class: decisions are made from the supplied clock, not an instance. */
    private ClockActionPolicy() {
    }

    /**
     * Returns the action a clock card should present and perform.
     *
     * <p>Running takes precedence so a running clock always offers Pause. A
     * completed timer or countdown offers no action, while a completed
     * stopwatch may start another accumulating segment.</p>
     *
     * @param clock the current clock model; must not be {@code null}
     * @return the valid action, or {@link Action#UNAVAILABLE} for a completed
     *         finite clock
     */
    static Action actionFor(ClockInstance clock) {
        Objects.requireNonNull(clock, "clock");
        if (clock.running) {
            return Action.PAUSE;
        }
        if (clock.completed && clock.type != ClockInstance.Type.STOPWATCH) {
            return Action.UNAVAILABLE;
        }
        return Action.START;
    }
}
