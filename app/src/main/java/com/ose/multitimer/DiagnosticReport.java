package com.ose.multitimer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns persisted timer evidence into a bounded, explainable postmortem report.
 *
 * <p>This class has no Android dependency. Categories describe what the app observed, with a
 * confidence level; they never infer that Doze, battery policy or a user action caused a failure
 * merely because that state appears in a snapshot. Database access and sharing live elsewhere.</p>
 */
final class DiagnosticReport {
    /** A callback may legitimately arrive after its nominal deadline. */
    private static final long LATE_MILLIS = 2_000L;
    /** An unfinished row needs time to progress before it is called suspicious. */
    private static final long STALE_MILLIS = 30_000L;

    /** One selected event and every persisted transition belonging to it. */
    static final class EventRow {
        final String id;
        final String clockId;
        final String profileId;
        final long expectedWallMillis;
        final long expectedElapsedMillis;
        final DiagnosticEvent.State state;
        final List<TransitionRow> transitions = new ArrayList<>();

        EventRow(String id, String clockId, String profileId, long expectedWallMillis,
                long expectedElapsedMillis, DiagnosticEvent.State state) {
            this.id = id;
            this.clockId = clockId;
            this.profileId = profileId;
            this.expectedWallMillis = expectedWallMillis;
            this.expectedElapsedMillis = expectedElapsedMillis;
            this.state = state;
        }
    }

    /** One actual transition, in ledger order. */
    static final class TransitionRow {
        final DiagnosticEvent.State state;
        final long wallMillis;
        final long elapsedMillis;
        final String detail;

        TransitionRow(DiagnosticEvent.State state, long wallMillis, long elapsedMillis,
                String detail) {
            this.state = state;
            this.wallMillis = wallMillis;
            this.elapsedMillis = elapsedMillis;
            this.detail = detail;
        }
    }

    /** One Android snapshot stored independently of event transitions. */
    static final class SnapshotRow {
        final String id;
        final String kind;
        final long wallMillis;
        final long elapsedMillis;
        final String detailsJson;

        SnapshotRow(String id, String kind, long wallMillis, long elapsedMillis,
                String detailsJson) {
            this.id = id;
            this.kind = kind;
            this.wallMillis = wallMillis;
            this.elapsedMillis = elapsedMillis;
            this.detailsJson = detailsJson;
        }
    }

    /** The category and evidence strength shown to a reader. */
    static final class Diagnosis {
        final String category;
        final String confidence;
        final String explanation;

        Diagnosis(String category, String confidence, String explanation) {
            this.category = category;
            this.confidence = confidence;
            this.explanation = explanation;
        }
    }

    /** The two files placed in one user-shareable archive. */
    static final class Output {
        final String text;
        final String json;

        Output(String text, String json) {
            this.text = text;
            this.json = json;
        }
    }

    private DiagnosticReport() {
        // Static formatter and classifier.
    }

    /** Classifies one event using only its own persisted transitions and the current time. */
    static Diagnosis diagnose(EventRow event, long nowWallMillis) {
        return diagnose(event, nowWallMillis, List.of());
    }

