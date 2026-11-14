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
    /** How far the continuous corner reaches along each edge, relative to the radius. */
    private static final float CORNER_EXTENT = 1.53f;
    /** Superellipse exponent: 2 is a circle; ~5 matches Apple's continuous corners. */
    private static final double SUPERELLIPSE_N = 5.0;

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

    /**
     * Rounded rectangle with Apple-style continuous corners: a superellipse instead of a quarter
     * circle, so the straight edge flows into the curve without a visible kink (like macOS windows).
     */
    static void addContinuousRoundRect(Path path, float l, float t, float r, float b, float radius) {
        // The continuous curve starts further out than a circle of the same radius (iOS/macOS: ~1.53x).
        float extent = Math.min(radius * CORNER_EXTENT, Math.min(r - l, b - t) / 2f);
        final int steps = 24;
        // Corners in order top-left, top-right, bottom-right, bottom-left; each from its edge start to end.
        float[][] corners = {
                {l, t, 1, 1}, {r, t, -1, 1}, {r, b, -1, -1}, {l, b, 1, -1},
        };
        boolean first = true;
        for (int c = 0; c < 4; c++) {
            float cx = corners[c][0], cy = corners[c][1], sx = corners[c][2], sy = corners[c][3];
            for (int i = 0; i <= steps; i++) {
                // Parameter runs from the vertical edge to the horizontal edge (or back, by corner).
                double a = Math.PI / 2 * i / steps;
                if (c == 1 || c == 3) {
                    a = Math.PI / 2 - a;
                }
                double ux = Math.pow(Math.cos(a), 2.0 / SUPERELLIPSE_N);
                double uy = Math.pow(Math.sin(a), 2.0 / SUPERELLIPSE_N);
                float px = cx + sx * extent * (float) (1 - ux);
                float py = cy + sy * extent * (float) (1 - uy);
                if (first) {
                    path.moveTo(px, py);
                    first = false;
                } else {
                    path.lineTo(px, py);
                }
            }
        }
        path.close();
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
        addContinuousRoundRect(path, left, top, left + w, top + h, r);
        canvas.drawPath(path, paint);
    }
}
