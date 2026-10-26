package com.limelight.duo;

import android.app.Activity;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;

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

    private final Activity activity;
    private final FrameLayout root;
    private final View stream;
    private final View backgroundTouch;
    private final DuoInputPanel panel;
    private final ImageButton toggleButton;
    private final boolean desktopApp;
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
        this.desktopApp = isDesktopApp(activity.getIntent().getStringExtra(Game.EXTRA_APP_NAME));
        float density = activity.getResources().getDisplayMetrics().density;

        DuoBridge.setSink(sink);

        panel = new DuoInputPanel(activity, sink, null);
        panel.setVisibility(View.GONE);
        panel.setElevation(20 * density);
        root.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.BOTTOM));

        // Small button bottom-left, at Windows taskbar height.
        toggleButton = new ImageButton(activity);
        toggleButton.setImageResource(R.drawable.ic_duo_keyboard);
        toggleButton.setContentDescription("Tastatur und Trackpad");
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xB3000000);
        bg.setCornerRadius(8 * density);
        bg.setStroke(Math.max(1, Math.round(density)), DuoColors.BORDER);
        toggleButton.setBackground(bg);
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
        DuoBridge.closePanel();
        DuoBridge.setSink(null);
    }

    public boolean isSplit() {
        return split;
    }

    private void togglePanel() {
        if (DuoBridge.isPanelOpen()) {
            DuoBridge.closePanel();
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
            toggleButton.setVisibility(View.GONE);
            // The panel is built in, so the one on the other screen is not needed.
            DuoBridge.closePanel();
            LimeLog.info("Duo: split mode, stream height " + top + ", panel height " + (rh - bottom));
        } else {
            setFrame(stream, ViewGroup.LayoutParams.MATCH_PARENT, streamGravity);
            setFrame(backgroundTouch, ViewGroup.LayoutParams.MATCH_PARENT, backgroundGravity);
            panel.setVisibility(View.GONE);
            toggleButton.setVisibility(desktopApp ? View.VISIBLE : View.GONE);
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
