package com.limelight.duo;

import android.content.Context;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A stronger suggestion engine for the Duo keyboard. The "Plus" build ships the AOSP LatinIME
 * engine (com.limelight.duo.plus.DuoLatinEngine); the normal build has none and uses
 * DuoDictionary + DuoNextWord.
 */
public interface DuoEngine {
    /** Loads the language (blocking, background thread). False if it can't. */
    boolean load(Context context, String lang);

    /** Corrections / completions of the word being typed (taps in key units, may be NaN). */
    DuoDictionary.Result lookup(String typed, float[] tapX, float[] tapY, String prev2, String prev1);

    /** Likely next words (lowercase prev words, DuoNextWord.START at a sentence start). */
    List<String> predict(String prev2, String prev1, int max);

    /** A word was committed after prev2 prev1: learn it (personal history with forgetting). */
    void learn(String prev2, String prev1, String word);

    void flush();

    /** The engine for a language once it's loaded, else null (also null in the normal build). */
    final class Registry {
        private Registry() {}

        private static final Map<String, DuoEngine> ready = new ConcurrentHashMap<>();
        private static final java.util.Set<String> loading =
                java.util.Collections.synchronizedSet(new java.util.HashSet<>());
        private static final java.util.Set<String> failed =
                java.util.Collections.synchronizedSet(new java.util.HashSet<>());
        private static Boolean available;

        public static boolean available() {
            if (available == null) {
                try {
                    Class.forName("com.limelight.duo.plus.DuoLatinEngine");
                    available = true;
                } catch (Throwable t) {
                    available = false;
                }
            }
            return available;
        }

        public static DuoEngine get(Context context, String lang) {
            DuoEngine e = ready.get(lang);
            if (e != null || !available() || failed.contains(lang) || !loading.add(lang)) {
                return e;
            }
            final Context app = context.getApplicationContext();
            new Thread(() -> {
                try {
                    DuoEngine n = (DuoEngine) Class.forName("com.limelight.duo.plus.DuoLatinEngine")
                            .getDeclaredConstructor().newInstance();
                    if (n.load(app, lang)) {
                        ready.put(lang, n);
                    } else {
                        failed.add(lang);
                    }
                } catch (Throwable t) {
                    failed.add(lang);
                    com.limelight.LimeLog.warning("Duo: keyboard engine failed: " + t);
                } finally {
                    loading.remove(lang);
                }
            }, "DuoEngine").start();
            return null;
        }

        /** Which keyboard engine is in use, for Settings (also starts loading it). */
        public static String status(Context context, String lang) {
            if (!available()) {
                return "Standard (built-in word list)";
            }
            if (ready.containsKey(lang)) {
                return "LatinIME (Plus) · active";
            }
            if (failed.contains(lang)) {
                return "LatinIME (Plus) · failed to load, using the built-in word list";
            }
            get(context, lang);
            return "LatinIME (Plus) · loading";
        }

        public static void flushAll() {
            for (DuoEngine e : ready.values()) {
                try {
                    e.flush();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
