package com.ose.multitimer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Owns timing and audible profile cues while one or more clocks are running. */
public final class TimingService extends Service {
    public static final String ACTION_SYNC = "com.ose.multitimer.action.SYNC";
    public static final String ACTION_STATE_CHANGED =
            "com.ose.multitimer.action.STATE_CHANGED";
    public static final String INTERNAL_BROADCAST_PERMISSION =
            "com.ose.multitimer.permission.INTERNAL_BROADCAST";
    public static final String EXTRA_RUNNING_COUNT = "running_count";

    private static final String TAG = "TimingService";
    private static final String NOTIFICATION_CHANNEL_ID = "running_timers";
    private static final int NOTIFICATION_ID = 1001;
    private static final long TICK_INTERVAL_MILLIS = 200L;
    private static final long CHECKPOINT_PERSIST_INTERVAL_MILLIS = 1_000L;
    private static final long IDLE_STOP_GRACE_MILLIS = 50L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tickRunnable = this::tick;
    private final Runnable idleStopRunnable = this::stopIfStillIdle;

    private AppStore store;
    private BeepPlayer beepPlayer;
    private SpeechPlayer speechPlayer;
    private NotificationManager notificationManager;
    private PowerManager.WakeLock wakeLock;
    private List<ClockInstance> clocks = new ArrayList<>();
    private List<BeepProfile> profiles = new ArrayList<>();
    private boolean foreground;
    private boolean stateDirty;
    private boolean destroyed;
    private int lastStartId;
    private int notifiedRunningCount = -1;
    private long lastCheckpointPersistWallTimeMillis;

    @Override
    public void onCreate() {
        super.onCreate();
        store = new AppStore(getSharedPreferences(AppStore.PREFERENCES_NAME, MODE_PRIVATE));
        beepPlayer = new BeepPlayer();
        speechPlayer = new SpeechPlayer(this);
        notificationManager = getSystemService(NotificationManager.class);
        createNotificationChannel();

        PowerManager powerManager = getSystemService(PowerManager.class);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    getPackageName() + ":timing"
            );
            wakeLock.setReferenceCounted(false);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        handler.removeCallbacks(tickRunnable);
        handler.removeCallbacks(idleStopRunnable);

        String action = intent == null ? null : intent.getAction();
        if (action != null && !ACTION_SYNC.equals(action)) {
            Log.w(TAG, "Ignoring unsupported action while synchronizing state: " + action);
        }
        reloadState();
        tick();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void reloadState() {
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
        }

