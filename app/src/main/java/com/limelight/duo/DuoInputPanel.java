package com.limelight.duo;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.nvstream.input.MouseButtonPacket;

import static com.limelight.duo.DuoKeys.*;

/** The lower-screen panel: top bar, keyboard-with-trackpad, and a big trackpad mode. */
@SuppressLint("ViewConstructor")
public class DuoInputPanel extends LinearLayout {
    private static final int COLOR_BG = 0xFF101214;
    private static final int COLOR_SURFACE = 0xFF1A1D21;
    private static final int COLOR_KEY = 0xFF262A30;
    private static final int COLOR_KEY_FN = 0xFF2E333A;
    private static final int COLOR_PRESSED = 0xFF3A4048;
    private static final int COLOR_TEXT = 0xFFE8EAED;
    private static final int COLOR_MUTED = 0xFF9AA3AE;
    private static final int COLOR_TAB_OFF = 0xFFC9CED6;
    private static final int COLOR_ON_ACCENT = 0xFF0B0D10;

    private final float density;
    private final DuoInputSink sink;
    private final int accent;

    private final DuoKeyboardView keyboard;
    private final View trackpadMode;
    private final TextView tabKeyboard;
    private final TextView tabTrackpad;

    public DuoInputPanel(Context context, DuoInputSink sink) {
        super(context);
        this.sink = sink;
        this.density = context.getResources().getDisplayMetrics().density;
        this.accent = 0xFF4C8DFF;

        setOrientation(VERTICAL);
        setBackgroundColor(COLOR_BG);
        int pad = dp(8);
        setPadding(pad, pad, pad, pad);
        // Swallow touches so nothing falls through to the stream below.
        setClickable(true);

        // ---------- top bar ----------
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        addView(bar, new LayoutParams(LayoutParams.MATCH_PARENT, dp(44)));

        LinearLayout segment = new LinearLayout(context);
        segment.setOrientation(HORIZONTAL);
        segment.setBackground(rounded(COLOR_SURFACE, 10));
        segment.setPadding(dp(3), dp(3), dp(3), dp(3));
        bar.addView(segment, new LayoutParams(LayoutParams.WRAP_CONTENT, dp(44)));

        tabKeyboard = tab("Tastatur");
        tabTrackpad = tab("Trackpad");
        segment.addView(tabKeyboard, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
        LayoutParams tp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT);
        tp.leftMargin = dp(3);
        segment.addView(tabTrackpad, tp);

        addBarView(bar, holdButton("L", MouseButtonPacket.BUTTON_LEFT, "Linksklick", COLOR_KEY), dp(44));
        addBarView(bar, holdButton("R", MouseButtonPacket.BUTTON_RIGHT, "Rechtsklick", COLOR_KEY), dp(44));

        View spacer = new View(context);
        bar.addView(spacer, new LayoutParams(0, 1, 1f));

        addBarView(bar, actionButton("Alt+Tab", v -> sendCombo(sink, VK_LMENU, VK_TAB)), LayoutParams.WRAP_CONTENT);
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
        keyboard.setAccent(accent);
        content.addView(keyboard, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        trackpadMode = buildTrackpadMode(context);
        trackpadMode.setVisibility(GONE);
        content.addView(trackpadMode, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        tabKeyboard.setOnClickListener(v -> showKeyboardMode(true));
        tabTrackpad.setOnClickListener(v -> showKeyboardMode(false));
        showKeyboardMode(true);
    }

    private View buildTrackpadMode(Context context) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(HORIZONTAL);

        LinearLayout left = new LinearLayout(context);
        left.setOrientation(VERTICAL);
        root.addView(left, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));

        DuoTrackpadView pad = new DuoTrackpadView(context);
        pad.setSink(sink);
        left.addView(pad, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout clicks = new LinearLayout(context);
        clicks.setOrientation(HORIZONTAL);
        LayoutParams clp = new LayoutParams(LayoutParams.MATCH_PARENT, dp(64));
        clp.topMargin = dp(6);
        left.addView(clicks, clp);
        addWeighted(clicks, holdButton("Links", MouseButtonPacket.BUTTON_LEFT, null, COLOR_KEY), 3f, false);
        addWeighted(clicks, holdButton("Mitte", MouseButtonPacket.BUTTON_MIDDLE, null, COLOR_KEY_FN), 1f, true);
        addWeighted(clicks, holdButton("Rechts", MouseButtonPacket.BUTTON_RIGHT, null, COLOR_KEY), 3f, true);

        LinearLayout shortcuts = new LinearLayout(context);
        shortcuts.setOrientation(VERTICAL);
        LayoutParams sp = new LayoutParams(dp(168), LayoutParams.MATCH_PARENT);
        sp.leftMargin = dp(8);
        root.addView(shortcuts, sp);

        // Note: on a German host layout, Strg+Z is the key at the US "Y" position.
        addShortcut(shortcuts, "Kopieren", "Strg+C", VK_LCONTROL, vk('c'));
        addShortcut(shortcuts, "Einfügen", "Strg+V", VK_LCONTROL, vk('v'));
        addShortcut(shortcuts, "Rückgängig", "Strg+Z", VK_LCONTROL, vk('y'));
        addShortcut(shortcuts, "Fenster", "Alt+Tab", VK_LMENU, VK_TAB);
        addShortcut(shortcuts, "Übersicht", "Win+Tab", VK_LWIN, VK_TAB);
        addShortcut(shortcuts, "Desktop", "Win+D", VK_LWIN, vk('d'));
        addShortcut(shortcuts, "Esc", "", VK_ESCAPE);
        return root;
    }

    private void showKeyboardMode(boolean kb) {
        keyboard.setVisibility(kb ? VISIBLE : GONE);
        trackpadMode.setVisibility(kb ? GONE : VISIBLE);
        styleTab(tabKeyboard, kb);
        styleTab(tabTrackpad, !kb);
    }

    // ---------- view helpers ----------

    private int dp(float v) {
        return Math.round(v * density);
    }

    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusDp * density);
        return d;
    }

    private StateListDrawable pressable(int color, float radiusDp) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, rounded(COLOR_PRESSED, radiusDp));
        s.addState(new int[]{}, rounded(color, radiusDp));
        return s;
    }

    private TextView baseButton(String text, int color) {
        TextView t = new TextView(getContext());
        t.setText(text);
        t.setTextColor(COLOR_TEXT);
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
        TextView t = baseButton(text, Color.TRANSPARENT);
        t.setTextSize(13);
        t.setPadding(dp(14), 0, dp(14), 0);
        return t;
    }

    private void styleTab(TextView t, boolean active) {
        t.setBackground(rounded(active ? accent : Color.TRANSPARENT, 8));
        t.setTextColor(active ? COLOR_ON_ACCENT : COLOR_TAB_OFF);
    }

    private TextView actionButton(String text, OnClickListener l) {
        TextView t = baseButton(text, COLOR_SURFACE);
        t.setOnClickListener(l);
        return t;
    }

    @SuppressLint("ClickableViewAccessibility")
    private TextView holdButton(String text, final byte button, String description, int color) {
        TextView t = baseButton(text, color);
        t.setTextSize(13);
        if (description != null) {
            t.setContentDescription(description);
        }
        t.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
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
        row.setBackground(pressable(COLOR_SURFACE, 10));
        row.setPadding(dp(12), 0, dp(12), 0);
        row.setClickable(true);
        row.setContentDescription(label + (combo.isEmpty() ? "" : ", " + combo));
        row.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            sendCombo(sink, vks);
        });

        TextView name = new TextView(getContext());
        name.setText(label);
        name.setTextColor(COLOR_TEXT);
        name.setTextSize(13);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        row.addView(name, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        TextView hint = new TextView(getContext());
        hint.setText(combo);
        hint.setTextColor(COLOR_MUTED);
        hint.setTextSize(11);
        row.addView(hint, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f);
        if (parent.getChildCount() > 0) {
            lp.topMargin = dp(6);
        }
        parent.addView(row, lp);
    }
}
