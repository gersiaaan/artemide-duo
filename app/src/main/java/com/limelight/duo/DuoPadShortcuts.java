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

    private static String nameOf(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return "A";
            case KeyEvent.KEYCODE_BUTTON_B: return "B";
            case KeyEvent.KEYCODE_BUTTON_X: return "X";
            case KeyEvent.KEYCODE_BUTTON_Y: return "Y";
            case KeyEvent.KEYCODE_BUTTON_START: return "START";
            case KeyEvent.KEYCODE_BUTTON_SELECT: return "SELECT";
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
                actions.run(a[0]);
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
