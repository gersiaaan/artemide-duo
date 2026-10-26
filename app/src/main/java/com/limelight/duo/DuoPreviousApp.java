package com.limelight.duo;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;
import android.view.ContextThemeWrapper;

import androidx.preference.PreferenceManager;

import com.limelight.LimeLog;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Remembers which app was showing on the other Duo screen before the panel covered it
 * (e.g. YouTube, which then goes picture-in-picture) and brings it back when the panel is hidden.
 * Needs the "usage access" permission; without it nothing happens.
 */
final class DuoPreviousApp {
    private DuoPreviousApp() {}

    private static final String KEY_ASKED = "duo_usage_access_asked";
    private static final long LOOKBACK_MS = 6L * 60 * 60 * 1000;

    private static String previousPackage;
    /** The stream activity: the app is launched next to it, i.e. on the panel's screen. */
    private static java.lang.ref.WeakReference<Activity> stream;

    static boolean hasAccess(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false;
        }
        try {
            AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            int mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), c.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Called right before the panel opens (asks once for the permission if missing). */
    static void remember(Activity activity) {
        previousPackage = null;
        stream = new java.lang.ref.WeakReference<>(activity);
        if (!hasAccess(activity)) {
            askOnce(activity);
            return;
        }
        try {
            previousPackage = findOtherScreenApp(activity);
            LimeLog.info("Duo: app on the other screen: " + previousPackage);
        } catch (Throwable t) {
            LimeLog.warning("Duo: could not read usage events: " + t);
        }
    }

    /**
     * Brings the remembered app back on the panel's screen. Call after the panel has closed:
     * the app is started next to the stream (launch adjacent), never on top of it.
     */
    static void restoreLater() {
        final String pkg = previousPackage;
        previousPackage = null;
        final Activity from = stream == null ? null : stream.get();
        if (pkg == null || from == null || from.isFinishing()) {
            return;
        }
        // Give the panel a moment to leave its screen first.
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (from.isFinishing()) {
                return;
            }
            try {
                Intent launch = from.getPackageManager().getLaunchIntentForPackage(pkg);
                if (launch == null) {
                    return;
                }
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
                }
                from.startActivity(launch);
                from.overridePendingTransition(0, 0);
            } catch (Throwable t) {
                LimeLog.warning("Duo: could not restore " + pkg + ": " + t);
            }
        }, 150);
    }

    static void forget() {
        previousPackage = null;
    }

    private static String findOtherScreenApp(Context c) {
        UsageStatsManager usm = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
        long now = System.currentTimeMillis();
        UsageEvents events = usm.queryEvents(now - LOOKBACK_MS, now);

        // Resumed activities per package, in the order they were last resumed.
        Map<String, Set<String>> resumed = new LinkedHashMap<>();
        UsageEvents.Event e = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            String pkg = e.getPackageName();
            String cls = e.getClassName() == null ? "" : e.getClassName();
            int type = e.getEventType();
            if (type == UsageEvents.Event.ACTIVITY_RESUMED) {
                Set<String> s = resumed.remove(pkg);
                if (s == null) {
                    s = new HashSet<>();
                }
                s.add(cls);
                resumed.put(pkg, s);
            } else if (type == UsageEvents.Event.ACTIVITY_PAUSED
                    || type == UsageEvents.Event.ACTIVITY_STOPPED) {
                Set<String> s = resumed.get(pkg);
                if (s != null) {
                    s.remove(cls);
                    if (s.isEmpty()) {
                        resumed.remove(pkg);
                    }
                }
            }
        }

        Set<String> skip = new HashSet<>();
        skip.add(c.getPackageName());
        skip.add("com.android.systemui");
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo ri : c.getPackageManager().queryIntentActivities(home, 0)) {
            skip.add(ri.activityInfo.packageName);
        }

        String found = null;
        for (String pkg : resumed.keySet()) {
            if (!skip.contains(pkg)) {
                found = pkg; // last one wins = most recently resumed
            }
        }
        return found;
    }

    private static void askOnce(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }
        if (PreferenceManager.getDefaultSharedPreferences(activity).getBoolean(KEY_ASKED, false)) {
            return;
        }
        PreferenceManager.getDefaultSharedPreferences(activity).edit().putBoolean(KEY_ASKED, true).apply();
        Context themed = new ContextThemeWrapper(activity, activity.getApplicationInfo().theme);
        new AlertDialog.Builder(themed)
                .setTitle("Bring the app back")
                .setMessage("To bring back the app that was on the other screen (e.g. YouTube) when you "
                        + "hide the keyboard and trackpad, Artemide Duo needs usage access. "
                        + "Please allow it for \"Artemide Duo\" in the list.")
                .setPositiveButton("Settings", (d, w) -> {
                    try {
                        Intent i = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        activity.startActivity(i);
                    } catch (Throwable ignored) {
                    }
                })
                .setNegativeButton("Not now", null)
                .show();
    }
}
