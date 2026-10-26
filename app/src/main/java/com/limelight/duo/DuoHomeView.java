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
        void addHost();
        void profiles();
        void settings();
        void exit();
    }

    private static final String KEY_SELECTED = "duo_home_selected_host";
    private static boolean splashShown;

    private final Host host;
    private final float d;
    private final int accent;
    private final boolean landscape;
    private final List<ComputerDetails> computers = new ArrayList<>();
    private final List<View> glyphs = new ArrayList<>();
    private String selectedUuid;

    private final LinearLayout content;
    private final LinearLayout hostChips;
    private final TextView clock, dateLine;
    private TextView badge, name, subline, address, addressLabel;
    private LinearLayout chips;
    private TextView primaryLabel;
    private View primary;
    private LinearLayout lastPanel;
    private TextView lastInfo, lastName;
    private ImageView lastCover;
    private TextView lastPlaceholder;
    private View playAgain;
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
        this.d = context.getResources().getDisplayMetrics().density;
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
        titles.addView(text("an Artemide fork for Surface Duo", 10.5f, DuoTheme.TEXT2, false));
        LinearLayout clockCol = new LinearLayout(context);
        clockCol.setOrientation(LinearLayout.VERTICAL);
        clockCol.setGravity(Gravity.END);
        header.addView(clockCol);
        clock = text("", 24, DuoTheme.TEXT, false);
        clock.setGravity(Gravity.END);
        clockCol.addView(clock);
        dateLine = text("", 10.5f, DuoTheme.TEXT2, false);
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
        View settings = button("Settings", "X", false, v -> host.settings());
        bottom.addView(settings, new LinearLayout.LayoutParams(-2, -1));
        View exit = button("Exit", "B", false, v -> host.exit());
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-2, -1);
        ep.leftMargin = dp(8);
        bottom.addView(exit, ep);
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
        computers.clear();
        computers.addAll(list);
        render();
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
        selectedUuid = c.uuid;
        PreferenceManager.getDefaultSharedPreferences(getContext()).edit().putString(KEY_SELECTED, c.uuid).apply();
        render();
    }

    private void cycle(int dir) {
        if (computers.size() < 2) {
            return;
        }
        int i = computers.indexOf(selected());
        select(computers.get((i + dir + computers.size()) % computers.size()));
    }

    /** Re-reads the last played app (e.g. after returning from a stream). */
    public void refresh() {
        render();
    }

    private void render() {
        ComputerDetails c = selected();

        // host chips
        hostChips.removeAllViews();
        for (ComputerDetails pc : computers) {
            boolean sel = pc == c;
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
            chip.setFocusable(true);
            chip.setOnClickListener(v -> select(pc));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -1);
            cp.rightMargin = dp(6);
            hostChips.addView(chip, cp);
        }
        TextView add = text("+  Add a host", 12, DuoTheme.TEXT2, false);
        add.setGravity(Gravity.CENTER_VERTICAL);
        add.setPadding(dp(10), 0, dp(10), 0);
        add.setBackground(focusable(0, 0, 9));
        add.setFocusable(true);
        add.setOnClickListener(v -> host.addHost());
        hostChips.addView(add, new LinearLayout.LayoutParams(-2, -1));

        // card
        if (c == null) {
            badge.setText("SEARCHING");
            badge.setCompoundDrawablesRelativeWithIntrinsicBounds(dotDrawable(DuoTheme.WARN), null, null, null);
            name.setText("No host yet");
            subline.setText("Looking for PCs on your network, or add one by address.");
            chips.setVisibility(GONE);
            address.setVisibility(GONE);
            addressLabel.setVisibility(GONE);
            primaryLabel.setText("Add a host");
            primary.setOnClickListener(v -> host.addHost());
            lastPanel.setVisibility(INVISIBLE);
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

        // last played
        DuoPlayStats.Last last = DuoPlayStats.last(getContext(), c.uuid);
        lastPanel.setVisibility(VISIBLE);
        if (last == null) {
            lastInfo.setText("NOTHING PLAYED YET");
            lastName.setText("");
            setCover(c, null);
            playAgain.setVisibility(INVISIBLE);
        } else {
            lastInfo.setText(("Last played " + DuoPlayStats.ago(last.when)
                    + (last.totalMs > 0 ? "  ·  " + DuoPlayStats.duration(last.totalMs) + " total" : "")).toUpperCase());
            lastName.setText(last.name);
            setCover(c, last);
            playAgain.setVisibility(VISIBLE);
            boolean ready = c.state == ComputerDetails.State.ONLINE && c.pairState == PairingManager.PairState.PAIRED;
            playAgain.setEnabled(ready);
            playAgain.setAlpha(ready ? 1f : 0.45f);
            playAgain.setOnClickListener(v -> host.playAgain(c, last));
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
        Bitmap bmp = null;
        if (last != null) {
            File f = new File(new File(new File(getContext().getCacheDir(), "boxart"), c.uuid), last.appId + ".png");
            if (f.exists()) {
                try {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inSampleSize = 2;
                    bmp = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
                } catch (Throwable ignored) {
                }
            }
        }
        if (bmp != null && (last == null || !DuoPlayStats.isApp(last.name))) {
            lastCover.setImageBitmap(bmp);
            lastCover.setVisibility(VISIBLE);
            lastPlaceholder.setVisibility(GONE);
        } else {
            lastCover.setVisibility(GONE);
            lastPlaceholder.setVisibility(VISIBLE);
            boolean desk = last == null || DuoPlayStats.isApp(last.name);
            lastPlaceholder.setText(last == null ? "" : desk ? last.name.toUpperCase() : initials(last.name));
            lastPlaceholder.setCompoundDrawablesWithIntrinsicBounds(null,
                    desk ? tinted(R.drawable.ic_duo_monitor) : null, null, null);
        }
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
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.topMargin = dp(6);
        card.addView(cs, cl);

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
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-2, -1);
        pp.leftMargin = dp(8);
        buttons.addView(profiles, pp);
        View options = button("Options", null, false, v -> {
            ComputerDetails c = selected();
            if (c != null) {
                host.options(c);
            }
        });
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(-2, -1);
        op.leftMargin = dp(8);
        buttons.addView(options, op);
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
        int cw = landscape ? 132 : 110;
        LinearLayout.LayoutParams cb = new LinearLayout.LayoutParams(dp(cw), dp(cw * 4 / 3f));
        cb.topMargin = dp(14);
        lastPanel.addView(coverBox, cb);
        coverBox.setBackground(rounded(0xFF1A1E22, DuoTheme.LINE, 12));
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

        playAgain = button("Play again", "Y", false, null);
        LinearLayout.LayoutParams pa = new LinearLayout.LayoutParams(-2, dp(36));
        pa.topMargin = dp(12);
        lastPanel.addView(playAgain, pa);
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
        // Loading bar: a short segment gliding across a faint track.
        FrameLayout track = new FrameLayout(getContext());
        track.setBackground(rounded(0x0FFFFFFF, 0, 2));
        track.setClipToOutline(true);
        LinearLayout.LayoutParams trp = new LinearLayout.LayoutParams(dp(160), dp(3));
        trp.topMargin = dp(22);
        s.addView(track, trp);
        View seg = new View(getContext());
        seg.setBackground(rounded(accent, 0, 2));
        track.addView(seg, new FrameLayout.LayoutParams(dp(64), -1));
        android.animation.ObjectAnimator a = android.animation.ObjectAnimator.ofFloat(seg, View.TRANSLATION_X, -dp(64), dp(160));
        a.setDuration(2400);
        a.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        a.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        a.start();
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

    private View logoBox(int sizeDp) {
        FrameLayout box = new FrameLayout(getContext());
        box.setBackground(rounded(DuoTheme.CARD, DuoTheme.LINE, sizeDp * 0.29f));
        ImageView i = new ImageView(getContext());
        i.setImageDrawable(tinted(R.drawable.ic_duo_logo));
        int pad = Math.round(sizeDp * 0.2f * d);
        i.setPadding(pad, pad, pad, pad);
        box.addView(i, match());
        return box;
    }

    private android.graphics.drawable.Drawable tinted(int res) {
        android.graphics.drawable.Drawable dr = getContext().getDrawable(res).mutate();
        dr.setTint(res == R.drawable.ic_duo_logo ? accent : DuoTheme.TEXT);
        return dr;
    }

    /** Button with optional controller glyph; the label view is stored as the tag. */
    private View button(String label, String glyph, boolean primaryStyle, OnClickListener l) {
        LinearLayout b = row();
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(primaryStyle ? 18 : 16), 0, dp(primaryStyle ? 18 : 16), 0);
        int bg = primaryStyle ? accent : DuoTheme.CARD_HIGH;
        b.setBackground(focusable(bg, primaryStyle ? 0 : DuoTheme.LINE, 10));
        if (glyph != null) {
            View g = glyph(glyph);
            LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(17), dp(17));
            gp.rightMargin = dp(8);
            b.addView(g, gp);
        }
        TextView t = text(label, primaryStyle ? 14 : 13, primaryStyle ? DuoTheme.onAccent(accent) : DuoTheme.TEXT, primaryStyle);
        b.addView(t);
        b.setTag(t);
        b.setFocusable(true);
        b.setClickable(true);
        if (l != null) {
            b.setOnClickListener(l);
        }
        return b;
    }

    private View glyph(String letter) {
        int c;
        switch (letter) {
            case "A": c = 0xFF6FCF7F; break;
            case "B": c = 0xFFE0645A; break;
            case "X": c = 0xFF5A9BE0; break;
            default: c = 0xFFE8C15A; break;
        }
        TextView g = text(letter, 9.5f, Color.BLACK, true);
        g.setGravity(Gravity.CENTER);
        g.setBackground(circle(c));
        glyphs.add(g);
        return g;
    }

    private View keyPill(String label) {
        TextView t = text(label, 9, DuoTheme.TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(dp(26));
        t.setPadding(dp(5), dp(2), dp(5), dp(2));
        t.setBackground(rounded(DuoTheme.CARD_HIGH, DuoTheme.LINE, 5));
        glyphs.add(t);
        return t;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
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
        ring.setStroke(Math.round(2 * d), color == accent ? DuoTheme.TEXT : accent);
        GradientDrawable pressed = rounded(color == 0 ? 0x14FFFFFF : DuoTheme.mix(Color.WHITE, color, 0.08f), stroke, radiusDp);
        s.addState(new int[]{android.R.attr.state_focused}, ring);
        s.addState(new int[]{android.R.attr.state_pressed}, pressed);
        s.addState(new int[]{}, rounded(color, stroke, radiusDp));
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
            case KeyEvent.KEYCODE_BUTTON_X:
                host.settings();
                return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
                if (playAgain.getVisibility() == VISIBLE && playAgain.isEnabled()) {
                    playAgain.performClick();
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
                host.exit();
                return true;
            case KeyEvent.KEYCODE_BUTTON_A: {
                View f = findFocus();
                if (f != null) {
                    f.performClick();
                } else if (c != null) {
                    primary.performClick();
                }
                return true;
            }
        }
        return false;
    }

    private static boolean isShortcut(int k) {
        return k == KeyEvent.KEYCODE_BUTTON_L1 || k == KeyEvent.KEYCODE_BUTTON_R1 || k == KeyEvent.KEYCODE_BUTTON_X
                || k == KeyEvent.KEYCODE_BUTTON_Y || k == KeyEvent.KEYCODE_BUTTON_B || k == KeyEvent.KEYCODE_BUTTON_A;
    }
}
