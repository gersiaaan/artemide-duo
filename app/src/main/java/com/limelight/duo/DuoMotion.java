package com.limelight.duo;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

/**
 * Small Apple-style motion helpers used across the Duo screens:
 * focus lift (tvOS), press squish, crossfades, slides and staggered appearance.
 */
public final class DuoMotion {
    private DuoMotion() {}

    public static final PathInterpolator EASE = new PathInterpolator(0.32f, 0.72f, 0f, 1f);
    private static final OvershootInterpolator SPRING = new OvershootInterpolator(1.6f);

    /** Focused: lifts a little (scale + shadow) like tvOS; unfocused: settles back. Keeps an existing listener. */
    public static void lift(View v, float scale) {
        final View.OnFocusChangeListener old = v.getOnFocusChangeListener();
        v.setOnFocusChangeListener((view, has) -> {
            float d = view.getResources().getDisplayMetrics().density;
            view.animate().scaleX(has ? scale : 1f).scaleY(has ? scale : 1f)
                    .translationZ(has ? 8 * d : 0f)
                    .setDuration(has ? 260 : 200).setInterpolator(has ? SPRING : EASE).start();
            if (old != null) {
                old.onFocusChange(view, has);
            }
        });
    }

    /** Press squish (96 %) with a springy release. Does not consume the touch. */
    @SuppressLint("ClickableViewAccessibility")
    public static void press(View v) {
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).setInterpolator(EASE).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    float s = view.isFocused() ? 1.04f : 1f;
                    view.animate().scaleX(s).scaleY(s).setDuration(260).setInterpolator(SPRING).start();
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    /** Press feedback for controller A / keyboard: quick squish and back. */
    public static void tap(View v) {
        if (v == null) {
            return;
        }
        float s = v.isFocused() ? 1.04f : 1f;
        v.animate().scaleX(s * 0.94f).scaleY(s * 0.94f).setDuration(80).setInterpolator(EASE)
                .withEndAction(() -> v.animate().scaleX(s).scaleY(s).setDuration(240).setInterpolator(SPRING).start())
                .start();
    }

    /** Lift + press for a button. */
    public static void button(View v) {
        lift(v, 1.04f);
        press(v);
    }

    /** Content swap: slides in from the side it came from (dir -1 left, 1 right, 0 fade only). */
    public static void slideIn(View v, int dir) {
        if (v == null) {
            return;
        }
        float d = v.getResources().getDisplayMetrics().density;
        v.animate().cancel();
        v.setAlpha(0f);
        v.setTranslationX(dir * 28 * d);
        v.animate().alpha(1f).translationX(0f).setDuration(320).setInterpolator(EASE).start();
    }

    /** Soft crossfade-in (after the content was replaced). */
    public static void fadeIn(View v, long ms) {
        if (v == null) {
            return;
        }
        v.animate().cancel();
        v.setAlpha(0f);
        v.animate().alpha(1f).setDuration(ms).setInterpolator(EASE).start();
    }

    /** Pop in from 92 % (dialogs, panels). */
    public static void popIn(View v) {
        if (v == null) {
            return;
        }
        v.setAlpha(0f);
        v.setScaleX(0.92f);
        v.setScaleY(0.92f);
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(340)
                .setInterpolator(new OvershootInterpolator(1.1f)).start();
    }

    /** Children rise in one after the other (lists). */
    public static void stagger(ViewGroup g, int max) {
        if (g == null) {
            return;
        }
        float d = g.getResources().getDisplayMetrics().density;
        int n = Math.min(g.getChildCount(), max);
        for (int i = 0; i < n; i++) {
            View c = g.getChildAt(i);
            c.setAlpha(0f);
            c.setTranslationY(12 * d);
            c.animate().alpha(1f).translationY(0f).setStartDelay(i * 28L).setDuration(300)
                    .setInterpolator(EASE).start();
        }
    }

    /** Counts a number up (sessions, …). */
    public static void countUp(TextView t, int to) {
        if (t == null || to <= 0) {
            if (t != null) {
                t.setText(String.valueOf(Math.max(0, to)));
            }
            return;
        }
        ValueAnimator a = ValueAnimator.ofInt(0, to);
        a.setDuration(Math.min(700, 200 + to * 40L));
        a.setInterpolator(EASE);
        a.addUpdateListener(x -> t.setText(String.valueOf((int) x.getAnimatedValue())));
        a.start();
    }

    /** Fine haptic tick when the controller moves focus (Settings → Duo, on by default). */
    public static void navTick(View v) {
        if (v != null && PreferenceManager.getDefaultSharedPreferences(v.getContext()).getBoolean("duo_nav_haptic", true)) {
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
    }

    /** Gentle pulse (status dots, loading text). */
    public static void pulse(View v) {
        if (v == null) {
            return;
        }
        ValueAnimator a = ValueAnimator.ofFloat(1f, 0.45f);
        a.setDuration(900);
        a.setRepeatMode(ValueAnimator.REVERSE);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.addUpdateListener(x -> v.setAlpha((float) x.getAnimatedValue()));
        v.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {}

            @Override
            public void onViewDetachedFromWindow(View view) {
                a.cancel();
            }
        });
        a.start();
    }
}
