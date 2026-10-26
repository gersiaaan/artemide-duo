package com.limelight.duo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;

import com.limelight.nvstream.input.MouseButtonPacket;

import java.util.ArrayList;
import java.util.List;

import static com.limelight.duo.DuoKeys.*;

/**
 * German QWERTZ keyboard that doubles as a trackpad.
 * Tap = key, swipe = mouse, 2-finger tap = left click, 3-finger tap = right click,
 * 2-finger swipe = scroll, long press + move = drag.
 * Modifiers: tap once = next key only, tap twice = locked, tap again = off.
 */
public class DuoKeyboardView extends DuoTouchSurface {

    private static final int COLOR_KEY = DuoColors.KEY;
    private static final int COLOR_KEY_FN = DuoColors.KEY_FN;
    private static final int COLOR_KEY_PRESSED = DuoColors.PRESSED;
    private static final int COLOR_TEXT = DuoColors.TEXT;
    private static final int COLOR_TEXT_DARK = DuoColors.ON_ACCENT;
    private static final int COLOR_HINT = DuoColors.MUTED;

    /** Preferred (flat) row heights in dp; rows shrink if the panel is smaller. */
    private static final float FN_ROW_DP = 38f;
    private static final float ROW_DP = 52f;

    private static final class Key {
        final String label;
        final String shiftLabel;
        final int vk;
        final float units;
        final boolean modifier;
        final boolean repeatable;
        final RectF rect = new RectF();

        Key(String label, String shiftLabel, int vk, float units, boolean modifier, boolean repeatable) {
            this.label = label;
            this.shiftLabel = shiftLabel;
            this.vk = vk;
            this.units = units;
            this.modifier = modifier;
            this.repeatable = repeatable;
        }
    }

    private final List<List<Key>> rows = new ArrayList<>();
    private final float[] rowWeights = {0.8f, 1f, 1f, 1f, 1f, 1f};

    /** 0 = off, 1 = next key only, 2 = locked. Indexed by VK. */
    private final int[] modState = new int[256];

    private final Paint keyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int accent = DuoColors.ACCENT;
    private Key pressed;
    private final float hintHeight;

    public DuoKeyboardView(Context context) {
        super(context);
        hintHeight = 18 * density;
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(density);
        borderPaint.setColor(DuoColors.BORDER);
        textPaint.setColor(COLOR_TEXT);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        hintPaint.setColor(COLOR_HINT);
        hintPaint.setTextAlign(Paint.Align.CENTER);
        hintPaint.setTextSize(10.5f * density);
        lockPaint.setColor(COLOR_TEXT_DARK);
        buildLayout();
    }

    public void setAccent(int color) {
        accent = color;
        invalidate();
    }

    // ---------------- layout ----------------

    private static Key k(String l, String s, int vk) { return new Key(l, s, vk, 1f, false, false); }
    private static Key w(String l, int vk, float u) { return new Key(l, null, vk, u, false, false); }
    private static Key r(String l, int vk, float u) { return new Key(l, null, vk, u, false, true); }
    private static Key mod(String l, int vk, float u) { return new Key(l, null, vk, u, true, false); }

