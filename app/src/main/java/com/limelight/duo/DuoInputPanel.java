package com.limelight.duo;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.nvstream.input.MouseButtonPacket;

import static com.limelight.duo.DuoKeys.*;

/**
 * Lower-screen panel with three masks:
 *  - Tastatur: full PC keyboard that is also a trackpad, click buttons below
 *  - Trackpad: big trackpad with shortcuts
 *  - Kompakt:  trackpad + one-handed phone keyboard side by side (tap the tab again to swap)
 * Top bar (mirrored): [hide] [menu] [mouse speed] [DE/EN] [Aa] ... [Tastatur | Trackpad | Kompakt]
 */
@SuppressLint("ViewConstructor")
public class DuoInputPanel extends LinearLayout implements DuoBridge.SpeedListener {
    private static final int MODE_KEYBOARD = 0;
    private static final int MODE_TRACKPAD = 1;
    private static final int MODE_COMPACT = 2;

    private final float density;
    private final DuoInputSink sink;
    private final DuoAutocorrect autocorrect;
    private final Runnable onHide;

    private final DuoKeyboardView keyboard;
    private final DuoKeyboardView compactKeyboard;
    private final DuoTrackpadView pad;
    private final DuoTrackpadView compactPad;
    private final DuoImeView ime;

    private final View[] modes = new View[3];
    private final TextView[] tabs = new TextView[3];
    private final TextView langButton;
    private LinearLayout compactRow;
    private View compactPadColumn;
    private View compactKeyColumn;

    private int mode;
    private boolean swapped;

    // Swipe up from the bottom edge hides the panel (panel on the other screen only).
    private float swipeStartY = -1;

    /**
     * @param onHide if not null, the panel runs on the other screen; it then gets a
     *               keyboard button and a swipe-up gesture that call it to hide the panel.
     */
    public DuoInputPanel(Context context, DuoInputSink sink, Runnable onHide) {
        super(context);
        this.sink = sink;
        this.onHide = onHide;
        this.density = context.getResources().getDisplayMetrics().density;
        this.autocorrect = new DuoAutocorrect(context, sink);

        setOrientation(VERTICAL);
        setBackgroundColor(DuoColors.BG);
        int pad8 = dp(8);
        setPadding(pad8, pad8, pad8, pad8);
        // Swallow touches so nothing falls through to the stream below.
        setClickable(true);

        // Gboard types into an invisible field that forwards everything to the PC.
        ime = new DuoImeView(context, sink);
        addView(ime, new LayoutParams(1, 1));

        // ---------- top bar (mirrored: buttons left, tabs right) ----------
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        addView(bar, new LayoutParams(LayoutParams.MATCH_PARENT, dp(44)));

        boolean first = true;
        if (onHide != null) {
            ImageButton hide = iconButton(context.getDrawable(R.drawable.ic_duo_keyboard), "Tastatur und Trackpad ausblenden");
            hide.setOnClickListener(v -> onHide.run());
            addBarView(bar, hide, dp(44), false);
            first = false;
        }
        TextView menu = actionButton("⋯", v -> sink.showStreamMenu());
        menu.setContentDescription("Stream-Menü");
        menu.setTextSize(18);
        addBarView(bar, menu, dp(44), !first);

        ImageButton mouse = iconButton(context.getDrawable(R.drawable.ic_duo_mouse), "Trackpad-Tempo");
        mouse.setOnClickListener(v -> {
            if (getContext() instanceof Activity) {
                DuoController.showSpeedDialog((Activity) getContext());
            }
        });
        addBarView(bar, mouse, dp(44), true);

        langButton = actionButton(DuoBridge.getLanguage(context).toUpperCase(), v -> toggleLanguage());
        langButton.setTextSize(12);
        langButton.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        langButton.setContentDescription("Sprache umschalten");
        langButton.setPadding(0, 0, 0, 0);
        addBarView(bar, langButton, dp(44), true);

        ImageButton gboard = iconButton(new DuoAaDrawable(density, DuoColors.TEXT), "Handy-Tastatur (Gboard)");
        gboard.setOnClickListener(v -> {
            autocorrect.onBoundary();
            ime.toggle();
        });
        addBarView(bar, gboard, dp(44), true);

        bar.addView(new View(context), new LayoutParams(0, 1, 1f));

        LinearLayout segment = new LinearLayout(context);
        segment.setOrientation(HORIZONTAL);
        segment.setBackground(outlined(DuoColors.SURFACE, 10));
        segment.setPadding(dp(3), dp(3), dp(3), dp(3));
        bar.addView(segment, new LayoutParams(LayoutParams.WRAP_CONTENT, dp(44)));
        String[] names = {"Tastatur", "Trackpad", "Kompakt"};
        // Shown left to right: Kompakt | Trackpad | Tastatur.
        int[] order = {MODE_COMPACT, MODE_TRACKPAD, MODE_KEYBOARD};
        for (int pos = 0; pos < 3; pos++) {
            final int m = order[pos];
            tabs[m] = tab(names[m]);
            tabs[m].setOnClickListener(v -> onTab(m));
            LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT);
            if (pos > 0) {
                lp.leftMargin = dp(3);
            }
            segment.addView(tabs[m], lp);
        }

