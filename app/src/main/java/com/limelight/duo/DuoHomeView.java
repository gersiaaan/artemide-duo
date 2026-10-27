package com.limelight.duo;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.hardware.input.InputManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

import com.limelight.BuildConfig;
import com.limelight.R;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Start screen of Artemide Duo, laid out after StreamLight's host page: one host at a time
 * (LB/RB or the chips switch), a big host card, the last played app with "Play again",
 * and buttons along the bottom. Works with touch and with a controller (D-pad focus,
 * A/B/X/Y, LB/RB); the button glyphs only show while a controller is connected.
 */
@SuppressLint("ViewConstructor")
public class DuoHomeView extends FrameLayout implements InputManager.InputDeviceListener {

    public interface Host {
        void open(ComputerDetails c);
        void options(ComputerDetails c);
        void playAgain(ComputerDetails c, DuoPlayStats.Last last);
        void resume(ComputerDetails c);
        void quit(ComputerDetails c);
        void addHost();
        void refreshHosts();
        void profiles();
        void settings();
        void exit();
    }

    private static final String KEY_SELECTED = "duo_home_selected_host";
    private static boolean splashShown;

    private final Host host;
    private final float d;
    private final float scale;
    private final int accent;
    private final boolean landscape;
    private final List<ComputerDetails> computers = new ArrayList<>();
    private final List<View> glyphs = new ArrayList<>();
    private final java.util.Map<View, View> focusA = new java.util.HashMap<>();
    private String selectedUuid;

    private final LinearLayout content;
    private final LinearLayout hostChips;
    private final TextView clock, dateLine;
    private TextView badge, name, subline, address, addressLabel;
    private LinearLayout chips;
    private View cardView;
    private boolean addMode;
    private View profilesBtn, optionsBtn, searchRow;
    private TextView primaryLabel;
    private View primary;
    private LinearLayout lastPanel;
    private TextView lastInfo, lastName;
    private ImageView lastCover;
    private TextView lastPlaceholder;
    private View playAgain, quitRunning, settingsBtn, quitAllBtn;
    private int coverWidthDp;
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

    public DuoHomeView(Context context, Host host) {
        super(context);
        this.host = host;
        this.scale = DuoTheme.uiScale(context);
        this.d = context.getResources().getDisplayMetrics().density * scale;
        this.accent = DuoTheme.accent(context);
        this.landscape = context.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        selectedUuid = PreferenceManager.getDefaultSharedPreferences(context).getString(KEY_SELECTED, null);
        setClickable(true); // nothing falls through to the old grid below

        int splashMs = splashShown ? 0 : DuoTheme.splashMs(context);
        addView(new DuoWavesView(context, !splashShown), match());

        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(18), dp(22), dp(12));
        addView(content, match());

        // ---------- header ----------
        LinearLayout header = row();
        content.addView(header, new LinearLayout.LayoutParams(-1, -2));
        header.setGravity(Gravity.TOP);
        header.addView(logoBox(30), new LinearLayout.LayoutParams(dp(30), dp(30)));
        LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
        tp.leftMargin = dp(10);
        header.addView(titles, tp);
        TextView title = text("", 16, DuoTheme.TEXT, true);
        title.setText(brand());
        title.setLetterSpacing(0.18f);
        titles.addView(title);
        titles.addView(text("an Artemide fork for Surface Duo and Galaxy Tab S6", 10.5f, DuoTheme.TEXT2, false));
        LinearLayout clockCol = new LinearLayout(context);
        clockCol.setOrientation(LinearLayout.VERTICAL);
        clockCol.setGravity(Gravity.END);
        header.addView(clockCol);
        clock = text("", 34, DuoTheme.TEXT, false);
        clock.setGravity(Gravity.END);
        clockCol.addView(clock);
        dateLine = text("", 13.5f, DuoTheme.TEXT2, false);
        dateLine.setGravity(Gravity.END);
        clockCol.addView(dateLine);

