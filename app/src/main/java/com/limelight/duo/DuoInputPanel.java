package com.limelight.duo;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.limelight.nvstream.input.MouseButtonPacket;

import static com.limelight.duo.DuoKeys.*;

/**
 * Lower-screen panel: top bar (mode tabs, trackpad speed, actions),
 * keyboard-that-is-a-trackpad with click buttons, and a big trackpad mode.
 */
@SuppressLint("ViewConstructor")
public class DuoInputPanel extends LinearLayout {
    private final float density;
    private final DuoInputSink sink;

    private final DuoKeyboardView keyboard;
    private final DuoTrackpadView pad;
    private final View keyboardMode;
    private final View trackpadMode;
    private final TextView tabKeyboard;
    private final TextView tabTrackpad;

    /**
     * @param onHide if not null, the panel runs detached on the other screen and
     *               shows an "Ausblenden" button (instead of Alt+Tab) that calls it.
     */
    public DuoInputPanel(Context context, DuoInputSink sink, Runnable onHide) {
        super(context);
        this.sink = sink;
        this.density = context.getResources().getDisplayMetrics().density;

        setOrientation(VERTICAL);
        setBackgroundColor(DuoColors.BG);
        int pad8 = dp(8);
        setPadding(pad8, pad8, pad8, pad8);
        // Swallow touches so nothing falls through to the stream below.
        setClickable(true);

        // ---------- top bar ----------
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        addView(bar, new LayoutParams(LayoutParams.MATCH_PARENT, dp(44)));

        LinearLayout segment = new LinearLayout(context);
        segment.setOrientation(HORIZONTAL);
        segment.setBackground(outlined(DuoColors.SURFACE, 10));
        segment.setPadding(dp(3), dp(3), dp(3), dp(3));
        bar.addView(segment, new LayoutParams(LayoutParams.WRAP_CONTENT, dp(44)));

        tabKeyboard = tab("Tastatur");
        tabTrackpad = tab("Trackpad");
        segment.addView(tabKeyboard, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
        LayoutParams tp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT);
        tp.leftMargin = dp(3);
        segment.addView(tabTrackpad, tp);

        LayoutParams speedLp = new LayoutParams(0, dp(44), 1f);
        speedLp.leftMargin = dp(6);
        bar.addView(buildSpeedControl(context), speedLp);

        if (onHide != null) {
            addBarView(bar, actionButton("Ausblenden", v -> onHide.run()), LayoutParams.WRAP_CONTENT);
        } else {
            addBarView(bar, actionButton("Alt+Tab", v -> sendCombo(sink, VK_LMENU, VK_TAB)), LayoutParams.WRAP_CONTENT);
        }
        addBarView(bar, actionButton("Gboard", v -> sink.toggleSystemKeyboard()), LayoutParams.WRAP_CONTENT);
        addBarView(bar, actionButton("Strg+Alt+Entf", v -> sendCombo(sink, VK_LCONTROL, VK_LMENU, VK_DELETE)), LayoutParams.WRAP_CONTENT);
        TextView menu = actionButton("⋯", v -> sink.showStreamMenu());
        menu.setContentDescription("Stream-Menü");
        menu.setTextSize(18);
        addBarView(bar, menu, dp(44));

        // ---------- content ----------
        FrameLayout content = new FrameLayout(context);
        LayoutParams cp = new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f);
        cp.topMargin = dp(8);
        addView(content, cp);

        keyboard = new DuoKeyboardView(context);
        keyboard.setSink(sink);
        pad = new DuoTrackpadView(context);
        pad.setSink(sink);

