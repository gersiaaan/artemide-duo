package com.limelight.duo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;

import com.limelight.nvstream.input.MouseButtonPacket;

import java.util.ArrayList;
import java.util.List;

import static com.limelight.duo.DuoKeys.*;

/**
 * On-screen keyboard for the Duo panel.
 *
 * FULL:    German QWERTZ PC keyboard (Gboard-style stagger, no umlaut keys) that doubles
 *          as a trackpad: tap = key, swipe = mouse, 2 fingers = click/scroll/zoom,
 *          3 fingers = right click / drag.
 * COMPACT: phone-style keyboard for one-handed typing next to a separate trackpad,
 *          with a ?123 layer for digits and symbols. Swipes don't move the mouse.
 *
 * Modifiers: tap once = next key only, tap twice = locked, tap again = off.
 */
public class DuoKeyboardView extends DuoTouchSurface {

    public static final int LAYOUT_FULL = 0;
    public static final int LAYOUT_COMPACT = 1;

    /** Row heights in dp; rows shrink if the panel is smaller. */
    private static final float FN_ROW_DP = 24f;
    private static final float ROW_DP = 58f;
    private static final float COMPACT_ROW_DP = 54f;
    /** Compact space-bar row: a bit flatter than the letter rows. */
    private static final float COMPACT_SPACE_ROW_DP = 46f;
    /** Compact: keys end above the Links/Rechts buttons of the trackpad column (52 + 6). */
    private static final float COMPACT_BOTTOM_DP = 58f;
    private float compactBottomDp = COMPACT_BOTTOM_DP;
    private float compactRowDp = COMPACT_ROW_DP, compactSpaceDp = COMPACT_SPACE_ROW_DP;

    /** Phone layout: same key height as the full keyboard. */
    public void setCompactRowsDp(float rows, float space) {
        compactRowDp = rows;
        compactSpaceDp = space;
        requestLayout();
    }

    private boolean phoneMode;

    /** Slim layout under the keyboard tab: trackpad + hint line like the full keyboard. */
    public void setPhoneMode(boolean on) {
        phoneMode = on;
        requestLayout();
        invalidate();
    }

    private boolean split;

    /**
     * Keyboard 4: split keyboard for two thumbs (arrangement like the iPhone Duo): left half flush
     * left, right half flush right, keys as big as on Slim, G and V on both halves.
     */
    public void setSplit(boolean on) {
        if (split == on || layout != LAYOUT_COMPACT) {
            return;
        }
        split = on;
        letterRows.clear();
        symbolRows.clear();
        buildCompact();
        if (on) {
            buildSplitLetters();
        }
        if (!symbols) {
            rows = letterRows;
        }
        layoutKeys(getWidth(), getHeight());
        invalidate();
    }

    private void buildSplitLetters() {
        letterRows.clear();
        Key[][] l = letterKeys();
        l[1][1].hold("ß");
        Key[] top = l[0], home = l[1], bottom = l[2];
        // Left half, positions in key pitches from the left edge.
        letterRows.add(row(top[0].at(0f), top[1].at(1f), top[2].at(2f), top[3].at(3f), top[4].at(4f),
                // Right half, positions from the right edge (negative = to the left of it).
                top[5].atRight(-5f), top[6].atRight(-4f), top[7].atRight(-3f), top[8].atRight(-2f), top[9].atRight(-1f)));
        letterRows.add(row(home[0].at(0.5f), home[1].at(1.5f), home[2].at(2.5f), home[3].at(3.5f), home[4].at(4.5f),
                home[4].copy().atRight(-5.5f), home[5].atRight(-4.5f), home[6].atRight(-3.5f), home[7].atRight(-2.5f),
                home[8].atRight(-1.5f)));
        letterRows.add(row(k("⇧", VK_LSHIFT, 1.3f).mod().fn().at(0f),
                bottom[0].at(1.5f), bottom[1].at(2.5f), bottom[2].at(3.5f), bottom[3].at(4.5f),
                bottom[3].copy().atRight(-5.5f), bottom[4].atRight(-4.5f), bottom[5].atRight(-3.5f), bottom[6].atRight(-2.5f),
                k("⌫", VK_BACK, 1.3f).rep().fn().atRight(-1.3f)));
        letterRows.add(row(
                k("?123", 0, 1.45f).quiet().action(ACTION_SYMBOLS).at(0f),
                k(",", VK_OEM_COMMA, 0.95f).quiet().at(1.45f),
                k("Space", VK_SPACE, 2.6f).rep().quiet().at(2.4f),
                k("Space", VK_SPACE, 2.65f).rep().quiet().atRight(-5.05f),
                k(".", VK_OEM_PERIOD, 0.95f).quiet().atRight(-2.4f),
                k("↵", VK_RETURN, 1.45f).fn().atRight(-1.45f)));
    }

