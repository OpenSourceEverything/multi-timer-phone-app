package com.ose.multitimer;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.Objects;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Durable SQLite event ledger for timer diagnostics.
 *
 * <p>This store is intentionally separate from AppStore. It records evidence about the
 * timer engine without becoming a second source of truth for clock state. Critical
 * lifecycle writes use short SQLite transactions so a scheduled event and its
 * transition are durable together. Diagnostic failures are allowed to degrade
 * diagnostics; they must not stop the timer service.</p>
 */
final class DiagnosticStore implements AutoCloseable {
    private static final String DATABASE_NAME = "multi_timer_diagnostics.db";
    private static final int DATABASE_VERSION = 2;

    private static final String EVENTS_TABLE = "event_ledger";
    private static final String TRANSITIONS_TABLE = "event_transition";
    private static final String SNAPSHOTS_TABLE = "state_snapshot";

    private static final String EVENT_ID = "event_id";
    private static final String EVENT_KEY = "event_key";
    private static final String CLOCK_ID = "clock_id";
    private static final String PROFILE_ID = "profile_id";
    private static final String CUE_OFFSET_MILLIS = "cue_offset_millis";
    private static final String EXPECTED_WALL_MILLIS = "expected_wall_millis";
    private static final String EXPECTED_ELAPSED_MILLIS = "expected_elapsed_millis";
    private static final String STATE = "state";
    private static final String CREATED_WALL_MILLIS = "created_wall_millis";
    private static final String UPDATED_WALL_MILLIS = "updated_wall_millis";
    private static final String TRANSITION_ID = "transition_id";
    private static final String ACTUAL_WALL_MILLIS = "actual_wall_millis";
    private static final String ACTUAL_ELAPSED_MILLIS = "actual_elapsed_millis";
    private static final String DETAIL = "detail";
    private static final String SNAPSHOT_ID = "snapshot_id";
    private static final String SNAPSHOT_KIND = "snapshot_kind";
    private static final String SNAPSHOT_WALL_MILLIS = "snapshot_wall_millis";
    private static final String SNAPSHOT_ELAPSED_MILLIS = "snapshot_elapsed_millis";
    private static final String DETAILS_JSON = "details_json";

    private final Helper helper;

    DiagnosticStore(Context context) {
        helper = new Helper(Objects.requireNonNull(context, "context").getApplicationContext());
    }

    /**
     * Inserts an expected event once, or refreshes its expected timestamps while it remains
     * scheduled. The event key is supplied by the scheduler and must identify one clock run,
     * profile and cue offset.
     */
    synchronized String ensureScheduledEvent(
            String eventKey,
            String clockId,
            String profileId,
            long cueOffsetMillis,
            long expectedWallMillis,
            long expectedElapsedMillis,
            long recordedWallMillis,
            long recordedElapsedMillis) {
        return ensureScheduledEvent(eventKey, clockId, profileId, cueOffsetMillis,
                expectedWallMillis, expectedElapsedMillis, recordedWallMillis,
                recordedElapsedMillis, true, "Expected cue recorded");
    }