        keyboardMode = buildKeyboardMode(context);
        content.addView(keyboardMode, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        trackpadMode = buildTrackpadMode(context);
        content.addView(trackpadMode, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        applySpeed(DuoBridge.getSpeed(context));

        tabKeyboard.setOnClickListener(v -> showKeyboardMode(true));
        tabTrackpad.setOnClickListener(v -> showKeyboardMode(false));
        showKeyboardMode(true);
    }

    private View buildSpeedControl(Context context) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(outlined(DuoColors.SURFACE, 10));
        box.setPadding(dp(10), 0, dp(10), 0);

        TextView label = new TextView(context);
        label.setText("Tempo");
        label.setTextColor(DuoColors.MUTED);
        label.setTextSize(12);
        box.addView(label, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        final TextView value = new TextView(context);
        value.setTextColor(DuoColors.TEXT);
        value.setTextSize(12);
        value.setTypeface(Typeface.DEFAULT_BOLD);
        value.setGravity(Gravity.END);

        SeekBar seek = new SeekBar(context);
        seek.setMax(9);
        int speed = DuoBridge.getSpeed(context);
        seek.setProgress(speed - 1);
        value.setText(String.valueOf(speed));
        seek.setContentDescription("Trackpad-Geschwindigkeit");
        seek.setProgressTintList(ColorStateList.valueOf(DuoColors.ACCENT));
        seek.setThumbTintList(ColorStateList.valueOf(DuoColors.ACCENT));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(DuoColors.BORDER));
        seek.setFocusable(false);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                int sp = progress + 1;
                value.setText(String.valueOf(sp));
                applySpeed(sp);
                if (fromUser) {
                    DuoBridge.setSpeed(getContext(), sp);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {}

            @Override
            public void onStopTrackingTouch(SeekBar s) {}
        });
        box.addView(seek, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        box.addView(value, new LayoutParams(dp(18), LayoutParams.WRAP_CONTENT));
        return box;
    }

    private void applySpeed(int speed) {
        float gain = DuoBridge.gainFor(speed);
        keyboard.setSensitivity(gain);
        pad.setSensitivity(gain);
    }

    private View buildKeyboardMode(Context context) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(VERTICAL);
        root.addView(keyboard, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout clicks = new LinearLayout(context);
        clicks.setOrientation(HORIZONTAL);
        LayoutParams clp = new LayoutParams(LayoutParams.MATCH_PARENT, dp(52));
        clp.topMargin = dp(6);
        root.addView(clicks, clp);
        addWeighted(clicks, holdButton("Linksklick", MouseButtonPacket.BUTTON_LEFT, DuoColors.KEY), 1f, false);
        addWeighted(clicks, holdButton("Rechtsklick", MouseButtonPacket.BUTTON_RIGHT, DuoColors.KEY), 1f, true);
        return root;
    }

    private View buildTrackpadMode(Context context) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(HORIZONTAL);

        LinearLayout shortcuts = new LinearLayout(context);
        shortcuts.setOrientation(VERTICAL);
        root.addView(shortcuts, new LayoutParams(dp(168), LayoutParams.MATCH_PARENT));

        // Note: on a German host layout, Strg+Z is the key at the US "Y" position.
        addShortcut(shortcuts, "Kopieren", "Strg+C", VK_LCONTROL, vk('c'));
        addShortcut(shortcuts, "Einfügen", "Strg+V", VK_LCONTROL, vk('v'));
        addShortcut(shortcuts, "Rückgängig", "Strg+Z", VK_LCONTROL, vk('y'));
        addShortcut(shortcuts, "Fenster", "Alt+Tab", VK_LMENU, VK_TAB);
        addShortcut(shortcuts, "Übersicht", "Win+Tab", VK_LWIN, VK_TAB);
        addShortcut(shortcuts, "Desktop", "Win+D", VK_LWIN, vk('d'));
        addShortcut(shortcuts, "Esc", "", VK_ESCAPE);

        LinearLayout right = new LinearLayout(context);
        right.setOrientation(VERTICAL);
        LayoutParams rp = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
        rp.leftMargin = dp(8);
        root.addView(right, rp);

        right.addView(pad, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout clicks = new LinearLayout(context);
        clicks.setOrientation(HORIZONTAL);
        LayoutParams clp = new LayoutParams(LayoutParams.MATCH_PARENT, dp(56));
        clp.topMargin = dp(6);
        right.addView(clicks, clp);
        addWeighted(clicks, holdButton("Links", MouseButtonPacket.BUTTON_LEFT, DuoColors.KEY), 3f, false);
        addWeighted(clicks, holdButton("Mitte", MouseButtonPacket.BUTTON_MIDDLE, DuoColors.KEY_FN), 1f, true);
        addWeighted(clicks, holdButton("Rechts", MouseButtonPacket.BUTTON_RIGHT, DuoColors.KEY), 3f, true);
        return root;
    }