        try {
            List<BeepProfile> loadedProfiles = store.loadProfiles();
            profiles = loadedProfiles == null
                    ? new ArrayList<>()
                    : new ArrayList<>(loadedProfiles);
        } catch (RuntimeException error) {
            Log.e(TAG, "Unable to load beep profiles; retaining the current profiles", error);
        }
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
            persistDirtyState();
            waitForAlertOrStop();
            return;
        }

        if (!ensureForeground(runningBeforeTick)) {
            shutdownService();
            return;
        }
        acquireWakeLock();

        long nowWallTimeMillis = System.currentTimeMillis();
        long nowElapsedRealtimeMillis = SystemClock.elapsedRealtime();
        long beepDurationMillis = 0L;
        List<String> speechTexts = new ArrayList<>();
        boolean visibleStateChanged = false;
        boolean checkpointAdvanced = false;

        for (ClockInstance clock : clocks) {
            if (clock == null) {
                continue;
            }

            BeepProfile profile = findProfile(clock.profileId);
            if (!clock.running) {
                if (isPendingCompletion(clock)) {
                    beepDurationMillis = Math.max(
                            beepDurationMillis,
                            acknowledgeCompletion(
                                    clock,
                                    profile,
                                    nowWallTimeMillis,
                                    nowElapsedRealtimeMillis)
                    );
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
                beepDurationMillis = Math.max(
                        beepDurationMillis,
                        acknowledgeCompletion(
                                clock, profile, nowWallTimeMillis, nowElapsedRealtimeMillis)
                );
                continue;
            }

            if (!clock.running || clock.completed) {
                continue;
            }

            long previousCueElapsedMillis = clock.lastCueElapsedMillis;
            long cueElapsedMillis;
            try {
                cueElapsedMillis = clock.cueElapsedAt(
                        nowWallTimeMillis, nowElapsedRealtimeMillis);
            } catch (RuntimeException error) {
                Log.e(TAG, "Unable to read cues for clock " + safeClockId(clock), error);
                continue;
            }

            boolean correctedBackwards = cueElapsedMillis < previousCueElapsedMillis;
            long cueAtElapsedMillis = BeepProfile.NO_CUE;
            CueEvent dueEvent = null;
            if (!correctedBackwards && profile != null) {
                try {
                    if (clock.initialCuePending) {
                        dueEvent = profile.firstEventBetween(-1L, cueElapsedMillis);
                        clock.initialCuePending = false;
                    } else {
                        dueEvent = profile.firstEventBetween(
                                previousCueElapsedMillis,
                                cueElapsedMillis);
                    }
                    if (dueEvent != null) {
                        cueAtElapsedMillis = dueEvent.offsetMillis;
                    }
                } catch (RuntimeException error) {
                    Log.e(TAG, "Unable to evaluate cues for clock " + safeClockId(clock), error);
                }
            }

            boolean cueDue = cueAtElapsedMillis != BeepProfile.NO_CUE;
            if (cueDue) {
                // Advance only through the first due event. A delayed tick catches up one
                // event at a time so intermediate profile entries are not silently consumed.
                clock.lastCueElapsedMillis = cueAtElapsedMillis;
                checkpointAdvanced = true;
            } else if (cueElapsedMillis != previousCueElapsedMillis) {
                clock.lastCueElapsedMillis = cueElapsedMillis;
                checkpointAdvanced = true;
            }
            if (correctedBackwards) {
                // Persist corrections immediately so an old cue window cannot be replayed.
                stateDirty = true;
            }
            if (cueDue) {
                Log.i(
                        TAG,
                        "cue due clock=" + safeClockId(clock)
                                + " profile=" + (profile == null ? "<none>" : profile.id)
                                + " previousElapsedMs=" + previousCueElapsedMillis
                                + " cueElapsedMs=" + cueElapsedMillis
                                + " cueAtElapsedMs=" + cueAtElapsedMillis
                                + " nowElapsedRealtimeMs=" + nowElapsedRealtimeMillis
                );
                if (profile != null) {
                    long eventBeepDuration = dueEvent == null
                            ? profile.beepDurationMillis
                            : dueEvent.beepDurationMillis;
                    beepDurationMillis = Math.max(beepDurationMillis, eventBeepDuration);
                    if (dueEvent != null && dueEvent.hasSpeech()) {
                        speechTexts.add(dueEvent.speechText);
                    }
                }
                visibleStateChanged = true;
                stateDirty = true;
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

        if (beepDurationMillis > 0L) {
            Log.i(TAG, "beep dispatch durationMs=" + beepDurationMillis
                    + " runningClocks=" + runningAfterTick);
            beepPlayer.play(beepDurationMillis);
        }
        for (String speechText : speechTexts) {
            speechPlayer.speak(speechText);
        }

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
    private long acknowledgeCompletion(
            ClockInstance clock,
            BeepProfile profile,
            long nowWallTimeMillis,
            long nowElapsedRealtimeMillis) {
        if (clock.completionBeepPlayed) {
            return 0L;
        }

        long previousCueElapsedMillis = clock.lastCueElapsedMillis;
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
            return 0L;
        }

        boolean shouldBeep = profile != null && profile.beepOnCompletion;
        if (!shouldBeep
                && profile != null
                && finalCueElapsedMillis >= previousCueElapsedMillis) {
            try {
                shouldBeep = profile.hasCueBetween(
                        previousCueElapsedMillis,
                        finalCueElapsedMillis
                );
            } catch (RuntimeException error) {
                Log.e(
                        TAG,
                        "Unable to evaluate the final cue for clock " + safeClockId(clock),
                        error
                );
            }
        }

        // This field is also the durable acknowledgement: an already-finished clock must not
        // beep later merely because its profile is edited or attached after completion.
        clock.completionBeepPlayed = true;
        return shouldBeep ? profile.beepDurationMillis : 0L;
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

    private boolean ensureForeground(int runningCount) {
        if (foreground) {
            updateForegroundNotification(runningCount);
            return true;
        }
        try {
            startForeground(NOTIFICATION_ID, buildNotification(runningCount));
            foreground = true;
            notifiedRunningCount = runningCount;
            return true;
        } catch (RuntimeException error) {
            Log.e(TAG, "Unable to enter foreground execution", error);
            return false;
        }
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

    private void acquireWakeLock() {
        if (wakeLock == null || wakeLock.isHeld()) {
            return;
        }
        try {
            // The lock is held only while at least one user-started clock is active and is
            // released from shutdownService/onDestroy. A timeout would let the Handler loop
            // enter deep sleep during a long timer session.
            wakeLock.acquire();
        } catch (RuntimeException error) {
            Log.e(TAG, "Unable to acquire the timing wake lock", error);
        }
    }

    private void releaseWakeLock() {
        if (wakeLock == null || !wakeLock.isHeld()) {
            return;
        }
        try {
            wakeLock.release();
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to release the timing wake lock cleanly", error);
        }
    }

    private void waitForAlertOrStop() {
        long remainingAlertMillis = beepPlayer.remainingMillis();
        if (remainingAlertMillis > 0L) {
            handler.postDelayed(
                    idleStopRunnable,
                    remainingAlertMillis + IDLE_STOP_GRACE_MILLIS
            );
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
        long remainingAlertMillis = beepPlayer.remainingMillis();
        if (remainingAlertMillis > 0L) {
            handler.postDelayed(
                    idleStopRunnable,
                    remainingAlertMillis + IDLE_STOP_GRACE_MILLIS
            );
            return;
        }
        shutdownService();
    }

    private void broadcastStateChanged(int runningCount) {
        Intent stateIntent = new Intent(ACTION_STATE_CHANGED)
                .setPackage(getPackageName())
                .putExtra(EXTRA_RUNNING_COUNT, runningCount);
        sendBroadcast(stateIntent, INTERNAL_BROADCAST_PERMISSION);
    }

    private String safeClockId(ClockInstance clock) {
        return clock.id == null ? "<unknown>" : clock.id;
    }

    private void shutdownService() {
        handler.removeCallbacks(tickRunnable);
        handler.removeCallbacks(idleStopRunnable);
        persistDirtyState();
        releaseWakeLock();
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foreground = false;
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
        releaseWakeLock();
        if (beepPlayer != null) {
            beepPlayer.close();
        }
        if (speechPlayer != null) {
            speechPlayer.close();
        }
        super.onDestroy();
    }
}
