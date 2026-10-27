package com.limelight.duo;

import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.view.Window;
import android.widget.Button;
import android.widget.SeekBar;

/** Duo look for dialogs: black, slightly see-through, rounded corners, thin dark border. */
public final class DuoDialogs {
    private DuoDialogs() {}

    /** Slider and accent of the Trackpad-Tempo dialog (darker blue). */
    public static final int SLIDER = 0xFF34508A;

    /** Call right after show(). */
    public static void style(AlertDialog dialog) {
        if (dialog == null) {
            return;
        }
        Window w = dialog.getWindow();
        if (w == null) {
            return;
        }
        float d = w.getContext().getResources().getDisplayMetrics().density;
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE0000000); // ~88 % black
        bg.setCornerRadius(22 * d);
        bg.setStroke(Math.max(1, Math.round(d)), DuoColors.BORDER);
        w.setBackgroundDrawable(bg);
        w.setDimAmount(0.35f);
        // Highlight / ripple of list entries in the user's accent colour (never Material purple).
        int accent = DuoTheme.accent(w.getContext());
        if (dialog.getListView() != null) {
            GradientDrawable sel = new GradientDrawable();
            sel.setColor(DuoTheme.withAlpha(accent, 0.22f));
            sel.setCornerRadius(12 * d);
            android.graphics.drawable.StateListDrawable st = new android.graphics.drawable.StateListDrawable();
            st.addState(new int[]{android.R.attr.state_pressed}, sel);
            st.addState(new int[]{android.R.attr.state_focused}, sel);
            st.addState(new int[]{android.R.attr.state_selected}, sel);
            dialog.getListView().setSelector(new android.graphics.drawable.RippleDrawable(
                    ColorStateList.valueOf(DuoTheme.withAlpha(accent, 0.3f)), st, null));
        }
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            Button b = dialog.getButton(which);
            if (b != null) {
                b.setTextColor(DuoColors.HI_TEXT);
            }
        }
    }

    public static void styleSlider(SeekBar seek) {
        ColorStateList c = ColorStateList.valueOf(SLIDER);
        seek.setProgressTintList(c);
        seek.setThumbTintList(c);
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(DuoColors.BORDER));
    }
}
