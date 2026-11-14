package com.limelight.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/**
 * Rounded stream corners like a macOS window. A transparent view above the stream that paints
 * black over the four corners of the video, following the stream container's position and size.
 * It never takes touches. The radius is a fixed share of the stream's width, so it scales with the
 * stream and matches the window corners in the picture.
 */
public class StreamCornerMask extends View {
    // A macOS window corner (about 12 pt on a 2x Retina desktop) is ~1.35 % of a 1800 px wide stream.
    private static final float CORNER_RADIUS_FRACTION_OF_WIDTH = 24f / 1800f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private View target;

    public StreamCornerMask(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setColor(Color.BLACK);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Follows {@code view} (a sibling) and rounds its corners. */
    public void attach(View view) {
        this.target = view;
        view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> invalidate());
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (target == null || target.getVisibility() != VISIBLE) {
            return;
        }
        float left = target.getX();
        float top = target.getY();
        float w = target.getWidth();
        float h = target.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float r = CORNER_RADIUS_FRACTION_OF_WIDTH * w;
        path.reset();
        path.setFillType(Path.FillType.EVEN_ODD);
        path.addRect(left, top, left + w, top + h, Path.Direction.CW);
        path.addRoundRect(left, top, left + w, top + h, r, r, Path.Direction.CW);
        canvas.drawPath(path, paint);
    }
}
