package com.limelight.duo;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.limelight.profiles.ProfilesManager;
import com.limelight.profiles.SettingsProfile;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-game settings: picked in the app's Options, saved as an Artemide profile
 * ("Game · <name>") that is switched on just for that game's stream and switched back
 * afterwards. Also lets the user say whether an entry is a game or an app.
 */
public final class DuoGameSettings {
    private DuoGameSettings() {}

    private static final String PREFS = "duo_play_stats";
    private static final String KEY_RESTORE = "profile_restore"; // previous active profile ("" = none)

    private static final class Choice {
        final String label;
        final Object value; // null = default (not in the profile)

        Choice(String label, Object value) {
            this.label = label;
            this.value = value;
        }
    }

    private static final class Row {
        final String title, key;
        final Choice[] choices;
        int selected;

        Row(String title, String key, Choice... choices) {
            this.title = title;
            this.key = key;
            this.choices = choices;
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------------- game / app ----------------

    /** "game", "app" or null (automatic). */
    public static String typeOverride(Context c, String pcUuid, int appId) {
        return prefs(c).getString(pcUuid + "|" + appId + "|type", null);
    }

    // ---------------- profile switching around a stream ----------------

    private static UUID profileFor(Context c, String pcUuid, int appId) {
        String s = prefs(c).getString(pcUuid + "|" + appId + "|profile", null);
        if (s == null) {
            return null;
        }
        UUID id = UUID.fromString(s);
        for (SettingsProfile p : ProfilesManager.getInstance().getProfiles()) {
            if (p.getUuid().equals(id)) {
                return id;
            }
        }
        return null; // profile was deleted in the profile manager
    }

    /** Call right before starting a stream: turns on the game's own profile, if it has one. */
    public static void applyForLaunch(Context c, String pcUuid, int appId) {
        restore(c);
        UUID id = profileFor(c, pcUuid, appId);
        if (id == null) {
            return;
        }
        ProfilesManager pm = ProfilesManager.getInstance();
        SettingsProfile active = pm.getActive();
        prefs(c).edit().putString(KEY_RESTORE, active == null ? "" : active.getUuid().toString()).apply();
        pm.setActive(id);
    }

    /** After the stream (or when the lists come back): the profile chosen before is active again. */
    public static void restore(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.contains(KEY_RESTORE)) {
            return;
        }
        String prev = p.getString(KEY_RESTORE, "");
        p.edit().remove(KEY_RESTORE).apply();
        ProfilesManager.getInstance().setActive(prev.isEmpty() ? null : UUID.fromString(prev));
    }

    // ---------------- the dialog ----------------

    public static void show(Activity a, String pcUuid, int appId, String appName, Runnable onSaved) {
        float d = a.getResources().getDisplayMetrics().density;
        int accent = DuoTheme.accent(a);
        UUID existing = profileFor(a, pcUuid, appId);
        Map<String, Object> current = new HashMap<>();
        if (existing != null) {
            for (SettingsProfile p : ProfilesManager.getInstance().getProfiles()) {
                if (p.getUuid().equals(existing) && p.getOptions() != null) {
                    current.putAll(p.getOptions());
                }
            }
        }

        Row type = new Row("Type", null, new Choice("Automatic", null), new Choice("Game", "game"), new Choice("App", "app"));
        Row[] rows = {
                new Row("Resolution", "list_resolution", new Choice("Default", null),
                        new Choice("Duo 1800×1350", "1800x1350"), new Choice("720p", "1280x720"),
                        new Choice("1080p", "1920x1080"), new Choice("1440p", "2560x1440"), new Choice("4K", "3840x2160")),
                new Row("Frame rate", "list_fps", new Choice("Default", null), new Choice("30", "30"),
                        new Choice("60", "60"), new Choice("90", "90"), new Choice("120", "120")),
                new Row("Bitrate", "seekbar_bitrate_kbps", new Choice("Default", null), new Choice("10 Mbps", 10000),
                        new Choice("20", 20000), new Choice("30", 30000), new Choice("50", 50000),
                        new Choice("80", 80000), new Choice("100", 100000), new Choice("150", 150000)),
                new Row("Codec", "video_format", new Choice("Default", null), new Choice("Auto", "auto"),
                        new Choice("HEVC", "forceh265"), new Choice("AV1", "forceav1"), new Choice("H.264", "neverh265")),
                new Row("Frame pacing", "frame_pacing", new Choice("Default", null), new Choice("Lowest latency", "latency"),
                        new Choice("Balanced", "balanced"), new Choice("Cap FPS", "cap-fps"), new Choice("Smoothest", "smoothness")),
                new Row("HDR", "checkbox_enable_hdr", new Choice("Default", null), new Choice("On", true), new Choice("Off", false)),
                new Row("Virtual display", "checkbox_use_virtual_display", new Choice("Default", null),
                        new Choice("On", true), new Choice("Off", false)),
                new Row("Audio", "list_audio_config", new Choice("Default", null), new Choice("Stereo", "2"),
                        new Choice("5.1", "51"), new Choice("7.1", "71")),
        };
        // Preselect what is saved.
        String t = typeOverride(a, pcUuid, appId);
        type.selected = "game".equals(t) ? 1 : "app".equals(t) ? 2 : 0;
        for (Row r : rows) {
            Object v = current.get(r.key);
            for (int i = 1; i < r.choices.length && v != null; i++) {
                Object cv = r.choices[i].value;
                if (cv.equals(v) || (cv instanceof Integer && v instanceof Number && ((Number) v).intValue() == (Integer) cv)) {
                    r.selected = i;
                }
            }
        }

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * d);
        col.setPadding(pad, Math.round(4 * d), pad, Math.round(8 * d));
        TextView hint = new TextView(a);
        hint.setText("Only for this game. \"Default\" keeps your normal settings.");
        hint.setTextSize(11.5f);
        hint.setTextColor(DuoTheme.TEXT2);
        col.addView(hint);
        addRow(a, col, type, accent, d);
        for (Row r : rows) {
            addRow(a, col, r, accent, d);
        }
        ScrollView scroll = new ScrollView(a);
        scroll.addView(col);

