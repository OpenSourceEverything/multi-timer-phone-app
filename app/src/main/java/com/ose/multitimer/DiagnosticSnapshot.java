package com.ose.multitimer;

import android.Manifest;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.ApplicationExitInfo;
import android.app.NotificationManager;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import java.util.List;

/**
 * Collects a point-in-time description of Android conditions relevant to a timer cue.
 *
 * <p>This class is deliberately read-only. It does not request permissions, change battery
 * policy, acquire audio focus, or alter timer scheduling. Its purpose is to preserve the
 * surrounding facts that may help explain a later failure report.</p>
 *
 * <p>Some Android values are unavailable on older releases or can be rejected by a device
 * policy. Those values are written as an explicit unknown marker rather than being guessed.
 * A snapshot is evidence collected by this app; it is not proof that Android caused a cue
 * failure.</p>
 */
final class DiagnosticSnapshot {
    private static final String UNKNOWN = "unknown";

    private DiagnosticSnapshot() {
        // Utility class.
    }

    /** Returns one JSON object containing the relevant app, device, power and audio facts. */
    static String capture(
            Context context,
            String kind,
            boolean foregroundServiceActive,
            long wallMillis,
            long elapsedMillis) {
        return capture(context, kind, foregroundServiceActive, wallMillis, elapsedMillis,
                wallMillis, elapsedMillis, -1L, -1L, null, null, UNKNOWN, UNKNOWN);
    }

    /** Captures the same state with the largest observed service-tick gap when available. */
    static String capture(
            Context context,
            String kind,
            boolean foregroundServiceActive,
            long wallMillis,
            long elapsedMillis,
            long tickGapMillis) {
        return capture(context, kind, foregroundServiceActive, wallMillis, elapsedMillis,
                wallMillis, elapsedMillis, tickGapMillis, -1L, null, null,
                UNKNOWN, UNKNOWN);
    }