    private void buildLayout() {
        List<Key> fn = new ArrayList<>();
        fn.add(w("Esc", VK_ESCAPE, 1f));
        for (int i = 0; i < 12; i++) {
            fn.add(w("F" + (i + 1), VK_F1 + i, 1f));
        }
        fn.add(r("Entf", VK_DELETE, 1.5f));
        rows.add(fn);

        List<Key> num = new ArrayList<>();
        num.add(k("^", "°", VK_OEM_3));
        String[] digits = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"};
        String[] shifted = {"!", "\"", "§", "$", "%", "&", "/", "(", ")", "="};
        for (int i = 0; i < 10; i++) {
            num.add(k(digits[i], shifted[i], vk(digits[i].charAt(0))));
        }
        num.add(k("ß", "?", VK_OEM_MINUS));
        num.add(k("´", "`", VK_OEM_PLUS));
        num.add(r("⌫", VK_BACK, 1.6f));
        rows.add(num);

        // German labels, US positional VKs (z and y are swapped on QWERTZ).
        List<Key> top = new ArrayList<>();
        top.add(w("Tab", VK_TAB, 1.5f));
        String topLetters = "qwertzuiop";
        String topUs = "qwertyuiop";
        for (int i = 0; i < topLetters.length(); i++) {
            top.add(k(String.valueOf(topLetters.charAt(i)), null, vk(topUs.charAt(i))));
        }
        top.add(k("ü", null, VK_OEM_4));
        top.add(k("+", "*", VK_OEM_6));
        top.add(k("#", "'", VK_OEM_5));
        rows.add(top);

        List<Key> home = new ArrayList<>();
        home.add(w("Caps", VK_CAPITAL, 1.8f));
        for (char c : "asdfghjkl".toCharArray()) {
            home.add(k(String.valueOf(c), null, vk(c)));
        }
        home.add(k("ö", null, VK_OEM_1));
        home.add(k("ä", null, VK_OEM_7));
        home.add(w("↵", VK_RETURN, 1.7f));
        rows.add(home);

        List<Key> bottom = new ArrayList<>();
        bottom.add(mod("⇧", VK_LSHIFT, 1.3f));
        bottom.add(k("<", ">", VK_OEM_102));
        String botLetters = "yxcvbnm";
        String botUs = "zxcvbnm";
        for (int i = 0; i < botLetters.length(); i++) {
            bottom.add(k(String.valueOf(botLetters.charAt(i)), null, vk(botUs.charAt(i))));
        }
        bottom.add(k(",", ";", VK_OEM_COMMA));
        bottom.add(k(".", ":", VK_OEM_PERIOD));
        bottom.add(k("-", "_", VK_OEM_2));
        bottom.add(mod("⇧", VK_LSHIFT, 2.2f));
        rows.add(bottom);

        List<Key> space = new ArrayList<>();
        space.add(mod("Alt", VK_LMENU, 1.3f));
        space.add(mod("Win", VK_LWIN, 1.2f));
        space.add(mod("Strg", VK_LCONTROL, 1.2f));
        space.add(r("Leertaste", VK_SPACE, 5.6f));
        space.add(mod("AltGr", VK_RMENU, 1.2f));
        space.add(r("←", VK_LEFT, 1f));
        space.add(r("↑", VK_UP, 1f));
        space.add(r("↓", VK_DOWN, 1f));
        space.add(r("→", VK_RIGHT, 1f));
        rows.add(space);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutKeys(w, h);
    }

    private void layoutKeys(int width, int height) {
        float gap = 5 * density;
        float available = height - hintHeight - gap * (rows.size() - 1);
        float wanted = 0;
        for (float wgt : rowWeights) {
            wanted += (wgt < 1f ? FN_ROW_DP : ROW_DP) * density;
        }
        // Flat keys anchored at the bottom; the space above stays free (still trackpad).
        float scale = Math.min(1f, available / wanted);
        float total = wanted * scale + gap * (rows.size() - 1);
        float y = height - hintHeight - total;

        float rowH = ROW_DP * density * scale;
        for (int ri = 0; ri < rows.size(); ri++) {
            List<Key> row = rows.get(ri);
            float h = (rowWeights[ri] < 1f ? FN_ROW_DP : ROW_DP) * density * scale;
            float units = 0;
            for (Key key : row) {
                units += key.units;
            }
            float unitW = (width - gap * (row.size() - 1)) / units;
            float x = 0;
            for (Key key : row) {
                float kw = unitW * key.units;
                key.rect.set(x + density / 2, y + density / 2, x + kw - density / 2, y + h - density / 2);
                x += kw + gap;
            }
            y += h + gap;
        }
        textPaint.setTextSize(Math.min(rowH * 0.34f, 18 * density));
    }

    // ---------------- drawing ----------------

