package com.limelight.duo;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Word tracking, suggestions and Gboard-style autocorrect for the Duo keyboard:
 *  - corrections on space using the built-in dictionary and where the finger landed
 *  - learns words you keep (and words whose correction you undo with backspace)
 *  - capitalizes German nouns and the start of a sentence
 *  - double space types ". "
 *
 * The keyboard only knows what was typed since the last boundary; any mouse action
 * or navigation key resets the state.
 */
public class DuoAutocorrect {

    public interface Listener {
        /** cells: 3 entries (may be empty strings); highlight: index of the bold cell or -1. */
        void onSuggestions(String[] cells, int highlight);
    }

    private static final String PREFS = "duo_learned_words";
    private static final String KEY = "words";
    private static final long DOUBLE_SPACE_MS = 1500;
    /** Token for dictionary lookups on the worker (a newer one replaces an older one). */
    private static final Object LOOKUP = new Object();

    private final Context context;
    private final DuoInputSink sink;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread worker;
    private Handler workerHandler;
    private final List<Listener> listeners = new ArrayList<>();
    private Map<String, Integer> learnedAtStart;

    private final StringBuilder word = new StringBuilder();
    private final List<Float> tapX = new ArrayList<>();
    private final List<Float> tapY = new ArrayList<>();
    private String resultWord = "";
    private DuoDictionary.Result result;

    /** Last automatic correction, so an immediate backspace can undo it. */
    private String undoOriginal;
    private String undoCorrected;

    /** Last character sent (0 = unknown) and whether a sentence just ended. */
    private char lastChar;
    private long lastSpaceTime;
    private boolean sentenceEnded;
    private boolean autoShift;

    // What each suggestion cell stands for.
    private final String[] cellText = {"", "", ""};
    private final boolean[] cellIsTyped = {false, false, false};

    private final Runnable saveLearned = this::persistLearned;

    /** Context for next-word prediction: the last two committed words (lowercase, START at a sentence start). */
    private String prev1, prev2;
    /** The last word that went into the text as it stands there, and the context before it. */
    private String lastWord, lastWordPrev1, lastWordPrev2;


    /** Bumped on every input event; a late correction only applies if nothing happened since. */
    private int generation;

    public DuoAutocorrect(Context context, DuoInputSink sink) {
        this.context = context.getApplicationContext();
        this.sink = sink;
        learnedAtStart = readLearned();
        DuoDictionary.setLanguage(context, DuoBridge.getLanguage(context), learnedAtStart);
        DuoNextWord.load(context, DuoBridge.getLanguage(context));
        prev1 = DuoNextWord.START;
    }

    /** The LatinIME engine (Plus build) once loaded, else null. */
    private DuoEngine engine() {
        return DuoEngine.Registry.get(context, DuoDictionary.language());
    }

    /** A word went into the text: remember it as context (and learn the phrase). */
    private void commit(String w) {
        if (w == null || w.isEmpty()) {
            return;
        }
        final DuoEngine eng = engine();
        if (eng != null) {
            final String p1 = prev1, p2 = prev2, word = w;
            worker().post(() -> eng.learn(p2, p1, word));
        }
        lastWord = w;
        lastWordPrev1 = prev1;
        lastWordPrev2 = prev2;
        String lw = w.toLowerCase();
        DuoNextWord.record(DuoDictionary.language(), prev2, prev1, lw);
        prev2 = prev1;
        prev1 = lw;
        schedulePersist();
    }

    /** The last committed word was replaced (late autocorrect). */
    private void recommit(String typed, String corrected) {
        if (typed.equals(lastWord)) {
            lastWord = corrected;
        }
        if (prev1 != null && prev1.equals(typed.toLowerCase())) {
            prev1 = corrected.toLowerCase();
            DuoNextWord.record(DuoDictionary.language(), null, prev2, prev1);
        }
    }

    public void addListener(Listener l) {
        listeners.add(l);
        publish();
    }

    /** "de" or "en": switches dictionary and autocorrect language. */
    public void setLanguage(String lang) {
        persistLearned();
        DuoDictionary.setLanguage(context, lang, readLearned());
        DuoNextWord.load(context, lang);
        onBoundary();
    }