    /**
     * Completion events keep their original deadline when acknowledged after a late tick.
     * Ordinary periodic cues may refresh expected times after clock-anchor recovery.
     */
    synchronized String ensureScheduledEvent(
            String eventKey, String clockId, String profileId, long cueOffsetMillis,
            long expectedWallMillis, long expectedElapsedMillis, long recordedWallMillis,
            long recordedElapsedMillis, boolean refreshExpected, String scheduledDetail) {
        Objects.requireNonNull(eventKey, "eventKey");
        Objects.requireNonNull(clockId, "clockId");
        Objects.requireNonNull(profileId, "profileId");
        SQLiteDatabase database = helper.getWritableDatabase();
        database.beginTransaction();
        try {
            String eventId = findEventId(database, eventKey);
            if (eventId == null) {
                eventId = UUID.randomUUID().toString();
                ContentValues values = new ContentValues();
                values.put(EVENT_ID, eventId);
                values.put(EVENT_KEY, eventKey);
                values.put(CLOCK_ID, clockId);
                values.put(PROFILE_ID, profileId);
                values.put(CUE_OFFSET_MILLIS, cueOffsetMillis);
                values.put(EXPECTED_WALL_MILLIS, expectedWallMillis);
                values.put(EXPECTED_ELAPSED_MILLIS, expectedElapsedMillis);
                values.put(STATE, DiagnosticEvent.State.SCHEDULED.name());
                values.put(CREATED_WALL_MILLIS, recordedWallMillis);
                values.put(UPDATED_WALL_MILLIS, recordedWallMillis);
                database.insertOrThrow(EVENTS_TABLE, null, values);
                insertTransition(
                        database,
                        eventId,
                        DiagnosticEvent.State.SCHEDULED,
                        recordedWallMillis,
                        recordedElapsedMillis,
                        scheduledDetail);
            } else if (refreshExpected && isScheduled(database, eventId)) {
                ContentValues values = new ContentValues();
                values.put(EXPECTED_WALL_MILLIS, expectedWallMillis);
                values.put(EXPECTED_ELAPSED_MILLIS, expectedElapsedMillis);
                values.put(UPDATED_WALL_MILLIS, recordedWallMillis);
                database.update(EVENTS_TABLE, values, EVENT_ID + " = ?", new String[]{eventId});
            }
            database.setTransactionSuccessful();
            return eventId;
        } finally {
            database.endTransaction();
        }
    }

