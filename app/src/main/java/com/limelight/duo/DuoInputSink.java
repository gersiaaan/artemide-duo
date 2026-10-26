package com.limelight.duo;

/**
 * Bridge between the Duo input panel and the streaming connection.
 * Implemented by Game so the panel never touches NvConnection directly.
 */
public interface DuoInputSink {
    /** vk is a Windows virtual key code (0x00-0xFF), positional on a US layout. */
    void sendKey(int vk, boolean down, byte modifiers);

    /** Types text as Unicode (used for autocorrect replacements). */
    void sendText(String text);

    void sendMouseMove(int dx, int dy);

    void sendMouseButton(byte button, boolean down);

    /** Values in WHEEL_DELTA units (120 = one notch). Positive v = up, positive h = right. */
    void sendScroll(short vertical, short horizontal);

    void toggleSystemKeyboard();

    void showStreamMenu();

    /** Ends the stream (like "Disconnect" in the quick menu; the PC session keeps running). */
    void disconnect();

    /** Performance overlay (HUD) on/off. */
    void togglePerformanceOverlay();

    /** After Ctrl+C / Ctrl+X: pull the PC clipboard to Android once the PC has it. */
    void fetchClipboardFromPc();

    /**
     * Before Ctrl+V: if Android has a new clipboard (copied in an Android app), send it to
     * the PC first; runs paste afterwards (or right away if there is nothing new).
     */
    void pushClipboardThen(Runnable paste);

    /** Stream window and panel window count as one: called with the combined focus. */
    void onUnitFocusChanged(boolean hasFocus);
}