    public void close() {
        main.removeCallbacks(saveLearned);
        persistLearned();
        DuoNextWord.persist(context);
        final Handler wh = workerHandler;
        if (wh != null) {
            wh.post(DuoEngine.Registry::flushAll);
        } else {
            DuoEngine.Registry.flushAll();
        }
        if (worker != null) {
            worker.quitSafely();
            worker = null;
            workerHandler = null;
        }
    }

    private Handler worker() {
        if (workerHandler == null) {
            worker = new HandlerThread("DuoAutocorrect");
            worker.start();
            workerHandler = new Handler(worker.getLooper());
        }
        return workerHandler;
    }

    /** True if the next letter should be capitalized (start of a sentence). */
    public boolean autoShift() {
        return autoShift;
    }

    // ---------------- events from the keyboard ----------------

    /** tx/ty: touch position in key units (q = 0,0), NaN if unknown. */
    public void onLetter(char c, float tx, float ty) {
        generation++;
        undoOriginal = null;
        autoShift = false;
        sentenceEnded = false;
        word.append(c);
        tapX.add(tx);
        tapY.add(ty);
        lastChar = c;
        request();
    }

    /** Punctuation, digits and other printable characters. */
    public void onChar(char c) {
        generation++;
        // English contractions: the apostrophe is part of the word (don't, I'm).
        if (c == '\'' && word.length() > 0 && "en".equals(DuoDictionary.language())) {
            undoOriginal = null;
            word.append(c);
            tapX.add(Float.NaN);
            tapY.add(Float.NaN);
            lastChar = c;
            request();
            return;
        }
        finishWord(true);
        lastWord = null; // punctuation after it: backspace removes that, not the word
        undoOriginal = null;
        autoShift = false;
        lastChar = c;
        sentenceEnded = c == '.' || c == '!' || c == '?';
        if (sentenceEnded) {
            prev2 = null;
            prev1 = DuoNextWord.START;
        } else if (c == ',' || c == ';' || c == ':') {
            // keeps the context
        } else {
            prev1 = prev2 = null; // digits, symbols: no idea what comes next
        }
        publish();
    }

    public void onEnter() {
        generation++;
        finishWord(true);
        lastWord = null;
        prev2 = null;
        prev1 = DuoNextWord.START;
        undoOriginal = null;
        lastChar = '\n';
        sentenceEnded = false;
        autoShift = true;
        publish();
    }

    /**
     * @return true if the backspace was fully handled here (correction undone),
     *         false if the keyboard should send a normal backspace.
     */
    public boolean onBackspace() {
        generation++;
        autoShift = false;
        if (undoOriginal != null) {
            // Gboard behaviour: backspace right after an autocorrect restores the typed word,
            // and that word is learned so it isn't corrected again.
            DuoKeys.tap(sink, DuoKeys.VK_BACK, undoCorrected.length() + 1);
            sink.sendText(undoOriginal);
            learn(undoOriginal, 2);
            word.setLength(0);
            word.append(undoOriginal);
            tapX.clear();
            tapY.clear();
            for (int i = 0; i < undoOriginal.length(); i++) {
                tapX.add(Float.NaN);
                tapY.add(Float.NaN);
            }
            undoOriginal = null;
            lastChar = word.charAt(word.length() - 1);
            request();
            return true;
        }
        if (word.length() > 0) {
            word.setLength(word.length() - 1);
            if (!tapX.isEmpty()) {
                tapX.remove(tapX.size() - 1);
                tapY.remove(tapY.size() - 1);
            }
            lastChar = word.length() > 0 ? word.charAt(word.length() - 1) : 0;
            request();
        } else if (lastChar == ' ' && lastWord != null) {
            // Back into the previous word (word, space, backspace): Gboard picks the whole word
            // up again, so suggestions and corrections see all of it, not just new letters.
            word.setLength(0);
            word.append(lastWord);
            tapX.clear();
            tapY.clear();
            for (int i = 0; i < lastWord.length(); i++) {
                tapX.add(Float.NaN);
                tapY.add(Float.NaN);
            }
            prev1 = lastWordPrev1;
            prev2 = lastWordPrev2;
            lastWord = null;
            lastChar = word.charAt(word.length() - 1);
            sentenceEnded = false;
            request();
        } else {
            lastChar = 0;
            sentenceEnded = false;
            prev1 = prev2 = null;
            lastWord = null;
            publish();
        }
        return false;
    }