    /** Adds prior-process evidence when a later startup snapshot covers a missing cue. */
    private static Diagnosis diagnose(
            EventRow event, long nowWallMillis, List<SnapshotRow> snapshots) {
        TransitionRow dispatch = first(event, DiagnosticEvent.State.DISPATCHED);
        TransitionRow last = event.transitions.isEmpty()
                ? null : event.transitions.get(event.transitions.size() - 1);
        String detail = last == null ? "" : last.detail;

        if (event.state == DiagnosticEvent.State.CANCELLED) {
            return new Diagnosis("CANCELLED", "high",
                    "The expected cue was withdrawn before dispatch: " + detail);
        }

        if (event.state == DiagnosticEvent.State.FAILED) {
            if (detail.contains("No enabled beep or speech action")) {
                return new Diagnosis("NO_AUDIO_ACTION", "high",
                        "The profile generated a cue but enabled no audible action.");
            }
            if (detail.contains("Startup found an overdue cue")) {
                if (processExitSpansExpectedCue(event, snapshots)) {
                    return new Diagnosis("PROCESS_TERMINATED", "low",
                            "Android reported a prior process exit before this cue, and the "
                                    + "service next recorded startup after it. The timer may also "
                                    + "have been paused or changed.");
                }
                return new Diagnosis("CALLBACK_NOT_RECEIVED", "low",
                        "Startup found a previously scheduled cue with no dispatch. "
                                + "A pause, edit or process gap may also explain it.");
            }
            if (first(event, DiagnosticEvent.State.EXECUTED) != null) {
                return new Diagnosis("AUDIO_FAILED", "high",
                        "The audio player reported failure: " + detail);
            }
            return new Diagnosis("EXECUTION_FAILED", "medium", detail);
        }
        if (dispatch != null) {
            long wallDelay = dispatch.wallMillis - event.expectedWallMillis;
            long expectedBootWall = event.expectedWallMillis - event.expectedElapsedMillis;
            long observedBootWall = dispatch.wallMillis - dispatch.elapsedMillis;
            // The boot-wall estimate must agree before two monotonic values can be compared.
            boolean sameBootAndClock = event.expectedElapsedMillis > 0L
                    && Math.abs(expectedBootWall - observedBootWall) < 5_000L;
            long delay = sameBootAndClock
                    ? dispatch.elapsedMillis - event.expectedElapsedMillis : wallDelay;
            if (delay > LATE_MILLIS) {
                return new Diagnosis("CALLBACK_LATE", sameBootAndClock ? "high" : "low",
                        "The service dispatched this cue " + delay + " ms after expected "
                                + (sameBootAndClock ? "monotonic" : "wall-clock") + " time.");
            }
        }
        if (event.state == DiagnosticEvent.State.COMPLETED) {
            return new Diagnosis("COMPLETED", "high",
                    "The player reported completion"
                            + (first(event, DiagnosticEvent.State.AUDIO_STARTED) == null
                            ? "; no start callback was recorded." : " after a start callback."));
        }
        if (event.state == DiagnosticEvent.State.SCHEDULED
                && nowWallMillis - event.expectedWallMillis > STALE_MILLIS) {
            if (processExitSpansExpectedCue(event, snapshots)) {
                return new Diagnosis("PROCESS_TERMINATED", "low",
                        "Android reported a prior process exit before this cue; "
                                + "the missing callback is not proof of causation.");
            }
            return new Diagnosis("CALLBACK_NOT_RECEIVED", "low",
                    "The expected time passed without a dispatch in this ledger. "
                            + "The timer may have been paused or changed.");
        }
        if ((event.state == DiagnosticEvent.State.EXECUTED
                || event.state == DiagnosticEvent.State.AUDIO_STARTED)
                && last != null && nowWallMillis - last.wallMillis > STALE_MILLIS) {
            return new Diagnosis("AUDIO_CALLBACK_MISSING", "low",
                    "No later player callback was recorded for this cue.");
        }
        if (event.state == DiagnosticEvent.State.DISPATCHED
                && last != null && nowWallMillis - last.wallMillis > STALE_MILLIS) {
            return new Diagnosis("EXECUTION_FAILED", "low",
                    "No later execution transition was recorded.");
        }
        return new Diagnosis("PENDING", "low",
                "The event has not reached a terminal state; it may still be active.");
    }

