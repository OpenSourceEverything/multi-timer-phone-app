package com.ose.multitimer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/** Utilities for the human-readable durations used by the clock and profile editors. */
public final class TimeUtils {
    private static final long MILLIS_PER_SECOND = 1_000L;
    private static final long SECONDS_PER_MINUTE = 60L;
    private static final long SECONDS_PER_HOUR = 3_600L;
    private static final int MAX_DURATION_ENTRY_DIGITS = 10;
    private static final long MAX_DURATION_ENTRY_HOURS = 999_999L;
    private static final long MAX_DURATION_ENTRY_SECONDS =
            MAX_DURATION_ENTRY_HOURS * SECONDS_PER_HOUR
                    + 59L * SECONDS_PER_MINUTE + 59L;

    private TimeUtils() {
    }

    /**
     * Parses {@code SS}, {@code MM:SS}, or {@code HH:MM:SS} into milliseconds.
     * The leftmost component may be greater than 59; colon-delimited minute and
     * second components must be in the range 0..59.
     */
    public static long parseDurationMillis(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("A duration is required");
        }

        String[] components = text.trim().split(":", -1);
        if (components.length < 1 || components.length > 3) {
            throw new IllegalArgumentException("Use SS, MM:SS, or HH:MM:SS");
        }

        long[] parsed = new long[components.length];
        for (int i = 0; i < components.length; i++) {
            String component = components[i].trim();
            if (component.isEmpty() || !isAsciiDigits(component)) {
                throw new IllegalArgumentException("Duration components must be non-negative integers");
            }
            try {
                parsed[i] = Long.parseLong(component);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Duration is too large", exception);
            }
        }

        if (components.length >= 2 && parsed[components.length - 1] >= 60L) {
            throw new IllegalArgumentException("Seconds must be less than 60");
        }
        if (components.length == 3 && parsed[1] >= 60L) {
            throw new IllegalArgumentException("Minutes must be less than 60");
        }

