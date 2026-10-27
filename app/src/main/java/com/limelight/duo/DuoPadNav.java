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
        if (!synthetic) {
            lastRealKey = SystemClock.uptimeMillis();
            MAIN.removeCallbacks(REPEAT);
            heldDir = 0;
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

    /** Receives the D-pad key events made from hat axes / the stick (the screen's own key handling). */
    public interface KeyHandler {
        boolean handle(KeyEvent e);
    }

    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    private static int heldDir;
    private static int heldRepeats;
    private static long heldSince;
    private static long lastRealKey;
    private static boolean synthetic;
    private static java.lang.ref.WeakReference<ViewGroup> heldRoot;
    private static KeyHandler heldHandler;

    private static final Runnable REPEAT = new Runnable() {
        @Override
        public void run() {
            ViewGroup root = heldRoot == null ? null : heldRoot.get();
            if (heldDir == 0 || root == null || !root.isAttachedToWindow() || !root.hasWindowFocus()) {
                heldDir = 0;
                return;
            }
            heldRepeats++;
            fire(root, heldDir, heldRepeats);
            // Held: repeats get faster the longer it's held (130 ms → 55 ms).
            long held = android.os.SystemClock.uptimeMillis() - heldSince;
            MAIN.postDelayed(this, Math.max(55, 130 - held / 25));
        }
    };

    private static int keyFor(int dir) {
        switch (dir) {
            case View.FOCUS_LEFT: return KeyEvent.KEYCODE_DPAD_LEFT;
            case View.FOCUS_RIGHT: return KeyEvent.KEYCODE_DPAD_RIGHT;
            case View.FOCUS_UP: return KeyEvent.KEYCODE_DPAD_UP;
            default: return KeyEvent.KEYCODE_DPAD_DOWN;
        }
    }

    private static void fire(ViewGroup root, int dir, int repeat) {
        long now = SystemClock.uptimeMillis();
        KeyEvent k = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyFor(dir), repeat);
        synthetic = true;
        try {
            if (heldHandler != null) {
                heldHandler.handle(k);
            } else {
                onKey(root, k, null);
            }
        } finally {
            synthetic = false;
        }
    }

    /** Hat axes and left stick, moving like the D-pad keys (old screens without own key handling). */
    public static boolean onMotion(ViewGroup root, MotionEvent e, View fallback) {
        final View fb = fallback;
        return onMotion(root, e, k -> onKey(root, k, fb));
    }

    /**
     * Hat axes and left stick (joystick motion events). They become D-pad key events for the
     * screen's own handler, so stick, hat and D-pad keys all move the same way; holding keeps
     * moving (with a timer, since a held hat / stick sends no further events).
     */
    public static boolean onMotion(ViewGroup root, MotionEvent e, KeyHandler handler) {
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK
                || e.getAction() != MotionEvent.ACTION_MOVE) {
            return false;
        }
        float hx = e.getAxisValue(MotionEvent.AXIS_HAT_X), hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
        int hat = hx < -0.5f ? View.FOCUS_LEFT : hx > 0.5f ? View.FOCUS_RIGHT
                : hy < -0.5f ? View.FOCUS_UP : hy > 0.5f ? View.FOCUS_DOWN : 0;
        long now = SystemClock.uptimeMillis();
        // Controllers that send the D-pad as keys AND as hat axes: the keys win (no double steps).
        if (now - lastRealKey < 400) {
            hat = 0;
        }
        lastHatDir = hat;
        float sx = e.getAxisValue(MotionEvent.AXIS_X), sy = e.getAxisValue(MotionEvent.AXIS_Y);
        // Hysteresis: 0.6 to start, back under 0.4 to stop.
        float on = heldDir != 0 ? 0.4f : 0.6f;
        int stick = Math.abs(sx) > Math.abs(sy)
                ? (sx < -on ? View.FOCUS_LEFT : sx > on ? View.FOCUS_RIGHT : 0)
                : (sy < -on ? View.FOCUS_UP : sy > on ? View.FOCUS_DOWN : 0);
        int dir = hat != 0 ? hat : stick;
        if (dir != heldDir) {
            MAIN.removeCallbacks(REPEAT);
            heldDir = dir;
            heldRepeats = 0;
            heldRoot = new java.lang.ref.WeakReference<>(root);
            heldHandler = handler;
            if (dir != 0) {
                heldSince = now;
                fire(root, dir, 0);
                MAIN.postDelayed(REPEAT, 360);
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
            if (next.requestFocus(dir)) {
                DuoMotion.navTick(next);
            }
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