        new AlertDialog.Builder(a)
                .setTitle(appName)
                .setView(scroll)
                .setPositiveButton("Save", (dlg, w) -> {
                    save(a, pcUuid, appId, appName, existing, type, rows);
                    if (onSaved != null) {
                        onSaved.run();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void save(Context c, String pcUuid, int appId, String appName, UUID existing, Row type, Row[] rows) {
        SharedPreferences.Editor e = prefs(c).edit();
        String base = pcUuid + "|" + appId + "|";
        Object tv = type.choices[type.selected].value;
        if (tv == null) {
            e.remove(base + "type");
        } else {
            e.putString(base + "type", (String) tv);
        }

        Map<String, Object> options = new HashMap<>();
        for (Row r : rows) {
            Object v = r.choices[r.selected].value;
            if (v != null) {
                options.put(r.key, v);
            }
        }
        ProfilesManager pm = ProfilesManager.getInstance();
        if (options.isEmpty()) {
            if (existing != null) {
                pm.delete(existing);
            }
            e.remove(base + "profile");
        } else {
            long now = System.currentTimeMillis();
            if (existing != null) {
                for (SettingsProfile p : pm.getProfiles()) {
                    if (p.getUuid().equals(existing)) {
                        p.setOptions(options);
                        p.setModifiedUtc(now);
                        pm.update(p);
                    }
                }
            } else {
                SettingsProfile p = new SettingsProfile(UUID.randomUUID(), "Game · " + appName, now, now, options);
                pm.add(p);
                e.putString(base + "profile", p.getUuid().toString());
            }
        }
        e.apply();
    }

    private static void addRow(Context c, LinearLayout col, Row r, int accent, float d) {
        TextView title = new TextView(c);
        title.setText(r.title.toUpperCase());
        title.setTextSize(9.5f);
        title.setLetterSpacing(0.15f);
        title.setTextColor(DuoTheme.TEXT2);
        title.setPadding(0, Math.round(14 * d), 0, Math.round(6 * d));
        col.addView(title);

        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout pills = new LinearLayout(c);
        pills.setPadding(Math.round(2 * d), Math.round(2 * d), Math.round(2 * d), Math.round(2 * d));
        GradientDrawable track = new GradientDrawable();
        track.setColor(0xFF000000);
        track.setCornerRadius(9 * d);
        track.setStroke(Math.max(1, Math.round(d)), DuoTheme.LINE);
        pills.setBackground(track);
        hs.addView(pills);
        col.addView(hs);
        TextView[] views = new TextView[r.choices.length];
        for (int i = 0; i < r.choices.length; i++) {
            final int idx = i;
            TextView t = new TextView(c);
            t.setText(r.choices[i].label);
            t.setTextSize(12);
            t.setTypeface(DuoFonts.medium());
            t.setGravity(Gravity.CENTER);
            t.setPadding(Math.round(11 * d), 0, Math.round(11 * d), 0);
            t.setFocusable(true);
            t.setClickable(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, Math.round(30 * d));
            if (i > 0) {
                lp.leftMargin = Math.round(2 * d);
            }
            pills.addView(t, lp);
            views[i] = t;
            t.setOnClickListener(v -> {
                r.selected = idx;
                style(views, r.selected, accent, d);
            });
        }
        style(views, r.selected, accent, d);
    }

    private static void style(TextView[] views, int selected, int accent, float d) {
        for (int i = 0; i < views.length; i++) {
            boolean on = i == selected;
            android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
            GradientDrawable focus = new GradientDrawable();
            focus.setCornerRadius(7 * d);
            focus.setColor(on ? DuoTheme.mix(accent, DuoTheme.CARD, 0.16f) : 0);
            focus.setStroke(Math.round(1.5f * d), accent);
            GradientDrawable normal = new GradientDrawable();
            normal.setCornerRadius(7 * d);
            normal.setColor(on ? DuoTheme.mix(accent, DuoTheme.CARD, 0.16f) : 0);
            s.addState(new int[]{android.R.attr.state_focused}, focus);
            s.addState(new int[]{}, normal);
            views[i].setBackground(s);
            views[i].setTextColor(on ? accent : DuoTheme.TEXT);
        }
    }
}
