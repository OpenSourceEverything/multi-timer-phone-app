package com.ose.multitimer;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Persisted state for one independently controlled clock. */
public final class ClockInstance {
    public enum Type {
        STOPWATCH,
        TIMER,
        COUNTDOWN
    }

    public String id;
    public String name;
    public String profileId;
    public Type type;
    /** Timer duration, countdown target epoch, or zero for a stopwatch. */
    public long configuredMillis;
    /** Display value captured when {@link #anchorWallTimeMillis} was last updated. */
    public long valueAtAnchorMillis;
    /** Cue-schedule elapsed time captured at the same anchor. */
    public long cueElapsedAtAnchorMillis;
    public long anchorWallTimeMillis;
    /** Monotonic anchor for active stopwatch/timer runs; zero means legacy wall-time state. */
    public long anchorElapsedRealtimeMillis;
    /** Last elapsed cue position examined by the scheduler. */
    public long lastCueElapsedMillis;
    /** True until the first run has had a chance to dispatch an event at offset zero. */
    public boolean initialCuePending;
    public boolean running;
    public boolean completed;
    /** True once the completion cue decision has been durably handled by the service. */
    public boolean completionBeepPlayed;

    private ClockInstance() {
    }

    public static ClockInstance create(
            Type type, String name, long configuredMillis, String profileId, long nowMillis) {
        return create(type, name, configuredMillis, profileId, nowMillis, 0L);
    }

    /** Creates a clock with both wall and monotonic timestamps available. */
    public static ClockInstance create(
            Type type,
            String name,
            long configuredMillis,
            String profileId,
            long nowWallTimeMillis,
            long nowElapsedRealtimeMillis) {
        Objects.requireNonNull(type, "type");
        String normalizedName = requireNonBlank(name, "name");
        if (type == Type.TIMER && configuredMillis <= 0L) {
            throw new IllegalArgumentException("A timer duration must be greater than zero");
        }
        if (type == Type.COUNTDOWN && configuredMillis <= 0L) {
            throw new IllegalArgumentException("A countdown target epoch must be greater than zero");
        }

        ClockInstance clock = new ClockInstance();
        clock.id = UUID.randomUUID().toString();
        clock.name = normalizedName;
        clock.profileId = normalizeNullable(profileId);
        clock.type = type;
        clock.configuredMillis = type == Type.STOPWATCH ? 0L : configuredMillis;
        clock.valueAtAnchorMillis = clock.initialValue(nowWallTimeMillis);
        clock.cueElapsedAtAnchorMillis = 0L;
        clock.anchorWallTimeMillis = nowWallTimeMillis;
        clock.anchorElapsedRealtimeMillis = nonNegative(nowElapsedRealtimeMillis);
        clock.lastCueElapsedMillis = 0L;
        clock.initialCuePending = true;
        clock.running = false;
        clock.completed = type != Type.STOPWATCH && clock.valueAtAnchorMillis == 0L;
        clock.completionBeepPlayed = false;
        return clock;
    }

    /** Returns the display value at {@code nowMillis} without mutating persisted state. */
    public long valueAt(long nowMillis) {
        return valueAt(nowMillis, 0L);
    }

    /** Returns the display value using wall time for absolute countdowns and monotonic time otherwise. */
    public long valueAt(long nowWallTimeMillis, long nowElapsedRealtimeMillis) {
        if (!running) {
            return Math.max(0L, valueAtAnchorMillis);
        }
        if (type == Type.COUNTDOWN) {
            return positiveDifference(configuredMillis, nowWallTimeMillis);
        }
        long elapsed = elapsedSinceAnchor(nowWallTimeMillis, nowElapsedRealtimeMillis);
        if (type == Type.STOPWATCH) {
            return saturatingAdd(Math.max(0L, valueAtAnchorMillis), elapsed);
        }
        return Math.max(0L, valueAtAnchorMillis - Math.min(valueAtAnchorMillis, elapsed));
    }

