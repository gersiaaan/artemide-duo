package com.limelight.duo;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.InputDevice;
import android.view.KeyEvent;

import androidx.preference.PreferenceManager;

/**
 * Controller shortcuts in the stream: hold LB + RB and press a button (Settings → Shortcuts).
 * The button press is consumed so the game doesn't see it; LB/RB pass through as usual.
 */
public final class DuoPadShortcuts {
    private DuoPadShortcuts() {}

    public interface Actions {
        void run(String action);
    }

    private static final String[][] ACTIONS = {
            {"quit", "A"}, {"pause", "Y"}, {"perf", "X"}, {"menu", "B"}, {"panel", "START"}, {"mode", "SELECT"}};

    private static boolean l1, r1;
    private static int consumedKey = -1;
    private static Actions actions;
    private static Context appContext;
    private static long lastFired;
    private static int lastMask;
    private static int swallow;

    /** Set by the stream when it starts (null when it ends). */
    public static void attach(Context c, Actions a) {
        appContext = c == null ? null : c.getApplicationContext();
        actions = a;
        lastMask = 0;
        swallow = 0;
        reset();
    }

    private static final int LB = 0x0100, RB = 0x0200;
    private static final String[][] FLAGS = {
            {"A", "4096"}, {"B", "8192"}, {"X", "16384"}, {"Y", "32768"},
            {"START", "16"}, {"SELECT", "32"}, {"THUMBL", "64"}, {"THUMBR", "128"}};

    /**
     * Controller button state as sent to the PC (all controller paths). Fires a shortcut on the
     * press of a mapped button while LB + RB are held, and removes that button from the state.
     */
    public static int onButtons(int mask) {
        Actions act = actions;
        Context c = appContext;
        int pressed = mask & ~lastMask;
        lastMask = mask;
        if (act == null || c == null) {
            return mask;
        }
        // Keep a button that fired a shortcut hidden from the game until it's released.
        swallow &= mask;
        if ((mask & LB) != 0 && (mask & RB) != 0 && pressed != 0) {
            SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(c);
            for (String[] f : FLAGS) {
                int flag = Integer.parseInt(f[1]);
                if ((pressed & flag) == 0) {
                    continue;
                }
                for (String[] a : ACTIONS) {
                    if (f[0].equals(p.getString("duo_pad_sc_" + a[0], a[1]))) {
                        swallow |= flag;
                        long now = android.os.SystemClock.uptimeMillis();
                        if (now - lastFired > 250) {
                            lastFired = now;
                            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> act.run(a[0]));
                        }
                    }
                }
            }
        }
        return mask & ~swallow;
    }

    private static String nameOf(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return "A";
            case KeyEvent.KEYCODE_BUTTON_B: return "B";
            case KeyEvent.KEYCODE_BUTTON_X: return "X";
            case KeyEvent.KEYCODE_BUTTON_Y: return "Y";
            case KeyEvent.KEYCODE_BUTTON_START: return "START";
            case KeyEvent.KEYCODE_BUTTON_SELECT: return "SELECT";
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return "THUMBL";
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return "THUMBR";
            default: return null;
        }
    }

    private static boolean fromPad(KeyEvent e) {
        InputDevice d = e.getDevice();
        int s = d != null ? d.getSources() : e.getSource();
        return (s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    /** Returns true when the event belongs to a shortcut and must not reach the game. */
    public static boolean onKey(Context c, KeyEvent e, Actions actions) {
        if (!fromPad(e)) {
            return false;
        }
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (k == KeyEvent.KEYCODE_BUTTON_L1) {
            l1 = down;
            return false;
        }
        if (k == KeyEvent.KEYCODE_BUTTON_R1) {
            r1 = down;
            return false;
        }
        if (!down && k == consumedKey) {
            consumedKey = -1;
            return true;
        }
        if (!down || !l1 || !r1) {
            return false;
        }
        if (e.getRepeatCount() > 0) {
            return k == consumedKey;
        }
        String name = nameOf(k);
        if (name == null) {
            return false;
        }
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(c);
        for (String[] a : ACTIONS) {
            if (name.equals(p.getString("duo_pad_sc_" + a[0], a[1]))) {
                consumedKey = k;
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lastFired > 250) {
                    lastFired = now;
                    actions.run(a[0]);
                }
                return true;
            }
        }
        return false;
    }

    public static void reset() {
        l1 = r1 = false;
        consumedKey = -1;
    }
}