    private boolean fullLike() {
        return layout == LAYOUT_FULL || phoneMode;
    }

    /** Phone layout under the keyboard tab: keys end right above the click buttons. */
    public void setCompactBottomDp(float dp) {
        compactBottomDp = dp;
        requestLayout();
    }
    /** Room above the keys for the suggestion bar, which floats right on top of them. */
    private static final float SUGGESTION_DP = 35f;
    /** Full layout: free space between the hint line and the click buttons below. */
    private static final float BOTTOM_GAP_DP = 16f;

    private static final int ACTION_NONE = 0;
    private static final int ACTION_SYMBOLS = 1;
    private static final int ACTION_LETTERS = 2;

    private static final class Key {
        final String label;
        String shiftLabel;
        String altLabel;
        /** Long-press character (a -> ä): sent as text. */
        String longLabel;
        /** Sent as Unicode text instead of a key (symbols layer). */
        String text;
        int action;
        final int vk;
        final float units;
        boolean modifier;
        boolean repeatable;
        boolean subtle;
        boolean func;
        /** 0 = normal, 1 = upper half of a stacked pair, 2 = lower half. */
        int stack;
        /** Empty space (no key, part of the trackpad). */
        boolean spacer;
        /** Only reacts to a long press (Caps Lock, so it can't be hit by accident). */
        boolean holdOnly;
        /** Split layout: start position in key pitches (from the left edge, or from the right edge if {@link #right}). */
        float pos = Float.NaN;
        boolean right;
        final RectF rect = new RectF();

        Key(String label, int vk, float units) {
            this.label = label;
            this.vk = vk;
            this.units = units;
        }

        Key shift(String s) { shiftLabel = s; return this; }
        Key alt(String s) { altLabel = s; return this; }
        Key hold(String s) { longLabel = s; return this; }
        Key mod() { modifier = true; return this; }
        Key rep() { repeatable = true; return this; }
        Key quiet() { subtle = true; return this; }
        Key fn() { func = true; return this; }
        Key stacked(int s) { stack = s; return this; }
        Key space() { spacer = true; return this; }
        Key holdOnly() { holdOnly = true; return this; }
        Key text(String t) { text = t; return this; }
        Key action(int a) { action = a; return this; }
        Key at(float p) { pos = p; right = false; return this; }
        Key atRight(float p) { pos = p; right = true; return this; }

        /** Same key once more (the split keyboard repeats G and V on both halves). */
        Key copy() {
            Key c = new Key(label, vk, units);
            c.shiftLabel = shiftLabel;
            c.altLabel = altLabel;
            c.longLabel = longLabel;
            c.text = text;
            c.action = action;
            c.modifier = modifier;
            c.repeatable = repeatable;
            c.subtle = subtle;
            c.func = func;
            return c;
        }

        boolean isLetter() {
            return label.length() == 1 && Character.isLetter(label.charAt(0));
        }
    }

    private final int layout;
    private boolean symbols;

    private final List<List<Key>> fullRows = new ArrayList<>();
    private final List<Boolean> fullFn = new ArrayList<>();
    private final List<List<Key>> letterRows = new ArrayList<>();
    private final List<List<Key>> symbolRows = new ArrayList<>();

    /** Rows currently shown. */
    private List<List<Key>> rows;

    /** 0 = off, 1 = next key only, 2 = locked. Indexed by VK. */
    private final int[] modState = new int[256];

    private final Paint keyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint altPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint popupPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint popupBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint popupText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF popupRect = new RectF();

