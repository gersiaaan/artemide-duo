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

    private static final long IN_MS = 460;
    private static final long OUT_MS = 300;

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
        // The Duo moves input focus to the screen you touch. So this panel stays focusable and hands
        // every controller event straight to the stream (see dispatchKeyEvent / dispatchGenericMotionEvent):
        // the controller always acts in the stream, whichever screen was touched last.
        hideSystemUi();

        panel = new DuoInputPanel(this, sink, this::hideByUser);
        panel.setAlpha(0f);
        setContentView(panel);

        // Slide in from the top once the panel has its size.
        panel.post(() -> {
            // Comes down from the stream screen above: slides in, grows from 94 % and lands softly
            // with a tiny overshoot (spring-like, iOS sheet).
            panel.setPivotX(panel.getWidth() / 2f);
            panel.setPivotY(0f);
            panel.setTranslationY(-panel.getHeight() * 0.6f);
            panel.setScaleX(0.94f);
            panel.setScaleY(0.94f);
            panel.animate()
                    .translationY(0f)
                    .scaleX(1f).scaleY(1f)
                    .alpha(1f)
                    .setDuration(IN_MS)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(0.6f))
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
        panel.setPivotX(panel.getWidth() / 2f);
        panel.setPivotY(0f);
        panel.animate()
                .translationY(-panel.getHeight() * 0.7f)
                .scaleX(0.92f).scaleY(0.92f)
                .alpha(0f)
                .setDuration(OUT_MS)
                // Back up into the stream screen: gentle start, quick finish.
                .setInterpolator(new PathInterpolator(0.4f, 0f, 0.7f, 0.2f))
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

    private static boolean fromPad(android.view.InputDevice dev, int source) {
        int s = dev != null ? dev.getSources() : source;
        return (s & android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD
                || (s & android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK;
    }

    /** Controller, hardware keyboard or mouse (anything physical) belongs to the stream. */
    private static boolean external(android.view.InputDevice dev, int source) {
        return fromPad(dev, source) || (dev != null && !dev.isVirtual()
                && ((source & android.view.InputDevice.SOURCE_KEYBOARD) == android.view.InputDevice.SOURCE_KEYBOARD
                || (source & android.view.InputDevice.SOURCE_MOUSE) == android.view.InputDevice.SOURCE_MOUSE
                || (source & android.view.InputDevice.SOURCE_MOUSE_RELATIVE) == android.view.InputDevice.SOURCE_MOUSE_RELATIVE));
    }

    /** If this screen ever has focus, the controller still goes to the stream (shortcuts included). */
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        Activity game = DuoBridge.getStreamActivity();
        if (game != null && !game.isFinishing() && external(event.getDevice(), event.getSource())) {
            return game.dispatchKeyEvent(event);
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(android.view.MotionEvent event) {
        Activity game = DuoBridge.getStreamActivity();
        if (game != null && !game.isFinishing() && external(event.getDevice(), event.getSource())) {
            return game.dispatchGenericMotionEvent(event);
        }
        return super.dispatchGenericMotionEvent(event);
    }

    /** Gboard needs a focused window; everything else works without focus. */
    void setTakesFocus(boolean focus) {
        // Always focusable now (controller input is forwarded to the stream).
    }
}
