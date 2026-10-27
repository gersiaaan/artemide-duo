package com.limelight.binding.video;

import android.content.Context;
import android.net.TrafficStats;
import android.os.Process;
import android.os.SystemClock;

import androidx.preference.PreferenceManager;

import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.utils.TrafficStatsHelper;

import java.util.Locale;

/**
 * Artemide Duo performance overlay ("New" / "New (detailed)"): compact, HTML-formatted.
 * Values are measured on this device; "H" = frames arriving from the host, "C" = frames shown.
 */
public final class DuoPerfText {
    private DuoPerfText() {}

    public static final String KEY_STYLE = "duo_overlay_style";
    public static final String KEY_OPACITY = "duo_overlay_opacity";

    private static long lastBytes = -1;
    private static long lastBytesAt;
    private static float lastMbps;
    private static float peakMbps;
    private static long peakResetAt;

    /** "new", "detailed" or "classic". */
    public static String style(Context c) {
        return PreferenceManager.getDefaultSharedPreferences(c).getString(KEY_STYLE, "new");
    }

    public static boolean isNew(Context c) {
        return !"classic".equals(style(c));
    }

    public static float opacity(Context c) {
        try {
            return Integer.parseInt(PreferenceManager.getDefaultSharedPreferences(c).getString(KEY_OPACITY, "25")) / 100f;
        } catch (NumberFormatException e) {
            return 0.25f;
        }
    }

    private static final String DIM = "<font color='#C8C8C8'>";
    private static final String END = "</font>";

    static String build(Context c, VideoStats s, VideoStatsFps fps, float decodeMs, long rttInfo,
                        int videoFormat, int width, int height, float e2eMs) {
        boolean detailed = "detailed".equals(style(c));
        String codec = (videoFormat & MoonBridge.VIDEO_FORMAT_MASK_AV1) != 0 ? "AV1"
                : (videoFormat & MoonBridge.VIDEO_FORMAT_MASK_H265) != 0 ? "HEVC" : "H.264";

        // Bitrate from the bytes this app received since the last update.
        long now = SystemClock.uptimeMillis();
        long rx = TrafficStatsHelper.getPackageRxBytes(Process.myUid());
        if (rx != TrafficStats.UNSUPPORTED && rx >= 0) {
            if (lastBytes >= 0 && now > lastBytesAt) {
                lastMbps = (rx - lastBytes) * 8f / 1000f / (now - lastBytesAt);
                if (now - peakResetAt > 5000) {
                    peakMbps = 0;
                    peakResetAt = now;
                }
                peakMbps = Math.max(peakMbps, lastMbps);
            }
            lastBytes = rx;
            lastBytesAt = now;
        }

        float netMs = (int) (rttInfo >> 32);
        float netVar = (int) rttInfo;
        float hostMs = s.framesWithHostProcessingLatency > 0
                ? (float) s.totalHostProcessingLatency / 10f / s.framesWithHostProcessingLatency : 0f;
        float renderMs = Math.max(0f, e2eMs - decodeMs);
        float lossPct = s.totalFrames > 0 ? (float) s.framesLost / s.totalFrames * 100f : 0f;
        float pacingPct = s.totalFramesReceived > 0
                ? Math.max(0, s.totalFramesReceived - s.totalFramesRendered) * 100f / s.totalFramesReceived : 0f;
        float total = hostMs + netMs + decodeMs + renderMs;

        StringBuilder b = new StringBuilder();
        b.append("<b>").append(width).append('×').append(height).append(" · ").append(codec).append(" · ")
                .append(f1(fps.receivedFps)).append(DIM).append("(H)").append(END).append(' ')
                .append(f1(fps.renderedFps)).append(DIM).append("(C)").append(END).append(" FPS</b><br>");
        b.append("<b>Bitrate ").append(f1(lastMbps)).append(" Mbps</b> ").append(DIM).append('(')
                .append(f1(lastMbps / 8f)).append(" MB/s) · peak ").append(f1(peakMbps)).append(END).append("<br>");
        if (detailed) {
            b.append(DIM).append("Net / Render: ").append(f1(fps.receivedFps)).append(" / ")
                    .append(f1(fps.renderedFps)).append(" FPS").append(END).append("<br>");
        }
        // Narrow box: short rows, each one centred.
        b.append(DIM).append("Total ").append(END).append("<b>≈ ").append(f1(total)).append(" ms</b>")
                .append(DIM).append(" · dec ").append(END).append("<b>").append(f2(decodeMs)).append(" ms</b><br>");
        b.append(DIM).append("net ").append(END).append("<b>").append(f0(netMs)).append(" ms</b>")
                .append(DIM).append(" ±").append(f0(netVar)).append(" · render ").append(END)
                .append("<b>").append(f2(renderMs)).append(" ms</b><br>");
        b.append(DIM).append("dropped ").append(END).append("<b>").append(f2(lossPct)).append("%</b>")
                .append(DIM).append(" net · ").append(END).append("<b>").append(f2(pacingPct)).append("%</b>")
                .append(DIM).append(" pacing").append(END);
        if (detailed && s.framesWithHostProcessingLatency > 0) {
            b.append("<br>").append(DIM).append("Host  ").append(END)
                    .append(f1(s.minHostProcessingLatency / 10f)).append(" / ")
                    .append(f1(s.maxHostProcessingLatency / 10f)).append(" / <b>").append(f1(hostMs)).append(" ms</b>")
                    .append("<br>").append(DIM).append("Sum  ").append(f1(hostMs)).append(" + ").append(f0(netMs))
                    .append(" + ").append(f1(decodeMs)).append(" + ").append(f1(renderMs)).append(END);
        }
        return b.toString().replace("  ", "&nbsp;");
    }

    private static String f0(float v) {
        return String.format(Locale.US, "%.0f", v);
    }

    private static String f1(float v) {
        return String.format(Locale.US, "%.1f", v);
    }

    private static String f2(float v) {
        return String.format(Locale.US, "%.2f", v);
    }
}