    @Override
    protected void onDraw(Canvas canvas) {
        boolean shift = modState[VK_LSHIFT] > 0;
        float radius = 7 * density;
        float smallText = textPaint.getTextSize() * 0.7f;
        float normalText = textPaint.getTextSize();

        for (List<Key> row : rows) {
            for (Key key : row) {
                int state = key.modifier ? modState[key.vk] : 0;
                int bg;
                if (state > 0) {
                    bg = accent;
                } else if (key == pressed) {
                    bg = COLOR_KEY_PRESSED;
                } else if (key.modifier || key.label.length() > 2) {
                    bg = COLOR_KEY_FN;
                } else {
                    bg = COLOR_KEY;
                }
                keyPaint.setColor(bg);
                canvas.drawRoundRect(key.rect, radius, radius, keyPaint);
                borderPaint.setColor(state > 0 ? accent : DuoColors.BORDER);
                canvas.drawRoundRect(key.rect, radius, radius, borderPaint);

                String text = key.label;
                if (shift && key.shiftLabel != null) {
                    text = key.shiftLabel;
                } else if (shift && text.length() == 1 && Character.isLetter(text.charAt(0))) {
                    text = text.toUpperCase();
                }
                textPaint.setTextSize(text.length() > 2 ? smallText : normalText);
                textPaint.setColor(state > 0 ? COLOR_TEXT_DARK : COLOR_TEXT);
                float ty = key.rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f;
                canvas.drawText(text, key.rect.centerX(), ty, textPaint);

                if (state == 2) {
                    // Small bar marks a locked modifier.
                    float bw = 10 * density;
                    float by = key.rect.bottom - 6 * density;
                    canvas.drawRoundRect(key.rect.centerX() - bw / 2, by, key.rect.centerX() + bw / 2,
                            by + 2 * density, density, density, lockPaint);
                }
            }
        }
        textPaint.setTextSize(normalText);

        canvas.drawText("Tippen = Taste · Wischen = Maus · 2 Finger: Klick / Scrollen · 3 Finger: Rechtsklick · Lang drücken = Ziehen",
                getWidth() / 2f, getHeight() - 5 * density, hintPaint);
    }

    // ---------------- input ----------------

    private Key keyAt(float x, float y) {
        Key best = null;
        float bestDist = Float.MAX_VALUE;
        for (List<Key> row : rows) {
            for (Key key : row) {
                if (key.rect.contains(x, y)) {
                    return key;
                }
                // Snap taps in the gaps to the nearest key.
                float dx = Math.max(Math.max(key.rect.left - x, 0), x - key.rect.right);
                float dy = Math.max(Math.max(key.rect.top - y, 0), y - key.rect.bottom);
                float d = dx * dx + dy * dy;
                if (d < bestDist) {
                    bestDist = d;
                    best = key;
                }
            }
        }
        return bestDist < (8 * density) * (8 * density) ? best : null;
    }

    @Override
    protected void onPress(float x, float y) {
        pressed = keyAt(x, y);
        invalidate();
    }

    @Override
    protected void onCancelPress() {
        if (pressed != null) {
            pressed = null;
            invalidate();
        }
    }

    @Override
    protected boolean isRepeatable(float x, float y) {
        Key key = keyAt(x, y);
        return key != null && key.repeatable;
    }

    @Override
    protected void onTap(float x, float y) {
        Key key = keyAt(x, y);
        if (key == null) {
            return;
        }
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);

        if (key.modifier) {
            modState[key.vk] = (modState[key.vk] + 1) % 3;
            invalidate();
            return;
        }

        List<Integer> combo = new ArrayList<>();
        for (int vk : new int[]{VK_LCONTROL, VK_LMENU, VK_RMENU, VK_LWIN, VK_LSHIFT}) {
            if (modState[vk] > 0) {
                combo.add(vk);
            }
        }
        combo.add(key.vk);
        int[] vks = new int[combo.size()];
        for (int i = 0; i < vks.length; i++) {
            vks[i] = combo.get(i);
        }
        DuoKeys.sendCombo(sink, vks);

        // Release one-shot modifiers.
        boolean changed = false;
        for (int i = 0; i < modState.length; i++) {
            if (modState[i] == 1) {
                modState[i] = 0;
                changed = true;
            }
        }
        if (changed) {
            invalidate();
        }
    }

    @Override
    protected void onTwoFingerTap() {
        click(MouseButtonPacket.BUTTON_LEFT);
    }

    @Override
    protected void onThreeFingerTap() {
        click(MouseButtonPacket.BUTTON_RIGHT);
    }

    private void click(byte button) {
        if (sink == null) {
            return;
        }
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        sink.sendMouseButton(button, true);
        sink.sendMouseButton(button, false);
    }
}
