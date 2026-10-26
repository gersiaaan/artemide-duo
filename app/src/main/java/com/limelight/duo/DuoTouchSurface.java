package com.limelight.duo;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import com.limelight.nvstream.input.MouseButtonPacket;

/**
 * A touch surface that acts as a trackpad:
 *  - tap (no movement)            -> onTap()
 *  - 1 finger swipe               -> relative mouse movement
 *  - long press, then move        -> drag (left button held)
 *  - long press on repeatable spot-> onTap() repeated
 *  - 2 / 3 finger tap             -> onTwoFingerTap() / onThreeFingerTap()
 *  - 2 finger swipe               -> scroll wheel
 */
public abstract class DuoTouchSurface extends View {
    private enum Mode { NONE, PENDING, MOUSE, DRAG, REPEAT, MULTI, SCROLL }

    private static final long LONG_PRESS_MS = 450;
    private static final long REPEAT_DELAY_MS = 55;
    private static final long MULTI_TAP_MAX_MS = 350;

    protected DuoInputSink sink;
    protected final float density;
    private final float slop;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private float sensitivity = 1.6f;

    private Mode mode = Mode.NONE;
    private float downX, downY, lastX, lastY;
    private float lastCx, lastCy;
    private boolean resetCentroid;
    private long downTime;
    private int maxPointers;
    private float remX, remY, scrollRemV, scrollRemH;

    private final Runnable longPress = new Runnable() {
        @Override
        public void run() {
            if (mode != Mode.PENDING) {
                return;
            }
            if (isRepeatable(downX, downY)) {
                mode = Mode.REPEAT;
                onTap(downX, downY);
                handler.postDelayed(repeat, REPEAT_DELAY_MS);
            } else {
                mode = Mode.DRAG;
                onCancelPress();
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                if (sink != null) {
                    sink.sendMouseButton(MouseButtonPacket.BUTTON_LEFT, true);
                }
            }
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
        slop = 9 * density;
        setHapticFeedbackEnabled(true);
    }

    public void setSink(DuoInputSink sink) {
        this.sink = sink;
    }

    public void setSensitivity(float sensitivity) {
        this.sensitivity = sensitivity;
    }

    // ---- hooks for subclasses ----
    protected void onPress(float x, float y) {}
    protected void onCancelPress() {}
    protected abstract void onTap(float x, float y);
    protected boolean isRepeatable(float x, float y) { return false; }
    protected abstract void onTwoFingerTap();
    protected abstract void onThreeFingerTap();

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mode = Mode.PENDING;
                downX = lastX = event.getX();
                downY = lastY = event.getY();
                downTime = event.getEventTime();
                maxPointers = 1;
                remX = remY = 0;
                handler.postDelayed(longPress, LONG_PRESS_MS);
                onPress(downX, downY);
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                maxPointers = Math.max(maxPointers, event.getPointerCount());
                if (mode == Mode.PENDING || mode == Mode.MOUSE || mode == Mode.REPEAT) {
                    cancelTimers();
                    onCancelPress();
                    mode = Mode.MULTI;
                }
                resetCentroid = true;
                return true;

            case MotionEvent.ACTION_MOVE:
                handleMove(event);
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                resetCentroid = true;
                return true;

            case MotionEvent.ACTION_UP:
                cancelTimers();
                switch (mode) {
                    case PENDING:
                        onTap(downX, downY);
                        break;
                    case MULTI:
                        if (event.getEventTime() - downTime <= MULTI_TAP_MAX_MS) {
                            if (maxPointers >= 3) {
                                onThreeFingerTap();
                            } else if (maxPointers == 2) {
                                onTwoFingerTap();
                            }
                        }
                        break;
                    case DRAG:
                        if (sink != null) {
                            sink.sendMouseButton(MouseButtonPacket.BUTTON_LEFT, false);
                        }
                        break;
                    default:
                        break;
                }
                finish();
                return true;

            case MotionEvent.ACTION_CANCEL:
                cancelTimers();
                if (mode == Mode.DRAG && sink != null) {
                    sink.sendMouseButton(MouseButtonPacket.BUTTON_LEFT, false);
                }
                finish();
                return true;
        }
        return super.onTouchEvent(event);
    }

    private void handleMove(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        switch (mode) {
            case PENDING:
                if (Math.hypot(x - downX, y - downY) > slop) {
                    cancelTimers();
                    onCancelPress();
                    mode = Mode.MOUSE;
                    lastX = downX;
                    lastY = downY;
                    moveMouse(x - lastX, y - lastY);
                    lastX = x;
                    lastY = y;
                }
                break;

            case MOUSE:
            case DRAG:
                moveMouse(x - lastX, y - lastY);
                lastX = x;
                lastY = y;
                break;

            case MULTI:
            case SCROLL: {
                if (event.getPointerCount() < 2) {
                    resetCentroid = true;
                    break;
                }
                float cx = (event.getX(0) + event.getX(1)) / 2f;
                float cy = (event.getY(0) + event.getY(1)) / 2f;
                if (resetCentroid) {
                    lastCx = cx;
                    lastCy = cy;
                    resetCentroid = false;
                    break;
                }
                float dx = cx - lastCx;
                float dy = cy - lastCy;
                if (mode == Mode.MULTI) {
                    if (Math.hypot(dx, dy) > slop) {
                        mode = Mode.SCROLL;
                        lastCx = cx;
                        lastCy = cy;
                    }
                    break;
                }
                scroll(dx, dy);
                lastCx = cx;
                lastCy = cy;
                break;
            }

            default:
                break;
        }
    }

    private void moveMouse(float dx, float dy) {
        if (sink == null) {
            return;
        }
        // Mild acceleration: faster swipes travel further.
        float speed = (float) Math.hypot(dx, dy) / density;
        float gain = sensitivity * (1f + Math.min(speed / 14f, 1.5f));
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

    @Override
    protected void onDetachedFromWindow() {
        cancelTimers();
        if (mode == Mode.DRAG && sink != null) {
            sink.sendMouseButton(MouseButtonPacket.BUTTON_LEFT, false);
        }
        mode = Mode.NONE;
        super.onDetachedFromWindow();
    }
}
