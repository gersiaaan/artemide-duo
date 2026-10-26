package com.limelight.duo;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

/** "Aa" above a small keyboard: icon for the phone keyboard (Gboard) button. */
class DuoAaDrawable extends Drawable {
    private final float density;
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    DuoAaDrawable(float density, int color) {
        this.density = density;
        text.setColor(color);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        text.setTextSize(9 * density);
        stroke.setColor(color);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.4f * density);
        stroke.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float u = Math.min(b.width(), b.height()) / 24f;   // 24-unit icon grid
        float ox = b.left + (b.width() - 24 * u) / 2f;
        float oy = b.top + (b.height() - 24 * u) / 2f;
        text.setTextSize(9 * u);
        canvas.drawText("Aa", ox + 12 * u, oy + 10 * u, text);
        stroke.setStrokeWidth(1.4f * u);
        rect.set(ox + 3 * u, oy + 13 * u, ox + 21 * u, oy + 21 * u);
        canvas.drawRoundRect(rect, 1.5f * u, 1.5f * u, stroke);
        float y1 = oy + 16 * u;
        for (float x : new float[]{6.5f, 9.5f, 12.5f, 15.5f}) {
            canvas.drawPoint(ox + x * u, y1, stroke);
        }
        canvas.drawLine(ox + 8.5f * u, oy + 18.5f * u, ox + 15.5f * u, oy + 18.5f * u, stroke);
    }

    @Override
    public int getIntrinsicWidth() {
        return Math.round(24 * density);
    }

    @Override
    public int getIntrinsicHeight() {
        return Math.round(24 * density);
    }

    @Override
    public void setAlpha(int alpha) {
        text.setAlpha(alpha);
        stroke.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        text.setColorFilter(cf);
        stroke.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