    private Key pressed;
    private Key held;
    private Key keyQ, keyW, keyA;
    private float lastTapX = Float.NaN, lastTapY = Float.NaN;
    private final float hintHeight;
    private float letterTextSize;
    private float keysTop, keysBottom;
    private long lastTypeTime;
    private DuoAutocorrect autocorrect;

    public interface KeysListener {
        /** Top edge of the key area, in this view's coordinates. */
        void onKeysTop(float top);
    }

    private KeysListener keysListener;

    public DuoKeyboardView(Context context, int layout) {
        super(context);
        this.layout = layout;
        hintHeight = layout == LAYOUT_FULL ? 18 * density : 0;
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(density);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(DuoFonts.medium());
        altPaint.setColor(DuoColors.MUTED);
        altPaint.setTextSize(10 * density);
        hintPaint.setColor(DuoColors.MUTED);
        hintPaint.setTextAlign(Paint.Align.CENTER);
        hintPaint.setTextSize(10.5f * density);
        lockPaint.setColor(DuoColors.HI_TEXT);
        popupPaint.setColor(DuoColors.HI_BG);
        popupBorder.setStyle(Paint.Style.STROKE);
        popupBorder.setStrokeWidth(density);
        popupBorder.setColor(DuoColors.HI_BORDER);
        popupText.setColor(DuoColors.HI_TEXT);
        popupText.setTextAlign(Paint.Align.CENTER);
        popupText.setTextSize(24 * density);
        popupText.setTypeface(DuoFonts.bold());

        if (layout == LAYOUT_FULL) {
            buildFull();
            rows = fullRows;
        } else {
            buildCompact();
            rows = letterRows;
        }
    }

    public void setAutocorrect(DuoAutocorrect autocorrect) {
        this.autocorrect = autocorrect;
    }

    public void setKeysListener(KeysListener l) {
        keysListener = l;
    }

    @Override
    protected boolean trackpadEnabled() {
        // Every keyboard doubles as a trackpad (compact mode too): swipe = mouse, 2 fingers = scroll.
        return true;
    }

    // ---------------- layouts ----------------

    private static Key k(String l, int vk) { return new Key(l, vk, 1f); }
    private static Key k(String l, int vk, float u) { return new Key(l, vk, u); }

    private static List<Key> row(Object... parts) {
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
        return list;
    }

    private static Key[] letters(String labels, String usPositions) {
        Key[] out = new Key[labels.length()];
        for (int i = 0; i < labels.length(); i++) {
            out[i] = k(String.valueOf(labels.charAt(i)), vk(usPositions.charAt(i)));
        }
        return out;
    }

    /** Letter keys with long-press characters (umlauts, @, €, ß). German labels, US VKs. */
    private Key[][] letterKeys() {
        Key[] top = letters("qwertzuiop", "qwertyuiop");
        Key[] home = letters("asdfghjkl", "asdfghjkl");
        Key[] bottom = letters("yxcvbnm", "zxcvbnm");
        top[0].alt("@");
        top[2].alt("€");
        top[6].hold("ü");
        top[8].hold("ö");
        home[0].hold("ä");
        bottom[6].alt("µ");
        keyQ = top[0];
        keyW = top[1];
        keyA = home[0];
        return new Key[][]{top, home, bottom};
    }

