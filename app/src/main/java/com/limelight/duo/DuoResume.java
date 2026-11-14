package com.limelight.duo;

import android.app.Activity;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.SystemClock;

/**
 * Brings a stream back when you return to Artemide. Android ends the stream when Artemide is no
 * longer visible (another app on top, Claude swiped up over it); the stream's launch intent is
 * remembered then, and the overview reopens it straight away instead of showing itself.
 * Swiping Artemide away in the recent apps forgets it, so the stream really ends then.
 */
public final class DuoResume {
    private DuoResume() {}

    /** A remembered stream older than this is not resumed any more. */
    private static final long MAX_AGE_MS = 15 * 60 * 1000;

    private static Intent pending;
    private static long rememberedAt;

    /** Called when a running stream is left without being ended by the user or the host. */
    public static synchronized void remember(Intent streamIntent) {
        pending = new Intent(streamIntent);
        rememberedAt = SystemClock.elapsedRealtime();
    }

    /** Called when the user or the host ends the stream. */
    public static synchronized void clear() {
        pending = null;
    }

    /** Reopens the remembered stream from {@code overview}; returns whether it did. */
    public static synchronized boolean resumeFrom(Activity overview) {
        if (pending == null) {
            return false;
        }
        Intent intent = pending;
        pending = null;
        if (SystemClock.elapsedRealtime() - rememberedAt > MAX_AGE_MS) {
            return false;
        }
        try {
            overview.startActivity(intent);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Starts watching for Artemide being swiped away (call while a stream runs in the foreground). */
    public static void watchTask(Context context) {
        try {
            context.startService(new Intent(context, TaskWatcher.class));
        } catch (RuntimeException ignored) {
            // Not allowed in the background: then a swipe-away just doesn't clear the resume.
        }
    }

    /** Forgets the stream when the task is removed from the recent apps. */
    public static class TaskWatcher extends Service {
        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            return START_NOT_STICKY;
        }

        @Override
        public void onTaskRemoved(Intent rootIntent) {
            clear();
            stopSelf();
        }

        @Override
        public IBinder onBind(Intent intent) {
            return null;
        }
    }
}