    private void showKeyboardMode(boolean kb) {
        keyboardMode.setVisibility(kb ? VISIBLE : GONE);
        trackpadMode.setVisibility(kb ? GONE : VISIBLE);
        styleTab(tabKeyboard, kb);
        styleTab(tabTrackpad, !kb);
    }

    // ---------- view helpers ----------

    private int dp(float v) {
        return Math.round(v * density);
    }

    private GradientDrawable outlined(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusDp * density);
        d.setStroke(Math.max(1, Math.round(density)), DuoColors.BORDER);
        return d;
    }

    private GradientDrawable filled(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusDp * density);
        return d;
    }

    private StateListDrawable pressable(int color, float radiusDp) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, outlined(DuoColors.PRESSED, radiusDp));
        s.addState(new int[]{}, outlined(color, radiusDp));
        return s;
    }

    private TextView baseButton(String text, int color) {
        TextView t = new TextView(getContext());
        t.setText(text);
        t.setTextColor(DuoColors.TEXT);
        t.setTextSize(12);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setGravity(Gravity.CENTER);
        t.setBackground(pressable(color, 10));
        t.setPadding(dp(12), 0, dp(12), 0);
        t.setMinWidth(dp(44));
        t.setClickable(true);
        t.setFocusable(false);
        return t;
    }

    private TextView tab(String text) {
        TextView t = new TextView(getContext());
        t.setText(text);
        t.setTextSize(13);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), 0, dp(14), 0);
        t.setClickable(true);
        t.setFocusable(false);
        return t;
    }

    private void styleTab(TextView t, boolean active) {
        t.setBackground(filled(active ? DuoColors.ACCENT : Color.TRANSPARENT, 8));
        t.setTextColor(active ? DuoColors.ON_ACCENT : DuoColors.TEXT);
    }

    private TextView actionButton(String text, OnClickListener l) {
        TextView t = baseButton(text, DuoColors.SURFACE);
        t.setOnClickListener(l);
        return t;
    }

    @SuppressLint("ClickableViewAccessibility")
    private TextView holdButton(String text, final byte button, int color) {
        TextView t = baseButton(text, color);
        t.setTextSize(13);
        t.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    sink.sendMouseButton(button, true);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    sink.sendMouseButton(button, false);
                    return true;
            }
            return true;
        });
        return t;
    }

    private void addBarView(LinearLayout bar, View v, int width) {
        LayoutParams lp = new LayoutParams(width, dp(44));
        lp.leftMargin = dp(6);
        bar.addView(v, lp);
    }

    private void addWeighted(LinearLayout parent, View v, float weight, boolean margin) {
        LayoutParams lp = new LayoutParams(0, LayoutParams.MATCH_PARENT, weight);
        if (margin) {
            lp.leftMargin = dp(6);
        }
        parent.addView(v, lp);
    }

    private void addShortcut(LinearLayout parent, String label, String combo, final int... vks) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(pressable(DuoColors.SURFACE, 10));
        row.setPadding(dp(12), 0, dp(12), 0);
        row.setClickable(true);
        row.setContentDescription(label + (combo.isEmpty() ? "" : ", " + combo));
        row.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            sendCombo(sink, vks);
        });

        TextView name = new TextView(getContext());
        name.setText(label);
        name.setTextColor(DuoColors.TEXT);
        name.setTextSize(13);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        row.addView(name, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        TextView hint = new TextView(getContext());
        hint.setText(combo);
        hint.setTextColor(DuoColors.MUTED);
        hint.setTextSize(11);
        row.addView(hint, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f);
        if (parent.getChildCount() > 0) {
            lp.topMargin = dp(6);
        }
        parent.addView(row, lp);
    }
}
