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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Offline German + English word list with frequencies and proper capitalization
 * (German nouns), used for suggestions and Gboard-style autocorrect.
 *
 * Matching works on a "plain" lowercase form (ä->a, ö->o, ü->u, ß->ss) so words typed
 * without umlauts find their umlaut spelling. Letter substitutions are cheap when the
 * finger actually landed near the other key (spatial model, like Gboard), and words
 * the user keeps typing are learned.
 */
public final class DuoDictionary {

    public static final class Result {
        /** The typed word is a known word. */
        public boolean known;
        /** Confident enough to replace the word automatically on space. */
        public boolean autocorrect;
        /** Best first; corrections and/or completions, in display spelling. */
        public final List<String> suggestions = new ArrayList<>();
    }

    /** Language code ("de"/"en") -> loaded dictionary. */
    private static final Map<String, DuoDictionary> instances = new ConcurrentHashMap<>();
    private static final java.util.Set<String> loading = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private static volatile String current = "de";

    private String[] display;      // display spelling (nouns capitalized), sorted by plain form
    private String[] lower;        // lowercase
    private String[] plain;        // plain forms, sorted
    private int[] freq;
    private Map<String, Integer> exact;      // lowercase -> index
    private int[][] byLength;      // plain length -> indices

    /** Learned words: lowercase -> (display spelling, count). */
    private final Map<String, Integer> learnedCount = new ConcurrentHashMap<>();
    private final Map<String, String> learnedDisplay = new ConcurrentHashMap<>();

    /** Dictionary of the current language (null while loading). */
    public static DuoDictionary get() {
        return instances.get(current);
    }

    public static String language() {
        return current;
    }

    /** Switches language and loads its word list if needed. */
    public static void setLanguage(Context context, String lang, Map<String, Integer> learned) {
        current = lang;
        load(context, learned, lang);
    }

