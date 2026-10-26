package com.limelight.duo;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.util.Consumer;
import androidx.window.java.layout.WindowInfoTrackerCallbackAdapter;
import androidx.window.layout.DisplayFeature;
import androidx.window.layout.FoldingFeature;
import androidx.window.layout.WindowInfoTracker;
import androidx.window.layout.WindowLayoutInfo;

import com.limelight.Game;
import com.limelight.LimeLog;
import com.limelight.R;

/**
 * Surface Duo support:
 *  - spanned in laptop posture (hinge horizontal): stream on the upper screen,
 *    keyboard/trackpad panel on the lower screen
 *  - single screen: a small button (desktop apps only) opens the panel on the
 *    other screen without spanning
 */
public class DuoController {
    /** Surface Duo 1 hinge width in pixels, used if WindowManager reports nothing. */
    private static final int DUO1_HINGE_PX = 84;

    public static boolean isDuoDevice() {
        return "Microsoft".equalsIgnoreCase(Build.MANUFACTURER)
                && Build.MODEL != null
                && Build.MODEL.toLowerCase().contains("duo");
    }

    /** The toggle button is only for desktop-style apps, not for games. */
    private static boolean isDesktopApp(String appName) {
        if (appName == null) {
            return false;
        }
        String n = appName.toLowerCase();
        return n.contains("desktop") || n.contains("virtual display") || n.contains("remote");
    }

    /** Desktop / remote app: name, or what the user set under "Game settings & type". */
    private static boolean isDesktopLaunch(Activity a) {
        android.content.Intent in = a.getIntent();
        String pc = in.getStringExtra(Game.EXTRA_PC_UUID);
        int id = in.getIntExtra(Game.EXTRA_APP_ID, 0);
        String type = pc == null ? null : DuoGameSettings.typeOverride(a, pc, id);
        if (type != null) {
            return "app".equals(type);
        }
        return isDesktopApp(in.getStringExtra(Game.EXTRA_APP_NAME));
    }

    private final Activity activity;
    private final FrameLayout root;
    private final View stream;
    private final View backgroundTouch;
    private final DuoInputPanel panel;
    private final ImageButton toggleButton;
    private final boolean desktopApp;
    /** The panel opens by itself once per stream for desktop apps (not spanned). */
    private boolean autoOpened;
    private final int streamGravity;
    private final int backgroundGravity;

    private WindowInfoTrackerCallbackAdapter tracker;
    private final Consumer<WindowLayoutInfo> layoutListener = this::onWindowLayout;
    private boolean listening;

    private Rect hinge;
    private boolean split;
    private boolean applied;
    private int appliedTop = -1, appliedBottom = -1, appliedHeight = -1;