    private void buildFull() {
        Key[][] l = letterKeys();
        l[1][1].hold("ß");

        Key[] fn = new Key[14];
        fn[0] = k("Esc", VK_ESCAPE).quiet();
        for (int i = 0; i < 12; i++) {
            fn[i + 1] = k("F" + (i + 1), VK_F1 + i).quiet();
        }
        fn[13] = k("Del", VK_DELETE, 2f).rep().quiet();
        fullRows.add(row((Object) fn));
        fullFn.add(true);

        String[] shifted = {"!", "\"", "§", "$", "%", "&", "/", "(", ")", "="};
        String[] alts = {null, "²", "³", null, null, null, "{", "[", "]", "}"};
        Key[] digits = new Key[10];
        for (int i = 0; i < 10; i++) {
            char d = (char) ('0' + (i + 1) % 10);
            digits[i] = k(String.valueOf(d), vk(d)).shift(shifted[i]).alt(alts[i]).quiet();
        }
        fullRows.add(row(
                k("^", VK_OEM_3).shift("°").quiet(),
                digits,
                k("ß", VK_OEM_MINUS).shift("?").alt("\\").quiet(),
                k("´", VK_OEM_PLUS).shift("`").quiet(),
                k("⌫", VK_BACK, 2f).rep().fn()));
        fullFn.add(false);

        // Gboard-style stagger, Q..P centred: A row +0.5, Y row starts under S.
        fullRows.add(row(
                k("Tab", VK_TAB, 2.5f).quiet(),
                l[0],
                k("+", VK_OEM_6, 1.25f).shift("*").alt("~").quiet(),
                k("#", VK_OEM_5, 1.25f).shift("'").quiet()));
        fullFn.add(false);

        fullRows.add(row(
                // Caps Lock only on long press (tap Shift twice for local caps).
                k("⇪", VK_CAPITAL, 3f).quiet().holdOnly(),
                l[1],
                k("↵", VK_RETURN, 3f).fn()));
        fullFn.add(false);

        fullRows.add(row(
                k("⇧", VK_LSHIFT, 3f).mod().fn(),
                k("<", VK_OEM_102).shift(">").alt("|").quiet(),
                l[2],
                k(",", VK_OEM_COMMA).shift(";").quiet(),
                k(".", VK_OEM_PERIOD).shift(":").quiet(),
                k("-", VK_OEM_2).shift("_").quiet(),
                k("⇧", VK_LSHIFT, 1f).mod().fn()));
        fullFn.add(false);

        fullRows.add(row(
                k("Alt", VK_LMENU, 1.3f).mod().quiet(),
                k("Win", VK_LWIN, 1.2f).mod().quiet(),
                k("Ctrl", VK_LCONTROL, 1.2f).mod().quiet(),
                k("Space", VK_SPACE, 7.1f).rep().quiet(),
                k("AltGr", VK_RMENU, 1.2f).mod().quiet(),
                k("←", VK_LEFT).rep().quiet(),
                k("↑", VK_UP).rep().quiet().stacked(1),
                k("↓", VK_DOWN, 0f).rep().quiet().stacked(2),
                k("→", VK_RIGHT).rep().quiet()));
        fullFn.add(false);
    }

    private void buildCompact() {
        Key[][] l = letterKeys();
        l[1][1].hold("ß");

        letterRows.add(row((Object) l[0]));
        letterRows.add(row(k("", 0, 0.5f).space(), l[1], k("", 0, 0.5f).space()));
        letterRows.add(row(k("⇧", VK_LSHIFT, 1.5f).mod().fn(), l[2], k("⌫", VK_BACK, 1.5f).rep().fn()));
        letterRows.add(row(
                k("?123", 0, 1.5f).quiet().action(ACTION_SYMBOLS),
                k(",", VK_OEM_COMMA).quiet(),
                k("Space", VK_SPACE, 5f).rep().quiet(),
                k(".", VK_OEM_PERIOD).quiet(),
                k("↵", VK_RETURN, 1.5f).fn()));

        Key[] nums = new Key[10];
        for (int i = 0; i < 10; i++) {
            char d = (char) ('0' + (i + 1) % 10);
            nums[i] = k(String.valueOf(d), vk(d));
        }
        symbolRows.add(row((Object) nums));
        symbolRows.add(row(sym("@"), sym("#"), sym("€"), sym("&"), sym("_"), sym("-"),
                sym("("), sym(")"), sym("/"), sym("+")));
        symbolRows.add(row(sym("%", 1.5f), sym("*"), sym("\""), sym("'"), sym(":"), sym(";"),
                sym("!"), sym("?"), k("⌫", VK_BACK, 1.5f).rep().fn()));
        symbolRows.add(row(
                k("ABC", 0, 1.5f).quiet().action(ACTION_LETTERS),
                k(",", VK_OEM_COMMA).quiet(),
                k("Space", VK_SPACE, 5f).rep().quiet(),
                k(".", VK_OEM_PERIOD).quiet(),
                k("↵", VK_RETURN, 1.5f).fn()));
    }

    private static Key sym(String s) {
        return k(s, 0).text(s);
    }

    private static Key sym(String s, float units) {
        return k(s, 0, units).text(s);
    }

    private boolean isFnRow(int ri) {
        return layout == LAYOUT_FULL && rows == fullRows && fullFn.get(ri);
    }

