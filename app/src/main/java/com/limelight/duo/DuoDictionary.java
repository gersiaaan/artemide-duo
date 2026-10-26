package com.limelight.duo;

import android.content.Context;

import com.limelight.LimeLog;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline German + English word list with frequencies, used for suggestions and
 * Gboard-style autocorrect. Loaded once in the background.
 *
 * Matching works on a "plain" form (ä->a, ö->o, ü->u, ß->ss) so words typed without
 * umlauts find their umlaut spelling, and substitutions between neighbouring keys on
 * the QWERTZ layout are cheaper than others.
 */
public final class DuoDictionary {

    public static final class Result {
        /** The typed word is a known word. */
        public boolean known;
        /** Confident enough to replace the word automatically on space. */
        public boolean autocorrect;
        /** Best first; corrections and/or completions. */
        public final List<String> suggestions = new ArrayList<>();
    }

    private static volatile DuoDictionary instance;
    private static boolean loading;

    private String[] words;        // sorted by plain form
    private String[] plain;        // plain forms, sorted
    private int[] freq;
    private Map<String, Integer> exact;     // word -> frequency
    private int[][] byLength;      // plain length -> indices

    public static DuoDictionary get() {
        return instance;
    }

    /** Starts loading in the background (no-op if already loaded or loading). */
    public static synchronized void load(final Context context) {
        if (instance != null || loading) {
            return;
        }
        loading = true;
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                DuoDictionary d = new DuoDictionary();
                d.read(app);
                instance = d;
                LimeLog.info("Duo: dictionary loaded, " + d.words.length + " words");
            } catch (Throwable t) {
                LimeLog.warning("Duo: dictionary failed to load: " + t);
            } finally {
                synchronized (DuoDictionary.class) {
                    loading = false;
                }
            }
        }, "DuoDictionary").start();
    }

    private DuoDictionary() {}

    private void read(Context context) throws Exception {
        List<String> w = new ArrayList<>(90000);
        List<Integer> f = new ArrayList<>(90000);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                context.getAssets().open("duo_words.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') {
                    continue;
                }
                int sp = line.indexOf(' ');
                if (sp <= 0) {
                    continue;
                }
                w.add(line.substring(0, sp));
                f.add(Integer.parseInt(line.substring(sp + 1).trim()));
            }
        }

        int n = w.size();
        Integer[] order = new Integer[n];
        final String[] plainTmp = new String[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
            plainTmp[i] = toPlain(w.get(i));
        }
        Arrays.sort(order, (a, b) -> plainTmp[a].compareTo(plainTmp[b]));

        words = new String[n];
        plain = new String[n];
        freq = new int[n];
        exact = new HashMap<>(n * 2);
        int maxLen = 0;
        for (int i = 0; i < n; i++) {
            int o = order[i];
            words[i] = w.get(o);
            plain[i] = plainTmp[o];
            freq[i] = f.get(o);
            Integer prev = exact.get(words[i]);
            if (prev == null || prev < freq[i]) {
                exact.put(words[i], freq[i]);
            }
            maxLen = Math.max(maxLen, plain[i].length());
        }

        int[] counts = new int[maxLen + 1];
        for (String p : plain) {
            counts[p.length()]++;
        }
        byLength = new int[maxLen + 1][];
        for (int l = 0; l <= maxLen; l++) {
            byLength[l] = new int[counts[l]];
        }
        int[] fill = new int[maxLen + 1];
        for (int i = 0; i < n; i++) {
            int l = plain[i].length();
            byLength[l][fill[l]++] = i;
        }
    }

    static String toPlain(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case 'ä': b.append('a'); break;
                case 'ö': b.append('o'); break;
                case 'ü': b.append('u'); break;
                case 'ß': b.append("ss"); break;
                default: b.append(c);
            }
        }
        return b.toString();
    }

    public boolean isKnown(String word) {
        return exact.containsKey(word.toLowerCase());
    }

    // ---------------- lookup ----------------

    public Result lookup(String typedRaw) {
        Result res = new Result();
        String typed = typedRaw.toLowerCase();
        if (typed.isEmpty()) {
            return res;
        }
        Integer typedFreq = exact.get(typed);
        res.known = typedFreq != null;
        String p = toPlain(typed);

        // 1) Corrections: weighted edit distance on plain forms, nearby lengths.
        final int maxCandidates = 3;
        String[] best = new String[maxCandidates];
        double[] bestScore = new double[maxCandidates];
        double[] bestDist = new double[maxCandidates];
        Arrays.fill(bestScore, Double.MAX_VALUE);
        double maxDist = p.length() <= 2 ? 0.6 : (p.length() <= 5 ? 1.1 : 2.1);

        for (int l = Math.max(1, p.length() - 2); l <= Math.min(byLength.length - 1, p.length() + 2); l++) {
            for (int idx : byLength[l]) {
                double d = distance(p, plain[idx], maxDist);
                if (d > maxDist) {
                    continue;
                }
                String cand = words[idx];
                if (cand.equals(typed)) {
                    continue;
                }
                // Umlaut spelling of exactly what was typed (fur -> für) is nearly free.
                if (d == 0) {
                    d = 0.15;
                }
                double score = d * 3.0 - Math.log(freq[idx] + 1);
                insert(best, bestScore, bestDist, cand, score, d);
            }
        }

        // 2) Completions: most frequent words starting with what was typed.
        List<String> completions = completions(p, typed, 3);

        // A "known" word that is very rare next to a close, very common candidate
        // (helo -> hello) is treated like a typo.
        boolean rareTypo = false;
        if (res.known && best[0] != null && bestDist[0] <= 1.0) {
            Integer bf = exact.get(best[0]);
            rareTypo = bf != null && typedFreq * 300L < bf;
        }

        if ((!res.known || rareTypo) && best[0] != null) {
            // Confident if the best candidate is close and clearly ahead of the rest.
            boolean close = bestDist[0] <= (p.length() <= 4 ? 1.0 : 2.0);
            boolean ahead = best[1] == null || bestScore[0] + 1.0 < bestScore[1];
            res.autocorrect = close && (ahead || bestDist[0] <= 0.5);
            for (String s : best) {
                if (s != null) {
                    add(res.suggestions, s);
                }
            }
            for (String c : completions) {
                add(res.suggestions, c);
            }
        } else {
            for (String c : completions) {
                add(res.suggestions, c);
            }
            for (String s : best) {
                if (s != null && bestDistOf(best, bestDist, s) <= 1.0) {
                    add(res.suggestions, s);
                }
            }
        }
        // Never suggest the typed word itself.
        res.suggestions.remove(typed);
        while (res.suggestions.size() > 3) {
            res.suggestions.remove(res.suggestions.size() - 1);
        }
        return res;
    }

    private static double bestDistOf(String[] best, double[] dist, String s) {
        for (int i = 0; i < best.length; i++) {
            if (s.equals(best[i])) {
                return dist[i];
            }
        }
        return Double.MAX_VALUE;
    }

    private static void add(List<String> list, String s) {
        if (!list.contains(s)) {
            list.add(s);
        }
    }

    private static void insert(String[] best, double[] scores, double[] dists, String cand, double score, double d) {
        for (int i = 0; i < best.length; i++) {
            if (cand.equals(best[i])) {
                if (score < scores[i]) {
                    scores[i] = score;
                    dists[i] = d;
                }
                return;
            }
        }
        for (int i = 0; i < best.length; i++) {
            if (score < scores[i]) {
                for (int j = best.length - 1; j > i; j--) {
                    best[j] = best[j - 1];
                    scores[j] = scores[j - 1];
                    dists[j] = dists[j - 1];
                }
                best[i] = cand;
                scores[i] = score;
                dists[i] = d;
                return;
            }
        }
    }

    private List<String> completions(String p, String typed, int max) {
        List<String> out = new ArrayList<>();
        if (p.length() < 2) {
            return out;
        }
        int lo = lowerBound(p);
        String[] top = new String[max];
        int[] topF = new int[max];
        int scanned = 0;
        for (int i = lo; i < plain.length && plain[i].startsWith(p) && scanned < 6000; i++, scanned++) {
            if (words[i].equals(typed) || plain[i].length() == p.length() && !words[i].startsWith(typed)) {
                // Same length as typed: that's the word itself or an umlaut variant handled above.
                if (words[i].equals(typed)) {
                    continue;
                }
            }
            int fr = freq[i];
            for (int k = 0; k < max; k++) {
                if (top[k] == null || fr > topF[k]) {
                    for (int j = max - 1; j > k; j--) {
                        top[j] = top[j - 1];
                        topF[j] = topF[j - 1];
                    }
                    top[k] = words[i];
                    topF[k] = fr;
                    break;
                }
            }
        }
        for (String s : top) {
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    private int lowerBound(String key) {
        int lo = 0, hi = plain.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (plain[mid].compareTo(key) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    // ---------------- weighted Damerau-Levenshtein ----------------

    private static final String[] ROWS = {"qwertzuiop", "asdfghjkl", "yxcvbnm"};
    private static final float[] ROW_OFFSET = {0f, 0.5f, 1.5f};
    private static final float[][] POS = new float[128][];

    static {
        for (int r = 0; r < ROWS.length; r++) {
            for (int c = 0; c < ROWS[r].length(); c++) {
                POS[ROWS[r].charAt(c)] = new float[]{c + ROW_OFFSET[r], r};
            }
        }
    }

    private static double subCost(char a, char b) {
        if (a == b) {
            return 0;
        }
        if (a < 128 && b < 128 && POS[a] != null && POS[b] != null) {
            float dx = POS[a][0] - POS[b][0];
            float dy = POS[a][1] - POS[b][1];
            if (dx * dx + dy * dy <= 1.6f) {
                return 0.5;   // neighbouring keys: likely a slip
            }
        }
        return 1.0;
    }

    /** Edit distance with cheap neighbour substitutions; stops early above max. */
    static double distance(String a, String b, double max) {
        int n = a.length(), m = b.length();
        if (Math.abs(n - m) > max) {
            return Double.MAX_VALUE;
        }
        double[] prev2 = new double[m + 1];
        double[] prev = new double[m + 1];
        double[] cur = new double[m + 1];
        for (int j = 0; j <= m; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            double rowMin = cur[0];
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                char cb = b.charAt(j - 1);
                double v = Math.min(prev[j] + 1, cur[j - 1] + 1);
                v = Math.min(v, prev[j - 1] + subCost(ca, cb));
                if (i > 1 && j > 1 && ca == b.charAt(j - 2) && a.charAt(i - 2) == cb) {
                    v = Math.min(v, prev2[j - 2] + 0.6);   // swapped letters
                }
                cur[j] = v;
                rowMin = Math.min(rowMin, v);
            }
            if (rowMin > max) {
                return Double.MAX_VALUE;
            }
            double[] t = prev2;
            prev2 = prev;
            prev = cur;
            cur = t;
        }
        return prev[m];
    }
}