    public DuoController(Activity activity, FrameLayout root, View stream, View backgroundTouch, DuoInputSink sink) {
        this.activity = activity;
        this.root = root;
        this.stream = stream;
        this.backgroundTouch = backgroundTouch;
        this.streamGravity = gravityOf(stream);
        this.backgroundGravity = gravityOf(backgroundTouch);
        // Settings: "desktop" (default) = only apps like Desktop, "all" = also games, "off" = never.
        String buttonMode = androidx.preference.PreferenceManager.getDefaultSharedPreferences(activity)
                .getString(DuoBridge.KEY_STREAM_BUTTON, "desktop");
        this.desktopApp = "all".equals(buttonMode)
                || (!"off".equals(buttonMode) && isDesktopLaunch(activity));
        float density = activity.getResources().getDisplayMetrics().density;

        DuoBridge.setSink(sink);
        DuoGamepadMouse.init(activity, desktopApp, sink);
        DuoBridge.setPanelListener(() -> root.post(this::updateToggleButton));

        panel = new DuoInputPanel(activity, sink, null);
        panel.setVisibility(View.GONE);
        panel.setElevation(20 * density);
        root.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.BOTTOM));

        // Small button bottom-left, at Windows taskbar height: icon only, transparent,
        // with a soft dark shadow so it stays visible on light backgrounds.
        toggleButton = new ImageButton(activity);
        Drawable shadow = ContextCompat.getDrawable(activity, R.drawable.ic_duo_keyboard).mutate();
        shadow.setColorFilter(0xB0000000, PorterDuff.Mode.SRC_IN);
        Drawable icon = ContextCompat.getDrawable(activity, R.drawable.ic_duo_keyboard).mutate();
        LayerDrawable layered = new LayerDrawable(new Drawable[]{shadow, icon});
        int off = Math.max(1, Math.round(density));
        layered.setLayerInset(0, off, off, 0, 0);
        layered.setLayerInset(1, 0, 0, off, off);
        toggleButton.setImageDrawable(layered);
        toggleButton.setContentDescription("Keyboard and trackpad");
        toggleButton.setBackground(null);
        int p = Math.round(5 * density);
        toggleButton.setPadding(p, p, p, p);
        toggleButton.setFocusable(false);
        toggleButton.setElevation(20 * density);
        toggleButton.setVisibility(View.GONE);
        toggleButton.setOnClickListener(v -> togglePanel());
        int size = Math.round(34 * density);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(size, size, Gravity.BOTTOM | Gravity.START);
        blp.leftMargin = Math.round(4 * density);
        blp.bottomMargin = Math.round(2 * density);
        root.addView(toggleButton, blp);

        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) {
                root.post(this::apply);
            }
        });

        try {
            tracker = new WindowInfoTrackerCallbackAdapter(WindowInfoTracker.getOrCreate(activity));
        } catch (Throwable t) {
            LimeLog.warning("Duo: WindowManager not available: " + t);
            tracker = null;
        }
    }

    public void start() {
        if (tracker != null && !listening) {
            try {
                tracker.addWindowLayoutInfoListener(activity, ContextCompat.getMainExecutor(activity), layoutListener);
                listening = true;
            } catch (Throwable t) {
                LimeLog.warning("Duo: could not listen for hinge: " + t);
            }
        }
        root.post(this::apply);
    }

    public void stop() {
        if (tracker != null && listening) {
            try {
                tracker.removeWindowLayoutInfoListener(layoutListener);
            } catch (Throwable ignored) {
            }
            listening = false;
        }
    }

    /** Call from the stream's onDestroy. */
    public void destroy() {
        stop();
        DuoBridge.setPanelListener(null);
        DuoBridge.resetFocus();
        DuoBridge.closePanel();
        DuoBridge.setSink(null);
        DuoGamepadMouse.end();
    }

    /** The stream button is hidden while split or while the panel is open on the other screen. */
    private void updateToggleButton() {
        boolean show = desktopApp && applied && !split && !DuoBridge.isPanelOpen();
        toggleButton.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /** Speed dialog for the stream menu: trackpad and gamepad cursor (desktop mode), 1..10. */
    public static void showSpeedDialog(Activity activity) {
        float density = activity.getResources().getDisplayMetrics().density;
        int pad = Math.round(20 * density);

        // Same look as Artemide's stream menu: the app theme, not the activity's.
        final android.content.Context themed =
                new android.view.ContextThemeWrapper(activity, activity.getApplicationInfo().theme);

        LinearLayout box = new LinearLayout(themed);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, Math.round(8 * density), pad, Math.round(8 * density));

        speedRow(themed, box, "Trackpad", null, DuoBridge.getSpeed(activity),
                sp -> DuoBridge.setSpeed(activity, sp), density);
        speedRow(themed, box, "Gamepad cursor",
                "Desktop mode (D): small stick movement = fine, fully pushed = fast",
                DuoGamepadMouse.getSpeed(activity), sp -> DuoGamepadMouse.setSpeed(activity, sp), density);

        AlertDialog dialog = new AlertDialog.Builder(themed)
                .setTitle("Speed")
                .setView(box)
                .setPositiveButton(android.R.string.ok, null)
                .show();
        DuoDialogs.style(dialog);
    }

    private interface IntSink {
        void set(int v);
    }

    private static void speedRow(android.content.Context themed, LinearLayout box, String title, String hint,
                                 int speed, IntSink onChange, float density) {
        TextView t = new TextView(themed);
        t.setText(title);
        t.setTextSize(14);
        t.setTypeface(DuoFonts.semibold());
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.topMargin = Math.round((box.getChildCount() == 0 ? 6 : 18) * density);
        box.addView(t, tp);
        if (hint != null) {
            TextView h = new TextView(themed);
            h.setText(hint);
            h.setTextSize(11.5f);
            h.setAlpha(0.6f);
            box.addView(h);
        }
        LinearLayout row = new LinearLayout(themed);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final TextView value = new TextView(themed);
        value.setTextSize(16);
        value.setGravity(Gravity.END);
        SeekBar seek = new SeekBar(themed);
        DuoDialogs.styleSlider(seek);
        seek.setMax(9);
        seek.setProgress(speed - 1);
        value.setText(String.valueOf(speed));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                value.setText(String.valueOf(progress + 1));
                if (fromUser) {
                    onChange.set(progress + 1);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {}

            @Override
            public void onStopTrackingTouch(SeekBar s) {}
        });
        row.addView(seek, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(value, new LinearLayout.LayoutParams(Math.round(32 * density), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.topMargin = Math.round(6 * density);
        box.addView(row, rp);
    }

    /** The stream is connected: desktop apps get the panel on the other screen right away. */
    public void onStreamConnected() {
        if (!desktopApp || autoOpened || split) {
            return;
        }
        autoOpened = true;
        root.postDelayed(() -> {
            if (!split && !DuoBridge.isPanelOpen() && !activity.isFinishing()) {
                DuoPanelActivity.open(activity);
            }
        }, 400);
    }

    public boolean isSplit() {
        return split;
    }

    public void togglePanel() {
        if (DuoBridge.isPanelOpen()) {
            DuoBridge.hidePanelByUser();
        } else {
            DuoPanelActivity.open(activity);
        }
    }

    private void onWindowLayout(WindowLayoutInfo info) {
        Rect found = null;
        for (DisplayFeature feature : info.getDisplayFeatures()) {
            if (feature instanceof FoldingFeature
                    && ((FoldingFeature) feature).getOrientation() == FoldingFeature.Orientation.HORIZONTAL) {
                found = feature.getBounds();
            }
        }
        hinge = found;
        apply();
    }

    private void apply() {
        int rw = root.getWidth();
        int rh = root.getHeight();
        if (rw == 0 || rh == 0) {
            return;
        }

        int top = -1, bottom = -1;
        if (hinge != null) {
            int[] loc = new int[2];
            root.getLocationInWindow(loc);
            top = hinge.top - loc[1];
            bottom = hinge.bottom - loc[1];
        } else if (rh > rw * 1.45f) {
            // Spanned in laptop posture but no hinge reported: assume Duo 1 geometry.
            top = (rh - DUO1_HINGE_PX) / 2;
            bottom = top + DUO1_HINGE_PX;
        }

        boolean shouldSplit = top > rh * 0.2f && bottom < rh * 0.95f;
        if (applied && shouldSplit == split && top == appliedTop && bottom == appliedBottom && rh == appliedHeight) {
            return;
        }
        applied = true;
        split = shouldSplit;
        appliedTop = top;
        appliedBottom = bottom;
        appliedHeight = rh;

        if (shouldSplit) {
            setFrame(stream, top, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            setFrame(backgroundTouch, top, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            setFrame(panel, rh - bottom, Gravity.BOTTOM);
            panel.setVisibility(View.VISIBLE);
            // The panel is built in, so the one on the other screen is not needed.
            DuoBridge.closePanel();
            updateToggleButton();
            LimeLog.info("Duo: split mode, stream height " + top + ", panel height " + (rh - bottom));
        } else {
            setFrame(stream, ViewGroup.LayoutParams.MATCH_PARENT, streamGravity);
            setFrame(backgroundTouch, ViewGroup.LayoutParams.MATCH_PARENT, backgroundGravity);
            panel.setVisibility(View.GONE);
            updateToggleButton();
            LimeLog.info("Duo: single screen mode, toggle button " + (desktopApp ? "shown" : "hidden"));
        }
    }

    private static int gravityOf(View v) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        return lp instanceof FrameLayout.LayoutParams ? ((FrameLayout.LayoutParams) lp).gravity : Gravity.CENTER;
    }

    private static void setFrame(View v, int height, int gravity) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.height = height;
        lp.gravity = gravity;
        v.setLayoutParams(lp);
    }
}
