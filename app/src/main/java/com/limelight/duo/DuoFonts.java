package com.limelight.duo;

import android.content.Context;
import android.graphics.Typeface;

/** Inter (SIL OFL 1.1, rsms.me/inter) for all Duo screens; falls back to Roboto until init(). */
public final class DuoFonts {
    private DuoFonts() {}

    private static Typeface regular, medium, semibold, bold;

    public static void init(Context c) {
        if (regular != null) {
            return;
        }
        try {
            android.content.res.AssetManager a = c.getApplicationContext().getAssets();
            regular = Typeface.createFromAsset(a, "fonts/inter_regular.ttf");
            medium = Typeface.createFromAsset(a, "fonts/inter_medium.ttf");
            semibold = Typeface.createFromAsset(a, "fonts/inter_semibold.ttf");
            bold = Typeface.createFromAsset(a, "fonts/inter_bold.ttf");
        } catch (RuntimeException e) {
            regular = null;
        }
    }

    public static Typeface regular() {
        return regular != null ? regular : Typeface.create("sans-serif", Typeface.NORMAL);
    }

    public static Typeface medium() {
        return medium != null ? medium : Typeface.create("sans-serif-medium", Typeface.NORMAL);
    }

    public static Typeface semibold() {
        return semibold != null ? semibold : Typeface.create("sans-serif-medium", Typeface.NORMAL);
    }

    public static Typeface bold() {
        return bold != null ? bold : Typeface.create("sans-serif-medium", Typeface.BOLD);
    }
}