    /**
     * Called when space is tapped (without modifiers).
     * @return true if the space was already sent here.
     */
    public boolean onSpace() {
        generation++;
        long now = SystemClock.uptimeMillis();
        char before = lastChar;

        // Double space after a word: ". " like Gboard.
        if (word.length() == 0 && lastChar == ' ' && now - lastSpaceTime < DOUBLE_SPACE_MS && !sentenceEnded) {
            DuoKeys.tap(sink, DuoKeys.VK_BACK, 1);
            DuoKeys.tap(sink, DuoKeys.VK_OEM_PERIOD, 1);
            DuoKeys.tap(sink, DuoKeys.VK_SPACE, 1);
            lastChar = ' ';
            lastSpaceTime = 0;
            sentenceEnded = false;
            autoShift = true;
            undoOriginal = null;
            lastWord = null;
            publish();
            return true;
        }

        boolean wasWord = word.length() > 0;
        if (!wasWord) {
            lastWord = null; // second space: backspace only removes a space
        }
        boolean sent = false;
        String typed = word.toString();
        DuoDictionary.Result r = typed.equals(resultWord) ? result : null;
        if (r == null && wasWord && (DuoDictionary.get() != null || engine() != null)) {
            // Result not back yet: send the space right away (no waiting on the
            // dictionary in the UI thread) and correct the word as soon as it's known.
            DuoKeys.tap(sink, DuoKeys.VK_SPACE, 1);
            correctLater(typed, floats(tapX), floats(tapY));
            undoOriginal = null;
            sent = true;
            commit(typed);
        }
        if (!sent && r != null && r.autocorrect && !r.suggestions.isEmpty()) {
            String corrected = matchCase(typed, r.suggestions.get(0));
            if (!corrected.equals(typed)) {
                replaceWord(typed, corrected);
                DuoKeys.tap(sink, DuoKeys.VK_SPACE, 1);
                undoOriginal = typed;
                undoCorrected = corrected;
                sent = true;
                commit(corrected);
            }
        }
        if (!sent) {
            undoOriginal = null;
            finishWord(false);
            if (wasWord) {
                commit(typed);
            }
        }
        resetWord();

        // Only arm double-space after a word, not after a run of spaces.
        // Arm double space after any typed character (word, number, symbol), not after a space.
        lastSpaceTime = (wasWord || (before != 0 && before != ' ' && before != '\n')) ? now : 0;
        if (sentenceEnded) {
            autoShift = true;
            sentenceEnded = false;
        }
        lastChar = ' ';
        publish();
        return sent;
    }

    /** Any other key, a click or a mouse movement: context unknown, start fresh. */
    public void onBoundary() {
        generation++;
        undoOriginal = null;
        autoShift = false;
        sentenceEnded = false;
        lastChar = 0;
        lastSpaceTime = 0;
        prev1 = prev2 = null;
        lastWord = null;
        if (word.length() > 0 || !resultWord.isEmpty()) {
            resetWord();
        }
        publish();
    }

    /** A suggestion cell was tapped. */
    public void pick(int cell) {
        generation++;
        if (cell < 0 || cell > 2 || cellText[cell].isEmpty()) {
            return;
        }
        String typed = word.toString();
        String chosen = cellIsTyped[cell] ? typed : cellText[cell];
        if (!cellIsTyped[cell]) {
            replaceWord(typed, cellText[cell]);
        } else {
            // Tapping „typed“ means: keep it as it is.
            learn(typed, 2);
        }
        commit(chosen);
        autoShift = false;
        sentenceEnded = false;
        DuoKeys.tap(sink, DuoKeys.VK_SPACE, 1);
        undoOriginal = null;
        resetWord();
        lastChar = ' ';
        lastSpaceTime = 0;
        publish();
    }

    // ---------------- internals ----------------