        try {
            long totalSeconds;
            if (components.length == 1) {
                totalSeconds = parsed[0];
            } else if (components.length == 2) {
                totalSeconds = Math.addExact(
                        Math.multiplyExact(parsed[0], SECONDS_PER_MINUTE), parsed[1]);
            } else {
                totalSeconds = Math.addExact(
                        Math.addExact(
                                Math.multiplyExact(parsed[0], SECONDS_PER_HOUR),
                                Math.multiplyExact(parsed[1], SECONDS_PER_MINUTE)),
                        parsed[2]);
            }
            return Math.multiplyExact(totalSeconds, MILLIS_PER_SECOND);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Duration is too large", exception);
        }
    }

    /**
     * Formats keypad digits as a fixed {@code HH:MM:SS} value. Digits fill the value
     * from the right, so {@code "3000"} becomes {@code "00:30:00"}. The hour field
     * expands for an existing or deliberately entered duration above 99 hours.
     */
    public static String formatDurationDigits(String digits) {
        validateDurationDigits(digits);
        String padded = digits.length() >= 6
                ? digits
                : "000000".substring(digits.length()) + digits;
        int minuteStart = padded.length() - 4;
        int secondStart = padded.length() - 2;
        return padded.substring(0, minuteStart) + ":"
                + padded.substring(minuteStart, secondStart) + ":"
                + padded.substring(secondStart);
    }

    /**
     * Parses right-aligned keypad digits as a positive {@code HH:MM:SS} duration.
     * The minute and second groups must be 00..59.
     */
    public static long parseDurationDigitsMillis(String digits) {
        validateDurationDigits(digits);
        if (digits.isEmpty()) {
            throw new IllegalArgumentException("A duration is required");
        }

        String formatted = formatDurationDigits(digits);
        String[] components = formatted.split(":", -1);
        long hours = Long.parseLong(components[0]);
        long minutes = Long.parseLong(components[1]);
        long seconds = Long.parseLong(components[2]);
        if (minutes >= SECONDS_PER_MINUTE) {
            throw new IllegalArgumentException("Minutes must be less than 60");
        }
        if (seconds >= SECONDS_PER_MINUTE) {
            throw new IllegalArgumentException("Seconds must be less than 60");
        }

        long totalSeconds = hours * SECONDS_PER_HOUR
                + minutes * SECONDS_PER_MINUTE + seconds;
        if (totalSeconds == 0L) {
            throw new IllegalArgumentException("Duration must be greater than zero");
        }
        return totalSeconds * MILLIS_PER_SECOND;
    }

    /**
     * Returns the shortest keypad digit buffer that represents a whole-second
     * duration. Zero is represented by the empty buffer used by a fresh editor.
     */
    public static String durationDigitsFromMillis(long millis) {
        if (millis < 0L) {
            throw new IllegalArgumentException("Duration must not be negative");
        }
        if (millis % MILLIS_PER_SECOND != 0L) {
            throw new IllegalArgumentException("Duration must be a whole number of seconds");
        }

        long totalSeconds = millis / MILLIS_PER_SECOND;
        if (totalSeconds > MAX_DURATION_ENTRY_SECONDS) {
            throw new IllegalArgumentException("Duration cannot exceed 999999:59:59");
        }

        long hours = totalSeconds / SECONDS_PER_HOUR;
        long minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE;
        long seconds = totalSeconds % SECONDS_PER_MINUTE;
        String padded = String.format(Locale.ROOT, "%02d%02d%02d", hours, minutes, seconds);
        int firstNonZero = 0;
        while (firstNonZero < padded.length() && padded.charAt(firstNonZero) == '0') {
            firstNonZero++;
        }
        return padded.substring(firstNonZero);
    }

    /** Parses comma- or newline-delimited cue offsets and returns sorted unique values. */
    public static List<Long> parseScriptOffsetsMillis(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new ArrayList<>();
        }

        TreeSet<Long> offsets = new TreeSet<>();
        for (String entry : text.split("[,\\r\\n]+")) {
            if (entry.trim().isEmpty()) {
                continue;
            }
            long offset = parseDurationMillis(entry);
            if (offset <= 0L) {
                throw new IllegalArgumentException("Cue offsets must be greater than zero");
            }
            offsets.add(offset);
        }
        return new ArrayList<>(offsets);
    }

    /** Formats a non-negative millisecond value for the clock list. */
    public static String formatDisplay(long millis) {
        long totalSeconds = Math.max(0L, millis) / MILLIS_PER_SECOND;
        long hours = totalSeconds / SECONDS_PER_HOUR;
        long minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE;
        long seconds = totalSeconds % SECONDS_PER_MINUTE;
        if (hours > 0L) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
    }

    /** Formats milliseconds in a form accepted by {@link #parseDurationMillis(String)}. */
    public static String formatInput(long millis) {
        return formatDisplay(millis);
    }

    /** Formats script offsets as a comma-delimited list suitable for the profile editor. */
    public static String formatScriptOffsetsMillis(List<Long> offsetsMillis) {
        Objects.requireNonNull(offsetsMillis, "offsetsMillis");
        StringBuilder result = new StringBuilder();
        for (Long offset : offsetsMillis) {
            if (offset == null || offset <= 0L) {
                throw new IllegalArgumentException("Cue offsets must be greater than zero");
            }
            if (result.length() > 0) {
                result.append(", ");
            }
            result.append(formatInput(offset));
        }
        return result.toString();
    }

    private static boolean isAsciiDigits(String value) {
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    private static void validateDurationDigits(String digits) {
        if (digits == null) {
            throw new IllegalArgumentException("Duration digits are required");
        }
        if (digits.length() > MAX_DURATION_ENTRY_DIGITS) {
            throw new IllegalArgumentException("Duration cannot use more than ten digits");
        }
        if (!isAsciiDigits(digits)) {
            throw new IllegalArgumentException("Duration must contain digits only");
        }
    }
}

/** Small JSON reader/writer for the fixed persistence schema; it has no Android dependency. */
final class JsonSupport {
    private JsonSupport() {
    }