    /**
     * Captures both the original event-boundary facts and the later worker sample.
     *
     * <p>Android-state reads run on a worker so they cannot stall a timer tick. This method
     * can therefore run after the event that requested the snapshot. The original timestamps
     * and power facts observed by the service are kept separately from values read here, so a
     * later state cannot be mistaken for the state at the tick boundary.</p>
     */
    static String capture(
            Context context,
            String kind,
            boolean foregroundServiceActive,
            long observedWallMillis,
            long observedElapsedMillis,
            long stateReadWallMillis,
            long stateReadElapsedMillis,
            long tickGapMillis,
            long uptimeGapMillis,
            Boolean wakeLockHeldAtObservation,
            Boolean deviceIdleAtObservation,
            String processSessionId,
            String serviceInstanceId) {
        Context appContext = context.getApplicationContext();
        PowerManager powerManager = appContext.getSystemService(PowerManager.class);
        AudioManager audioManager = appContext.getSystemService(AudioManager.class);
        NotificationManager notificationManager =
                appContext.getSystemService(NotificationManager.class);
        AlarmManager alarmManager = appContext.getSystemService(AlarmManager.class);
        UsageStatsManager usageStatsManager =
                appContext.getSystemService(UsageStatsManager.class);

        BatteryReading battery = readBattery(appContext);
        PackageInfo packageInfo = readPackageInfo(appContext);
        StringBuilder json = new StringBuilder(1_600)
                .append('{')
                .append("\"kind\":").append(JsonSupport.quote(kind)).append(',')
                .append("\"wallClockMillis\":").append(observedWallMillis).append(',')
                .append("\"elapsedRealtimeMillis\":").append(observedElapsedMillis).append(',')
                .append("\"stateReadWallClockMillis\":").append(stateReadWallMillis).append(',')
                .append("\"stateReadElapsedRealtimeMillis\":")
                .append(stateReadElapsedMillis).append(',')
                .append("\"processAlive\":true,")
                .append("\"processId\":").append(android.os.Process.myPid()).append(',')
                .append("\"processSessionId\":")
                .append(JsonSupport.quote(processSessionId)).append(',')
                .append("\"serviceInstanceId\":")
                .append(JsonSupport.quote(serviceInstanceId)).append(',')
                .append("\"bootCount\":").append(readBootCount(appContext)).append(',')
                .append("\"foregroundServiceActive\":")
                .append(foregroundServiceActive).append(',')
                .append("\"wakeLockHeldAtObservation\":")
                .append(nullableBoolean(wakeLockHeldAtObservation)).append(',')
                // isHeld() reports this app's acquire/release bookkeeping. A device vendor
                // can suspend enforcement without changing the Java WakeLock object's state.
                .append("\"wakeLockObservationSource\":")
                .append(JsonSupport.quote("PowerManager.WakeLock.isHeld()")).append(',')
                .append("\"wakeLockObservationMeaning\":")
                .append(JsonSupport.quote("App acquire/release state; not proof that the device "
                        + "is honoring the CPU wake request")).append(',')
                .append("\"deviceIdleModeAtObservation\":")
                .append(nullableBoolean(deviceIdleAtObservation)).append(',')
                .append("\"appVersion\":")
                .append(JsonSupport.quote(packageInfo == null || packageInfo.versionName == null
                        ? UNKNOWN : packageInfo.versionName)).append(',')
                .append("\"androidRelease\":").append(JsonSupport.quote(Build.VERSION.RELEASE))
                .append(',')
                .append("\"androidSdk\":").append(Build.VERSION.SDK_INT).append(',')
                .append("\"manufacturer\":")
                .append(JsonSupport.quote(Build.MANUFACTURER)).append(',')
                .append("\"model\":").append(JsonSupport.quote(Build.MODEL)).append(',')
                .append("\"interactive\":")
                .append(powerManager != null && powerManager.isInteractive()).append(',')
                .append("\"powerSaveMode\":")
                .append(powerManager != null && powerManager.isPowerSaveMode()).append(',')
                .append("\"batteryOptimizationIgnored\":")
                .append(readBatteryOptimization(appContext, powerManager)).append(',')
                .append("\"appStandbyBucket\":")
                .append(readAppStandbyBucket(usageStatsManager)).append(',')
                .append("\"exactAlarmAccess\":")
                .append(readExactAlarmAccess(alarmManager)).append(',')
                .append("\"notificationPermissionGranted\":")
                .append(readNotificationPermission(appContext)).append(',')
                .append("\"notificationPolicyAccess\":")
                .append(readNotificationPolicyAccess(notificationManager)).append(',')
                .append("\"interruptionFilter\":")
                .append(readInterruptionFilter(notificationManager)).append(',')
                .append("\"charging\":").append(battery.charging).append(',')
                .append("\"batteryPercent\":").append(battery.percent).append(',')
                .append("\"alarmVolume\":").append(readStreamVolume(
                        audioManager, AudioManager.STREAM_ALARM)).append(',')
                .append("\"alarmMaxVolume\":").append(readStreamMaxVolume(
                        audioManager, AudioManager.STREAM_ALARM)).append(',')
                .append("\"alarmMuted\":").append(readStreamMute(
                        audioManager, AudioManager.STREAM_ALARM)).append(',')
                .append("\"musicVolume\":").append(readStreamVolume(
                        audioManager, AudioManager.STREAM_MUSIC)).append(',')
                .append("\"musicMaxVolume\":").append(readStreamMaxVolume(
                        audioManager, AudioManager.STREAM_MUSIC)).append(',')
                .append("\"musicMuted\":").append(readStreamMute(
                        audioManager, AudioManager.STREAM_MUSIC)).append(',')
                .append("\"ringerMode\":").append(readRingerMode(audioManager)).append(',')
                .append("\"audioMode\":").append(readAudioMode(audioManager));
        if (tickGapMillis >= 0L) {
            json.append(",\"observedTickGapMillis\":").append(tickGapMillis);
        }
        if (uptimeGapMillis >= 0L) {
            json.append(",\"observedTickUptimeGapMillis\":").append(uptimeGapMillis);
        }
        if (tickGapMillis >= 0L && uptimeGapMillis >= 0L) {
            long estimatedDeepSleepMillis = estimateDeepSleepMillis(
                    tickGapMillis, uptimeGapMillis);
            json.append(",\"estimatedDeepSleepMillis\":")
                    .append(estimatedDeepSleepMillis);
        }
        if ("scheduler_gap".equals(kind)) {
            // App-visible APIs cannot expose every OEM power decision. Keep that limit explicit
            // even when the lock appears held again at the recovery tick.
            json.append(",\"causeClassification\":\"UNKNOWN\"")
                    .append(",\"causeClassificationLimit\":")
                    .append(JsonSupport.quote("App observations cannot establish whether Android "
                            + "or manufacturer policy suspended wake-lock enforcement"));
        }
        json.append('}');
        return json.toString();
    }