    private float rowDpFor(int ri, float rowDp) {
        if (isFnRow(ri)) {
            return FN_ROW_DP;
        }
        if (layout == LAYOUT_COMPACT && ri == rows.size() - 1) {
            return compactSpaceDp;
        }
        return rowDp;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutKeys(w, h);
    }

    private void layoutKeys(int width, int height) {
        if (width == 0 || height == 0) {
            return;
        }
        float hgap = 5 * density;
        float vgap = (layout == LAYOUT_FULL ? 4 : 6) * density;
        float rowDp = layout == LAYOUT_FULL ? ROW_DP : compactRowDp;
        float bottomReserve = fullLike() ? 18 * density + BOTTOM_GAP_DP * density : compactBottomDp * density;

        float wanted = 0;
        for (int ri = 0; ri < rows.size(); ri++) {
            wanted += rowDpFor(ri, rowDp) * density;
        }
        float minTop = SUGGESTION_DP * density;
        float available = height - minTop - bottomReserve - vgap * (rows.size() - 1);
        float scale = Math.min(1f, available / wanted);
        float total = wanted * scale + vgap * (rows.size() - 1);
        float rowH = rowDp * density * scale;

        // Keys sit at the bottom, within thumb reach; the space above stays free.
        float y = Math.max(minTop, height - bottomReserve - total);
        keysTop = y;

        for (int ri = 0; ri < rows.size(); ri++) {
            List<Key> row = rows.get(ri);
            float h = rowDpFor(ri, rowDp) * density * scale;
            float units = 0;
            int visible = 0;
            for (Key key : row) {
                units += key.units;
                if (key.stack != 2) {
                    visible++;
                }
            }
            float unitW = (width - hgap * (visible - 1)) / units;
            if (!row.isEmpty() && !Float.isNaN(row.get(0).pos)) {
                // Split keyboard: same key pitch as Slim (78 % of the width for ten keys), halves flush
                // with the left and right edge.
                float pitch = (width * 0.78f - 9 * hgap) / 10f + hgap;
                for (Key key : row) {
                    float kx = key.right ? width + key.pos * pitch + hgap : key.pos * pitch;
                    float kw = key.units * pitch - hgap;
                    float inset = density / 2;
                    key.rect.set(kx + inset, y + inset, kx + kw - inset, y + h - inset);
                }
                y += h + vgap;
                continue;
            }
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
                x += kw + hgap;
            }
            y += h + vgap;
        }
        keysBottom = y - vgap;
        letterTextSize = Math.min(rowH * 0.36f, 20 * density);
        if (keysListener != null) {
            keysListener.onKeysTop(keysTop);
        }
    }

    private void switchLayer(boolean toSymbols) {
        symbols = toSymbols;
        rows = toSymbols ? symbolRows : letterRows;
        layoutKeys(getWidth(), getHeight());
        invalidate();
    }

    // ---------------- drawing ----------------

    @Override
    protected void onDraw(Canvas canvas) {
        boolean shift = modState[VK_LSHIFT] > 0;
        boolean autoCap = autocorrect != null && autocorrect.autoShift();
        boolean altGr = modState[VK_RMENU] > 0;
        float radius = 7 * density;
        float subtleText = Math.min(letterTextSize * 0.62f, 12 * density);

        for (List<Key> row : rows) {
            for (Key key : row) {
                if (key.spacer) {
                    continue;
                }
                int state = key.modifier ? modState[key.vk] : 0;

                int bg, border, fg;
                if (state > 0) {
                    bg = DuoColors.HI_BG;
                    border = DuoColors.HI_BORDER;
                    fg = DuoColors.HI_TEXT;
                } else if (key.func) {
                    bg = key == pressed ? DuoColors.PRESSED : DuoColors.FUNC_BG;
                    border = DuoColors.FUNC_BORDER;
                    fg = DuoColors.FUNC_TEXT;
                } else if (key.subtle) {
                    bg = key == pressed ? DuoColors.PRESSED : DuoColors.SUBTLE_BG;
                    border = DuoColors.SUBTLE_BORDER;
                    fg = DuoColors.MUTED;
                } else {
                    bg = key == pressed ? DuoColors.PRESSED : DuoColors.KEY;
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
                } else if ((shift || autoCap) && key.isLetter()) {
                    text = text.toUpperCase();
                }
                boolean small = (key.subtle && state == 0) || text.length() > 2;
                textPaint.setTextSize(small ? subtleText : letterTextSize);
                textPaint.setColor(fg);
                float ty = key.rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f;
                canvas.drawText(text, key.rect.centerX(), ty, textPaint);

                if (key.rect.height() > 30 * density) {
                    // Small AltGr character top right (e.g. @ on Q).
                    if (!altGr && key.altLabel != null) {
                        altPaint.setTextAlign(Paint.Align.RIGHT);
                        canvas.drawText(key.altLabel, key.rect.right - 5 * density,
                                key.rect.top + 12 * density, altPaint);
                    }
                    // Small long-press character top left (ä on a).
                    if (key.longLabel != null) {
                        altPaint.setTextAlign(Paint.Align.LEFT);
                        canvas.drawText(key.longLabel, key.rect.left + 5 * density,
                                key.rect.top + 12 * density, altPaint);
                    }
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

        if (phoneMode) {
            canvas.drawText("Tap free space = click · swipe = mouse · 2 fingers: click / scroll / zoom · 3 fingers: right click / drag",
                    getWidth() / 2f, keysBottom + 14 * density, hintPaint);
        } else if (layout == LAYOUT_FULL) {
            canvas.drawText("Tap free space = click · swipe = mouse · 2 fingers: click / scroll / zoom · 3 fingers: right click / drag · hold a o u = ä ö ü · hold ⇪ = Caps",
                    getWidth() / 2f, keysBottom + 14 * density, hintPaint);
        }

        // Long-press popup above the key (Gboard style).
        if (held != null && held.longLabel != null) {
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
            if (popupRect.left < 0) {
                popupRect.offset(-popupRect.left, 0);
            }
            if (popupRect.right > getWidth()) {
                popupRect.offset(getWidth() - popupRect.right, 0);
            }
            keyPaint.setColor(0x99000000);
            popupRect.offset(0, 3 * density);
            canvas.drawRoundRect(popupRect, 10 * density, 10 * density, keyPaint);
            popupRect.offset(0, -3 * density);
            canvas.drawRoundRect(popupRect, 10 * density, 10 * density, popupPaint);
            canvas.drawRoundRect(popupRect, 10 * density, 10 * density, popupBorder);
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
        // The space bar is wide and flat: a touch a bit below or above it still counts.
        float reach = best != null && best.vk == VK_SPACE ? 16 : 8;
        return bestDist < (reach * density) * (reach * density) ? best : null;
    }

    /** Fingers land a little below where they aim; shift the hit point up like Gboard. */
    private Key keyAtTouch(float x, float y) {
        float yy = y - 4 * density;
        // Touch position in key units (q = 0,0) for the autocorrect's spatial model.
        lastTapX = lastTapY = Float.NaN;
        if (!symbols && keyQ != null && keyW != null && keyA != null) {
            float pitchX = keyW.rect.left - keyQ.rect.left;
            float pitchY = keyA.rect.top - keyQ.rect.top;
            if (pitchX > 0 && pitchY > 0) {
                lastTapX = (x - keyQ.rect.centerX()) / pitchX;
                lastTapY = (yy - keyQ.rect.centerY()) / pitchY;
            }
        }
        return keyAt(x, yy);
    }

    @Override
    protected boolean supportsRollover() {
        return true;
    }

    /** While typing (a key within the last second), keys fire on touch-down. */
    @Override
    protected boolean fireOnDown(float x, float y) {
        return SystemClock.uptimeMillis() - lastTypeTime < 1000 && keyAtTouch(x, y) != null;
    }

    /** A touch that starts on a key may drift much further before it becomes a trackpad move (space bar!). */
    @Override
    protected float trackpadSlopPx(float x, float y) {
        return keyAtTouch(x, y) != null ? tapSlopDp() * density * 2.2f : tapSlopDp() * density;
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
        if (key != null && key.holdOnly) {
            DuoKeys.sendComboNow(sink, key.vk);
            if (autocorrect != null) {
                autocorrect.onBoundary();
            }
            return true;   // HELD; release does nothing (held stays null)
        }
        if (key == null || key.longLabel == null) {
            return false;
        }
        if (alreadyTyped) {
            // The plain letter went out on touch-down: remove it, the long-press character replaces it.
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
        if (key == null || key.longLabel == null) {
            return;
        }
        lastTypeTime = SystemClock.uptimeMillis();
        boolean upper = modState[VK_LSHIFT] > 0 || (autocorrect != null && autocorrect.autoShift());
        String t = upper ? key.longLabel.toUpperCase() : key.longLabel;
        if (sink != null) {
            sink.sendText(t);
        }
        if (autocorrect != null) {
            char c = t.charAt(0);
            if (Character.isLetter(c)) {
                autocorrect.onLetter(c, Float.NaN, Float.NaN);
            } else {
                autocorrect.onChar(c);
            }
        }
        releaseOneShot();
    }

    @Override
    protected void onPress(float x, float y) {
        pressed = keyAtTouch(x, y);
        if (pressed != null) {
            // Immediate feedback on touch-down, like Gboard.
            DuoHaptics.key(this);
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
            // A tap on free space (no key nearby) is a left click, like on a trackpad. Not while typing:
            // a slightly missed key must never click (that would move the text cursor on the PC).
            if (trackpadEnabled() && fullLike() && SystemClock.uptimeMillis() - lastTypeTime > 900) {
                click(MouseButtonPacket.BUTTON_LEFT);
            }
            return;
        }
        if (key.holdOnly) {
            return;
        }
        float tx = lastTapX, ty = lastTapY;
        lastTypeTime = SystemClock.uptimeMillis();

        if (key.action == ACTION_SYMBOLS || key.action == ACTION_LETTERS) {
            switchLayer(key.action == ACTION_SYMBOLS);
            return;
        }

        if (key.modifier) {
            modState[key.vk] = (modState[key.vk] + 1) % 3;
            invalidate();
            return;
        }

        boolean shortcutMods = modState[VK_LCONTROL] > 0 || modState[VK_LMENU] > 0 || modState[VK_LWIN] > 0;
        boolean altGr = modState[VK_RMENU] > 0;
        boolean shift = modState[VK_LSHIFT] > 0;
        boolean plain = !shortcutMods && !altGr;

        // Symbols layer: send as Unicode text (independent of the PC's keyboard layout).
        if (key.text != null) {
            if (sink != null) {
                sink.sendText(key.text);
            }
            if (autocorrect != null) {
                autocorrect.onChar(key.text.charAt(0));
            }
            invalidate();
            releaseOneShot();
            return;
        }

        boolean handled = false;
        if (autocorrect != null) {
            if (plain && key.vk == VK_SPACE) {
                handled = autocorrect.onSpace();
            } else if (plain && key.vk == VK_BACK) {
                handled = autocorrect.onBackspace();
            }
        }

        // Start of a sentence: capitalize the letter like Gboard's auto-shift.
        boolean autoCap = !handled && plain && !shift && key.isLetter()
                && autocorrect != null && autocorrect.autoShift();

        if (!handled) {
            List<Integer> combo = new ArrayList<>();
            for (int vk : new int[]{VK_LCONTROL, VK_LMENU, VK_RMENU, VK_LWIN, VK_LSHIFT}) {
                if (modState[vk] > 0) {
                    combo.add(vk);
                }
            }
            if (autoCap) {
                combo.add(VK_LSHIFT);
            }
            combo.add(key.vk);
            int[] vks = new int[combo.size()];
            for (int i = 0; i < vks.length; i++) {
                vks[i] = combo.get(i);
            }
            DuoKeys.sendWithClipboard(sink, true, vks);

            if (autocorrect != null && key.vk != VK_SPACE && key.vk != VK_BACK) {
                if (plain && key.isLetter()) {
                    char c = key.label.charAt(0);
                    autocorrect.onLetter(shift || autoCap ? Character.toUpperCase(c) : c, tx, ty);
                } else if (plain && key.vk == VK_RETURN) {
                    autocorrect.onEnter();
                } else if (plain && key.label.length() == 1) {
                    String out = shift && key.shiftLabel != null ? key.shiftLabel : key.label;
                    autocorrect.onChar(out.charAt(0));
                } else {
                    autocorrect.onBoundary();
                }
            }
        }
        invalidate();
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
