package com.limelight.duo;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.profiles.ProfilesManager;
import com.limelight.profiles.SettingsProfile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Host profiles in the StreamLight look: pick Off or a profile (LB/RB on a controller), see what it
 * changes, add one from a template (Desktop, GPD Win Max) or from the current settings.
 * Per-game profiles ("Game · …") are left out; they are managed under Game settings.
 */
public final class DuoProfilesDialog {
    private DuoProfilesDialog() {}

    public static void show(Activity a, String hostName) {
        ProfilesManager.getInstance().load(a);
        new DuoProfilesDialog.State(a, hostName).open();
    }

    private static final class State {
        final Activity a;
        final String host;
        final float d;
        final int accent;
        Dialog dialog;
        LinearLayout pills, rows;

        State(Activity a, String host) {
            this.a = a;
            this.host = host;
            this.d = a.getResources().getDisplayMetrics().density;
            this.accent = DuoTheme.accent(a);
        }

        int dp(float v) {
            return Math.round(v * d);
        }

        List<SettingsProfile> list() {
            List<SettingsProfile> l = new ArrayList<>();
            for (SettingsProfile p : ProfilesManager.getInstance().getProfiles()) {
                if (p.getName() == null || !p.getName().startsWith("Game · ")) {
                    l.add(p);
                }
            }
            return l;
        }

        void open() {
            LinearLayout card = new LinearLayout(a);
            card.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF121A1F);
            bg.setCornerRadius(16 * d);
            bg.setStroke(Math.max(1, dp(1)), 0x1AFFFFFF);
            card.setBackground(bg);

            LinearLayout head = new LinearLayout(a);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setPadding(dp(18), dp(14), dp(18), dp(12));
            TextView title = text("Host profiles", 17, DuoTheme.TEXT, DuoFonts.semibold());
            head.addView(title);
            TextView sub = text(host == null ? "" : host, 12, DuoTheme.TEXT2, DuoFonts.regular());
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
            sp.leftMargin = dp(10);
            head.addView(sub, sp);
            card.addView(head);
            card.addView(line());

            LinearLayout bar = new LinearLayout(a);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setPadding(dp(18), dp(10), dp(18), dp(10));
            bar.addView(text("Profile", 14, DuoTheme.TEXT, DuoFonts.semibold()),
                    new LinearLayout.LayoutParams(0, -2, 1f));
            HorizontalScrollView hs = new HorizontalScrollView(a);
            hs.setHorizontalScrollBarEnabled(false);
            pills = new LinearLayout(a);
            pills.setGravity(Gravity.CENTER_VERTICAL);
            hs.addView(pills);
            bar.addView(hs, new LinearLayout.LayoutParams(-2, -2));
            card.addView(bar);
            card.addView(line());

            rows = new LinearLayout(a);
            rows.setOrientation(LinearLayout.VERTICAL);
            rows.setPadding(dp(18), dp(10), dp(18), dp(6));
            card.addView(rows);
            card.addView(line());

            LinearLayout foot = new LinearLayout(a);
            foot.setGravity(Gravity.CENTER_VERTICAL);
            foot.setPadding(dp(18), dp(10), dp(18), dp(12));
            TextView hint = text(DuoPadNav.hasGamepad() ? "LB / RB switch profile" : "", 11, DuoTheme.TEXT2, DuoFonts.regular());
            foot.addView(hint, new LinearLayout.LayoutParams(0, -2, 1f));
            View edit = button("Edit…", null, v -> {
                dialog.dismiss();
                a.startActivity(new Intent(a, com.limelight.ProfilesActivity.class));
            });
            foot.addView(edit);
            View done = button("Done", "B", v -> dialog.dismiss());
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-2, dp(34));
            dl.leftMargin = dp(8);
            foot.addView(done, dl);
            card.addView(foot);

