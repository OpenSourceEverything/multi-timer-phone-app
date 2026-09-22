package com.ose.multitimer;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A small, dependency-free interface for managing independent clocks and their reusable cue profiles.
 */
public final class MainActivity extends Activity {
    private static final int REQUEST_NOTIFICATIONS = 1001;
    private static final long UI_TICK_MILLIS = 100L;

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
        setContentView(buildContent());
        requestNotificationPermissionIfNeeded();
    }

    @Override
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(TimingService.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter,
                    TimingService.INTERNAL_BROADCAST_PERMISSION, null,
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter,
                    TimingService.INTERNAL_BROADCAST_PERMISSION, null);
        }
        receiverRegistered = true;
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
        if (receiverRegistered) {
            unregisterReceiver(stateReceiver);
            receiverRegistered = false;
        }
        super.onStop();
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
        summary = text("No clocks yet", 13, COLOR_MUTED, Typeface.NORMAL);
        titles.addView(title);
        titles.addView(summary, marginParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 2, 0, 0));
        header.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button profilesButton = secondaryButton("Profiles");
        profilesButton.setContentDescription("Manage beep profiles");
        profilesButton.setOnClickListener(v -> showProfilesDialog());
        header.addView(profilesButton);
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
        TextView profile = text(profileName(clock.profileId), 12, COLOR_MUTED, Typeface.NORMAL);
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
            refs.startPause.setText(clock.running ? "Pause" : (clock.completed ? "Finished" : "Start"));
            refs.startPause.setEnabled(!clock.completed || clock.type == ClockInstance.Type.STOPWATCH);
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

        List<BeepProfile> profiles = store.loadProfiles();
        panel.addView(label("Beep profile"), topMargin(12));
        Spinner profileSpinner = profileSpinner(profiles, null);
        panel.addView(profileSpinner);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("New " + typeLabel(type).toLowerCase(Locale.getDefault()))
                .setView(wrapDialogPanel(panel))
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Add", null)
                .create();
        DurationInputView finalDuration = duration;
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
                    String profileId = selectedProfileId(profileSpinner, profiles);
                    long nowWallTimeMillis = System.currentTimeMillis();
                    ClockInstance clock = ClockInstance.create(
                            type,
                            clockName,
                            configured,
                            profileId,
                            nowWallTimeMillis,
                            SystemClock.elapsedRealtime());
                    clocks.add(clock);
                    saveAndSync();
                    renderClockList();
                    dialog.dismiss();
                }));
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
        if (clock.running) {
            clock.pause(nowWallTimeMillis, nowElapsedRealtimeMillis);
        } else if (!clock.completed || clock.type == ClockInstance.Type.STOPWATCH) {
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

        List<BeepProfile> profiles = store.loadProfiles();
        panel.addView(label("Beep profile"), topMargin(12));
        Spinner profileSpinner = profileSpinner(profiles, clock.profileId);
        panel.addView(profileSpinner);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Edit " + typeLabel(clock.type).toLowerCase(Locale.getDefault()))
                .setView(wrapDialogPanel(panel))
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Profiles", (ignored, which) -> showProfilesDialog())
                .setPositiveButton("Save", null)
                .create();
        DurationInputView finalDuration = duration;
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

                    String profileId = selectedProfileId(profileSpinner, profiles);
                    boolean profileChanged = !sameProfileId(current.profileId, profileId);
                    current.name = clockName;
                    if (configurationChanged) {
                        current.reconfigure(
                                configured, nowWallTimeMillis, nowElapsedRealtimeMillis);
                    }
                    if (profileChanged) {
                        current.profileId = profileId;
                        // A newly attached schedule begins at the current clock position.
                        current.lastCueElapsedMillis = current.cueElapsedAt(
                                nowWallTimeMillis, nowElapsedRealtimeMillis);
                        current.initialCuePending = false;
                        if (!current.completed) {
                            current.completionBeepPlayed = false;
                        }
                    }

                    saveAndSync();
                    renderClockList();
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private void showProfilesDialog() {
        if (profilesDialog != null && profilesDialog.isShowing()) {
            profilesDialog.dismiss();
        }
        List<BeepProfile> profiles = store.loadProfiles();
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(8), dp(4), dp(8), dp(4));

        if (profiles.isEmpty()) {
            TextView empty = text("No profiles. Add one to schedule beeps.",
                    14, COLOR_MUTED, Typeface.NORMAL);
            empty.setPadding(dp(12), dp(20), dp(12), dp(20));
            content.addView(empty);
        } else {
            for (BeepProfile profile : profiles) {
                content.addView(buildProfileRow(profile), marginParams(
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
                .setTitle("Beep profiles")
                .setView(frame)
                .setNegativeButton("Close", null)
                .setPositiveButton("Add profile", null)
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
                    showProfileEditor(null);
                }));
        dialog.show();
    }

    private View buildProfileRow(BeepProfile profile) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(12), dp(12), dp(10));
        row.setBackground(roundRect(Color.rgb(250, 251, 252), 10, COLOR_BORDER, 1));

        TextView name = text(profile.name, 16, COLOR_INK, Typeface.BOLD);
        row.addView(name);
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
        Button delete = textButton("Delete", COLOR_DANGER);
        delete.setOnClickListener(v -> confirmDeleteProfile(profile));
        actions.addView(delete);
        row.addView(actions);
        return row;
    }

    private void showProfileEditor(BeepProfile existing) {
        LinearLayout panel = dialogPanel();
        EditText name = field("Profile name", existing == null ? "Interval cue" : existing.name);
        panel.addView(label("Name"));
        panel.addView(name);

        panel.addView(label("Schedule"), topMargin(12));
        Spinner mode = new Spinner(this);
        mode.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Fixed period", "Scripted entries"}));
        if (existing != null && existing.mode == BeepProfile.Mode.SCRIPTED) {
            mode.setSelection(1);
        }
        panel.addView(mode, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        panel.addView(label("Beep length (milliseconds)"), topMargin(12));
        EditText beepLength = field("500", String.valueOf(existing == null
                ? 500L : existing.beepDurationMillis));
        beepLength.setInputType(InputType.TYPE_CLASS_NUMBER);
        panel.addView(beepLength);

        LinearLayout periodicFields = new LinearLayout(this);
        periodicFields.setOrientation(LinearLayout.VERTICAL);
        periodicFields.addView(label("Beep every"));
        DurationInputView period = new DurationInputView(this);
        period.setDurationMillis(existing == null || existing.periodMillis <= 0L
                ? 60_000L : existing.periodMillis);
        periodicFields.addView(period);
        periodicFields.addView(durationEntryHelp(), topMargin(4));
        panel.addView(periodicFields, topMargin(12));

        LinearLayout scriptedFields = new LinearLayout(this);
        scriptedFields.setOrientation(LinearLayout.VERTICAL);
        scriptedFields.addView(label("Event script (text or TOML)"));
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
                "Rows: TIME | say \"text\" | beep [milliseconds]. TOML [[event]] rows are also accepted.",
                12, COLOR_MUTED, Typeface.NORMAL), topMargin(4));
        panel.addView(scriptedFields, topMargin(12));

        CheckBox completion = new CheckBox(this);
        completion.setText("Also beep when a timer or countdown finishes");
        completion.setTextColor(COLOR_INK);
        completion.setChecked(existing == null || existing.beepOnCompletion);
        panel.addView(completion, topMargin(10));

        Runnable updateMode = () -> {
            boolean periodic = mode.getSelectedItemPosition() == 0;
            periodicFields.setVisibility(periodic ? View.VISIBLE : View.GONE);
            scriptedFields.setVisibility(periodic ? View.GONE : View.VISIBLE);
        };
        mode.setOnItemSelectedListener(new SimpleItemSelectedListener(updateMode));
        updateMode.run();

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(existing == null ? "New beep profile" : "Edit beep profile")
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
                            if (events.isEmpty() && !completion.isChecked()) {
                                throw new IllegalArgumentException(
                                        "Add an event or enable the completion beep");
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
                            completion.isChecked())
                            : BeepProfile.scriptedEvents(profileName, beepMillis, events,
                            completion.isChecked()))
                            : existing;
                    profile.name = profileName;
                    profile.mode = selectedMode;
                    profile.beepDurationMillis = beepMillis;
                    profile.periodMillis = periodMillis;
                    profile.events = selectedMode == BeepProfile.Mode.SCRIPTED
                            ? events : new ArrayList<>();
                    profile.scriptOffsetsMillis = selectedMode == BeepProfile.Mode.SCRIPTED
                            ? eventOffsets(events) : offsets;
                    profile.beepOnCompletion = completion.isChecked();
                    try {
                        profile.validate();
                    } catch (IllegalArgumentException error) {
                        Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
                        return;
                    }
                    store.putProfile(profile);
                    syncService();
                    dialog.dismiss();
                    showProfilesDialog();
                }));
        dialog.show();
    }

    private void confirmDeleteProfile(BeepProfile profile) {
        new AlertDialog.Builder(this)
                .setTitle("Delete " + profile.name + "?")
                .setMessage("Clocks using it will become silent.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> {
                    store.deleteProfile(profile.id);
                    for (ClockInstance clock : clocks) {
                        if (profile.id.equals(clock.profileId)) {
                            clock.profileId = "";
                            clock.lastCueElapsedMillis = 0L;
                        }
                    }
                    saveAndSync();
                    showProfilesDialog();
                })
                .show();
    }

    private Spinner profileSpinner(List<BeepProfile> profiles, String selectedId) {
        ArrayList<String> labels = new ArrayList<>();
        labels.add("Silent (no profile)");
        int selected = 0;
        for (int i = 0; i < profiles.size(); i++) {
            labels.add(profiles.get(i).name);
            if (profiles.get(i).id.equals(selectedId)) {
                selected = i + 1;
            }
        }
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        spinner.setSelection(selected);
        return spinner;
    }

    private String selectedProfileId(Spinner spinner, List<BeepProfile> profiles) {
        int position = spinner.getSelectedItemPosition();
        return position <= 0 ? "" : profiles.get(position - 1).id;
    }

    private boolean sameProfileId(String first, String second) {
        String normalizedFirst = first == null ? "" : first;
        String normalizedSecond = second == null ? "" : second;
        return normalizedFirst.equals(normalizedSecond);
    }

    private TextView durationEntryHelp() {
        return text("Tap HH, MM, or SS, type two digits, and the focus advances.",
                12, COLOR_MUTED, Typeface.NORMAL);
    }

    private void saveAndSync() {
        store.saveClockInstances(clocks);
        syncService();
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

    private String profileName(String profileId) {
        if (profileId == null || profileId.isEmpty()) {
            return "Silent";
        }
        BeepProfile profile = store.getProfile(profileId);
        return profile == null ? "Silent" : profile.name;
    }

    private String profileDescription(BeepProfile profile) {
        String schedule = profile.mode == BeepProfile.Mode.PERIODIC
                ? "Every " + compactDuration(profile.periodMillis)
                : profile.scriptOffsetsMillis.size() + (profile.scriptOffsetsMillis.size() == 1
                ? " scripted cue" : " scripted cues");
        return schedule + " • " + profile.beepDurationMillis + " ms beep"
                + (profile.beepOnCompletion ? " • finish cue" : "");
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
