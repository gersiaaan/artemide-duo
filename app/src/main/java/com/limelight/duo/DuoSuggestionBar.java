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
    private final View[] dividers = new View[2];
    private final String[] shown = {null, null, null};
    private int shownHighlight = -2;
    private boolean shownAny;
    private static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    private static final Typeface BOLD = Typeface.create("sans-serif", Typeface.BOLD);

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
                dividers[i - 1] = divider;
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
        autocorrect.addListener(this);
    }

    @Override
    public void onSuggestions(String[] texts, int highlight) {
        boolean any = false;
        for (String t : texts) {
            if (t != null && !t.isEmpty()) {
                any = true;
            }
        }
        // Dividers only when there is something to separate.
        if (any != shownAny || shownHighlight == -2) {
            shownAny = any;
            for (View d : dividers) {
                if (d != null) {
                    d.setVisibility(any ? VISIBLE : INVISIBLE);
                }
            }
        }
        // Only touch cells that really changed: keeps typing smooth (no relayout per key).
        boolean highlightChanged = highlight != shownHighlight;
        shownHighlight = highlight;
        for (int i = 0; i < 3; i++) {
            String s = texts[i] == null ? "" : texts[i];
            if (!highlightChanged && s.equals(shown[i])) {
                continue;
            }
            shown[i] = s;
            if (!s.contentEquals(cells[i].getText())) {
                cells[i].setText(s);
            }
            cells[i].setClickable(!s.isEmpty());
            boolean bold = i == highlight;
            Typeface tf = bold ? BOLD : REGULAR;
            if (cells[i].getTypeface() != tf) {
                cells[i].setTypeface(tf);
            }
            cells[i].setTextColor(bold ? 0xFFFFFFFF : (s.startsWith("„") ? DuoColors.MUTED : DuoColors.TEXT));
        }
    }
}
