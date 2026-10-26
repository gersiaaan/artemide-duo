package com.limelight.duo;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import java.lang.ref.WeakReference;

/** Shared state between the stream (Game) and the detached panel on the other screen. */
public final class DuoBridge {
    private DuoBridge() {}

    private static final String PREFS = "duo_input";
    private static final String KEY_SPEED = "trackpad_speed";
    public static final int DEFAULT_SPEED = 5;

    private static DuoInputSink sink;
    private static WeakReference<Activity> panelActivity;

    public static void setSink(DuoInputSink s) {
        sink = s;
    }

    public static DuoInputSink getSink() {
        return sink;
    }

    static void setPanelActivity(Activity a) {
        panelActivity = a == null ? null : new WeakReference<>(a);
    }

    static void clearPanelActivity(Activity a) {
        if (panelActivity != null && panelActivity.get() == a) {
            panelActivity = null;
        }
    }

    public static boolean isPanelOpen() {
        Activity a = panelActivity == null ? null : panelActivity.get();
        return a != null && !a.isFinishing();
    }

    public static void closePanel() {
        Activity a = panelActivity == null ? null : panelActivity.get();
        if (a != null && !a.isFinishing()) {
            a.finish();
        }
        panelActivity = null;
    }

    /** Trackpad speed 1..10. */
    public static int getSpeed(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return Math.max(1, Math.min(10, p.getInt(KEY_SPEED, DEFAULT_SPEED)));
    }

    public static void setSpeed(Context c, int speed) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_SPEED, speed).apply();
    }

    /** Maps speed 1..10 to a pointer gain (5 -> 1.6). */
    public static float gainFor(int speed) {
        return 0.6f + speed * 0.2f;
    }
}
