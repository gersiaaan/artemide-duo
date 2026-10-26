package com.limelight.duo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Date;

/** Clock and battery of the Duo itself, centred in the panel's top bar. */
public class DuoStatusView extends LinearLayout {
    private final float density;
    private final TextView time;
    private final TextView percent;
    private final BatteryIcon icon;
    private final BoltIcon bolt;
    private boolean registered;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                updateBattery(intent);
            } else {
                updateTime();
            }
        }
    };

    public DuoStatusView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);

        time = new TextView(context);
        time.setTextColor(DuoColors.TEXT);
        time.setTextSize(13);
        time.setTypeface(DuoFonts.medium());
        addView(time, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        icon = new BatteryIcon(context);
        LayoutParams ip = new LayoutParams(dp(22), dp(12));
        ip.leftMargin = dp(10);
        addView(icon, ip);

        // Lightning bolt next to the battery while charging.
        bolt = new BoltIcon(context);
        LayoutParams bp = new LayoutParams(dp(7), dp(10));
        bp.leftMargin = dp(4);
        bolt.setVisibility(GONE);
        addView(bolt, bp);

        percent = new TextView(context);
        percent.setTextColor(DuoColors.MUTED);
        percent.setTextSize(13);
        percent.setTypeface(DuoFonts.medium());
        LayoutParams pp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        pp.leftMargin = dp(5);
        addView(percent, pp);
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        f.addAction(Intent.ACTION_BATTERY_CHANGED);
        // System broadcasts only; BATTERY_CHANGED is sticky, so the level shows right away.
        Intent sticky = getContext().registerReceiver(receiver, f);
        registered = true;
        updateTime();
        if (sticky != null) {
            updateBattery(sticky);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (registered) {
            try {
                getContext().unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) {
            }
            registered = false;
        }
        super.onDetachedFromWindow();
    }

    private void updateTime() {
        String pattern = DateFormat.is24HourFormat(getContext()) ? "HH:mm" : "h:mm";
        time.setText(DateFormat.format(pattern, new Date()));
        updateDescription();
    }

    private void updateBattery(Intent intent) {
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        if (level < 0 || scale <= 0) {
            return;
        }
        int pct = Math.round(level * 100f / scale);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        percent.setText(pct + " %");
        icon.set(pct, charging);
        bolt.setVisibility(charging ? VISIBLE : GONE);
        percent.setTextColor(charging ? DuoColors.HI_TEXT : DuoColors.MUTED);
        updateDescription();
    }

    private void updateDescription() {
        setContentDescription("Time " + time.getText() + ", battery " + percent.getText()
                + (bolt.getVisibility() == VISIBLE ? ", charging" : ""));
    }

    /** Small filled lightning bolt (charging). */
    private static class BoltIcon extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path = new android.graphics.Path();

        BoltIcon(Context c) {
            super(c);
            p.setStyle(Paint.Style.FILL);
            p.setColor(DuoColors.HI_TEXT);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            // 9 x 13 grid
            float u = Math.min(getWidth() / 9f, getHeight() / 13f);
            float ox = (getWidth() - 9 * u) / 2f;
            float oy = (getHeight() - 13 * u) / 2f;
            path.reset();
            path.moveTo(ox + 5.5f * u, oy);
            path.lineTo(ox, oy + 7.5f * u);
            path.lineTo(ox + 4f * u, oy + 7.5f * u);
            path.lineTo(ox + 3.5f * u, oy + 13f * u);
            path.lineTo(ox + 9f * u, oy + 5.5f * u);
            path.lineTo(ox + 5f * u, oy + 5.5f * u);
            path.close();
            canvas.drawPath(path, p);
        }
    }

    /** Outline battery with a fill for the level; the fill turns blue while charging. */
    private static class BatteryIcon extends View {
        private final float d;
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private int level = -1;
        private boolean charging;

        BatteryIcon(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(1.4f * d);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setColor(DuoColors.MUTED);
            fill.setStyle(Paint.Style.FILL);
        }

        void set(int level, boolean charging) {
            this.level = level;
            this.charging = charging;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            // 22 x 12 grid like the design.
            float u = getHeight() / 12f;
            float ox = (getWidth() - 22 * u) / 2f;
            r.set(ox + 0.7f * u, 0.7f * u, ox + 18.7f * u, 11.3f * u);
            canvas.drawRoundRect(r, 2.5f * u, 2.5f * u, stroke);
            canvas.drawLine(ox + 20.5f * u, 4.2f * u, ox + 20.5f * u, 7.8f * u, stroke);
            if (level >= 0) {
                float w = 14.4f * u * Math.max(0.05f, Math.min(1f, level / 100f));
                fill.setColor(charging ? DuoColors.HI_TEXT : (level <= 15 ? 0xFFE0645A : DuoColors.MUTED));
                r.set(ox + 2.5f * u, 2.5f * u, ox + 2.5f * u + w, 9.5f * u);
                canvas.drawRoundRect(r, 1f * u, 1f * u, fill);
            }
        }
    }
}