    static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder result = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"':
                    result.append("\\\"");
                    break;
                case '\\':
                    result.append("\\\\");
                    break;
                case '\b':
                    result.append("\\b");
                    break;
                case '\f':
                    result.append("\\f");
                    break;
                case '\n':
                    result.append("\\n");
                    break;
                case '\r':
                    result.append("\\r");
                    break;
                case '\t':
                    result.append("\\t");
                    break;
                default:
                    if (character < 0x20) {
                        result.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        result.append(character);
                    }
            }
        }
        return result.append('"').toString();
    }

    static Map<String, Object> parseObject(String json) {
        Object parsed = new Parser(json).parseDocument();
        if (!(parsed instanceof Map)) {
            throw new IllegalArgumentException("Expected a JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> object = (Map<String, Object>) parsed;
        return object;
    }

    static List<Object> parseArray(String json) {
        Object parsed = new Parser(json).parseDocument();
        if (!(parsed instanceof List)) {
            throw new IllegalArgumentException("Expected a JSON array");
        }
        @SuppressWarnings("unchecked")
        List<Object> array = (List<Object>) parsed;
        return array;
    }

    static String requiredString(Map<String, Object> object, String key) {
        Object value = required(object, key);
        if (!(value instanceof String)) {
            throw new IllegalArgumentException("Expected string field: " + key);
        }
        return (String) value;
    }

    static String nullableString(Map<String, Object> object, String key) {
        Object value = required(object, key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String)) {
            throw new IllegalArgumentException("Expected nullable string field: " + key);
        }
        return (String) value;
    }

    static long requiredLong(Map<String, Object> object, String key) {
        Object value = required(object, key);
        if (!(value instanceof Long)) {
            throw new IllegalArgumentException("Expected integer field: " + key);
        }
        return (Long) value;
    }

    static long optionalLong(Map<String, Object> object, String key, long defaultValue) {
        if (!object.containsKey(key)) {
            return defaultValue;
        }
        Object value = object.get(key);
        if (!(value instanceof Long)) {
            throw new IllegalArgumentException("Expected integer field: " + key);
        }
        return (Long) value;
    }

    static boolean requiredBoolean(Map<String, Object> object, String key) {
        Object value = required(object, key);
        if (!(value instanceof Boolean)) {
            throw new IllegalArgumentException("Expected boolean field: " + key);
        }
        return (Boolean) value;
    }

    static boolean optionalBoolean(Map<String, Object> object, String key, boolean defaultValue) {
        if (!object.containsKey(key)) {
            return defaultValue;
        }
        Object value = object.get(key);
        if (!(value instanceof Boolean)) {
            throw new IllegalArgumentException("Expected boolean field: " + key);
        }
        return (Boolean) value;
    }

    static List<Long> requiredLongList(Map<String, Object> object, String key) {
        Object value = required(object, key);
        if (!(value instanceof List)) {
            throw new IllegalArgumentException("Expected array field: " + key);
        }
        List<Long> result = new ArrayList<>();
        for (Object entry : (List<?>) value) {
            if (!(entry instanceof Long)) {
                throw new IllegalArgumentException("Expected integer entries in: " + key);
            }
            result.add((Long) entry);
        }
        return result;
    }

    private static Object required(Map<String, Object> object, String key) {
        if (!object.containsKey(key)) {
            throw new IllegalArgumentException("Missing JSON field: " + key);
        }
        return object.get(key);
    }

    private static final class Parser {
        private final String json;
        private int offset;

        Parser(String json) {
            this.json = Objects.requireNonNull(json, "json");
        }

        Object parseDocument() {
            skipWhitespace();
            Object result = parseValue();
            skipWhitespace();
            if (offset != json.length()) {
                throw error("Unexpected trailing content");
            }
            return result;
        }

        private Object parseValue() {
            if (offset >= json.length()) {
                throw error("Unexpected end of JSON");
            }
            char character = json.charAt(offset);
            if (character == '{') {
                return parseObjectValue();
            }
            if (character == '[') {
                return parseArrayValue();
            }
            if (character == '"') {
                return parseString();
            }
            if (character == '-' || (character >= '0' && character <= '9')) {
                return parseLong();
            }
            if (json.startsWith("true", offset)) {
                offset += 4;
                return Boolean.TRUE;
            }
            if (json.startsWith("false", offset)) {
                offset += 5;
                return Boolean.FALSE;
            }
            if (json.startsWith("null", offset)) {
                offset += 4;
                return null;
            }
            throw error("Unexpected value");
        }

        private Map<String, Object> parseObjectValue() {
            offset++;
            skipWhitespace();
            Map<String, Object> result = new LinkedHashMap<>();
            if (consume('}')) {
                return result;
            }
            while (true) {
                if (offset >= json.length() || json.charAt(offset) != '"') {
                    throw error("Expected an object key");
                }
                String key = parseString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                if (result.containsKey(key)) {
                    throw error("Duplicate object key: " + key);
                }
                result.put(key, parseValue());
                skipWhitespace();
                if (consume('}')) {
                    return result;
                }
                expect(',');
                skipWhitespace();
            }
        }

        private List<Object> parseArrayValue() {
            offset++;
            skipWhitespace();
            List<Object> result = new ArrayList<>();
            if (consume(']')) {
                return result;
            }
            while (true) {
                result.add(parseValue());
                skipWhitespace();
                if (consume(']')) {
                    return result;
                }
                expect(',');
                skipWhitespace();
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (offset < json.length()) {
                char character = json.charAt(offset++);
                if (character == '"') {
                    return result.toString();
                }
                if (character == '\\') {
                    if (offset >= json.length()) {
                        throw error("Unterminated escape sequence");
                    }
                    char escaped = json.charAt(offset++);
                    switch (escaped) {
                        case '"':
                        case '\\':
                        case '/':
                            result.append(escaped);
                            break;
                        case 'b':
                            result.append('\b');
                            break;
                        case 'f':
                            result.append('\f');
                            break;
                        case 'n':
                            result.append('\n');
                            break;
                        case 'r':
                            result.append('\r');
                            break;
                        case 't':
                            result.append('\t');
                            break;
                        case 'u':
                            result.append(parseUnicodeEscape());
                            break;
                        default:
                            throw error("Invalid escape sequence");
                    }
                } else {
                    if (character < 0x20) {
                        throw error("Unescaped control character");
                    }
                    result.append(character);
                }
            }
            throw error("Unterminated string");
        }

        private char parseUnicodeEscape() {
            if (offset + 4 > json.length()) {
                throw error("Incomplete unicode escape");
            }
            int value = 0;
            for (int i = 0; i < 4; i++) {
                int digit = Character.digit(json.charAt(offset++), 16);
                if (digit < 0) {
                    throw error("Invalid unicode escape");
                }
                value = (value << 4) | digit;
            }
            return (char) value;
        }

        private Long parseLong() {
            int start = offset;
            if (json.charAt(offset) == '-') {
                offset++;
            }
            int digitStart = offset;
            while (offset < json.length()) {
                char character = json.charAt(offset);
                if (character < '0' || character > '9') {
                    break;
                }
                offset++;
            }
            if (digitStart == offset) {
                throw error("Invalid integer");
            }
            if (offset - digitStart > 1 && json.charAt(digitStart) == '0') {
                throw error("Leading zeroes are not valid JSON numbers");
            }
            if (offset < json.length()) {
                char following = json.charAt(offset);
                if (following == '.' || following == 'e' || following == 'E') {
                    throw error("Only integer JSON numbers are supported");
                }
            }
            try {
                return Long.parseLong(json.substring(start, offset));
            } catch (NumberFormatException exception) {
                throw error("Integer is outside the supported range");
            }
        }

        private void skipWhitespace() {
            while (offset < json.length()) {
                char character = json.charAt(offset);
                if (character != ' ' && character != '\n' && character != '\r' && character != '\t') {
                    return;
                }
                offset++;
            }
        }

        private boolean consume(char expected) {
            if (offset < json.length() && json.charAt(offset) == expected) {
                offset++;
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            if (!consume(expected)) {
                throw error("Expected '" + expected + "'");
            }
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at character " + offset);
        }
    }
}
