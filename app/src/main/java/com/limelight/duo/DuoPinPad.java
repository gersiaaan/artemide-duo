package com.limelight.duo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * Small PIN pad shown over the stream while the host's screen is locked (MacShine reports it).
 * It sits to the right of the centred login field, as wide as that field, and types digits,
 * backspace and return to the host. It never moves the host's login: it only floats beside it.
 */
public class DuoPinPad extends View {
    /** The host's login field is about this wide, relative to the stream. */
    private static final float WIDTH_OF_STREAM = 0.165f;
    /** Left edge of the pad, right of the stream's centre, relative to the stream width. */
    private static final float LEFT_OF_CENTRE = 0.115f;
    /** Vertical centre of the pad (the login field's height on the lock screen), relative to the stream height. */
    private static final float CENTRE_Y = 0.6f;

    private static final int VK_BACK = 0x08;
    private static final int VK_RETURN = 0x0D;
    private static final String[] LABELS = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "↵"};

    private final Paint panel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint key = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint keyDown = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint enter = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF[] keys = new RectF[12];
    private final RectF frame = new RectF();
    private final float density;
    private View stream;
    private DuoInputSink sink;
    private int pressed = -1;

    public DuoPinPad(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        panel.setColor(Color.argb(140, 10, 14, 24));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(density);
        border.setColor(Color.argb(26, 255, 255, 255));
        key.setColor(Color.argb(31, 255, 255, 255));
        keyDown.setColor(Color.argb(80, 255, 255, 255));
        enter.setColor(Color.rgb(47, 91, 211));
        text.setColor(Color.rgb(242, 243, 245));
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(DuoFonts.medium());
        for (int i = 0; i < keys.length; i++) {
            keys[i] = new RectF();
        }
        setVisibility(GONE);
    }

    /** Lay the pad out against {@code streamView} (a sibling) and type into {@code inputSink}. */
    public void attach(View streamView, DuoInputSink inputSink) {
        this.stream = streamView;
        this.sink = inputSink;
        streamView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> invalidate());
    }

    /** Show while the host is locked, hide once it is unlocked. */
    public void setLocked(boolean locked) {
        int want = locked ? VISIBLE : GONE;
        if (getVisibility() != want) {
            pressed = -1;
            setVisibility(want);
        }
    }

    private boolean layoutKeys() {
        if (stream == null || stream.getWidth() <= 0) {
            return false;
        }
        float sw = stream.getWidth();
        float sh = stream.getHeight();
        float pad = 6 * density;
        float gap = 5 * density;
        float width = Math.max(sw * WIDTH_OF_STREAM, 132 * density);
        float keyW = (width - 2 * pad - 2 * gap) / 3f;
        float keyH = Math.max(keyW * 0.62f, 34 * density);
        float height = 2 * pad + 4 * keyH + 3 * gap;
        float left = stream.getX() + sw / 2f + sw * LEFT_OF_CENTRE;
        float top = stream.getY() + sh * CENTRE_Y - height / 2f;
        left = Math.min(left, stream.getX() + sw - width - 12 * density);
        frame.set(left, top, left + width, top + height);
        for (int i = 0; i < 12; i++) {
            int row = i / 3;
            int col = i % 3;
            float x = left + pad + col * (keyW + gap);
            float y = top + pad + row * (keyH + gap);
            keys[i].set(x, y, x + keyW, y + keyH);
        }
        text.setTextSize(keyH * 0.45f);
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!layoutKeys()) {
            return;
        }
        float r = 12 * density;
        canvas.drawRoundRect(frame, r, r, panel);
        canvas.drawRoundRect(frame, r, r, border);
        float kr = 9 * density;
        for (int i = 0; i < 12; i++) {
            Paint p = i == 11 ? enter : (i == pressed ? keyDown : key);
            canvas.drawRoundRect(keys[i], kr, kr, p);
            float ty = keys[i].centerY() - (text.descent() + text.ascent()) / 2f;
            canvas.drawText(LABELS[i], keys[i].centerX(), ty, text);
        }
    }

    private int keyAt(float x, float y) {
        for (int i = 0; i < 12; i++) {
            if (keys[i].contains(x, y)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Only touches on the pad itself are ours; everything else goes on to the stream.
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && !frame.contains(event.getX(), event.getY())) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = keyAt(event.getX(), event.getY());
                if (pressed >= 0) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                int k = keyAt(event.getX(), event.getY());
                if (k >= 0 && k == pressed) {
                    type(k);
                }
                pressed = -1;
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressed = -1;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private void type(int index) {
        if (sink == null) {
            return;
        }
        int vk;
        if (index == 9) {
            vk = VK_BACK;
        } else if (index == 11) {
            vk = VK_RETURN;
        } else {
            vk = '0' + Integer.parseInt(LABELS[index]);
        }
        sink.sendKey(vk, true, (byte) 0);
        sink.sendKey(vk, false, (byte) 0);
    }
}