        // ---------- host bar ----------
        LinearLayout hostBar = row();
        LinearLayout.LayoutParams hb = new LinearLayout.LayoutParams(-1, dp(32));
        hb.topMargin = dp(14);
        content.addView(hostBar, hb);
        View lb = keyPill("LB");
        lb.setOnClickListener(v -> cycle(-1));
        hostBar.addView(lb);
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        hostChips = row();
        scroll.addView(hostChips);
        // Host tabs: LB / RB or touch only, never a stick target (incl. the scroll container).
        scroll.setFocusable(false);
        scroll.setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            scroll.setDefaultFocusHighlightEnabled(false);
        }
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -1);
        sp.leftMargin = dp(10);
        sp.rightMargin = dp(10);
        hostBar.addView(scroll, sp);
        View rb = keyPill("RB");
        rb.setOnClickListener(v -> cycle(1));
        hostBar.addView(rb);

        // ---------- body ----------
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, 0, 1f);
        bp.topMargin = dp(14);
        content.addView(body, bp);
        View card = buildCard();
        cardView = card;
        View right = buildLastPanel();
        if (landscape) {
            body.addView(card, new LinearLayout.LayoutParams(0, -1, 1.55f));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -1, 1f);
            rp.leftMargin = dp(18);
            body.addView(right, rp);
        } else {
            body.addView(card, new LinearLayout.LayoutParams(-1, 0, 1.1f));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, 0, 1f);
            rp.topMargin = dp(16);
            body.addView(right, rp);
        }

        // ---------- bottom buttons ----------
        LinearLayout bottom = row();
        LinearLayout.LayoutParams bt = new LinearLayout.LayoutParams(-1, dp(36));
        bt.topMargin = dp(14);
        content.addView(bottom, bt);
        View settings = button("Settings", "Y", false, v -> host.settings());
        settingsBtn = settings;
        bottom.addView(settings, new LinearLayout.LayoutParams(-2, -1));
        View exit = button("Exit", "B", false, v -> host.exit());
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-2, -1);
        ep.leftMargin = dp(8);
        bottom.addView(exit, ep);
        // Bottom row works with Y / B; the stick never lands on it.
        settings.setFocusable(false);
        exit.setFocusable(false);
        // Quit everything running on this PC (Menu ≡), no questions asked.
        quitAllBtn = button("Quit all sessions", "≡", false, v -> {
            ComputerDetails c = selected();
            if (c != null) {
                host.quit(c);
            }
        });
        quitAllBtn.setFocusable(false);
        quitAllBtn.setVisibility(GONE);
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(-2, -1);
        qp.leftMargin = dp(8);
        bottom.addView(quitAllBtn, qp);
        bottom.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1f));
        bottom.addView(text(version(), 10.5f, 0xFF4A4F57, false));

        updateGlyphs();
        updateClock();
        render();

        // ---------- start screen ----------
        if (splashMs > 0) {
            splashShown = true;
            content.setAlpha(0f);
            final View splash = buildSplash();
            addView(splash, match());
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                splash.animate().alpha(0f).setDuration(400).withEndAction(() -> removeView(splash)).start();
                content.setTranslationY(dp(8));
                content.animate().alpha(1f).translationY(0).setDuration(500)
                        .setInterpolator(new PathInterpolator(0.32f, 0.72f, 0f, 1f)).start();
            }, splashMs);
        } else {
            splashShown = true;
        }
    }

    // ================= data =================

    public void setComputers(List<ComputerDetails> list) {
        // On the "Add a host" page a newly found PC opens right away as its tab.
        ComputerDetails found = null;
        if (addMode) {
            for (ComputerDetails n : list) {
                boolean known = false;
                for (ComputerDetails o : computers) {
                    if (o.uuid != null && o.uuid.equals(n.uuid)) {
                        known = true;
                        break;
                    }
                }
                if (!known) {
                    found = n;
                    break;
                }
            }
        }
        computers.clear();
        computers.addAll(list);
        if (found != null && found.uuid != null) {
            select(found);
            return;
        }
        render();
    }

    public ComputerDetails selectedComputer() {
        return addMode ? null : selected();
    }

    private ComputerDetails selected() {
        if (computers.isEmpty()) {
            return null;
        }
        for (ComputerDetails c : computers) {
            if (c.uuid != null && c.uuid.equals(selectedUuid)) {
                return c;
            }
        }
        return computers.get(0);
    }

    private void select(ComputerDetails c) {
        addMode = false;
        selectedUuid = c.uuid;
        PreferenceManager.getDefaultSharedPreferences(getContext()).edit().putString(KEY_SELECTED, c.uuid).apply();
        render();
    }

    private void cycle(int dir) {
        // Tabs: every host, then "Add a host".
        int n = computers.size() + 1;
        int i = addMode ? n - 1 : computers.indexOf(selected());
        int j = (i + dir + n) % n;
        if (j == n - 1) {
            addMode = true;
            render();
        } else {
            select(computers.get(j));
        }
        // The host card slides in from the side you switched to.
        DuoMotion.slideIn(cardView, dir);
        DuoMotion.fadeIn(lastPanel, 360);
    }

    /** Re-reads the last played app (e.g. after returning from a stream). */
    public void refresh() {
        render();
        // Coming back (from the app list or a stream): start on the main button.
        if (!addMode && primary != null) {
            primary.post(() -> {
                if (!primary.requestFocus() && DuoPadNav.hasGamepad()) {
                    primary.requestFocusFromTouch();
                }
            });
        }
    }

    private void render() {
        ComputerDetails c = selected();

        // host chips
        hostChips.removeAllViews();
        for (ComputerDetails pc : computers) {
            boolean sel = pc == c && !addMode;
            LinearLayout chip = row();
            chip.setPadding(dp(12), 0, dp(12), 0);
            chip.setBackground(focusable(sel ? DuoTheme.mix(accent, DuoTheme.CARD, 0.16f) : 0,
                    sel ? DuoTheme.withAlpha(accent, 0.45f) : 0, 9));
            View dot = new View(getContext());
            dot.setBackground(circle(stateColor(pc)));
            chip.addView(dot, new LinearLayout.LayoutParams(dp(7), dp(7)));
            TextView t = text(pc.name == null ? "?" : pc.name.toUpperCase(), 12, sel ? DuoTheme.TEXT : DuoTheme.TEXT2, true);
            t.setLetterSpacing(0.04f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = dp(8);
            chip.addView(t, lp);
            // Host tabs switch with LB/RB (or touch); the stick never lands on them.
            chip.setFocusable(false);
            chip.setOnClickListener(v -> select(pc));
            DuoMotion.press(chip);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -1);
            cp.rightMargin = dp(6);
            hostChips.addView(chip, cp);
        }
        boolean addSel = addMode || c == null;
        TextView add = text("+  Add a host", 12, addSel ? DuoTheme.TEXT : DuoTheme.TEXT2, addSel);
        add.setGravity(Gravity.CENTER_VERTICAL);
        add.setPadding(dp(10), 0, dp(10), 0);
        add.setBackground(focusable(addSel ? DuoTheme.mix(accent, DuoTheme.CARD, 0.16f) : 0,
                addSel ? DuoTheme.withAlpha(accent, 0.45f) : 0, 9));
        add.setFocusable(false);
        add.setOnClickListener(v -> {
            addMode = true;
            render();
        });
        hostChips.addView(add, new LinearLayout.LayoutParams(-2, -1));

        // card
        boolean addPage = c == null || addMode;
        badge.setVisibility(addPage ? GONE : VISIBLE);
        searchRow.setVisibility(addPage ? VISIBLE : GONE);
        optionsBtn.setVisibility(addPage ? GONE : VISIBLE);
        ((TextView) profilesBtn.getTag()).setText(addPage ? "Refresh" : "Profiles");
        profilesBtn.setOnClickListener(addPage ? v -> host.refreshHosts() : v -> host.profiles());
        if (addPage) {
            // StreamLight's "Add a host" tab: search runs in the background, manual add by address.
            name.setText("Add a host");
            subline.setText("Searching your local network…");
            chips.setVisibility(GONE);
            address.setVisibility(GONE);
            addressLabel.setVisibility(GONE);
            primaryLabel.setText("Add a host");
            primary.setOnClickListener(v -> host.addHost());
            lastPanel.setVisibility(INVISIBLE);
            if (quitAllBtn != null) {
                quitAllBtn.setVisibility(GONE);
            }
            return;
        }
        String state;
        int stateColor = stateColor(c);
        String sub;
        String action = "Open";
        switch (c.state) {
            case ONLINE:
                state = "ONLINE";
                if (c.pairState != PairingManager.PairState.PAIRED) {
                    sub = "Not paired yet";
                    action = "Pair";
                } else {
                    sub = c.runningGameId != 0 ? "A session is running" : "Ready to stream";
                }
                break;
            case OFFLINE:
                state = "OFFLINE";
                sub = "Offline";
                action = c.macAddress != null ? "Wake" : "Open";
                break;
            default:
                state = "CHECKING";
                sub = "Checking…";
                break;
        }
        badge.setText(state);
        badge.setCompoundDrawablesRelativeWithIntrinsicBounds(dotDrawable(stateColor), null, null, null);
        name.setText(c.name);
        subline.setText(sub);
        chips.setVisibility(VISIBLE);
        fillChips();
        String addr = addressOf(c);
        address.setText(addr == null ? "–" : addr);
        address.setVisibility(VISIBLE);
        addressLabel.setVisibility(VISIBLE);
        primaryLabel.setText(action);
        primary.setOnClickListener(v -> host.open(c));

        if (quitAllBtn != null) {
            boolean anyRunning = c.state == ComputerDetails.State.ONLINE && c.runningGameId != 0;
            quitAllBtn.setVisibility(anyRunning ? VISIBLE : GONE);
        }

        // last played
        DuoPlayStats.Last last = DuoPlayStats.last(getContext(), c.uuid);
        lastPanel.setVisibility(VISIBLE);
        if (last == null) {
            lastInfo.setText("NOTHING PLAYED YET");
            lastName.setText("");
            setCover(c, null);
            playAgain.setVisibility(INVISIBLE);
            quitRunning.setVisibility(GONE);
        } else {
            lastInfo.setText(("Last played " + DuoPlayStats.ago(last.when)
                    + (last.totalMs > 0 ? "  ·  " + DuoPlayStats.duration(last.totalMs) + " total" : "")).toUpperCase());
            lastName.setText(last.name);
            setCover(c, last);
            playAgain.setVisibility(VISIBLE);
            boolean ready = c.state == ComputerDetails.State.ONLINE && c.pairState == PairingManager.PairState.PAIRED;
            playAgain.setEnabled(ready);
            playAgain.setAlpha(ready ? 1f : 0.45f);
            boolean running = ready && c.runningGameId != 0;
            // Desktop / remote apps aren't "played": they're opened.
            boolean isApp = DuoPlayStats.isApp(getContext(), c.uuid, last.appId, last.name);
            ((TextView) playAgain.getTag()).setText(running ? "Resume" : isApp ? "Open" : "Play again");
            playAgain.setOnClickListener(v -> {
                if (running) {
                    host.resume(c);
                } else {
                    host.playAgain(c, last);
                }
            });
            // Quitting lives in the bottom bar ("Quit all sessions").
            quitRunning.setVisibility(GONE);
            quitRunning.setOnClickListener(v -> host.quit(c));
        }
    }

    private int stateColor(ComputerDetails c) {
        if (c.state == ComputerDetails.State.ONLINE) {
            return DuoTheme.ONLINE;
        }
        return c.state == ComputerDetails.State.OFFLINE ? DuoTheme.OFFLINE : DuoTheme.WARN;
    }

    private static String addressOf(ComputerDetails c) {
        ComputerDetails.AddressTuple a = c.activeAddress != null ? c.activeAddress
                : c.localAddress != null ? c.localAddress
                : c.manualAddress != null ? c.manualAddress : c.remoteAddress;
        return a == null ? null : a.address;
    }

    private void fillChips() {
        chips.removeAllViews();
        PreferenceConfiguration p = PreferenceConfiguration.readPreferences(getContext());
        List<String> l = new ArrayList<>();
        l.add(p.width + "×" + p.height);
        l.add(Math.round(p.fps) + " FPS");
        l.add(Math.round(p.bitrate / 1000f) + " Mbps");
        switch (p.videoFormat) {
            case FORCE_HEVC: l.add("HEVC"); break;
            case FORCE_AV1: l.add("AV1"); break;
            case FORCE_H264: l.add("H.264"); break;
            default: l.add("Auto codec"); break;
        }
        int ch = p.audioConfiguration == null ? 2 : p.audioConfiguration.channelCount;
        l.add(ch >= 8 ? "7.1" : ch >= 6 ? "5.1" : "Stereo");
        for (String s : l) {
            TextView t = text(s, 10.5f, DuoTheme.TEXT, true);
            t.setPadding(dp(8), dp(3), dp(8), dp(3));
            t.setBackground(rounded(DuoTheme.CARD, DuoTheme.LINE, 6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(6);
            chips.addView(t, lp);
        }
    }

    private void setCover(ComputerDetails c, DuoPlayStats.Last last) {
        boolean desk = last == null || DuoPlayStats.isApp(getContext(), c.uuid, last.appId, last.name);
        lastPlaceholder.setText(last == null ? "" : desk ? last.name.toUpperCase() : initials(last.name));
        lastPlaceholder.setTextSize(desk ? 11 : 26);
        lastPlaceholder.setCompoundDrawablesWithIntrinsicBounds(null,
                desk && last != null ? tinted(R.drawable.ic_duo_monitor) : null, null, null);
        if (last == null) {
            lastCover.setTag(null);
            lastCover.setImageDrawable(null);
            lastCover.setVisibility(INVISIBLE);
            lastPlaceholder.setVisibility(VISIBLE);
            return;
        }
        // Real box art for every app (Desktop too, when the host provides one).
        DuoCovers.into(getContext(), c.uuid, last.appId, lastCover, lastPlaceholder, dp(coverWidthDp));
    }

    private static String initials(String n) {
        StringBuilder b = new StringBuilder();
        for (String w : n.split("\\s+")) {
            if (!w.isEmpty() && b.length() < 3) {
                b.append(Character.toUpperCase(w.charAt(0)));
            }
        }
        return b.toString();
    }

    // ================= building blocks =================

    private View buildCard() {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(18), dp(20), dp(18));
        // Slightly see-through so the waves show behind it (StreamLight's host card).
        card.setBackground(rounded(DuoTheme.withAlpha(DuoTheme.CARD, 0.72f), DuoTheme.LINE, 16));

        badge = text("", 10.5f, DuoTheme.TEXT, true);
        badge.setLetterSpacing(0.1f);
        badge.setCompoundDrawablePadding(dp(6));
        badge.setPadding(dp(9), dp(3), dp(9), dp(3));
        badge.setBackground(rounded(0, DuoTheme.LINE, 6));
        card.addView(badge, new LinearLayout.LayoutParams(-2, -2));

        name = text("", landscape ? 34 : 30, DuoTheme.TEXT, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2);
        np.topMargin = dp(14);
        card.addView(name, np);

        subline = text("", 12, DuoTheme.TEXT2, false);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = dp(10);
        card.addView(subline, sl);
        chips = new LinearLayout(getContext());
        chips.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView cs = new HorizontalScrollView(getContext());
        cs.setHorizontalScrollBarEnabled(false);
        cs.addView(chips);
        // Info only: never takes focus.
        cs.setFocusable(false);
        cs.setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.topMargin = dp(6);
        card.addView(cs, cl);

        LinearLayout sr = row();
        android.widget.ProgressBar spin = new android.widget.ProgressBar(getContext());
        spin.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(accent));
        sr.addView(spin, new LinearLayout.LayoutParams(dp(16), dp(16)));
        TextView sh = text("Hosts found on the network appear as tabs above.", 12, DuoTheme.TEXT2, false);
        LinearLayout.LayoutParams shp = new LinearLayout.LayoutParams(-2, -2);
        shp.leftMargin = dp(10);
        sr.addView(sh, shp);
        searchRow = sr;
        LinearLayout.LayoutParams srp = new LinearLayout.LayoutParams(-1, -2);
        srp.topMargin = dp(18);
        card.addView(sr, srp);

        addressLabel = text("ADDRESS", 9.5f, DuoTheme.TEXT2, false);
        addressLabel.setLetterSpacing(0.15f);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-2, -2);
        al.topMargin = dp(16);
        card.addView(addressLabel, al);
        address = text("", 16, DuoTheme.TEXT, true);
        card.addView(address);

        card.addView(new View(getContext()), new LinearLayout.LayoutParams(1, 0, 1f));

        LinearLayout buttons = row();
        card.addView(buttons, new LinearLayout.LayoutParams(-1, dp(40)));
        primary = button("Open", "A", true, null);
        primaryLabel = (TextView) primary.getTag();
        buttons.addView(primary, new LinearLayout.LayoutParams(-2, -1));
        View profiles = button("Profiles", null, false, v -> host.profiles());
        profilesBtn = profiles;
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-2, -1);
        pp.leftMargin = dp(8);
        buttons.addView(profiles, pp);
        View options = button("Options", "⧉", false, v -> {
            ComputerDetails c = selected();
            if (c != null) {
                host.options(c);
            }
        });
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(-2, -1);
        op.leftMargin = dp(8);
        buttons.addView(options, op);
        optionsBtn = options;
        primary.post(primary::requestFocus);
        return card;
    }

    private View buildLastPanel() {
        lastPanel = new LinearLayout(getContext());
        lastPanel.setOrientation(LinearLayout.VERTICAL);
        lastPanel.setGravity(Gravity.CENTER_HORIZONTAL);

        lastInfo = text("", 9.5f, DuoTheme.TEXT, true);
        lastInfo.setLetterSpacing(0.08f);
        lastInfo.setPadding(dp(10), dp(4), dp(10), dp(4));
        lastInfo.setBackground(rounded(0, DuoTheme.LINE, 6));
        lastInfo.setSingleLine(true);
        lastInfo.setEllipsize(TextUtils.TruncateAt.END);
        lastPanel.addView(lastInfo, new LinearLayout.LayoutParams(-2, -2));

        FrameLayout coverBox = new FrameLayout(getContext());
        int cw = Math.round((landscape ? 128 : 108) * (scale > 1.15f ? 1.25f : 1.2f));
        coverWidthDp = cw;
        // Box art proportions (about 1 : 1.41, like Moonlight's covers).
        LinearLayout.LayoutParams cb = new LinearLayout.LayoutParams(dp(cw), dp(cw * 1.41f));
        cb.topMargin = dp(14);
        lastPanel.addView(coverBox, cb);
        coverBox.setBackground(DuoCovers.placeholderBackground(accent, 12 * d));
        coverBox.setForeground(rounded(0, DuoTheme.LINE, 12));
        coverBox.setClipToOutline(true);
        coverBox.setElevation(dp(10));
        lastCover = new ImageView(getContext());
        lastCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        coverBox.addView(lastCover, match());
        lastPlaceholder = text("", 11, DuoTheme.TEXT, true);
        lastPlaceholder.setLetterSpacing(0.15f);
        lastPlaceholder.setGravity(Gravity.CENTER);
        lastPlaceholder.setCompoundDrawablePadding(dp(8));
        coverBox.addView(lastPlaceholder, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        lastName = text("", 19, DuoTheme.TEXT, true);
        lastName.setGravity(Gravity.CENTER);
        lastName.setSingleLine(true);
        lastName.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams ln = new LinearLayout.LayoutParams(-1, -2);
        ln.topMargin = dp(14);
        lastPanel.addView(lastName, ln);

        LinearLayout actions = row();
        actions.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams pa = new LinearLayout.LayoutParams(-2, dp(36));
        pa.topMargin = dp(12);
        lastPanel.addView(actions, pa);
        playAgain = button("Play again", "X", false, null);
        actions.addView(playAgain, new LinearLayout.LayoutParams(-2, -1));
        quitRunning = button("Quit", "≡", false, null);
        // Resume / Play again (X) and Quit (Menu ≡) are pressed on the controller, not selected.
        playAgain.setFocusable(false);
        quitRunning.setFocusable(false);
        LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(-2, -1);
        ql.leftMargin = dp(8);
        actions.addView(quitRunning, ql);
        quitRunning.setVisibility(GONE);
        return lastPanel;
    }

    private View buildSplash() {
        LinearLayout s = new LinearLayout(getContext());
        s.setOrientation(LinearLayout.VERTICAL);
        s.setGravity(Gravity.CENTER);
        s.setClickable(true);
        s.addView(logoBox(64), new LinearLayout.LayoutParams(dp(64), dp(64)));
        TextView t = text("", 30, DuoTheme.TEXT, true);
        t.setText(brandMixed());
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.topMargin = dp(18);
        s.addView(t, tl);
        s.setAlpha(0f);
        s.animate().alpha(1f).setDuration(1200).start();
        return s;
    }

    private CharSequence brand() {
        android.text.SpannableString s = new android.text.SpannableString("ARTEMIDE DUO");
        s.setSpan(new android.text.style.ForegroundColorSpan(accent), 9, 12, 0);
        return s;
    }

    private CharSequence brandMixed() {
        android.text.SpannableString s = new android.text.SpannableString("Artemide Duo");
        s.setSpan(new android.text.style.ForegroundColorSpan(accent), 9, 12, 0);
        return s;
    }

    /** iOS-style app icon: squircle-ish rounded square, accent gradient, white glyph. */
    private View logoBox(int sizeDp) {
        return DuoTheme.appIcon(getContext(), accent, sizeDp);
    }

    private android.graphics.drawable.Drawable tinted(int res) {
        android.graphics.drawable.Drawable dr = getContext().getDrawable(res).mutate();
        dr.setTint(DuoTheme.TEXT);
        return dr;
    }

    /** Button with optional controller glyph; the label view is stored as the tag. */
    private View button(String label, String glyph, boolean primaryStyle, OnClickListener l) {
        LinearLayout b = row();
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(primaryStyle ? 18 : 16), 0, dp(primaryStyle ? 18 : 16), 0);
        // Secondary buttons: light, see-through, so the waves show.
        int bg = primaryStyle ? DuoTheme.focus(accent) : 0x17FFFFFF;
        b.setBackground(focusable(bg, primaryStyle ? 0 : 0x29FFFFFF, 10));
        // Card buttons: the A glyph follows focus; X etc. show while not focused.
        boolean follow = glyph == null || "A".equals(glyph) || "X".equals(glyph) || "≡".equals(glyph) || "⧉".equals(glyph);
        if (follow) {
            View a = glyph("A");
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(23), dp(23));
            ap.leftMargin = -dp(3);
            ap.rightMargin = dp(5);
            b.addView(a, ap);
            focusA.put(b, a);
            b.setOnFocusChangeListener((v, f) -> updateGlyphs());
        }
        if (glyph != null && !"A".equals(glyph)) {
            View g = glyph(glyph);
            // 3 dp of glow around the 17 dp button.
            LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(23), dp(23));
            gp.leftMargin = -dp(3);
            gp.rightMargin = dp(5);
            b.addView(g, gp);
        }
        TextView t = text(label, primaryStyle ? 14 : 13, primaryStyle ? DuoTheme.onAccent(DuoTheme.focus(accent)) : DuoTheme.TEXT, primaryStyle);
        b.addView(t);
        b.setTag(t);
        b.setFocusable(true);
        b.setClickable(true);
        if (l != null) {
            b.setOnClickListener(l);
        }
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
        TextView g = text(letter, 9.5f, Color.BLACK, true);
        DuoTheme.styleGlyph(g, letter, scale);
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

    private GradientDrawable circle(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    private GradientDrawable dotDrawable(int color) {
        GradientDrawable g = circle(color);
        g.setSize(dp(6), dp(6));
        return g;
    }

    /** Normal look + accent ring while focused (controller) or pressed. */
    private StateListDrawable focusable(int color, int stroke, float radiusDp) {
        StateListDrawable s = new StateListDrawable();
        GradientDrawable ring = rounded(color, 0, radiusDp);
        ring.setStroke(Math.round(2 * d), color == DuoTheme.focus(accent) ? DuoTheme.TEXT : DuoTheme.focus(accent));
        GradientDrawable pressed = rounded(color == 0 ? 0x14FFFFFF : DuoTheme.mix(Color.WHITE, color, 0.08f), stroke, radiusDp);
        s.addState(new int[]{android.R.attr.state_focused}, ring);
        s.addState(new int[]{android.R.attr.state_pressed}, pressed);
        s.addState(new int[]{}, rounded(color, stroke, radiusDp));
        s.setEnterFadeDuration(140);
        s.setExitFadeDuration(180);
        return s;
    }

    private static LayoutParams match() {
        return new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(float v) {
        return Math.round(v * d);
    }

    private static String version() {
        String v = BuildConfig.VERSION_NAME;
        int i = v.lastIndexOf("Duo ");
        return i >= 0 ? "v" + v.substring(i + 4) : v;
    }

    // ================= clock, battery, controller =================

    private void updateClock() {
        Date now = new Date();
        clock.setText(DateFormat.getTimeFormat(getContext()).format(now));
        String date = DateFormat.getDateFormat(getContext()).format(now);
        dateLine.setText(batteryPct < 0 ? date : date + "  ·  " + (charging ? "⚡ " : "") + batteryPct + " %");
    }

    private static boolean hasGamepad() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice dev = InputDevice.getDevice(id);
            if (dev == null || dev.isVirtual()) {
                continue;
            }
            int s = dev.getSources();
            if ((s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
                return true;
            }
        }
        return false;
    }

    private void updateGlyphs() {
        boolean pad = hasGamepad();
        for (View g : glyphs) {
            g.setVisibility(pad ? VISIBLE : GONE);
        }
        for (java.util.Map.Entry<View, View> e : focusA.entrySet()) {
            boolean f = e.getKey().hasFocus() || e.getKey().isFocused();
            e.getValue().setVisibility(pad && f ? VISIBLE : GONE);
            ViewGroup b = (ViewGroup) e.getKey();
            for (int i = 0; i < b.getChildCount(); i++) {
                View ch = b.getChildAt(i);
                if (ch != e.getValue() && glyphs.contains(ch)) {
                    ch.setVisibility(pad && !f ? VISIBLE : GONE);
                }
            }
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
        InputManager im = (InputManager) getContext().getSystemService(Context.INPUT_SERVICE);
        im.registerInputDeviceListener(this, null);
        updateGlyphs();
    }

    @Override
    protected void onDetachedFromWindow() {
        try {
            getContext().unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
        }
        InputManager im = (InputManager) getContext().getSystemService(Context.INPUT_SERVICE);
        im.unregisterInputDeviceListener(this);
        super.onDetachedFromWindow();
    }

    /** Controller shortcuts; returns true if handled. */
    public boolean handleKey(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN && settingsBtn != null) {
            View f = findFocus();
            // Card buttons / play again → down to the bottom row; bottom row → up to Open.
            if (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN && f != null && f != settingsBtn
                    && f.getParent() != settingsBtn.getParent() && (f.getParent() == primary.getParent()
                    || f == playAgain || f == quitRunning)) {
                settingsBtn.requestFocus();
                return true;
            }
            if (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP && f != null && f.getParent() == settingsBtn.getParent()) {
                primary.requestFocus();
                return true;
            }
            // Left / right inside the card's button row: step to the neighbour, nothing else.
            int k = e.getKeyCode();
            if ((k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) && f != null
                    && f.getParent() == primary.getParent()) {
                ViewGroup row = (ViewGroup) f.getParent();
                int step = k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                for (int i = row.indexOfChild(f) + step; i >= 0 && i < row.getChildCount(); i += step) {
                    View n = row.getChildAt(i);
                    if (n.isFocusable() && n.getVisibility() == VISIBLE) {
                        n.requestFocus();
                        DuoMotion.navTick(n);
                        break;
                    }
                }
                return true;
            }
        }
        if (DuoPadNav.onKey(this, e, primary)) {
            return true;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() > 0) {
            return e.getAction() == KeyEvent.ACTION_UP && isShortcut(e.getKeyCode());
        }
        ComputerDetails c = selected();
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_BUTTON_L1:
                cycle(-1);
                return true;
            case KeyEvent.KEYCODE_BUTTON_R1:
                cycle(1);
                return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
                host.settings();
                return true;
            case KeyEvent.KEYCODE_BUTTON_X:
                if (playAgain.getVisibility() == VISIBLE && playAgain.isEnabled()) {
                    DuoMotion.tap(playAgain);
                    playAgain.performClick();
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
                host.exit();
                return true;
            case KeyEvent.KEYCODE_BUTTON_START:
                if (quitAllBtn != null && quitAllBtn.getVisibility() == VISIBLE) {
                    DuoMotion.tap(quitAllBtn);
                    quitAllBtn.performClick();
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                if (optionsBtn != null && optionsBtn.getVisibility() == VISIBLE) {
                    optionsBtn.performClick();
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_A: {
                View f = findFocus();
                if (f != null) {
                    DuoMotion.tap(f);
                    f.performClick();
                } else if (c != null) {
                    primary.performClick();
                }
                return true;
            }
        }
        return false;
    }

    public boolean handleMotion(android.view.MotionEvent e) {
        return DuoPadNav.onMotion(this, e, this::handleKey);
    }

    private static boolean isShortcut(int k) {
        return k == KeyEvent.KEYCODE_BUTTON_L1 || k == KeyEvent.KEYCODE_BUTTON_R1 || k == KeyEvent.KEYCODE_BUTTON_X
                || k == KeyEvent.KEYCODE_BUTTON_START || k == KeyEvent.KEYCODE_BUTTON_SELECT || k == KeyEvent.KEYCODE_BUTTON_Y || k == KeyEvent.KEYCODE_BUTTON_B || k == KeyEvent.KEYCODE_BUTTON_A;
    }
}
