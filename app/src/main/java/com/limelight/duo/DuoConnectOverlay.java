package com.limelight.duo;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Stream start screen: the app's cover pops in over the waves with a pulsing accent glow,
 * "Connecting to <PC>" and the current stage. When the stream is up the cover zooms through
 * the screen and the overlay dissolves into the video.
 */
public final class DuoConnectOverlay extends FrameLayout {
    private final float d;
    private final int accent;
    private final FrameLayout coverBox;
    private final View glow;
    private final TextView stage;
    private final View bar;
    private final TextView percent;
    private final int trackW;
    private ObjectAnimator pulse;
    private ValueAnimator barAnim;
    private boolean gone;
    // Progress 0..1: shown value glides towards the target; a slow creep keeps it alive
    // while a step takes long (app launch on the PC).
    private float shown, target, ceiling;
    private int stages;
    private static final int STAGE_COUNT = 11; // moonlight-common-c connection stages

    public static DuoConnectOverlay show(Activity a, String appName, String pcName, String pcUuid, int appId) {
        ViewGroup root = a.findViewById(android.R.id.content);
        DuoConnectOverlay o = new DuoConnectOverlay(a, appName, pcName, pcUuid, appId);
        root.addView(o, new LayoutParams(-1, -1));
        o.enter();
        return o;
    }

    private DuoConnectOverlay(Activity a, String appName, String pcName, String pcUuid, int appId) {
        super(a);
        d = a.getResources().getDisplayMetrics().density * DuoTheme.uiScale(a);
        accent = DuoTheme.accent(a);
        setBackgroundColor(DuoTheme.FLOOR);
        setClickable(true);
        // Never takes key focus: controller shortcuts keep working while connecting.
        setFocusable(false);
        setElevation(100 * d);
        addView(new DuoWavesView(a, true), new LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        addView(col, new LayoutParams(-2, -2, Gravity.CENTER));

        FrameLayout stack = new FrameLayout(a);
        int cw = dp(118), ch = Math.round(cw * 1.41f);
        glow = new View(a);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius((cw + dp(70)) / 2f);
        g.setColors(new int[]{DuoTheme.withAlpha(accent, 0.55f), DuoTheme.withAlpha(accent, 0f)});
        glow.setBackground(g);
        stack.addView(glow, new FrameLayout.LayoutParams(cw + dp(70), ch + dp(70), Gravity.CENTER));

        coverBox = new FrameLayout(a);
        coverBox.setBackground(DuoCovers.placeholderBackground(accent, 14 * d));
        coverBox.setClipToOutline(true);
        coverBox.setElevation(12 * d);
        TextView ph = new TextView(a);
        ph.setText(appName == null ? "" : appName.toUpperCase());
        ph.setTextColor(DuoTheme.TEXT);
        ph.setTextSize(13);
        ph.setTypeface(DuoFonts.bold());
        ph.setGravity(Gravity.CENTER);
        ph.setPadding(dp(8), 0, dp(8), 0);
        coverBox.addView(ph, new FrameLayout.LayoutParams(-1, -1));
        ImageView iv = new ImageView(a);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        coverBox.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        if (pcUuid != null) {
            DuoCovers.into(a, pcUuid, appId, iv, ph, cw);
        }
        stack.addView(coverBox, new FrameLayout.LayoutParams(cw, ch, Gravity.CENTER));
        // The cover zooms far beyond its box when the stream starts: never clip it.
        stack.setClipChildren(false);
        stack.setClipToPadding(false);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        setClipChildren(false);
        col.addView(stack, new LinearLayout.LayoutParams(cw + dp(70), ch + dp(70)));

        TextView name = new TextView(a);
        name.setText(appName == null ? "" : appName);
        name.setTextColor(DuoTheme.TEXT);
        name.setTextSize(20);
        name.setTypeface(DuoFonts.semibold());
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, -2);
        np.topMargin = -dp(14);
        col.addView(name, np);

        stage = new TextView(a);
        stage.setText("Connecting to " + (pcName == null ? "your PC" : pcName) + " …");
        stage.setTextColor(DuoTheme.TEXT2);
        stage.setTextSize(12.5f);
        stage.setTypeface(DuoFonts.regular());
        stage.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.topMargin = dp(6);
        col.addView(stage, sp);

        FrameLayout track = new FrameLayout(a);
        GradientDrawable tb = new GradientDrawable();
        tb.setCornerRadius(2 * d);
        tb.setColor(0x1FFFFFFF);
        track.setBackground(tb);
        track.setClipToOutline(true);
        bar = new View(a);
        GradientDrawable bb = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{DuoTheme.withAlpha(accent, 0.55f), accent});
        bb.setCornerRadius(2 * d);
        bar.setBackground(bb);
        trackW = dp(200);
        bar.setPivotX(0f);
        bar.setScaleX(0f);
        track.addView(bar, new FrameLayout.LayoutParams(trackW, -1));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(trackW, dp(3));
        tp.topMargin = dp(18);
        col.addView(track, tp);

