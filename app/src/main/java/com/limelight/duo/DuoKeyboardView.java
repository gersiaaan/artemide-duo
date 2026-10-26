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
 * German QWERTZ keyboard (Gboard-style stagger, no umlaut keys) that doubles as a trackpad.
 * Tap = key, swipe = mouse, 2-finger tap = left click, 3-finger tap = right click,
 * 2-finger swipe = scroll, long press + move = drag.
 * Modifiers: tap once = next key only, tap twice = locked, tap again = off.
 */
public class DuoKeyboardView extends DuoTouchSurface {

    private static final int COLOR_SUBTLE_BG = 0xFF08090A;
    private static final int COLOR_SUBTLE_BORDER = 0xFF1A1C20;

    /** Row heights in dp; rows shrink if the panel is smaller. */
    private static final float FN_ROW_DP = 24f;
    private static final float ROW_DP = 58f;

    private static final class Key {
        final String label;
        String shiftLabel;
        String altLabel;
        /** Long-press character (a -> ä) with its VK on a German layout. */
        String longLabel;
        int longVk;
        final int vk;
        final float units;
        boolean modifier;
        boolean repeatable;
        boolean subtle;
        /** 0 = normal, 1 = upper half of a stacked pair, 2 = lower half. */
        int stack;
        final RectF rect = new RectF();

        Key(String label, int vk, float units) {
            this.label = label;
            this.vk = vk;
            this.units = units;
        }

        Key shift(String s) { shiftLabel = s; return this; }
        Key alt(String s) { altLabel = s; return this; }
        Key hold(String s, int vk) { longLabel = s; longVk = vk; return this; }
        Key mod() { modifier = true; return this; }
        Key rep() { repeatable = true; return this; }
        Key quiet() { subtle = true; return this; }
        Key stacked(int s) { stack = s; return this; }
        /** Empty space (no key, part of the trackpad). */
        boolean spacer;
        Key space() { spacer = true; return this; }

        boolean isLetter() {
            return label.length() == 1 && Character.isLetter(label.charAt(0));
        }
    }

    private final List<List<Key>> rows = new ArrayList<>();
    private final List<Boolean> fnRow = new ArrayList<>();

    /** 0 = off, 1 = next key only, 2 = locked. Indexed by VK. */
    private final int[] modState = new int[256];

    private final Paint keyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint altPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Key pressed;
    private Key held;
    private final Paint popupPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint popupText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF popupRect = new RectF();
    private final float hintHeight;
    private float letterTextSize;
    private DuoAutocorrect autocorrect;

    public DuoKeyboardView(Context context) {
        super(context);
        hintHeight = 18 * density;
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(density);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        altPaint.setTextAlign(Paint.Align.RIGHT);
        altPaint.setColor(DuoColors.MUTED);
        altPaint.setTextSize(10 * density);
        hintPaint.setColor(DuoColors.MUTED);
        hintPaint.setTextAlign(Paint.Align.CENTER);
        hintPaint.setTextSize(10.5f * density);
        lockPaint.setColor(DuoColors.ON_ACCENT);
        popupPaint.setColor(DuoColors.ACCENT);
        popupText.setColor(DuoColors.ON_ACCENT);
        popupText.setTextAlign(Paint.Align.CENTER);
        popupText.setTextSize(24 * density);
        popupText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        buildLayout();
    }

    public void setAutocorrect(DuoAutocorrect autocorrect) {
        this.autocorrect = autocorrect;
    }

    // ---------------- layout ----------------

    private static Key k(String l, int vk) { return new Key(l, vk, 1f); }
    private static Key k(String l, int vk, float u) { return new Key(l, vk, u); }

    private void addRow(boolean fn, Key... keys) {
        List<Key> row = new ArrayList<>();
        for (Key key : keys) {
            row.add(key);
        }
        rows.add(row);
        fnRow.add(fn);
    }

    private static Key[] letters(String labels, String usPositions) {
        Key[] out = new Key[labels.length()];
        for (int i = 0; i < labels.length(); i++) {
            out[i] = k(String.valueOf(labels.charAt(i)), vk(usPositions.charAt(i)));
        }
        return out;
    }

    private static Key[] concat(Object... parts) {
        List<Key> list = new ArrayList<>();
        for (Object p : parts) {
            if (p instanceof Key) {
                list.add((Key) p);
            } else {
                for (Key key : (Key[]) p) {
                    list.add(key);
                }
            }
        }
        return list.toArray(new Key[0]);
    }

