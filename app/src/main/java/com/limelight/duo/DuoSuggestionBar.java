package com.limelight.duo;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Gboard-style suggestion strip: „typed“ | best (bold) | alternative. */
@SuppressLint("ViewConstructor")
public class DuoSuggestionBar extends LinearLayout implements DuoAutocorrect.Listener {
    private final TextView[] cells = new TextView[3];

    public DuoSuggestionBar(Context context, final DuoAutocorrect autocorrect) {
        super(context);
        float density = context.getResources().getDisplayMetrics().density;
        setOrientation(HORIZONTAL);
        setBackgroundColor(DuoColors.BG);

        for (int i = 0; i < 3; i++) {
            if (i > 0) {
                View divider = new View(context);
                divider.setBackgroundColor(DuoColors.BORDER);
                LayoutParams dl = new LayoutParams(Math.max(1, Math.round(density)), LayoutParams.MATCH_PARENT);
                dl.topMargin = dl.bottomMargin = Math.round(8 * density);
                addView(divider, dl);
            }
            final int index = i;
            TextView t = new TextView(context);
            t.setGravity(Gravity.CENTER);
            t.setTextSize(15);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setPadding(Math.round(6 * density), 0, Math.round(6 * density), 0);
            t.setFocusable(false);
            t.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                autocorrect.pick(index);
            });
            cells[i] = t;
            addView(t, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
        }
        autocorrect.setListener(this);
    }

    @Override
    public void onSuggestions(String[] texts, int highlight) {
        for (int i = 0; i < 3; i++) {
            String s = texts[i] == null ? "" : texts[i];
            cells[i].setText(s);
            cells[i].setClickable(!s.isEmpty());
            boolean bold = i == highlight;
            cells[i].setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
            cells[i].setTextColor(bold ? 0xFFFFFFFF : (s.startsWith("„") ? DuoColors.MUTED : DuoColors.TEXT));
        }
    }
}