    /**
     * Estimates sleep time from the difference between two Android clock deltas.
     *
     * <p>{@code elapsedRealtime} advances during deep sleep, while {@code uptimeMillis} does
     * not. Their nonnegative difference estimates sleep within the interval. It is timing
     * evidence only: it cannot identify why Android or the device vendor allowed sleep.</p>
     *
     * @param elapsedGapMillis elapsed-realtime difference between consecutive service ticks
     * @param uptimeGapMillis uptime difference over the same two service ticks
     * @return estimated sleep duration, or {@code -1} if either observation is unavailable
     */
    static long estimateDeepSleepMillis(long elapsedGapMillis, long uptimeGapMillis) {
        if (elapsedGapMillis < 0L || uptimeGapMillis < 0L) {
            return -1L;
        }
        return Math.max(0L, elapsedGapMillis - uptimeGapMillis);
    }

    /** Encodes unavailable booleans explicitly rather than guessing false. */
    private static String nullableBoolean(Boolean value) {
        return value == null ? JsonSupport.quote(UNKNOWN) : value.toString();
    }

    /** Returns the Android boot counter, or the established unknown marker if unavailable. */
    private static String readBootCount(Context context) {
        try {
            int count = Settings.Global.getInt(
                    context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
            return count < 0 ? JsonSupport.quote(UNKNOWN) : Integer.toString(count);
        } catch (RuntimeException unavailable) {
            return JsonSupport.quote(UNKNOWN);
        }
    }

    /**
     * Reads recent Android process exits on API 30+. A reason is OS-reported evidence about
     * a previous process, not proof that it caused any one missing timer callback.
     */
    static String captureProcessExitHistory(Context context, long wallMillis, long elapsedMillis) {
        StringBuilder json = new StringBuilder("{\"kind\":\"process_exit_history\",\"wallClockMillis\":")
                .append(wallMillis).append(",\"elapsedRealtimeMillis\":")
                .append(elapsedMillis);
        boolean available = false;
        StringBuilder entries = new StringBuilder();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ActivityManager manager = context.getSystemService(ActivityManager.class);
            if (manager != null) {
                try {
                    List<ApplicationExitInfo> exits = manager.getHistoricalProcessExitReasons(
                            context.getPackageName(), 0, 5);
                    available = exits != null;
                    for (int index = 0; exits != null && index < exits.size(); index++) {
                        ApplicationExitInfo exit = exits.get(index);
                        if (index > 0) {
                            entries.append(',');
                        }
                        entries.append("{\"timestampMillis\":").append(exit.getTimestamp())
                                .append(",\"reasonCode\":").append(exit.getReason())
                                .append(",\"status\":").append(exit.getStatus())
                                .append(",\"description\":")
                                .append(JsonSupport.quote(exit.getDescription()))
                                .append('}');
                    }
                } catch (RuntimeException error) {
                    available = false;
                }
            }
        }
        return json.append(",\"available\":").append(available)
                .append(",\"entries\": [").append(entries).append("]}").toString();
    }

