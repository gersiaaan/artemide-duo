package com.limelight.duo;

import android.app.Activity;
import android.content.Context;
import androidx.preference.PreferenceManager;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/** Shared state between the stream (Game), the panels and the settings. */
public final class DuoBridge {
    private DuoBridge() {}

    /** Shared with the "Surface Duo" section in the Artemide settings. */
    public static final String KEY_SPEED = "duo_trackpad_speed";
    public static final int DEFAULT_SPEED = 5;
    /** Keyboard button bottom-left in the stream: "desktop", "all" or "off". */
    public static final String KEY_STREAM_BUTTON = "duo_stream_button";

    public interface SpeedListener {
        void onSpeedChanged(int speed);
    }

    private static DuoInputSink sink;
    private static WeakReference<Activity> panelActivity;
    private static Runnable panelListener;
    private static final List<SpeedListener> speedListeners = new ArrayList<>();

    public static void setSink(DuoInputSink s) {
        sink = s;
    }

    public static DuoInputSink getSink() {
        return sink;
    }

    // ---------------- detached panel ----------------

    /** Notified when the panel on the other screen opens or closes. */
    public static void setPanelListener(Runnable r) {
        panelListener = r;
    }

    static void setPanelActivity(Activity a) {
        panelActivity = a == null ? null : new WeakReference<>(a);
        notifyPanel();
    }

    static void clearPanelActivity(Activity a) {
        if (panelActivity != null && panelActivity.get() == a) {
            panelActivity = null;
            notifyPanel();
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
        notifyPanel();
    }

    /** Hidden on purpose by the user: brings back the app that was on that screen. */
    public static void hidePanelByUser() {
        Activity a = panelActivity == null ? null : panelActivity.get();
        if (a instanceof DuoPanelActivity && !a.isFinishing()) {
            ((DuoPanelActivity) a).hideByUser();
        }
        panelActivity = null;
        notifyPanel();
    }

    private static void notifyPanel() {
        if (panelListener != null) {
            panelListener.run();
        }
    }

    // ---------------- combined focus (clipboard sync) ----------------

    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    private static boolean gameFocus, panelFocus, unitFocus;
    private static final Runnable FOCUS_CHECK = () -> {
        boolean now = gameFocus || panelFocus;
        if (now != unitFocus) {
            unitFocus = now;
            if (sink != null) {
                sink.onUnitFocusChanged(now);
            }
        }
    };

    /** Focus of the stream window (game = true) or of the panel window. */
    public static void reportFocus(boolean game, boolean hasFocus) {
        if (game) {
            gameFocus = hasFocus;
        } else {
            panelFocus = hasFocus;
        }
        // Moving between stream and panel loses one focus just before the other gains it.
        MAIN.removeCallbacks(FOCUS_CHECK);
        MAIN.postDelayed(FOCUS_CHECK, 250);
    }

    public static void resetFocus() {
        MAIN.removeCallbacks(FOCUS_CHECK);
        gameFocus = panelFocus = unitFocus = false;
    }

    // ---------------- trackpad speed ----------------

    /** Trackpad speed 1..10. */
    public static int getSpeed(Context c) {
        int v = PreferenceManager.getDefaultSharedPreferences(c).getInt(KEY_SPEED, DEFAULT_SPEED);
        return Math.max(1, Math.min(10, v));
    }

    public static void setSpeed(Context c, int speed) {
        PreferenceManager.getDefaultSharedPreferences(c).edit().putInt(KEY_SPEED, speed).apply();
        for (SpeedListener l : new ArrayList<>(speedListeners)) {
            l.onSpeedChanged(speed);
        }
    }

    public static void addSpeedListener(SpeedListener l) {
        if (!speedListeners.contains(l)) {
            speedListeners.add(l);
        }
    }

    public static void removeSpeedListener(SpeedListener l) {
        speedListeners.remove(l);
    }

    // ---------------- remembered panel state ----------------

    private static final String KEY_LANG = "duo_language";
    private static final String KEY_MODE = "duo_panel_mode";
    private static final String KEY_SWAP = "duo_compact_swapped";

    /** "de" or "en". */
    public static String getLanguage(Context c) {
        return PreferenceManager.getDefaultSharedPreferences(c).getString(KEY_LANG, "de");
    }

    public static void setLanguage(Context c, String lang) {
        PreferenceManager.getDefaultSharedPreferences(c).edit().putString(KEY_LANG, lang).apply();
    }

    /** 0 = keyboard, 1 = trackpad, 2 = compact. */
    public static int getPanelMode(Context c) {
        return PreferenceManager.getDefaultSharedPreferences(c).getInt(KEY_MODE, 0);
    }

    public static void setPanelMode(Context c, int mode) {
        PreferenceManager.getDefaultSharedPreferences(c).edit().putInt(KEY_MODE, mode).apply();
    }

    /** Compact mode: keyboard on the left instead of the right. */
    public static boolean isCompactSwapped(Context c) {
        return PreferenceManager.getDefaultSharedPreferences(c).getBoolean(KEY_SWAP, false);
    }

    public static void setCompactSwapped(Context c, boolean swapped) {
        PreferenceManager.getDefaultSharedPreferences(c).edit().putBoolean(KEY_SWAP, swapped).apply();
    }

    /** Maps speed 1..10 to a pointer gain (1 -> 0.15, 5 -> 0.62, 10 -> 1.2). */
    public static float gainFor(int speed) {
        return 0.15f + (speed - 1) * (1.05f / 9f);
    }
}
