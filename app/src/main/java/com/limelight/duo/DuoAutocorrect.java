package com.limelight.duo;

import android.content.Context;
import android.view.textservice.SentenceSuggestionsInfo;
import android.view.textservice.SpellCheckerSession;
import android.view.textservice.SuggestionsInfo;
import android.view.textservice.TextInfo;
import android.view.textservice.TextServicesManager;

import com.limelight.LimeLog;

import java.util.ArrayList;
import java.util.List;

/**
 * Word tracking, suggestions and Gboard-style autocorrect on space, using the
 * system spell checker (the same dictionary as the phone keyboard).
 *
 * The keyboard only knows what was typed since the last word boundary; any
 * mouse action or navigation key resets the word.
 */
public class DuoAutocorrect implements SpellCheckerSession.SpellCheckerSessionListener {

    public interface Listener {
        /** cells: 3 entries (may be empty strings); highlight: index of the bold cell or -1. */
        void onSuggestions(String[] cells, int highlight);
    }

    private final DuoInputSink sink;
    private final Context context;
    private SpellCheckerSession session;
    private boolean sessionFailed;
    private Listener listener;

    private final StringBuilder word = new StringBuilder();
    private String resultWord = "";
    private final List<String> suggestions = new ArrayList<>();
    private boolean looksLikeTypo;
    private boolean recommended;

    /** Last automatic correction, so an immediate backspace can undo it. */
    private String undoOriginal;
    private String undoCorrected;

    // What each suggestion cell stands for.
    private final String[] cellText = {"", "", ""};
    private final boolean[] cellIsTyped = {false, false, false};

    public DuoAutocorrect(Context context, DuoInputSink sink) {
        this.sink = sink;
        this.context = context;
    }

    private void ensureSession() {
        if (session != null || sessionFailed) {
            return;
        }
        try {
            TextServicesManager tsm = (TextServicesManager) context.getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE);
            if (tsm != null) {
                session = tsm.newSpellCheckerSession(null, null, this, true);
            }
        } catch (Throwable t) {
            LimeLog.warning("Duo: spell checker not available: " + t);
            session = null;
        }
        if (session == null) {
            sessionFailed = true;
            LimeLog.info("Duo: no system spell checker, suggestions disabled");
        }
    }

    public void setListener(Listener l) {
        listener = l;
        publish();
    }

    public void close() {
        if (session != null) {
            session.close();
            session = null;
        }
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
        if (!typed.isEmpty() && typed.equals(resultWord) && looksLikeTypo && recommended && !suggestions.isEmpty()) {
            String corrected = matchCase(typed, suggestions.get(0));
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
        suggestions.clear();
        looksLikeTypo = false;
        recommended = false;
        publish();
    }

    private void request() {
        if (word.length() == 0) {
            resultWord = "";
            suggestions.clear();
            looksLikeTypo = recommended = false;
            publish();
            return;
        }
        ensureSession();
        if (session == null) {
            publish();
            return;
        }
        try {
            session.getSentenceSuggestions(new TextInfo[]{new TextInfo(word.toString())}, 3);
        } catch (Throwable t) {
            LimeLog.warning("Duo: spell check failed: " + t);
        }
    }

    @Override
    public void onGetSuggestions(SuggestionsInfo[] results) {
        // Not used (sentence API below).
    }

    @Override
    public void onGetSentenceSuggestions(SentenceSuggestionsInfo[] results) {
        if (results == null || results.length == 0 || results[0] == null
                || results[0].getSuggestionsCount() == 0) {
            return;
        }
        SuggestionsInfo info = results[0].getSuggestionsInfoAt(0);
        if (info == null) {
            return;
        }
        // Results arrive asynchronously; only use them for the word still being typed.
        String current = word.toString();
        suggestions.clear();
        int n = Math.max(0, info.getSuggestionsCount());
        for (int i = 0; i < n && suggestions.size() < 3; i++) {
            String s = info.getSuggestionAt(i);
            if (s != null && !s.isEmpty() && !s.equalsIgnoreCase(current)) {
                suggestions.add(s);
            }
        }
        int attrs = info.getSuggestionsAttributes();
        boolean inDictionary = (attrs & SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY) != 0;
        looksLikeTypo = !inDictionary && (attrs & SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO) != 0;
        recommended = (attrs & SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS) != 0;
        resultWord = current;
        publish();
    }

    private void publish() {
        for (int i = 0; i < 3; i++) {
            cellText[i] = "";
            cellIsTyped[i] = false;
        }
        String typed = word.toString();
        int highlight = -1;
        if (!typed.isEmpty()) {
            boolean fresh = typed.equals(resultWord);
            List<String> sug = new ArrayList<>();
            if (fresh) {
                for (String s : suggestions) {
                    sug.add(matchCase(typed, s));
                }
            }
            if (fresh && looksLikeTypo && recommended && !sug.isEmpty()) {
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