    /** Returns elapsed profile time, capped at completion for finite clocks. */
    public long cueElapsedAt(long nowMillis) {
        return cueElapsedAt(nowMillis, 0L);
    }

    /** Returns elapsed profile time using a monotonic source while the clock is running. */
    public long cueElapsedAt(long nowWallTimeMillis, long nowElapsedRealtimeMillis) {
        if (!running) {
            return Math.max(0L, cueElapsedAtAnchorMillis);
        }
        long elapsed = type == Type.COUNTDOWN
                ? positiveDelta(nowWallTimeMillis, anchorWallTimeMillis)
                : elapsedSinceAnchor(nowWallTimeMillis, nowElapsedRealtimeMillis);
        if (type != Type.STOPWATCH) {
            elapsed = Math.min(Math.max(0L, valueAtAnchorMillis), elapsed);
        }
        return saturatingAdd(Math.max(0L, cueElapsedAtAnchorMillis), elapsed);
    }

    public void start(long nowMillis) {
        start(nowMillis, 0L);
    }

    public void start(long nowWallTimeMillis, long nowElapsedRealtimeMillis) {
        if (running) {
            return;
        }
        // A countdown's configured value is an absolute wall-clock target. Recompute
        // after a delayed start or pause so those actions never move the target.
        if (type == Type.COUNTDOWN) {
            valueAtAnchorMillis = positiveDifference(configuredMillis, nowWallTimeMillis);
        }
        if (type != Type.STOPWATCH && valueAtAnchorMillis <= 0L) {
            completed = true;
            anchorWallTimeMillis = nowWallTimeMillis;
            anchorElapsedRealtimeMillis = nonNegative(nowElapsedRealtimeMillis);
            return;
        }
        anchorWallTimeMillis = nowWallTimeMillis;
        anchorElapsedRealtimeMillis = nonNegative(nowElapsedRealtimeMillis);
        running = true;
        completed = false;
    }

    public void pause(long nowMillis) {
        pause(nowMillis, 0L);
    }

    public void pause(long nowWallTimeMillis, long nowElapsedRealtimeMillis) {
        if (!running) {
            return;
        }
        long currentValue = valueAt(nowWallTimeMillis, nowElapsedRealtimeMillis);
        long currentCueElapsed = cueElapsedAt(nowWallTimeMillis, nowElapsedRealtimeMillis);
        valueAtAnchorMillis = currentValue;
        cueElapsedAtAnchorMillis = currentCueElapsed;
        anchorWallTimeMillis = nowWallTimeMillis;
        anchorElapsedRealtimeMillis = nonNegative(nowElapsedRealtimeMillis);
        running = false;
        if (type != Type.STOPWATCH && currentValue == 0L) {
            completed = true;
        }
    }

    /** Restores the configured initial state and clears all cue/completion bookkeeping. */
    public void reset(long nowMillis) {
        reset(nowMillis, 0L);
    }

    public void reset(long nowWallTimeMillis, long nowElapsedRealtimeMillis) {
        valueAtAnchorMillis = initialValue(nowWallTimeMillis);
        cueElapsedAtAnchorMillis = 0L;
        anchorWallTimeMillis = nowWallTimeMillis;
        anchorElapsedRealtimeMillis = nonNegative(nowElapsedRealtimeMillis);
        lastCueElapsedMillis = 0L;
        initialCuePending = true;
        running = false;
        completed = type != Type.STOPWATCH && valueAtAnchorMillis == 0L;
        completionBeepPlayed = false;
    }

    /**
     * Applies a new timer duration or countdown target. A changed time setting starts a
     * fresh paused run; saving the existing setting leaves current progress untouched.
     *
     * @return true when the configuration changed and the clock was reset
     */
    public boolean reconfigure(long newConfiguredMillis, long nowMillis) {
        return reconfigure(newConfiguredMillis, nowMillis, 0L);
    }