    /** Records a forward lifecycle transition and ignores duplicate observations. */
    synchronized boolean transition(
            String eventId,
            DiagnosticEvent.State nextState,
            long actualWallMillis,
            long actualElapsedMillis,
            String detail) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(nextState, "nextState");
        SQLiteDatabase database = helper.getWritableDatabase();
        database.beginTransaction();
        try {
            DiagnosticEvent.State currentState = findState(database, eventId);
            if (currentState == null || currentState == nextState) {
                database.setTransactionSuccessful();
                return false;
            }
            if (!DiagnosticEvent.canTransition(currentState, nextState)) {
                database.setTransactionSuccessful();
                return false;
            }
            ContentValues values = new ContentValues();
            values.put(STATE, nextState.name());
            values.put(UPDATED_WALL_MILLIS, actualWallMillis);
            database.update(EVENTS_TABLE, values, EVENT_ID + " = ?", new String[]{eventId});
            insertTransition(
                    database,
                    eventId,
                    nextState,
                    actualWallMillis,
                    actualElapsedMillis,
                    detail);
            database.setTransactionSuccessful();
            return true;
        } finally {
            database.endTransaction();
        }
    }

    /**
     * Persists one point-in-time Android state snapshot.
     *
     * <p>Snapshots are separate rows rather than columns on event_ledger because one event may
     * have several relevant observations: service startup, scheduling, audio failure and later
     * recovery. The JSON payload also allows new platform facts to be added without changing the
     * event lifecycle schema.</p>
     */
    synchronized void insertSnapshot(
            String kind,
            long wallMillis,
            long elapsedMillis,
            String detailsJson) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(detailsJson, "detailsJson");
        ContentValues values = new ContentValues();
        values.put(SNAPSHOT_ID, UUID.randomUUID().toString());
        values.put(SNAPSHOT_KIND, kind);
        values.put(SNAPSHOT_WALL_MILLIS, wallMillis);
        values.put(SNAPSHOT_ELAPSED_MILLIS, elapsedMillis);
        values.put(DETAILS_JSON, detailsJson);
        helper.getWritableDatabase().insertOrThrow(SNAPSHOTS_TABLE, null, values);
    }

    /** Bounded read model used by the export worker; it never changes clock or ledger state. */
    static final class ReportData {
        final List<DiagnosticReport.EventRow> events;
        final int totalEvents;
        final List<DiagnosticReport.SnapshotRow> snapshots;
        final int totalSnapshots;

        ReportData(List<DiagnosticReport.EventRow> events, int totalEvents,
                List<DiagnosticReport.SnapshotRow> snapshots, int totalSnapshots) {
            this.events = events;
            this.totalEvents = totalEvents;
            this.snapshots = snapshots;
            this.totalSnapshots = totalSnapshots;
        }
    }

    /**
     * Reads recent evidence plus older failures without loading the full retention window.
     * Separate queries avoid a long transaction against the service's timing-critical writer;
     * an export during a live cue may therefore show the latest completed transition on the
     * next export rather than in this one.
     */
    synchronized ReportData readReport() {
        SQLiteDatabase database = helper.getReadableDatabase();
        Map<String, DiagnosticReport.EventRow> selected = new LinkedHashMap<>();
        readEvents(database, STATE + " = ?", new String[]{DiagnosticEvent.State.FAILED.name()},
                100, selected);
        readEvents(database, null, null, 200, selected);
        List<DiagnosticReport.EventRow> events = new ArrayList<>(selected.values());
        events.sort(Comparator.comparingLong(
                (DiagnosticReport.EventRow event) -> event.expectedWallMillis).reversed());
        for (DiagnosticReport.EventRow event : events) {
            try (Cursor cursor = database.query(TRANSITIONS_TABLE,
                    new String[]{STATE, ACTUAL_WALL_MILLIS, ACTUAL_ELAPSED_MILLIS, DETAIL},
                    EVENT_ID + " = ?", new String[]{event.id}, null, null,
                    TRANSITION_ID + " ASC")) {
                while (cursor.moveToNext()) {
                    event.transitions.add(new DiagnosticReport.TransitionRow(
                            DiagnosticEvent.State.valueOf(cursor.getString(0)),
                            cursor.getLong(1), cursor.getLong(2), cursor.getString(3)));
                }
            }
        }
        List<DiagnosticReport.SnapshotRow> snapshots = new ArrayList<>();
        try (Cursor cursor = database.query(SNAPSHOTS_TABLE,
                new String[]{SNAPSHOT_ID, SNAPSHOT_KIND, SNAPSHOT_WALL_MILLIS,
                        SNAPSHOT_ELAPSED_MILLIS, DETAILS_JSON},
                null, null, null, null, SNAPSHOT_WALL_MILLIS + " DESC", "500")) {
            while (cursor.moveToNext()) {
                snapshots.add(new DiagnosticReport.SnapshotRow(
                        cursor.getString(0), cursor.getString(1), cursor.getLong(2),
                        cursor.getLong(3), cursor.getString(4)));
            }
        }
        return new ReportData(events, rowCount(database, EVENTS_TABLE),
                snapshots, rowCount(database, SNAPSHOTS_TABLE));
    }

    /** Adds one ordered event selection, deduplicating IDs found by both queries. */
    private static void readEvents(SQLiteDatabase database, String where, String[] args,
            int limit, Map<String, DiagnosticReport.EventRow> selected) {
        try (Cursor cursor = database.query(EVENTS_TABLE,
                new String[]{EVENT_ID, CLOCK_ID, PROFILE_ID, EXPECTED_WALL_MILLIS,
                        EXPECTED_ELAPSED_MILLIS, STATE},
                where, args, null, null, UPDATED_WALL_MILLIS + " DESC",
                String.valueOf(limit))) {
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                selected.putIfAbsent(id, new DiagnosticReport.EventRow(
                        id, cursor.getString(1), cursor.getString(2), cursor.getLong(3),
                        cursor.getLong(4), DiagnosticEvent.State.valueOf(cursor.getString(5))));
            }
        }
    }

    /** Counts retained rows so the report can reveal its selection cap honestly. */
    private static int rowCount(SQLiteDatabase database, String table) {
        try (Cursor cursor = database.rawQuery("SELECT COUNT(*) FROM " + table, null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    /** Removes old events and snapshots using separate evidence-retention windows. */
    synchronized int pruneBefore(long eventCutoffWallMillis, long snapshotCutoffWallMillis) {
        SQLiteDatabase database = helper.getWritableDatabase();
        database.beginTransaction();
        try {
            int deletedTransitions = database.delete(
                    TRANSITIONS_TABLE,
                    EVENT_ID + " IN (SELECT " + EVENT_ID + " FROM " + EVENTS_TABLE
                            + " WHERE " + UPDATED_WALL_MILLIS + " < ?)",
                    new String[]{String.valueOf(eventCutoffWallMillis)});
            database.delete(
                    EVENTS_TABLE,
                    UPDATED_WALL_MILLIS + " < ?",
                    new String[]{String.valueOf(eventCutoffWallMillis)});
            database.delete(
                    SNAPSHOTS_TABLE,
                    SNAPSHOT_WALL_MILLIS + " < ?",
                    new String[]{String.valueOf(snapshotCutoffWallMillis)});
            database.setTransactionSuccessful();
            return deletedTransitions;
        } finally {
            database.endTransaction();
        }
    }

    /** Marks scheduled events still past their expected wall time after startup reconciliation. */
    synchronized int failOverdueScheduledEvents(
            long nowWallMillis, long nowElapsedMillis, String detail) {
        SQLiteDatabase database = helper.getWritableDatabase();
        database.beginTransaction();
        int failed = 0;
        try (Cursor cursor = database.query(
                EVENTS_TABLE,
                new String[]{EVENT_ID},
                STATE + " = ? AND " + EXPECTED_WALL_MILLIS + " <= ?",
                new String[]{
                        DiagnosticEvent.State.SCHEDULED.name(),
                        String.valueOf(nowWallMillis)
                },
                null,
                null,
                EXPECTED_WALL_MILLIS + " ASC")) {
            while (cursor.moveToNext()) {
                String eventId = cursor.getString(0);
                ContentValues values = new ContentValues();
                values.put(STATE, DiagnosticEvent.State.FAILED.name());
                values.put(UPDATED_WALL_MILLIS, nowWallMillis);
                database.update(EVENTS_TABLE, values, EVENT_ID + " = ?", new String[]{eventId});
                insertTransition(
                        database,
                        eventId,
                        DiagnosticEvent.State.FAILED,
                        nowWallMillis,
                        nowElapsedMillis,
                        detail);
                failed++;
            }
            database.setTransactionSuccessful();
            return failed;
        } finally {
            database.endTransaction();
        }
    }

    /**
     * Withdraws only undispatched expectations from clock/profile runs no longer present.
     * A paused, reset or deleted clock is not evidence of a missing Android callback. The
     * caller must provide a successfully loaded app state; an app-state read failure must
     * never cause every expected cue to be cancelled.
     */
    synchronized int cancelScheduledOutsideRuns(
            Set<String> activeRunPrefixes, long nowWallMillis, long nowElapsedMillis) {
        Objects.requireNonNull(activeRunPrefixes, "activeRunPrefixes");
        SQLiteDatabase database = helper.getWritableDatabase();
        database.beginTransaction();
        try {
            List<String> obsoleteIds = new ArrayList<>();
            try (Cursor cursor = database.query(EVENTS_TABLE,
                    new String[]{EVENT_ID, EVENT_KEY}, STATE + " = ?",
                    new String[]{DiagnosticEvent.State.SCHEDULED.name()},
                    null, null, null)) {
                while (cursor.moveToNext()) {
                    String key = cursor.getString(1);
                    boolean active = false;
                    for (String prefix : activeRunPrefixes) {
                        if (key.startsWith(prefix)) {
                            active = true;
                            break;
                        }
                    }
                    if (!active) {
                        obsoleteIds.add(cursor.getString(0));
                    }
                }
            }
            for (String eventId : obsoleteIds) {
                ContentValues values = new ContentValues();
                values.put(STATE, DiagnosticEvent.State.CANCELLED.name());
                values.put(UPDATED_WALL_MILLIS, nowWallMillis);
                database.update(EVENTS_TABLE, values, EVENT_ID + " = ?",
                        new String[]{eventId});
                insertTransition(database, eventId, DiagnosticEvent.State.CANCELLED,
                        nowWallMillis, nowElapsedMillis,
                        "Clock paused, reset, deleted or profile detached");
            }
            database.setTransactionSuccessful();
            return obsoleteIds.size();
        } finally {
            database.endTransaction();
        }
    }

    @Override
    public synchronized void close() {
        helper.close();
    }

    private static String findEventId(SQLiteDatabase database, String eventKey) {
        try (Cursor cursor = database.query(
                EVENTS_TABLE,
                new String[]{EVENT_ID},
                EVENT_KEY + " = ?",
                new String[]{eventKey},
                null,
                null,
                null)) {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }

    private static boolean isScheduled(SQLiteDatabase database, String eventId) {
        return DiagnosticEvent.State.SCHEDULED == findState(database, eventId);
    }

    private static DiagnosticEvent.State findState(SQLiteDatabase database, String eventId) {
        try (Cursor cursor = database.query(
                EVENTS_TABLE,
                new String[]{STATE},
                EVENT_ID + " = ?",
                new String[]{eventId},
                null,
                null,
                null)) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            return DiagnosticEvent.State.valueOf(cursor.getString(0));
        }
    }

    private static void insertTransition(
            SQLiteDatabase database,
            String eventId,
            DiagnosticEvent.State state,
            long actualWallMillis,
            long actualElapsedMillis,
            String detail) {
        ContentValues values = new ContentValues();
        values.put(EVENT_ID, eventId);
        values.put(STATE, state.name());
        values.put(ACTUAL_WALL_MILLIS, actualWallMillis);
        values.put(ACTUAL_ELAPSED_MILLIS, actualElapsedMillis);
        values.put(DETAIL, detail == null ? "" : detail);
        database.insertOrThrow(TRANSITIONS_TABLE, null, values);
    }

    /** Owns the versioned diagnostic schema and its future migrations. */
    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase database) {
            database.execSQL(
                    "CREATE TABLE " + EVENTS_TABLE + " ("
                            + EVENT_ID + " TEXT PRIMARY KEY, "
                            + EVENT_KEY + " TEXT NOT NULL UNIQUE, "
                            + CLOCK_ID + " TEXT NOT NULL, "
                            + PROFILE_ID + " TEXT NOT NULL, "
                            + CUE_OFFSET_MILLIS + " INTEGER NOT NULL, "
                            + EXPECTED_WALL_MILLIS + " INTEGER NOT NULL, "
                            + EXPECTED_ELAPSED_MILLIS + " INTEGER NOT NULL, "
                            + STATE + " TEXT NOT NULL, "
                            + CREATED_WALL_MILLIS + " INTEGER NOT NULL, "
                            + UPDATED_WALL_MILLIS + " INTEGER NOT NULL"
                            + ")");
            database.execSQL(
                    "CREATE TABLE " + TRANSITIONS_TABLE + " ("
                            + TRANSITION_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                            + EVENT_ID + " TEXT NOT NULL, "
                            + STATE + " TEXT NOT NULL, "
                            + ACTUAL_WALL_MILLIS + " INTEGER NOT NULL, "
                            + ACTUAL_ELAPSED_MILLIS + " INTEGER NOT NULL, "
                            + DETAIL + " TEXT NOT NULL, "
                            + "FOREIGN KEY(" + EVENT_ID + ") REFERENCES "
                            + EVENTS_TABLE + "(" + EVENT_ID + ") ON DELETE CASCADE"
                            + ")");
            database.execSQL(
                    "CREATE INDEX event_ledger_state_expected_index ON " + EVENTS_TABLE
                            + " (" + STATE + ", " + EXPECTED_WALL_MILLIS + ")");
            database.execSQL(
                    "CREATE INDEX event_transition_event_index ON " + TRANSITIONS_TABLE
                            + " (" + EVENT_ID + ", " + TRANSITION_ID + ")");
            createSnapshotTable(database);
        }

        @Override
        public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
            if (oldVersion < 2) {
                createSnapshotTable(database);
            }
            // Future changes must preserve the ledger through explicit migrations rather than
            // silently dropping diagnostic evidence.
        }

        /** Creates the snapshot table for both new databases and version-one upgrades. */
        private static void createSnapshotTable(SQLiteDatabase database) {
            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS " + SNAPSHOTS_TABLE + " ("
                            + SNAPSHOT_ID + " TEXT PRIMARY KEY, "
                            + SNAPSHOT_KIND + " TEXT NOT NULL, "
                            + SNAPSHOT_WALL_MILLIS + " INTEGER NOT NULL, "
                            + SNAPSHOT_ELAPSED_MILLIS + " INTEGER NOT NULL, "
                            + DETAILS_JSON + " TEXT NOT NULL"
                            + ")");
            database.execSQL(
                    "CREATE INDEX IF NOT EXISTS snapshot_kind_time_index ON " + SNAPSHOTS_TABLE
                            + " (" + SNAPSHOT_KIND + ", " + SNAPSHOT_WALL_MILLIS + ")");
        }
    }
}
