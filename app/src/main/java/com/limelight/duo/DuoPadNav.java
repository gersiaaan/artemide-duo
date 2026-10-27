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
    private static java.lang.ref.WeakReference<View> lastFocus;
    private static long lastStickMove;
    private static long stickHeldSince;
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
            // Held down: speeds up (1 → 2 → 3 rows per repeat) so long lists scroll quickly.
            int r = e.getRepeatCount();
            int steps = r > 14 ? 3 : r > 5 ? 2 : 1;
            for (int i = 0; i < steps; i++) {
                move(root, dir, fallback);
            }
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
            stickHeldSince = 0;
        } else {
            if (stickHeldSince == 0) {
                stickHeldSince = now;
            }
            // The longer the stick is held, the faster it repeats (220 ms → 60 ms).
            long held = now - stickHeldSince;
            long interval = Math.max(60, STICK_REPEAT_MS - held / 6);
            if (now - lastStickMove > interval) {
                lastStickMove = now;
                move(root, stick, fallback);
            }
        }
        return true;
    }

    private static void move(ViewGroup root, int dir, View fallback) {
        View f = root.findFocus();
        // After touching, the list container itself can hold focus; start from a row instead.
        if (f instanceof androidx.recyclerview.widget.RecyclerView || f instanceof android.widget.ScrollView
                || f instanceof android.widget.HorizontalScrollView) {
            f = null;
        }
        if (f == null) {
            // Back from touch: continue on the button the controller was on last.
            View last = lastFocus == null ? null : lastFocus.get();
            View target = last != null && last.isAttachedToWindow() && last.isShown() && isInside(root, last)
                    && last.getGlobalVisibleRect(new android.graphics.Rect())
                    ? last : fallback;
            if (target != null) {
                target.requestFocusFromTouch();
                lastFocus = new java.lang.ref.WeakReference<>(target);
            }
            return;
        }
        // focusSearch lets lists (RecyclerView, ScrollView) scroll to the next item.
        View next = f.focusSearch(dir);
        // Stay inside the Duo screen: never hand focus to the hidden grid behind it.
        if (next != null && isInside(root, next)) {
            next.requestFocus(dir);
            lastFocus = new java.lang.ref.WeakReference<>(next);
        }
    }

    private static boolean isInside(ViewGroup root, View v) {
        for (android.view.ViewParent p = v.getParent(); p != null; p = p.getParent()) {
            if (p == root) {
                return true;
            }
        }
        return false;
    }

    /** A real gamepad / joystick is connected. */
    public static boolean hasGamepad() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice dev = InputDevice.getDevice(id);
            if (dev == null || dev.isVirtual()) {
                continue;
            }
            int s = dev.getSources();
            if ((s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
                return true;
            }
        }
        return false;
    }
}
