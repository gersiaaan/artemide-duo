package com.limelight.duo;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.HapticFeedbackConstants;
import android.view.View;

import androidx.preference.PreferenceManager;

/** Keyboard key vibration with an adjustable strength (Settings → Duo, 0 = off, default 50 %). */
public final class DuoHaptics {
    private DuoHaptics() {}

    public static final String KEY = "duo_kb_vibration";

    public static void key(View v) {
        Context c = v.getContext();
        int pct = PreferenceManager.getDefaultSharedPreferences(c).getInt(KEY, 50);
        if (pct <= 0) {
            return;
        }
        Vibrator vib = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
        if (vib == null || !vib.hasVibrator()) {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 26 && vib.hasAmplitudeControl()) {
                // Strength from the slider, long enough to be felt on every motor.
                int amp = Math.max(30, Math.min(255, Math.round(pct * 2.55f)));
                vib.vibrate(VibrationEffect.createOneShot(18 + pct / 5, amp));
            } else if (Build.VERSION.SDK_INT >= 29) {
                // No amplitude control (e.g. Surface Duo): pick a stronger or softer system click.
                int effect = pct < 35 ? VibrationEffect.EFFECT_TICK
                        : pct < 75 ? VibrationEffect.EFFECT_CLICK : VibrationEffect.EFFECT_HEAVY_CLICK;
                vib.vibrate(VibrationEffect.createPredefined(effect));
            } else if (Build.VERSION.SDK_INT >= 26) {
                vib.vibrate(VibrationEffect.createOneShot(10 + pct / 3, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vib.vibrate(10 + pct / 3);
            }
        } catch (RuntimeException e) {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        }
    }
}