            dialog = new Dialog(a);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setContentView(card);
            Window w = dialog.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                int width = Math.min(a.getResources().getDisplayMetrics().widthPixels - dp(48), dp(560 * DuoTheme.uiScale(a)));
                w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
                w.setDimAmount(0.62f);
                w.setWindowAnimations(com.limelight.R.style.DuoDialogAnim);
            }
            dialog.setOnKeyListener((dlg, code, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) {
                    return false;
                }
                if (code == KeyEvent.KEYCODE_BUTTON_L1 || code == KeyEvent.KEYCODE_BUTTON_R1) {
                    step(code == KeyEvent.KEYCODE_BUTTON_R1 ? 1 : -1);
                    return true;
                }
                if (code == KeyEvent.KEYCODE_BUTTON_B) {
                    dialog.dismiss();
                    return true;
                }
                return false;
            });
            render();
            dialog.show();
        }

        void step(int dir) {
            List<SettingsProfile> l = list();
            SettingsProfile act = ProfilesManager.getInstance().getActive();
            int i = act == null ? -1 : indexOf(l, act.getUuid());
            int n = l.size() + 1;
            int j = ((i + 1 + dir) % n + n) % n - 1;
            ProfilesManager.getInstance().setActive(j < 0 ? null : l.get(j).getUuid());
            render();
        }

        int indexOf(List<SettingsProfile> l, UUID u) {
            for (int i = 0; i < l.size(); i++) {
                if (l.get(i).getUuid().equals(u)) {
                    return i;
                }
            }
            return -1;
        }

        void render() {
            pills.removeAllViews();
            SettingsProfile act = ProfilesManager.getInstance().getActive();
            if (act != null && act.getName() != null && act.getName().startsWith("Game · ")) {
                act = null;
            }
            addPill("Off", act == null, () -> ProfilesManager.getInstance().setActive(null));
            for (SettingsProfile p : list()) {
                final UUID u = p.getUuid();
                addPill(p.getName(), act != null && act.getUuid().equals(u), () -> ProfilesManager.getInstance().setActive(u));
            }
            TextView add = pill("+ Add", false);
            add.setTextColor(accent);
            GradientDrawable ab = new GradientDrawable();
            ab.setCornerRadius(9 * d);
            ab.setColor(0x0FFFFFFF);
            ab.setStroke(dp(1.5f), DuoTheme.focus(accent));
            add.setBackground(ab);
            add.setOnClickListener(v -> addMenu(v));
            LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-2, dp(32));
            al.leftMargin = dp(6);
            pills.addView(add, al);

            rows.removeAllViews();
            if (act == null) {
                TextView t = text(list().isEmpty()
                        ? "No profiles yet.\nAdd one to override streaming settings for this host\n(e.g. a \"Desktop\" profile and a \"GPD Win Max\" profile)."
                        : "Off: your normal settings are used.", 12.5f, DuoTheme.TEXT, DuoFonts.regular());
                t.setGravity(Gravity.CENTER);
                t.setPadding(0, dp(18), 0, dp(18));
                rows.addView(t, new LinearLayout.LayoutParams(-1, -2));
            } else {
                Map<String, Object> o = act.getOptions() == null ? new HashMap<>() : act.getOptions();
                row("Resolution", o.get("list_resolution"));
                row("Frame rate", o.get("list_fps") == null ? null : o.get("list_fps") + " FPS");
                Object br = o.get("seekbar_bitrate_kbps");
                row("Bitrate", br instanceof Number ? Math.round(((Number) br).intValue() / 1000f) + " Mbps" : br);
                row("Codec", codec(o.get("video_format")));
                row("HDR", onOff(o.get("checkbox_enable_hdr")));
                row("Virtual display", onOff(o.get("checkbox_use_virtual_display")));
                int other = o.size();
                for (String k : new String[]{"list_resolution", "list_fps", "seekbar_bitrate_kbps", "video_format",
                        "checkbox_enable_hdr", "checkbox_use_virtual_display"}) {
                    if (o.containsKey(k)) {
                        other--;
                    }
                }
                if (other > 0) {
                    row("Other settings", other + " changed");
                }
            }
        }

        String codec(Object v) {
            if (v == null) {
                return null;
            }
            switch (String.valueOf(v)) {
                case "forceh265": return "HEVC";
                case "forceav1": return "AV1";
                case "neverh265": return "H.264";
                default: return "Auto";
            }
        }

        String onOff(Object v) {
            return v == null ? null : Boolean.TRUE.equals(v) || "true".equals(String.valueOf(v)) ? "On" : "Off";
        }

        void row(String k, Object v) {
            LinearLayout r = new LinearLayout(a);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(12), 0, dp(12), 0);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(9 * d);
            g.setColor(0x0AFFFFFF);
            g.setStroke(Math.max(1, dp(1)), 0x0FFFFFFF);
            r.setBackground(g);
            r.addView(text(k, 13, DuoTheme.TEXT, DuoFonts.regular()), new LinearLayout.LayoutParams(0, -2, 1f));
            r.addView(text(v == null ? "Default" : String.valueOf(v), 12.5f, v == null ? DuoTheme.TEXT2 : accent, DuoFonts.semibold()));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(36));
            lp.bottomMargin = dp(6);
            rows.addView(r, lp);
        }

        void addMenu(View anchor) {
            android.widget.PopupMenu m = new android.widget.PopupMenu(a, anchor);
            m.getMenu().add(0, 1, 0, "Desktop (Duo, virtual display)");
            m.getMenu().add(0, 2, 1, "GPD Win Max (2560×1600, 120 FPS, HDR)");
            m.getMenu().add(0, 3, 2, "Copy current settings…");
            m.setOnMenuItemClickListener(item -> {
                Map<String, Object> o = new HashMap<>();
                String name;
                if (item.getItemId() == 1) {
                    name = "Desktop";
                    o.put("list_resolution", "1800x1350");
                    o.put("list_fps", "60");
                    o.put("seekbar_bitrate_kbps", 40000);
                    o.put("video_format", "forceh265");
                    o.put("checkbox_use_virtual_display", true);
                } else if (item.getItemId() == 2) {
                    name = "GPD Win Max";
                    o.put("list_resolution", "2560x1600");
                    o.put("list_fps", "120");
                    o.put("seekbar_bitrate_kbps", 80000);
                    o.put("video_format", "forceav1");
                    o.put("checkbox_enable_hdr", true);
                    o.put("checkbox_use_virtual_display", true);
                } else {
                    dialog.dismiss();
                    a.startActivity(new Intent(a, com.limelight.ProfilesActivity.class));
                    return true;
                }
                long now = System.currentTimeMillis();
                SettingsProfile p = new SettingsProfile(UUID.randomUUID(), name, now, now, o);
                ProfilesManager.getInstance().add(p);
                ProfilesManager.getInstance().setActive(p.getUuid());
                render();
                return true;
            });
            m.show();
        }

        void addPill(String label, boolean on, Runnable pick) {
            TextView t = pill(label, on);
            t.setOnClickListener(v -> {
                pick.run();
                render();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(32));
            if (pills.getChildCount() > 0) {
                lp.leftMargin = dp(6);
            }
            pills.addView(t, lp);
        }

        TextView pill(String label, boolean on) {
            TextView t = text(label, 12.5f, on ? DuoTheme.onAccent(accent) : DuoTheme.TEXT, DuoFonts.semibold());
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(14), 0, dp(14), 0);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(9 * d);
            g.setColor(on ? accent : 0x0FFFFFFF);
            if (!on) {
                g.setStroke(dp(1.5f), 0x1FFFFFFF);
            }
            t.setBackground(g);
            t.setFocusable(true);
            return t;
        }

        View button(String label, String glyph, View.OnClickListener l) {
            LinearLayout b = new LinearLayout(a);
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(14), 0, dp(16), 0);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(10 * d);
            g.setColor(0x17FFFFFF);
            g.setStroke(Math.max(1, dp(1)), 0x29FFFFFF);
            b.setBackground(g);
            if (glyph != null && DuoPadNav.hasGamepad()) {
                TextView gl = text(glyph, 9.5f, Color.BLACK, DuoFonts.bold());
                DuoTheme.styleGlyph(gl, glyph, 1f);
                gl.setBackground(DuoTheme.padButton(0xFFE0645A, d));
                LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(23), dp(23));
                gp.leftMargin = -dp(3);
                gp.rightMargin = dp(5);
                b.addView(gl, gp);
            }
            b.addView(text(label, 13, DuoTheme.TEXT, DuoFonts.medium()));
            b.setClickable(true);
            b.setFocusable(true);
            b.setOnClickListener(l);
            b.setMinimumHeight(dp(34));
            return b;
        }

        View line() {
            View v = new View(a);
            v.setBackgroundColor(0x14FFFFFF);
            v.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.max(1, dp(1))));
            return v;
        }

        TextView text(String s, float sp, int color, android.graphics.Typeface tf) {
            TextView t = new TextView(a);
            t.setText(s);
            t.setTextSize(sp);
            t.setTextColor(color);
            t.setTypeface(tf);
            return t;
        }
    }
}
