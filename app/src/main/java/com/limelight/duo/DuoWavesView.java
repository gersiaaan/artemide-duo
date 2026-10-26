package com.limelight.duo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.Choreographer;
import android.view.View;

/**
 * The floor of the pre-stream screens: a dark ramp washed with the accent towards the bottom,
 * and five slow waves travelling left to right on it.
 *
 * Ported from StreamLight (FoggyBytes/StreamLight, GPL-3.0): app/gui/AmbientBackground.qml and
 * AmbientWaves.qml — same layer count, levels, amplitudes, wavelengths, travel times, swell
 * and alphas. As there, each path is built once per size and only translated per frame, and
 * the animation stops whenever nobody can see it (window without focus = dialog in front,
 * app in the background, view hidden).
 */
public class DuoWavesView extends View implements Choreographer.FrameCallback {
    private static final int LAYERS = 5;
    /** 10 % quicker than StreamLight's drift. */
    private static final float SPEED = 1.1f;

    private final int accent;
    private final Paint floor = new Paint(Paint.DITHER_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crest = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path[] crestPath = new Path[LAYERS];
    private final Path[] bodyPath = new Path[LAYERS];
    private final float[] level = new float[LAYERS], amp = new float[LAYERS], wavelength = new float[LAYERS],
            travel = new float[LAYERS], swellAmp = new float[LAYERS], swellPeriod = new float[LAYERS],
            phase = new float[LAYERS], fillAlpha = new float[LAYERS], crestAlpha = new float[LAYERS];

    private float t;          // flow time in ms at speed 1
    private long lastFrame;   // ns
    private boolean running;
    private float rise = 1f;  // 0 = all layers below the edge, 1 = in place
    private long riseStart;   // ns, 0 = no rise animation
    private static final long RISE_NS = 1_800_000_000L;

    public DuoWavesView(Context context, boolean riseOnStart) {
        super(context);
        accent = DuoTheme.accent(context);
        fill.setStyle(Paint.Style.FILL);
        crest.setStyle(Paint.Style.STROKE);
        crest.setStrokeJoin(Paint.Join.ROUND);
        if (riseOnStart) {
            rise = 0f;
            riseStart = -1;
        }
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        int tint07 = DuoTheme.mix(accent, DuoTheme.FLOOR, 0.07f);
        int tint20 = DuoTheme.mix(accent, DuoTheme.FLOOR, 0.20f);
        floor.setShader(new LinearGradient(0, 0, 0, h, new int[]{DuoTheme.FLOOR, tint07, tint20},
                new float[]{0f, 0.7f, 1f}, Shader.TileMode.CLAMP));

        float u = Math.max(w, h) / 1280f;  // designed at 1280 wide, grown with the window
        crest.setStrokeWidth(Math.max(1f, 1.5f * u));
        for (int i = 0; i < LAYERS; i++) {
            float depth = i / (float) (LAYERS - 1);
            level[i] = h * (0.25f + 0.53f * depth);
            amp[i] = (12 + 22 * depth) * u;
            wavelength[i] = (720 + 780 * depth) * u;
            travel[i] = 34000 - 18000 * depth;
            swellAmp[i] = (4 + 6 * depth) * u;
            swellPeriod[i] = 11000 + 2300 * i;
            phase[i] = i * 1.7f;
            fillAlpha[i] = 0.022f + 0.022f * depth;
            crestAlpha[i] = 0.07f + 0.13f * depth;

            float width = w + wavelength[i];
            float below = amp[i] + swellAmp[i] + 8 * u;
            float height = h - level[i] + amp[i] + below;
            Path c = new Path();
            float step = Math.max(6, 10 * u);
            int n = (int) Math.ceil(width / step);
            for (int k = 0; k <= n; k++) {
                float px = k * step;
                double a = px / wavelength[i] * 2 * Math.PI;
                float py = (float) (amp[i] * (1 - 0.72 * Math.sin(a + phase[i]) - 0.28 * Math.sin(2 * a + phase[i] * 2.3)));
                if (k == 0) {
                    c.moveTo(px, py);
                } else {
                    c.lineTo(px, py);
                }
            }
            Path b = new Path(c);
            b.lineTo(width, height);
            b.lineTo(0, height);
            b.close();
            crestPath[i] = c;
            bodyPath[i] = b;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        canvas.drawRect(0, 0, w, h, floor);
        if (crestPath[0] == null) {
            return;
        }
        for (int i = 0; i < LAYERS; i++) {
            float depth = i / (float) (LAYERS - 1);
            float x = -wavelength[i] + wavelength[i] * (((t / travel[i]) + i * 0.37f) % 1f);
            float riseK = Math.min(1f, Math.max(0f, rise * 1.6f - depth * 0.6f));
            float y = level[i] - amp[i]
                    + swellAmp[i] * (float) Math.sin(t / swellPeriod[i] * 2 * Math.PI + phase[i])
                    + (h - level[i] + amp[i] + swellAmp[i]) * (1 - easeOut(riseK));
            fill.setColor(DuoTheme.withAlpha(accent, fillAlpha[i]));
            crest.setColor(DuoTheme.withAlpha(accent, crestAlpha[i]));
            canvas.save();
            canvas.translate(x, y);
            canvas.drawPath(bodyPath[i], fill);
            canvas.drawPath(crestPath[i], crest);
            canvas.restore();
        }
    }

    private static float easeOut(float k) {
        return 1 - (1 - k) * (1 - k) * (1 - k);
    }

    // ---------------- animation: only while visible and in front ----------------

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!running) {
            return;
        }
        if (lastFrame != 0) {
            // Clamped: the first frame after a pause would report the whole time away.
            t += Math.min(frameTimeNanos - lastFrame, 100_000_000L) / 1_000_000f * SPEED;
        }
        lastFrame = frameTimeNanos;
        if (riseStart == -1) {
            riseStart = frameTimeNanos;
        }
        if (riseStart > 0 && rise < 1f) {
            rise = Math.min(1f, (frameTimeNanos - riseStart) / (float) RISE_NS);
        }
        invalidate();
        Choreographer.getInstance().postFrameCallback(this);
    }

    private void update() {
        boolean should = isAttachedToWindow() && isShown() && hasWindowFocus();
        if (should == running) {
            return;
        }
        running = should;
        lastFrame = 0;
        if (running) {
            Choreographer.getInstance().postFrameCallback(this);
        } else {
            Choreographer.getInstance().removeFrameCallback(this);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(this::update);
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
        super.onDetachedFromWindow();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        update();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        update();
    }
}
