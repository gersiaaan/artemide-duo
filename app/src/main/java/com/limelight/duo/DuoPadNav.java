package com.limelight.duo;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

/**
 * Controller navigation for the pre-stream screens. Some controllers (e.g. Xbox pads on
 * Samsung tablets) send the D-pad only as hat axes and never leave touch mode, so nothing
 * gets focus. This moves focus itself: D-pad keys, hat axes and the left stick.
 */
public final class DuoPadNav {
    private DuoPadNav() {}

    private static int lastHatDir;
    private static long lastStickMove;
    private static final long STICK_REPEAT_MS = 220;

    /** D-pad key events. fallback gets focus first if nothing is focused yet. */
    public static boolean onKey(ViewGroup root, KeyEvent e, View fallback) {
        int dir;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP: dir = View.FOCUS_UP; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: dir = View.FOCUS_DOWN; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: dir = View.FOCUS_LEFT; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: dir = View.FOCUS_RIGHT; break;
            default: return false;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            move(root, dir, fallback);
        }
        return true;
    }

    /** Hat axes and left stick (joystick motion events). */
    public static boolean onMotion(ViewGroup root, MotionEvent e, View fallback) {
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK
                || e.getAction() != MotionEvent.ACTION_MOVE) {
            return false;
        }
        float hx = e.getAxisValue(MotionEvent.AXIS_HAT_X), hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
        int hat = hx < -0.5f ? View.FOCUS_LEFT : hx > 0.5f ? View.FOCUS_RIGHT
                : hy < -0.5f ? View.FOCUS_UP : hy > 0.5f ? View.FOCUS_DOWN : 0;
        if (hat != lastHatDir) {
            lastHatDir = hat;
            if (hat != 0) {
                move(root, hat, fallback);
            }
            return true;
        }
        float sx = e.getAxisValue(MotionEvent.AXIS_X), sy = e.getAxisValue(MotionEvent.AXIS_Y);
        int stick = Math.abs(sx) > Math.abs(sy)
                ? (sx < -0.6f ? View.FOCUS_LEFT : sx > 0.6f ? View.FOCUS_RIGHT : 0)
                : (sy < -0.6f ? View.FOCUS_UP : sy > 0.6f ? View.FOCUS_DOWN : 0);
        long now = SystemClock.uptimeMillis();
        if (stick == 0) {
            lastStickMove = 0;
        } else if (now - lastStickMove > STICK_REPEAT_MS) {
            lastStickMove = now;
            move(root, stick, fallback);
        }
        return true;
    }

    private static void move(ViewGroup root, int dir, View fallback) {
        View f = root.findFocus();
        if (f == null) {
            if (fallback != null) {
                fallback.requestFocusFromTouch();
            }
            return;
        }
        // focusSearch lets lists (RecyclerView, ScrollView) scroll to the next item.
        View next = f.focusSearch(dir);
        if (next != null) {
            next.requestFocus(dir);
        }
    }
}