        // ---------- content ----------
        FrameLayout content = new FrameLayout(context);
        LayoutParams cp = new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f);
        cp.topMargin = dp(8);
        addView(content, cp);

        keyboard = new DuoKeyboardView(context, DuoKeyboardView.LAYOUT_FULL);
        keyboard.setSink(sink);
        keyboard.setAutocorrect(autocorrect);
        compactKeyboard = new DuoKeyboardView(context, DuoKeyboardView.LAYOUT_COMPACT);
        compactKeyboard.setSink(sink);
        compactKeyboard.setAutocorrect(autocorrect);
        pad = new DuoTrackpadView(context);
        pad.setSink(sink);
        compactPad = new DuoTrackpadView(context);
        compactPad.setSink(sink);

        modes[MODE_KEYBOARD] = buildKeyboardMode(context);
        modes[MODE_TRACKPAD] = buildTrackpadMode(context);
        modes[MODE_COMPACT] = buildCompactMode(context);
        for (View m : modes) {
            content.addView(m, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }

        onSpeedChanged(DuoBridge.getSpeed(context));
        swapped = DuoBridge.isCompactSwapped(context);
        applySwap();
        int saved = DuoBridge.getPanelMode(context);
        showMode(saved >= 0 && saved <= 2 ? saved : MODE_KEYBOARD);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        DuoBridge.addSpeedListener(this);
        onSpeedChanged(DuoBridge.getSpeed(getContext()));
    }

    @Override
    protected void onDetachedFromWindow() {
        DuoBridge.removeSpeedListener(this);
        autocorrect.close();
        super.onDetachedFromWindow();
    }

    @Override
    public void onSpeedChanged(int speed) {
        float gain = DuoBridge.gainFor(speed);
        keyboard.setSensitivity(gain);
        pad.setSensitivity(gain);
        compactPad.setSensitivity(gain);
    }

    // ---------- swipe up from the bottom edge ----------

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (onHide == null) {
            return false;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                swipeStartY = e.getY() >= getHeight() - dp(28) ? e.getY() : -1;
                break;
            case MotionEvent.ACTION_MOVE:
                if (swipeStartY >= 0 && swipeStartY - e.getY() > dp(70)) {
                    swipeStartY = -1;
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    onHide.run();
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                swipeStartY = -1;
                break;
        }
        return false;
    }

    // ---------- modes ----------

    private void onTab(int m) {
        if (m == MODE_COMPACT && mode == MODE_COMPACT) {
            // Tapping Kompakt again swaps trackpad and keyboard.
            swapped = !swapped;
            DuoBridge.setCompactSwapped(getContext(), swapped);
            applySwap();
            return;
        }
        showMode(m);
    }

    private void showMode(int m) {
        mode = m;
        for (int i = 0; i < 3; i++) {
            modes[i].setVisibility(i == m ? VISIBLE : GONE);
            styleTab(tabs[i], i == m);
        }
        tabs[MODE_COMPACT].setText(m == MODE_COMPACT ? "Kompakt ⇄" : "Kompakt");
        DuoBridge.setPanelMode(getContext(), m);
        autocorrect.onBoundary();
    }

    private void applySwap() {
        if (compactRow == null) {
            return;
        }
        compactRow.removeAllViews();
        View left = swapped ? compactKeyColumn : compactPadColumn;
        View right = swapped ? compactPadColumn : compactKeyColumn;
        compactRow.addView(left, columnParams(left, false));
        compactRow.addView(right, columnParams(right, true));
    }

    private LayoutParams columnParams(View v, boolean margin) {
        LayoutParams lp = v == compactKeyColumn
                ? new LayoutParams(dp(360), LayoutParams.MATCH_PARENT)
                : new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
        if (margin) {
            lp.leftMargin = dp(8);
        }
        return lp;
    }

    private void toggleLanguage() {
        String lang = "de".equals(DuoBridge.getLanguage(getContext())) ? "en" : "de";
        DuoBridge.setLanguage(getContext(), lang);
        autocorrect.setLanguage(lang);
        langButton.setText(lang.toUpperCase());
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    /** Keyboard view with the suggestion bar floating right above its keys. */
    private View keyboardWithSuggestions(Context context, DuoKeyboardView kb) {
        FrameLayout area = new FrameLayout(context);
        area.addView(kb, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        final DuoSuggestionBar suggestions = new DuoSuggestionBar(context, autocorrect);
        area.addView(suggestions, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(32), Gravity.TOP));
        kb.setKeysListener(top -> suggestions.setTranslationY(Math.max(0, top - dp(35))));
        return area;
    }

    private View buildKeyboardMode(Context context) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(VERTICAL);
        root.addView(keyboardWithSuggestions(context, keyboard), new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout clicks = new LinearLayout(context);
        clicks.setOrientation(HORIZONTAL);
        root.addView(clicks, new LayoutParams(LayoutParams.MATCH_PARENT, dp(48)));
        addWeighted(clicks, holdButton("Linksklick", MouseButtonPacket.BUTTON_LEFT), 1f, false);
        addWeighted(clicks, holdButton("Rechtsklick", MouseButtonPacket.BUTTON_RIGHT), 1f, true);
        return root;
    }

    private View buildCompactMode(Context context) {
        compactRow = new LinearLayout(context);
        compactRow.setOrientation(HORIZONTAL);

        LinearLayout padCol = new LinearLayout(context);
        padCol.setOrientation(VERTICAL);
        padCol.addView(compactPad, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout clicks = new LinearLayout(context);
        clicks.setOrientation(HORIZONTAL);
        LayoutParams clp = new LayoutParams(LayoutParams.MATCH_PARENT, dp(52));
        clp.topMargin = dp(6);
        padCol.addView(clicks, clp);
        addWeighted(clicks, holdButton("Links", MouseButtonPacket.BUTTON_LEFT), 1f, false);
        addWeighted(clicks, holdButton("Rechts", MouseButtonPacket.BUTTON_RIGHT), 1f, true);
        compactPadColumn = padCol;

        compactKeyColumn = keyboardWithSuggestions(context, compactKeyboard);
        return compactRow;
    }

    private View buildTrackpadMode(Context context) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(HORIZONTAL);

        LinearLayout shortcuts = new LinearLayout(context);
        shortcuts.setOrientation(VERTICAL);
        root.addView(shortcuts, new LayoutParams(dp(168), LayoutParams.MATCH_PARENT));

        // Note: on a German host layout, Strg+Z is the key at the US "Y" position.
        addShortcut(shortcuts, "Kopieren", "Strg+C", VK_LCONTROL, vk('c'));
        addShortcut(shortcuts, "Ausschneiden", "Strg+X", VK_LCONTROL, vk('x'));
        addShortcut(shortcuts, "Einfügen", "Strg+V", VK_LCONTROL, vk('v'));
        addShortcut(shortcuts, "Rückgängig", "Strg+Z", VK_LCONTROL, vk('y'));
        addShortcut(shortcuts, "Fenster", "Alt+Tab", VK_LMENU, VK_TAB);
        addShortcut(shortcuts, "Tab wechseln", "Strg+Tab", VK_LCONTROL, VK_TAB);
        addShortcut(shortcuts, "Übersicht", "Win+Tab", VK_LWIN, VK_TAB);
        addShortcut(shortcuts, "Desktop", "Win+D", VK_LWIN, vk('d'));
        // Strg+Alt+Entf can't be injected (Windows only accepts it from real hardware).
        addShortcut(shortcuts, "Task-Manager", "Strg+⇧+Esc", VK_LCONTROL, VK_LSHIFT, VK_ESCAPE);
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
        addWeighted(clicks, holdButton("Links", MouseButtonPacket.BUTTON_LEFT), 1f, false);
        addWeighted(clicks, holdButton("Rechts", MouseButtonPacket.BUTTON_RIGHT), 1f, true);
        return root;
    }

    // ---------- view helpers ----------

    private int dp(float v) {
        return Math.round(v * density);
    }

    private GradientDrawable outlined(int color, int stroke, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusDp * density);
        d.setStroke(Math.max(1, Math.round(density)), stroke);
        return d;
    }

    private GradientDrawable outlined(int color, float radiusDp) {
        return outlined(color, DuoColors.BORDER, radiusDp);
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

    private ImageButton iconButton(android.graphics.drawable.Drawable icon, String description) {
        ImageButton b = new ImageButton(getContext());
        b.setImageDrawable(icon);
        // Fixed 22dp icon, exactly centred (no automatic scaling).
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        b.setPadding(dp(11), dp(11), dp(11), dp(11));
        b.setBackground(pressable(DuoColors.SURFACE, 10));
        b.setContentDescription(description);
        b.setFocusable(false);
        return b;
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
        if (active) {
            t.setBackground(outlined(DuoColors.HI_BG, DuoColors.HI_BORDER, 8));
            t.setTextColor(DuoColors.HI_TEXT);
        } else {
            GradientDrawable d = new GradientDrawable();
            d.setColor(Color.TRANSPARENT);
            t.setBackground(d);
            t.setTextColor(DuoColors.TEXT);
        }
    }

    private TextView actionButton(String text, OnClickListener l) {
        TextView t = baseButton(text, DuoColors.SURFACE);
        t.setOnClickListener(l);
        return t;
    }

    @SuppressLint("ClickableViewAccessibility")
    private TextView holdButton(String text, final byte button) {
        TextView t = baseButton(text, DuoColors.KEY);
        t.setTextSize(13);
        t.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    autocorrect.onBoundary();
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

    private void addBarView(LinearLayout bar, View v, int width, boolean margin) {
        LayoutParams lp = new LayoutParams(width, dp(44));
        if (margin) {
            lp.leftMargin = dp(6);
        }
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
            autocorrect.onBoundary();
            sendWithClipboard(sink, false, vks);
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
        hint.setTextSize(10);
        row.addView(hint, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f);
        if (parent.getChildCount() > 0) {
            lp.topMargin = dp(4);
        }
        parent.addView(row, lp);
    }
}
