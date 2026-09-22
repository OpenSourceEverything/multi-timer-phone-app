package com.ose.multitimer;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.DigitsKeyListener;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Numeric duration entry with separate HH, MM, and SS fields. */
public final class DurationInputView extends LinearLayout {
    private static final int COLOR_INK = Color.rgb(23, 33, 43);
    private static final int COLOR_MUTED = Color.rgb(91, 103, 116);
    private static final int COLOR_BORDER = Color.rgb(190, 198, 207);
    private static final int COLOR_ACCENT = Color.rgb(0, 108, 103);
    private static final int COLOR_ERROR = Color.rgb(176, 0, 32);

    private final EditText hours;
    private final EditText minutes;
    private final EditText seconds;
    private final TextView error;
    private String errorMessage;
    private boolean updatingFields;

    public DurationInputView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setClickable(true);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);

        LinearLayout display = new LinearLayout(context);
        display.setGravity(Gravity.CENTER_VERTICAL);
        hours = addTimeGroup(display, "HH", 6);
        addColon(display);
        minutes = addTimeGroup(display, "MM", 2);
        addColon(display);
        seconds = addTimeGroup(display, "SS", 2);
        addView(display, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        error = new TextView(context);
        error.setTextSize(12);
        error.setTextColor(COLOR_ERROR);
        error.setVisibility(GONE);
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE);
        LayoutParams errorParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        errorParams.topMargin = dp(4);
        addView(error, errorParams);

        hours.addTextChangedListener(fieldWatcher(hours));
        minutes.addTextChangedListener(fieldWatcher(minutes));
        seconds.addTextChangedListener(fieldWatcher(seconds));
        hours.setOnEditorActionListener((view, actionId, event) -> moveToNext(hours, actionId));
        minutes.setOnEditorActionListener((view, actionId, event) -> moveToNext(minutes, actionId));
        seconds.setOnEditorActionListener((view, actionId, event) -> moveToNext(seconds, actionId));
        setOnFocusChangeListener((view, focused) -> {
            refreshBoxBackgrounds();
            if (focused) {
                showKeyboard();
            }
        });
        setAccessibilityDelegate(new AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(
                    View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(EditText.class.getName());
                info.setEditable(true);
                info.setText(TimeUtils.formatDurationDigits(getDigits()));
                info.setHintText("Hours minutes seconds");
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT);
                if (errorMessage != null) {
                    info.setContentInvalid(true);
                    info.setError(errorMessage);
                }
            }

            @Override
            public boolean performAccessibilityAction(View host, int action, Bundle arguments) {
                if (action == AccessibilityNodeInfo.ACTION_SET_TEXT && arguments != null) {
                    CharSequence requested = arguments.getCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE);
                    try {
                        setDigits(normalizeAccessibleText(requested));
                        return true;
                    } catch (IllegalArgumentException invalid) {
                        showError(invalid.getMessage());
                        return false;
                    }
                }
                return super.performAccessibilityAction(host, action, arguments);
            }
        });
        setFieldText(hours, "00");
        setFieldText(minutes, "00");
        setFieldText(seconds, "00");
        updateDisplay();
    }

    @Override
    public boolean performClick() {
        super.performClick();
        showKeyboard();
        return true;
    }

    public void setDurationMillis(long millis) {
        setDigits(TimeUtils.durationDigitsFromMillis(millis));
    }

    public long getDurationMillis() {
        return TimeUtils.parseDurationDigitsMillis(getDigits());
    }

    public String getDigits() {
        String canonical = fieldText(hours, "0")
                + twoDigits(fieldText(minutes, "0"))
                + twoDigits(fieldText(seconds, "0"));
        int firstNonZero = 0;
        while (firstNonZero < canonical.length() && canonical.charAt(firstNonZero) == '0') {
            firstNonZero++;
        }
        return canonical.substring(firstNonZero);
    }

    public void setDigits(String digits) {
        String formatted = TimeUtils.formatDurationDigits(digits);
        String[] components = formatted.split(":", -1);
        updatingFields = true;
        try {
            setFieldText(hours, components[0]);
            setFieldText(minutes, components[1]);
            setFieldText(seconds, components[2]);
        } finally {
            updatingFields = false;
        }
        updateDisplay();
    }

    public void showError(String message) {
        errorMessage = message;
        error.setText(message);
        error.setVisibility(VISIBLE);
        showKeyboard();
        error.announceForAccessibility(message);
    }

    public void clearError() {
        errorMessage = null;
        error.setVisibility(GONE);
        error.setText("");
    }

    private EditText addTimeGroup(LinearLayout parent, String label, int maxDigits) {
        LinearLayout group = new LinearLayout(getContext());
        group.setOrientation(VERTICAL);
        group.setGravity(Gravity.CENTER);

        EditText value = new EditText(getContext());
        value.setGravity(Gravity.CENTER);
        value.setTextSize(28);
        value.setTextColor(COLOR_INK);
        value.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        value.setInputType(InputType.TYPE_CLASS_NUMBER);
        value.setKeyListener(DigitsKeyListener.getInstance("0123456789"));
        value.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxDigits)});
        value.setSingleLine(true);
        value.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        value.setSelectAllOnFocus(true);
        value.setPadding(0, 0, 0, 0);
        value.setAutoSizeTextTypeUniformWithConfiguration(
                14, 28, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        value.setBackground(boxBackground(false));
        value.setOnClickListener(view -> ((EditText) view).selectAll());
        value.setOnFocusChangeListener((view, focused) -> {
            if (!focused) {
                normalizeField((EditText) view);
            }
            refreshBoxBackgrounds();
        });
        group.addView(value, new LayoutParams(LayoutParams.MATCH_PARENT, dp(56)));

        TextView caption = new TextView(getContext());
        caption.setText(label);
        caption.setGravity(Gravity.CENTER);
        caption.setTextSize(10);
        caption.setTextColor(COLOR_MUTED);
        LayoutParams captionParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        captionParams.topMargin = dp(3);
        group.addView(caption, captionParams);

        parent.addView(group, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        return value;
    }

    private void addColon(LinearLayout parent) {
        TextView colon = new TextView(getContext());
        colon.setText(":");
        colon.setGravity(Gravity.CENTER);
        colon.setTextSize(26);
        colon.setTextColor(COLOR_INK);
        colon.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        LayoutParams params = new LayoutParams(dp(24), dp(56));
        parent.addView(colon, params);
    }

    private void updateDisplay() {
        clearError();
        setContentDescription("Duration " + TimeUtils.formatDurationDigits(getDigits())
                + ". Enter hours, minutes, then seconds.");
    }

    private String normalizeAccessibleText(CharSequence requested) {
        if (requested == null) {
            return "";
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < requested.length(); i++) {
            char character = requested.charAt(i);
            if (character >= '0' && character <= '9') {
                digits.append(character);
            } else if (character != ':' && !Character.isWhitespace(character)) {
                throw new IllegalArgumentException("Duration must contain digits only");
            }
        }
        return digits.toString();
    }

    private void refreshBoxBackgrounds() {
        boolean focused = hasFocus() || hours.hasFocus() || minutes.hasFocus() || seconds.hasFocus();
        hours.setBackground(boxBackground(focused));
        minutes.setBackground(boxBackground(focused));
        seconds.setBackground(boxBackground(focused));
    }

    private void showKeyboard() {
        EditText target = hours.hasFocus() ? hours
                : minutes.hasFocus() ? minutes
                : seconds.hasFocus() ? seconds : hours;
        target.requestFocus();
        target.selectAll();
        InputMethodManager keyboard = (InputMethodManager)
                getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) {
            post(() -> keyboard.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT));
        }
    }

    private TextWatcher fieldWatcher(EditText field) {
        return new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence value, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence value, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable value) {
                if (updatingFields) {
                    return;
                }
                updateDisplay();
                if (value.length() >= 2 && field.hasFocus()) {
                    moveToNext(field, EditorInfo.IME_ACTION_NEXT);
                }
            }
        };
    }

    private boolean moveToNext(EditText field, int actionId) {
        if (actionId != EditorInfo.IME_ACTION_NEXT && actionId != EditorInfo.IME_ACTION_DONE) {
            return false;
        }
        if (field == hours) {
            minutes.requestFocus();
            minutes.selectAll();
        } else if (field == minutes) {
            seconds.requestFocus();
            seconds.selectAll();
        } else {
            field.selectAll();
        }
        return true;
    }

    private void normalizeField(EditText field) {
        String value = field.getText().toString();
        if (value.isEmpty()) {
            setFieldText(field, "00");
        } else if (value.length() == 1) {
            setFieldText(field, "0" + value);
        }
    }

    private void setFieldText(EditText field, String value) {
        field.setText(value);
        field.setSelection(field.length());
    }

    private String fieldText(EditText field, String fallback) {
        String value = field.getText().toString();
        return value.isEmpty() ? fallback : value;
    }

    private String twoDigits(String value) {
        return value.length() == 1 ? "0" + value : value;
    }

    private GradientDrawable boxBackground(boolean focused) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(8));
        background.setStroke(dp(focused ? 2 : 1), focused ? COLOR_ACCENT : COLOR_BORDER);
        return background;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