    /** Builds concise TXT and machine-readable JSON from a bounded database selection. */
    static Output render(List<EventRow> events, int totalEvents,
            List<SnapshotRow> snapshots, int totalSnapshots, long nowWallMillis,
            Map<String, String> clockNames, Map<String, String> profileNames) {
        StringBuilder text = new StringBuilder("MultiTimer diagnostics\n")
                .append("Generated: ").append(time(nowWallMillis)).append("\n")
                .append("Events included: ").append(events.size()).append(" of ")
                .append(totalEvents).append("; snapshots: ").append(snapshots.size())
                .append(" of ").append(totalSnapshots).append("\n")
                .append("Classification describes app evidence, not a proven Android cause.\n\n");
        if (hasUnknownWakeLockCause(snapshots)) {
            text.append("Wake-lock loss observed; cause UNKNOWN: no matching app release "
                    + "was recorded.\n\n");
        }
        if (hasUnknownSchedulerGapCause(snapshots)) {
            text.append("Scheduler gap observed; cause UNKNOWN: app-side state cannot show "
                    + "whether the device honored its wake-lock request.\n\n");
        }
        StringBuilder json = new StringBuilder("{\"schemaVersion\":1,\"generatedAtUtc\":")
                .append(JsonSupport.quote(time(nowWallMillis)))
                .append(",\"totalEvents\":").append(totalEvents)
                .append(",\"totalSnapshots\":").append(totalSnapshots)
                .append(",\"events\":[");
        int issues = 0;
        for (EventRow event : events) {
            Diagnosis diagnosis = diagnose(event, nowWallMillis, snapshots);
            if (!diagnosis.category.equals("COMPLETED")
                    && !diagnosis.category.equals("PENDING")
                    && !diagnosis.category.equals("CANCELLED")) {
                issues++;
                text.append("- ").append(time(event.expectedWallMillis)).append("  ")
                        .append(diagnosis.category).append(" [").append(diagnosis.confidence)
                        .append("]  ").append(name(clockNames, event.clockId)).append(" / ")
                        .append(name(profileNames, event.profileId)).append("\n  ")
                        .append(diagnosis.explanation).append("\n");
            }
        }
        if (issues == 0) {
            text.append("No classified problems in the selected events.\n");
        }
        text.append("\nEvent evidence (newest first)\n");
        for (int index = 0; index < events.size(); index++) {
            EventRow event = events.get(index);
            Diagnosis diagnosis = diagnose(event, nowWallMillis, snapshots);
            if (index > 0) {
                json.append(',');
            }
            json.append("{\"id\":").append(JsonSupport.quote(event.id))
                    .append(",\"clockId\":").append(JsonSupport.quote(event.clockId))
                    .append(",\"clockName\":").append(JsonSupport.quote(name(clockNames, event.clockId)))
                    .append(",\"profileId\":").append(JsonSupport.quote(event.profileId))
                    .append(",\"profileName\":").append(JsonSupport.quote(name(profileNames, event.profileId)))
                    .append(",\"expectedWallMillis\":").append(event.expectedWallMillis)
                    .append(",\"expectedElapsedMillis\":").append(event.expectedElapsedMillis)
                    .append(",\"state\":").append(JsonSupport.quote(event.state.name()))
                    .append(",\"diagnosis\":{\"category\":")
                    .append(JsonSupport.quote(diagnosis.category))
                    .append(",\"confidence\":").append(JsonSupport.quote(diagnosis.confidence))
                    .append(",\"explanation\":").append(JsonSupport.quote(diagnosis.explanation))
                    .append("},\"transitions\":[");
            text.append(time(event.expectedWallMillis)).append("  ")
                    .append(name(clockNames, event.clockId)).append(" / ")
                    .append(name(profileNames, event.profileId)).append("  ")
                    .append(diagnosis.category).append("\n");
            for (int step = 0; step < event.transitions.size(); step++) {
                TransitionRow transition = event.transitions.get(step);
                if (step > 0) {
                    json.append(',');
                }
                json.append("{\"state\":").append(JsonSupport.quote(transition.state.name()))
                        .append(",\"wallMillis\":").append(transition.wallMillis)
                        .append(",\"elapsedMillis\":").append(transition.elapsedMillis)
                        .append(",\"detail\":").append(JsonSupport.quote(transition.detail))
                        .append('}');
                text.append("  ").append(time(transition.wallMillis)).append("  ")
                        .append(transition.state).append("  ").append(transition.detail).append("\n");
            }
            json.append("],\"nearbySnapshotIds\":[");
            int nearby = 0;
            for (SnapshotRow snapshot : snapshots) {
                if (Math.abs(snapshot.wallMillis - event.expectedWallMillis) <= 120_000L
                        && nearby < 3) {
                    if (nearby++ > 0) {
                        json.append(',');
                    }
                    json.append(JsonSupport.quote(snapshot.id));
                    text.append("  State: ").append(snapshot.kind).append(" at ")
                            .append(time(snapshot.wallMillis)).append(" (JSON: ")
                            .append(snapshot.id).append(")\n");
                }
            }
            json.append("]}");
        }
        json.append("],\"snapshots\":[");
        text.append("\nAndroid state snapshots (newest first)\n");
        for (int index = 0; index < snapshots.size(); index++) {
            SnapshotRow snapshot = snapshots.get(index);
            if (index > 0) {
                json.append(',');
            }
            json.append("{\"id\":").append(JsonSupport.quote(snapshot.id))
                    .append(",\"kind\":").append(JsonSupport.quote(snapshot.kind))
                    .append(",\"wallMillis\":").append(snapshot.wallMillis)
                    .append(",\"elapsedMillis\":").append(snapshot.elapsedMillis)
                    .append(",\"details\":").append(validJsonOrString(snapshot.detailsJson))
                    .append('}');
            text.append(time(snapshot.wallMillis)).append("  ").append(snapshot.kind)
                    .append("  ID ").append(snapshot.id).append("\n  ")
                    .append(snapshot.detailsJson).append("\n");
        }
        return new Output(text.toString(), json.append("]}").toString());
    }

