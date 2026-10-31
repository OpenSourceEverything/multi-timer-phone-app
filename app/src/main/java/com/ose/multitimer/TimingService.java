package com.ose.multitimer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Owns timing and audible profile cues while one or more clocks are running. */
public final class TimingService extends Service {
    public static final String ACTION_SYNC = "com.ose.multitimer.action.SYNC";
    public static final String ACTION_STATE_CHANGED =
            "com.ose.multitimer.action.STATE_CHANGED";
    public static final String EXTRA_RUNNING_COUNT = "running_count";

    private static final String TAG = "TimingService";
    /** Shared by service instances in one process; it changes when Android starts a new process. */
    private static final String PROCESS_SESSION_ID = UUID.randomUUID().toString();
    private static final String NOTIFICATION_CHANNEL_ID = "running_timers";
    private static final int NOTIFICATION_ID = 1001;
    private static final long TICK_INTERVAL_MILLIS = 200L;
    private static final long CHECKPOINT_PERSIST_INTERVAL_MILLIS = 1_000L;
    /** Keeps the next expected cue durable without expanding an infinite schedule. */
    private static final long DIAGNOSTIC_LOOKAHEAD_MILLIS = 5L * 60L * 1_000L;
    private static final long DIAGNOSTIC_RETENTION_MILLIS = 30L * 24L * 60L * 60L * 1_000L;
    private static final long SNAPSHOT_RETENTION_MILLIS = 7L * 24L * 60L * 60L * 1_000L;
    private static final long PRUNE_INTERVAL_MILLIS = 24L * 60L * 60L * 1_000L;
    /** Prevents repeated service ticks from producing an unbounded snapshot stream. */
    private static final long SNAPSHOT_MIN_INTERVAL_MILLIS = 5_000L;
    /** Scheduling state changes slowly; frequent copies would waste significant storage. */
    private static final long SCHEDULE_SNAPSHOT_INTERVAL_MILLIS = 5L * 60L * 1_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tickRunnable = this::tick;
    private final Runnable idleStopRunnable = this::stopIfStillIdle;

    private AppStore store;
    private DiagnosticStore diagnosticStore;
    private DiagnosticSnapshotWriter snapshotWriter;
    private DiagnosticAudioTracker diagnosticAudioTracker;
    private AudioTimelinePlayer audioTimelinePlayer;
    private NotificationManager notificationManager;
    private PowerManager powerManager;
    private PowerManager.WakeLock wakeLock;
    /** Distinguishes a service restart from a new process in persisted runtime traces. */
    private final String serviceInstanceId = UUID.randomUUID().toString();
    private List<ClockInstance> clocks = new ArrayList<>();
    private List<BeepProfile> profiles = new ArrayList<>();
    private boolean foreground;
    /** Current Android foreground-service type mask, or -1 before the first promotion. */
    private int foregroundServiceType = -1;
    private boolean stateDirty;
    private boolean destroyed;
    private int lastStartId;
    private int notifiedRunningCount = -1;
    private long lastCheckpointPersistWallTimeMillis;
    /** Monotonic service-tick history used to spot large gaps while the process is alive. */
    private long lastTickElapsedRealtimeMillis;
    /** Uptime excludes deep sleep, letting reports compare CPU-awake time with elapsed time. */
    private long lastTickUptimeMillis;
    private long lastHeartbeatElapsedRealtimeMillis;
    private long largestTickGapSinceHeartbeatMillis;
    private long largestUptimeGapSinceHeartbeatMillis;
    private long lastDiagnosticPruneElapsedRealtimeMillis;
    /** Last observed lock state lets one gap record detect a lost lock without spamming rows. */
    private Boolean lastWakeLockHeldObservation;
    /** Last snapshot wall time per reason, used for bounded diagnostic volume. */
    private final Map<String, Long> lastSnapshotWallMillis = new LinkedHashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();
        store = new AppStore(getSharedPreferences(AppStore.PREFERENCES_NAME, MODE_PRIVATE));
        try {
            diagnosticStore = new DiagnosticStore(this);
            pruneDiagnosticHistory(System.currentTimeMillis(), SystemClock.elapsedRealtime());
            snapshotWriter = new DiagnosticSnapshotWriter(this);
        } catch (RuntimeException error) {
            // Diagnostics are valuable evidence, but a storage problem must not silence timers.
            Log.e(TAG, "Unable to open diagnostic ledger", error);
            diagnosticStore = null;
        }
        diagnosticAudioTracker = new DiagnosticAudioTracker(
                (eventId, state, wallMillis, elapsedMillis, detail) ->
                        recordAudioDiagnosticTransition(
                                eventId, state, wallMillis, elapsedMillis, detail));
        captureDiagnosticSnapshot(
                "service_created", false, System.currentTimeMillis(), SystemClock.elapsedRealtime());
        captureDiagnosticSnapshot(
                "process_exit_history", false, System.currentTimeMillis(),
                SystemClock.elapsedRealtime());
        audioTimelinePlayer = new AudioTimelinePlayer(this,
                new AudioTimelinePlayer.Observer() {
                    @Override
                    public void onStreamStarted(int audioSessionId) {
                        Boolean held = readWakeLockHeld();
                        recordRuntimeSnapshot("audio_track_started", "AudioTimelinePlayer",
                                "sessionId=" + audioSessionId + "; sampleRate="
                                        + AudioTimelineMixer.SAMPLE_RATE,
                                held, held);
                    }

                    @Override
                    public void onUnderrun(int underrunCount, long playbackFrame) {
                        Boolean held = readWakeLockHeld();
                        recordRuntimeSnapshot("audio_track_underrun", "AudioTimelinePlayer",
                                "underrunCount=" + underrunCount
                                        + "; playbackHeadFrame=" + playbackFrame,
                                held, held);
                    }

                    @Override
                    public void onStreamFailed(String detail) {
                        Boolean held = readWakeLockHeld();
                        recordRuntimeSnapshot("audio_track_failed", "AudioTimelinePlayer",
                                "FAILED: " + detail, held, held);
                    }
                });
        notificationManager = getSystemService(NotificationManager.class);
        createNotificationChannel();

