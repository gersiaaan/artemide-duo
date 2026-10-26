package com.limelight.duo;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;
import androidx.preference.TwoStatePreference;

import com.limelight.R;

/**
 * Settings in the Artemide Duo look: tabs instead of one long list, card rows with an accent
 * focus ring, toggles instead of checkboxes and pill selectors for short lists.
 * All preferences, their listeners and the fragment's logic stay Artemide's own.
 */
public final class DuoSettingsDecor {
    private DuoSettingsDecor() {}

    public static final String[] TABS = {"Duo", "Video", "Decoder", "Audio", "Input", "Controller",
            "On-screen", "Overlay", "Interface", "Shortcuts", "General"};

    /** Which tab a top-level category belongs to (unknown ones go to General). */
    public static int tabFor(String key) {
        if (key == null) {
            return 10;
        }
        switch (key) {
            case "category_duo": return 0;
            case "category_video_settings": return 1;
            case "category_artemide_tweaks":
            case "category_advanced_settings": return 2;
            case "category_audio_settings": return 3;
            case "category_input_settings":
            case "category_virtual_trackpad_settings": return 4;
            case "category_gamepad_settings": return 5;
            case "category_onscreen_controls":
            case "category_special_key_layout": return 6;
            case "category_perf_monitor_settings": return 7;
            case "category_ui_settings": return 8;
            case "category_shortcuts": return 9;
            default: return 10;
        }
    }

    public static void decorate(PreferenceViewHolder holder, Preference pref) {
        View row = holder.itemView;
        if (row.getId() != R.id.duo_row) {
            return;
        }
        Context c = row.getContext();
        float d = c.getResources().getDisplayMetrics().density;
        int accent = DuoTheme.accent(c);
        row.setBackground(rowBackground(accent, d));
        View title = row.findViewById(android.R.id.title);
        if (title instanceof TextView) {
            ((TextView) title).setTypeface(DuoFonts.medium());
        }
        View sum = row.findViewById(android.R.id.summary);
        if (sum instanceof TextView) {
            ((TextView) sum).setTypeface(DuoFonts.regular());
        }

        LinearLayout pills = row.findViewById(R.id.duo_pills);
        View summary = row.findViewById(android.R.id.summary);
        pills.removeAllViews();
        pills.setVisibility(View.GONE);

        if (pref instanceof TwoStatePreference) {
            View cb = row.findViewById(android.R.id.checkbox);
            if (cb instanceof CheckBox) {
                ((CheckBox) cb).setButtonDrawable(toggle(accent, d));
                ((CheckBox) cb).setChecked(((TwoStatePreference) pref).isChecked());
            }
        }

        if (pref instanceof ListPreference) {
            ListPreference lp = (ListPreference) pref;
            CharSequence[] names = lp.getEntries();
            CharSequence[] values = lp.getEntryValues();
            if (names != null && values != null && names.length >= 2 && names.length <= 4
                    && names.length == values.length && fits(names)) {
                pills.setBackground(rounded(0xFF000000, DuoTheme.LINE, 9, d));
                String current = lp.getValue();
                for (int i = 0; i < names.length; i++) {
                    final String v = values[i].toString();
                    boolean on = v.equals(current);
                    TextView t = new TextView(c);
                    t.setText(names[i]);
                    t.setTextSize(11.5f);
                    t.setTypeface(DuoFonts.medium());
                    t.setTextColor(on ? accent : DuoTheme.TEXT);
                    t.setGravity(Gravity.CENTER);
                    t.setPadding(Math.round(10 * d), 0, Math.round(10 * d), 0);
                    StateListDrawable s = new StateListDrawable();
                    GradientDrawable focus = rounded(on ? DuoTheme.mix(accent, DuoTheme.CARD, 0.16f) : 0, 0, 7, d);
                    focus.setStroke(Math.round(1.5f * d), DuoTheme.focus(accent));
                    s.addState(new int[]{android.R.attr.state_focused}, focus);
                    s.addState(new int[]{}, rounded(on ? DuoTheme.mix(accent, DuoTheme.CARD, 0.16f) : 0, 0, 7, d));
                    t.setBackground(s);
                    t.setFocusable(true);
                    t.setEnabled(pref.isEnabled());
                    t.setOnClickListener(x -> {
                        if (!v.equals(lp.getValue()) && lp.callChangeListener(v)) {
                            lp.setValue(v);
                        }
                    });
                    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                            Math.round(28 * d));
                    if (i > 0) {
                        p.leftMargin = Math.round(2 * d);
                    }
                    pills.addView(t, p);
                }
                pills.setVisibility(View.VISIBLE);
                if (summary != null) {
                    summary.setVisibility(View.GONE);
                }
            }
        }
    }

    private static boolean fits(CharSequence[] names) {
        int total = 0;
        for (CharSequence n : names) {
            if (n.length() > 16) {
                return false;
            }
            total += n.length();
        }
        return total <= 36;
    }

    private static StateListDrawable rowBackground(int accent, float d) {
        StateListDrawable s = new StateListDrawable();
        GradientDrawable focused = rounded(DuoTheme.CARD, 0, 12, d);
        focused.setStroke(Math.round(1.5f * d), DuoTheme.focus(accent));
        s.addState(new int[]{android.R.attr.state_focused}, focused);
        s.addState(new int[]{android.R.attr.state_pressed}, rounded(DuoTheme.CARD_HIGH, DuoTheme.LINE, 12, d));
        s.addState(new int[]{}, rounded(DuoTheme.withAlpha(DuoTheme.CARD, 0.88f), DuoTheme.LINE, 12, d));
        return s;
    }

    /** iOS/StreamLight-like switch: 40 x 22 track with a 16 dp knob. */
    private static StateListDrawable toggle(int accent, float d) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_checked}, toggleState(true, accent, d));
        s.addState(new int[]{}, toggleState(false, accent, d));
        return s;
    }

    private static LayerDrawable toggleState(boolean on, int accent, float d) {
        int w = Math.round(40 * d), h = Math.round(22 * d), k = Math.round(16 * d), m = Math.round(3 * d);
        GradientDrawable track = rounded(on ? DuoTheme.mix(accent, DuoTheme.CARD, 0.35f) : 0xFF0A0E11,
                on ? accent : 0x29FFFFFF, 11, d);
        track.setSize(w, h);
        GradientDrawable knob = new GradientDrawable();
        knob.setShape(GradientDrawable.OVAL);
        knob.setColor(on ? accent : DuoTheme.TEXT3);
        knob.setSize(k, k);
        LayerDrawable l = new LayerDrawable(new android.graphics.drawable.Drawable[]{track, knob});
        int left = on ? w - k - m : m;
        l.setLayerInset(1, left, m, w - k - left, h - k - m);
        return l;
    }

    private static GradientDrawable rounded(int color, int stroke, float r, float d) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(r * d);
        if (stroke != 0) {
            g.setStroke(Math.max(1, Math.round(d)), stroke);
        }
        return g;
    }
}