    private void buildLayout() {
        // Function row: compact and subtle.
        Key[] fn = new Key[14];
        fn[0] = k("Esc", VK_ESCAPE).quiet();
        for (int i = 0; i < 12; i++) {
            fn[i + 1] = k("F" + (i + 1), VK_F1 + i).quiet();
        }
        fn[13] = k("Entf", VK_DELETE, 2f).rep().quiet();
        addRow(true, fn);

        // Number row (units: 1 + 10 + 1 + 1 + 2 = 15)
        String[] shifted = {"!", "\"", "§", "$", "%", "&", "/", "(", ")", "="};
        String[] alts = {null, "²", "³", null, null, null, "{", "[", "]", "}"};
        Key[] digits = new Key[10];
        for (int i = 0; i < 10; i++) {
            char d = (char) ('0' + (i + 1) % 10);
            digits[i] = k(String.valueOf(d), vk(d)).shift(shifted[i]).alt(alts[i]).quiet();
        }
        addRow(false, concat(
                k("^", VK_OEM_3).shift("°").quiet(),
                digits,
                k("ß", VK_OEM_MINUS).shift("?").alt("\\").quiet(),
                k("´", VK_OEM_PLUS).shift("`").quiet(),
                k("⌫", VK_BACK, 2f).rep()));

        // Letters, Gboard-style stagger: A row +0.5, Y row starts under S.
        // German labels with US positional VKs (z and y are swapped on QWERTZ).
        Key[] top = letters("qwertzuiop", "qwertyuiop");
        top[0].alt("@");
        top[2].alt("€");
        addRow(false, concat(
                k("Tab", VK_TAB, 2f).quiet(),
                top,
                k("+", VK_OEM_6, 1.5f).shift("*").alt("~").quiet(),
                k("#", VK_OEM_5, 1.5f).shift("'").quiet()));

        top[6].hold("ü", VK_OEM_4);
        top[8].hold("ö", VK_OEM_1);
        Key[] home = letters("asdfghjkl", "asdfghjkl");
        home[0].hold("ä", VK_OEM_7);
        addRow(false, concat(
                // No Caps Lock (tap Shift twice instead); keeps the Gboard stagger.
                k("", 0, 2.5f).space(),
                home,
                k("↵", VK_RETURN, 3.5f)));

        Key[] bottom = letters("yxcvbnm", "zxcvbnm");
        bottom[6].alt("µ");
        addRow(false, concat(
                k("⇧", VK_LSHIFT, 2.5f).mod(),
                k("<", VK_OEM_102).shift(">").alt("|").quiet(),
                bottom,
                k(",", VK_OEM_COMMA).shift(";"),
                k(".", VK_OEM_PERIOD).shift(":"),
                k("-", VK_OEM_2).shift("_"),
                k("⇧", VK_LSHIFT, 1.5f).mod()));

        addRow(false,
                k("Alt", VK_LMENU, 1.3f).mod().quiet(),
                k("Win", VK_LWIN, 1.2f).mod().quiet(),
                k("Strg", VK_LCONTROL, 1.2f).mod().quiet(),
                k("Leertaste", VK_SPACE, 7.1f).rep().quiet(),
                k("AltGr", VK_RMENU, 1.2f).mod().quiet(),
                k("←", VK_LEFT).rep().quiet(),
                k("↑", VK_UP).rep().quiet().stacked(1),
                k("↓", VK_DOWN, 0f).rep().quiet().stacked(2),
                k("→", VK_RIGHT).rep().quiet());
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutKeys(w, h);
    }

    private void layoutKeys(int width, int height) {
        float gap = 5 * density;
        float wanted = 0;
        for (Boolean fn : fnRow) {
            wanted += (fn ? FN_ROW_DP : ROW_DP) * density;
        }
        float available = height - hintHeight - gap * (rows.size() - 1);
        float scale = Math.min(1f, available / wanted);
        float total = wanted * scale + gap * (rows.size() - 1);
        // Keys anchored at the bottom; any space above stays free (still trackpad).
        float y = height - hintHeight - total;

        for (int ri = 0; ri < rows.size(); ri++) {
            List<Key> row = rows.get(ri);
            float h = (fnRow.get(ri) ? FN_ROW_DP : ROW_DP) * density * scale;
            float units = 0;
            int visible = 0;
            for (Key key : row) {
                units += key.units;
                if (key.stack != 2) {
                    visible++;
                }
            }
            float unitW = (width - gap * (visible - 1)) / units;
            float x = 0;
            float prevX = 0, prevW = 0;
            float half = (h - 3 * density) / 2f;
            for (Key key : row) {
                float inset = density / 2;
                if (key.stack == 2) {
                    key.rect.set(prevX + inset, y + h - half + inset, prevX + prevW - inset, y + h - inset);
                    continue;
                }
                float kw = unitW * key.units;
                if (key.stack == 1) {
                    key.rect.set(x + inset, y + inset, x + kw - inset, y + half - inset);
                } else {
                    key.rect.set(x + inset, y + inset, x + kw - inset, y + h - inset);
                }
                prevX = x;
                prevW = kw;
                x += kw + gap;
            }
            y += h + gap;
        }
        letterTextSize = Math.min(ROW_DP * density * scale * 0.36f, 20 * density);
    }

