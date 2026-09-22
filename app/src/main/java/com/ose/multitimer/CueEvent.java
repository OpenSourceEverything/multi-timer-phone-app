package com.ose.multitimer;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** One absolute event in a scripted profile. */
public final class CueEvent {
    public String id;
    public long offsetMillis;
    public String speechText;
    /** Zero means no beep; a positive value is the event-specific beep length. */
    public long beepDurationMillis;

    private CueEvent() {
    }

    public static CueEvent create(
            long offsetMillis, String speechText, long beepDurationMillis) {
        CueEvent event = new CueEvent();
        event.id = UUID.randomUUID().toString();
        event.offsetMillis = offsetMillis;
        event.speechText = normalizeSpeech(speechText);
        event.beepDurationMillis = beepDurationMillis;
        event.validate();
        return event;
    }

    public boolean hasSpeech() {
        return speechText != null && !speechText.isEmpty();
    }

    public boolean hasBeep() {
        return beepDurationMillis > 0L;
    }

    public void validate() {
        id = requireNonBlank(id, "id");
        if (offsetMillis < 0L) {
            throw new IllegalArgumentException("Event time cannot be negative");
        }
        speechText = normalizeSpeech(speechText);
        if (!hasSpeech() && !hasBeep()) {
            throw new IllegalArgumentException("An event needs speech or a beep");
        }
        if (beepDurationMillis < 0L
                || beepDurationMillis > BeepProfile.MAX_BEEP_DURATION_MILLIS) {
            throw new IllegalArgumentException("Event beep must be from 0 to 10,000 milliseconds");
        }
    }

    String toJson() {
        validate();
        return new StringBuilder(160)
                .append('{')
                .append("\"id\":").append(JsonSupport.quote(id)).append(',')
                .append("\"offsetMillis\":").append(offsetMillis).append(',')
                .append("\"speechText\":").append(speechText == null
                        ? "null" : JsonSupport.quote(speechText)).append(',')
                .append("\"beepDurationMillis\":").append(beepDurationMillis)
                .append('}')
                .toString();
    }

    static CueEvent fromJsonObject(Map<String, Object> object) {
        CueEvent event = new CueEvent();
        event.id = JsonSupport.requiredString(object, "id");
        event.offsetMillis = JsonSupport.requiredLong(object, "offsetMillis");
        event.speechText = JsonSupport.nullableString(object, "speechText");
        event.beepDurationMillis = JsonSupport.requiredLong(object, "beepDurationMillis");
        event.validate();
        return event;
    }

    private static String normalizeSpeech(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
        return value.trim();
    }
}
