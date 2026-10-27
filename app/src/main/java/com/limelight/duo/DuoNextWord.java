package com.limelight.duo;

import android.content.Context;
import android.content.SharedPreferences;

import com.limelight.LimeLog;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Next-word prediction for the Duo keyboard (offline): a seed of common word pairs per
 * language plus what you type yourself (word pairs and triples, saved on the device).
 * Your own phrases weigh much more than the seed, so predictions adapt quickly.
 * Also re-ranks completions of the word being typed by how well they fit the context.
 */
public final class DuoNextWord {
    private DuoNextWord() {}

    public static final String START = "<s>";
    private static final String PREFS = "duo_ngrams";
    private static final int MAX_LEARNED = 6000;

    /** lang -> seed: previous word -> (next word -> count). */
    private static final Map<String, Map<String, Map<String, Integer>>> seeds = new ConcurrentHashMap<>();
    private static final Set<String> loading = Collections.synchronizedSet(new HashSet<>());
    /** lang -> learned "a b" / "a b c" -> count. */
    private static final Map<String, Map<String, Integer>> learned = new ConcurrentHashMap<>();

    public static void load(Context context, String lang) {
        final Context app = context.getApplicationContext();
        if (!learned.containsKey(lang)) {
            learned.put(lang, readLearned(app, lang));
        }
        if (seeds.containsKey(lang) || !loading.add(lang)) {
            return;
        }
        new Thread(() -> {
            Map<String, Map<String, Integer>> seed = new HashMap<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    app.getAssets().open("duo_next_" + lang + ".txt"), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isEmpty() || line.charAt(0) == '#') {
                        continue;
                    }
                    int tab = line.indexOf('\t');
                    if (tab <= 0) {
                        continue;
                    }
                    Map<String, Integer> next = new HashMap<>();
                    for (String part : line.substring(tab + 1).split(",")) {
                        int c = part.lastIndexOf(':');
                        if (c > 0) {
                            next.put(part.substring(0, c), Integer.parseInt(part.substring(c + 1)));
                        }
                    }
                    seed.put(line.substring(0, tab), next);
                }
            } catch (Throwable t) {
                LimeLog.warning("Duo: next-word seed " + lang + " not loaded: " + t);
            } finally {
                seeds.put(lang, seed);
                loading.remove(lang);
            }
        }, "DuoNextWord").start();
    }

    /** A word was committed after prev2 prev1 (lowercase; START at a sentence start). */
    public static void record(String lang, String prev2, String prev1, String word) {
        Map<String, Integer> m = learned.get(lang);
        if (m == null || prev1 == null || word == null || word.isEmpty()) {
            return;
        }
        String w = word.toLowerCase();
        bump(m, prev1 + " " + w);
        if (prev2 != null) {
            bump(m, prev2 + " " + prev1 + " " + w);
        }
        if (m.size() > MAX_LEARNED) {
            // Drop the rarest entries.
            List<Map.Entry<String, Integer>> all = new ArrayList<>(m.entrySet());
            Collections.sort(all, (a, b) -> Integer.compare(a.getValue(), b.getValue()));
            for (int i = 0; i < all.size() / 4; i++) {
                m.remove(all.get(i).getKey());
            }
        }
    }

    private static void bump(Map<String, Integer> m, String key) {
        Integer c = m.get(key);
        m.put(key, c == null ? 1 : c + 1);
    }

    /** Score of `word` following prev2 prev1. */
    public static float score(String lang, String prev2, String prev1, String word) {
        if (prev1 == null) {
            return 0f;
        }
        String w = word.toLowerCase();
        float s = 0f;
        Map<String, Integer> m = learned.get(lang);
        if (m != null) {
            Integer bi = m.get(prev1 + " " + w);
            if (bi != null) {
                s += 10f * bi;
            }
            if (prev2 != null) {
                Integer tri = m.get(prev2 + " " + prev1 + " " + w);
                if (tri != null) {
                    s += 30f * tri;
                }
            }
        }
        Map<String, Map<String, Integer>> seed = seeds.get(lang);
        Map<String, Integer> next = seed == null ? null : seed.get(prev1);
        if (next != null) {
            Integer c = next.get(w);
            if (c != null) {
                int total = 0;
                for (int v : next.values()) {
                    total += v;
                }
                s += 6f * c / Math.max(1, total);
            }
        }
        return s;
    }

    /** Up to `max` likely next words (lowercase), best first. */
    public static List<String> predict(String lang, String prev2, String prev1, int max) {
        List<String> out = new ArrayList<>();
        if (prev1 == null) {
            return out;
        }
        Map<String, Float> cand = new HashMap<>();
        Map<String, Integer> m = learned.get(lang);
        if (m != null) {
            String bp = prev1 + " ";
            String tp = prev2 == null ? null : prev2 + " " + prev1 + " ";
            for (String k : m.keySet()) {
                String w = null;
                if (tp != null && k.startsWith(tp)) {
                    w = k.substring(tp.length());
                } else if (k.startsWith(bp) && k.indexOf(' ', bp.length()) < 0) {
                    w = k.substring(bp.length());
                }
                if (w != null && !w.isEmpty()) {
                    cand.put(w, 0f);
                }
            }
        }
        Map<String, Map<String, Integer>> seed = seeds.get(lang);
        Map<String, Integer> next = seed == null ? null : seed.get(prev1);
        if (next != null) {
            for (String w : next.keySet()) {
                cand.put(w, 0f);
            }
        }
        for (Map.Entry<String, Float> e : cand.entrySet()) {
            e.setValue(score(lang, prev2, prev1, e.getKey()));
        }
        List<Map.Entry<String, Float>> sorted = new ArrayList<>(cand.entrySet());
        Collections.sort(sorted, (a, b) -> Float.compare(b.getValue(), a.getValue()));
        for (Map.Entry<String, Float> e : sorted) {
            if (out.size() >= max) {
                break;
            }
            out.add(e.getKey());
        }
        return out;
    }

    private static Map<String, Integer> readLearned(Context c, String lang) {
        Map<String, Integer> map = new ConcurrentHashMap<>();
        Set<String> set = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(lang, null);
        if (set != null) {
            for (String s : set) {
                int i = s.lastIndexOf(':');
                if (i > 0) {
                    try {
                        map.put(s.substring(0, i), Integer.parseInt(s.substring(i + 1)));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return map;
    }

    public static void persist(Context c) {
        SharedPreferences.Editor ed = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        for (Map.Entry<String, Map<String, Integer>> l : learned.entrySet()) {
            Set<String> set = new HashSet<>();
            for (Map.Entry<String, Integer> e : l.getValue().entrySet()) {
                set.add(e.getKey() + ":" + e.getValue());
            }
            ed.putStringSet(l.getKey(), set);
        }
        ed.apply();
    }
}
