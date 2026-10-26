package com.limelight.duo;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import com.limelight.nvstream.input.MouseButtonPacket;

/**
 * A touch surface that acts as a trackpad:
 *  - tap (no movement)             -> onTap()
 *  - 1 finger swipe                -> relative mouse movement
 *  - long press                    -> onLongPress() (e.g. umlaut), repeat on repeatable
 *                                     spots, or drag if dragOnLongPress()
 *  - 2 / 3 finger tap              -> onTwoFingerTap() / onThreeFingerTap()
 *  - 2 finger swipe                -> scroll wheel
 *  - 3 finger swipe                -> drag (left button held), e.g. move windows/files
 */
public abstract class DuoTouchSurface extends View {
    private enum Mode { NONE, PENDING, TYPED, HELD, MOUSE, DRAG, REPEAT, MULTI, SCROLL, DRAG3, PINCH }

    private static final long LONG_PRESS_MS = 380;
    private static final long REPEAT_DELAY_MS = 55;
    private static final long MULTI_TAP_MAX_MS = 350;
    /** A second finger landing later than this is fast typing, not a 2-finger gesture. */
    private static final long ROLLOVER_MS = 70;

    protected DuoInputSink sink;
    protected final float density;
    private final float slop;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private float sensitivity = 0.62f;

    private Mode mode = Mode.NONE;
    private float downX, downY, lastX, lastY;
    private float lastCx, lastCy;
    private float startSpan, lastSpan, zoomRem;
    private boolean resetCentroid;
    private long downTime;
    private int activeId;
    private int maxPointers;
    private float remX, remY, scrollRemV, scrollRemH;
    private long lastMoveTime;
    private float smoothVelocity;
    /** Keyboard: two fingers landed almost together; typed as two keys unless they move. */
    private boolean multiKeys;
    private float secondX, secondY;

    private final Runnable longPress = new Runnable() {
        @Override
        public void run() {
            if (mode != Mode.PENDING && mode != Mode.TYPED) {
                return;
            }
            boolean typed = mode == Mode.TYPED;
            if (isRepeatable(downX, downY)) {
                mode = Mode.REPEAT;
                onTap(downX, downY);
                handler.postDelayed(repeat, REPEAT_DELAY_MS);
            } else if (onLongPress(downX, downY, typed)) {
                mode = Mode.HELD;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            } else if (!typed && dragOnLongPress()) {
                mode = Mode.DRAG;
                onCancelPress();
                onPointerAction();
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                if (sink != null) {
                    sink.sendMouseButton(MouseButtonPacket.BUTTON_LEFT, true);
                }
            }
            // Otherwise stay PENDING: releasing still counts as a normal tap.
        }
    };

    private final Runnable repeat = new Runnable() {
        @Override
        public void run() {
            if (mode != Mode.REPEAT) {
                return;
            }
            onTap(downX, downY);
            handler.postDelayed(this, REPEAT_DELAY_MS);
        }
    };

    public DuoTouchSurface(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        slop = tapSlopDp() * density;
        setHapticFeedbackEnabled(true);
    }

    public void setSink(DuoInputSink sink) {
        this.sink = sink;
    }

    public void setSensitivity(float sensitivity) {
        this.sensitivity = sensitivity;
    }

    // ---- hooks for subclasses ----
    /** How far a finger may move and still count as a tap. */
    protected float tapSlopDp() { return 9f; }
    protected void onPress(float x, float y) {}
    protected void onCancelPress() {}
    protected abstract void onTap(float x, float y);
    protected boolean isRepeatable(float x, float y) { return false; }
    /** Return true to take over a long press (finger still down); onLongRelease follows. */
    protected boolean onLongPress(float x, float y, boolean alreadyTyped) { return false; }
    /** Return true to fire the key immediately on touch-down (fast typing). */
    protected boolean fireOnDown(float x, float y) { return false; }
    protected void onLongRelease(float x, float y) {}
    /** Long press without movement starts a drag (trackpad); keyboards return false. */
    protected boolean dragOnLongPress() { return true; }
    protected abstract void onTwoFingerTap();
    protected abstract void onThreeFingerTap();
    /** Keyboards return true so overlapping taps from two thumbs are typed, not treated as gestures. */
    protected boolean supportsRollover() { return false; }
    /** Called when the touch turns into mouse movement, dragging or scrolling. */
    protected void onPointerAction() {}
    /** False for pure keyboards (compact mode): swipes don't move the mouse. */
    protected boolean trackpadEnabled() { return true; }

