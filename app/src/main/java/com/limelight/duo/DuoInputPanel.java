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
        // On the other screen the home indicator sits at the bottom instead of the padding.
        setPadding(pad8, pad8, pad8, onHide != null ? 0 : pad8);
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
            ImageButton hide = iconButton(context.getDrawable(R.drawable.ic_duo_keyboard), "Hide keyboard and trackpad");
            hide.setOnClickListener(v -> onHide.run());
            addBarView(bar, hide, dp(44), false);
            first = false;
        }
        TextView menu = actionButton("⋯", v -> sink.showStreamMenu());
        menu.setContentDescription("Stream menu");
        menu.setTextSize(18);
        addBarView(bar, menu, dp(44), !first);

        // Disconnect: hold 1.5 s (a ring runs around the button), letting go early cancels.
        DuoHoldButton disconnect = new DuoHoldButton(context, 1500, sink::disconnect);
        disconnect.setImageDrawable(context.getDrawable(R.drawable.ic_duo_power));
        disconnect.setScaleType(ImageView.ScaleType.FIT_CENTER);
        disconnect.setPadding(dp(12), dp(12), dp(12), dp(12));
        disconnect.setBackground(pressable(DuoColors.SURFACE, 10));
        disconnect.setContentDescription("Disconnect, hold for 1.5 seconds");
        addBarView(bar, disconnect, dp(44), true);

        ImageButton gboard = iconButton(new DuoAaDrawable(density, DuoColors.TEXT), "Phone keyboard (Gboard)");
        gboard.setOnClickListener(v -> {
            autocorrect.onBoundary();
            ime.toggle();
        });
        addBarView(bar, gboard, dp(44), true);

        langButton = actionButton(DuoBridge.getLanguage(context).toUpperCase(), v -> toggleLanguage());
        langButton.setTextSize(12);
        langButton.setTypeface(DuoFonts.bold());
        langButton.setContentDescription("Switch language");
        langButton.setPadding(0, 0, 0, 0);
        addBarView(bar, langButton, dp(44), true);

        ImageButton perf = iconButton(context.getDrawable(R.drawable.ic_duo_perf), "Performance overlay on/off");
        perf.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            sink.togglePerformanceOverlay();
        });
        addBarView(bar, perf, dp(44), true);

        // Controller mode for desktop / remote streams: G = gamepad, D = desktop (mouse).
        if (DuoGamepadMouse.available()) {
            FrameLayout pad = new FrameLayout(context);
            ImageButton padIcon = iconButton(context.getDrawable(R.drawable.ic_duo_gamepad), "Controller: gamepad or desktop");
            pad.addView(padIcon, new FrameLayout.LayoutParams(-1, -1));
            final TextView padLetter = new TextView(context);
            padLetter.setTextSize(9);
            padLetter.setTypeface(DuoFonts.bold());
            FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
            ll.rightMargin = dp(4);
            ll.bottomMargin = dp(2);
            pad.addView(padLetter, ll);
            final Runnable paint = () -> {
                boolean d = DuoGamepadMouse.isDesktop();
                padLetter.setText(d ? "D" : "G");
                padLetter.setTextColor(d ? DuoColors.HI_TEXT : DuoColors.MUTED);
                padIcon.setBackground(d ? outlined(DuoColors.SURFACE, DuoColors.HI_TEXT, 10) : pressable(DuoColors.SURFACE, 10));
            };
            padIcon.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                DuoGamepadMouse.toggle(getContext());
                paint.run();
            });
            paint.run();
            // Also follow the toggle in the stream menu.
            DuoGamepadMouse.setListener(() -> post(paint));
            addBarView(bar, pad, dp(44), true);
        }

        // Clock and battery of the Duo, centred between the buttons and the tabs.
        DuoStatusView status = new DuoStatusView(context);
        status.setGravity(Gravity.CENTER);
        LayoutParams sp = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
        sp.leftMargin = dp(6);
        sp.rightMargin = dp(6);
        bar.addView(status, sp);

        LinearLayout segment = new LinearLayout(context);
        segment.setOrientation(HORIZONTAL);
        segment.setBackground(outlined(DuoColors.SURFACE, 10));
        segment.setPadding(dp(3), dp(3), dp(3), dp(3));
        bar.addView(segment, new LayoutParams(LayoutParams.WRAP_CONTENT, dp(44)));
        String[] names = {"Keyboard", "Trackpad", "Compact"};
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

        if (onHide != null) {
            addView(homeIndicator(context), new LayoutParams(LayoutParams.MATCH_PARENT, dp(20)));
        }

        onSpeedChanged(DuoBridge.getSpeed(context));
        swapped = DuoBridge.isCompactSwapped(context);
        applySwap();
        int saved = DuoBridge.getPanelMode(context);
        showMode(saved >= 0 && saved <= 2 ? saved : MODE_KEYBOARD);
        // Touch only: the controller stays in the stream and never selects panel buttons.
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        setFocusable(false);
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
        if (phoneKeyboard != null) {
            phoneKeyboard.setSensitivity(gain);
        }
        pad.setSensitivity(gain);
        compactPad.setSensitivity(gain);
    }

    // ---------- Apple-style home indicator: swipe it up to hide the panel ----------

    @SuppressLint("ClickableViewAccessibility")
    private View homeIndicator(Context context) {
        FrameLayout strip = new FrameLayout(context);
        strip.setContentDescription("Swipe up to hide keyboard and trackpad");
        View bar = new View(context);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x52FFFFFF);
        g.setCornerRadius(dp(3));
        bar.setBackground(g);
        strip.addView(bar, new FrameLayout.LayoutParams(dp(120), dp(5), Gravity.CENTER));
        final float[] start = {0};
        strip.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    start[0] = e.getRawY();
                    animate().cancel();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dy = Math.min(0, e.getRawY() - start[0]);
                    setTranslationY(dy * 0.6f);   // the panel follows the finger, a little damped
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    float dy = e.getRawY() - start[0];
                    if (e.getActionMasked() == MotionEvent.ACTION_UP && dy < -dp(40)) {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        onHide.run();
                    } else {
                        animate().translationY(0).setDuration(180).start();
                    }
                    return true;
                }
            }
            return true;
        });
        return strip;
    }

    // ---------- modes ----------

    private void onTab(int m) {
        if (m == MODE_KEYBOARD && mode == MODE_KEYBOARD) {
            // Tapping Keyboard again cycles: PC keyboard -> Slim -> Wide -> PC keyboard.
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(getContext()).edit()
                    .putInt("duo_kb_mode", (kbMode + 1) % 3).apply();
            applyPhone();
            return;
        }
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
        tabs[MODE_COMPACT].setText(m == MODE_COMPACT ? "Compact ⇄" : "Compact");
        tabs[MODE_KEYBOARD].setText(m == MODE_KEYBOARD ? "Keyboard " + (kbMode + 1) : "Keyboard");
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
        FrameLayout area = new FrameLayout(context);
        root.addView(area, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        fullKeyboardArea = keyboardWithSuggestions(context, keyboard);
        area.addView(fullKeyboardArea, new FrameLayout.LayoutParams(-1, -1));
        // Phone layout: the compact keys, a bit narrower than the full keyboard, centred.
        phoneKeyboard = new DuoKeyboardView(context, DuoKeyboardView.LAYOUT_COMPACT);
        phoneKeyboard.setSink(sink);
        phoneKeyboard.setAutocorrect(autocorrect);
        phoneKeyboard.setPhoneMode(true);
        phoneKeyboard.setCompactRowsDp(58, 58);
        phoneKeyboardArea = keyboardWithSuggestions(context, phoneKeyboard);
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL);
        area.addView(phoneKeyboardArea, pl);
        applyPhone();

        LinearLayout clicks = new LinearLayout(context);
        clicks.setOrientation(HORIZONTAL);
        root.addView(clicks, new LayoutParams(LayoutParams.MATCH_PARENT, dp(48)));
        addWeighted(clicks, holdButton("Left click", MouseButtonPacket.BUTTON_LEFT), 1f, false);
        addWeighted(clicks, holdButton("Right click", MouseButtonPacket.BUTTON_RIGHT), 1f, true);
        return root;
    }

    private View fullKeyboardArea, phoneKeyboardArea;
    private DuoKeyboardView phoneKeyboard;
    private int kbMode;

    private void applyPhone() {
        android.content.SharedPreferences sp = androidx.preference.PreferenceManager.getDefaultSharedPreferences(getContext());
        kbMode = sp.getInt("duo_kb_mode", sp.getBoolean("duo_kb_phone", false) ? 1 : 0);
        boolean phone = kbMode != 0;
        fullKeyboardArea.setVisibility(phone ? GONE : VISIBLE);
        phoneKeyboardArea.setVisibility(phone ? VISIBLE : GONE);
        // Slim: 11 % side margins, normal keys. Wide: full width, taller keys.
        FrameLayout.LayoutParams pl = (FrameLayout.LayoutParams) phoneKeyboardArea.getLayoutParams();
        int side = kbMode == 1 ? Math.round(getResources().getDisplayMetrics().widthPixels * 0.11f) : 0;
        pl.leftMargin = side;
        pl.rightMargin = side;
        phoneKeyboardArea.setLayoutParams(pl);
        float row = kbMode == 2 ? 68 : 58;
        phoneKeyboard.setCompactRowsDp(row, row);
        if (tabs[MODE_KEYBOARD] != null && mode == MODE_KEYBOARD) {
            tabs[MODE_KEYBOARD].setText("Keyboard " + (kbMode + 1));
        }
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
        addWeighted(clicks, holdButton("Left", MouseButtonPacket.BUTTON_LEFT), 1f, false);
        addWeighted(clicks, holdButton("Right", MouseButtonPacket.BUTTON_RIGHT), 1f, true);
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
        addShortcut(shortcuts, "Copy", "Ctrl+C", VK_LCONTROL, vk('c'));
        addShortcut(shortcuts, "Cut", "Ctrl+X", VK_LCONTROL, vk('x'));
        addShortcut(shortcuts, "Paste", "Ctrl+V", VK_LCONTROL, vk('v'));
        addShortcut(shortcuts, "Undo", "Ctrl+Z", VK_LCONTROL, vk('y'));
        addShortcut(shortcuts, "Switch window", "Alt+Tab", VK_LMENU, VK_TAB);
        addShortcut(shortcuts, "Next tab", "Ctrl+Tab", VK_LCONTROL, VK_TAB);
        addShortcut(shortcuts, "Task view", "Win+Tab", VK_LWIN, VK_TAB);
        addShortcut(shortcuts, "Desktop", "Win+D", VK_LWIN, vk('d'));
        // Strg+Alt+Entf can't be injected (Windows only accepts it from real hardware).
        addShortcut(shortcuts, "Task Manager", "Ctrl+⇧+Esc", VK_LCONTROL, VK_LSHIFT, VK_ESCAPE);
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
        addWeighted(clicks, holdButton("Left", MouseButtonPacket.BUTTON_LEFT), 1f, false);
        addWeighted(clicks, holdButton("Right", MouseButtonPacket.BUTTON_RIGHT), 1f, true);
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
        t.setTypeface(DuoFonts.medium());
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
        t.setTypeface(DuoFonts.bold());
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
        name.setTypeface(DuoFonts.medium());
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