    /** Starts loading in the background (no-op if already loaded or loading). */
    public static void load(final Context context, final Map<String, Integer> learned, final String lang) {
        if (instances.containsKey(lang) || !loading.add(lang)) {
            return;
        }
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                DuoDictionary d = new DuoDictionary();
                d.read(app, "duo_words_" + lang + ".txt");
                if (learned != null) {
                    for (Map.Entry<String, Integer> e : learned.entrySet()) {
                        d.learnedCount.put(e.getKey().toLowerCase(), e.getValue());
                        d.learnedDisplay.put(e.getKey().toLowerCase(), e.getKey());
                    }
                }
                instances.put(lang, d);
                LimeLog.info("Duo: dictionary " + lang + " loaded, " + d.display.length + " words");
            } catch (Throwable t) {
                LimeLog.warning("Duo: dictionary failed to load: " + t);
            } finally {
                loading.remove(lang);
            }
        }, "DuoDictionary").start();
    }

    private DuoDictionary() {}

    private void read(Context context, String asset) throws Exception {
        List<String> w = new ArrayList<>(90000);
        List<Integer> f = new ArrayList<>(90000);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                context.getAssets().open(asset), StandardCharsets.UTF_8))) {
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
            plainTmp[i] = toPlain(w.get(i).toLowerCase());
        }
        Arrays.sort(order, (a, b) -> plainTmp[a].compareTo(plainTmp[b]));

        display = new String[n];
        lower = new String[n];
        plain = new String[n];
        freq = new int[n];
        exact = new HashMap<>(n * 2);
        int maxLen = 0;
        for (int i = 0; i < n; i++) {
            int o = order[i];
            display[i] = w.get(o);
            lower[i] = display[i].toLowerCase();
            plain[i] = plainTmp[o];
            freq[i] = f.get(o);
            Integer prev = exact.get(lower[i]);
            if (prev == null || freq[prev] < freq[i]) {
                exact.put(lower[i], i);
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

    // ---------------- learning ----------------

    /** The user typed this word and kept it. Returns the new count. */
    public int learn(String word, int increment) {
        String key = word.toLowerCase();
        int c = (learnedCount.containsKey(key) ? learnedCount.get(key) : 0) + increment;
        learnedCount.put(key, c);
        if (!learnedDisplay.containsKey(key)) {
            learnedDisplay.put(key, word);
        }
        return c;
    }

    /** Learned words of all loaded languages (display spelling -> count). */
    public static Map<String, Integer> learnedAll() {
        Map<String, Integer> out = new HashMap<>();
        for (DuoDictionary d : instances.values()) {
            for (Map.Entry<String, Integer> e : d.learnedSnapshot().entrySet()) {
                Integer prev = out.get(e.getKey());
                out.put(e.getKey(), prev == null ? e.getValue() : Math.max(prev, e.getValue()));
            }
        }
        return out;
    }

    /** Learned words worth persisting (display spelling -> count). */
    public Map<String, Integer> learnedSnapshot() {
        Map<String, Integer> out = new HashMap<>();
        for (Map.Entry<String, Integer> e : learnedCount.entrySet()) {
            String d = learnedDisplay.get(e.getKey());
            out.put(d != null ? d : e.getKey(), e.getValue());
        }
        return out;
    }

    private int learned(String key) {
        Integer c = learnedCount.get(key);
        return c == null ? 0 : c;
    }

    // ---------------- lookup ----------------

    /**
     * @param tapX, tapY per typed character, the touch position in key units
     *        (q = 0,0; one key/row = 1), or NaN if unknown. May be null.
     */
    public Result lookup(String typedRaw, float[] tapX, float[] tapY) {
        Result res = new Result();
        String typed = typedRaw.toLowerCase();
        if (typed.isEmpty()) {
            return res;
        }
        Integer typedIdx = exact.get(typed);
        int typedLearned = learned(typed);
        // A word typed and kept twice counts as known.
        res.known = typedIdx != null || typedLearned >= 2;
        String p = toPlain(typed);
        if (p.length() != typed.length()) {
            tapX = tapY = null;   // ß expanded: positions no longer line up
        }

        // Known German noun typed in lowercase: capitalize it (haus -> Haus).
        if (typedIdx != null && typedLearned < 2 && Character.isLowerCase(typedRaw.charAt(0))) {
            String disp = display[typedIdx];
            if (Character.isUpperCase(disp.charAt(0)) && disp.equalsIgnoreCase(typedRaw)) {
                res.autocorrect = true;
                res.suggestions.add(disp);
            }
        }

        // 1) Corrections: weighted edit distance on plain forms, nearby lengths.
        final int maxCandidates = 3;
        String[] best = new String[maxCandidates];
        double[] bestScore = new double[maxCandidates];
        double[] bestDist = new double[maxCandidates];
        int[] bestFreq = new int[maxCandidates];
        Arrays.fill(bestScore, Double.MAX_VALUE);
        double maxDist = p.length() <= 2 ? 0.6 : (p.length() <= 5 ? 1.1 : 2.1);

        for (int l = Math.max(1, p.length() - 2); l <= Math.min(byLength.length - 1, p.length() + 2); l++) {
            for (int idx : byLength[l]) {
                double d = distance(p, plain[idx], maxDist, tapX, tapY);
                if (d > maxDist || lower[idx].equals(typed)) {
                    continue;
                }
                // Umlaut spelling of exactly what was typed (fur -> für) is nearly free.
                if (d == 0) {
                    d = 0.15;
                }
                int fr = freq[idx] + learned(lower[idx]) * 20000;
                double score = d * 3.0 - Math.log(fr + 1);
                insert(best, bestScore, bestDist, bestFreq, display[idx], score, d, fr);
            }
        }
        // Learned words that aren't in the word list.
        for (Map.Entry<String, Integer> e : learnedCount.entrySet()) {
            String key = e.getKey();
            if (e.getValue() < 2 || exact.containsKey(key) || key.equals(typed)) {
                continue;
            }
            double d = distance(p, toPlain(key), maxDist, tapX, tapY);
            if (d <= maxDist) {
                int fr = e.getValue() * 20000;
                String disp = learnedDisplay.get(key);
                insert(best, bestScore, bestDist, bestFreq, disp != null ? disp : key,
                        d * 3.0 - Math.log(fr + 1), Math.max(d, 0.15), fr);
            }
        }

        // 2) Completions: most frequent words starting with what was typed.
        List<String> completions = completions(p, typed, 3);

        // A "known" word that is very rare next to a close, very common candidate
        // (helo -> hello) is treated like a typo.
        boolean rareTypo = false;
        if (res.known && typedLearned < 2 && typedIdx != null && best[0] != null && bestDist[0] <= 1.0) {
            rareTypo = (long) freq[typedIdx] * 300L < bestFreq[0];
        }

        if ((!res.known || rareTypo) && best[0] != null) {
            // Confident if the best candidate is close and clearly ahead of the rest.
            boolean close = bestDist[0] <= (p.length() <= 4 ? 1.0 : 2.0);
            boolean ahead = best[1] == null || bestScore[0] + 1.0 < bestScore[1];
            if (close && (ahead || bestDist[0] <= 0.5)) {
                res.autocorrect = true;
            }
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
            for (int i = 0; i < best.length; i++) {
                if (best[i] != null && bestDist[i] <= 1.0) {
                    add(res.suggestions, best[i]);
                }
            }
        }
        // Never suggest exactly the typed word itself.
        for (int i = res.suggestions.size() - 1; i >= 0; i--) {
            if (res.suggestions.get(i).equals(typedRaw)) {
                res.suggestions.remove(i);
            }
        }
        while (res.suggestions.size() > 3) {
            res.suggestions.remove(res.suggestions.size() - 1);
        }
        if (res.autocorrect && res.suggestions.isEmpty()) {
            res.autocorrect = false;
        }
        return res;
    }

    private static void add(List<String> list, String s) {
        for (String x : list) {
            if (x.equalsIgnoreCase(s)) {
                return;
            }
        }
        list.add(s);
    }

    private static void insert(String[] best, double[] scores, double[] dists, int[] freqs,
                               String cand, double score, double d, int fr) {
        for (int i = 0; i < best.length; i++) {
            if (cand.equalsIgnoreCase(best[i])) {
                if (score < scores[i]) {
                    scores[i] = score;
                    dists[i] = d;
                    freqs[i] = fr;
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
                    freqs[j] = freqs[j - 1];
                }
                best[i] = cand;
                scores[i] = score;
                dists[i] = d;
                freqs[i] = fr;
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
            if (lower[i].equals(typed)) {
                continue;
            }
            int fr = freq[i] + learned(lower[i]) * 20000;
            for (int k = 0; k < max; k++) {
                if (top[k] == null || fr > topF[k]) {
                    for (int j = max - 1; j > k; j--) {
                        top[j] = top[j - 1];
                        topF[j] = topF[j - 1];
                    }
                    top[k] = display[i];
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

    /** Key centre of a letter in key units (q = 0,0), or null. */
    static float[] keyPos(char c) {
        return c < 128 ? POS[c] : null;
    }

    private static double subCost(int i, char a, char b, float[] tapX, float[] tapY) {
        if (a == b) {
            return 0;
        }
        float[] pb = b < 128 ? POS[b] : null;
        if (pb != null && tapX != null && i < tapX.length && !Float.isNaN(tapX[i])) {
            // Spatial model: how close did the finger land to the other key?
            float dx = tapX[i] - pb[0];
            float dy = tapY[i] - pb[1];
            double d = Math.sqrt(dx * dx + dy * dy);
            return Math.max(0.1, Math.min(1.0, d - 0.45));
        }
        float[] pa = a < 128 ? POS[a] : null;
        if (pa != null && pb != null) {
            float dx = pa[0] - pb[0];
            float dy = pa[1] - pb[1];
            if (dx * dx + dy * dy <= 1.6f) {
                return 0.5;   // neighbouring keys: likely a slip
            }
        }
        return 1.0;
    }

    /** Edit distance with cheap near-miss substitutions; stops early above max. */
    static double distance(String a, String b, double max, float[] tapX, float[] tapY) {
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
                v = Math.min(v, prev[j - 1] + subCost(i - 1, ca, cb, tapX, tapY));
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