    public boolean reconfigure(
            long newConfiguredMillis,
            long nowWallTimeMillis,
            long nowElapsedRealtimeMillis) {
        if (type == Type.STOPWATCH) {
            if (newConfiguredMillis != 0L) {
                throw new IllegalArgumentException("A stopwatch cannot have a configured duration");
            }
            return false;
        }
        if (newConfiguredMillis <= 0L) {
            throw new IllegalArgumentException("Finite clocks require a positive configuration");
        }
        if (configuredMillis == newConfiguredMillis) {
            return false;
        }
        configuredMillis = newConfiguredMillis;
        reset(nowWallTimeMillis, nowElapsedRealtimeMillis);
        return true;
    }

    /**
     * Freezes a finite clock that has reached zero.
     *
     * @return true only when this call transitions the clock to completed
     */
    public boolean settleCompletion(long nowMillis) {
        return settleCompletion(nowMillis, 0L);
    }

    public boolean settleCompletion(long nowWallTimeMillis, long nowElapsedRealtimeMillis) {
        if (type == Type.STOPWATCH
                || completed
                || valueAt(nowWallTimeMillis, nowElapsedRealtimeMillis) > 0L) {
            return false;
        }
        cueElapsedAtAnchorMillis = cueElapsedAt(nowWallTimeMillis, nowElapsedRealtimeMillis);
        valueAtAnchorMillis = 0L;
        anchorWallTimeMillis = nowWallTimeMillis;
        anchorElapsedRealtimeMillis = nonNegative(nowElapsedRealtimeMillis);
        running = false;
        completed = true;
        return true;
    }

    /**
     * Rebases a running clock to the current boot's monotonic clock. This is used after a
     * service/process restart; if the persisted monotonic anchor belongs to an earlier boot,
     * the wall-time anchor supplies the one-time recovery estimate.
     */
    public boolean rebaseMonotonicAnchor(long nowWallTimeMillis, long nowElapsedRealtimeMillis) {
        if (!running || nowElapsedRealtimeMillis <= 0L) {
            return false;
        }
        long currentValue = valueAt(nowWallTimeMillis, nowElapsedRealtimeMillis);
        long currentCueElapsed = cueElapsedAt(nowWallTimeMillis, nowElapsedRealtimeMillis);
        valueAtAnchorMillis = currentValue;
        cueElapsedAtAnchorMillis = currentCueElapsed;
        anchorWallTimeMillis = nowWallTimeMillis;
        anchorElapsedRealtimeMillis = nowElapsedRealtimeMillis;
        return true;
    }

    public String toJson() {
        validate();
        return new StringBuilder(384)
                .append('{')
                .append("\"id\":").append(JsonSupport.quote(id)).append(',')
                .append("\"name\":").append(JsonSupport.quote(name)).append(',')
                .append("\"profileId\":").append(JsonSupport.quote(profileId)).append(',')
                .append("\"type\":").append(JsonSupport.quote(type.name())).append(',')
                .append("\"configuredMillis\":").append(configuredMillis).append(',')
                .append("\"valueAtAnchorMillis\":").append(valueAtAnchorMillis).append(',')
                .append("\"cueElapsedAtAnchorMillis\":").append(cueElapsedAtAnchorMillis).append(',')
                .append("\"anchorWallTimeMillis\":").append(anchorWallTimeMillis).append(',')
                .append("\"anchorElapsedRealtimeMillis\":").append(anchorElapsedRealtimeMillis).append(',')
                .append("\"lastCueElapsedMillis\":").append(lastCueElapsedMillis).append(',')
                .append("\"initialCuePending\":").append(initialCuePending).append(',')
                .append("\"running\":").append(running).append(',')
                .append("\"completed\":").append(completed).append(',')
                .append("\"completionBeepPlayed\":").append(completionBeepPlayed)
                .append('}')
                .toString();
    }

    public static ClockInstance fromJson(String json) {
        return fromJsonObject(JsonSupport.parseObject(json));
    }

