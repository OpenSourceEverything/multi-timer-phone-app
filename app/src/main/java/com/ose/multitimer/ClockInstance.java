package com.ose.multitimer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
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
    /** Legacy single-profile field; kept as the first entry for old saved clocks. */
    public String profileId;
    /** Profiles scheduled on this clock. Multiple profiles may overlap. */
    public List<String> profileIds;
    /** Independent cue checkpoints prevent one profile from consuming another's events. */
    public Map<String, Long> cueElapsedByProfile;
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
    /** Stable identity for one active segment; rebasing after process/device restart keeps it. */
    public String activeSegmentId;
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
        clock.profileIds = new ArrayList<>();
        if (clock.profileId != null) {
            clock.profileIds.add(clock.profileId);
        }
        clock.cueElapsedByProfile = new HashMap<>();
        if (clock.profileId != null) {
            clock.cueElapsedByProfile.put(clock.profileId, 0L);
        }
        clock.type = type;
        clock.configuredMillis = type == Type.STOPWATCH ? 0L : configuredMillis;
        clock.valueAtAnchorMillis = clock.initialValue(nowWallTimeMillis);
        clock.cueElapsedAtAnchorMillis = 0L;
        clock.anchorWallTimeMillis = nowWallTimeMillis;
        clock.anchorElapsedRealtimeMillis = nonNegative(nowElapsedRealtimeMillis);
        clock.activeSegmentId = UUID.randomUUID().toString();
        clock.lastCueElapsedMillis = 0L;
        clock.initialCuePending = true;
        clock.running = false;
        clock.completed = type != Type.STOPWATCH && clock.valueAtAnchorMillis == 0L;
        clock.completionBeepPlayed = false;
        return clock;
    }

    /** Returns the attached profile IDs, including the legacy profile when needed. */
    public List<String> attachedProfileIds() {
        normalizeProfileState();
        return new ArrayList<>(profileIds);
    }

    /** Replaces the attached profiles while preserving existing per-profile checkpoints. */
    public void setAttachedProfileIds(Collection<String> ids) {
        Objects.requireNonNull(ids, "ids");
        List<String> normalized = new ArrayList<>();
        for (String id : ids) {
            String value = normalizeNullable(id);
            if (value != null && !normalized.contains(value)) {
                normalized.add(value);
            }
        }
        normalizeProfileState();
        Map<String, Long> previous = new HashMap<>(cueElapsedByProfile);
        profileIds = normalized;
        profileId = normalized.isEmpty() ? null : normalized.get(0);
        cueElapsedByProfile = new HashMap<>();
        for (String id : normalized) {
            cueElapsedByProfile.put(id, previous.containsKey(id)
                    ? Math.max(0L, previous.get(id)) : 0L);
        }
    }

    public long cueElapsedForProfile(String id) {
        normalizeProfileState();
        Long value = cueElapsedByProfile.get(id);
        return value == null ? 0L : Math.max(0L, value);
    }

    public void setCueElapsedForProfile(String id, long elapsedMillis) {
        String normalized = requireNonBlank(id, "profile id");
        if (elapsedMillis < 0L) {
            throw new IllegalArgumentException("Cue position cannot be negative");
        }
        normalizeProfileState();
        if (!profileIds.contains(normalized)) {
            profileIds.add(normalized);
        }
        cueElapsedByProfile.put(normalized, elapsedMillis);
        profileId = profileIds.isEmpty() ? null : profileIds.get(0);
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
        // A resume starts a new diagnostic expectation window. The prior segment's cues
        // were cancelled at Pause and must not be confused with new cues at the same offset.
        activeSegmentId = UUID.randomUUID().toString();
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
        activeSegmentId = UUID.randomUUID().toString();
        lastCueElapsedMillis = 0L;
        initialCuePending = true;
        running = false;
        completed = type != Type.STOPWATCH && valueAtAnchorMillis == 0L;
        completionBeepPlayed = false;
        normalizeProfileState();
        cueElapsedByProfile.clear();
        for (String profile : profileIds) {
            cueElapsedByProfile.put(profile, 0L);
        }
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
        normalizeProfileState();
        return toJsonWithProfiles();
    }

    private String toJsonWithProfiles() {
        StringBuilder json = new StringBuilder(512)
                .append('{')
                .append("\"id\":").append(JsonSupport.quote(id)).append(',')
                .append("\"name\":").append(JsonSupport.quote(name)).append(',')
                .append("\"profileId\":").append(JsonSupport.quote(profileId)).append(',')
                .append("\"profileIds\":[");
        for (int i = 0; i < profileIds.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(JsonSupport.quote(profileIds.get(i)));
        }
        json.append("],\"cueElapsedByProfile\":{");
        int checkpointIndex = 0;
        for (String profile : profileIds) {
            if (checkpointIndex++ > 0) {
                json.append(',');
            }
            json.append(JsonSupport.quote(profile)).append(':')
                    .append(cueElapsedForProfile(profile));
        }
        return json.append("},")
                .append("\"type\":").append(JsonSupport.quote(type.name())).append(',')
                .append("\"configuredMillis\":").append(configuredMillis).append(',')
                .append("\"valueAtAnchorMillis\":").append(valueAtAnchorMillis).append(',')
                .append("\"cueElapsedAtAnchorMillis\":").append(cueElapsedAtAnchorMillis).append(',')
                .append("\"anchorWallTimeMillis\":").append(anchorWallTimeMillis).append(',')
                .append("\"anchorElapsedRealtimeMillis\":").append(anchorElapsedRealtimeMillis).append(',')
                .append("\"activeSegmentId\":").append(JsonSupport.quote(activeSegmentId)).append(',')
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
        clock.profileIds = optionalProfileIds(object, clock.profileId);
        clock.cueElapsedByProfile = optionalCueCheckpoints(object, clock.profileIds);
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
        // Older saved clocks have no segment ID. Derive a repeatable migration value
        // instead of inventing a different ID on every load before the next save.
        clock.activeSegmentId = object.containsKey("activeSegmentId")
                ? JsonSupport.nullableString(object, "activeSegmentId") : null;
        if (clock.activeSegmentId == null) {
            String legacySegment = clock.id + "|" + clock.anchorWallTimeMillis
                    + "|" + clock.anchorElapsedRealtimeMillis;
            clock.activeSegmentId = UUID.nameUUIDFromBytes(
                    legacySegment.getBytes(StandardCharsets.UTF_8)).toString();
        }
        clock.lastCueElapsedMillis = JsonSupport.requiredLong(object, "lastCueElapsedMillis");
        if (!object.containsKey("cueElapsedByProfile")) {
            for (String profileId : clock.profileIds) {
                clock.cueElapsedByProfile.put(profileId, clock.lastCueElapsedMillis);
            }
        }
        clock.initialCuePending = JsonSupport.optionalBoolean(
                object, "initialCuePending", false);
        clock.running = JsonSupport.requiredBoolean(object, "running");
        clock.completed = JsonSupport.requiredBoolean(object, "completed");
        clock.completionBeepPlayed = JsonSupport.requiredBoolean(object, "completionBeepPlayed");
        clock.validate();
        return clock;
    }

    private static List<String> optionalProfileIds(Map<String, Object> object, String legacyId) {
        List<String> result = new ArrayList<>();
        if (object.containsKey("profileIds")) {
            Object raw = object.get("profileIds");
            if (!(raw instanceof List)) {
                throw new IllegalArgumentException("Expected array field: profileIds");
            }
            for (Object entry : (List<?>) raw) {
                if (!(entry instanceof String)) {
                    throw new IllegalArgumentException("Expected string entries in: profileIds");
                }
                String id = normalizeNullable((String) entry);
                if (id != null && !result.contains(id)) {
                    result.add(id);
                }
            }
        }
        String normalizedLegacy = normalizeNullable(legacyId);
        if (result.isEmpty() && normalizedLegacy != null) {
            result.add(normalizedLegacy);
        }
        return result;
    }

    private static Map<String, Long> optionalCueCheckpoints(
            Map<String, Object> object, List<String> profileIds) {
        Map<String, Long> result = new HashMap<>();
        if (object.containsKey("cueElapsedByProfile")) {
            Object raw = object.get("cueElapsedByProfile");
            if (!(raw instanceof Map)) {
                throw new IllegalArgumentException("Expected object field: cueElapsedByProfile");
            }
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
                if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof Long)) {
                    throw new IllegalArgumentException("Invalid profile cue checkpoint");
                }
                long value = (Long) entry.getValue();
                if (value < 0L) {
                    throw new IllegalArgumentException("Cue position cannot be negative");
                }
                result.put((String) entry.getKey(), value);
            }
        }
        for (String profileId : profileIds) {
            result.putIfAbsent(profileId, 0L);
        }
        return result;
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
        normalizeProfileState();
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
        activeSegmentId = requireNonBlank(activeSegmentId, "activeSegmentId");
        if (completed && (type == Type.STOPWATCH || running || valueAtAnchorMillis != 0L)) {
            throw new IllegalArgumentException("Completed state is inconsistent");
        }
        if (completionBeepPlayed && !completed) {
            throw new IllegalArgumentException("A completion beep requires a completed clock");
        }
        if (!running && lastCueElapsedMillis > cueElapsedAtAnchorMillis) {
            throw new IllegalArgumentException("The cue checkpoint exceeds elapsed cue time");
        }
        for (String profile : profileIds) {
            long checkpoint = cueElapsedForProfile(profile);
            if (!running && checkpoint > cueElapsedAtAnchorMillis) {
                throw new IllegalArgumentException(
                        "A profile cue checkpoint exceeds elapsed cue time");
            }
        }
    }

    private void normalizeProfileState() {
        if (profileIds == null) {
            profileIds = new ArrayList<>();
        }
        List<String> normalized = new ArrayList<>();
        for (String id : profileIds) {
            String value = normalizeNullable(id);
            if (value != null && !normalized.contains(value)) {
                normalized.add(value);
            }
        }
        String normalizedLegacy = normalizeNullable(profileId);
        if (normalized.isEmpty() && normalizedLegacy != null) {
            normalized.add(normalizedLegacy);
        }
        profileIds = normalized;
        profileId = normalized.isEmpty() ? null : normalized.get(0);
        if (cueElapsedByProfile == null) {
            cueElapsedByProfile = new HashMap<>();
        }
        cueElapsedByProfile.keySet().removeIf(key -> !profileIds.contains(key));
        for (String id : profileIds) {
            Long value = cueElapsedByProfile.get(id);
            cueElapsedByProfile.put(id, value == null ? 0L : Math.max(0L, value));
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