    /** Space already sent; looks the word up off the UI thread and fixes it if nothing was typed since. */
    private void correctLater(final String typed, final float[] tx, final float[] ty) {
        final DuoDictionary dict = DuoDictionary.get();
        final DuoEngine eng = engine();
        if (dict == null && eng == null) {
            return;
        }
        final String p1 = prev1, p2 = prev2;
        final int gen = generation;
        Handler h = worker();
        h.removeCallbacksAndMessages(LOOKUP);
        h.postAtTime(() -> {
            final DuoDictionary.Result r = eng != null ? eng.lookup(typed, tx, ty, p2, p1)
                    : dict.lookup(typed, tx, ty);
            main.post(() -> {
                if (generation != gen) {
                    return; // more was typed meanwhile: leave the word as it is
                }
                if (r != null && r.autocorrect && !r.suggestions.isEmpty()) {
                    String corrected = matchCase(typed, r.suggestions.get(0));
                    if (!corrected.equals(typed)) {
                        DuoKeys.tap(sink, DuoKeys.VK_BACK, typed.length() + 1);
                        sink.sendText(corrected);
                        DuoKeys.tap(sink, DuoKeys.VK_SPACE, 1);
                        undoOriginal = typed;
                        undoCorrected = corrected;
                        recommit(typed, corrected);
                        publish();
                        return;
                    }
                }
                if (r != null && !r.known && typed.length() >= 2) {
                    learn(typed, 1);
                }
            });
        }, LOOKUP, android.os.SystemClock.uptimeMillis());
    }

    /** The word ends without a correction: learn it if it's new. */
    private void finishWord(boolean resetAfter) {
        String typed = word.toString();
        DuoDictionary d = DuoDictionary.get();
        if (typed.length() >= 2 && d != null) {
            DuoDictionary.Result r = typed.equals(resultWord) ? result : null;
            if (r != null && !r.known) {
                learn(typed, 1);
            }
        }
        if (resetAfter) {
            if (!typed.isEmpty()) {
                commit(typed);
            }
            resetWord();
        }
    }

    private void learn(String w, int inc) {
        DuoDictionary d = DuoDictionary.get();
        if (d == null || w.length() < 2) {
            return;
        }
        d.learn(w, inc);
        main.removeCallbacks(saveLearned);
        main.postDelayed(saveLearned, 2000);
    }

    private void schedulePersist() {
        main.removeCallbacks(saveLearned);
        main.postDelayed(saveLearned, 4000);
    }

