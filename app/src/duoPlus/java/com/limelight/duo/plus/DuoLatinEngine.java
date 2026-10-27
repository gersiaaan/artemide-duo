package com.limelight.duo.plus;

import android.content.Context;

import com.android.inputmethod.keyboard.ProximityInfo;
import com.android.inputmethod.latin.BinaryDictionary;
import com.android.inputmethod.latin.DicTraverseSession;
import com.android.inputmethod.latin.utils.BinaryDictionaryUtils;
import com.limelight.LimeLog;
import com.limelight.duo.DuoDictionary;
import com.limelight.duo.DuoEngine;
import com.limelight.duo.DuoNextWord;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Artemide Duo Plus: the AOSP LatinIME engine (the one behind AOSP keyboard / HeliBoard) for
 * the Duo keyboard: spatial model of where the finger landed, the main dictionary with word
 * pairs, and a personal history dictionary that learns your words and phrases (and slowly
 * forgets ones you stop using).
 */
public final class DuoLatinEngine implements DuoEngine {
    // From the native side (defines.h / SuggestedWords).
    private static final int MAX_WORD = BinaryDictionary.DICTIONARY_MAX_WORD_LENGTH;
    private static final int MAX_RESULTS = 18;
    private static final int NOT_A_PROBABILITY = -1;
    private static final int NOT_A_COORDINATE = -1;
    private static final int FLAG_EXACT_MATCH = 0x40000000;
    private static final int FLAG_EXACT_MATCH_WITH_OMISSION = 0x20000000;
    private static final int FLAG_APPROPRIATE_FOR_AUTOCORRECT = 0x10000000;
    private static final int FORMAT_VERSION_4 = 403;
    /** HeliBoard's "modest" auto-correction threshold. */
    private static final float AUTOCORRECT_THRESHOLD = 0.185f;

    // Virtual key grid in "key units" (same as the keyboard's tap positions, q = 0,0).
    private static final int KW = 100, KH = 120;
    private static final String[] ROWS_DE = {"qwertzuiopü", "asdfghjklöä", "yxcvbnm"};
    private static final String[] ROWS_EN = {"qwertzuiop", "asdfghjkl", "yxcvbnm"};
    private static final float[] ROW_OFFSET = {0f, 0.5f, 1.5f};

    private String lang;
    private long mainDict, historyDict, proximity;
    private long mainSession, historySession;
    private long mainSize;
    private File historyFile;
    private int learnedSinceFlush;
    /** New (not in the main dictionary) words seen so far: learned only from the 3rd time on. */
    private android.content.SharedPreferences pending;
    private static final int LEARN_AFTER = 3;

    public DuoLatinEngine() {}

