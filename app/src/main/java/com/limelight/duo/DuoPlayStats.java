package com.limelight.duo;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

/**
 * Last played app per host, play time and sessions per app. Kept on the Duo only
 * (Artemide/Artemis don't track this). Keys: "<pcUuid>|last.*" and "<pcUuid>|<appId>|time/sessions".
 */
public final class DuoPlayStats {
    private DuoPlayStats() {}

    private static final String PREFS = "duo_play_stats";

    public static final class Last {
        public int appId;
        public String name;
        public String appUuid;
        public boolean hdr;
        public long when;
        public long totalMs;
        public int sessions;
    }

    // Session in progress (one stream at a time).
    private static String runPc;
    private static int runApp;
    private static long runStart;

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The stream is connected. */
    public static void started(Context c, String pcUuid, int appId, String name, String appUuid, boolean hdr) {
        if (pcUuid == null) {
            return;
        }
        ended(c);
        runPc = pcUuid;
        runApp = appId;
        runStart = SystemClock.elapsedRealtime();
        String p = pcUuid + "|";
        String a = p + appId + "|";
        SharedPreferences s = prefs(c);
        // A resume without a known name ("app") keeps the name we already have.
        if (name == null || name.isEmpty() || "app".equals(name)) {
            String known = s.getString(a + "name", null);
            if (known != null) {
                name = known;
            }
        }
        s.edit()
                .putInt(p + "last.id", appId)
                .putString(p + "last.name", name)
                .putString(p + "last.uuid", appUuid)
                .putBoolean(p + "last.hdr", hdr)
                .putLong(p + "last.when", System.currentTimeMillis())
                .putInt(a + "sessions", s.getInt(a + "sessions", 0) + 1)
                .putString(a + "name", name)
                .apply();
    }

    /** The stream ended (activity destroyed). Adds the play time. */
    public static void ended(Context c) {
        if (runPc == null) {
            return;
        }
        long ms = SystemClock.elapsedRealtime() - runStart;
        String a = runPc + "|" + runApp + "|";
        SharedPreferences s = prefs(c);
        s.edit().putLong(a + "time", s.getLong(a + "time", 0) + Math.max(0, ms))
                .putLong(runPc + "|last.when", System.currentTimeMillis())
                .apply();
        runPc = null;
        DuoGameSettings.restore(c);
    }

    public static Last last(Context c, String pcUuid) {
        SharedPreferences s = prefs(c);
        String p = pcUuid + "|";
        if (!s.contains(p + "last.id")) {
            return null;
        }
        Last l = new Last();
        l.appId = s.getInt(p + "last.id", 0);
        l.name = s.getString(p + "last.name", "");
        l.appUuid = s.getString(p + "last.uuid", null);
        l.hdr = s.getBoolean(p + "last.hdr", false);
        l.when = s.getLong(p + "last.when", 0);
        l.totalMs = time(c, pcUuid, l.appId);
        l.sessions = sessions(c, pcUuid, l.appId);
        return l;
    }

    public static long time(Context c, String pcUuid, int appId) {
        return prefs(c).getLong(pcUuid + "|" + appId + "|time", 0);
    }

    public static int sessions(Context c, String pcUuid, int appId) {
        return prefs(c).getInt(pcUuid + "|" + appId + "|sessions", 0);
    }

    /** "1 h 11 m", "54 m", "< 1 m". */
    public static String duration(long ms) {
        long m = ms / 60000;
        if (m < 1) {
            return "< 1 m";
        }
        return m >= 60 ? (m / 60) + " h " + (m % 60) + " m" : m + " m";
    }

    /** "14 h ago", "3 d ago", "just now". */
    public static String ago(long when) {
        long d = Math.max(0, System.currentTimeMillis() - when) / 60000;
        if (d < 1) {
            return "just now";
        }
        if (d < 60) {
            return d + " min ago";
        }
        if (d < 48 * 60) {
            return (d / 60) + " h ago";
        }
        return (d / 1440) + " d ago";
    }

    /** Words that mark a tool / app rather than a game (tabs GAMES / APPS). */
    private static final String[] APP_WORDS = {"desktop", "virtual display", "remote", "reboot", "restart",
            "shutdown", "shut down", "power off", "hibernate", "log off", "logoff", "sign out", "lock screen",
            "big picture", "playnite", "launcher", "explorer", "browser", "chrome", "firefox", "spotify",
            "youtube", "netflix", "plex", "kodi", "vlc", "terminal", "powershell", "task manager", "discord"};

    /** Game or app, honouring the choice made in "Game settings & type". */
    public static boolean isApp(Context c, String pcUuid, int appId, String name) {
        String t = pcUuid == null ? null : DuoGameSettings.typeOverride(c, pcUuid, appId);
        if (t != null) {
            return "app".equals(t);
        }
        return isApp(name);
    }

    /** Tools and apps (Desktop, PC Reboot, Steam Big Picture …) vs. games. */
    public static boolean isApp(String name) {
        if (name == null) {
            return false;
        }
        String n = name.toLowerCase();
        for (String w : APP_WORDS) {
            if (n.contains(w)) {
                return true;
            }
        }
        return false;
    }

    // ---------------- favourites (pinned apps) ----------------

    public static boolean isPinned(Context c, String pcUuid, int appId) {
        return prefs(c).getBoolean(pcUuid + "|" + appId + "|pinned", false);
    }

    public static void setPinned(Context c, String pcUuid, int appId, boolean pinned) {
        prefs(c).edit().putBoolean(pcUuid + "|" + appId + "|pinned", pinned).apply();
    }
}
