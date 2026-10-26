package com.limelight.duo;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import androidx.preference.PreferenceManager;

import com.limelight.nvstream.input.MouseButtonPacket;

/**
 * Controller "Desktop" mode (G/D button in the panel), only for desktop / remote streams:
 * left stick = mouse (soft curve: fine when slightly pushed, fast when fully pushed),
 * right stick = scroll, A = left click, B = right click, X = Windows key, Y = Alt+Tab.
 * Games, the host page and the app list always use the normal gamepad mode.
 */
public final class DuoGamepadMouse {
    private DuoGamepadMouse() {}

    public static final String KEY_DESKTOP = "duo_pad_desktop";
    public static final String KEY_SPEED = "duo_pad_speed";

    private static final int VK_TAB = 0x09;
    private static final int VK_LWIN = 0x5B;
    private static final int VK_LMENU = 0xA4;
    private static final byte MOD_ALT = 0x04;

    private static final long TICK_MS = 8;
    private static final float DEAD = 0.12f;
    private static final long Y_REPEAT_MS = 420;

    private static boolean streamIsDesktop;
    private static boolean desktop;
    private static int speed = 5;
    private static DuoInputSink sink;
    private static Runnable listener;

    private static float lx, ly, rx, ry;
    private static float accX, accY, accScroll, accHScroll;
    private static boolean ticking;
    private static boolean altHeld;
    private static long lastTab;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Called when a stream starts. Desktop mode only exists for desktop / remote apps. */
    public static void init(Context c, boolean desktopStream, DuoInputSink s) {
        streamIsDesktop = desktopStream;
        sink = s;
        desktop = desktopStream && PreferenceManager.getDefaultSharedPreferences(c).getBoolean(KEY_DESKTOP, false);
        speed = PreferenceManager.getDefaultSharedPreferences(c).getInt(KEY_SPEED, 5);
        reset();
    }

    public static void end() {
        reset();
        streamIsDesktop = false;
        desktop = false;
        sink = null;
    }

    public static boolean available() {
        return streamIsDesktop;
    }

    public static boolean isDesktop() {
        return desktop && streamIsDesktop;
    }

    public static void setListener(Runnable r) {
        listener = r;
    }

    public static void toggle(Context c) {
        if (!streamIsDesktop) {
            return;
        }
        desktop = !desktop;
        PreferenceManager.getDefaultSharedPreferences(c).edit().putBoolean(KEY_DESKTOP, desktop).apply();
        reset();
        if (listener != null) {
            listener.run();
        }
    }

    public static int getSpeed(Context c) {
        return PreferenceManager.getDefaultSharedPreferences(c).getInt(KEY_SPEED, 5);
    }

    public static void setSpeed(Context c, int s) {
        speed = Math.max(1, Math.min(10, s));
        PreferenceManager.getDefaultSharedPreferences(c).edit().putInt(KEY_SPEED, speed).apply();
    }

    private static boolean fromPad(InputDevice dev, int source) {
        int s = dev != null ? dev.getSources() : source;
        return (s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    /** Face buttons. Returns true when consumed. */
    public static boolean onKey(KeyEvent e) {
        if (!isDesktop() || sink == null || !fromPad(e.getDevice(), e.getSource())) {
            return false;
        }
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        boolean first = down && e.getRepeatCount() == 0;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_BUTTON_A:
                if (first || !down) {
                    sink.sendMouseButton(MouseButtonPacket.BUTTON_LEFT, down);
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
                if (first || !down) {
                    sink.sendMouseButton(MouseButtonPacket.BUTTON_RIGHT, down);
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_X:
                if (first || !down) {
                    sink.sendKey(VK_LWIN, down, (byte) 0);
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
                if (down) {
                    long now = SystemClock.uptimeMillis();
                    if (!altHeld) {
                        sink.sendKey(VK_LMENU, true, MOD_ALT);
                        altHeld = true;
                        tapTab();
                        lastTab = now;
                    } else if (now - lastTab >= Y_REPEAT_MS) {
                        // Holding Y keeps Alt down and steps through the windows.
                        tapTab();
                        lastTab = now;
                    }
                } else if (altHeld) {
                    sink.sendKey(VK_LMENU, false, (byte) 0);
                    altHeld = false;
                }
                return true;
            default:
                return false;
        }
    }

    private static void tapTab() {
        sink.sendKey(VK_TAB, true, MOD_ALT);
        sink.sendKey(VK_TAB, false, MOD_ALT);
    }

    /** Sticks. Returns true when consumed. */
    public static boolean onMotion(MotionEvent e) {
        if (!isDesktop() || sink == null
                || (e.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK
                || e.getAction() != MotionEvent.ACTION_MOVE) {
            return false;
        }
        lx = e.getAxisValue(MotionEvent.AXIS_X);
        ly = e.getAxisValue(MotionEvent.AXIS_Y);
        rx = e.getAxisValue(MotionEvent.AXIS_Z);
        ry = e.getAxisValue(MotionEvent.AXIS_RZ);
        if (!ticking && (active(lx, ly) || active(rx, ry))) {
            ticking = true;
            MAIN.postDelayed(TICK, TICK_MS);
        }
        return true;
    }

    private static boolean active(float x, float y) {
        return Math.hypot(x, y) > DEAD;
    }

    /** 0 in the dead zone, then a soft curve up to 1. */
    private static float curve(float mag) {
        if (mag <= DEAD) {
            return 0;
        }
        float n = Math.min(1f, (mag - DEAD) / (1f - DEAD));
        return (float) Math.pow(n, 2.2);
    }

    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            if (sink == null || !isDesktop()) {
                ticking = false;
                return;
            }
            boolean any = false;
            float mag = (float) Math.hypot(lx, ly);
            if (mag > DEAD) {
                any = true;
                // speed 1 ≈ 400 px/s, 10 ≈ 2600 px/s at full tilt.
                float pxPerTick = (3f + speed * 2.2f) * curve(mag);
                accX += lx / mag * pxPerTick;
                accY += ly / mag * pxPerTick;
                int dx = (int) accX, dy = (int) accY;
                accX -= dx;
                accY -= dy;
                if (dx != 0 || dy != 0) {
                    sink.sendMouseMove(dx, dy);
                }
            }
            float rmag = (float) Math.hypot(rx, ry);
            if (rmag > DEAD) {
                any = true;
                float k = curve(rmag) / rmag * 32f;
                accScroll += -ry * k;
                accHScroll += rx * k;
                short v = (short) accScroll, h = (short) accHScroll;
                accScroll -= v;
                accHScroll -= h;
                if (v != 0 || h != 0) {
                    sink.sendScroll(v, h);
                }
            }
            if (any) {
                MAIN.postDelayed(this, TICK_MS);
            } else {
                ticking = false;
            }
        }
    };

    private static void reset() {
        MAIN.removeCallbacks(TICK);
        ticking = false;
        lx = ly = rx = ry = 0;
        accX = accY = accScroll = accHScroll = 0;
        if (altHeld && sink != null) {
            sink.sendKey(VK_LMENU, false, (byte) 0);
        }
        altHeld = false;
    }
}