        powerManager = getSystemService(PowerManager.class);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    getPackageName() + ":timing"
            );
            wakeLock.setReferenceCounted(false);
        }
        lastWakeLockHeldObservation = readWakeLockHeld();
        recordRuntimeSnapshot("service_created", "Service.onCreate completed",
                "observed", lastWakeLockHeldObservation, lastWakeLockHeldObservation);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        String action = intent == null ? null : intent.getAction();
        Boolean wakeLockAtStart = readWakeLockHeld();
        recordRuntimeSnapshot("service_start_requested", "Service.onStartCommand",
                "startId=" + startId + ", flags=" + flags + ", action="
                        + (action == null ? "<null>" : action),
                wakeLockAtStart, wakeLockAtStart);
        handler.removeCallbacks(tickRunnable);
        handler.removeCallbacks(idleStopRunnable);

        if (action != null && !ACTION_SYNC.equals(action)) {
            Log.w(TAG, "Ignoring unsupported action while synchronizing state: " + action);
        }
        boolean stateLoaded = reloadState();
        if (stateLoaded) {
            cancelInactiveDiagnosticRuns();
        }
        tick();
        reconcileStartupDiagnostics();
        captureDiagnosticSnapshot(
                "service_started", foreground, System.currentTimeMillis(),
                SystemClock.elapsedRealtime());
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** Returns false if either persisted collection failed to load, protecting ledger evidence. */
    private boolean reloadState() {
        boolean loaded = true;
        long nowWallTimeMillis = System.currentTimeMillis();
        long nowElapsedRealtimeMillis = SystemClock.elapsedRealtime();
        try {
            List<ClockInstance> loadedClocks = store.loadClockInstances();
            clocks = loadedClocks == null ? new ArrayList<>() : new ArrayList<>(loadedClocks);
            for (ClockInstance clock : clocks) {
                if (clock != null
                        && clock.rebaseMonotonicAnchor(
                        nowWallTimeMillis, nowElapsedRealtimeMillis)) {
                    stateDirty = true;
                }
            }
        } catch (RuntimeException error) {
            Log.e(TAG, "Unable to load clocks; retaining the current in-memory state", error);
            loaded = false;
        }

        try {
            List<BeepProfile> loadedProfiles = store.loadProfiles();
            profiles = loadedProfiles == null
                    ? new ArrayList<>()
                    : new ArrayList<>(loadedProfiles);
        } catch (RuntimeException error) {
            Log.e(TAG, "Unable to load beep profiles; retaining the current profiles", error);
            loaded = false;
        }
        return loaded;
    }

    /** Cancels stale expectations only after both clocks and profiles loaded successfully. */
    private void cancelInactiveDiagnosticRuns() {
        if (diagnosticStore == null) {
            return;
        }
        Set<String> activePrefixes = new LinkedHashSet<>();
        for (ClockInstance clock : clocks) {
            if (clock == null || !clock.running) {
                continue;
            }
            for (String profileId : clock.attachedProfileIds()) {
                if (findProfile(profileId) != null) {
                    activePrefixes.add(diagnosticRunPrefix(clock, profileId));
                }
            }
        }
        try {
            diagnosticStore.cancelScheduledOutsideRuns(activePrefixes,
                    System.currentTimeMillis(), SystemClock.elapsedRealtime());
        } catch (RuntimeException error) {
            Log.e(TAG, "Unable to cancel inactive diagnostic expectations", error);
        }
    }

    /** Stable through an anchor rebase, but changes on resume/reset. */
    private String diagnosticRunPrefix(ClockInstance clock, String profileId) {
        return safeClockId(clock) + "|" + profileId
                + "|" + clock.activeSegmentId + "|";
    }

    private void tick() {
        if (destroyed) {
            return;
        }
        handler.removeCallbacks(tickRunnable);
        handler.removeCallbacks(idleStopRunnable);

        int runningBeforeTick = countRunningClocks();
        boolean pendingCompletion = hasPendingCompletion();
        if (runningBeforeTick == 0 && !pendingCompletion) {
            lastTickElapsedRealtimeMillis = 0L;
            lastTickUptimeMillis = 0L;
            persistDirtyState();
            waitForAlertOrStop();
            return;
        }

        // Capture the lock and power state before any recovery action reacquires the lock.
        // If the service waited until after acquireWakeLock(), a missing lock at the moment
        // the delayed tick arrived would be hidden by the successful reacquisition.
        Boolean wakeLockHeldAtTickEntry = readWakeLockHeld();
        Boolean deviceIdleAtTickEntry = readDeviceIdleMode();
        if (Boolean.TRUE.equals(lastWakeLockHeldObservation)
                && Boolean.FALSE.equals(wakeLockHeldAtTickEntry)) {
            recordRuntimeSnapshot("wake_lock_missing_unknown",
                    "Tick found the previously held lock absent before reacquisition",
                    "UNKNOWN: no matching release was recorded by this service instance",
                    true, false);
        }
        lastWakeLockHeldObservation = wakeLockHeldAtTickEntry;

        boolean audioTimelineNeeded = hasRunningAudioProfile()
                || audioTimelinePlayer.isStarted();
        if (!ensureForeground(runningBeforeTick, audioTimelineNeeded)) {
            shutdownService();
            return;
        }
        if (audioTimelineNeeded) {
            audioTimelinePlayer.start();
        }
        acquireWakeLock("active timer tick");

        long nowWallTimeMillis = System.currentTimeMillis();
        long nowElapsedRealtimeMillis = SystemClock.elapsedRealtime();
        long nowUptimeMillis = SystemClock.uptimeMillis();
        if (nowElapsedRealtimeMillis - lastDiagnosticPruneElapsedRealtimeMillis
                >= PRUNE_INTERVAL_MILLIS) {
            pruneDiagnosticHistory(nowWallTimeMillis, nowElapsedRealtimeMillis);
        }
        recordTickHealth(nowWallTimeMillis, nowElapsedRealtimeMillis, nowUptimeMillis,
                wakeLockHeldAtTickEntry, deviceIdleAtTickEntry);
        AudioDispatch audioDispatch = new AudioDispatch();
        boolean visibleStateChanged = false;
        boolean checkpointAdvanced = false;

        for (ClockInstance clock : clocks) {
            if (clock == null) {
                continue;
            }

            if (!clock.running) {
                if (isPendingCompletion(clock)) {
                    acknowledgeCompletion(
                            clock,
                            nowWallTimeMillis,
                            nowElapsedRealtimeMillis,
                            audioDispatch);
                    visibleStateChanged = true;
                    stateDirty = true;
                }
                continue;
            }

            boolean justCompleted;
            try {
                justCompleted = clock.settleCompletion(
                        nowWallTimeMillis, nowElapsedRealtimeMillis);
            } catch (RuntimeException error) {
                Log.e(TAG, "Unable to settle clock " + safeClockId(clock), error);
                continue;
            }

            if (justCompleted) {
                visibleStateChanged = true;
                stateDirty = true;
                acknowledgeCompletion(clock, nowWallTimeMillis,
                        nowElapsedRealtimeMillis, audioDispatch);
                continue;
            }

            if (!clock.running || clock.completed) {
                continue;
            }

            long cueElapsedMillis;
            try {
                cueElapsedMillis = clock.cueElapsedAt(
                        nowWallTimeMillis, nowElapsedRealtimeMillis);
            } catch (RuntimeException error) {
                Log.e(TAG, "Unable to read cues for clock " + safeClockId(clock), error);
                continue;
            }

            boolean anyProfileCue = false;
            for (String profileId : clock.attachedProfileIds()) {
                BeepProfile profile = findProfile(profileId);
                if (profile == null) {
                    continue;
                }
                scheduleCompletionDiagnosticEvent(clock, profile,
                        nowWallTimeMillis, nowElapsedRealtimeMillis);
                scheduleNextDiagnosticEvent(clock, profile, cueElapsedMillis,
                        nowWallTimeMillis, nowElapsedRealtimeMillis);
                long previousCueElapsedMillis = clock.cueElapsedForProfile(profileId);
                if (cueElapsedMillis < previousCueElapsedMillis) {
                    clock.setCueElapsedForProfile(profileId, cueElapsedMillis);
                    stateDirty = true;
                    continue;
                }

                List<CueEvent> dueEvents = new ArrayList<>();
                try {
                    dueEvents = profile.eventsBetween(
                            clock.initialCuePending ? -1L : previousCueElapsedMillis,
                            cueElapsedMillis);
                } catch (RuntimeException error) {
                    Log.e(TAG, "Unable to evaluate cues for clock " + safeClockId(clock), error);
                }
                if (!dueEvents.isEmpty()) {
                    CueEvent lastEvent = dueEvents.get(dueEvents.size() - 1);
                    clock.setCueElapsedForProfile(profileId, lastEvent.offsetMillis);
                    checkpointAdvanced = true;
                    anyProfileCue = true;
                    Log.i(TAG, "cue due clock=" + safeClockId(clock)
                            + " profile=" + profile.id
                            + " previousElapsedMs=" + previousCueElapsedMillis
                            + " cueElapsedMs=" + cueElapsedMillis
                            + " cueCount=" + dueEvents.size()
                            + " lastCueAtElapsedMs=" + lastEvent.offsetMillis);
                    for (CueEvent dueEvent : dueEvents) {
                        String diagnosticEventId = ensureDiagnosticEvent(
                                clock,
                                profile,
                                dueEvent,
                                nowWallTimeMillis,
                                nowElapsedRealtimeMillis);
                        recordDiagnosticTransition(
                                diagnosticEventId,
                                DiagnosticEvent.State.DISPATCHED,
                                nowWallTimeMillis,
                                nowElapsedRealtimeMillis,
                                "TimingService observed the cue");
                        if (profile.beepOnCue && dueEvent.hasBeep()) {
                            audioDispatch.addBeep(
                                    diagnosticEventId, dueEvent.beepDurationMillis);
                        }
                        if (profile.speakOnCue) {
                            addEventSpeech(profile, clock, dueEvent, audioDispatch,
                                    diagnosticEventId);
                        }
                        if (profile.speakOnCue && profile.speakCurrentTime) {
                            audioDispatch.addSpeech(
                                    diagnosticEventId, currentTimeSpeech(nowWallTimeMillis));
                        }
                        addDynamicTimeSpeech(
                                profile, clock, nowWallTimeMillis, nowElapsedRealtimeMillis,
                                audioDispatch, diagnosticEventId);
                        recordDiagnosticTransition(
                                diagnosticEventId,
                                DiagnosticEvent.State.EXECUTED,
                                nowWallTimeMillis,
                                nowElapsedRealtimeMillis,
                                "Cue actions queued by TimingService");
                        recordSilentCueIfNeeded(audioDispatch, diagnosticEventId,
                                nowWallTimeMillis, nowElapsedRealtimeMillis);
                    }
                    visibleStateChanged = true;
                    stateDirty = true;
                } else if (cueElapsedMillis != previousCueElapsedMillis) {
                    clock.setCueElapsedForProfile(profileId, cueElapsedMillis);
                    checkpointAdvanced = true;
                }
            }
            if (clock.initialCuePending) {
                clock.initialCuePending = false;
                stateDirty = true;
            }
            if (anyProfileCue || cueElapsedMillis != clock.lastCueElapsedMillis) {
                clock.lastCueElapsedMillis = cueElapsedMillis;
            }
        }

        if (checkpointAdvanced && checkpointPersistIsDue(nowWallTimeMillis)) {
            stateDirty = true;
        }
        persistDirtyState();

        int runningAfterTick = countRunningClocks();
        if (visibleStateChanged) {
            broadcastStateChanged(runningAfterTick);
        }

        dispatchAudio(audioDispatch, runningAfterTick);

        if (runningAfterTick == 0) {
            updateForegroundNotification(0);
            waitForAlertOrStop();
            return;
        }

        updateForegroundNotification(runningAfterTick);
        handler.postDelayed(tickRunnable, TICK_INTERVAL_MILLIS);
    }

    /**
     * Consumes the durable completion marker, merging either the configured finish alert or a
     * regular cue exactly at the final boundary. This also covers a user pressing Pause in the
     * short interval after zero but before the service's next tick.
     */
    private void acknowledgeCompletion(
            ClockInstance clock,
            long nowWallTimeMillis,
            long nowElapsedRealtimeMillis,
            AudioDispatch audioDispatch) {
        if (clock.completionBeepPlayed) {
            return;
        }

        long finalCueElapsedMillis;
        try {
            finalCueElapsedMillis = clock.cueElapsedAt(
                    nowWallTimeMillis, nowElapsedRealtimeMillis);
            clock.lastCueElapsedMillis = finalCueElapsedMillis;
        } catch (RuntimeException error) {
            Log.e(
                    TAG,
                    "Unable to read the final cue position for clock " + safeClockId(clock),
                    error
            );
            // Mark the completion handled so corrupt state cannot keep the service alive forever.
            clock.completionBeepPlayed = true;
            return;
        }

        for (String profileId : clock.attachedProfileIds()) {
            BeepProfile profile = findProfile(profileId);
            if (profile == null) {
                continue;
            }
            long previousCueElapsedMillis = clock.cueElapsedForProfile(profileId);
            List<CueEvent> finalEvents = new ArrayList<>();
            try {
                if (finalCueElapsedMillis >= previousCueElapsedMillis) {
                    finalEvents = profile.eventsBetween(
                            previousCueElapsedMillis, finalCueElapsedMillis);
                }
            } catch (RuntimeException error) {
                Log.e(
                        TAG,
                        "Unable to evaluate the final cue for clock " + safeClockId(clock),
                        error
                );
            }
            String completionEventId = null;
            if (hasCompletionAudio(profile)) {
                completionEventId = ensureCompletionDiagnosticEvent(clock, profile,
                        nowWallTimeMillis, nowElapsedRealtimeMillis);
                recordDiagnosticTransition(completionEventId, DiagnosticEvent.State.DISPATCHED,
                        nowWallTimeMillis, nowElapsedRealtimeMillis,
                        "TimingService observed timer completion");
            }
            if (profile.beepOnCompletion) {
                audioDispatch.addBeep(completionEventId, profile.beepDurationMillis);
            }
            for (CueEvent finalEvent : finalEvents) {
                String diagnosticEventId = ensureDiagnosticEvent(
                        clock,
                        profile,
                        finalEvent,
                        nowWallTimeMillis,
                        nowElapsedRealtimeMillis);
                recordDiagnosticTransition(
                        diagnosticEventId,
                        DiagnosticEvent.State.DISPATCHED,
                        nowWallTimeMillis,
                        nowElapsedRealtimeMillis,
                        "TimingService observed the final cue");
                if (profile.beepOnCue && finalEvent.hasBeep()) {
                    audioDispatch.addBeep(
                            diagnosticEventId, finalEvent.beepDurationMillis);
                }
                if (profile.speakOnCue) {
                    addEventSpeech(profile, clock, finalEvent, audioDispatch,
                            diagnosticEventId);
                }
                if (profile.speakOnCue && profile.speakCurrentTime) {
                    audioDispatch.addSpeech(
                            diagnosticEventId, currentTimeSpeech(nowWallTimeMillis));
                }
                addDynamicTimeSpeech(
                        profile, clock, nowWallTimeMillis, nowElapsedRealtimeMillis,
                        audioDispatch, diagnosticEventId);
                recordDiagnosticTransition(
                        diagnosticEventId,
                        DiagnosticEvent.State.EXECUTED,
                        nowWallTimeMillis,
                        nowElapsedRealtimeMillis,
                        "Final cue actions queued by TimingService");
                recordSilentCueIfNeeded(audioDispatch, diagnosticEventId,
                        nowWallTimeMillis, nowElapsedRealtimeMillis);
            }
            if (profile.speakOnCue && profile.speakOnCompletion) {
                audioDispatch.addSpeech(completionEventId, clock.name + " finished");
            }
            if (completionEventId != null) {
                recordDiagnosticTransition(completionEventId, DiagnosticEvent.State.EXECUTED,
                        nowWallTimeMillis, nowElapsedRealtimeMillis,
                        "Completion audio actions queued by TimingService");
            }
            clock.setCueElapsedForProfile(profileId, finalCueElapsedMillis);
        }

        // This field is also the durable acknowledgement: an already-finished clock must not
        // beep later merely because its profile is edited or attached after completion.
        clock.completionBeepPlayed = true;
    }

    /** Adds scripted speech, optionally identifying which overlapping clock produced it. */
    private void addEventSpeech(
            BeepProfile profile,
            ClockInstance clock,
            CueEvent event,
            AudioDispatch audioDispatch,
            String diagnosticEventId) {
        if (!event.hasSpeech()) {
            return;
        }
        audioDispatch.addSpeech(diagnosticEventId, profile.speakClockNameWithSpeech
                ? clock.name + ": " + event.speechText
                : event.speechText);
    }

    private String currentTimeSpeech(long nowWallTimeMillis) {
        return "The time is " + DateFormat.getTimeInstance(
                DateFormat.SHORT, Locale.getDefault()).format(new Date(nowWallTimeMillis));
    }

    /** Adds profile-selected elapsed or remaining duration text to the TTS queue. */
    private void addDynamicTimeSpeech(
            BeepProfile profile,
            ClockInstance clock,
            long nowWallTimeMillis,
            long nowElapsedRealtimeMillis,
            AudioDispatch audioDispatch,
            String diagnosticEventId) {
        if (!profile.speakOnCue) {
            return;
        }
        String clockPrefix = profile.speakClockNameWithSpeech ? clock.name + ": " : "";
        if (profile.speakElapsedTime) {
            audioDispatch.addSpeech(diagnosticEventId, clockPrefix + TimeUtils.formatSpeechDuration(
                    clock.cueElapsedAt(nowWallTimeMillis, nowElapsedRealtimeMillis))
                    + " elapsed");
        }
        if (profile.speakRemainingTime) {
            if (clock.type == ClockInstance.Type.STOPWATCH) {
                audioDispatch.addSpeech(diagnosticEventId, clockPrefix + "no remaining time");
            } else {
                audioDispatch.addSpeech(diagnosticEventId, clockPrefix + TimeUtils.formatSpeechDuration(
                        clock.valueAt(nowWallTimeMillis, nowElapsedRealtimeMillis))
                        + " remaining");
            }
        }
    }

    /**
     * Sends one tick's collected audio to the players after all timer state is checkpointed.
     *
     * <p>Registering the event/action counts first is important.  Both Android TTS and the
     * beep worker are asynchronous, and a very short beep can report its first callback before
     * this method has returned to the Handler.  The tracker must therefore already know how many
     * actions belong to each event before either player is called.</p>
     */
    private void dispatchAudio(AudioDispatch audioDispatch, int runningCount) {
        audioDispatch.registerWith(diagnosticAudioTracker);
        List<AudioTimelinePlayer.SpeechCue> speechCues = new ArrayList<>();
        for (SpeechRequest request : audioDispatch.speechRequests) {
            speechCues.add(new AudioTimelinePlayer.SpeechCue(
                    request.text, audioListenerFor(request.eventId)));
        }
        AudioPlaybackListener beepListener = audioListenerFor(audioDispatch.beepEventIds);
        if (audioDispatch.beepDurationMillis > 0L || !speechCues.isEmpty()) {
            Log.i(TAG, "audio timeline dispatch beepMs=" + audioDispatch.beepDurationMillis
                    + " speechCount=" + speechCues.size()
                    + " runningClocks=" + runningCount);
            audioTimelinePlayer.dispatch(audioDispatch.beepDurationMillis,
                    beepListener, speechCues);
        }
    }

    /**
     * A due schedule can contain no enabled audio action after the profile switches are applied.
     * Record that exact reason now; otherwise the row would remain EXECUTED forever and a later
     * report could mistake a silent configuration for a missing Android player callback.
     */
    private void recordSilentCueIfNeeded(
            AudioDispatch audioDispatch, String eventId, long wallMillis, long elapsedMillis) {
        if (eventId != null && !audioDispatch.actionCounts.containsKey(eventId)) {
            recordDiagnosticTransition(eventId, DiagnosticEvent.State.FAILED,
                    wallMillis, elapsedMillis, "No enabled beep or speech action for cue");
        }
    }

    /**
     * Builds one callback that fans a shared player result out to all event IDs in that action.
     *
     * <p>Several overlapping beep events intentionally share one AudioTrack.  Their callback
     * is therefore a small fan-out rather than one callback per physical sound stream.</p>
     */
    private AudioPlaybackListener audioListenerFor(Set<String> eventIds) {
        if (eventIds == null || eventIds.isEmpty()) {
            return null;
        }
        List<String> ids = new ArrayList<>(eventIds);
        return new AudioPlaybackListener() {
            @Override
            public void onStarted(String detail) {
                for (String eventId : ids) {
                    diagnosticAudioTracker.onStarted(eventId, detail);
                }
            }

            @Override
            public void onCompleted(String detail) {
                for (String eventId : ids) {
                    diagnosticAudioTracker.onCompleted(eventId, detail);
                }
            }

            @Override
            public void onFailed(String detail) {
                for (String eventId : ids) {
                    diagnosticAudioTracker.onFailed(eventId, detail);
                }
            }
        };
    }

    /** Convenience overload for one speech utterance attributed to one event. */
    private AudioPlaybackListener audioListenerFor(String eventId) {
        return eventId == null
                ? null
                : audioListenerFor(Collections.singleton(eventId));
    }

    /**
     * Collects one Handler tick's audio before dispatching it.
     *
     * <p>The scheduler previously kept one list of speech strings and one maximum beep length.
     * This richer intermediate form preserves that same coalescing behavior while retaining the
     * event ID that explains why each action exists.</p>
     */
    private static final class AudioDispatch {
        private long beepDurationMillis;
        private final Set<String> beepEventIds = new LinkedHashSet<>();
        private final List<SpeechRequest> speechRequests = new ArrayList<>();
        private final Map<String, Integer> actionCounts = new LinkedHashMap<>();

        /** Adds a beep action and associates it with the originating event when available. */
        private void addBeep(String eventId, long durationMillis) {
            if (durationMillis <= 0L) {
                return;
            }
            beepDurationMillis = Math.max(beepDurationMillis, durationMillis);
            if (eventId != null) {
                beepEventIds.add(eventId);
                incrementActionCount(eventId);
            }
        }

        /** Adds one speech utterance and associates it with its originating event when known. */
        private void addSpeech(String eventId, String text) {
            if (text == null || text.trim().isEmpty()) {
                return;
            }
            speechRequests.add(new SpeechRequest(eventId, text));
            if (eventId != null) {
                incrementActionCount(eventId);
            }
        }

        /** Registers all event/action relationships before any player callback can arrive. */
        private void registerWith(DiagnosticAudioTracker tracker) {
            if (tracker == null) {
                return;
            }
            for (Map.Entry<String, Integer> entry : actionCounts.entrySet()) {
                tracker.register(entry.getKey(), entry.getValue());
            }
        }

        /** Increments the number of independent player actions required by one event. */
        private void incrementActionCount(String eventId) {
            actionCounts.put(eventId, actionCounts.getOrDefault(eventId, 0) + 1);
        }
    }

    /** One TTS request plus the event ID that should receive its callbacks. */
    private static final class SpeechRequest {
        private final String eventId;
        private final String text;

        private SpeechRequest(String eventId, String text) {
            this.eventId = eventId;
            this.text = text;
        }
    }

    private boolean hasPendingCompletion() {
        for (ClockInstance clock : clocks) {
            if (isPendingCompletion(clock)) {
                return true;
            }
        }
        return false;
    }

    private boolean isPendingCompletion(ClockInstance clock) {
        return clock != null
                && clock.completed
                && !clock.completionBeepPlayed
                && clock.cueElapsedAtAnchorMillis > 0L;
    }

    private boolean checkpointPersistIsDue(long nowWallTimeMillis) {
        return nowWallTimeMillis < lastCheckpointPersistWallTimeMillis
                || nowWallTimeMillis - lastCheckpointPersistWallTimeMillis
                >= CHECKPOINT_PERSIST_INTERVAL_MILLIS;
    }

    /** Only profiles with a configured finish sound need a separate expected event. */
    private static boolean hasCompletionAudio(BeepProfile profile) {
        return profile.beepOnCompletion || (profile.speakOnCue && profile.speakOnCompletion);
    }

    /**
     * Record a finite clock's finish event before its deadline enters the audio path. The
     * five-minute horizon bounds ledger growth; the same key is reused when the cue fires.
     */
    private void scheduleCompletionDiagnosticEvent(ClockInstance clock, BeepProfile profile,
            long nowWallMillis, long nowElapsedMillis) {
        if (clock.type == ClockInstance.Type.STOPWATCH || !hasCompletionAudio(profile)
                || diagnosticStore == null) {
            return;
        }
        long remainingMillis = clock.valueAt(nowWallMillis, nowElapsedMillis);
        if (remainingMillis <= DIAGNOSTIC_LOOKAHEAD_MILLIS) {
            ensureCompletionDiagnosticEvent(clock, profile, nowWallMillis,
                    nowElapsedMillis, true);
            captureDiagnosticSnapshot("event_scheduled", foreground,
                    nowWallMillis, nowElapsedMillis);
        }
    }

    /**
     * A finish action is distinct from a scripted cue at the same offset. Its stable segment
     * key survives the completion state change and process restarts. The first scheduled
     * deadline is retained on acknowledgement so lateness remains measurable.
     */
    private String ensureCompletionDiagnosticEvent(ClockInstance clock, BeepProfile profile,
            long nowWallMillis, long nowElapsedMillis) {
        return ensureCompletionDiagnosticEvent(clock, profile, nowWallMillis,
                nowElapsedMillis, false);
    }

    private String ensureCompletionDiagnosticEvent(ClockInstance clock, BeepProfile profile,
            long nowWallMillis, long nowElapsedMillis, boolean refreshExpected) {
        if (diagnosticStore == null) {
            return null;
        }
        long expectedWallMillis = clock.type == ClockInstance.Type.COUNTDOWN
                ? clock.configuredMillis
                : saturatingAdd(clock.anchorWallTimeMillis, clock.valueAtAnchorMillis);
        long expectedElapsedMillis = clock.type == ClockInstance.Type.COUNTDOWN
                || clock.anchorElapsedRealtimeMillis <= 0L
                ? 0L : saturatingAdd(clock.anchorElapsedRealtimeMillis,
                        clock.valueAtAnchorMillis);
        long finalCueOffsetMillis = saturatingAdd(clock.cueElapsedAtAnchorMillis,
                clock.valueAtAnchorMillis);
        try {
            return diagnosticStore.ensureScheduledEvent(
                    diagnosticRunPrefix(clock, profile.id) + "completion",
                    safeClockId(clock), profile.id, finalCueOffsetMillis,
                    expectedWallMillis, expectedElapsedMillis, nowWallMillis,
                    nowElapsedMillis, refreshExpected, "Expected finish sound recorded");
        } catch (RuntimeException error) {
            Log.e(TAG, "Unable to record expected completion sound", error);
            return null;
        }
    }

    /** Records the next cue before it is due, using a bounded rolling horizon. */
    private void scheduleNextDiagnosticEvent(
            ClockInstance clock,
            BeepProfile profile,
            long cueElapsedMillis,
            long nowWallTimeMillis,
            long nowElapsedRealtimeMillis) {
        if (diagnosticStore == null) {
            return;
        }
        long exclusiveFromMillis = clock.initialCuePending
                ? -1L : clock.cueElapsedForProfile(profile.id);
        long lookaheadMillis = saturatingAdd(cueElapsedMillis, DIAGNOSTIC_LOOKAHEAD_MILLIS);
        CueEvent nextEvent;
        try {
            nextEvent = profile.firstEventBetween(exclusiveFromMillis, lookaheadMillis);
        } catch (RuntimeException error) {
            Log.e(TAG, "Unable to identify the next diagnostic cue", error);
            return;
        }
        if (nextEvent != null) {
            ensureDiagnosticEvent(
                    clock, profile, nextEvent, nowWallTimeMillis, nowElapsedRealtimeMillis);
            captureDiagnosticSnapshot(
                    "event_scheduled", foreground, nowWallTimeMillis, nowElapsedRealtimeMillis);
        }
    }

    /** Creates or reuses one durable event identity for a clock/profile/offset combination. */
    private String ensureDiagnosticEvent(
            ClockInstance clock,
            BeepProfile profile,
            CueEvent event,
            long nowWallTimeMillis,
            long nowElapsedRealtimeMillis) {
        if (diagnosticStore == null || event == null) {
            return null;
        }
        // The next tick may arrive after this cue. Preserve its original deadline
        // instead of clamping a past offset to "now", which would hide a late dispatch.
        long offsetFromAnchor = signedDifference(
                event.offsetMillis, clock.cueElapsedAtAnchorMillis);
        long expectedWallMillis = saturatingSignedAdd(
                clock.anchorWallTimeMillis, offsetFromAnchor);
        long expectedElapsedMillis = clock.anchorElapsedRealtimeMillis <= 0L
                ? 0L
                : saturatingSignedAdd(clock.anchorElapsedRealtimeMillis, offsetFromAnchor);
        String eventKey = diagnosticRunPrefix(clock, profile.id) + event.offsetMillis;
        try {
            boolean stillFuture = event.offsetMillis > clock.cueElapsedAt(
                    nowWallTimeMillis, nowElapsedRealtimeMillis);
            String eventId = diagnosticStore.ensureScheduledEvent(
                    eventKey,
                    safeClockId(clock),
                    profile.id,
                    event.offsetMillis,
                    expectedWallMillis,
                    expectedElapsedMillis,
                    nowWallTimeMillis,
                    nowElapsedRealtimeMillis,
                    stillFuture,
                    "Expected cue recorded");
            return eventId;
        } catch (RuntimeException error) {
            Log.e(TAG, "Unable to record expected diagnostic cue", error);
            return null;
        }
    }

    /** Records a diagnostic transition without allowing ledger failure to affect timing. */
    private void recordDiagnosticTransition(
            String eventId,
            DiagnosticEvent.State state,
            long nowWallTimeMillis,
            long nowElapsedRealtimeMillis,
            String detail) {
        if (diagnosticStore == null || eventId == null) {
            return;
        }
        try {
            diagnosticStore.transition(
                    eventId,
                    state,
                    nowWallTimeMillis,
                    nowElapsedRealtimeMillis,
                    detail);
        } catch (RuntimeException error) {
            Log.e(TAG, "Unable to record diagnostic transition " + state, error);
        }
    }

    /** Records audio evidence and captures platform state when the player reports failure. */
    private void recordAudioDiagnosticTransition(
            String eventId,
            DiagnosticEvent.State state,
            long nowWallTimeMillis,
            long nowElapsedRealtimeMillis,
            String detail) {
        recordDiagnosticTransition(
                eventId, state, nowWallTimeMillis, nowElapsedRealtimeMillis, detail);
        if (state == DiagnosticEvent.State.FAILED) {
            captureDiagnosticSnapshot(
                    "audio_failure", foreground, nowWallTimeMillis, nowElapsedRealtimeMillis);
        }
    }

    /**
     * Captures one bounded Android-state sample without allowing diagnostic collection to stop
     * the timer. This method is synchronized because audio callbacks may arrive off the service
     * Handler thread while lifecycle snapshots normally arrive on it.
     */
    private synchronized void captureDiagnosticSnapshot(
            String kind,
            boolean foregroundServiceActive,
            long wallMillis,
            long elapsedMillis) {
        captureDiagnosticSnapshot(kind, foregroundServiceActive, wallMillis, elapsedMillis,
                -1L, -1L, readWakeLockHeld(), readDeviceIdleMode());
    }

    /** Adds an optional measured scheduling gap to a normal Android-state snapshot. */
    private synchronized void captureDiagnosticSnapshot(
            String kind, boolean foregroundServiceActive, long wallMillis, long elapsedMillis,
            long tickGapMillis) {
        captureDiagnosticSnapshot(kind, foregroundServiceActive, wallMillis, elapsedMillis,
                tickGapMillis, -1L, readWakeLockHeld(), readDeviceIdleMode());
    }

    /** Preserves state read at a delayed tick before the service tries to recover the wake lock. */
    private synchronized void captureDiagnosticSnapshot(
            String kind,
            boolean foregroundServiceActive,
            long wallMillis,
            long elapsedMillis,
            long tickGapMillis,
            long uptimeGapMillis,
            Boolean wakeLockHeldAtObservation,
            Boolean deviceIdleAtObservation) {
        if (snapshotWriter == null || kind == null) {
            return;
        }
        Long previousWallMillis = lastSnapshotWallMillis.get(kind);
        long minimumInterval = "event_scheduled".equals(kind)
                ? SCHEDULE_SNAPSHOT_INTERVAL_MILLIS : SNAPSHOT_MIN_INTERVAL_MILLIS;
        if (previousWallMillis != null
                && wallMillis >= previousWallMillis
                && wallMillis - previousWallMillis < minimumInterval) {
            return;
        }
        lastSnapshotWallMillis.put(kind, wallMillis);
        // Only the bounded queue operation remains on the timing thread. Android-state
        // queries and snapshot writes happen on the background worker. The timestamp and
        // two state values passed here remain tied to the original observation boundary.
        snapshotWriter.enqueue(kind, foregroundServiceActive, wallMillis, elapsedMillis,
                tickGapMillis, uptimeGapMillis, wakeLockHeldAtObservation,
                deviceIdleAtObservation, PROCESS_SESSION_ID, serviceInstanceId);
    }

    /**
     * Persists one heartbeat per minute and immediately samples a gap longer than five seconds.
     * A gap is app-side timing evidence; it cannot establish whether Android killed, paused or
     * merely delayed the process. Monotonic time avoids wall-clock adjustment artifacts.
     */
    private void recordTickHealth(
            long wallMillis,
            long elapsedMillis,
            long uptimeMillis,
            Boolean wakeLockHeldAtTickEntry,
            Boolean deviceIdleAtTickEntry) {
        long elapsedGapMillis = -1L;
        long uptimeGapMillis = -1L;
        if (lastTickElapsedRealtimeMillis > 0L
                && elapsedMillis >= lastTickElapsedRealtimeMillis) {
            elapsedGapMillis = elapsedMillis - lastTickElapsedRealtimeMillis;
            uptimeGapMillis = lastTickUptimeMillis > 0L && uptimeMillis >= lastTickUptimeMillis
                    ? uptimeMillis - lastTickUptimeMillis : -1L;
            largestTickGapSinceHeartbeatMillis = Math.max(
                    largestTickGapSinceHeartbeatMillis, elapsedGapMillis);
            if (uptimeGapMillis >= 0L) {
                largestUptimeGapSinceHeartbeatMillis = Math.max(
                        largestUptimeGapSinceHeartbeatMillis, uptimeGapMillis);
            }
            if (elapsedGapMillis > 5_000L) {
                captureDiagnosticSnapshot("scheduler_gap", foreground,
                        wallMillis, elapsedMillis, elapsedGapMillis, uptimeGapMillis,
                        wakeLockHeldAtTickEntry, deviceIdleAtTickEntry);
            }
        }
        lastTickElapsedRealtimeMillis = elapsedMillis;
        lastTickUptimeMillis = uptimeMillis;
        if (lastHeartbeatElapsedRealtimeMillis == 0L
                || elapsedMillis < lastHeartbeatElapsedRealtimeMillis
                || elapsedMillis - lastHeartbeatElapsedRealtimeMillis >= 60_000L) {
            captureDiagnosticSnapshot("service_heartbeat", foreground,
                    wallMillis, elapsedMillis, largestTickGapSinceHeartbeatMillis,
                    largestUptimeGapSinceHeartbeatMillis, wakeLockHeldAtTickEntry,
                    deviceIdleAtTickEntry);
            lastHeartbeatElapsedRealtimeMillis = elapsedMillis;
            largestTickGapSinceHeartbeatMillis = 0L;
            largestUptimeGapSinceHeartbeatMillis = 0L;
        }
    }

    /** Keeps high-frequency state samples shorter than event history, including long services. */
    private void pruneDiagnosticHistory(long wallMillis, long elapsedMillis) {
        if (diagnosticStore == null) {
            return;
        }
        try {
            diagnosticStore.pruneBefore(wallMillis - DIAGNOSTIC_RETENTION_MILLIS,
                    wallMillis - SNAPSHOT_RETENTION_MILLIS);
            lastDiagnosticPruneElapsedRealtimeMillis = elapsedMillis;
        } catch (RuntimeException error) {
            Log.e(TAG, "Unable to prune diagnostic history", error);
        }
    }

    /**
     * Reconciles rows left scheduled by an earlier process after the first recovery tick.
     * A scheduled row that is still overdue is evidence of a missed engine observation, not
     * proof of a particular Android restriction.
     */
    private void reconcileStartupDiagnostics() {
        if (diagnosticStore == null) {
            return;
        }
        try {
            int failed = diagnosticStore.failOverdueScheduledEvents(
                    System.currentTimeMillis(),
                    SystemClock.elapsedRealtime(),
                    "Startup found an overdue cue still marked scheduled");
            if (failed > 0) {
                Log.w(TAG, "Startup reconciliation found " + failed
                        + " overdue diagnostic cue(s)");
            }
        } catch (RuntimeException error) {
            Log.e(TAG, "Unable to reconcile diagnostic ledger at startup", error);
        }
        captureDiagnosticSnapshot(
                "startup_reconciliation", foreground, System.currentTimeMillis(),
                SystemClock.elapsedRealtime());
    }

    private BeepProfile findProfile(String profileId) {
        for (BeepProfile profile : profiles) {
            if (profile != null && Objects.equals(profile.id, profileId)) {
                return profile;
            }
        }
        return null;
    }

    private int countRunningClocks() {
        int count = 0;
        for (ClockInstance clock : clocks) {
            if (clock != null && clock.running) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns true when an active clock or pending completion has a profile that can emit audio.
     *
     * <p>Starting the stream with the timer, rather than at the first cue, lets Android see the
     * user-requested background audio work throughout the run. Profiles with no audible actions
     * keep using only the timer service and do not open an AudioTrack.</p>
     */
    private boolean hasRunningAudioProfile() {
        for (ClockInstance clock : clocks) {
            if (clock == null || (!clock.running && !isPendingCompletion(clock))) {
                continue;
            }
            for (String profileId : clock.attachedProfileIds()) {
                BeepProfile profile = findProfile(profileId);
                if (profile != null && profile.hasConfiguredAudioAction()) {
                    return true;
                }
            }
        }
        return false;
    }

    private void persistDirtyState() {
        if (!stateDirty) {
            return;
        }
        try {
            store.saveClockInstances(new ArrayList<>(clocks));
            stateDirty = false;
            lastCheckpointPersistWallTimeMillis = System.currentTimeMillis();
        } catch (RuntimeException error) {
            // Keep the dirty flag set so the next tick retries rather than losing cue checkpoints.
            Log.e(TAG, "Unable to persist timer state", error);
        }
    }

    private void createNotificationChannel() {
        if (notificationManager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(getString(R.string.notification_channel_description));
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setShowBadge(false);
        notificationManager.createNotificationChannel(channel);
    }

    private boolean ensureForeground(int runningCount, boolean audioPlayback) {
        int requestedType = foregroundServiceTypeFor(audioPlayback);
        if (foreground && requestedType == foregroundServiceType) {
            updateForegroundNotification(runningCount);
            return true;
        }
        try {
            Notification notification = buildNotification(runningCount);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, requestedType);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            foreground = true;
            foregroundServiceType = requestedType;
            notifiedRunningCount = runningCount;
            Boolean heldAtForegroundStart = readWakeLockHeld();
            recordRuntimeSnapshot("foreground_service_started", "startForeground succeeded",
                    "notification posted", heldAtForegroundStart, heldAtForegroundStart);
            captureDiagnosticSnapshot(
                    "foreground_started", true, System.currentTimeMillis(),
                    SystemClock.elapsedRealtime());
            return true;
        } catch (RuntimeException error) {
            Log.e(TAG, "Unable to enter foreground execution", error);
            Boolean heldAtForegroundFailure = readWakeLockHeld();
            recordRuntimeSnapshot("foreground_service_start_failed", "startForeground",
                    "FAILED: " + error.getClass().getSimpleName() + ": " + error.getMessage(),
                    heldAtForegroundFailure, heldAtForegroundFailure);
            captureDiagnosticSnapshot(
                    "foreground_start_failed", false, System.currentTimeMillis(),
                    SystemClock.elapsedRealtime());
            return false;
        }
    }

    /**
     * Declares only the foreground-service types needed for the current work.
     *
     * <p>Android 14 introduced the special-use runtime type. Earlier Android versions receive
     * the media-playback type only when a timer has audible work; a zero mask retains the
     * platform's pre-Android-14 timer-service behavior.</p>
     */
    private int foregroundServiceTypeFor(boolean audioPlayback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return 0;
        }
        int type = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        }
        if (audioPlayback) {
            type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
        }
        return type;
    }

    private void updateForegroundNotification(int runningCount) {
        if (!foreground || notificationManager == null
                || notifiedRunningCount == runningCount) {
            return;
        }
        notificationManager.notify(NOTIFICATION_ID, buildNotification(runningCount));
        notifiedRunningCount = runningCount;
    }

    private Notification buildNotification(int runningCount) {
        Intent openAppIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String contentText;
        if (runningCount == 0) {
            contentText = "Timer alert sounding";
        } else if (runningCount == 1) {
            contentText = "1 clock is running";
        } else {
            contentText = runningCount + " clocks are running";
        }

        return new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_timer)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(contentText)
                .setContentIntent(contentIntent)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .build();
    }

    /** Reads lock ownership without turning an unavailable Android value into false. */
    private Boolean readWakeLockHeld() {
        if (wakeLock == null) {
            return null;
        }
        try {
            return wakeLock.isHeld();
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to observe timing wake-lock state", error);
            return null;
        }
    }

    /** Samples device idle mode at the caller boundary; null means Android did not expose it. */
    private Boolean readDeviceIdleMode() {
        if (powerManager == null) {
            return null;
        }
        try {
            return powerManager.isDeviceIdleMode();
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to observe device idle mode", error);
            return null;
        }
    }

    /**
     * Writes rare service and wake-lock transitions to the existing durable snapshot ledger.
     *
     * <p>This synchronous insert is used only at lifecycle and lock-transition boundaries,
     * never on every 200 ms tick. It records the Android operation result together with process,
     * service and boot identities. If Android supplies no cause for a missing lock, the row says
     * UNKNOWN instead of attributing the change to a manufacturer or system policy.</p>
     */
    private void recordRuntimeSnapshot(
            String kind,
            String reason,
            String outcome,
            Boolean wakeLockHeldBefore,
            Boolean wakeLockHeldAfter) {
        if (diagnosticStore == null) {
            return;
        }

        long wallMillis = System.currentTimeMillis();
        long elapsedMillis = SystemClock.elapsedRealtime();
        long uptimeMillis = SystemClock.uptimeMillis();
        Boolean deviceIdleMode = readDeviceIdleMode();
        String details = new StringBuilder(640)
                .append('{')
                .append("\"kind\":").append(JsonSupport.quote(kind)).append(',')
                .append("\"wallClockMillis\":").append(wallMillis).append(',')
                .append("\"elapsedRealtimeMillis\":").append(elapsedMillis).append(',')
                .append("\"uptimeMillis\":").append(uptimeMillis).append(',')
                .append("\"processId\":").append(Process.myPid()).append(',')
                .append("\"processSessionId\":")
                .append(JsonSupport.quote(PROCESS_SESSION_ID)).append(',')
                .append("\"serviceInstanceId\":")
                .append(JsonSupport.quote(serviceInstanceId)).append(',')
                .append("\"bootCount\":").append(readBootCountJson()).append(',')
                .append("\"foregroundServiceActive\":").append(foreground).append(',')
                .append("\"deviceIdleMode\":").append(nullableBooleanJson(deviceIdleMode)).append(',')
                .append("\"wakeLockHeldBefore\":")
                .append(nullableBooleanJson(wakeLockHeldBefore)).append(',')
                .append("\"wakeLockHeldAfter\":")
                .append(nullableBooleanJson(wakeLockHeldAfter)).append(',')
                .append("\"reason\":").append(JsonSupport.quote(reason)).append(',')
                .append("\"outcome\":").append(JsonSupport.quote(outcome)).append(',')
                .append("\"causeClassification\":")
                .append(JsonSupport.quote(outcome.startsWith("UNKNOWN") ? "UNKNOWN" : "OBSERVED"))
                .append('}')
                .toString();
        try {
            diagnosticStore.insertSnapshot(kind, wallMillis, elapsedMillis, details);
        } catch (RuntimeException error) {
            // Diagnostics may be incomplete if storage fails; the timer operation still proceeds.
            Log.e(TAG, "Unable to persist runtime diagnostic " + kind, error);
        }
    }

    /** Returns a JSON boolean or the explicit unknown marker for unavailable observations. */
    private static String nullableBooleanJson(Boolean value) {
        return value == null ? JsonSupport.quote("unknown") : value.toString();
    }

    /** Reads the OS boot counter, retaining UNKNOWN if Android hides the value. */
    private String readBootCountJson() {
        try {
            int count = Settings.Global.getInt(
                    getContentResolver(), Settings.Global.BOOT_COUNT, -1);
            return count < 0 ? JsonSupport.quote("unknown") : Integer.toString(count);
        } catch (RuntimeException unavailable) {
            return JsonSupport.quote("unknown");
        }
    }

    /** Acquires the CPU wake lock and records exactly why this operation was requested. */
    private void acquireWakeLock(String reason) {
        Boolean heldBefore = readWakeLockHeld();
        if (Boolean.TRUE.equals(heldBefore)) {
            lastWakeLockHeldObservation = true;
            return;
        }
        if (wakeLock == null) {
            recordRuntimeSnapshot("wake_lock_acquire_unavailable", reason,
                    "UNKNOWN: PowerManager did not provide a wake lock", null, null);
            return;
        }
        try {
            // The lock is held only while a user-started clock or completion alert is active.
            // It is released on idle shutdown or service destruction; no timeout is used because
            // a timeout would let an otherwise active Handler loop sleep during a long timer.
            wakeLock.acquire();
            Boolean heldAfter = readWakeLockHeld();
            lastWakeLockHeldObservation = heldAfter;
            String outcome = Boolean.TRUE.equals(heldAfter)
                    ? "acquired" : "UNKNOWN: acquire returned but held state is unavailable/false";
            recordRuntimeSnapshot("wake_lock_acquire", reason, outcome,
                    heldBefore, heldAfter);
            Log.i(TAG, "Wake lock acquire reason=" + reason
                    + " heldBefore=" + heldBefore + " heldAfter=" + heldAfter);
        } catch (RuntimeException error) {
            Boolean heldAfter = readWakeLockHeld();
            lastWakeLockHeldObservation = heldAfter;
            recordRuntimeSnapshot("wake_lock_acquire_failed", reason,
                    "FAILED: " + error.getClass().getSimpleName() + ": " + error.getMessage(),
                    heldBefore, heldAfter);
            Log.e(TAG, "Unable to acquire the timing wake lock; reason=" + reason, error);
        }
    }

    /** Releases the CPU wake lock and records whether the app actually held it beforehand. */
    private void releaseWakeLock(String reason) {
        Boolean heldBefore = readWakeLockHeld();
        if (wakeLock == null) {
            recordRuntimeSnapshot("wake_lock_release_unavailable", reason,
                    "UNKNOWN: PowerManager did not provide a wake lock", null, null);
            return;
        }
        if (!Boolean.TRUE.equals(heldBefore)) {
            if (Boolean.TRUE.equals(lastWakeLockHeldObservation)) {
                recordRuntimeSnapshot("wake_lock_missing_unknown", reason,
                        "UNKNOWN: lock was previously observed held but is absent now",
                        true, heldBefore);
            }
            recordRuntimeSnapshot("wake_lock_release_not_held", reason,
                    "release() not called because isHeld() was false or unknown",
                    heldBefore, heldBefore);
            lastWakeLockHeldObservation = heldBefore;
            return;
        }
        try {
            wakeLock.release();
            Boolean heldAfter = readWakeLockHeld();
            lastWakeLockHeldObservation = heldAfter;
            recordRuntimeSnapshot("wake_lock_release", reason, "released",
                    heldBefore, heldAfter);
            Log.i(TAG, "Wake lock release reason=" + reason
                    + " heldBefore=" + heldBefore + " heldAfter=" + heldAfter);
        } catch (RuntimeException error) {
            Boolean heldAfter = readWakeLockHeld();
            lastWakeLockHeldObservation = heldAfter;
            recordRuntimeSnapshot("wake_lock_release_failed", reason,
                    "FAILED: " + error.getClass().getSimpleName() + ": " + error.getMessage(),
                    heldBefore, heldAfter);
            Log.w(TAG, "Unable to release the timing wake lock; reason=" + reason, error);
        }
    }

    private void waitForAlertOrStop() {
        if (audioTimelinePlayer.hasPendingAudio()) {
            handler.postDelayed(idleStopRunnable, 250L);
        } else {
            shutdownService();
        }
    }

    private void stopIfStillIdle() {
        if (destroyed) {
            return;
        }
        if (countRunningClocks() > 0) {
            tick();
            return;
        }
        if (audioTimelinePlayer.hasPendingAudio()) {
            handler.postDelayed(idleStopRunnable, 250L);
            return;
        }
        shutdownService();
    }

    private void broadcastStateChanged(int runningCount) {
        Intent stateIntent = new Intent(ACTION_STATE_CHANGED)
                .setPackage(getPackageName())
                .putExtra(EXTRA_RUNNING_COUNT, runningCount);
        sendBroadcast(stateIntent, internalBroadcastPermission(this));
    }

    /**
     * Resolves the app-private permission from this install's actual package ID.
     *
     * Debug builds have a separate application ID so they can coexist with a
     * Play install. Scoping this signature permission to that ID prevents the
     * two packages from declaring the same Android permission name.
     */
    public static String internalBroadcastPermission(Context context) {
        return context.getPackageName() + ".permission.INTERNAL_BROADCAST";
    }

    private String safeClockId(ClockInstance clock) {
        return clock.id == null ? "<unknown>" : clock.id;
    }

    private static long saturatingAdd(long first, long second) {
        try {
            return Math.addExact(first, second);
        } catch (ArithmeticException error) {
            return Long.MAX_VALUE;
        }
    }

    /** Signed deadline displacement, including an already-overdue cue after recovery. */
    private static long signedDifference(long later, long earlier) {
        try {
            return Math.subtractExact(later, earlier);
        } catch (ArithmeticException error) {
            return later < earlier ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }

    /** Keeps a reconstructed wall/elapsed deadline inside the nonnegative clock domain. */
    private static long saturatingSignedAdd(long anchor, long displacement) {
        try {
            return Math.max(0L, Math.addExact(anchor, displacement));
        } catch (ArithmeticException error) {
            return displacement < 0L ? 0L : Long.MAX_VALUE;
        }
    }

    private void shutdownService() {
        handler.removeCallbacks(tickRunnable);
        handler.removeCallbacks(idleStopRunnable);
        persistDirtyState();
        captureDiagnosticSnapshot(
                "service_idle", foreground, System.currentTimeMillis(),
                SystemClock.elapsedRealtime());
        Boolean wakeLockAtShutdown = readWakeLockHeld();
        recordRuntimeSnapshot("service_shutdown", "shutdownService",
                "no running clocks or pending audio", wakeLockAtShutdown,
                wakeLockAtShutdown);
        releaseWakeLock("shutdownService: no active clock/audio");
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foreground = false;
            foregroundServiceType = -1;
            notifiedRunningCount = -1;
        }
        if (lastStartId != 0) {
            stopSelfResult(lastStartId);
        } else {
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        persistDirtyState();
        captureDiagnosticSnapshot(
                "service_destroyed", foreground, System.currentTimeMillis(),
                SystemClock.elapsedRealtime());
        Boolean wakeLockAtDestroy = readWakeLockHeld();
        recordRuntimeSnapshot("service_destroyed", "Service.onDestroy entered",
                "destroyed=true", wakeLockAtDestroy, wakeLockAtDestroy);
        releaseWakeLock("Service.onDestroy");
        if (audioTimelinePlayer != null) {
            audioTimelinePlayer.close();
        }
        if (diagnosticAudioTracker != null) {
            diagnosticAudioTracker.close();
        }
        if (diagnosticStore != null) {
            diagnosticStore.close();
        }
        if (snapshotWriter != null) {
            snapshotWriter.close();
        }
        super.onDestroy();
    }
}
