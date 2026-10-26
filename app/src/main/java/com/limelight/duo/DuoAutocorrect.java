package com.limelight.duo;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/**
 * Word tracking, suggestions and Gboard-style autocorrect on space, using the
 * built-in German + English dictionary (offline).
 *
 * The keyboard only knows what was typed since the last word boundary; any
 * mouse action or navigation key resets the word.
 */
public class DuoAutocorrect {

    public interface Listener {
        /** cells: 3 entries (may be empty strings); highlight: index of the bold cell or -1. */
        void onSuggestions(String[] cells, int highlight);
    }

    private final DuoInputSink sink;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread worker;
    private Handler workerHandler;
    private Listener listener;

    private final StringBuilder word = new StringBuilder();
    private String resultWord = "";
    private DuoDictionary.Result result;

    /** Last automatic correction, so an immediate backspace can undo it. */
    private String undoOriginal;
    private String undoCorrected;

    // What each suggestion cell stands for.
    private final String[] cellText = {"", "", ""};
    private final boolean[] cellIsTyped = {false, false, false};

    public DuoAutocorrect(Context context, DuoInputSink sink) {
        this.sink = sink;
        DuoDictionary.load(context);
    }

    public void setListener(Listener l) {
        listener = l;
        publish();
    }

    public void close() {
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

    // ---------------- events from the keyboard ----------------

    public void onLetter(char c) {
        undoOriginal = null;
        word.append(c);
        request();
    }

    /**
     * @return true if the backspace was fully handled here (correction undone),
     *         false if the keyboard should send a normal backspace.
     */
    public boolean onBackspace() {
        if (undoOriginal != null) {
            // Gboard behaviour: backspace right after an autocorrect restores the typed word.
            DuoKeys.tap(sink, DuoKeys.VK_BACK, undoCorrected.length() + 1);
            sink.sendText(undoOriginal);
            word.setLength(0);
            word.append(undoOriginal);
            undoOriginal = null;
            request();
            return true;
        }
        if (word.length() > 0) {
            word.setLength(word.length() - 1);
            request();
        }
        return false;
    }

    /**
     * Called when space is tapped (without modifiers).
     * @return true if the space was already sent here.
     */
    public boolean onSpace() {
        String typed = word.toString();
        DuoDictionary.Result r = typed.equals(resultWord) ? result : null;
        if (r == null && !typed.isEmpty() && DuoDictionary.get() != null) {
            // Result not back yet: decide now.
            r = DuoDictionary.get().lookup(typed);
        }
        if (r != null && r.autocorrect && !r.suggestions.isEmpty()) {
            String corrected = matchCase(typed, r.suggestions.get(0));
            if (!corrected.equals(typed)) {
                replaceWord(typed, corrected);
                DuoKeys.tap(sink, DuoKeys.VK_SPACE, 1);
                undoOriginal = typed;
                undoCorrected = corrected;
                resetWord();
                return true;
            }
        }
        undoOriginal = null;
        resetWord();
        return false;
    }

    /** Any other key, a click or a mouse movement ends the word. */
    public void onBoundary() {
        undoOriginal = null;
        if (word.length() > 0 || !resultWord.isEmpty()) {
            resetWord();
        }
    }

    /** A suggestion cell was tapped. */
    public void pick(int cell) {
        if (cell < 0 || cell > 2 || cellText[cell].isEmpty()) {
            return;
        }
        String typed = word.toString();
        if (!cellIsTyped[cell]) {
            replaceWord(typed, cellText[cell]);
        }
        DuoKeys.tap(sink, DuoKeys.VK_SPACE, 1);
        undoOriginal = null;
        resetWord();
    }

    // ---------------- internals ----------------

    private void replaceWord(String typed, String replacement) {
        DuoKeys.tap(sink, DuoKeys.VK_BACK, typed.length());
        sink.sendText(replacement);
    }

    private void resetWord() {
        word.setLength(0);
        resultWord = "";
        result = null;
        publish();
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
        if (dict == null) {
            return;
        }
        Handler h = worker();
        h.removeCallbacksAndMessages(null);
        h.post(() -> {
            final DuoDictionary.Result r = dict.lookup(typed);
            main.post(() -> {
                // Only use it if the word hasn't changed meanwhile.
                if (typed.equals(word.toString())) {
                    resultWord = typed;
                    result = r;
                    publish();
                }
            });
        });
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
            }
            if (auto && !sug.isEmpty()) {
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
                if (sug.size() > 0) {
                    cellText[0] = sug.get(0);
                }
                if (sug.size() > 1) {
                    cellText[2] = sug.get(1);
                }
            }
            highlight = 1;
        }
        if (listener != null) {
            String[] cells = new String[3];
            for (int i = 0; i < 3; i++) {
                cells[i] = (cellIsTyped[i] && i == 0) ? "„" + cellText[i] + "“" : cellText[i];
            }
            listener.onSuggestions(cells, highlight);
        }
    }

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