    static ClockInstance fromJsonObject(Map<String, Object> object) {
        ClockInstance clock = new ClockInstance();
        clock.id = JsonSupport.requiredString(object, "id");
        clock.name = JsonSupport.requiredString(object, "name");
        clock.profileId = JsonSupport.nullableString(object, "profileId");
        try {
            clock.type = Type.valueOf(JsonSupport.requiredString(object, "type"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown clock type", exception);
        }
        clock.configuredMillis = JsonSupport.requiredLong(object, "configuredMillis");
        clock.valueAtAnchorMillis = JsonSupport.requiredLong(object, "valueAtAnchorMillis");
        clock.cueElapsedAtAnchorMillis = JsonSupport.requiredLong(object, "cueElapsedAtAnchorMillis");
        clock.anchorWallTimeMillis = JsonSupport.requiredLong(object, "anchorWallTimeMillis");
        clock.anchorElapsedRealtimeMillis = JsonSupport.optionalLong(
                object, "anchorElapsedRealtimeMillis", 0L);
        clock.lastCueElapsedMillis = JsonSupport.requiredLong(object, "lastCueElapsedMillis");
        clock.initialCuePending = JsonSupport.optionalBoolean(
                object, "initialCuePending", false);
        clock.running = JsonSupport.requiredBoolean(object, "running");
        clock.completed = JsonSupport.requiredBoolean(object, "completed");
        clock.completionBeepPlayed = JsonSupport.requiredBoolean(object, "completionBeepPlayed");
        clock.validate();
        return clock;
    }

    private long initialValue(long nowMillis) {
        if (type == Type.STOPWATCH) {
            return 0L;
        }
        if (type == Type.TIMER) {
            return configuredMillis;
        }
        return positiveDifference(configuredMillis, nowMillis);
    }

    private void validate() {
        id = requireNonBlank(id, "id");
        name = requireNonBlank(name, "name");
        profileId = normalizeNullable(profileId);
        Objects.requireNonNull(type, "type");
        if (type == Type.STOPWATCH && configuredMillis != 0L) {
            throw new IllegalArgumentException("A stopwatch cannot have a configured duration");
        }
        if (type != Type.STOPWATCH && configuredMillis <= 0L) {
            throw new IllegalArgumentException("Finite clocks require a positive configuration");
        }
        if (valueAtAnchorMillis < 0L
                || cueElapsedAtAnchorMillis < 0L
                || lastCueElapsedMillis < 0L) {
            throw new IllegalArgumentException("Clock values and cue positions cannot be negative");
        }
        if (anchorWallTimeMillis < 0L || anchorElapsedRealtimeMillis < 0L) {
            throw new IllegalArgumentException("Clock anchors cannot be negative");
        }
        if (completed && (type == Type.STOPWATCH || running || valueAtAnchorMillis != 0L)) {
            throw new IllegalArgumentException("Completed state is inconsistent");
        }
        if (completionBeepPlayed && !completed) {
            throw new IllegalArgumentException("A completion beep requires a completed clock");
        }
        if (!running && lastCueElapsedMillis > cueElapsedAtAnchorMillis) {
            throw new IllegalArgumentException("The cue checkpoint exceeds elapsed cue time");
        }
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
        return value.trim();
    }

    private static String normalizeNullable(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static long positiveDelta(long later, long earlier) {
        if (later <= earlier) {
            return 0L;
        }
        try {
            return Math.subtractExact(later, earlier);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private long elapsedSinceAnchor(long nowWallTimeMillis, long nowElapsedRealtimeMillis) {
        if (nowElapsedRealtimeMillis > 0L
                && anchorElapsedRealtimeMillis > 0L
                && nowElapsedRealtimeMillis >= anchorElapsedRealtimeMillis) {
            return positiveDelta(nowElapsedRealtimeMillis, anchorElapsedRealtimeMillis);
        }
        return positiveDelta(nowWallTimeMillis, anchorWallTimeMillis);
    }

    private static long nonNegative(long value) {
        return Math.max(0L, value);
    }

    private static long positiveDifference(long greater, long lesser) {
        if (greater <= lesser) {
            return 0L;
        }
        try {
            return Math.subtractExact(greater, lesser);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static long saturatingAdd(long first, long second) {
        try {
            return Math.addExact(first, second);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }
}
