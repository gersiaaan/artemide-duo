package com.limelight.duo;

import android.app.Activity;
import android.view.Window;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/** Pre-stream screens fill the whole display: status and navigation bars hidden, swipe to peek. */
public final class DuoFullscreen {
    private DuoFullscreen() {}

    public static void apply(Activity a) {
        Window w = a.getWindow();
        WindowCompat.setDecorFitsSystemWindows(w, false);
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(w, w.getDecorView());
        c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        c.hide(WindowInsetsCompat.Type.systemBars());
        // Artemide pads the content by the (stable) bar insets, which leaves an empty strip
        // where the hidden navigation bar was (seen on the Galaxy Tab S6). Our screens fill it.
        android.view.View content = a.findViewById(android.R.id.content);
        if (content != null) {
            content.setOnApplyWindowInsetsListener((v, insets) -> {
                v.setPadding(0, 0, 0, 0);
                return insets;
            });
            content.setPadding(0, 0, 0, 0);
            content.requestApplyInsets();
        }
        w.getDecorView().setSystemUiVisibility(w.getDecorView().getSystemUiVisibility()
                & ~android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
}
