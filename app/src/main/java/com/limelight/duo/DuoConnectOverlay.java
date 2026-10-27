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
    private ObjectAnimator pulse;
    private ValueAnimator barAnim;
    private boolean gone;

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
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(22 * d);
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(cw * 0.95f);
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
                new int[]{DuoTheme.withAlpha(accent, 0f), accent, DuoTheme.withAlpha(accent, 0f)});
        bar.setBackground(bb);
        track.addView(bar, new FrameLayout.LayoutParams(dp(60), -1));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(dp(160), dp(3));
        tp.topMargin = dp(16);
        col.addView(track, tp);
    }

    private int dp(float v) {
        return Math.round(v * d);
    }

    private void enter() {
        coverBox.setScaleX(0.6f);
        coverBox.setScaleY(0.6f);
        coverBox.setAlpha(0f);
        coverBox.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(620)
                .setInterpolator(new OvershootInterpolator(1.4f)).start();
        glow.setAlpha(0f);
        pulse = ObjectAnimator.ofFloat(glow, View.ALPHA, 0.35f, 1f);
        pulse.setDuration(1100);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setStartDelay(300);
        pulse.start();
        barAnim = ValueAnimator.ofFloat(-1f, 2.7f);
        barAnim.setDuration(1300);
        barAnim.setRepeatCount(ValueAnimator.INFINITE);
        barAnim.addUpdateListener(v -> bar.setTranslationX((float) v.getAnimatedValue() * dp(60)));
        barAnim.start();
    }

    public void setStage(String s) {
        if (!gone && s != null) {
            stage.setText(s);
        }
    }

    /** connected = zoom through into the stream; otherwise just fade (an error dialog follows). */
    public void dismiss(boolean connected) {
        if (gone) {
            return;
        }
        gone = true;
        if (pulse != null) {
            pulse.cancel();
        }
        if (barAnim != null) {
            barAnim.cancel();
        }
        PathInterpolator ease = new PathInterpolator(0.32f, 0.72f, 0f, 1f);
        if (connected) {
            coverBox.animate().scaleX(7f).scaleY(7f).alpha(0f).setDuration(520)
                    .setInterpolator(new PathInterpolator(0.55f, 0f, 0.8f, 0.4f)).start();
            glow.animate().scaleX(4f).scaleY(4f).alpha(0f).setDuration(420).start();
        }
        animate().alpha(0f).setStartDelay(connected ? 160 : 0).setDuration(connected ? 420 : 220)
                .setInterpolator(ease)
                .withEndAction(() -> {
                    if (getParent() instanceof ViewGroup) {
                        ((ViewGroup) getParent()).removeView(this);
                    }
                })
                .start();
        setClickable(false);
    }
}
