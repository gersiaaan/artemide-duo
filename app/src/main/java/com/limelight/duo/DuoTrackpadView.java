package com.limelight.duo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;

import com.limelight.nvstream.input.MouseButtonPacket;

/** Plain trackpad: tap = left click, 2-finger tap = right click, 3-finger tap = middle click. */
public class DuoTrackpadView extends DuoTouchSurface {
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    public DuoTrackpadView(Context context) {
        super(context);
        bg.setColor(DuoColors.BG);
        border.setColor(DuoColors.BORDER);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(density);
        title.setColor(DuoColors.TEXT);
        title.setTextAlign(Paint.Align.CENTER);
        title.setTextSize(15 * density);
        title.setFakeBoldText(true);
        hint.setColor(DuoColors.MUTED);
        hint.setTextAlign(Paint.Align.CENTER);
        hint.setTextSize(12 * density);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float r = 12 * density;
        rect.set(density / 2, density / 2, getWidth() - density / 2, getHeight() - density / 2);
        canvas.drawRoundRect(rect, r, r, bg);
        canvas.drawRoundRect(rect, r, r, border);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        canvas.drawText("Trackpad", cx, cy - 22 * density, title);
        canvas.drawText("Tippen: Linksklick · 2 Finger tippen: Rechtsklick", cx, cy + 2 * density, hint);
        canvas.drawText("2 Finger wischen: Scrollen · Lang drücken: Ziehen", cx, cy + 22 * density, hint);
    }

    @Override
    protected void onTap(float x, float y) {
        click(MouseButtonPacket.BUTTON_LEFT);
    }

    @Override
    protected void onTwoFingerTap() {
        click(MouseButtonPacket.BUTTON_RIGHT);
    }

    @Override
    protected void onThreeFingerTap() {
        click(MouseButtonPacket.BUTTON_MIDDLE);
    }

    private void click(byte button) {
        if (sink == null) {
            return;
        }
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        sink.sendMouseButton(button, true);
        sink.sendMouseButton(button, false);
    }
}
