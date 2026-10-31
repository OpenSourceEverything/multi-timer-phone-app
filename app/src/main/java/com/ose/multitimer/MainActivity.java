package com.ose.multitimer;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.play.core.appupdate.AppUpdateInfo;
import com.google.android.play.core.appupdate.AppUpdateManager;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.appupdate.AppUpdateOptions;
import com.google.android.play.core.install.InstallStateUpdatedListener;
import com.google.android.play.core.install.model.AppUpdateType;
import com.google.android.play.core.install.model.InstallStatus;
import com.google.android.play.core.install.model.UpdateAvailability;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import kotlin.Unit;

/**
 * A small, dependency-free interface for managing independent clocks and their reusable cue profiles.
 */
public final class MainActivity extends Activity {
    private static final int REQUEST_NOTIFICATIONS = 1001;
    private static final int REQUEST_IN_APP_UPDATE = 1002;
    private static final int REQUEST_IMPORT_PROFILES = 1003;
    private static final int REQUEST_EXPORT_PROFILES = 1004;
    private static final long UI_TICK_MILLIS = 100L;
    private static final String STATE_PENDING_EXPORT_BYTES = "pending_profile_export_bytes";
    private static final String STATE_IMPORT_INTERRUPTED = "profile_import_interrupted";

    private static final int COLOR_PAPER = Color.rgb(246, 247, 249);
    private static final int COLOR_CARD = Color.WHITE;
    private static final int COLOR_INK = Color.rgb(23, 33, 43);
    private static final int COLOR_MUTED = Color.rgb(91, 103, 116);
    private static final int COLOR_ACCENT = Color.rgb(0, 108, 103);
    private static final int COLOR_ACCENT_SOFT = Color.rgb(222, 241, 239);
    private static final int COLOR_DANGER = Color.rgb(166, 45, 55);
    private static final int COLOR_BORDER = Color.rgb(222, 226, 231);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, ClockCard> cards = new HashMap<>();
    private final DateFormat targetDateFormat = DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM, DateFormat.SHORT);

    private AppStore store;
    private LinearLayout clockList;
    private TextView summary;
    private AlertDialog profilesDialog;
    private List<ClockInstance> clocks = new ArrayList<>();
    private boolean receiverRegistered;
    private AppUpdateManager appUpdateManager;
    private ExecutorService profileFileExecutor;
    private ProfileImportCoordinator profileImportCoordinator;
    private AlertDialog profileTransferDialog;
    private AlertDialog profilePreviewDialog;
    private byte[] pendingExportBytes;
    private long profileTransferGeneration;
    private long displayedPreviewRequestId = -1L;
    private boolean profileImportInProgress;
    private boolean activityDestroyed;
    private boolean updateFlowStarted;
    private boolean updateReadyDialogShown;
    private boolean updatePostponedThisSession;
    private boolean diagnosticExportInProgress;

    private final InstallStateUpdatedListener updateListener = state -> {
        if (state.installStatus() == InstallStatus.DOWNLOADED) {
            updateFlowStarted = false;
            promptToCompleteUpdate();
        }
    };

    private final Runnable uiTicker = new Runnable() {
        @Override
        public void run() {
            updateClockDisplays();
            handler.postDelayed(this, UI_TICK_MILLIS);
        }
    };

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            reloadClocks();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setStatusBarColor(COLOR_PAPER);
        window.setNavigationBarColor(COLOR_PAPER);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        store = new AppStore(getSharedPreferences(AppStore.PREFERENCES_NAME, MODE_PRIVATE));
        store.ensureDefaultProfiles();
        store.pruneOrphanedOwnedProfiles(store.loadClockInstances());
        if (savedInstanceState != null) {
            pendingExportBytes = savedInstanceState.getByteArray(STATE_PENDING_EXPORT_BYTES);
        }
        appUpdateManager = AppUpdateManagerFactory.create(this);
        setContentView(buildContent());
        requestNotificationPermissionIfNeeded();
        if (savedInstanceState != null
                && savedInstanceState.getBoolean(STATE_IMPORT_INTERRUPTED, false)) {
            handler.post(() -> Toast.makeText(this,
                    "Import screen was recreated. Check Reusable profiles before retrying.",
                    Toast.LENGTH_LONG).show());
        }
    }

    @Override
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(TimingService.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter,
                    TimingService.internalBroadcastPermission(this), null,
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter,
                    TimingService.internalBroadcastPermission(this), null);
        }
        receiverRegistered = true;
        updatePostponedThisSession = false;
        appUpdateManager.registerListener(updateListener);
        reloadClocks();
        if (hasRunningClock()) {
            // Resumes the single scheduler after a process restart or force-stop/relaunch.
            syncService();
        }
        handler.removeCallbacks(uiTicker);
        handler.post(uiTicker);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(uiTicker);
        appUpdateManager.unregisterListener(updateListener);
        if (receiverRegistered) {
            unregisterReceiver(stateReceiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    /**
     * Keeps one-shot SAF export bytes across rotation and reports unfinished imports.
     *
     * A preview is intentionally not restored as a pending write: if Android
     * recreates this screen, the person must reopen the file and confirm again.
     * If a save had already begun, it cannot be rolled back, so the restored
     * message tells them to inspect the profile list before trying it again.
     */
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (pendingExportBytes != null) {
            outState.putByteArray(STATE_PENDING_EXPORT_BYTES, pendingExportBytes);
        }
        ProfileImportCoordinator.State importState = profileImportCoordinator == null
                ? null : profileImportCoordinator.getState();
        boolean unfinishedImport = profileImportInProgress
                && !(importState instanceof ProfileImportCoordinator.State.Success)
                && !(importState instanceof ProfileImportCoordinator.State.Error);
        outState.putBoolean(STATE_IMPORT_INTERRUPTED, unfinishedImport);
        super.onSaveInstanceState(outState);
    }

    /**
     * Prevents callbacks from an old Activity instance updating a recreated screen.
     *
     * Reads and unconfirmed previews are invalidated. A batch already in Saving
     * is allowed to finish exactly once because SharedPreferences cannot undo it.
     */
    @Override
    protected void onDestroy() {
        activityDestroyed = true;
        profileTransferGeneration++;
        if (profileImportCoordinator != null) {
            profileImportCoordinator.cancel();
        }
        dismissProfileTransferDialogs();
        if (profileFileExecutor != null) {
            // shutdown() lets already-issued writes finish; it does not interrupt
            // a provider mid-write and risk leaving a destination half-open.
            profileFileExecutor.shutdown();
        }
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkForAppUpdate();
    }

    private void checkForAppUpdate() {
        if (appUpdateManager == null || updateFlowStarted || updatePostponedThisSession) {
            return;
        }
        appUpdateManager.getAppUpdateInfo()
                .addOnSuccessListener(this::handleAppUpdateInfo);
    }

    private void handleAppUpdateInfo(AppUpdateInfo info) {
        if (updatePostponedThisSession) {
            return;
        }
        if (info.installStatus() == InstallStatus.DOWNLOADED) {
            promptToCompleteUpdate();
            return;
        }

        if (info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
            // Resume an update the user already accepted before leaving the app.
            if (info.isUpdateTypeAllowed(AppUpdateOptions.defaultOptions(AppUpdateType.IMMEDIATE))) {
                startAppUpdate(info, AppUpdateType.IMMEDIATE);
            } else if (info.isUpdateTypeAllowed(AppUpdateOptions.defaultOptions(AppUpdateType.FLEXIBLE))) {
                startAppUpdate(info, AppUpdateType.FLEXIBLE);
            }
            return;
        }

        if (info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE) {
            return;
        }

        // Flexible updates keep the timer usable while Play downloads the new build.
        if (info.isUpdateTypeAllowed(AppUpdateOptions.defaultOptions(AppUpdateType.FLEXIBLE))) {
            startAppUpdate(info, AppUpdateType.FLEXIBLE);
        } else if (info.isUpdateTypeAllowed(AppUpdateOptions.defaultOptions(AppUpdateType.IMMEDIATE))) {
            startAppUpdate(info, AppUpdateType.IMMEDIATE);
        }
    }

    private void startAppUpdate(AppUpdateInfo info, int updateType) {
        if (updateFlowStarted || isFinishing() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1
                && isDestroyed())) {
            return;
        }
        try {
            updateFlowStarted = appUpdateManager.startUpdateFlowForResult(
                    info, this, AppUpdateOptions.defaultOptions(updateType), REQUEST_IN_APP_UPDATE);
        } catch (IntentSender.SendIntentException ignored) {
            updateFlowStarted = false;
        }
    }

    private void promptToCompleteUpdate() {
        if (updateReadyDialogShown || isFinishing()
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed())) {
            return;
        }
        updateReadyDialogShown = true;
        new AlertDialog.Builder(this)
                .setTitle("Update ready")
                .setMessage("A newer version of Multi Timer is downloaded and ready to install.")
                .setNegativeButton("Later", (dialog, which) -> {
                    updateReadyDialogShown = false;
                    updatePostponedThisSession = true;
                })
                .setPositiveButton("Restart now", (dialog, which) -> {
                    updateReadyDialogShown = false;
                    appUpdateManager.completeUpdate();
                })
                .setOnCancelListener(dialog -> {
                    updateReadyDialogShown = false;
                    updatePostponedThisSession = true;
                })
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_IMPORT_PROFILES) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                beginProfileImport(data.getData());
            }
            return;
        }
        if (requestCode == REQUEST_EXPORT_PROFILES) {
            finishExportDocumentSelection(resultCode, data);
            return;
        }
        if (requestCode == REQUEST_IN_APP_UPDATE) {
            updateFlowStarted = false;
        }
    }

    private View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_PAPER);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(18), dp(12), dp(12));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("Multi Timer", 28, COLOR_INK, Typeface.BOLD);
        TextView version = text("v" + BuildConfig.VERSION_NAME + " (build "
                + BuildConfig.VERSION_CODE + ")", 12, COLOR_MUTED, Typeface.NORMAL);
        version.setContentDescription("App version " + BuildConfig.VERSION_NAME
                + ", build " + BuildConfig.VERSION_CODE);
        summary = text("No clocks yet", 13, COLOR_MUTED, Typeface.NORMAL);
        titles.addView(title);
        titles.addView(version, marginParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 1, 0, 0));
        titles.addView(summary, marginParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 2, 0, 0));
        header.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout headerActions = new LinearLayout(this);
        headerActions.setOrientation(LinearLayout.VERTICAL);
        Button profilesButton = secondaryButton("Reusable profiles");
        profilesButton.setContentDescription("Manage beep profiles");
        profilesButton.setOnClickListener(v -> showProfilesDialog());
        headerActions.addView(profilesButton);
        Button diagnosticsButton = textButton("Export diagnostics", COLOR_ACCENT);
        diagnosticsButton.setContentDescription("Export timer diagnostics as a ZIP report");
        diagnosticsButton.setOnClickListener(v -> exportDiagnostics(diagnosticsButton));
        headerActions.addView(diagnosticsButton);
        header.addView(headerActions);
        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        clockList = new LinearLayout(this);
        clockList.setOrientation(LinearLayout.VERTICAL);
        clockList.setPadding(dp(16), dp(4), dp(16), dp(24));
        scroll.addView(clockList, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        HorizontalScrollView addScroller = new HorizontalScrollView(this);
        addScroller.setHorizontalScrollBarEnabled(false);
        addScroller.setBackgroundColor(COLOR_CARD);
        LinearLayout addBar = new LinearLayout(this);
        addBar.setOrientation(LinearLayout.HORIZONTAL);
        addBar.setGravity(Gravity.CENTER);
        addBar.setPadding(dp(12), dp(10), dp(12), dp(12));
        addBar.addView(addButton("+ Stopwatch", ClockInstance.Type.STOPWATCH));
        addBar.addView(addButton("+ Timer", ClockInstance.Type.TIMER),
                marginParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 8, 0, 0, 0));
        addBar.addView(addButton("+ Countdown", ClockInstance.Type.COUNTDOWN),
                marginParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 8, 0, 0, 0));
        addScroller.addView(addBar, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(addScroller);
        return root;
    }

    /**
     * Reads and packages diagnostics off the UI thread so a large retained ledger cannot freeze
     * clock controls. The share sheet grants the selected destination access to one archive.
     */
    private void exportDiagnostics(Button button) {
        if (diagnosticExportInProgress) {
            return;
        }
        diagnosticExportInProgress = true;
        button.setEnabled(false);
        new Thread(() -> {
            Uri archive;
            try {
                archive = DiagnosticExporter.export(getApplicationContext(), store);
            } catch (IOException | RuntimeException error) {
                handler.post(() -> {
                    diagnosticExportInProgress = false;
                    button.setEnabled(true);
                    if (!isDestroyed()) {
                        Toast.makeText(this, "Could not export diagnostics: "
                                + error.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
                return;
            }
            handler.post(() -> {
                diagnosticExportInProgress = false;
                button.setEnabled(true);
                if (isDestroyed()) {
                    return;
                }
                Intent share = new Intent(Intent.ACTION_SEND)
                        .setType("application/zip")
                        .putExtra(Intent.EXTRA_STREAM, archive)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                share.setClipData(ClipData.newUri(getContentResolver(),
                        "MultiTimer diagnostics", archive));
                try {
                    startActivity(Intent.createChooser(share, "Share diagnostics"));
                } catch (RuntimeException error) {
                    Toast.makeText(this, "No app available to share diagnostics",
                            Toast.LENGTH_LONG).show();
                }
            });
        }, "diagnostic-export").start();
    }

    private Button addButton(String label, ClockInstance.Type type) {
        Button button = primaryButton(label);
        button.setOnClickListener(v -> showCreateClockDialog(type));
        return button;
    }

    private void reloadClocks() {
        clocks = store.loadClockInstances();
        long nowWallTimeMillis = System.currentTimeMillis();
        long nowElapsedRealtimeMillis = SystemClock.elapsedRealtime();
        for (ClockInstance clock : clocks) {
            if (clock != null) {
                clock.rebaseMonotonicAnchor(nowWallTimeMillis, nowElapsedRealtimeMillis);
            }
        }
        renderClockList();
    }

    private void renderClockList() {
        clockList.removeAllViews();
        cards.clear();
        if (clocks.isEmpty()) {
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(20), dp(72), dp(20), dp(40));
            TextView icon = text("00:00", 40, COLOR_ACCENT, Typeface.BOLD);
            icon.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            empty.addView(icon);
            TextView heading = text("Ready when you are", 20, COLOR_INK, Typeface.BOLD);
            empty.addView(heading, marginParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0, 14, 0, 0));
            TextView help = text("Add a stopwatch, duration timer, or date countdown below.",
                    14, COLOR_MUTED, Typeface.NORMAL);
            help.setGravity(Gravity.CENTER);
            empty.addView(help, marginParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT,
                    0, 8, 0, 0));
            clockList.addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            for (ClockInstance clock : clocks) {
                View card = buildClockCard(clock);
                clockList.addView(card, marginParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 8));
            }
        }
        updateSummary();
        updateClockDisplays();
    }

    private View buildClockCard(ClockInstance clock) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(14));
        card.setBackground(roundRect(COLOR_CARD, 16, COLOR_BORDER, 1));
        card.setElevation(dp(1));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView kind = pill(typeLabel(clock.type), COLOR_ACCENT_SOFT, COLOR_ACCENT);
        top.addView(kind);
        TextView profile = text(profileNames(clock.attachedProfileIds()), 12,
                COLOR_MUTED, Typeface.NORMAL);
        profile.setGravity(Gravity.END);
        top.addView(profile, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(top);

        TextView name = text(clock.name, 19, COLOR_INK, Typeface.BOLD);
        name.setMaxLines(2);
        card.addView(name, marginParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 12, 0, 0));

        TextView time = text("00:00:00.0", 34, COLOR_INK, Typeface.NORMAL);
        time.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        time.setSingleLine(true);
        time.setContentDescription(clock.name + " time");
        card.addView(time, marginParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 0));

        TextView detail = text(clockDetail(clock), 12, COLOR_MUTED, Typeface.NORMAL);
        card.addView(detail, marginParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 2, 0, 0));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(14), 0, 0);
        Button startPause = primaryButton(clock.running ? "Pause" : "Start");
        startPause.setOnClickListener(v -> toggleClock(clock.id));
        actions.addView(startPause, new LinearLayout.LayoutParams(0, dp(44), 1.25f));

        Button reset = secondaryButton("Reset");
        reset.setOnClickListener(v -> resetClock(clock.id));
        actions.addView(reset, weightedParams(1f, 8));

        Button edit = secondaryButton("Edit");
        edit.setOnClickListener(v -> showEditClockDialog(clock.id));
        edit.setContentDescription("Edit " + clock.name);
        actions.addView(edit, weightedParams(0.9f, 8));

        Button delete = textButton("Delete", COLOR_DANGER);
        delete.setOnClickListener(v -> confirmDeleteClock(clock.id));
        actions.addView(delete, weightedParams(1f, 4));
        card.addView(actions);

        cards.put(clock.id, new ClockCard(time, detail, startPause));
        return card;
    }

    private void updateClockDisplays() {
        long nowWallTimeMillis = System.currentTimeMillis();
        long nowElapsedRealtimeMillis = SystemClock.elapsedRealtime();
        for (ClockInstance clock : clocks) {
            ClockCard refs = cards.get(clock.id);
            if (refs == null) {
                continue;
            }
            refs.time.setText(TimeUtils.formatDisplay(
                    clock.valueAt(nowWallTimeMillis, nowElapsedRealtimeMillis)));
            refs.detail.setText(clockDetail(clock));
            ClockActionPolicy.Action action = ClockActionPolicy.actionFor(clock);
            refs.startPause.setText(action == ClockActionPolicy.Action.PAUSE
                    ? "Pause" : (action == ClockActionPolicy.Action.UNAVAILABLE
                            ? "Finished" : "Start"));
            refs.startPause.setEnabled(action != ClockActionPolicy.Action.UNAVAILABLE);
            refs.startPause.setAlpha(refs.startPause.isEnabled() ? 1f : 0.55f);
        }
        updateSummary();
    }

    private void updateSummary() {
        int running = 0;
        for (ClockInstance clock : clocks) {
            if (clock.running) {
                running++;
            }
        }
        if (clocks.isEmpty()) {
            summary.setText("No clocks yet");
        } else if (running == 0) {
            summary.setText(clocks.size() + (clocks.size() == 1 ? " clock" : " clocks") + " • all paused");
        } else {
            summary.setText(running + " running • " + clocks.size() + " total");
        }
    }

    private String clockDetail(ClockInstance clock) {
        if (clock.completed) {
            return "Finished • Reset to run again";
        }
        String state = clock.running ? "Running" : "Paused";
        if (clock.type == ClockInstance.Type.COUNTDOWN) {
            return state + " • Target " + targetDateFormat.format(clock.configuredMillis);
        }
        if (clock.type == ClockInstance.Type.TIMER) {
            return state + " • Set for " + compactDuration(clock.configuredMillis);
        }
        return state;
    }

    private void showCreateClockDialog(ClockInstance.Type type) {
        LinearLayout panel = dialogPanel();
        EditText name = field("Name", defaultClockName(type));
        panel.addView(label("Name"));
        panel.addView(name);

        DurationInputView duration = null;
        Button targetButton = null;
        final long[] targetMillis = {System.currentTimeMillis() + 60L * 60L * 1000L};
        if (type == ClockInstance.Type.TIMER) {
            panel.addView(label("Duration"), topMargin(12));
            duration = new DurationInputView(this);
            panel.addView(duration);
            panel.addView(durationEntryHelp(), topMargin(4));
        } else if (type == ClockInstance.Type.COUNTDOWN) {
            panel.addView(label("Target date and time"), topMargin(12));
            targetButton = secondaryButton(targetDateFormat.format(targetMillis[0]));
            Button finalTargetButton = targetButton;
            targetButton.setOnClickListener(v -> chooseTarget(targetMillis, finalTargetButton));
            panel.addView(targetButton, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        }

        String draftOwnerId = UUID.randomUUID().toString();
        List<String> draftProfileIds = new ArrayList<>();
        List<BeepProfile> profiles = store.loadReusableProfiles();
        List<BeepProfile> customProfiles = new ArrayList<>();
        panel.addView(label("Reusable profiles (available to all timers)"), topMargin(12));
        LinearLayout profileChoices = profileChoices(profiles, new ArrayList<>());
        LinearLayout customProfileChoices = profileChoices(customProfiles, new ArrayList<>());
        panel.addView(profileChoices);
        addNewProfileShortcut(
                panel, profiles, customProfiles, profileChoices, customProfileChoices,
                draftProfileIds);
        panel.addView(label("This timer's private profiles (only this timer)"), topMargin(10));
        panel.addView(customProfileChoices);
        populateProfileChoices(customProfileChoices, customProfiles, new ArrayList<>(), true,
                profile -> deleteCustomProfile(
                        profile, profiles, customProfiles, profileChoices,
                        customProfileChoices,
                        selectedProfileIds(profiles, profileChoices, customProfiles,
                                customProfileChoices),
                        draftProfileIds));
        addPrivateProfileShortcuts(
                panel, profiles, customProfiles, profileChoices, customProfileChoices,
                draftOwnerId, draftProfileIds);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("New " + typeLabel(type).toLowerCase(Locale.getDefault()))
                .setView(wrapDialogPanel(panel))
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Add", null)
                .create();
        DurationInputView finalDuration = duration;
        final boolean[] committed = {false};
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String clockName = name.getText().toString().trim();
                    if (clockName.isEmpty()) {
                        name.setError("Enter a name");
                        return;
                    }
                    long configured = 0L;
                    if (type == ClockInstance.Type.TIMER) {
                        try {
                            configured = finalDuration.getDurationMillis();
                        } catch (IllegalArgumentException error) {
                            finalDuration.showError(error.getMessage());
                            return;
                        }
                    } else if (type == ClockInstance.Type.COUNTDOWN) {
                        configured = targetMillis[0];
                        if (configured <= System.currentTimeMillis()) {
                            Toast.makeText(this, "Choose a future target", Toast.LENGTH_SHORT).show();
                            return;
                        }
                    }
                    List<String> selectedProfileIds = selectedProfileIds(
                            profiles, profileChoices, customProfiles, customProfileChoices);
                    long nowWallTimeMillis = System.currentTimeMillis();
                    ClockInstance clock = ClockInstance.create(
                            type,
                            clockName,
                            configured,
                            selectedProfileIds.isEmpty() ? null : selectedProfileIds.get(0),
                            nowWallTimeMillis,
                            SystemClock.elapsedRealtime());
                    clock.setAttachedProfileIds(selectedProfileIds);
                    commitDraftProfiles(draftOwnerId, clock.id, selectedProfileIds);
                    clocks.add(clock);
                    saveAndSync();
                    renderClockList();
                    committed[0] = true;
                    dialog.dismiss();
                }));
        dialog.setOnDismissListener(ignored -> {
            if (!committed[0]) {
                store.deleteProfilesOwnedBy(draftOwnerId);
            }
        });
        dialog.show();
    }

    private void chooseTarget(long[] targetMillis, Button targetButton) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(targetMillis[0]);
        DatePickerDialog dateDialog = new DatePickerDialog(this,
                (datePicker, year, month, day) -> {
                    Calendar chosen = Calendar.getInstance();
                    chosen.setTimeInMillis(targetMillis[0]);
                    chosen.set(Calendar.YEAR, year);
                    chosen.set(Calendar.MONTH, month);
                    chosen.set(Calendar.DAY_OF_MONTH, day);
                    TimePickerDialog timeDialog = new TimePickerDialog(this,
                            (timePicker, hour, minute) -> {
                                chosen.set(Calendar.HOUR_OF_DAY, hour);
                                chosen.set(Calendar.MINUTE, minute);
                                chosen.set(Calendar.SECOND, 0);
                                chosen.set(Calendar.MILLISECOND, 0);
                                targetMillis[0] = chosen.getTimeInMillis();
                                targetButton.setText(targetDateFormat.format(targetMillis[0]));
                            }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE),
                            android.text.format.DateFormat.is24HourFormat(this));
                    timeDialog.show();
                }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH));
        dateDialog.show();
    }

    private void toggleClock(String id) {
        ClockInstance clock = findClock(id);
        if (clock == null) {
            return;
        }
        long nowWallTimeMillis = System.currentTimeMillis();
        long nowElapsedRealtimeMillis = SystemClock.elapsedRealtime();
        ClockActionPolicy.Action action = ClockActionPolicy.actionFor(clock);
        if (action == ClockActionPolicy.Action.UNAVAILABLE) {
            return;
        }
        if (action == ClockActionPolicy.Action.PAUSE) {
            clock.pause(nowWallTimeMillis, nowElapsedRealtimeMillis);
        } else {
            clock.start(nowWallTimeMillis, nowElapsedRealtimeMillis);
        }
        saveAndSync();
        renderClockList();
    }

    private void resetClock(String id) {
        ClockInstance clock = findClock(id);
        if (clock == null) {
            return;
        }
        clock.reset(System.currentTimeMillis(), SystemClock.elapsedRealtime());
        saveAndSync();
        renderClockList();
    }

    private void confirmDeleteClock(String id) {
        ClockInstance clock = findClock(id);
        if (clock == null) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Delete " + clock.name + "?")
                .setMessage("This removes the clock. Its reusable beep profile is kept.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> {
                    clocks.remove(clock);
                    store.deleteProfilesOwnedBy(clock.id);
                    saveAndSync();
                    renderClockList();
                })
                .show();
    }

    private void showEditClockDialog(String clockId) {
        ClockInstance clock = findClock(clockId);
        if (clock == null) {
            return;
        }

        LinearLayout panel = dialogPanel();
        EditText name = field("Name", clock.name);
        panel.addView(label("Name"));
        panel.addView(name);

        DurationInputView duration = null;
        Button targetButton = null;
        final long[] targetMillis = {clock.configuredMillis};
        if (clock.type == ClockInstance.Type.TIMER) {
            panel.addView(label("Duration"), topMargin(12));
            duration = new DurationInputView(this);
            duration.setDurationMillis(clock.configuredMillis);
            panel.addView(duration);
            panel.addView(durationEntryHelp(), topMargin(4));
        } else if (clock.type == ClockInstance.Type.COUNTDOWN) {
            panel.addView(label("Target date and time"), topMargin(12));
            targetButton = secondaryButton(targetDateFormat.format(targetMillis[0]));
            Button finalTargetButton = targetButton;
            targetButton.setOnClickListener(v -> chooseTarget(targetMillis, finalTargetButton));
            panel.addView(targetButton, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        }

        if (clock.type != ClockInstance.Type.STOPWATCH) {
            TextView resetNote = text("Changing the time setting resets and pauses this clock.",
                    12, COLOR_MUTED, Typeface.NORMAL);
            panel.addView(resetNote, topMargin(5));
        }

        List<String> draftProfileIds = new ArrayList<>();
        List<BeepProfile> profiles = store.loadReusableProfiles();
        List<BeepProfile> customProfiles = customProfilesForClock(clock);
        List<String> attachedProfileIds = clock.attachedProfileIds();
        panel.addView(label("Reusable profiles (available to all timers)"), topMargin(12));
        LinearLayout profileChoices = profileChoices(profiles, attachedProfileIds);
        LinearLayout customProfileChoices = profileChoices(customProfiles, attachedProfileIds);
        panel.addView(profileChoices);
        addNewProfileShortcut(
                panel, profiles, customProfiles, profileChoices, customProfileChoices,
                draftProfileIds);
        panel.addView(label("This timer's private profiles (only this timer)"), topMargin(10));
        panel.addView(customProfileChoices);
        populateProfileChoices(customProfileChoices, customProfiles, attachedProfileIds, true,
                profile -> deleteCustomProfile(
                        profile, profiles, customProfiles, profileChoices,
                        customProfileChoices,
                        selectedProfileIds(profiles, profileChoices, customProfiles,
                                customProfileChoices),
                        draftProfileIds));
        addPrivateProfileShortcuts(
                panel, profiles, customProfiles, profileChoices, customProfileChoices,
                clock.id, draftProfileIds);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Edit " + typeLabel(clock.type).toLowerCase(Locale.getDefault()))
                .setView(wrapDialogPanel(panel))
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Reusable profiles", (ignored, which) -> showProfilesDialog())
                .setPositiveButton("Save", null)
                .create();
        DurationInputView finalDuration = duration;
        final boolean[] committed = {false};
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    ClockInstance current = findClock(clockId);
                    if (current == null) {
                        dialog.dismiss();
                        return;
                    }

                    String clockName = name.getText().toString().trim();
                    if (clockName.isEmpty()) {
                        name.setError("Enter a name");
                        return;
                    }

                    long configured = current.configuredMillis;
                    if (current.type == ClockInstance.Type.TIMER) {
                        try {
                            configured = finalDuration.getDurationMillis();
                        } catch (IllegalArgumentException error) {
                            finalDuration.showError(error.getMessage());
                            return;
                        }
                    } else if (current.type == ClockInstance.Type.COUNTDOWN) {
                        configured = targetMillis[0];
                    }

                    long nowWallTimeMillis = System.currentTimeMillis();
                    long nowElapsedRealtimeMillis = SystemClock.elapsedRealtime();
                    boolean configurationChanged = current.type != ClockInstance.Type.STOPWATCH
                            && configured != current.configuredMillis;
                    if (current.type == ClockInstance.Type.COUNTDOWN
                            && configurationChanged && configured <= nowWallTimeMillis) {
                        Toast.makeText(this, "Choose a future target", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    List<String> selectedProfileIds = selectedProfileIds(
                            profiles, profileChoices, customProfiles, customProfileChoices);
                    List<String> previousProfileIds = current.attachedProfileIds();
                    boolean profileChanged = !previousProfileIds.equals(selectedProfileIds);
                    current.name = clockName;
                    if (configurationChanged) {
                        current.reconfigure(
                                configured, nowWallTimeMillis, nowElapsedRealtimeMillis);
                    }
                    if (profileChanged) {
                        current.setAttachedProfileIds(selectedProfileIds);
                        // A newly attached schedule begins at the current clock position.
                        long currentCueElapsed = current.cueElapsedAt(
                                nowWallTimeMillis, nowElapsedRealtimeMillis);
                        for (String profileId : selectedProfileIds) {
                            if (!previousProfileIds.contains(profileId)) {
                                current.setCueElapsedForProfile(profileId, currentCueElapsed);
                            }
                        }
                        current.lastCueElapsedMillis = currentCueElapsed;
                        current.initialCuePending = false;
                        if (!current.completed) {
                            current.completionBeepPlayed = false;
                        }
                    }

                    store.deleteUnreferencedProfilesOwnedBy(current.id, selectedProfileIds);
                    saveAndSync();
                    renderClockList();
                    committed[0] = true;
                    dialog.dismiss();
                }));
        dialog.setOnDismissListener(ignored -> {
            if (!committed[0]) {
                for (String profileId : draftProfileIds) {
                    store.deleteProfile(profileId);
                }
            }
        });
        dialog.show();
    }

    private void showProfilesDialog() {
        AlertDialog previousDialog = profilesDialog;
        profilesDialog = null;
        if (previousDialog != null && previousDialog.isShowing()) {
            previousDialog.dismiss();
        }
        List<BeepProfile> profiles = store.loadReusableProfiles();
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(8), dp(4), dp(8), dp(4));

        content.addView(text(
                "Reusable profiles are templates available to any timer. "
                        + "For a one-timer variation, use Customize for this timer… "
                        + "inside that timer's editor.",
                13, COLOR_MUTED, Typeface.NORMAL), marginParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                8, 4, 8, 10));

        // File sharing stays beside the templates it operates on. Export begins
        // disabled so a file can never silently include every stored profile.
        Map<String, CheckBox> exportChoices = new LinkedHashMap<>();
        LinearLayout fileActions = new LinearLayout(this);
        fileActions.setGravity(Gravity.CENTER);
        Button importButton = secondaryButton("Import file");
        importButton.setOnClickListener(v -> {
            dialogDismissIfVisible();
            launchProfileImportPicker();
        });
        Button exportButton = secondaryButton("Export selected");
        exportButton.setEnabled(false);
        exportButton.setOnClickListener(v -> {
            List<SharedProfile> selectedProfiles = selectedProfilesForExport(
                    profiles, exportChoices);
            if (selectedProfiles == null) {
                return;
            }
            if (selectedProfiles.isEmpty()) {
                Toast.makeText(this, "Select at least one reusable profile to export.",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            dialogDismissIfVisible();
            beginProfileExport(selectedProfiles);
        });
        fileActions.addView(importButton, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams exportButtonParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        exportButtonParams.setMarginStart(dp(8));
        fileActions.addView(exportButton, exportButtonParams);
        content.addView(fileActions, marginParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                8, 0, 8, 10));

        if (profiles.isEmpty()) {
            TextView empty = text("No reusable profiles. Add one to schedule cues.",
                    14, COLOR_MUTED, Typeface.NORMAL);
            empty.setPadding(dp(12), dp(20), dp(12), dp(20));
            content.addView(empty);
        } else {
            for (BeepProfile profile : profiles) {
                content.addView(buildProfileRow(profile, exportChoices, exportButton), marginParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        0, 3, 0, 3));
            }
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        FrameLayout frame = new FrameLayout(this);
        frame.setPadding(dp(12), 0, dp(12), 0);
        frame.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(420)));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Reusable profiles")
                .setView(frame)
                .setNegativeButton("Close", null)
                .setPositiveButton("Add reusable profile", null)
                .create();
        profilesDialog = dialog;
        dialog.setOnDismissListener(ignored -> {
            if (profilesDialog == dialog) {
                profilesDialog = null;
            }
        });
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    dialog.dismiss();
                    showProfileEditor(null, null, "New reusable profile");
                }));
        dialog.show();
    }

    private View buildProfileRow(
            BeepProfile profile,
            Map<String, CheckBox> exportChoices,
            Button exportButton) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(12), dp(12), dp(10));
        row.setBackground(roundRect(Color.rgb(250, 251, 252), 10, COLOR_BORDER, 1));

        CheckBox exportChoice = new CheckBox(this);
        exportChoice.setText(profile.name);
        exportChoice.setTextSize(16);
        exportChoice.setTextColor(COLOR_INK);
        exportChoice.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        exportChoice.setContentDescription("Select " + profile.name + " to export");
        exportChoices.put(profile.id, exportChoice);
        exportChoice.setOnCheckedChangeListener((button, checked) ->
                exportButton.setEnabled(hasSelectedExportProfile(exportChoices)));
        row.addView(exportChoice);
        TextView detail = text(profileDescription(profile), 12, COLOR_MUTED, Typeface.NORMAL);
        row.addView(detail, topMargin(3));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        Button edit = textButton("Edit", COLOR_ACCENT);
        edit.setOnClickListener(v -> {
            if (profilesDialog != null) {
                profilesDialog.dismiss();
            }
            showProfileEditor(profile);
        });
        actions.addView(edit);
        Button duplicate = textButton("Duplicate", COLOR_ACCENT);
        duplicate.setOnClickListener(v -> duplicateProfile(profile));
        actions.addView(duplicate);
        Button delete = textButton("Delete", COLOR_DANGER);
        delete.setOnClickListener(v -> confirmDeleteProfile(profile));
        actions.addView(delete);
        row.addView(actions);
        return row;
    }

    /** Checks only rows the person explicitly selected for this export action. */
    private boolean hasSelectedExportProfile(Map<String, CheckBox> choices) {
        for (CheckBox choice : choices.values()) {
            if (choice.isChecked()) {
                return true;
            }
        }
        return false;
    }

    /** Makes detached, identity-free snapshots for exactly the checked reusable rows. */
    private List<SharedProfile> selectedProfilesForExport(
            List<BeepProfile> profiles, Map<String, CheckBox> choices) {
        List<SharedProfile> selected = new ArrayList<>();
        try {
            for (BeepProfile profile : profiles) {
                CheckBox choice = choices.get(profile.id);
                if (choice != null && choice.isChecked()) {
                    selected.add(SharedProfile.fromProfile(profile));
                }
            }
        } catch (IllegalArgumentException failure) {
            dialogDismissIfVisible();
            showProfileTransferError("Could not export profiles", failure.getMessage());
            return null;
        }
        return selected;
    }

    /** Closes the current template list before another modal flow is opened. */
    private void dialogDismissIfVisible() {
        AlertDialog currentDialog = profilesDialog;
        if (currentDialog != null && currentDialog.isShowing()) {
            currentDialog.dismiss();
        }
    }

    /** Opens the Android document picker; the app receives only the chosen URI grant. */
    private void launchProfileImportPicker() {
        Intent openDocument = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        openDocument.addCategory(Intent.CATEGORY_OPENABLE);
        openDocument.setType("application/json");
        try {
            startActivityForResult(openDocument, REQUEST_IMPORT_PROFILES);
        } catch (ActivityNotFoundException failure) {
            showProfileTransferError("Cannot open profile files",
                    "Android could not find a document picker.");
        }
    }

    /** Creates a v1 JSON file only from the user's checked reusable-profile rows. */
    private void beginProfileExport(List<SharedProfile> selectedProfiles) {
        final long generation = ++profileTransferGeneration;
        showProfileTransferProgress("Preparing profile file", "Encoding selected profiles…",
                false, null);
        try {
            profileFileExecutor().execute(() -> {
                byte[] encoded;
                try {
                    encoded = ProfileBundleCodec.encode(selectedProfiles);
                } catch (RuntimeException failure) {
                    postTransferResult(generation, () -> {
                        dismissProfileTransferDialogs();
                        showProfileTransferError("Could not export profiles",
                                readableTransferError(failure));
                    });
                    return;
                }

                postTransferResult(generation, () -> {
                    pendingExportBytes = encoded;
                    dismissProfileTransferDialogs();
                    Intent createDocument = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    createDocument.addCategory(Intent.CATEGORY_OPENABLE);
                    createDocument.setType("application/json");
                    createDocument.putExtra(Intent.EXTRA_TITLE, "multitimer-profiles.json");
                    try {
                        startActivityForResult(createDocument, REQUEST_EXPORT_PROFILES);
                    } catch (ActivityNotFoundException failure) {
                        pendingExportBytes = null;
                        showProfileTransferError("Cannot save profile file",
                                "Android could not find a document picker.");
                    }
                });
            });
        } catch (RejectedExecutionException failure) {
            dismissProfileTransferDialogs();
            showProfileTransferError("Could not export profiles", readableTransferError(failure));
        }
    }

    /** Reads one picked document on a worker, then asks the coordinator to validate it. */
    private void beginProfileImport(Uri documentUri) {
        if (profileImportCoordinator != null
                && profileImportCoordinator.getState() instanceof ProfileImportCoordinator.State.Saving) {
            showProfileTransferError("Import still in progress",
                    "Wait for the current profile import to finish.");
            return;
        }

        final long generation = ++profileTransferGeneration;
        profileImportCoordinator = null;
        displayedPreviewRequestId = -1L;
        profileImportInProgress = true;
        showProfileTransferProgress("Reading profile file", "Checking its size…", true,
                () -> cancelProfileImport(generation));

        try {
            profileFileExecutor().execute(() -> {
                byte[] documentBytes;
                try (InputStream input = getContentResolver().openInputStream(documentUri)) {
                    if (input == null) {
                        throw new IOException("The selected document provider returned no data stream");
                    }
                    // Do not trust OpenableColumns.SIZE or InputStream.available();
                    // the helper counts the actual bytes and rejects limit + 1.
                    documentBytes = ProfileBundleFileIO.readBounded(input);
                } catch (Exception failure) {
                    postTransferResult(generation, () -> failProfileImport(
                            "Could not read profile file", readableTransferError(failure)));
                    return;
                }

                postTransferResult(generation, () -> {
                    ProfileImportCoordinator coordinator = new ProfileImportCoordinator(
                            profileFileExecutor(),
                            batch -> {
                                store.appendReusableProfiles(batch);
                                return Unit.INSTANCE;
                            },
                            bytes -> ProfileBundleCodec.decode(bytes),
                            state -> {
                                // The coordinator may publish from its worker.
                                // The generation check below prevents an old
                                // Activity instance updating a replacement view.
                                handler.post(() -> renderProfileImportState(generation, state));
                                return Unit.INSTANCE;
                            });
                    profileImportCoordinator = coordinator;
                    coordinator.load(documentBytes);
                });
            });
        } catch (RejectedExecutionException failure) {
            failProfileImport("Could not read profile file", readableTransferError(failure));
        }
    }

    /** Opens the system save-as picker after the bounded JSON bytes are ready. */
    private void finishExportDocumentSelection(int resultCode, Intent result) {
        if (resultCode != RESULT_OK) {
            pendingExportBytes = null;
            Toast.makeText(this, "Export cancelled; no file was written.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        Uri destination = result == null ? null : result.getData();
        byte[] encoded = pendingExportBytes;
        pendingExportBytes = null;
        if (destination == null || encoded == null) {
            showProfileTransferError("Could not save profile file",
                    "Android did not return the selected destination.");
            return;
        }
        writeProfileExport(destination, encoded);
    }

    /** Writes the selected document and closes the provider stream on the worker. */
    private void writeProfileExport(Uri destination, byte[] documentBytes) {
        final long generation = ++profileTransferGeneration;
        showProfileTransferProgress("Saving profile file", "Writing the selected profiles…",
                false, null);
        try {
            profileFileExecutor().execute(() -> {
                try (OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                    if (output == null) {
                        throw new IOException("The selected document provider returned no output stream");
                    }
                    ProfileBundleFileIO.writeBounded(output, documentBytes);
                } catch (Exception failure) {
                    postTransferResult(generation, () -> {
                        dismissProfileTransferDialogs();
                        showProfileTransferError("Could not save profile file",
                                readableTransferError(failure)
                                        + " The destination may contain an incomplete file.");
                    });
                    return;
                }

                postTransferResult(generation, () -> {
                    dismissProfileTransferDialogs();
                    Toast.makeText(this, "Selected profiles exported.", Toast.LENGTH_LONG).show();
                });
            });
        } catch (RejectedExecutionException failure) {
            dismissProfileTransferDialogs();
            showProfileTransferError("Could not save profile file", readableTransferError(failure));
        }
    }

    /** Shows coordinator transitions only while they still describe current state. */
    private void renderProfileImportState(
            long generation, ProfileImportCoordinator.State state) {
        if (!isCurrentTransfer(generation) || profileImportCoordinator == null
                || !state.equals(profileImportCoordinator.getState())) {
            return;
        }
        if (state instanceof ProfileImportCoordinator.State.Loading) {
            showProfileTransferProgress("Reading profile file", "Validating every profile…",
                    true, () -> cancelProfileImport(generation));
        } else if (state instanceof ProfileImportCoordinator.State.Preview) {
            ProfileImportCoordinator.State.Preview preview =
                    (ProfileImportCoordinator.State.Preview) state;
            if (profilePreviewDialog == null
                    || displayedPreviewRequestId != preview.getRequestId()) {
                showProfileImportPreview(generation, preview);
            }
        } else if (state instanceof ProfileImportCoordinator.State.Saving) {
            showProfileTransferProgress("Importing profiles", "Saving the selected profiles…",
                    false, null);
        } else if (state instanceof ProfileImportCoordinator.State.Success) {
            ProfileImportCoordinator.State.Success success =
                    (ProfileImportCoordinator.State.Success) state;
            profileImportInProgress = false;
            displayedPreviewRequestId = -1L;
            dismissProfileTransferDialogs();
            String importedMessage = getResources().getQuantityString(
                    R.plurals.profile_imported_count,
                    success.getImportedCount(), success.getImportedCount());
            Toast.makeText(this, importedMessage, Toast.LENGTH_LONG).show();
            showProfilesDialog();
        } else if (state instanceof ProfileImportCoordinator.State.Error) {
            ProfileImportCoordinator.State.Error error =
                    (ProfileImportCoordinator.State.Error) state;
            profileImportInProgress = false;
            displayedPreviewRequestId = -1L;
            dismissProfileTransferDialogs();
            showProfileTransferError("Could not import profiles", error.getMessage());
        } else if (state instanceof ProfileImportCoordinator.State.Idle) {
            profileImportInProgress = false;
            displayedPreviewRequestId = -1L;
            dismissProfileTransferDialogs();
        }
    }

    /** Builds an explicit review step before any imported profile is persisted. */
    private void showProfileImportPreview(
            long generation, ProfileImportCoordinator.State.Preview preview) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(8), dp(4), dp(8), dp(4));
        content.addView(text("Review the profiles below. Nothing is saved until you confirm.",
                13, COLOR_MUTED, Typeface.NORMAL), marginParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                4, 0, 4, 8));

        List<CheckBox> choices = new ArrayList<>();
        for (int index = 0; index < preview.getProfiles().size(); index++) {
            SharedProfile profile = preview.getProfiles().get(index);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            row.setBackground(roundRect(Color.rgb(250, 251, 252), 8, COLOR_BORDER, 1));
            CheckBox choice = new CheckBox(this);
            choice.setText(profile.getName());
            choice.setTextColor(COLOR_INK);
            choice.setContentDescription("Import profile " + profile.getName());
            choice.setChecked(preview.getSelectedIndices().contains(index));
            choices.add(choice);
            row.addView(choice);
            row.addView(text(sharedProfileDescription(profile), 12, COLOR_MUTED, Typeface.NORMAL),
                    marginParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT, 4, 0, 4, 0));
            content.addView(row, marginParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0, 3, 0, 3));
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        FrameLayout frame = new FrameLayout(this);
        frame.setPadding(dp(8), 0, dp(8), 0);
        frame.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(380)));

        String importButtonText = getString(
                R.string.profile_import_confirm, preview.getSelectedCount());
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Preview profile import")
                .setView(frame)
                .setNegativeButton("Cancel", (ignored, which) -> cancelProfileImport(generation))
                .setPositiveButton(importButtonText, null)
                .create();
        profilePreviewDialog = dialog;
        displayedPreviewRequestId = preview.getRequestId();
        for (int index = 0; index < choices.size(); index++) {
            final int profileIndex = index;
            choices.get(index).setOnCheckedChangeListener((button, checked) -> {
                if (!isCurrentTransfer(generation) || profileImportCoordinator == null) {
                    return;
                }
                profileImportCoordinator.setSelected(profileIndex, checked);
                int selectedCount = selectedCount(choices);
                Button confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                confirm.setEnabled(selectedCount > 0);
                confirm.setText(getString(R.string.profile_import_confirm, selectedCount));
            });
        }
        dialog.setOnCancelListener(ignored -> cancelProfileImport(generation));
        dialog.setOnDismissListener(ignored -> {
            if (profilePreviewDialog == dialog) {
                profilePreviewDialog = null;
                displayedPreviewRequestId = -1L;
            }
        });
        dialog.setOnShowListener(ignored -> {
            Button confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            confirm.setEnabled(preview.getSelectedCount() > 0);
            confirm.setOnClickListener(view -> {
                if (!isCurrentTransfer(generation) || profileImportCoordinator == null) {
                    return;
                }
                if (profileImportCoordinator.confirm()) {
                    dialog.dismiss();
                    profilePreviewDialog = null;
                    displayedPreviewRequestId = -1L;
                    showProfileTransferProgress("Importing profiles",
                            "Saving the selected profiles…", false, null);
                }
            });
        });
        dialog.show();
    }

    /** Produces plain schedule information without implying the profile has been saved. */
    private String sharedProfileDescription(SharedProfile profile) {
        String schedule = profile.getMode() == BeepProfile.Mode.PERIODIC
                ? "Every " + compactDuration(profile.getPeriodMillis())
                : profile.getEvents().size() + (profile.getEvents().size() == 1
                ? " scripted cue" : " scripted cues");
        return schedule + " • " + SharedProfileDescription.actions(profile);
    }

    /** Returns the number of checked preview rows, preserving duplicate names as separate rows. */
    private int selectedCount(List<CheckBox> choices) {
        int count = 0;
        for (CheckBox choice : choices) {
            if (choice.isChecked()) {
                count++;
            }
        }
        return count;
    }

    /** Cancels only a read or preview; a persistent batch already in flight cannot be undone. */
    private void cancelProfileImport(long generation) {
        if (!isCurrentTransfer(generation)) {
            return;
        }
        if (profileImportCoordinator != null) {
            profileImportCoordinator.cancel();
        }
        profileTransferGeneration++;
        profileImportInProgress = false;
        displayedPreviewRequestId = -1L;
        dismissProfileTransferDialogs();
        Toast.makeText(this, "Import cancelled; no profiles were changed.",
                Toast.LENGTH_SHORT).show();
    }

    /** Converts a read/validation failure to a user-visible terminal state. */
    private void failProfileImport(String title, String message) {
        profileImportInProgress = false;
        displayedPreviewRequestId = -1L;
        dismissProfileTransferDialogs();
        showProfileTransferError(title, message);
    }

    /** Posts a worker result only if it still belongs to this live Activity operation. */
    private void postTransferResult(long generation, Runnable result) {
        handler.post(() -> {
            if (isCurrentTransfer(generation)) {
                result.run();
            }
        });
    }

    /** Rejects callbacks after the operation was replaced or the Activity was destroyed. */
    private boolean isCurrentTransfer(long generation) {
        return !activityDestroyed && !isFinishing()
                && generation == profileTransferGeneration;
    }

    /** Lazily owns one background thread for bounded document I/O and codec work. */
    private ExecutorService profileFileExecutor() {
        if (profileFileExecutor == null || profileFileExecutor.isShutdown()) {
            profileFileExecutor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "profile-file-exchange");
                thread.setDaemon(true);
                return thread;
            });
        }
        return profileFileExecutor;
    }

    /** Shows cancellable progress only while no persistent write has started. */
    private void showProfileTransferProgress(
            String title, String message, boolean cancellable, Runnable cancelAction) {
        dismissProgressDialog();
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(cancellable);
        if (cancellable && cancelAction != null) {
            builder.setNegativeButton("Cancel", (ignored, which) -> cancelAction.run());
        }
        profileTransferDialog = builder.create();
        if (cancellable && cancelAction != null) {
            profileTransferDialog.setOnCancelListener(ignored -> cancelAction.run());
        }
        profileTransferDialog.show();
    }

    /** Dismisses transient file-progress and preview dialogs without changing stored data. */
    private void dismissProfileTransferDialogs() {
        dismissProgressDialog();
        AlertDialog preview = profilePreviewDialog;
        profilePreviewDialog = null;
        displayedPreviewRequestId = -1L;
        if (preview != null && preview.isShowing()) {
            preview.setOnCancelListener(null);
            preview.dismiss();
        }
    }

    /** Dismisses the progress dialog when another import/export state replaces it. */
    private void dismissProgressDialog() {
        AlertDialog progress = profileTransferDialog;
        profileTransferDialog = null;
        if (progress != null && progress.isShowing()) {
            progress.setOnCancelListener(null);
            progress.dismiss();
        }
    }

    /** Shows a bounded, readable failure instead of silently losing a provider error. */
    private void showProfileTransferError(String title, String message) {
        String detail = message == null || message.trim().isEmpty()
                ? "The document operation could not be completed."
                : message;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(detail)
                .setPositiveButton("OK", null)
                .show();
    }

    /** Replaces empty platform exceptions with concise text a user can act on. */
    private String readableTransferError(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.trim().isEmpty()
                ? "The document provider could not complete the operation."
                : message;
    }

    /** Creates an independent profile for a timer-specific variation. */
    private void duplicateProfile(BeepProfile profile) {
        String baseName = profile.name + " copy";
        BeepProfile copy = profile.copyAs(baseName);
        store.putProfile(copy);
        syncService();
        refreshProfilesDialog();
    }

    /** Reopens the reusable-profile list after a row action has changed its contents. */
    private void refreshProfilesDialog() {
        AlertDialog currentDialog = profilesDialog;
        profilesDialog = null;
        if (currentDialog != null && currentDialog.isShowing()) {
            currentDialog.dismiss();
        }
        handler.post(this::showProfilesDialog);
    }

    private void showProfileEditor(BeepProfile existing) {
        showProfileEditor(existing, null);
    }

    /** Opens the profile editor and optionally returns the saved profile to a parent form. */
    private void showProfileEditor(BeepProfile existing, ProfileSavedListener savedListener) {
        showProfileEditor(existing, savedListener, null);
    }

    /** Opens the profile editor with an optional context-specific title. */
    private void showProfileEditor(
            BeepProfile existing, ProfileSavedListener savedListener, String titleOverride) {
        LinearLayout panel = dialogPanel();
        EditText name = field("Profile name", existing == null ? "Interval cue" : existing.name);
        panel.addView(label("Name"));
        panel.addView(name);

        panel.addView(label("Schedule"), topMargin(12));
        Spinner mode = new Spinner(this);
        mode.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Fixed period (one repeating cue)",
                        "Scripted entries (many arbitrary cues)"}));
        if (existing != null && existing.mode == BeepProfile.Mode.SCRIPTED) {
            mode.setSelection(1);
        }
        panel.addView(mode, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        TextView beepLengthLabel = label("Beep length (milliseconds; used only when beep is enabled)");
        EditText beepLength = field("500", String.valueOf(existing == null
                ? 500L : existing.beepDurationMillis));
        beepLength.setInputType(InputType.TYPE_CLASS_NUMBER);

        LinearLayout periodicFields = new LinearLayout(this);
        periodicFields.setOrientation(LinearLayout.VERTICAL);
        periodicFields.addView(label("Repeat every"));
        DurationInputView period = new DurationInputView(this);
        period.setDurationMillis(existing == null || existing.periodMillis <= 0L
                ? 60_000L : existing.periodMillis);
        periodicFields.addView(period);
        periodicFields.addView(durationEntryHelp(), topMargin(4));
        panel.addView(periodicFields, topMargin(12));

        LinearLayout scriptedFields = new LinearLayout(this);
        scriptedFields.setOrientation(LinearLayout.VERTICAL);
        scriptedFields.addView(label("Cue script (any number of cues)"));
        String existingScript = existing == null || existing.events == null
                ? "" : CueScriptParser.format(existing.events);
        EditText script = field("00:00 | say \"Start\" | beep\n00:30 | say \"Rest\"",
                existingScript);
        script.setMinLines(3);
        script.setGravity(Gravity.TOP | Gravity.START);
        script.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        scriptedFields.addView(script, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(96)));
        scriptedFields.addView(text(
                "Each row is one cue: TIME | say \"text\" | beep [milliseconds]. "
                        + "TOML [[event]] rows are also accepted.",
                12, COLOR_MUTED, Typeface.NORMAL), topMargin(4));
        panel.addView(scriptedFields, topMargin(12));

        panel.addView(label("Actions"), topMargin(10));
        panel.addView(text(
                "Choose whether this profile beeps, speaks, or does both. "
                        + "The detailed settings appear below.",
                12, COLOR_MUTED, Typeface.NORMAL), topMargin(2));

        CheckBox beepActions = new CheckBox(this);
        beepActions.setText("Beep");
        beepActions.setTextColor(COLOR_INK);
        beepActions.setChecked(existing == null
                || existing.beepOnCue || existing.beepOnCompletion);
        panel.addView(beepActions, topMargin(6));

        CheckBox speechActions = new CheckBox(this);
        speechActions.setText("Speak");
        speechActions.setTextColor(COLOR_INK);
        speechActions.setChecked(existing != null && hasSpeechAction(existing));
        panel.addView(speechActions);

        CheckBox cueBeep = new CheckBox(this);
        cueBeep.setText("Beep at each scheduled cue");
        cueBeep.setTextColor(COLOR_INK);
        cueBeep.setChecked(existing == null || existing.beepOnCue);

        CheckBox completion = new CheckBox(this);
        completion.setText("Also beep when a timer or countdown finishes");
        completion.setTextColor(COLOR_INK);
        completion.setChecked(existing == null || existing.beepOnCompletion);

        LinearLayout beepControls = new LinearLayout(this);
        beepControls.setOrientation(LinearLayout.VERTICAL);
        beepControls.addView(label("Beep settings"), topMargin(6));
        beepControls.addView(beepLengthLabel);
        beepControls.addView(beepLength);
        beepControls.addView(cueBeep, topMargin(6));
        beepControls.addView(completion);
        panel.addView(beepControls, topMargin(2));

        Spinner spokenTime = new Spinner(this);
        spokenTime.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"None", "Speak current time", "Speak elapsed time",
                        "Speak time remaining"}));
        spokenTime.setSelection(spokenTimeSelection(existing));

        CheckBox spokenClockName = new CheckBox(this);
        spokenClockName.setText("Speak the clock name with spoken cues");
        spokenClockName.setTextColor(COLOR_INK);
        spokenClockName.setChecked(existing != null && existing.speakClockNameWithSpeech);

        CheckBox spokenCompletion = new CheckBox(this);
        spokenCompletion.setText("Speak when a timer or countdown finishes");
        spokenCompletion.setTextColor(COLOR_INK);
        spokenCompletion.setChecked(existing != null && existing.speakOnCompletion);

        LinearLayout speechControls = new LinearLayout(this);
        speechControls.setOrientation(LinearLayout.VERTICAL);
        speechControls.addView(label("Speech settings"), topMargin(6));
        speechControls.addView(label("Speech at each scheduled cue"), topMargin(4));
        speechControls.addView(spokenTime, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        speechControls.addView(text(
                "Current time means the phone's wall-clock time. Remaining applies to timers "
                        + "and date countdowns; elapsed applies to any clock.",
                12, COLOR_MUTED, Typeface.NORMAL), topMargin(4));
        speechControls.addView(spokenClockName, topMargin(6));
        speechControls.addView(text(
                "Off by default: spoken cues omit the clock name. Turn this on when "
                        + "overlapping clocks need to identify themselves.",
                12, COLOR_MUTED, Typeface.NORMAL), topMargin(2));
        speechControls.addView(label("When the clock finishes"), topMargin(6));
        speechControls.addView(spokenCompletion);
        panel.addView(speechControls, topMargin(2));

        Runnable updateActionControls = () -> {
            beepControls.setVisibility(beepActions.isChecked() ? View.VISIBLE : View.GONE);
            speechControls.setVisibility(speechActions.isChecked() ? View.VISIBLE : View.GONE);
        };
        beepActions.setOnCheckedChangeListener((button, checked) -> updateActionControls.run());
        speechActions.setOnCheckedChangeListener((button, checked) -> updateActionControls.run());
        updateActionControls.run();

        Runnable updateMode = () -> {
            boolean periodic = mode.getSelectedItemPosition() == 0;
            periodicFields.setVisibility(periodic ? View.VISIBLE : View.GONE);
            scriptedFields.setVisibility(periodic ? View.GONE : View.VISIBLE);
        };
        mode.setOnItemSelectedListener(new SimpleItemSelectedListener(updateMode));
        updateMode.run();

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(titleOverride == null
                        ? (savedListener == null
                        ? (existing == null ? "New reusable profile" : "Edit reusable profile")
                        : (existing == null ? "New profile" : "Edit profile"))
                        : titleOverride)
                .setView(wrapDialogPanel(panel))
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String profileName = name.getText().toString().trim();
                    if (profileName.isEmpty()) {
                        name.setError("Enter a name");
                        return;
                    }
                    long beepMillis;
                    try {
                        beepMillis = Long.parseLong(beepLength.getText().toString().trim());
                        if (beepMillis < 50L || beepMillis > 10_000L) {
                            throw new IllegalArgumentException("Use 50 to 10,000 milliseconds");
                        }
                    } catch (NumberFormatException error) {
                        beepLength.setError("Enter milliseconds from 50 to 10,000");
                        return;
                    } catch (IllegalArgumentException error) {
                        beepLength.setError(error.getMessage());
                        return;
                    }

                    BeepProfile.Mode selectedMode = mode.getSelectedItemPosition() == 0
                            ? BeepProfile.Mode.PERIODIC : BeepProfile.Mode.SCRIPTED;
                    long periodMillis = 0L;
                    List<Long> offsets = new ArrayList<>();
                    List<CueEvent> events = new ArrayList<>();
                    try {
                        if (selectedMode == BeepProfile.Mode.PERIODIC) {
                            periodMillis = period.getDurationMillis();
                        } else {
                            events = CueScriptParser.parse(script.getText().toString(), beepMillis);
                            if (events.isEmpty()
                                    && !(beepActions.isChecked() && completion.isChecked())
                                    && !(speechActions.isChecked() && spokenCompletion.isChecked())) {
                                throw new IllegalArgumentException(
                                        "Add an event or enable a completion action");
                            }
                        }
                    } catch (IllegalArgumentException error) {
                        if (selectedMode == BeepProfile.Mode.PERIODIC) {
                            period.showError(error.getMessage());
                        } else {
                            script.setError(error.getMessage());
                        }
                        return;
                    }

                    BeepProfile profile = existing == null
                            ? (selectedMode == BeepProfile.Mode.PERIODIC
                            ? BeepProfile.periodic(profileName, beepMillis, periodMillis,
                            beepActions.isChecked() && completion.isChecked())
                            : BeepProfile.scriptedEvents(profileName, beepMillis, events,
                            beepActions.isChecked() && completion.isChecked()))
                            : existing;
                    profile.name = profileName;
                    profile.mode = selectedMode;
                    profile.beepDurationMillis = beepMillis;
                    profile.periodMillis = periodMillis;
                    profile.events = selectedMode == BeepProfile.Mode.SCRIPTED
                            ? events : new ArrayList<>();
                    profile.scriptOffsetsMillis = selectedMode == BeepProfile.Mode.SCRIPTED
                            ? eventOffsets(events) : offsets;
                    profile.beepOnCue = beepActions.isChecked() && cueBeep.isChecked();
                    profile.beepOnCompletion = beepActions.isChecked() && completion.isChecked();
                    profile.speakOnCue = speechActions.isChecked();
                    profile.speakOnCompletion = speechActions.isChecked()
                            && spokenCompletion.isChecked();
                    int selectedSpokenTime = spokenTime.getSelectedItemPosition();
                    profile.speakCurrentTime = speechActions.isChecked() && selectedSpokenTime == 1;
                    profile.speakElapsedTime = speechActions.isChecked() && selectedSpokenTime == 2;
                    profile.speakRemainingTime = speechActions.isChecked()
                            && selectedSpokenTime == 3;
                    profile.speakClockNameWithSpeech = speechActions.isChecked()
                            && spokenClockName.isChecked();
                    if (!profile.hasConfiguredAudioAction()) {
                        Toast.makeText(
                                this,
                                "Enable at least one beep or speech action",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    try {
                        profile.validate();
                    } catch (IllegalArgumentException error) {
                        Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
                        return;
                    }
                    store.putProfile(profile);
                    syncService();
                    dialog.dismiss();
                    if (savedListener == null) {
                        showProfilesDialog();
                    } else {
                        savedListener.onSaved(profile);
                    }
                }));
        dialog.show();
    }

    /** Adds the profile creation shortcut used by new-clock forms. */
    private void addNewProfileShortcut(
            LinearLayout panel,
            List<BeepProfile> profiles,
            List<BeepProfile> customProfiles,
            LinearLayout profileChoices,
            LinearLayout customProfileChoices,
            List<String> draftProfileIds) {
        Button newProfile = textButton("Create reusable profile…", COLOR_ACCENT);
        newProfile.setGravity(Gravity.CENTER);
        newProfile.setOnClickListener(v -> {
            List<String> selected = selectedProfileIds(
                    profiles, profileChoices, customProfiles, customProfileChoices);
            showProfileEditor(null, created -> {
                profiles.clear();
                profiles.addAll(store.loadReusableProfiles());
                selected.add(created.id);
                populateProfileChoices(profileChoices, profiles, selected, false, null);
                populateProfileChoices(customProfileChoices, customProfiles, selected, true,
                        profile -> deleteCustomProfile(
                                profile, profiles, customProfiles, profileChoices,
                                customProfileChoices, selected, draftProfileIds));
            }, "Create reusable profile");
        });
        panel.addView(newProfile, centeredActionParams(2));
    }

    /** Adds blank and global-copy entry points for timer-owned profiles. */
    private void addPrivateProfileShortcuts(
            LinearLayout panel,
            List<BeepProfile> profiles,
            List<BeepProfile> customProfiles,
            LinearLayout profileChoices,
            LinearLayout customProfileChoices,
            String ownerClockId,
            List<String> draftProfileIds) {
        Button createPrivate = textButton("Create Private Profile", COLOR_ACCENT);
        createPrivate.setGravity(Gravity.CENTER);
        createPrivate.setOnClickListener(v -> {
            List<String> selected = selectedProfileIds(
                    profiles, profileChoices, customProfiles, customProfileChoices);
            BeepProfile privateProfile = newPrivateProfile(ownerClockId);
            draftProfileIds.add(privateProfile.id);
            showProfileEditor(privateProfile, created -> {
                customProfiles.add(created);
                selected.add(created.id);
                populateProfileChoices(profileChoices, profiles, selected, false, null);
                populateProfileChoices(customProfileChoices, customProfiles, selected, true,
                        profile -> deleteCustomProfile(
                                profile, profiles, customProfiles, profileChoices,
                                customProfileChoices, selected, draftProfileIds));
            }, "Create Private Profile");
        });
        panel.addView(createPrivate, centeredActionParams(2));

        Button copyPrivate = textButton("Create Private Profile from Global…", COLOR_ACCENT);
        copyPrivate.setGravity(Gravity.CENTER);
        copyPrivate.setOnClickListener(v -> {
            if (profiles.isEmpty()) {
                Toast.makeText(this, "No global profiles are available to copy",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            List<String> selected = selectedProfileIds(
                    profiles, profileChoices, customProfiles, customProfileChoices);
            if (profiles.size() == 1) {
                createPrivateProfileFromGlobal(
                        profiles.get(0), selected, profiles, customProfiles,
                        profileChoices, customProfileChoices, ownerClockId, draftProfileIds);
                return;
            }
            String[] names = new String[profiles.size()];
            for (int i = 0; i < profiles.size(); i++) {
                names[i] = profiles.get(i).name;
            }
            new AlertDialog.Builder(this)
                    .setTitle("Choose global profile to copy")
                    .setItems(names, (dialog, which) -> createPrivateProfileFromGlobal(
                            profiles.get(which), selected, profiles, customProfiles,
                            profileChoices, customProfileChoices, ownerClockId, draftProfileIds))
                    .setNegativeButton("Cancel", null)
                    .show();
        });
        panel.addView(copyPrivate, centeredActionParams(0));
    }

    /** Creates a blank, timer-owned profile draft. */
    private BeepProfile newPrivateProfile(String ownerClockId) {
        BeepProfile profile = BeepProfile.periodic("Private profile", 500L, 60_000L, true);
        profile.reusable = false;
        profile.ownerClockId = ownerClockId;
        return profile;
    }

    /** Opens a global-copy editor and keeps the resulting copy in this clock form only. */
    private void createPrivateProfileFromGlobal(
            BeepProfile source,
            List<String> selected,
            List<BeepProfile> profiles,
            List<BeepProfile> customProfiles,
            LinearLayout profileChoices,
            LinearLayout customProfileChoices,
            String ownerClockId,
            List<String> draftProfileIds) {
        BeepProfile copy = source.copyAs(source.name + " (for this timer)");
        copy.reusable = false;
        copy.ownerClockId = ownerClockId;
        draftProfileIds.add(copy.id);
        showProfileEditor(copy, customized -> {
            customProfiles.add(customized);
            profiles.clear();
            profiles.addAll(store.loadReusableProfiles());
            selected.remove(source.id);
            selected.add(customized.id);
            populateProfileChoices(profileChoices, profiles, selected, false, null);
            populateProfileChoices(customProfileChoices, customProfiles, selected, true,
                    profile -> deleteCustomProfile(
                            profile, profiles, customProfiles, profileChoices,
                            customProfileChoices, selected, draftProfileIds));
        }, "Customize profile for this timer");
    }

    /** Removes a timer-specific profile from the current form without touching global templates. */
    private void deleteCustomProfile(
            BeepProfile profile,
            List<BeepProfile> profiles,
            List<BeepProfile> customProfiles,
            LinearLayout profileChoices,
            LinearLayout customProfileChoices,
            List<String> selected,
            List<String> draftProfileIds) {
        new AlertDialog.Builder(this)
                .setTitle("Remove " + profile.name + "?")
                .setMessage("It will be removed from this timer when you save.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (dialog, which) -> {
                    selected.remove(profile.id);
                    customProfiles.removeIf(item -> profile.id.equals(item.id));
                    if (draftProfileIds.contains(profile.id)) {
                        store.deleteProfile(profile.id);
                        draftProfileIds.remove(profile.id);
                    }
                    populateProfileChoices(profileChoices, profiles, selected, false, null);
                    populateProfileChoices(customProfileChoices, customProfiles, selected, true,
                            item -> deleteCustomProfile(
                                    item, profiles, customProfiles, profileChoices,
                                    customProfileChoices, selected, draftProfileIds));
                })
                .show();
    }

    private void confirmDeleteProfile(BeepProfile profile) {
        new AlertDialog.Builder(this)
                .setTitle("Delete " + profile.name + "?")
                .setMessage("Clocks using it will become silent.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> {
                    store.deleteProfile(profile.id);
                    for (ClockInstance clock : clocks) {
                        if (clock.attachedProfileIds().contains(profile.id)) {
                            List<String> remaining = clock.attachedProfileIds();
                            remaining.remove(profile.id);
                            clock.setAttachedProfileIds(remaining);
                            clock.lastCueElapsedMillis = 0L;
                        }
                    }
                    saveAndSync();
                    refreshProfilesDialog();
                })
                .show();
    }

    private LinearLayout profileChoices(
            List<BeepProfile> profiles, List<String> selectedProfileIds) {
        LinearLayout choices = new LinearLayout(this);
        choices.setOrientation(LinearLayout.VERTICAL);
        populateProfileChoices(choices, profiles, selectedProfileIds, false, null);
        return choices;
    }

    /**
     * Renders profile checkboxes. Timer-owned rows also expose edit and delete actions;
     * reusable global rows remain selection-only in a timer editor.
     */
    private void populateProfileChoices(
            LinearLayout choices,
            List<BeepProfile> profiles,
            List<String> selectedProfileIds,
            boolean allowDelete,
            ProfileDeletedListener deleteListener) {
        choices.removeAllViews();
        if (profiles.isEmpty()) {
            choices.addView(text(allowDelete
                            ? "No private profiles for this timer."
                            : "No reusable profiles yet. Use Profiles to add one.",
                    13, COLOR_MUTED, Typeface.NORMAL));
            return;
        }
        for (BeepProfile profile : profiles) {
            CheckBox choice = new CheckBox(this);
            choice.setText(profile.name);
            choice.setTextColor(COLOR_INK);
            choice.setChecked(selectedProfileIds.contains(profile.id));
            if (!allowDelete) {
                choices.addView(choice);
                continue;
            }
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(choice, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            Button edit = textButton("Edit", COLOR_ACCENT);
            edit.setOnClickListener(v -> showProfileEditor(
                    profile,
                    edited -> populateProfileChoices(
                            choices, profiles, selectedProfileIds, true, deleteListener),
                    "Edit Private Profile"));
            row.addView(edit);
            Button delete = textButton("Delete", COLOR_DANGER);
            delete.setOnClickListener(v -> {
                if (deleteListener != null) {
                    deleteListener.onDelete(profile);
                }
            });
            row.addView(delete);
            choices.addView(row);
        }
    }

    private LinearLayout profileSelectionHeader(String title) {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView titleView = label(title);
        header.addView(titleView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return header;
    }

    private List<String> selectedProfileIds(
            List<BeepProfile> profiles,
            LinearLayout choices,
            List<BeepProfile> customProfiles,
            LinearLayout customProfileChoices) {
        List<String> selected = new ArrayList<>();
        addSelectedProfileIds(selected, choices, profiles);
        addSelectedProfileIds(selected, customProfileChoices, customProfiles);
        return selected;
    }

    private void addSelectedProfileIds(
            List<String> selected, LinearLayout choices, List<BeepProfile> profiles) {
        for (int i = 0; i < profiles.size() && i < choices.getChildCount(); i++) {
            CheckBox choice = choiceCheckBox(choices.getChildAt(i));
            if (choice != null && choice.isChecked()) {
                selected.add(profiles.get(i).id);
            }
        }
    }

    private CheckBox choiceCheckBox(View child) {
        if (child instanceof CheckBox) {
            return (CheckBox) child;
        }
        if (!(child instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) child;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (group.getChildAt(i) instanceof CheckBox) {
                return (CheckBox) group.getChildAt(i);
            }
        }
        return null;
    }

    private TextView durationEntryHelp() {
        return text("Tap HH, MM, or SS, type two digits, and the focus advances.",
                12, COLOR_MUTED, Typeface.NORMAL);
    }

    private void saveAndSync() {
        store.saveClockInstances(clocks);
        syncService();
    }

    /** Commits selected timer-form drafts to the newly created clock and discards the rest. */
    private void commitDraftProfiles(
            String draftOwnerId, String clockId, List<String> retainedProfileIds) {
        for (BeepProfile profile : store.loadProfilesOwnedBy(draftOwnerId)) {
            if (retainedProfileIds.contains(profile.id)) {
                profile.ownerClockId = clockId;
                profile.reusable = false;
                store.putProfile(profile);
            } else {
                store.deleteProfile(profile.id);
            }
        }
    }

    private void syncService() {
        Intent intent = new Intent(this, TimingService.class).setAction(TimingService.ACTION_SYNC);
        boolean anyRunning = hasRunningClock();
        if (anyRunning) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private boolean hasRunningClock() {
        for (ClockInstance clock : clocks) {
            if (clock.running) {
                return true;
            }
        }
        return false;
    }

    private ClockInstance findClock(String id) {
        for (ClockInstance clock : clocks) {
            if (clock.id.equals(id)) {
                return clock;
            }
        }
        return null;
    }

    private String profileNames(List<String> profileIds) {
        if (profileIds == null || profileIds.isEmpty()) {
            return "Silent";
        }
        StringBuilder names = new StringBuilder();
        for (String profileId : profileIds) {
            BeepProfile profile = store.getProfile(profileId);
            if (profile == null) {
                continue;
            }
            if (names.length() > 0) {
                names.append(" + ");
            }
            names.append(profile.name);
        }
        return names.length() == 0 ? "Silent" : names.toString();
    }

    /** Returns timer-owned profiles already attached to this clock. */
    private List<BeepProfile> customProfilesForClock(ClockInstance clock) {
        List<BeepProfile> profiles = new ArrayList<>();
        for (String profileId : clock.attachedProfileIds()) {
            BeepProfile profile = store.getProfile(profileId);
            if (profile != null && !profile.reusable) {
                profiles.add(profile);
            }
        }
        return profiles;
    }

    private String profileDescription(BeepProfile profile) {
        String schedule = profile.mode == BeepProfile.Mode.PERIODIC
                ? "Every " + compactDuration(profile.periodMillis)
                : profile.scriptOffsetsMillis.size() + (profile.scriptOffsetsMillis.size() == 1
                ? " scripted cue" : " scripted cues");
        return schedule + " • " + profile.beepDurationMillis + " ms beep"
                + (profile.beepOnCue ? "" : " • speech only")
                + (profile.beepOnCompletion ? " • finish cue" : "")
                + (profile.speakOnCompletion ? " • spoken finish" : "")
                + (profile.speakCurrentTime ? " • says current time" : "")
                + (profile.speakElapsedTime ? " • says elapsed time" : "")
                + (profile.speakRemainingTime ? " • says time remaining" : "")
                + (profile.speakClockNameWithSpeech ? " • names clock with scripted text" : "");
    }

    /** Reports whether an existing profile has any speech action worth exposing. */
    private boolean hasSpeechAction(BeepProfile profile) {
        if (!profile.speakOnCue) {
            return false;
        }
        if (profile.speakOnCompletion || profile.speakCurrentTime
                || profile.speakElapsedTime || profile.speakRemainingTime) {
            return true;
        }
        if (profile.events == null) {
            return false;
        }
        for (CueEvent event : profile.events) {
            if (event.hasSpeech()) {
                return true;
            }
        }
        return false;
    }

    private int spokenTimeSelection(BeepProfile profile) {
        if (profile == null) {
            return 0;
        }
        if (profile.speakCurrentTime) {
            return 1;
        }
        if (profile.speakElapsedTime) {
            return 2;
        }
        if (profile.speakRemainingTime) {
            return 3;
        }
        return 0;
    }

    private String defaultClockName(ClockInstance.Type type) {
        String base = type == ClockInstance.Type.STOPWATCH ? "Stopwatch"
                : type == ClockInstance.Type.TIMER ? "Timer" : "Countdown";
        int count = 1;
        for (ClockInstance clock : clocks) {
            if (clock.type == type) {
                count++;
            }
        }
        return base + " " + count;
    }

    private String typeLabel(ClockInstance.Type type) {
        if (type == ClockInstance.Type.STOPWATCH) {
            return "STOPWATCH";
        }
        if (type == ClockInstance.Type.TIMER) {
            return "TIMER";
        }
        return "COUNTDOWN";
    }

    private String compactDuration(long millis) {
        long totalSeconds = Math.max(0L, millis) / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    private String joinOffsets(List<Long> offsets) {
        StringBuilder text = new StringBuilder();
        for (long offset : offsets) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(compactDuration(offset));
        }
        return text.toString();
    }

    private List<Long> eventOffsets(List<CueEvent> events) {
        List<Long> offsets = new ArrayList<>();
        for (CueEvent event : events) {
            offsets.add(event.offsetMillis);
        }
        return offsets;
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
        }
    }

    private LinearLayout dialogPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(8), dp(20), dp(8));
        return panel;
    }

    private View wrapDialogPanel(View panel) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.addView(panel, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private TextView label(String value) {
        return text(value, 12, COLOR_MUTED, Typeface.BOLD);
    }

    private EditText field(String hint, String value) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setText(value);
        field.setTextSize(16);
        field.setTextColor(COLOR_INK);
        field.setHintTextColor(Color.rgb(145, 153, 162));
        field.setSingleLine(true);
        field.setPadding(dp(12), 0, dp(12), 0);
        field.setBackground(roundRect(Color.WHITE, 8, COLOR_BORDER, 1));
        field.setSelectAllOnFocus(true);
        field.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        return field;
    }

    private TextView text(String value, float size, int color, int style) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setTypeface(Typeface.create("sans", style));
        return text;
    }

    private TextView pill(String value, int backgroundColor, int foregroundColor) {
        TextView text = text(value, 11, foregroundColor, Typeface.BOLD);
        text.setGravity(Gravity.CENTER);
        text.setPadding(dp(9), dp(4), dp(9), dp(4));
        text.setBackground(roundRect(backgroundColor, 20, Color.TRANSPARENT, 0));
        return text;
    }

    private Button primaryButton(String value) {
        Button button = baseButton(value);
        button.setTextColor(Color.WHITE);
        button.setBackgroundTintList(ColorStateList.valueOf(COLOR_ACCENT));
        return button;
    }

    private Button secondaryButton(String value) {
        Button button = baseButton(value);
        button.setTextColor(COLOR_ACCENT);
        button.setBackgroundTintList(ColorStateList.valueOf(COLOR_ACCENT_SOFT));
        return button;
    }

    private Button textButton(String value, int color) {
        Button button = baseButton(value);
        button.setTextColor(color);
        button.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        button.setMinWidth(0);
        button.setPadding(dp(6), 0, dp(6), 0);
        return button;
    }

    private Button baseButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(13);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(14), 0, dp(14), 0);
        return button;
    }

    private GradientDrawable roundRect(int color, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) {
            drawable.setStroke(dp(strokeDp), strokeColor);
        }
        return drawable;
    }

    private LinearLayout.LayoutParams marginParams(int width, int height,
                                                    int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private LinearLayout.LayoutParams weightedParams(float weight, int leftMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(44), weight);
        params.setMargins(dp(leftMargin), 0, 0, 0);
        return params;
    }

    private LinearLayout.LayoutParams topMargin(int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(top);
        return params;
    }

    /** Centers a text action beneath the profile section it operates on. */
    private LinearLayout.LayoutParams centeredActionParams(int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.CENTER_HORIZONTAL;
        params.topMargin = dp(top);
        return params;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class ClockCard {
        final TextView time;
        final TextView detail;
        final Button startPause;

        ClockCard(TextView time, TextView detail, Button startPause) {
            this.time = time;
            this.detail = detail;
            this.startPause = startPause;
        }
    }

    /** Receives a profile created from within a clock form. */
    private interface ProfileSavedListener {
        void onSaved(BeepProfile profile);
    }

    /** Handles removal of a timer-owned profile from a clock form. */
    private interface ProfileDeletedListener {
        void onDelete(BeepProfile profile);
    }

    private static final class SimpleItemSelectedListener
            implements android.widget.AdapterView.OnItemSelectedListener {
        private final Runnable action;

        SimpleItemSelectedListener(Runnable action) {
            this.action = action;
        }

        @Override
        public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                   int position, long id) {
            action.run();
        }

        @Override
        public void onNothingSelected(android.widget.AdapterView<?> parent) {
            action.run();
        }
    }
}
