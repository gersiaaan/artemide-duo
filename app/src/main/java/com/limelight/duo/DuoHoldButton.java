package com.limelight.duo;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.animation.LinearInterpolator;
import android.widget.ImageButton;

/**
 * Icon button that only fires after being held: a ring runs around the edge while
 * the finger stays down; letting go early cancels. Used for "Verbindung trennen".
 */
@SuppressLint("AppCompatCustomView")
public class DuoHoldButton extends ImageButton {
    private final float density;
    private final long holdMs;
    private final Runnable action;
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path outline = new Path();
    private final Path partial = new Path();
    private final RectF rect = new RectF();
    private PathMeasure measure;
    private float progress;
    private ValueAnimator animator;
    private boolean fired;

    public DuoHoldButton(Context context, long holdMs, Runnable action) {
        super(context);
        this.density = context.getResources().getDisplayMetrics().density;
        this.holdMs = holdMs;
        this.action = action;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2 * density);
        ring.setStrokeCap(Paint.Cap.ROUND);
        ring.setColor(DuoColors.HI_TEXT);
        setFocusable(false);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float inset = density;
        rect.set(inset, inset, w - inset, h - inset);
        float r = 10 * density;
        // Start at the top centre and run clockwise.
        outline.reset();
        outline.moveTo(w / 2f, rect.top);
        outline.lineTo(rect.right - r, rect.top);
        outline.quadTo(rect.right, rect.top, rect.right, rect.top + r);
        outline.lineTo(rect.right, rect.bottom - r);
        outline.quadTo(rect.right, rect.bottom, rect.right - r, rect.bottom);
        outline.lineTo(rect.left + r, rect.bottom);
        outline.quadTo(rect.left, rect.bottom, rect.left, rect.bottom - r);
        outline.lineTo(rect.left, rect.top + r);
        outline.quadTo(rect.left, rect.top, rect.left + r, rect.top);
        outline.lineTo(w / 2f, rect.top);
        measure = new PathMeasure(outline, false);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (progress > 0 && measure != null) {
            partial.reset();
            measure.getSegment(0, measure.getLength() * progress, partial, true);
            canvas.drawPath(partial, ring);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                fired = false;
                setPressed(true);
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                start();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (e.getX() < -20 * density || e.getY() < -20 * density
                        || e.getX() > getWidth() + 20 * density || e.getY() > getHeight() + 20 * density) {
                    cancel();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                cancel();
                return true;
        }
        return true;
    }

    private void start() {
        if (animator != null) {
            animator.cancel();
        }
        animator = ValueAnimator.ofFloat(progress, 1f);
        animator.setDuration((long) (holdMs * (1f - progress)));
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
            if (progress >= 1f && !fired) {
                fired = true;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                setPressed(false);
                progress = 0;
                invalidate();
                if (action != null) {
                    action.run();
                }
            }
        });
        animator.start();
    }

    /** Released early: the ring runs back quickly, nothing happens. */
    private void cancel() {
        setPressed(false);
        if (fired) {
            return;
        }
        if (animator != null) {
            animator.cancel();
        }
        if (progress <= 0) {
            return;
        }
        animator = ValueAnimator.ofFloat(progress, 0f);
        animator.setDuration(180);
        animator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.cancel();
        }
        super.onDetachedFromWindow();
    }
}
