package com.ose.multitimer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** A reusable audible-cue schedule. */
public final class BeepProfile {
    public enum Mode {
        PERIODIC,
        SCRIPTED
    }

    public static final String DEFAULT_EVERY_MINUTE_ID = "default-every-minute";
    public static final String DEFAULT_FOCUS_CUES_ID = "default-focus-cues";
    public static final String DEFAULT_COMPLETION_ONLY_ID = "default-completion-only";
    public static final long MIN_BEEP_DURATION_MILLIS = 50L;
    public static final long MAX_BEEP_DURATION_MILLIS = 10_000L;
    public static final long NO_CUE = -1L;

    public String id;
    public String name;
    public Mode mode;
    public long beepDurationMillis;
    public long periodMillis;
    public List<Long> scriptOffsetsMillis;
    /** Structured scripted events; legacy profiles are converted from scriptOffsetsMillis. */
    public List<CueEvent> events;
    public boolean beepOnCompletion;

    private BeepProfile() {
    }

    public static BeepProfile periodic(
            String name, long beepDurationMillis, long periodMillis, boolean beepOnCompletion) {
        BeepProfile profile = new BeepProfile();
        profile.id = UUID.randomUUID().toString();
        profile.name = name;
        profile.mode = Mode.PERIODIC;
        profile.beepDurationMillis = beepDurationMillis;
        profile.periodMillis = periodMillis;
        profile.scriptOffsetsMillis = new ArrayList<>();
        profile.events = new ArrayList<>();
        profile.beepOnCompletion = beepOnCompletion;
        profile.validate();
        return profile;
    }

    public static BeepProfile scripted(
            String name,
            long beepDurationMillis,
            List<Long> scriptOffsetsMillis,
            boolean beepOnCompletion) {
        BeepProfile profile = new BeepProfile();
        profile.id = UUID.randomUUID().toString();
        profile.name = name;
        profile.mode = Mode.SCRIPTED;
        profile.beepDurationMillis = beepDurationMillis;
        profile.periodMillis = 0L;
        profile.scriptOffsetsMillis = new ArrayList<>(
                Objects.requireNonNull(scriptOffsetsMillis, "scriptOffsetsMillis"));
        profile.events = new ArrayList<>();
        for (Long offset : profile.scriptOffsetsMillis) {
            profile.events.add(CueEvent.create(offset, null, beepDurationMillis));
        }
        profile.beepOnCompletion = beepOnCompletion;
        profile.validate();
        return profile;
    }

    public static BeepProfile scriptedEvents(
            String name,
            long beepDurationMillis,
            List<CueEvent> events,
            boolean beepOnCompletion) {
        BeepProfile profile = new BeepProfile();
        profile.id = UUID.randomUUID().toString();
        profile.name = name;
        profile.mode = Mode.SCRIPTED;
        profile.beepDurationMillis = beepDurationMillis;
        profile.periodMillis = 0L;
        profile.events = new ArrayList<>(Objects.requireNonNull(events, "events"));
        profile.scriptOffsetsMillis = offsetsFromEvents(profile.events);
        profile.beepOnCompletion = beepOnCompletion;
        profile.validate();
        return profile;
    }

    /** Returns immutable fresh instances of the profiles seeded into a new store. */
    public static List<BeepProfile> defaultProfiles() {
        BeepProfile everyMinute = periodic("Every minute", 500L, 60_000L, true);
        everyMinute.id = DEFAULT_EVERY_MINUTE_ID;

        BeepProfile focusCues = scripted(
                "Focus cues",
                500L,
                Arrays.asList(5 * 60_000L, 10 * 60_000L, 15 * 60_000L),
                true);
        focusCues.id = DEFAULT_FOCUS_CUES_ID;

        BeepProfile completionOnly = scripted(
                "Completion only", 750L, Collections.emptyList(), true);
        completionOnly.id = DEFAULT_COMPLETION_ONLY_ID;
        return Collections.unmodifiableList(
                Arrays.asList(everyMinute, focusCues, completionOnly));
    }

    /** Returns the first structured event crossed by an elapsed-time window. */
    public CueEvent firstEventBetween(long exclusiveFromMillis, long inclusiveToMillis) {
        validate();
        if (inclusiveToMillis < exclusiveFromMillis || inclusiveToMillis < 0L) {
            return null;
        }
        if (mode == Mode.PERIODIC) {
            long nonNegativeStart = Math.max(0L, exclusiveFromMillis);
            long startBucket = nonNegativeStart / periodMillis;
            if (startBucket == Long.MAX_VALUE
                    || startBucket + 1L > Long.MAX_VALUE / periodMillis) {
                return null;
            }
            long firstCue = (startBucket + 1L) * periodMillis;
            return firstCue <= inclusiveToMillis
                    ? CueEvent.create(firstCue, null, beepDurationMillis) : null;
        }
        for (CueEvent event : events) {
            if (exclusiveFromMillis == 0L && event.offsetMillis == 0L
                    && inclusiveToMillis >= 0L) {
                return event;
            }
            if (event.offsetMillis > inclusiveToMillis) {
                return null;
            }
            if (event.offsetMillis > exclusiveFromMillis) {
                return event;
            }
        }
        return null;
    }

    /**
     * Reports whether one or more scheduled cue offsets fall in
     * ({@code exclusiveFromMillis}, {@code inclusiveToMillis}].
     */
    public boolean hasCueBetween(long exclusiveFromMillis, long inclusiveToMillis) {
        return firstCueBetween(exclusiveFromMillis, inclusiveToMillis) != NO_CUE;
    }