    @Override
    public synchronized boolean load(Context context, String language) {
        lang = language;
        pending = context.getApplicationContext().getSharedPreferences("duo_plus_pending_" + language, Context.MODE_PRIVATE);
        try {
            File dir = new File(context.getFilesDir(), "duo_dicts");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return false;
            }
            File main = new File(dir, "main_" + lang + ".dict");
            copyAsset(context, "dicts/main_" + lang + ".dict", main);
            mainSize = main.length();
            mainDict = BinaryDictionary.openNative(main.getAbsolutePath(), 0, mainSize, false);
            if (mainDict == 0) {
                LimeLog.warning("Duo Plus: main dictionary " + lang + " didn't open");
                return false;
            }
            historyFile = new File(dir, "history_" + lang + ".dict");
            if (historyFile.exists()) {
                historyDict = BinaryDictionary.openNative(historyFile.getAbsolutePath(), 0,
                        historyFile.length(), true);
                if (historyDict != 0 && BinaryDictionary.isCorruptedNative(historyDict)) {
                    BinaryDictionary.closeNative(historyDict);
                    historyDict = 0;
                }
            }
            if (historyDict == 0) {
                deleteRecursive(historyFile);
                String[] keys = {"dictionary", "locale", "version", "USES_FORGETTING_CURVE", "HAS_HISTORICAL_INFO"};
                String[] values = {"history." + lang, locale(),
                        String.valueOf(System.currentTimeMillis() / 1000), "1", "1"};
                historyDict = BinaryDictionary.createOnMemoryNative(FORMAT_VERSION_4, locale(), keys, values);
            }
            mainSession = DicTraverseSession.setDicTraverseSessionNative(locale(), mainSize);
            DicTraverseSession.initDicTraverseSessionNative(mainSession, mainDict, null, 0);
            if (historyDict != 0) {
                historySession = DicTraverseSession.setDicTraverseSessionNative(locale(), 0);
                DicTraverseSession.initDicTraverseSessionNative(historySession, historyDict, null, 0);
            }
            proximity = buildProximity();
            LimeLog.info("Duo Plus: LatinIME engine ready for " + lang);
            return true;
        } catch (Throwable t) {
            LimeLog.warning("Duo Plus: engine failed for " + lang + ": " + t);
            return false;
        }
    }

    private String locale() {
        return "en".equals(lang) ? "en_US" : lang;
    }

    private static void copyAsset(Context c, String asset, File to) throws Exception {
        long size;
        try (android.content.res.AssetFileDescriptor fd = c.getAssets().openFd(asset)) {
            size = fd.getLength();
        } catch (Exception compressed) {
            size = -1;
        }
        if (to.exists() && (size < 0 || to.length() == size)) {
            return;
        }
        File tmp = new File(to.getPath() + ".tmp");
        try (InputStream in = c.getAssets().open(asset); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
        if (!tmp.renameTo(to)) {
            throw new Exception("rename failed");
        }
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    deleteRecursive(k);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    // ---------------- key geometry ----------------

    private long buildProximity() {
        String[] rows = "de".equals(lang) ? ROWS_DE : ROWS_EN;
        List<int[]> keys = new ArrayList<>(); // x, y, w, h, code
        int width = 0;
        for (int r = 0; r < rows.length; r++) {
            for (int c = 0; c < rows[r].length(); c++) {
                int x = Math.round((c + ROW_OFFSET[r]) * KW);
                keys.add(new int[]{x, r * KH, KW, KH, rows[r].charAt(c)});
                width = Math.max(width, x + KW);
            }
        }
        int height = rows.length * KH;
        int gridW = 32, gridH = 16;
        int cellW = (width + gridW - 1) / gridW, cellH = (height + gridH - 1) / gridH;
        int max = ProximityInfo.MAX_PROXIMITY_CHARS_SIZE;
        int[] prox = new int[gridW * gridH * max];
        Arrays.fill(prox, -1);
        float threshold = KW * 1.2f;
        for (int gy = 0; gy < gridH; gy++) {
            for (int gx = 0; gx < gridW; gx++) {
                float cx = gx * cellW + cellW / 2f, cy = gy * cellH + cellH / 2f;
                List<float[]> near = new ArrayList<>(); // distance, code
                for (int[] k : keys) {
                    float dx = Math.max(0, Math.max(k[0] - cx, cx - (k[0] + k[2])));
                    float dy = Math.max(0, Math.max(k[1] - cy, cy - (k[1] + k[3])));
                    float d = (float) Math.hypot(dx, dy);
                    if (d < threshold) {
                        near.add(new float[]{d, k[4]});
                    }
                }
                Collections.sort(near, (a, b) -> Float.compare(a[0], b[0]));
                int base = (gy * gridW + gx) * max;
                for (int i = 0; i < near.size() && i < max; i++) {
                    prox[base + i] = (int) near.get(i)[1];
                }
            }
        }
        int n = keys.size();
        int[] xs = new int[n], ys = new int[n], ws = new int[n], hs = new int[n], codes = new int[n];
        for (int i = 0; i < n; i++) {
            int[] k = keys.get(i);
            xs[i] = k[0];
            ys[i] = k[1];
            ws[i] = k[2];
            hs[i] = k[3];
            codes[i] = k[4];
        }
        return ProximityInfo.setProximityInfoNative(width, height, gridW, gridH, KW, KH, prox, n,
                xs, ys, ws, hs, codes, null, null, null);
    }

    // ---------------- queries ----------------

    private static final class Hit {
        String word;
        int score;
        int type;
        long dict;
    }

    private static int[] cps(String s) {
        int n = s.codePointCount(0, s.length());
        int[] out = new int[n];
        for (int i = 0, o = 0; i < s.length(); o++) {
            int c = s.codePointAt(i);
            out[o] = c;
            i += Character.charCount(c);
        }
        return out;
    }

    private int prob(long dict, String w) {
        if (dict == 0 || w == null || w.isEmpty()) {
            return NOT_A_PROBABILITY;
        }
        return BinaryDictionary.getProbabilityNative(dict, cps(w));
    }

    /** The prev word as the dictionary spells it (German nouns are stored capitalized). */
    private String dictCase(String w) {
        if (w == null || w.isEmpty() || prob(mainDict, w) != NOT_A_PROBABILITY) {
            return w;
        }
        String cap = Character.toUpperCase(w.charAt(0)) + w.substring(1);
        return prob(mainDict, cap) != NOT_A_PROBABILITY ? cap : w;
    }

    /** Previous words for the native side: index 0 = the word right before. */
    private int context(String prev2, String prev1, int[][] arrays, boolean[] bos) {
        Arrays.fill(bos, false);
        for (int i = 0; i < arrays.length; i++) {
            arrays[i] = new int[0];
        }
        if (prev1 == null) {
            return 0;
        }
        if (DuoNextWord.START.equals(prev1)) {
            bos[0] = true;
            return 1;
        }
        arrays[0] = cps(dictCase(prev1));
        if (prev2 == null) {
            return 1;
        }
        if (DuoNextWord.START.equals(prev2)) {
            bos[1] = true;
        } else {
            arrays[1] = cps(dictCase(prev2));
        }
        return 2;
    }

    private List<Hit> suggest(long dict, long session, boolean fullEdit, int[] input, int inputSize,
                              int[] xs, int[] ys, String prev2, String prev1, int[] outConfidence) {
        List<Hit> out = new ArrayList<>();
        if (dict == 0 || session == 0) {
            return out;
        }
        int[][] prev = new int[BinaryDictionary.MAX_PREV_WORD_COUNT_FOR_N_GRAM][];
        boolean[] bos = new boolean[BinaryDictionary.MAX_PREV_WORD_COUNT_FOR_N_GRAM];
        int prevCount = context(prev2, prev1, prev, bos);
        int[] options = new int[5];
        options[1] = fullEdit ? 1 : 0;   // USE_FULL_EDIT_DISTANCE
        options[2] = 1;                  // BLOCK_OFFENSIVE_WORDS
        options[4] = 1000;               // WEIGHT_FOR_LOCALE (thousandths)
        int[] count = new int[1];
        int[] outCodes = new int[MAX_WORD * MAX_RESULTS];
        int[] scores = new int[MAX_RESULTS];
        int[] spaces = new int[MAX_RESULTS];
        int[] types = new int[MAX_RESULTS];
        int[] confidence = new int[1];
        float[] weight = {-1f};
        BinaryDictionary.getSuggestionsNative(dict, proximity, session, xs, ys, new int[MAX_WORD],
                new int[MAX_WORD], input, inputSize, options, prev, bos, prevCount, count, outCodes,
                scores, spaces, types, confidence, weight);
        if (outConfidence != null) {
            outConfidence[0] = confidence[0];
        }
        for (int j = 0; j < count[0] && j < MAX_RESULTS; j++) {
            int start = j * MAX_WORD, len = 0;
            while (len < MAX_WORD && outCodes[start + len] != 0) {
                len++;
            }
            if (len == 0) {
                continue;
            }
            Hit h = new Hit();
            h.word = new String(outCodes, start, len);
            h.score = scores[j];
            h.type = types[j];
            h.dict = dict;
            out.add(h);
        }
        return out;
    }

    private static List<Hit> merge(List<Hit> a, List<Hit> b) {
        Map<String, Hit> best = new HashMap<>();
        for (List<Hit> l : java.util.Arrays.asList(a, b)) {
            for (Hit h : l) {
                String k = h.word.toLowerCase();
                Hit old = best.get(k);
                if (old == null || h.score > old.score) {
                    best.put(k, h);
                }
            }
        }
        List<Hit> all = new ArrayList<>(best.values());
        Collections.sort(all, (x, y) -> Integer.compare(y.score, x.score));
        return all;
    }

    /** The dictionary word that differs from `typed` only by accents / umlauts (uberragend -> überragend), else null. */
    private String accentVariant(String typed) {
        if (mainDict == 0 || typed == null || typed.isEmpty() || typed.length() >= MAX_WORD) {
            return null;
        }
        String lower = typed.toLowerCase();
        int[] code = cps(lower);
        int[] input = new int[MAX_WORD];
        int[] xs = new int[MAX_WORD], ys = new int[MAX_WORD];
        Arrays.fill(input, -1);
        Arrays.fill(xs, NOT_A_COORDINATE);
        Arrays.fill(ys, NOT_A_COORDINATE);
        System.arraycopy(code, 0, input, 0, code.length);
        for (Hit h : suggest(mainDict, mainSession, false, input, code.length, xs, ys, null, null, null)) {
            if ((h.type & FLAG_EXACT_MATCH) != 0 && !h.word.equalsIgnoreCase(typed)
                    && h.word.length() == typed.length()) {
                return h.word;
            }
        }
        return null;
    }

    @Override
    public synchronized DuoDictionary.Result lookup(String typed, float[] tapX, float[] tapY,
                                                    String prev2, String prev1) {
        DuoDictionary.Result r = new DuoDictionary.Result();
        if (mainDict == 0 || typed == null || typed.isEmpty() || typed.length() >= MAX_WORD) {
            return r;
        }
        String lower = typed.toLowerCase();
        int[] code = cps(lower);
        int[] input = new int[MAX_WORD];
        int[] xs = new int[MAX_WORD], ys = new int[MAX_WORD];
        Arrays.fill(input, -1);
        Arrays.fill(xs, NOT_A_COORDINATE);
        Arrays.fill(ys, NOT_A_COORDINATE);
        System.arraycopy(code, 0, input, 0, code.length);
        for (int i = 0; i < code.length; i++) {
            if (tapX != null && i < tapX.length && !Float.isNaN(tapX[i]) && !Float.isNaN(tapY[i])) {
                xs[i] = Math.max(0, Math.round((tapX[i] + 0.5f) * KW));
                ys[i] = Math.max(0, Math.round((tapY[i] + 0.5f) * KH));
            }
        }
        List<Hit> hits = merge(
                suggest(mainDict, mainSession, false, input, code.length, xs, ys, prev2, prev1, null),
                suggest(historyDict, historySession, true, input, code.length, xs, ys, prev2, prev1, null));

        boolean knownMain = prob(mainDict, typed) != NOT_A_PROBABILITY || prob(mainDict, lower) != NOT_A_PROBABILITY;
        boolean knownHistory = prob(historyDict, typed) != NOT_A_PROBABILITY || prob(historyDict, lower) != NOT_A_PROBABILITY;
        r.known = knownMain || knownHistory;
        String variant = knownMain ? null : accentVariant(typed);
        for (Hit h : hits) {
            if (r.suggestions.size() >= 6) {
                break;
            }
            r.suggestions.add(h.word);
        }
        if (!r.known && !hits.isEmpty()) {
            Hit top = hits.get(0);
            boolean onlyAccents = (top.type & (FLAG_EXACT_MATCH | FLAG_EXACT_MATCH_WITH_OMISSION)) != 0;
            float norm = BinaryDictionaryUtils.calcNormalizedScoreNative(code, cps(top.word.toLowerCase()), top.score);
            r.autocorrect = !top.word.equalsIgnoreCase(typed)
                    && (onlyAccents || ((top.type & FLAG_APPROPRIATE_FOR_AUTOCORRECT) != 0
                    && norm >= AUTOCORRECT_THRESHOLD));
        }
        if (variant != null) {
            // Only a missing umlaut / accent of a dictionary word (uberragend -> überragend): always
            // correct it, even if it was learned by mistake before.
            r.known = false;
            r.suggestions.remove(variant);
            r.suggestions.add(0, variant);
            r.autocorrect = true;
        }
        return r;
    }

    @Override
    public synchronized List<String> predict(String prev2, String prev1, int max) {
        List<String> out = new ArrayList<>();
        if (mainDict == 0 || prev1 == null) {
            return out;
        }
        int[] input = new int[MAX_WORD];
        int[] xs = new int[MAX_WORD], ys = new int[MAX_WORD];
        Arrays.fill(input, -1);
        Arrays.fill(xs, NOT_A_COORDINATE);
        Arrays.fill(ys, NOT_A_COORDINATE);
        List<Hit> hits = merge(
                suggest(mainDict, mainSession, false, input, 0, xs, ys, prev2, prev1, null),
                suggest(historyDict, historySession, true, input, 0, xs, ys, prev2, prev1, null));
        for (Hit h : hits) {
            if (out.size() >= max) {
                break;
            }
            out.add(h.word);
        }
        return out;
    }

    @Override
    public synchronized void learn(String prev2, String prev1, String word) {
        if (historyDict == 0 || word == null || word.isEmpty() || word.length() >= MAX_WORD) {
            return;
        }
        int[][] prev = new int[BinaryDictionary.MAX_PREV_WORD_COUNT_FOR_N_GRAM][];
        boolean[] bos = new boolean[BinaryDictionary.MAX_PREV_WORD_COUNT_FOR_N_GRAM];
        context(prev2, prev1, prev, bos);
        boolean valid = prob(mainDict, word) != NOT_A_PROBABILITY
                || prob(mainDict, word.toLowerCase()) != NOT_A_PROBABILITY;
        if (!valid) {
            // A word the dictionary doesn't know: never learn it if it's only a missing umlaut / accent
            // of a real word (uber -> über), and otherwise only once it was typed a few times
            // (one typo must not become a "known" word that is never corrected again).
            if (accentVariant(word) != null) {
                return;
            }
            String key = word.toLowerCase();
            int seen = pending == null ? LEARN_AFTER : pending.getInt(key, 0) + 1;
            if (pending != null) {
                pending.edit().putInt(key, seen).apply();
            }
            if (seen < LEARN_AFTER) {
                return;
            }
        }
        BinaryDictionary.updateEntriesForWordWithNgramContextNative(historyDict, prev, bos, cps(word),
                valid, 1, (int) (System.currentTimeMillis() / 1000));
        if (++learnedSinceFlush >= 20) {
            flush();
        }
    }

    @Override
    public synchronized void flush() {
        if (historyDict == 0 || historyFile == null || learnedSinceFlush == 0) {
            return;
        }
        learnedSinceFlush = 0;
        try {
            if (BinaryDictionary.needsToRunGCNative(historyDict, true)) {
                BinaryDictionary.flushWithGCNative(historyDict, historyFile.getAbsolutePath());
            } else {
                BinaryDictionary.flushNative(historyDict, historyFile.getAbsolutePath());
            }
        } catch (Throwable t) {
            LimeLog.warning("Duo Plus: history flush failed: " + t);
        }
    }
}
