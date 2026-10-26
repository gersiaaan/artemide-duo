package com.limelight.duo;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

/**
 * Invisible text target for Gboard inside the Duo panel.
 *
 * Gboard edits a local buffer (with its own autocorrect, suggestions, glide typing);
 * after every change the difference to what the PC already has is sent: backspaces for
 * removed characters and Unicode text for new ones. This assumes the PC cursor stays at
 * the end of the text, which holds for normal typing and deleting.
 */
public class DuoImeView extends View {
    private final DuoInputSink sink;
    private boolean shown;

    public DuoImeView(Context context, DuoInputSink sink) {
        super(context);
        this.sink = sink;
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    public boolean isShown2() {
        return shown;
    }

    /** Shows Gboard bound to this view, or hides it. */
    public void toggle() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) {
            return;
        }
        if (shown) {
            imm.hideSoftInputFromWindow(getWindowToken(), 0);
            clearFocus();
            shown = false;
        } else {
            requestFocus();
            imm.restartInput(this);
            imm.showSoftInput(this, InputMethodManager.SHOW_FORCED);
            shown = true;
        }
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, android.graphics.Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        if (!gainFocus) {
            shown = false;
        }
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_FULLSCREEN
                | EditorInfo.IME_ACTION_NONE;
        outAttrs.initialSelStart = 0;
        outAttrs.initialSelEnd = 0;
        return new Connection(this);
    }

    private final class Connection extends BaseInputConnection {
        /** What the PC currently shows for this session. */
        private final StringBuilder sent = new StringBuilder();

        Connection(View view) {
            super(view, true);
        }

        private void sync() {
            Editable e = getEditable();
            if (e == null) {
                return;
            }
            String now = e.toString();
            int common = 0;
            int max = Math.min(now.length(), sent.length());
            while (common < max && now.charAt(common) == sent.charAt(common)) {
                common++;
            }
            int remove = sent.length() - common;
            if (remove > 0) {
                DuoKeys.tap(sink, DuoKeys.VK_BACK, remove);
            }
            String add = now.substring(common);
            if (!add.isEmpty()) {
                // Newlines go out as Enter; everything else as Unicode text.
                int start = 0;
                for (int i = 0; i < add.length(); i++) {
                    if (add.charAt(i) == '\n') {
                        if (i > start) {
                            sink.sendText(add.substring(start, i));
                        }
                        DuoKeys.tap(sink, DuoKeys.VK_RETURN, 1);
                        start = i + 1;
                    }
                }
                if (start < add.length()) {
                    sink.sendText(add.substring(start));
                }
            }
            sent.setLength(0);
            sent.append(now);

            // Keep the buffer small; Gboard only needs a bit of context.
            if (e.length() > 400 && getComposingSpanStart(e) < 0) {
                int cut = e.length() - 200;
                e.delete(0, cut);
                sent.delete(0, cut);
            }
        }

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            boolean r = super.commitText(text, newCursorPosition);
            sync();
            return r;
        }

        @Override
        public boolean setComposingText(CharSequence text, int newCursorPosition) {
            boolean r = super.setComposingText(text, newCursorPosition);
            sync();
            return r;
        }

        @Override
        public boolean finishComposingText() {
            boolean r = super.finishComposingText();
            sync();
            return r;
        }

        @Override
        public boolean deleteSurroundingText(int beforeLength, int afterLength) {
            Editable e = getEditable();
            if (beforeLength > 0 && e != null && e.length() == 0) {
                // Nothing left locally: delete on the PC directly.
                DuoKeys.tap(sink, DuoKeys.VK_BACK, beforeLength);
                return true;
            }
            boolean r = super.deleteSurroundingText(beforeLength, afterLength);
            sync();
            return r;
        }

        @Override
        public boolean sendKeyEvent(KeyEvent event) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                switch (event.getKeyCode()) {
                    case KeyEvent.KEYCODE_DEL: {
                        Editable e = getEditable();
                        if (e != null && e.length() > 0) {
                            int end = e.length();
                            e.delete(end - 1, end);
                            sync();
                        } else {
                            DuoKeys.tap(sink, DuoKeys.VK_BACK, 1);
                        }
                        return true;
                    }
                    case KeyEvent.KEYCODE_ENTER:
                        DuoKeys.tap(sink, DuoKeys.VK_RETURN, 1);
                        resetBuffer();
                        return true;
                    case KeyEvent.KEYCODE_TAB:
                        DuoKeys.tap(sink, DuoKeys.VK_TAB, 1);
                        resetBuffer();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        DuoKeys.tap(sink, DuoKeys.VK_LEFT, 1);
                        resetBuffer();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        DuoKeys.tap(sink, DuoKeys.VK_RIGHT, 1);
                        resetBuffer();
                        return true;
                    default:
                        break;
                }
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                switch (event.getKeyCode()) {
                    case KeyEvent.KEYCODE_DEL:
                    case KeyEvent.KEYCODE_ENTER:
                    case KeyEvent.KEYCODE_TAB:
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        return true;
                    default:
                        break;
                }
            }
            return super.sendKeyEvent(event);
        }

        @Override
        public boolean performEditorAction(int actionCode) {
            DuoKeys.tap(sink, DuoKeys.VK_RETURN, 1);
            resetBuffer();
            return true;
        }

        private void resetBuffer() {
            Editable e = getEditable();
            if (e != null) {
                e.clear();
            }
            sent.setLength(0);
        }
    }
}
