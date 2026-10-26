package com.limelight.duo;

/**
 * Bridge between the Duo input panel and the streaming connection.
 * Implemented by Game so the panel never touches NvConnection directly.
 */
public interface DuoInputSink {
    /** vk is a Windows virtual key code (0x00-0xFF), positional on a US layout. */
    void sendKey(int vk, boolean down, byte modifiers);

    void sendMouseMove(int dx, int dy);

    void sendMouseButton(byte button, boolean down);

    /** Values in WHEEL_DELTA units (120 = one notch). Positive v = up, positive h = right. */
    void sendScroll(short vertical, short horizontal);

    void toggleSystemKeyboard();

    void showStreamMenu();
}