    private void beginPending(MotionEvent event, int index) {
        mode = Mode.PENDING;
        activeId = event.getPointerId(index);
        downX = lastX = event.getX(index);
        downY = lastY = event.getY(index);
        downTime = event.getEventTime();
        lastMoveTime = downTime;
        smoothVelocity = 0;
        maxPointers = 1;
        remX = remY = 0;
        multiKeys = false;
        handler.postDelayed(longPress, LONG_PRESS_MS);
        onPress(downX, downY);
        if (fireOnDown(downX, downY)) {
            // Typing burst: no need to wait for a swipe, type right away.
            mode = Mode.TYPED;
            onTap(downX, downY);
        }
    }

    /** Two quick fingers on the keyboard: both keys, in the order they touched down. */
    private void typeBothKeys() {
        multiKeys = false;
        onTap(downX, downY);
        onTap(secondX, secondY);
    }

    /** Commits whatever the current single finger means (key or held key). */
    private void commitSingle() {
        if (mode == Mode.PENDING) {
            onTap(downX, downY);
        } else if (mode == Mode.HELD) {
            onLongRelease(downX, downY);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // Deliver touch samples as soon as they arrive instead of once per frame.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    requestUnbufferedDispatch(event);
                }
                beginPending(event, 0);
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (supportsRollover()
                        && (mode == Mode.PENDING || mode == Mode.TYPED || mode == Mode.HELD || mode == Mode.NONE)
                        && (mode != Mode.PENDING || event.getEventTime() - downTime > ROLLOVER_MS)) {
                    // Fast typing: commit the first key now, the new finger is the next key.
                    cancelTimers();
                    commitSingle();
                    beginPending(event, event.getActionIndex());
                    return true;
                }
                maxPointers = Math.max(maxPointers, event.getPointerCount());
                if (supportsRollover() && mode == Mode.PENDING && event.getPointerCount() == 2) {
                    // Two thumbs typing at nearly the same moment: remember the second key.
                    multiKeys = true;
                    secondX = event.getX(event.getActionIndex());
                    secondY = event.getY(event.getActionIndex());
                } else {
                    multiKeys = false;
                }
                if (mode == Mode.PENDING || mode == Mode.TYPED || mode == Mode.HELD || mode == Mode.MOUSE || mode == Mode.REPEAT) {
                    cancelTimers();
                    onCancelPress();
                    mode = Mode.MULTI;
                }
                resetCentroid = true;
                return true;

            case MotionEvent.ACTION_MOVE:
                handleMove(event);
                return true;

            case MotionEvent.ACTION_POINTER_UP: {
                int upId = event.getPointerId(event.getActionIndex());
                if (supportsRollover() && (mode == Mode.PENDING || mode == Mode.TYPED || mode == Mode.HELD)) {
                    if (upId == activeId) {
                        // The newest finger lifted first: type it, ignore the rest.
                        cancelTimers();
                        commitSingle();
                        mode = Mode.NONE;
                        onCancelPress();
                    }
                    // An older, already typed finger lifted: nothing to do.
                    return true;
                }
                if (mode == Mode.MULTI && multiKeys) {
                    // One of the two fingers lifted without moving: that was typing.
                    typeBothKeys();
                    mode = Mode.NONE;
                    return true;
                }
                resetCentroid = true;
                return true;
            }

            case MotionEvent.ACTION_UP:
                cancelTimers();
                switch (mode) {
                    case PENDING:
                    case HELD:
                        commitSingle();
                        break;
                    case MULTI:
                        if (multiKeys) {
                            typeBothKeys();
                        } else if (event.getEventTime() - downTime <= MULTI_TAP_MAX_MS) {
                            if (maxPointers >= 3) {
                                onThreeFingerTap();
                            } else if (maxPointers == 2) {
                                onTwoFingerTap();
                            }
                        }
                        break;
                    case DRAG:
                    case DRAG3:
                        if (sink != null) {
                            sink.sendMouseButton(MouseButtonPacket.BUTTON_LEFT, false);
                        }
                        break;
                    case PINCH:
                        endPinch();
                        break;
                    default:
                        break;
                }
                finish();
                return true;

            case MotionEvent.ACTION_CANCEL:
                cancelTimers();
                if (mode == Mode.PINCH) {
                    endPinch();
                }
                if ((mode == Mode.DRAG || mode == Mode.DRAG3) && sink != null) {
                    sink.sendMouseButton(MouseButtonPacket.BUTTON_LEFT, false);
                }
                finish();
                return true;
        }
        return super.onTouchEvent(event);
    }

    private void handleMove(MotionEvent event) {
        int index = event.findPointerIndex(activeId);
        if (index < 0) {
            index = 0;
        }
        float x = event.getX(index);
        float y = event.getY(index);

        switch (mode) {
            case PENDING:
                if (trackpadEnabled() && Math.hypot(x - downX, y - downY) > slop) {
                    cancelTimers();
                    onCancelPress();
                    mode = Mode.MOUSE;
                    onPointerAction();
                    // Start from here (no jump by the slop distance).
                    lastX = x;
                    lastY = y;
                    lastMoveTime = event.getEventTime();
                }
                break;

            case MOUSE:
            case DRAG: {
                // Walk through batched samples so acceleration sees the real finger speed.
                int history = event.getHistorySize();
                for (int i = 0; i < history; i++) {
                    float hx = event.getHistoricalX(index, i);
                    float hy = event.getHistoricalY(index, i);
                    moveMouse(hx - lastX, hy - lastY, event.getHistoricalEventTime(i));
                    lastX = hx;
                    lastY = hy;
                }
                moveMouse(x - lastX, y - lastY, event.getEventTime());
                lastX = x;
                lastY = y;
                break;
            }

            case MULTI:
            case SCROLL:
            case DRAG3:
            case PINCH: {
                if (event.getPointerCount() < 2) {
                    resetCentroid = true;
                    break;
                }
                int n = Math.min(3, event.getPointerCount());
                float cx = 0, cy = 0;
                for (int i = 0; i < n; i++) {
                    cx += event.getX(i);
                    cy += event.getY(i);
                }
                cx /= n;
                cy /= n;
                float span = (float) Math.hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1));
                if (resetCentroid) {
                    lastCx = cx;
                    lastCy = cy;
                    lastSpan = span;
                    if (mode == Mode.MULTI) {
                        startSpan = span;
                    }
                    resetCentroid = false;
                    break;
                }
                float dx = cx - lastCx;
                float dy = cy - lastCy;
                if (mode == Mode.PINCH) {
                    zoom(span - lastSpan);
                    lastSpan = span;
                    lastCx = cx;
                    lastCy = cy;
                    break;
                }
                if (mode == Mode.MULTI && maxPointers == 2 && event.getPointerCount() == 2
                        && Math.abs(span - startSpan) > slop * 2.5f
                        && Math.abs(span - startSpan) > Math.hypot(dx, dy)) {
                    // Two fingers apart/together: zoom like a laptop trackpad (Ctrl + wheel).
                    mode = Mode.PINCH;
                    onPointerAction();
                    zoomRem = 0;
                    if (sink != null) {
                        sink.sendKey(DuoKeys.VK_LCONTROL, true, (byte) 0);
                    }
                    lastSpan = span;
                    lastCx = cx;
                    lastCy = cy;
                    break;
                }
                if (mode == Mode.MULTI) {
                    if (Math.hypot(dx, dy) > slop) {
                        onPointerAction();
                        if (maxPointers >= 3) {
                            // Three-finger drag: hold the left button while moving.
                            mode = Mode.DRAG3;
                            lastMoveTime = event.getEventTime();
                            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                            if (sink != null) {
                                sink.sendMouseButton(MouseButtonPacket.BUTTON_LEFT, true);
                            }
                        } else {
                            mode = Mode.SCROLL;
                        }
                        lastCx = cx;
                        lastCy = cy;
                    }
                    break;
                }
                if (mode == Mode.DRAG3) {
                    moveMouse(dx, dy, event.getEventTime());
                } else {
                    scroll(dx, dy);
                }
                lastCx = cx;
                lastCy = cy;
                break;
            }

            default:
                break;
        }
    }

    private void moveMouse(float dx, float dy, long time) {
        if (sink == null || (dx == 0 && dy == 0)) {
            return;
        }
        // Speed in dp per millisecond, smoothed to avoid jitter in the acceleration.
        long dt = Math.max(1, time - lastMoveTime);
        lastMoveTime = time;
        float v = (float) Math.hypot(dx, dy) / density / dt;
        smoothVelocity = smoothVelocity * 0.6f + v * 0.4f;

        // Pointer-precision curve: slow = 1.0x, fast = up to 1.9x.
        float t = Math.max(0f, Math.min(1f, (smoothVelocity - 0.1f) / 1.4f));
        float gain = sensitivity * (1.0f + 0.9f * t * t * (3f - 2f * t));

        remX += dx * gain;
        remY += dy * gain;
        int ix = (int) remX;
        int iy = (int) remY;
        remX -= ix;
        remY -= iy;
        if (ix != 0 || iy != 0) {
            sink.sendMouseMove(ix, iy);
        }
    }

    private void scroll(float dx, float dy) {
        if (sink == null) {
            return;
        }
        // About one wheel notch per 22dp of finger travel, natural direction.
        float factor = 120f / (22f * density);
        scrollRemV += dy * factor;
        scrollRemH += -dx * factor;
        short v = (short) scrollRemV;
        short h = (short) scrollRemH;
        scrollRemV -= v;
        scrollRemH -= h;
        if (v != 0 || h != 0) {
            sink.sendScroll(v, h);
        }
    }

    private void cancelTimers() {
        handler.removeCallbacks(longPress);
        handler.removeCallbacks(repeat);
    }

    private void finish() {
        mode = Mode.NONE;
        scrollRemV = scrollRemH = 0;
        onCancelPress();
    }

    private void zoom(float dSpan) {
        if (sink == null) {
            return;
        }
        // One full wheel notch per 70dp of finger spread; apart = zoom in.
        // Only whole notches: browsers zoom one step per wheel event, however small,
        // so fine-grained deltas made pinching far too jumpy.
        zoomRem += dSpan / (70f * density);
        while (zoomRem >= 1f) {
            sink.sendScroll((short) 120, (short) 0);
            zoomRem -= 1f;
        }
        while (zoomRem <= -1f) {
            sink.sendScroll((short) -120, (short) 0);
            zoomRem += 1f;
        }
    }

    private void endPinch() {
        if (sink != null) {
            sink.sendKey(DuoKeys.VK_LCONTROL, false, (byte) 0);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mode == Mode.PINCH) {
            endPinch();
        }
        cancelTimers();
        if ((mode == Mode.DRAG || mode == Mode.DRAG3) && sink != null) {
            sink.sendMouseButton(MouseButtonPacket.BUTTON_LEFT, false);
        }
        mode = Mode.NONE;
        super.onDetachedFromWindow();
    }
}