    /** Returns the first scheduled cue in ({@code exclusiveFromMillis}, {@code inclusiveToMillis}]. */
    public long firstCueBetween(long exclusiveFromMillis, long inclusiveToMillis) {
        CueEvent event = firstEventBetween(exclusiveFromMillis, inclusiveToMillis);
        return event == null ? NO_CUE : event.offsetMillis;
    }

    /** Validates and normalizes this profile before it is scheduled or persisted. */
    public void validate() {
        id = requireNonBlank(id, "id");
        name = requireNonBlank(name, "name");
        Objects.requireNonNull(mode, "mode");
        if (beepDurationMillis < MIN_BEEP_DURATION_MILLIS
                || beepDurationMillis > MAX_BEEP_DURATION_MILLIS) {
            throw new IllegalArgumentException(
                    "Beep duration must be from " + MIN_BEEP_DURATION_MILLIS
                            + " to " + MAX_BEEP_DURATION_MILLIS + " milliseconds");
        }
        if (scriptOffsetsMillis == null) {
            throw new IllegalArgumentException("Script offsets cannot be null");
        }
        if (events == null) {
            events = new ArrayList<>();
            for (Long offset : scriptOffsetsMillis) {
                events.add(CueEvent.create(offset, null, beepDurationMillis));
            }
        }

        if (mode == Mode.PERIODIC) {
            if (periodMillis <= 0L) {
                throw new IllegalArgumentException("A periodic profile requires a positive period");
            }
            if (!scriptOffsetsMillis.isEmpty()) {
                throw new IllegalArgumentException("A periodic profile cannot contain script offsets");
            }
            if (!events.isEmpty()) {
                throw new IllegalArgumentException("A periodic profile cannot contain events");
            }
            return;
        }

        if (periodMillis != 0L) {
            throw new IllegalArgumentException("A scripted profile cannot have a period");
        }
        long previous = -1L;
        if (events.size() != scriptOffsetsMillis.size()) {
            throw new IllegalArgumentException("Script offsets and events must match");
        }
        for (int i = 0; i < events.size(); i++) {
            CueEvent event = Objects.requireNonNull(events.get(i), "event");
            event.validate();
            if (event.offsetMillis <= previous) {
                throw new IllegalArgumentException("Script event times must be increasing");
            }
            if (!Objects.equals(scriptOffsetsMillis.get(i), event.offsetMillis)) {
                throw new IllegalArgumentException("Script offsets and events must match");
            }
            previous = event.offsetMillis;
        }
    }

    public String toJson() {
        validate();
        StringBuilder json = new StringBuilder(256)
                .append('{')
                .append("\"id\":").append(JsonSupport.quote(id)).append(',')
                .append("\"name\":").append(JsonSupport.quote(name)).append(',')
                .append("\"mode\":").append(JsonSupport.quote(mode.name())).append(',')
                .append("\"beepDurationMillis\":").append(beepDurationMillis).append(',')
                .append("\"periodMillis\":").append(periodMillis).append(',')
                .append("\"scriptOffsetsMillis\":[");
        for (int i = 0; i < scriptOffsetsMillis.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(scriptOffsetsMillis.get(i));
        }
        json.append("],\"events\":[");
        for (int i = 0; i < events.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(events.get(i).toJson());
        }
        return json.append("],\"beepOnCompletion\":")
                .append(beepOnCompletion)
                .append('}')
                .toString();
    }

    public static BeepProfile fromJson(String json) {
        return fromJsonObject(JsonSupport.parseObject(json));
    }

    static BeepProfile fromJsonObject(Map<String, Object> object) {
        BeepProfile profile = new BeepProfile();
        profile.id = JsonSupport.requiredString(object, "id");
        profile.name = JsonSupport.requiredString(object, "name");
        try {
            profile.mode = Mode.valueOf(JsonSupport.requiredString(object, "mode"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown beep profile mode", exception);
        }
        profile.beepDurationMillis = JsonSupport.requiredLong(object, "beepDurationMillis");
        profile.periodMillis = JsonSupport.requiredLong(object, "periodMillis");
        profile.scriptOffsetsMillis = JsonSupport.requiredLongList(object, "scriptOffsetsMillis");
        profile.events = optionalEvents(object, profile.scriptOffsetsMillis, profile.beepDurationMillis);
        profile.beepOnCompletion = JsonSupport.requiredBoolean(object, "beepOnCompletion");
        profile.validate();
        return profile;
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
        return value.trim();
    }

    private static List<CueEvent> offsetsToEvents(List<Long> offsets, long beepDurationMillis) {
        List<CueEvent> result = new ArrayList<>();
        for (Long offset : offsets) {
            result.add(CueEvent.create(offset, null, beepDurationMillis));
        }
        return result;
    }

    private static List<CueEvent> optionalEvents(
            Map<String, Object> object, List<Long> offsets, long beepDurationMillis) {
        if (!object.containsKey("events")) {
            return offsetsToEvents(offsets, beepDurationMillis);
        }
        Object raw = object.get("events");
        if (!(raw instanceof List)) {
            throw new IllegalArgumentException("Expected array field: events");
        }
        List<CueEvent> result = new ArrayList<>();
        for (Object entry : (List<?>) raw) {
            if (!(entry instanceof Map)) {
                throw new IllegalArgumentException("Event list entries must be objects");
            }
            @SuppressWarnings("unchecked")
            CueEvent event = CueEvent.fromJsonObject((Map<String, Object>) entry);
            result.add(event);
        }
        return result;
    }

    private static List<Long> offsetsFromEvents(List<CueEvent> events) {
        List<Long> offsets = new ArrayList<>();
        for (CueEvent event : events) {
            offsets.add(event.offsetMillis);
        }
        return offsets;
    }
}
