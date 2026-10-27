package com.limelight.duo;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.hardware.input.InputManager;
import android.os.BatteryManager;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.preferences.PreferenceConfiguration;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * App list of one host, laid out after StreamLight's apps page: tabs ALL / GAMES / APPS,
 * "Last played" on top, a big cover with play time and sessions, Play and Options.
 * Touch: tap a row to select it, tap it again (or Play) to start. Controller: D-pad,
 * A start, Y options, LB/RB tabs, X settings, B back to the host.
 */
@SuppressLint("ViewConstructor")
public class DuoAppsView extends FrameLayout implements InputManager.InputDeviceListener {

    /** One app as the screen needs it. */
    public static final class App {
        public final Object key;      // AppView.AppObject
        public final int id;
        public final String name;
        public final boolean running;

        public App(Object key, int id, String name, boolean running) {
            this.key = key;
            this.id = id;
            this.name = name;
            this.running = running;
        }
    }

    public interface Host {
        List<App> apps();
        ComputerDetails computer();
        void start(App app);
        void quit(App app);
        void options(App app);
        void loadCover(App app, ImageView into);
        void profiles();
        void settings();
        void back();
    }

    private static final String[] TABS = {"ALL", "GAMES", "APPS"};

    private final Host host;
    private final float d;
    private final float scale;
    private final int accent;
    private final boolean landscape;
    private final List<View> glyphs = new ArrayList<>();
    private final TextView[] tabViews = new TextView[3];
    private int tab;
    private int selectedId = -1;
    private boolean opened;
    private boolean staggered;
    private int shownDetailId = Integer.MIN_VALUE;

