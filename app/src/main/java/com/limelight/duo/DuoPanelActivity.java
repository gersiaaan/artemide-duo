package com.limelight.duo;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;

/**
 * Keyboard/trackpad panel shown on the other Surface Duo screen,
 * next to the stream, without spanning the app.
 * Slides in from the top (the hinge / stream side) and slides back up when hidden.
 */
public class DuoPanelActivity extends Activity {

    private static final long IN_MS = 280;
    private static final long OUT_MS = 200;

    private View panel;
    private boolean finishing;
    private boolean restoreOnFinish;

    /** Opens the panel on the adjacent screen. */
    public static void open(Activity from) {
        DuoPreviousApp.remember(from);
        Intent intent = new Intent(from, DuoPanelActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
        }
        from.startActivity(intent);
        // Our own slide animation replaces the system one.
        from.overridePendingTransition(0, 0);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        overridePendingTransition(0, 0);

        DuoInputSink sink = DuoBridge.getSink();
        if (sink == null) {
            // No stream running
            super.finish();
            return;
        }
        DuoBridge.setPanelActivity(this);

        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();

        panel = new DuoInputPanel(this, sink, this::hideByUser);
        panel.setAlpha(0f);
        setContentView(panel);

        // Slide in from the top once the panel has its size.
        panel.post(() -> {
            panel.setTranslationY(-panel.getHeight());
            panel.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(IN_MS)
                    // iOS-like: fast start, long soft landing
                    .setInterpolator(new PathInterpolator(0.32f, 0.72f, 0f, 1f))
                    .start();
        });
    }

    /**
     * Hidden by the user (button, swipe, stream button, menu):
     * the app that was on this screen before comes back.
     */
    void hideByUser() {
        restoreOnFinish = true;
        finish();
    }

    @Override
    public void finish() {
        if (finishing) {
            return;
        }
        finishing = true;
        // Tell the stream right away (not after the slide-out), so its keyboard button comes back.
        DuoBridge.clearPanelActivity(this);
        if (!restoreOnFinish) {
            DuoPreviousApp.forget();
        }
        if (panel == null || panel.getHeight() == 0) {
            super.finish();
            overridePendingTransition(0, 0);
            if (restoreOnFinish) {
                DuoPreviousApp.restoreLater();
            }
            return;
        }
        // Slide back up towards the stream, then close.
        panel.animate()
                .translationY(-panel.getHeight())
                .alpha(0f)
                .setDuration(OUT_MS)
                .setInterpolator(new PathInterpolator(0.4f, 0f, 0.9f, 0.6f))
                .withEndAction(() -> {
                    DuoPanelActivity.super.finish();
                    overridePendingTransition(0, 0);
                    if (restoreOnFinish) {
                        DuoPreviousApp.restoreLater();
                    }
                })
                .start();
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        DuoBridge.reportFocus(false, hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
    }

    @Override
    protected void onDestroy() {
        DuoBridge.clearPanelActivity(this);
        DuoBridge.reportFocus(false, false);
        super.onDestroy();
    }
}
