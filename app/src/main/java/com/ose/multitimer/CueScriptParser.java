package com.ose.multitimer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Parses the small, deliberately non-programming script format used by profiles. */
public final class CueScriptParser {
    private CueScriptParser() {
    }

    /**
     * Parses either pipe text rows or a small TOML-like event list.
     *
     * <pre>
     * 00:00 | say "Start" | beep
     * 00:30 | say "Rest"
     *
     * [[event]]
     * at = "01:00"
     * say = "Next set"
     * beep_ms = 500
     * </pre>
     */
    public static List<CueEvent> parse(String text, long defaultBeepDurationMillis) {
        if (defaultBeepDurationMillis < BeepProfile.MIN_BEEP_DURATION_MILLIS
                || defaultBeepDurationMillis > BeepProfile.MAX_BEEP_DURATION_MILLIS) {
            throw new IllegalArgumentException("Default beep duration is out of range");
        }
        if (text == null || text.trim().isEmpty()) {
            return new ArrayList<>();
        }

        List<CueEvent> events = new ArrayList<>();
        TomlEvent toml = null;
        String[] lines = text.split("\\r?\\n");
        for (int lineNumber = 0; lineNumber < lines.length; lineNumber++) {
            String line = stripComment(lines[lineNumber]).trim();
            if (line.isEmpty() || line.equals("[profile]") || line.equals("[[event]]")
                    || line.equals("[[events]]")) {
                if (line.startsWith("[[")) {
                    if (toml != null) {
                        events.add(toml.toEvent(defaultBeepDurationMillis, lineNumber));
                    }
                    toml = new TomlEvent();
                }
                continue;
            }
            if (toml != null && line.indexOf('=') > 0 && line.indexOf('|') < 0) {
                parseTomlProperty(toml, line, lineNumber + 1);
                continue;
            }
            if (toml != null) {
                events.add(toml.toEvent(defaultBeepDurationMillis, lineNumber));
                toml = null;
            }
            events.add(parsePipeRow(line, defaultBeepDurationMillis, lineNumber + 1));
        }
        if (toml != null) {
            events.add(toml.toEvent(defaultBeepDurationMillis, lines.length));
        }

        Collections.sort(events, Comparator.comparingLong(event -> event.offsetMillis));
        return events;
    }

    public static String format(List<CueEvent> events) {
        StringBuilder output = new StringBuilder();
        for (CueEvent event : events) {
            event.validate();
            if (output.length() > 0) {
                output.append('\n');
            }
            output.append(TimeUtils.formatInput(event.offsetMillis)).append(" | ");
            if (event.hasSpeech()) {
                output.append("say \"").append(event.speechText.replace("\"", "\\\""))
                        .append('"');
            }
            if (event.hasBeep()) {
                if (event.hasSpeech()) {
                    output.append(" | ");
                }
                output.append("beep ").append(event.beepDurationMillis);
            }
        }
        return output.toString();
    }

    private static CueEvent parsePipeRow(
            String line, long defaultBeepDurationMillis, int lineNumber) {
        String[] columns = line.split("\\|", -1);
        if (columns.length < 2) {
            throw error(lineNumber, "Use TIME | say \"text\" | beep [milliseconds]");
        }
        long offset = parseTime(columns[0].trim(), lineNumber);
        String speech = null;
        long beep = 0L;
        for (int i = 1; i < columns.length; i++) {
            String action = columns[i].trim();
            if (action.isEmpty()) {
                continue;
            }
            String lower = action.toLowerCase(Locale.ROOT);
            if (lower.startsWith("say")) {
                speech = parseTextValue(action.substring(3).trim(), lineNumber, "say");
            } else if (lower.startsWith("beep")) {
                String value = action.substring(4).trim();
                beep = value.isEmpty() ? defaultBeepDurationMillis : parseBeep(value, lineNumber);
            } else {
                throw error(lineNumber, "Unknown event action: " + action);
            }
        }
        return CueEvent.create(offset, speech, beep);
    }

    private static void parseTomlProperty(TomlEvent event, String line, int lineNumber) {
        int equals = line.indexOf('=');
        String key = line.substring(0, equals).trim().toLowerCase(Locale.ROOT);
        String value = line.substring(equals + 1).trim();
        switch (key) {
            case "at":
            case "time":
                event.time = parseTextValue(value, lineNumber, key);
                break;
            case "say":
            case "speech":
                event.speech = parseTextValue(value, lineNumber, key);
                break;
            case "beep":
            case "beep_ms":
            case "beep_duration_ms":
                if (value.equalsIgnoreCase("true")) {
                    event.beepRequested = true;
                    event.beep = null;
                } else if (value.equalsIgnoreCase("false")) {
                    event.beepRequested = false;
                    event.beep = 0L;
                } else {
                    event.beep = parseBeep(value, lineNumber);
                }
                break;
            default:
                throw error(lineNumber, "Unknown event property: " + key);
        }
    }

    private static long parseTime(String value, int lineNumber) {
        try {
            long parsed = TimeUtils.parseDurationMillis(value);
            if (parsed < 0L) {
                throw new IllegalArgumentException("negative time");
            }
            return parsed;
        } catch (IllegalArgumentException invalid) {
            throw error(lineNumber, "Invalid event time: " + value);
        }
    }

    private static long parseBeep(String value, int lineNumber) {
        try {
            long millis = Long.parseLong(unquote(value));
            if (millis < BeepProfile.MIN_BEEP_DURATION_MILLIS
                    || millis > BeepProfile.MAX_BEEP_DURATION_MILLIS) {
                throw new IllegalArgumentException();
            }
            return millis;
        } catch (RuntimeException invalid) {
            throw error(lineNumber, "Beep duration must be 50..10000 milliseconds");
        }
    }

    private static String parseTextValue(String value, int lineNumber, String field) {
        String parsed = unquote(value).trim();
        if (parsed.isEmpty()) {
            throw error(lineNumber, field + " text cannot be empty");
        }
        return parsed;
    }

    private static String unquote(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2
                && trimmed.charAt(0) == '"'
                && trimmed.charAt(trimmed.length() - 1) == '"') {
            return trimmed.substring(1, trimmed.length() - 1).replace("\\\"", "\"");
        }
        return trimmed;
    }

    private static String stripComment(String line) {
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == '"' && (i == 0 || line.charAt(i - 1) != '\\')) {
                quoted = !quoted;
            } else if (!quoted && (line.charAt(i) == '#' || line.startsWith("//", i))) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static IllegalArgumentException error(int line, String message) {
        return new IllegalArgumentException("Script line " + line + ": " + message);
    }

    private static final class TomlEvent {
        String time;
        String speech;
        Long beep;
        boolean beepRequested;

        CueEvent toEvent(long defaultBeepDurationMillis, int lineNumber) {
            if (time == null) {
                throw error(lineNumber, "Event requires at/time");
            }
            long beepMillis = beep != null
                    ? beep : beepRequested || speech == null ? defaultBeepDurationMillis : 0L;
            return CueEvent.create(parseTime(time, lineNumber), speech, beepMillis);
        }
    }
}
