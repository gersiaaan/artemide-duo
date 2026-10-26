package com.limelight.duo;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.core.content.ContextCompat;
import androidx.core.util.Consumer;
import androidx.window.java.layout.WindowInfoTrackerCallbackAdapter;
import androidx.window.layout.DisplayFeature;
import androidx.window.layout.FoldingFeature;
import androidx.window.layout.WindowInfoTracker;
import androidx.window.layout.WindowLayoutInfo;

import com.limelight.LimeLog;

/**
 * Surface Duo split mode: when the app spans both screens in laptop posture
 * (hinge horizontal), the stream goes on the upper screen and the
 * keyboard/trackpad panel on the lower screen.
 */
public class DuoController {
    /** Surface Duo 1 hinge width in pixels, used if WindowManager reports nothing. */
    private static final int DUO1_HINGE_PX = 84;

    public static boolean isDuoDevice() {
        return "Microsoft".equalsIgnoreCase(Build.MANUFACTURER)
                && Build.MODEL != null
                && Build.MODEL.toLowerCase().contains("duo");
    }

    private final Activity activity;
    private final FrameLayout root;
    private final View stream;
    private final View backgroundTouch;
    private final DuoInputPanel panel;
    private final int streamGravity;
    private final int backgroundGravity;

    private WindowInfoTrackerCallbackAdapter tracker;
    private final Consumer<WindowLayoutInfo> layoutListener = this::onWindowLayout;
    private boolean listening;

    private Rect hinge;
    private boolean split;
    private int appliedTop = -1, appliedBottom = -1, appliedHeight = -1;

    public DuoController(Activity activity, FrameLayout root, View stream, View backgroundTouch, DuoInputSink sink) {
        this.activity = activity;
        this.root = root;
        this.stream = stream;
        this.backgroundTouch = backgroundTouch;
        this.streamGravity = gravityOf(stream);
        this.backgroundGravity = gravityOf(backgroundTouch);

        panel = new DuoInputPanel(activity, sink);
        panel.setVisibility(View.GONE);
        panel.setElevation(20 * activity.getResources().getDisplayMetrics().density);
        root.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.BOTTOM));

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

    public boolean isSplit() {
        return split;
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
        } else if (isDuoDevice() && rh > rw * 1.45f) {
            // Spanned in laptop posture but no hinge reported: assume Duo 1 geometry.
            top = (rh - DUO1_HINGE_PX) / 2;
            bottom = top + DUO1_HINGE_PX;
        }

        boolean shouldSplit = top > rh * 0.2f && bottom < rh * 0.95f;
        if (shouldSplit == split && top == appliedTop && bottom == appliedBottom && rh == appliedHeight) {
            return;
        }
        split = shouldSplit;
        appliedTop = top;
        appliedBottom = bottom;
        appliedHeight = rh;

        if (shouldSplit) {
            setFrame(stream, top, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            setFrame(backgroundTouch, top, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            setFrame(panel, rh - bottom, Gravity.BOTTOM);
            panel.setVisibility(View.VISIBLE);
            LimeLog.info("Duo: split mode, stream height " + top + ", panel height " + (rh - bottom));
        } else {
            setFrame(stream, ViewGroup.LayoutParams.MATCH_PARENT, streamGravity);
            setFrame(backgroundTouch, ViewGroup.LayoutParams.MATCH_PARENT, backgroundGravity);
            panel.setVisibility(View.GONE);
            LimeLog.info("Duo: single screen mode");
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