        percent = new TextView(a);
        percent.setText("0 %");
        percent.setTextColor(DuoTheme.TEXT2);
        percent.setTextSize(10.5f);
        percent.setTypeface(DuoFonts.medium());
        percent.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-2, -2);
        pp.topMargin = dp(8);
        col.addView(percent, pp);
    }

    private int dp(float v) {
        return Math.round(v * d);
    }

    private void enter() {
        // Calm entrance: the cover rises softly, the glow breathes slowly.
        coverBox.setScaleX(0.86f);
        coverBox.setScaleY(0.86f);
        coverBox.setAlpha(0f);
        coverBox.setTranslationY(dp(10));
        coverBox.animate().scaleX(1f).scaleY(1f).alpha(1f).translationY(0f).setDuration(900)
                .setInterpolator(new PathInterpolator(0.22f, 1f, 0.36f, 1f)).start();
        glow.setAlpha(0f);
        pulse = ObjectAnimator.ofFloat(glow, View.ALPHA, 0.45f, 0.85f);
        pulse.setDuration(2200);
        pulse.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setStartDelay(500);
        pulse.start();
        // Starting the app on the PC: up to 30 %.
        target = 0.03f;
        ceiling = 0.30f;
        barAnim = ValueAnimator.ofFloat(0f, 1f);
        barAnim.setDuration(1000);
        barAnim.setRepeatCount(ValueAnimator.INFINITE);
        barAnim.addUpdateListener(v -> tick());
        barAnim.start();
    }

    /** Called every frame: creep towards the ceiling, glide the bar towards the target. */
    private void tick() {
        // Creep: a little closer to the current step's ceiling, slower and slower.
        target += (ceiling - target) * 0.004f;
        shown += (target - shown) * 0.08f;
        bar.setScaleX(Math.max(0f, Math.min(1f, shown)));
        percent.setText(Math.max(1, Math.min(100, Math.round(shown * 100))) + " %");
    }

    /** One connection stage started (11 in total, after the app launch). */
    public void stageStarted() {
        if (gone) {
            return;
        }
        stages = Math.min(STAGE_COUNT, stages + 1);
        target = Math.max(target, 0.30f + 0.65f * (stages - 1) / STAGE_COUNT);
        ceiling = 0.30f + 0.65f * stages / STAGE_COUNT;
    }

    public void setStage(String s) {
        if (!gone && s != null && !s.contentEquals(stage.getText())) {
            stage.animate().cancel();
            stage.animate().alpha(0.35f).setDuration(140).withEndAction(() -> {
                stage.setText(s);
                stage.animate().alpha(1f).setDuration(220).start();
            }).start();
        }
    }

    /** connected = fill to 100 %, then the cover glides towards you and dissolves into the stream. */
    public void dismiss(boolean connected) {
        if (gone) {
            return;
        }
        gone = true;
        setClickable(false);
        if (!connected) {
            stopAnims();
            animate().alpha(0f).setDuration(320).setInterpolator(DuoMotion.EASE)
                    .withEndAction(this::remove).start();
            return;
        }
        target = 1f;
        ceiling = 1f;
        ValueAnimator fill = ValueAnimator.ofFloat(shown, 1f);
        fill.setDuration(320);
        fill.setInterpolator(DuoMotion.EASE);
        fill.addUpdateListener(v -> {
            shown = (float) v.getAnimatedValue();
            bar.setScaleX(shown);
            percent.setText(Math.round(shown * 100) + " %");
        });
        if (barAnim != null) {
            barAnim.cancel();
        }
        fill.start();
        postDelayed(() -> {
            stopAnims();
            // Relaxed zoom: grows to 1.6× (never past the screen edges) while fading.
            PathInterpolator ease = new PathInterpolator(0.32f, 0.72f, 0f, 1f);
            coverBox.animate().scaleX(1.6f).scaleY(1.6f).alpha(0f).setDuration(900)
                    .setInterpolator(ease).start();
            glow.animate().scaleX(1.8f).scaleY(1.8f).alpha(0f).setDuration(700).setInterpolator(ease).start();
            animate().alpha(0f).setStartDelay(260).setDuration(760).setInterpolator(ease)
                    .withEndAction(this::remove).start();
        }, 360);
    }

    private void stopAnims() {
        if (pulse != null) {
            pulse.cancel();
        }
        if (barAnim != null) {
            barAnim.cancel();
        }
    }

    private void remove() {
        if (getParent() instanceof ViewGroup) {
            ((ViewGroup) getParent()).removeView(this);
        }
    }
}