    /** Highlights the explicit unknown marker without attributing a missing lock to Android. */
    private static boolean hasUnknownWakeLockCause(List<SnapshotRow> snapshots) {
        for (SnapshotRow snapshot : snapshots) {
            if (!"wake_lock_missing_unknown".equals(snapshot.kind)) {
                continue;
            }
            try {
                Map<String, Object> payload = JsonSupport.parseObject(snapshot.detailsJson);
                if ("UNKNOWN".equals(payload.get("causeClassification"))) {
                    return true;
                }
            } catch (RuntimeException invalidSnapshot) {
                // A malformed row cannot supply a trustworthy cause classification.
            }
        }
        return false;
    }

    /** Reports scheduler gaps honestly when Android or vendor policy is not app-visible. */
    private static boolean hasUnknownSchedulerGapCause(List<SnapshotRow> snapshots) {
        for (SnapshotRow snapshot : snapshots) {
            if (!"scheduler_gap".equals(snapshot.kind)) {
                continue;
            }
            try {
                Map<String, Object> payload = JsonSupport.parseObject(snapshot.detailsJson);
                if ("UNKNOWN".equals(payload.get("causeClassification"))) {
                    return true;
                }
            } catch (RuntimeException invalidSnapshot) {
                // A malformed row cannot supply a trustworthy cause classification.
            }
        }
        return false;
    }

    /** Finds the first observation of one state, used to measure dispatch delay. */
    private static TransitionRow first(EventRow event, DiagnosticEvent.State state) {
        for (TransitionRow transition : event.transitions) {
            if (transition.state == state) {
                return transition;
            }
        }
        return null;
    }

    /**
     * Looks for an OS-reported exit between scheduling and the expected cue, followed by a
     * service startup after the cue. This supports a low-confidence correlation only.
     */
    private static boolean processExitSpansExpectedCue(
            EventRow event, List<SnapshotRow> snapshots) {
        TransitionRow scheduled = first(event, DiagnosticEvent.State.SCHEDULED);
        if (scheduled == null) {
            return false;
        }
        for (SnapshotRow snapshot : snapshots) {
            if (!"process_exit_history".equals(snapshot.kind)
                    || snapshot.wallMillis <= event.expectedWallMillis) {
                continue;
            }
            try {
                Map<String, Object> payload = JsonSupport.parseObject(snapshot.detailsJson);
                Object entries = payload.get("entries");
                if (!Boolean.TRUE.equals(payload.get("available"))
                        || !(entries instanceof List)) {
                    continue;
                }
                for (Object entry : (List<?>) entries) {
                    if (!(entry instanceof Map)) {
                        continue;
                    }
                    Object timestamp = ((Map<?, ?>) entry).get("timestampMillis");
                    if (timestamp instanceof Number) {
                        long exitWallMillis = ((Number) timestamp).longValue();
                        if (exitWallMillis >= scheduled.wallMillis
                                && exitWallMillis <= event.expectedWallMillis) {
                            return true;
                        }
                    }
                }
            } catch (RuntimeException invalidSnapshot) {
                // A malformed old row must not prevent diagnosis of the remaining events.
            }
        }
        return false;
    }

    /** Uses a saved human name where possible and preserves the ID when that object was removed. */
    private static String name(Map<String, String> names, String id) {
        return names.getOrDefault(id, id);
    }

    /** Formats UTC so reports are unambiguous across time zones and clock changes. */
    private static String time(long wallMillis) {
        return Instant.ofEpochMilli(wallMillis).toString();
    }

    /** Keeps the structured file valid even if an old snapshot payload is malformed. */
    private static String validJsonOrString(String value) {
        try {
            JsonSupport.parseObject(value);
            return value;
        } catch (RuntimeException error) {
            return JsonSupport.quote(value);
        }
    }
}
