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

    private static void notifyPanel() {
        if (panelListener != null) {
            panelListener.run();
        }
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

    /** Maps speed 1..10 to a pointer gain (1 -> 0.15, 5 -> 0.62, 10 -> 1.2). */
    public static float gainFor(int speed) {
        return 0.15f + (speed - 1) * (1.05f / 9f);
    }
}