    private Map<String, Integer> readLearned() {
        Map<String, Integer> map = new HashMap<>();
        Set<String> set = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, null);
        if (set != null) {
            for (String s : set) {
                int c = s.lastIndexOf(':');
                if (c > 0) {
                    try {
                        map.put(s.substring(0, c), Integer.parseInt(s.substring(c + 1)));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return map;
    }

    private void persistLearned() {
        DuoNextWord.persist(context);
        Map<String, Integer> all = DuoDictionary.learnedAll();
        if (all.isEmpty()) {
            return;
        }
        // Keep words learned earlier that aren't loaded right now.
        Map<String, Integer> merged = new HashMap<>(readLearned());
        merged.putAll(all);
        Set<String> set = new HashSet<>();
        for (Map.Entry<String, Integer> e : merged.entrySet()) {
            set.add(e.getKey() + ":" + e.getValue());
        }
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        p.edit().putStringSet(KEY, set).apply();
    }

    private void replaceWord(String typed, String replacement) {
        DuoKeys.tap(sink, DuoKeys.VK_BACK, typed.length());
        sink.sendText(replacement);
    }

    private void resetWord() {
        word.setLength(0);
        tapX.clear();
        tapY.clear();
        resultWord = "";
        result = null;
        publish();
    }

    private static float[] floats(List<Float> list) {
        float[] out = new float[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        return out;
    }

    private void request() {
        final String typed = word.toString();
        if (typed.isEmpty()) {
            resultWord = "";
            result = null;
            publish();
            return;
        }
        publish();
        final DuoDictionary dict = DuoDictionary.get();
        final DuoEngine eng = engine();
        if (dict == null && eng == null) {
            return;
        }
        final String p1 = prev1, p2 = prev2;
        final float[] tx = floats(tapX);
        final float[] ty = floats(tapY);
        Handler h = worker();
        h.removeCallbacksAndMessages(LOOKUP);
        h.postAtTime(() -> {
            final DuoDictionary.Result r = eng != null ? eng.lookup(typed, tx, ty, p2, p1)
                    : dict.lookup(typed, tx, ty);
            main.post(() -> {
                // Only use it if the word hasn't changed meanwhile.
                if (typed.equals(word.toString())) {
                    resultWord = typed;
                    result = r;
                    publish();
                }
            });
        }, LOOKUP, android.os.SystemClock.uptimeMillis());
    }

    private void publish() {
        for (int i = 0; i < 3; i++) {
            cellText[i] = "";
            cellIsTyped[i] = false;
        }
        String typed = word.toString();
        int highlight = -1;
        if (!typed.isEmpty()) {
            List<String> sug = new ArrayList<>();
            boolean auto = false;
            if (result != null && typed.equals(resultWord)) {
                for (String s : result.suggestions) {
                    sug.add(matchCase(typed, s));
                }
                auto = result.autocorrect;
                // Context: completions that fit the previous words move up (keeps the first
                // one when it's an autocorrect, so space does what the bold cell shows).
                if (prev1 != null && sug.size() > 1) {
                    final String lang = DuoDictionary.language();
                    final List<String> base = new ArrayList<>(sug);
                    List<String> tail = new ArrayList<>(auto ? base.subList(1, base.size()) : base);
                    java.util.Collections.sort(tail, (a, b) -> {
                        float sa = DuoNextWord.score(lang, prev2, prev1, a);
                        float sb = DuoNextWord.score(lang, prev2, prev1, b);
                        return sa == sb ? Integer.compare(base.indexOf(a), base.indexOf(b)) : Float.compare(sb, sa);
                    });
                    sug.clear();
                    if (auto) {
                        sug.add(base.get(0));
                    }
                    sug.addAll(tail);
                }
            }
            if (auto && !sug.isEmpty() && !sug.get(0).equals(typed)) {
                // „typed“ | best (bold, applied on space) | alternative
                cellText[0] = typed;
                cellIsTyped[0] = true;
                cellText[1] = sug.get(0);
                if (sug.size() > 1) {
                    cellText[2] = sug.get(1);
                }
            } else {
                // alternative | typed (bold) | alternative
                cellText[1] = typed;
                cellIsTyped[1] = true;
                int k = 0;
                for (String s : sug) {
                    if (s.equals(typed)) {
                        continue;
                    }
                    if (k == 0) {
                        cellText[0] = s;
                    } else if (k == 1) {
                        cellText[2] = s;
                    }
                    k++;
                }
            }
            highlight = 1;
        } else if (prev1 != null && (lastChar == ' ' || lastChar == '\n' || lastChar == 0)) {
            // Nothing typed yet: what probably comes next (best in the middle).
            DuoDictionary d = DuoDictionary.get();
            DuoEngine eng = engine();
            List<String> next = eng != null ? eng.predict(prev2, prev1, 3)
                    : DuoNextWord.predict(DuoDictionary.language(), prev2, prev1, 3);
            int[] slot = {1, 0, 2};
            for (int i = 0; i < next.size() && i < 3; i++) {
                String w = eng != null ? next.get(i) : d != null ? d.displayOf(next.get(i)) : next.get(i);
                if (w.isEmpty()) {
                    continue;
                }
                if (autoShift || DuoNextWord.START.equals(prev1)) {
                    w = Character.toUpperCase(w.charAt(0)) + w.substring(1);
                }
                cellText[slot[i]] = w;
            }
        }
        if (!listeners.isEmpty()) {
            String[] cells = new String[3];
            for (int i = 0; i < 3; i++) {
                cells[i] = (cellIsTyped[i] && i == 0) ? "“" + cellText[i] + "”" : cellText[i];
            }
            for (Listener l : listeners) {
                l.onSuggestions(cells, highlight);
            }
        }
    }

    /** Keeps nouns capitalized; capitalizes the suggestion if the typed word was. */
    private static String matchCase(String typed, String suggestion) {
        if (typed.isEmpty() || suggestion.isEmpty()) {
            return suggestion;
        }
        if (Character.isUpperCase(typed.charAt(0)) && Character.isLowerCase(suggestion.charAt(0))) {
            return Character.toUpperCase(suggestion.charAt(0)) + suggestion.substring(1);
        }
        return suggestion;
    }
}