    private final TextView hostName, badge, clock, dateLine;
    private final LinearLayout chips;
    private final LinearLayout list;
    private final ScrollView listScroll;
    private ImageView bigCover;
    private TextView bigPlaceholder, bigName, statTime, statSessions;
    private View playButton, quitButton, optionsButton, pinButton;
    private int batteryPct = -1;
    private boolean charging;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_BATTERY_CHANGED.equals(i.getAction())) {
                int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                int st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                batteryPct = level < 0 || scale <= 0 ? -1 : Math.round(level * 100f / scale);
                charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
            }
            updateClock();
        }
    };

    public DuoAppsView(Context context, Host host, String title) {
        super(context);
        this.host = host;
        this.scale = DuoTheme.uiScale(context);
        this.d = context.getResources().getDisplayMetrics().density * scale;
        this.accent = DuoTheme.accent(context);
        this.landscape = context.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        setClickable(true);

        addView(new DuoWavesView(context, false), match());

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(18), dp(22), dp(12));
        addView(content, match());

        // ---------- header ----------
        LinearLayout header = row();
        header.setGravity(Gravity.TOP);
        content.addView(header, new LinearLayout.LayoutParams(-1, -2));
        header.addView(logoBox(), new LinearLayout.LayoutParams(dp(30), dp(30)));
        hostName = text(title == null ? "" : title, 20, DuoTheme.TEXT, true);
        hostName.setSingleLine(true);
        hostName.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams hn = new LinearLayout.LayoutParams(-2, dp(30));
        hn.leftMargin = dp(10);
        hostName.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(hostName, hn);
        badge = text("", 10, DuoTheme.TEXT, true);
        badge.setLetterSpacing(0.1f);
        badge.setCompoundDrawablePadding(dp(6));
        badge.setPadding(dp(9), dp(4), dp(9), dp(4));
        badge.setBackground(rounded(0, DuoTheme.LINE, 6));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
        bl.leftMargin = dp(10);
        bl.topMargin = dp(4);
        header.addView(badge, bl);
        header.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1f));
        LinearLayout clockCol = new LinearLayout(context);
        clockCol.setOrientation(LinearLayout.VERTICAL);
        clockCol.setGravity(Gravity.END);
        header.addView(clockCol);
        clock = text("", 34, DuoTheme.TEXT, false);
        clockCol.addView(clock);
        dateLine = text("", 13.5f, DuoTheme.TEXT2, false);
        clockCol.addView(dateLine);

        // ---------- host + stream chips ----------
        HorizontalScrollView cs = new HorizontalScrollView(context);
        cs.setHorizontalScrollBarEnabled(false);
        chips = row();
        cs.addView(chips);
        // Info only: never takes focus.
        cs.setFocusable(false);
        cs.setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.topMargin = dp(8);
        content.addView(cs, cp);

        // ---------- tabs ----------
        LinearLayout tabs = row();
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, dp(30));
        tl.topMargin = dp(12);
        content.addView(tabs, tl);
        View lb = keyPill("LB");
        lb.setOnClickListener(v -> setTab((tab + 2) % 3));
        tabs.addView(lb);
        for (int i = 0; i < 3; i++) {
            final int t = i;
            TextView tv = text(TABS[i], 12, DuoTheme.TEXT3, true);
            tv.setLetterSpacing(0.15f);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(dp(6), 0, dp(6), 0);
            // Tabs switch with LB/RB (or touch); the stick never lands on them.
            tv.setFocusable(false);
            tv.setOnClickListener(v -> setTab(t));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -1);
            lp.leftMargin = dp(i == 0 ? 10 : 8);
            tabs.addView(tv, lp);
            tabViews[i] = tv;
        }
        View rb = keyPill("RB");
        rb.setOnClickListener(v -> setTab((tab + 1) % 3));
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-2, -2);
        rl.leftMargin = dp(10);
        tabs.addView(rb, rl);

        // ---------- body ----------
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, 0, 1f);
        bp.topMargin = dp(8);
        content.addView(body, bp);

        listScroll = new ScrollView(context);
        listScroll.setVerticalScrollBarEnabled(false);
        // A scroll container must never show Android's grey focus tint.
        listScroll.setFocusable(false);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            listScroll.setDefaultFocusHighlightEnabled(false);
        }
        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        listScroll.addView(list);
        View detail = buildDetail();
        if (landscape) {
            body.addView(listScroll, new LinearLayout.LayoutParams(0, -1, 1.45f));
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(0, -1, 1f);
            dl.leftMargin = dp(18);
            body.addView(detail, dl);
        } else {
            body.addView(detail, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, 0, 1f);
            ll.topMargin = dp(10);
            body.addView(listScroll, ll);
        }

        // ---------- bottom ----------
        LinearLayout bottom = row();
        LinearLayout.LayoutParams bt = new LinearLayout.LayoutParams(-1, dp(36));
        bt.topMargin = dp(12);
        content.addView(bottom, bt);
        bottomFirst = button("‹  Back", "B", false, v -> host.back());
        bottom.addView(bottomFirst, new LinearLayout.LayoutParams(-2, -1));
        LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(-2, -1);
        b2.leftMargin = dp(8);
        bottom.addView(button("Settings", "Y", false, v -> host.settings()), b2);
        // Options: the View button (⧉, left of the Xbox button).
        optionsButton = button("Options", "⧉", false, v -> {
            App a = selectedApp();
            if (a != null) {
                host.options(a);
            }
        });
        LinearLayout.LayoutParams b4 = new LinearLayout.LayoutParams(-2, -1);
        b4.leftMargin = dp(8);
        bottom.addView(optionsButton, b4);
        // Bottom row works with B / Y / Menu; the stick never lands on it.
        for (int i = 0; i < bottom.getChildCount(); i++) {
            bottom.getChildAt(i).setFocusable(false);
        }

        setTab(0);
        updateGlyphs();
        updateClock();
    }

    // ================= data =================

    public void refresh() {
        ComputerDetails c = host.computer();
        if (c != null) {
            hostName.setText(c.name);
            boolean online = c.state == ComputerDetails.State.ONLINE;
            badge.setText(online ? "ONLINE" : c.state == ComputerDetails.State.OFFLINE ? "OFFLINE" : "CHECKING");
            badge.setCompoundDrawablesRelativeWithIntrinsicBounds(dot(online ? DuoTheme.ONLINE
                    : c.state == ComputerDetails.State.OFFLINE ? DuoTheme.OFFLINE : DuoTheme.WARN), null, null, null);
        }
        fillChips(c);
        renderList();
    }

    private void setTab(int t) {
        int dir = Integer.signum(t - tab);
        boolean changed = t != tab;
        tab = t;
        for (int i = 0; i < 3; i++) {
            TextView tv = tabViews[i];
            tv.setTextColor(i == t ? DuoTheme.TEXT : DuoTheme.TEXT3);
            GradientDrawable underline = new GradientDrawable();
            underline.setColor(i == t ? accent : 0);
            android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(
                    new android.graphics.drawable.Drawable[]{underline});
            ld.setLayerInset(0, dp(6), dp(27), dp(6), 0);
            tv.setBackground(ld);
        }
        renderList();
        if (changed) {
            // The list slides in from the side of the new tab, rows rising one after the other.
            DuoMotion.slideIn(listScroll, dir);
            DuoMotion.stagger(list, 14);
        }
    }

    private boolean inTab(App a) {
        ComputerDetails c = host.computer();
        return tab == 0 || (tab == 1) != DuoPlayStats.isApp(getContext(), c == null ? null : c.uuid, a.id, a.name);
    }

    private void renderList() {
        View before = findFocus();
        boolean refocus = before == null || list.hasFocus();
        list.removeAllViews();
        ComputerDetails c = host.computer();
        List<App> apps = host.apps();
        DuoPlayStats.Last last = c == null ? null : DuoPlayStats.last(getContext(), c.uuid);

        App lastApp = null;
        List<App> pinned = new ArrayList<>();
        List<App> rest = new ArrayList<>();
        for (App a : apps) {
            if (!inTab(a)) {
                continue;
            }
            if (last != null && a.id == last.appId && lastApp == null) {
                lastApp = a;
            } else if (c != null && DuoPlayStats.isPinned(getContext(), c.uuid, a.id)) {
                pinned.add(a);
            } else {
                rest.add(a);
            }
        }
        // Opening the list starts on the last played app (or the first one).
        if (!opened && !apps.isEmpty()) {
            opened = true;
            selectedId = -1;
        }
        if (selectedId == -1 || !contains(apps, selectedId)) {
            selectedId = lastApp != null ? lastApp.id : !pinned.isEmpty() ? pinned.get(0).id
                    : rest.isEmpty() ? -1 : rest.get(0).id;
        }
        if (lastApp != null) {
            list.addView(section("LAST PLAYED", accent));
            list.addView(rowFor(lastApp, c));
        }
        if (!pinned.isEmpty()) {
            list.addView(section("FAVOURITES", DuoTheme.TEXT2));
            for (App a : pinned) {
                list.addView(rowFor(a, c));
            }
        }
        if (!rest.isEmpty()) {
            list.addView(section(tab == 0 ? "ALL" : TABS[tab], DuoTheme.TEXT2));
            for (App a : rest) {
                list.addView(rowFor(a, c));
            }
        }
        if (lastApp == null && rest.isEmpty() && pinned.isEmpty()) {
            TextView empty = text(apps.isEmpty() ? "Loading apps…" : "Nothing here", 13, DuoTheme.TEXT2, false);
            if (apps.isEmpty()) {
                DuoMotion.pulse(empty);
            }
            empty.setPadding(0, dp(18), 0, 0);
            list.addView(empty);
        }
        renderDetail();
        if (!staggered && !apps.isEmpty()) {
            staggered = true;
            DuoMotion.stagger(list, 14);
        }
        // Start on (and keep focus on) the selected app, LAST PLAYED first.
        if (refocus) {
            View row = list.findViewWithTag(selectedApp());
            if (row != null && !row.requestFocus() && DuoPadNav.hasGamepad()) {
                row.requestFocusFromTouch();
            }
        }
    }

    /** Favourite on/off (star button or long press on a row). */
    private void togglePin(App a) {
        ComputerDetails c = host.computer();
        if (c == null) {
            return;
        }
        DuoPlayStats.setPinned(getContext(), c.uuid, a.id, !DuoPlayStats.isPinned(getContext(), c.uuid, a.id));
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        selectedId = a.id;
        renderList();
    }

    private static boolean contains(List<App> apps, int id) {
        for (App a : apps) {
            if (a.id == id) {
                return true;
            }
        }
        return false;
    }

    private App selectedApp() {
        for (App a : host.apps()) {
            if (a.id == selectedId) {
                return a;
            }
        }
        return null;
    }

    private void select(App a) {
        if (a.id == selectedId) {
            return;
        }
        selectedId = a.id;
        for (int i = 0; i < list.getChildCount(); i++) {
            View v = list.getChildAt(i);
            if (v.getTag() instanceof App) {
                v.setSelected(((App) v.getTag()).id == selectedId);
            }
        }
        renderDetail();
    }

    private View section(String title, int color) {
        TextView t = text(title, 9.5f, color, true);
        t.setLetterSpacing(0.15f);
        t.setPadding(dp(2), dp(10), 0, dp(2));
        return t;
    }

    private View rowFor(App a, ComputerDetails c) {
        LinearLayout r = row();
        r.setTag(a);
        r.setPadding(dp(10), 0, dp(10), 0);
        r.setBackground(rowBackground());
        r.setSelected(a.id == selectedId);
        r.setFocusable(true);
        r.setClickable(true);

        FrameLayout thumb = new FrameLayout(getContext());
        thumb.setBackground(DuoCovers.placeholderBackground(accent, 5 * d));
        thumb.setClipToOutline(true);
        View ph;
        if (DuoPlayStats.isApp(getContext(), c == null ? null : c.uuid, a.id, a.name)) {
            ImageView icon = new ImageView(getContext());
            icon.setImageDrawable(tinted(R.drawable.ic_duo_monitor, DuoTheme.TEXT));
            icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            icon.setPadding(dp(6), dp(6), dp(6), dp(6));
            ph = icon;
        } else {
            TextView t = text(placeholderText(a), 9, DuoTheme.TEXT, true);
            t.setGravity(Gravity.CENTER);
            ph = t;
        }
        thumb.addView(ph, match());
        ImageView iv = new ImageView(getContext());
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.addView(iv, match());
        if (c != null) {
            DuoCovers.into(getContext(), c.uuid, a.id, iv, ph, dp(64));
        }
        r.addView(thumb, new LinearLayout.LayoutParams(dp(38), dp(54)));

        LinearLayout texts = new LinearLayout(getContext());
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
        tp.leftMargin = dp(12);
        r.addView(texts, tp);
        TextView n = text(a.name, 14, DuoTheme.TEXT, false);
        n.setSingleLine(true);
        n.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(n);
        String sub;
        int subColor = DuoTheme.TEXT2;
        if (a.running) {
            sub = "Running now";
            subColor = accent;
        } else {
            long ms = c == null ? 0 : DuoPlayStats.time(getContext(), c.uuid, a.id);
            sub = ms > 0 ? DuoPlayStats.duration(ms) : "";
        }
        if (!sub.isEmpty()) {
            TextView s = text(sub, 11, subColor, false);
            s.setPadding(0, dp(3), 0, 0);
            texts.addView(s);
        }

        r.setOnLongClickListener(v -> {
            togglePin(a);
            return true;
        });
        r.setOnFocusChangeListener((v, has) -> {
            if (has) {
                select(a);
            }
        });
        r.setOnClickListener(v -> {
            if (a.id == selectedId && !v.isFocused()) {
                host.start(a);    // second tap on the selected row starts it
            } else if (v.isFocused()) {
                host.start(a);    // controller A / Enter on the focused row
            } else {
                select(a);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(62));
        lp.topMargin = dp(5);
        r.setLayoutParams(lp);
        DuoMotion.press(r);
        return r;
    }

    private static String placeholderText(App a) {
        StringBuilder b = new StringBuilder();
        for (String w : a.name.split("\\s+")) {
            if (!w.isEmpty() && b.length() < 3) {
                b.append(Character.toUpperCase(w.charAt(0)));
            }
        }
        return b.toString();
    }

    private View buildDetail() {
        LinearLayout p = new LinearLayout(getContext());
        p.setOrientation(landscape ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        p.setGravity(landscape ? Gravity.CENTER_HORIZONTAL : Gravity.CENTER_VERTICAL);

        FrameLayout cover = new FrameLayout(getContext());
        cover.setBackground(DuoCovers.placeholderBackground(accent, 12 * d));
        cover.setForeground(rounded(0, DuoTheme.LINE, 12));
        cover.setClipToOutline(true);
        cover.setElevation(dp(10));
        int cw = Math.round((landscape ? 124 : 92) * (scale > 1.15f ? 1.1f : 1.2f));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(dp(cw), dp(cw * 1.41f));
        if (landscape) {
            cl.topMargin = dp(10);
        }
        p.addView(cover, cl);
        bigPlaceholder = text("", 13, DuoTheme.TEXT, true);
        bigPlaceholder.setLetterSpacing(0.15f);
        bigPlaceholder.setGravity(Gravity.CENTER);
        bigPlaceholder.setCompoundDrawablePadding(dp(8));
        cover.addView(bigPlaceholder, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        bigCover = new ImageView(getContext());
        bigCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.addView(bigCover, match());

        LinearLayout info = new LinearLayout(getContext());
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(landscape ? Gravity.CENTER_HORIZONTAL : Gravity.START);
        LinearLayout.LayoutParams il = landscape ? new LinearLayout.LayoutParams(-1, -2) : new LinearLayout.LayoutParams(0, -2, 1f);
        if (landscape) {
            il.topMargin = dp(12);
        } else {
            il.leftMargin = dp(16);
        }
        p.addView(info, il);

        bigName = text("", 19, DuoTheme.TEXT, true);
        bigName.setGravity(landscape ? Gravity.CENTER : Gravity.START);
        // One line, so the buttons below never get pushed out of the screen.
        bigName.setMaxLines(1);
        bigName.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(bigName, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout stats = row();
        stats.setGravity(landscape ? Gravity.CENTER : Gravity.START);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = dp(10);
        info.addView(stats, sl);
        statTime = stat(stats, "PLAYED");
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(dp(26), 1);
        stats.addView(new View(getContext()), gap);
        statSessions = stat(stats, "SESSIONS");

        LinearLayout buttons = row();
        buttons.setGravity(landscape ? Gravity.CENTER : Gravity.START);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, dp(36));
        bl.topMargin = dp(12);
        info.addView(buttons, bl);
        // Outline only (no accent fill), like the other buttons.
        playButton = button("Open", "X", false, v -> {
            App a = selectedApp();
            if (a != null) {
                host.start(a);
            }
        });
        buttons.addView(playButton, new LinearLayout.LayoutParams(-2, -1));
        quitButton = button("Quit", "≡", false, v -> {
            App a = selectedApp();
            if (a != null) {
                host.quit(a);
            }
        });
        LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(-2, -1);
        ol.leftMargin = dp(8);
        buttons.addView(quitButton, ol);
        // Play / Resume (X) and Quit (Menu ≡) are pressed on the controller, not selected.
        playButton.setFocusable(false);
        quitButton.setFocusable(false);
        pinButton = button("☆", null, false, v -> {
            App a = selectedApp();
            if (a != null) {
                togglePin(a);
            }
        });
        pinButton.setContentDescription("Favourite");
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-2, -1);
        pl.leftMargin = dp(8);
        return p;
    }

    private TextView stat(LinearLayout parent, String label) {
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView v = text("–", 17, DuoTheme.TEXT, true);
        col.addView(v);
        TextView l = text(label, 9, DuoTheme.TEXT2, false);
        l.setLetterSpacing(0.15f);
        l.setPadding(0, dp(3), 0, 0);
        col.addView(l);
        parent.addView(col);
        return v;
    }

    private void renderDetail() {
        App a = selectedApp();
        ComputerDetails c = host.computer();
        boolean has = a != null;
        playButton.setVisibility(has ? VISIBLE : INVISIBLE);
        quitButton.setVisibility(has && a.running ? VISIBLE : GONE);
        pinButton.setVisibility(GONE);
        if (has && c != null) {
            boolean pinned = DuoPlayStats.isPinned(getContext(), c.uuid, a.id);
            TextView pt = (TextView) pinButton.getTag();
            pt.setText(pinned ? "★" : "☆");
            pt.setTextColor(pinned ? accent : DuoTheme.TEXT);
        }
        optionsButton.setEnabled(has);
        optionsButton.setAlpha(has ? 1f : 0.45f);
        if (!has) {
            bigName.setText("");
            statTime.setText("–");
            statSessions.setText("–");
            bigCover.setTag(null);
            bigCover.setImageDrawable(null);
            bigPlaceholder.setText("");
            return;
        }
        bigName.setText(a.name);
        boolean newDetail = a.id != shownDetailId;
        shownDetailId = a.id;
        if (newDetail) {
            // Cover, name and stats crossfade softly to the newly selected app.
            View coverBox = (View) bigCover.getParent();
            DuoMotion.fadeIn(coverBox, 280);
            coverBox.setScaleX(0.97f);
            coverBox.setScaleY(0.97f);
            coverBox.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(DuoMotion.EASE).start();
            DuoMotion.fadeIn(bigName, 260);
        }
        ((TextView) playButton.getTag()).setText(a.running ? "Resume" : "Open");
        long ms = c == null ? 0 : DuoPlayStats.time(getContext(), c.uuid, a.id);
        int sessions = c == null ? 0 : DuoPlayStats.sessions(getContext(), c.uuid, a.id);
        statTime.setText(ms > 0 ? DuoPlayStats.duration(ms) : "–");
        if (newDetail) {
            DuoMotion.countUp(statSessions, sessions);
        } else {
            statSessions.setText(String.valueOf(sessions));
        }
        boolean desk = DuoPlayStats.isApp(getContext(), c == null ? null : c.uuid, a.id, a.name);
        bigPlaceholder.setText(desk ? a.name.toUpperCase() : placeholderText(a));
        bigPlaceholder.setTextSize(desk ? 13 : 28);
        bigPlaceholder.setCompoundDrawablesWithIntrinsicBounds(null,
                desk ? tinted(R.drawable.ic_duo_monitor, DuoTheme.TEXT) : null, null, null);
        if (c != null) {
            DuoCovers.into(getContext(), c.uuid, a.id, bigCover, bigPlaceholder, dp(240));
        }
    }

    private void fillChips(ComputerDetails c) {
        chips.removeAllViews();
        String addr = null;
        if (c != null) {
            ComputerDetails.AddressTuple t = c.activeAddress != null ? c.activeAddress
                    : c.localAddress != null ? c.localAddress : c.manualAddress;
            addr = t == null ? null : t.address;
        }
        label(chips, "HOST");
        chip(chips, addr == null ? "–" : addr);
        View sep = new View(getContext());
        sep.setBackgroundColor(DuoTheme.LINE);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(1), dp(16));
        sp.leftMargin = dp(4);
        sp.rightMargin = dp(10);
        chips.addView(sep, sp);
        label(chips, "STREAM");
        PreferenceConfiguration p = PreferenceConfiguration.readPreferences(getContext());
        chip(chips, p.width + "×" + p.height);
        chip(chips, Math.round(p.fps) + " FPS");
        chip(chips, Math.round(p.bitrate / 1000f) + " Mbps");
        switch (p.videoFormat) {
            case FORCE_HEVC: chip(chips, "HEVC"); break;
            case FORCE_AV1: chip(chips, "AV1"); break;
            case FORCE_H264: chip(chips, "H.264"); break;
            default: chip(chips, "Auto codec"); break;
        }
        int ch = p.audioConfiguration == null ? 2 : p.audioConfiguration.channelCount;
        chip(chips, ch >= 8 ? "7.1" : ch >= 6 ? "5.1" : "Stereo");
    }

    private void label(LinearLayout parent, String s) {
        TextView t = text(s, 9.5f, DuoTheme.TEXT2, false);
        t.setLetterSpacing(0.15f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(6);
        parent.addView(t, lp);
    }

    private void chip(LinearLayout parent, String s) {
        TextView t = text(s, 10.5f, DuoTheme.TEXT, true);
        t.setPadding(dp(8), dp(4), dp(8), dp(4));
        t.setBackground(rounded(DuoTheme.CARD, DuoTheme.LINE, 6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(6);
        parent.addView(t, lp);
    }

    // ================= helpers (same look as DuoHomeView) =================

    private StateListDrawable rowBackground() {
        StateListDrawable s = new StateListDrawable();
        GradientDrawable focused = rounded(DuoTheme.withAlpha(accent, 0.07f), 0, 10);
        focused.setStroke(Math.round(1.5f * d), DuoTheme.focus(accent));
        s.addState(new int[]{android.R.attr.state_focused}, focused);
        s.addState(new int[]{android.R.attr.state_selected}, focused);
        s.addState(new int[]{android.R.attr.state_pressed}, rounded(0x14FFFFFF, 0, 10));
        s.addState(new int[]{}, rounded(0, 0, 10));
        s.setEnterFadeDuration(140);
        s.setExitFadeDuration(180);
        return s;
    }

    private View logoBox() {
        return DuoTheme.appIcon(getContext(), accent, 30);
    }

    private android.graphics.drawable.Drawable tinted(int res, int color) {
        android.graphics.drawable.Drawable dr = getContext().getDrawable(res).mutate();
        dr.setTint(color);
        return dr;
    }

    private View button(String label, String glyph, boolean primaryStyle, OnClickListener l) {
        LinearLayout b = row();
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), 0, dp(16), 0);
        // Secondary buttons: light, see-through, so the waves show.
        int bg = primaryStyle ? DuoTheme.focus(accent) : 0x17FFFFFF;
        StateListDrawable s = new StateListDrawable();
        GradientDrawable ring = rounded(bg, 0, 10);
        ring.setStroke(Math.round(2 * d), primaryStyle ? DuoTheme.TEXT : DuoTheme.focus(accent));
        s.addState(new int[]{android.R.attr.state_focused}, ring);
        s.addState(new int[]{android.R.attr.state_pressed}, rounded(DuoTheme.mix(Color.WHITE, bg, 0.08f), 0, 10));
        s.addState(new int[]{}, rounded(bg, primaryStyle ? 0 : 0x29FFFFFF, 10));
        s.setEnterFadeDuration(140);
        s.setExitFadeDuration(180);
        b.setBackground(s);
        if (glyph != null) {
            View g = glyph(glyph);
            // 3 dp of glow around the 17 dp button.
            LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(23), dp(23));
            gp.leftMargin = -dp(3);
            gp.rightMargin = dp(5);
            b.addView(g, gp);
        }
        TextView t = text(label, 13, primaryStyle ? DuoTheme.onAccent(DuoTheme.focus(accent)) : DuoTheme.TEXT, primaryStyle);
        b.addView(t);
        b.setTag(t);
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnClickListener(l);
        DuoMotion.button(b);
        return b;
    }

    private View glyph(String letter) {
        int c;
        switch (letter) {
            case "A": c = 0xFF6FCF7F; break;
            case "B": c = 0xFFE0645A; break;
            case "X": c = 0xFF5A9BE0; break;
            case "≡": c = 0xFF3A434A; break;
            case "⧉": c = 0xFF3A434A; break;
            default: c = 0xFFE8C15A; break;
        }
        TextView g = text(letter, 9.5f, ("≡".equals(letter) || "⧉".equals(letter)) ? Color.WHITE : Color.BLACK, true);
        g.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(c);
        g.setBackground(DuoTheme.padButton(c, d));
        glyphs.add(g);
        return g;
    }

    private View keyPill(String label) {
        TextView t = text(label, 9, DuoTheme.TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(dp(26));
        t.setPadding(dp(5), dp(2), dp(5), dp(2));
        t.setBackground(rounded(DuoTheme.CARD_HIGH, DuoTheme.LINE, 5));
        // Hint only: LB/RB are pressed on the controller, never selected.
        t.setFocusable(false);
        glyphs.add(t);
        return t;
    }

    private GradientDrawable dot(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        g.setSize(dp(6), dp(6));
        return g;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(sp * scale);
        t.setTextColor(color);
        t.setTypeface((bold ? DuoFonts.semibold() : DuoFonts.regular()));
        t.setIncludeFontPadding(false);
        return t;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(getContext());
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private GradientDrawable rounded(int color, int stroke, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusDp * d);
        if (stroke != 0) {
            g.setStroke(Math.max(1, Math.round(d)), stroke);
        }
        return g;
    }

    private static LayoutParams match() {
        return new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(float v) {
        return Math.round(v * d);
    }

    private void updateClock() {
        Date now = new Date();
        clock.setText(DateFormat.getTimeFormat(getContext()).format(now));
        String date = DateFormat.getDateFormat(getContext()).format(now);
        dateLine.setText(batteryPct < 0 ? date : date + "  ·  " + (charging ? "⚡ " : "") + batteryPct + " %");
    }

    private void updateGlyphs() {
        boolean pad = false;
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice dev = InputDevice.getDevice(id);
            if (dev != null && !dev.isVirtual()) {
                int s = dev.getSources();
                if ((s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                        || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
                    pad = true;
                }
            }
        }
        for (View g : glyphs) {
            g.setVisibility(pad ? VISIBLE : GONE);
        }
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        updateGlyphs();
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        updateGlyphs();
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
        updateGlyphs();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        f.addAction(Intent.ACTION_BATTERY_CHANGED);
        Intent sticky = getContext().registerReceiver(receiver, f);
        if (sticky != null) {
            receiver.onReceive(getContext(), sticky);
        }
        ((InputManager) getContext().getSystemService(Context.INPUT_SERVICE)).registerInputDeviceListener(this, null);
        updateGlyphs();
    }

    @Override
    protected void onDetachedFromWindow() {
        try {
            getContext().unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
        }
        ((InputManager) getContext().getSystemService(Context.INPUT_SERVICE)).unregisterInputDeviceListener(this);
        super.onDetachedFromWindow();
    }

    public boolean handleMotion(android.view.MotionEvent e) {
        return DuoPadNav.onMotion(this, e, list.getChildCount() > 1 ? list.getChildAt(1) : playButton);
    }

    private View bottomFirst;

    /** Explicit jumps between the list, the detail buttons and the bottom row. */
    private boolean jump(int key) {
        View f = findFocus();
        if (f == null) {
            return false;
        }
        boolean inList = f.getParent() == list;
        if (inList && (key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_DPAD_LEFT)) {
            return true; // the detail buttons work with X / Menu
        }
        if (inList && (key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_DPAD_UP)) {
            // Step row by row in list order (LAST PLAYED → FAVOURITES → ALL), never skipping a section.
            int i = list.indexOfChild(f);
            int step = key == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1;
            for (int j = i + step; j >= 0 && j < list.getChildCount(); j += step) {
                View v = list.getChildAt(j);
                if (v.isFocusable() && v.getVisibility() == VISIBLE) {
                    v.requestFocus();
                    v.requestRectangleOnScreen(new android.graphics.Rect(0, 0, v.getWidth(), v.getHeight()));
                    return true;
                }
            }
            return true;
        }
        if ((f == playButton || f == quitButton || f == pinButton) && key == KeyEvent.KEYCODE_DPAD_LEFT
                && f == playButton) {
            View row = selectedRow();
            return row != null && row.requestFocus();
        }
        if ((f == playButton || f == quitButton || f == pinButton) && key == KeyEvent.KEYCODE_DPAD_DOWN) {
            return optionsButton.requestFocus();
        }
        return false;
    }

    private View lastRow() {
        for (int i = list.getChildCount() - 1; i >= 0; i--) {
            if (list.getChildAt(i).getTag() instanceof App) {
                return list.getChildAt(i);
            }
        }
        return null;
    }

    private View selectedRow() {
        for (int i = 0; i < list.getChildCount(); i++) {
            View v = list.getChildAt(i);
            if (v.getTag() instanceof App && ((App) v.getTag()).id == selectedId) {
                return v;
            }
        }
        return null;
    }

    /** Controller shortcuts; returns true if handled. */
    public boolean handleKey(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN && jump(e.getKeyCode())) {
            return true;
        }
        if (DuoPadNav.onKey(this, e, list.getChildCount() > 1 ? list.getChildAt(1) : playButton)) {
            return true;
        }
        int k = e.getKeyCode();
        boolean ours = k == KeyEvent.KEYCODE_BUTTON_L1 || k == KeyEvent.KEYCODE_BUTTON_R1 || k == KeyEvent.KEYCODE_BUTTON_X
                || k == KeyEvent.KEYCODE_BUTTON_START || k == KeyEvent.KEYCODE_BUTTON_SELECT || k == KeyEvent.KEYCODE_BUTTON_Y || k == KeyEvent.KEYCODE_BUTTON_B || k == KeyEvent.KEYCODE_BUTTON_A;
        if (!ours) {
            return false;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() > 0) {
            return true;
        }
        switch (k) {
            case KeyEvent.KEYCODE_BUTTON_L1:
                setTab((tab + 2) % 3);
                break;
            case KeyEvent.KEYCODE_BUTTON_R1:
                setTab((tab + 1) % 3);
                break;
            case KeyEvent.KEYCODE_BUTTON_Y:
                host.settings();
                break;
            case KeyEvent.KEYCODE_BUTTON_X:
                if (playButton.getVisibility() == VISIBLE) {
                    DuoMotion.tap(playButton);
                    playButton.performClick();
                }
                break;
            case KeyEvent.KEYCODE_BUTTON_START:
                if (quitButton.getVisibility() == VISIBLE) {
                    DuoMotion.tap(quitButton);
                    quitButton.performClick();
                }
                break;
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                optionsButton.performClick();
                break;
            case KeyEvent.KEYCODE_BUTTON_B:
                host.back();
                break;
            case KeyEvent.KEYCODE_BUTTON_A: {
                View f = findFocus();
                if (f != null) {
                    DuoMotion.tap(f);
                    f.performClick();
                } else {
                    playButton.performClick();
                }
                break;
            }
        }
        return true;
    }
}
