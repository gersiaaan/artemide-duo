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
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            return;
        }
        if (Build.VERSION.SDK_INT >= 26 && vib.hasAmplitudeControl()) {
            int amp = Math.max(1, Math.min(255, Math.round(pct * 2.55f)));
            vib.vibrate(VibrationEffect.createOneShot(12, amp));
        } else if (Build.VERSION.SDK_INT >= 26) {
            // No amplitude control: shorter buzz = softer.
            vib.vibrate(VibrationEffect.createOneShot(Math.max(4, Math.round(pct * 0.25f)), VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vib.vibrate(Math.max(4, Math.round(pct * 0.25f)));
        }
    }
}
