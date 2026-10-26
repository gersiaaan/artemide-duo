package com.limelight.duo;

import android.content.Context;
import android.graphics.Color;

import androidx.preference.PreferenceManager;

/**
 * Colours of the new pre-stream screens. Values follow StreamLight's dark theme
 * (FoggyBytes/StreamLight, GPL-3.0, app/settings/theme.h/.cpp); the accent is user-selectable.
 */
public final class DuoTheme {
    private DuoTheme() {}

    public static final String KEY_ACCENT = "duo_accent";
    public static final String KEY_SPLASH = "duo_splash";

    /** StreamLight's default "Ion". */
    public static final int DEFAULT_ACCENT = 0xFF00D3F2;

    public static final int FLOOR = 0xFF151515;
    public static final int CARD = 0xFF0F1519;
    public static final int CARD_HIGH = 0xFF161F25;
    public static final int LINE = 0x14FFFFFF;
    public static final int TEXT = 0xFFF3F7F8;
    public static final int TEXT2 = 0xFF9AA7AE;
    public static final int TEXT3 = 0xFF5B6C75;
    public static final int ONLINE = 0xFF4ADE80;
    public static final int OFFLINE = 0xFF5B6C75;
    public static final int WARN = 0xFFFFB300;

    public static int accent(Context c) {
        String v = PreferenceManager.getDefaultSharedPreferences(c).getString(KEY_ACCENT, null);
        if (v != null) {
            try {
                return Color.parseColor(v);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return DEFAULT_ACCENT;
    }

    /** Start screen duration in ms (0 = off). */
    public static int splashMs(Context c) {
        String v = PreferenceManager.getDefaultSharedPreferences(c).getString(KEY_SPLASH, "2");
        try {
            return Math.max(0, Math.min(3, Integer.parseInt(v))) * 1000;
        } catch (NumberFormatException e) {
            return 2000;
        }
    }

    /** a over b, k = share of a (0..1). */
    public static int mix(int a, int b, float k) {
        int r = Math.round(Color.red(a) * k + Color.red(b) * (1 - k));
        int g = Math.round(Color.green(a) * k + Color.green(b) * (1 - k));
        int bl = Math.round(Color.blue(a) * k + Color.blue(b) * (1 - k));
        return Color.rgb(r, g, bl);
    }

    public static int withAlpha(int color, float alpha) {
        return (Math.round(alpha * 255) << 24) | (color & 0x00FFFFFF);
    }

    /** Dark or white text on the accent, whichever reads better. */
    public static int onAccent(int accent) {
        double l = 0.2126 * lin(Color.red(accent)) + 0.7152 * lin(Color.green(accent)) + 0.0722 * lin(Color.blue(accent));
        return l > 0.35 ? 0xFF08120C : Color.WHITE;
    }

    private static double lin(int c) {
        double s = c / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    /** App icon: dark rounded square with a thin edge, glyph in the accent colour. */
    public static android.view.View appIcon(Context c, int accent, int sizeDp) {
        float d = c.getResources().getDisplayMetrics().density;
        android.widget.FrameLayout box = new android.widget.FrameLayout(c);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(sizeDp * 0.225f * d);
        bg.setStroke(Math.max(1, Math.round(d)), LINE);
        box.setBackground(bg);
        android.widget.ImageView glyph = new android.widget.ImageView(c);
        android.graphics.drawable.Drawable dr = c.getDrawable(com.limelight.R.drawable.ic_duo_logo).mutate();
        dr.setTint(accent);
        glyph.setImageDrawable(dr);
        int pad = Math.round(sizeDp * 0.2f * d);
        glyph.setPadding(pad, pad, pad, pad);
        box.addView(glyph, new android.widget.FrameLayout.LayoutParams(-1, -1));
        return box;
    }
}