    // ---------------- drawing ----------------

    @Override
    protected void onDraw(Canvas canvas) {
        boolean shift = modState[VK_LSHIFT] > 0;
        boolean altGr = modState[VK_RMENU] > 0;
        float radius = 7 * density;
        float subtleText = Math.min(letterTextSize * 0.62f, 12 * density);

        for (List<Key> row : rows) {
            for (Key key : row) {
                if (key.spacer) {
                    continue;
                }
                int state = key.modifier ? modState[key.vk] : 0;
                boolean quiet = key.subtle && state == 0;

                int bg, border, fg;
                if (state > 0) {
                    bg = DuoColors.ACCENT;
                    border = DuoColors.ACCENT;
                    fg = DuoColors.ON_ACCENT;
                } else if (quiet) {
                    bg = key == pressed ? DuoColors.PRESSED : COLOR_SUBTLE_BG;
                    border = COLOR_SUBTLE_BORDER;
                    fg = DuoColors.MUTED;
                } else {
                    bg = key == pressed ? DuoColors.PRESSED : (key.modifier || key.label.length() > 1 ? DuoColors.KEY_FN : DuoColors.KEY);
                    border = DuoColors.BORDER;
                    fg = DuoColors.TEXT;
                }

                float r = key.stack != 0 ? radius * 0.8f : radius;
                keyPaint.setColor(bg);
                canvas.drawRoundRect(key.rect, r, r, keyPaint);
                borderPaint.setColor(border);
                canvas.drawRoundRect(key.rect, r, r, borderPaint);

                String text = key.label;
                if (altGr && key.altLabel != null) {
                    text = key.altLabel;
                } else if (shift && key.shiftLabel != null) {
                    text = key.shiftLabel;
                } else if (shift && key.isLetter()) {
                    text = text.toUpperCase();
                }
                boolean small = quiet || text.length() > 2;
                textPaint.setTextSize(small ? subtleText : letterTextSize);
                textPaint.setColor(fg);
                float ty = key.rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f;
                canvas.drawText(text, key.rect.centerX(), ty, textPaint);

                // Small AltGr character in the corner (e.g. @ on Q).
                if (!altGr && key.altLabel != null && key.rect.height() > 30 * density) {
                    canvas.drawText(key.altLabel, key.rect.right - 5 * density,
                            key.rect.top + 12 * density, altPaint);
                }

                // Small long-press character in the other corner (ä on a).
                if (key.longLabel != null && key.rect.height() > 30 * density) {
                    altPaint.setTextAlign(Paint.Align.LEFT);
                    canvas.drawText(key.longLabel, key.rect.left + 5 * density,
                            key.rect.top + 12 * density, altPaint);
                    altPaint.setTextAlign(Paint.Align.RIGHT);
                }

                if (state == 2) {
                    // Small bar marks a locked modifier.
                    float bw = 10 * density;
                    float by = key.rect.bottom - 6 * density;
                    canvas.drawRoundRect(key.rect.centerX() - bw / 2, by, key.rect.centerX() + bw / 2,
                            by + 2 * density, density, density, lockPaint);
                }
            }
        }

        canvas.drawText("Tippen = Taste · Wischen = Maus · 2 Finger: Klick / Scrollen · 3 Finger: Rechtsklick / Ziehen · a o u halten = ä ö ü",
                getWidth() / 2f, getHeight() - 5 * density, hintPaint);

        // Long-press popup above the key (Gboard style).
        if (held != null) {
            boolean upper = modState[VK_LSHIFT] > 0;
            String t = upper ? held.longLabel.toUpperCase() : held.longLabel;
            float w = Math.max(held.rect.width(), 48 * density);
            float h = 56 * density;
            float cx = held.rect.centerX();
            float bottom = held.rect.top - 6 * density;
            popupRect.set(cx - w / 2, bottom - h, cx + w / 2, bottom);
            if (popupRect.top < 0) {
                popupRect.offset(0, -popupRect.top);
            }
            keyPaint.setColor(0x99000000);
            popupRect.offset(0, 3 * density);
            canvas.drawRoundRect(popupRect, 10 * density, 10 * density, keyPaint);
            popupRect.offset(0, -3 * density);
            canvas.drawRoundRect(popupRect, 10 * density, 10 * density, popupPaint);
            float ty = popupRect.centerY() - (popupText.descent() + popupText.ascent()) / 2f;
            canvas.drawText(t, popupRect.centerX(), ty, popupText);
        }
    }