    /** Reads battery state without retaining a receiver registration. */
    private static BatteryReading readBattery(Context context) {
        Intent batteryIntent = context.registerReceiver(
                null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batteryIntent == null) {
            return new BatteryReading(false, -1);
        }
        int status = batteryIntent.getIntExtra(
                BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        int level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int percent = level >= 0 && scale > 0 ? (level * 100) / scale : -1;
        return new BatteryReading(charging, percent);
    }

    /** Reads the installed app version, returning unknown when package metadata is unavailable. */
    private static PackageInfo readPackageInfo(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException error) {
            return null;
        }
    }

    /** Returns whether Android currently exempts this app from battery optimization. */
    private static String readBatteryOptimization(Context context, PowerManager powerManager) {
        if (powerManager == null) {
            return UNKNOWN;
        }
        try {
            return String.valueOf(powerManager.isIgnoringBatteryOptimizations(
                    context.getPackageName()));
        } catch (RuntimeException error) {
            return UNKNOWN;
        }
    }

    /** Returns the app standby bucket, or unknown on releases without that API. */
    private static String readAppStandbyBucket(UsageStatsManager usageStatsManager) {
        if (usageStatsManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return UNKNOWN;
        }
        try {
            return String.valueOf(usageStatsManager.getAppStandbyBucket());
        } catch (RuntimeException error) {
            return UNKNOWN;
        }
    }

    /** Reports exact-alarm access without requesting that access. */
    private static String readExactAlarmAccess(AlarmManager alarmManager) {
        if (alarmManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return UNKNOWN;
        }
        try {
            return String.valueOf(alarmManager.canScheduleExactAlarms());
        } catch (RuntimeException error) {
            return UNKNOWN;
        }
    }

    /** Reports POST_NOTIFICATIONS permission state on Android 13 and later. */
    private static boolean readNotificationPermission(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Reports whether this app may change the device notification policy. */
    private static String readNotificationPolicyAccess(NotificationManager manager) {
        if (manager == null) {
            return UNKNOWN;
        }
        try {
            return String.valueOf(manager.isNotificationPolicyAccessGranted());
        } catch (RuntimeException error) {
            return UNKNOWN;
        }
    }

    /** Captures Android's current interruption filter without assuming it suppresses audio. */
    private static String readInterruptionFilter(NotificationManager manager) {
        if (manager == null) {
            return UNKNOWN;
        }
        try {
            return String.valueOf(manager.getCurrentInterruptionFilter());
        } catch (RuntimeException error) {
            return UNKNOWN;
        }
    }

    /** Reads one audio stream value, returning unknown when no AudioManager exists. */
    private static String readStreamVolume(AudioManager manager, int stream) {
        return manager == null ? UNKNOWN : String.valueOf(manager.getStreamVolume(stream));
    }

    /** Reads one stream's maximum volume for interpreting the current volume. */
    private static String readStreamMaxVolume(AudioManager manager, int stream) {
        return manager == null ? UNKNOWN : String.valueOf(manager.getStreamMaxVolume(stream));
    }

    /** Reads the platform mute bit for one stream where the API is available. */
    private static String readStreamMute(AudioManager manager, int stream) {
        if (manager == null) {
            return UNKNOWN;
        }
        try {
            return String.valueOf(manager.isStreamMute(stream));
        } catch (RuntimeException error) {
            return UNKNOWN;
        }
    }

    /** Reads the global ringer mode, which is useful context for alarm output. */
    private static String readRingerMode(AudioManager manager) {
        return manager == null ? UNKNOWN : String.valueOf(manager.getRingerMode());
    }

    /** Reads the active audio mode, not a claim about whether this app owns audio focus. */
    private static String readAudioMode(AudioManager manager) {
        return manager == null ? UNKNOWN : String.valueOf(manager.getMode());
    }

    /** Small immutable result used to keep battery parsing separate from JSON assembly. */
    private static final class BatteryReading {
        private final boolean charging;
        private final int percent;

        private BatteryReading(boolean charging, int percent) {
            this.charging = charging;
            this.percent = percent;
        }
    }
}
