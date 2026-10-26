package com.limelight.duo;

import android.app.Activity;
import android.view.KeyEvent;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.ListPreference;

/**
 * Rebind a controller shortcut: the dialog waits for a button on the controller
 * (LB + RB are always part of the combo) and saves it.
 */
public final class DuoRebindDialog {
    private DuoRebindDialog() {}

    static String nameFor(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return "A";
            case KeyEvent.KEYCODE_BUTTON_B: return "B";
            case KeyEvent.KEYCODE_BUTTON_X: return "X";
            case KeyEvent.KEYCODE_BUTTON_Y: return "Y";
            case KeyEvent.KEYCODE_BUTTON_START: return "START";
            case KeyEvent.KEYCODE_BUTTON_SELECT: return "SELECT";
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return "THUMBL";
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return "THUMBR";
            default: return null;
        }
    }

    public static void show(Activity a, ListPreference pref, String defaultValue) {
        AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle(pref.getTitle())
                .setMessage("Press a button on the controller …\n\nThe shortcut is LB + RB + that button.\n"
                        + "(A, B, X, Y, Menu, View, L3 or R3)")
                .setNegativeButton("Turn off", (d, w) -> set(pref, "OFF"))
                .setNeutralButton("Reset", (d, w) -> set(pref, defaultValue))
                .setPositiveButton(android.R.string.cancel, null)
                .create();
        dialog.setOnKeyListener((d, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() > 0) {
                return code != KeyEvent.KEYCODE_BACK && nameFor(code) != null;
            }
            String n = nameFor(code);
            if (n == null) {
                // LB / RB themselves and anything else: keep waiting (Back / B on the dialog closes it).
                return code == KeyEvent.KEYCODE_BUTTON_L1 || code == KeyEvent.KEYCODE_BUTTON_R1;
            }
            set(pref, n);
            d.dismiss();
            return true;
        });
        dialog.show();
    }

    private static void set(ListPreference pref, String v) {
        if (pref.callChangeListener(v)) {
            pref.setValue(v);
        }
    }
}