    // ---------------- input ----------------

    private Key keyAt(float x, float y) {
        Key best = null;
        float bestDist = Float.MAX_VALUE;
        for (List<Key> row : rows) {
            for (Key key : row) {
                if (key.spacer) {
                    continue;
                }
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

    /** Fingers land a little below where they aim; shift the hit point up like Gboard. */
    private Key keyAtTouch(float x, float y) {
        return keyAt(x, y - 4 * density);
    }

    private long lastTypeTime;

    @Override
    protected boolean supportsRollover() {
        return true;
    }

    /** While typing (a key within the last second), keys fire on touch-down. */
    @Override
    protected boolean fireOnDown(float x, float y) {
        return android.os.SystemClock.uptimeMillis() - lastTypeTime < 1000 && keyAtTouch(x, y) != null;
    }

    /** Generous: slight finger slips while typing still count as a key, not a mouse move. */
    @Override
    protected float tapSlopDp() {
        return 20f;
    }

    /** Dragging on the keyboard is done with three fingers instead. */
    @Override
    protected boolean dragOnLongPress() {
        return false;
    }

    @Override
    protected boolean onLongPress(float x, float y, boolean alreadyTyped) {
        Key key = keyAtTouch(x, y);
        if (key == null || key.longLabel == null) {
            return false;
        }
        if (alreadyTyped) {
            // The plain letter went out on touch-down: remove it, the umlaut replaces it.
            if (autocorrect == null || !autocorrect.onBackspace()) {
                DuoKeys.sendComboNow(sink, VK_BACK);
            }
        }
        held = key;
        invalidate();
        return true;
    }

    @Override
    protected void onLongRelease(float x, float y) {
        Key key = held;
        held = null;
        invalidate();
        if (key == null) {
            return;
        }
        lastTypeTime = android.os.SystemClock.uptimeMillis();
        boolean shift = modState[VK_LSHIFT] > 0;
        if (shift) {
            DuoKeys.sendComboNow(sink, VK_LSHIFT, key.longVk);
        } else {
            DuoKeys.sendComboNow(sink, key.longVk);
        }
        if (autocorrect != null) {
            char c = key.longLabel.charAt(0);
            autocorrect.onLetter(shift ? Character.toUpperCase(c) : c);
        }
        releaseOneShot();
    }

    @Override
    protected void onPress(float x, float y) {
        pressed = keyAtTouch(x, y);
        if (pressed != null) {
            // Immediate feedback on touch-down, like Gboard.
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        }
        invalidate();
    }

    @Override
    protected void onCancelPress() {
        if (pressed != null || held != null) {
            pressed = null;
            held = null;
            invalidate();
        }
    }

    @Override
    protected void onPointerAction() {
        // Mouse use ends the typing burst.
        lastTypeTime = 0;
        if (autocorrect != null) {
            autocorrect.onBoundary();
        }
    }

    @Override
    protected boolean isRepeatable(float x, float y) {
        Key key = keyAtTouch(x, y);
        return key != null && key.repeatable;
    }

    @Override
    protected void onTap(float x, float y) {
        Key key = keyAtTouch(x, y);
        if (key == null) {
            return;
        }
        lastTypeTime = android.os.SystemClock.uptimeMillis();

        if (key.modifier) {
            modState[key.vk] = (modState[key.vk] + 1) % 3;
            invalidate();
            return;
        }

        boolean shortcutMods = modState[VK_LCONTROL] > 0 || modState[VK_LMENU] > 0 || modState[VK_LWIN] > 0;
        boolean altGr = modState[VK_RMENU] > 0;
        boolean shift = modState[VK_LSHIFT] > 0;
        boolean plain = !shortcutMods && !altGr;

        boolean handled = false;
        if (autocorrect != null) {
            if (plain && key.vk == VK_SPACE) {
                handled = autocorrect.onSpace();
            } else if (plain && key.vk == VK_BACK) {
                handled = autocorrect.onBackspace();
            }
        }

        if (!handled) {
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
            DuoKeys.sendComboNow(sink, vks);

            if (autocorrect != null && key.vk != VK_SPACE && key.vk != VK_BACK) {
                if (plain && key.isLetter()) {
                    char c = key.label.charAt(0);
                    autocorrect.onLetter(shift ? Character.toUpperCase(c) : c);
                } else {
                    autocorrect.onBoundary();
                }
            }
        }

        releaseOneShot();
    }

    private void releaseOneShot() {
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
        onPointerAction();
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        sink.sendMouseButton(button, true);
        sink.sendMouseButton(button, false);
    }
}
