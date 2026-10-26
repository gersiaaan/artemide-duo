package com.limelight.duo;

import android.os.Handler;
import android.os.Looper;

import com.limelight.nvstream.input.KeyboardPacket;

/** Windows virtual key codes (US positional) and a helper to send key combos. */
public final class DuoKeys {
    private DuoKeys() {}

    public static final int VK_BACK = 0x08, VK_TAB = 0x09, VK_RETURN = 0x0D, VK_CAPITAL = 0x14,
            VK_ESCAPE = 0x1B, VK_SPACE = 0x20, VK_LEFT = 0x25, VK_UP = 0x26, VK_RIGHT = 0x27,
            VK_DOWN = 0x28, VK_DELETE = 0x2E, VK_LWIN = 0x5B, VK_F1 = 0x70,
            VK_LSHIFT = 0xA0, VK_LCONTROL = 0xA2, VK_LMENU = 0xA4, VK_RMENU = 0xA5,
            VK_OEM_1 = 0xBA, VK_OEM_PLUS = 0xBB, VK_OEM_COMMA = 0xBC, VK_OEM_MINUS = 0xBD,
            VK_OEM_PERIOD = 0xBE, VK_OEM_2 = 0xBF, VK_OEM_3 = 0xC0, VK_OEM_4 = 0xDB,
            VK_OEM_5 = 0xDC, VK_OEM_6 = 0xDD, VK_OEM_7 = 0xDE, VK_OEM_102 = 0xE2;

    /** Letter at a US position. On a German host, the key labelled Z is VK 'Y' and vice versa. */
    public static int vk(char usLetterOrDigit) {
        return Character.toUpperCase(usLetterOrDigit);
    }

    public static byte flagFor(int vk) {
        switch (vk) {
            case VK_LSHIFT: return KeyboardPacket.MODIFIER_SHIFT;
            case VK_LCONTROL: return KeyboardPacket.MODIFIER_CTRL;
            case VK_LMENU: return KeyboardPacket.MODIFIER_ALT;
            case VK_RMENU: return (byte) (KeyboardPacket.MODIFIER_CTRL | KeyboardPacket.MODIFIER_ALT);
            case VK_LWIN: return KeyboardPacket.MODIFIER_META;
            default: return 0;
        }
    }

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static final long KEY_UP_DELAY_MS = 25;

    /** Presses all keys in order, then releases them in reverse order. */
    public static void sendCombo(final DuoInputSink sink, final int... vks) {
        if (sink == null || vks.length == 0) {
            return;
        }
        byte mods = 0;
        for (int vk : vks) {
            sink.sendKey(vk, true, mods);
            mods |= flagFor(vk);
        }
        final byte held = mods;
        HANDLER.postDelayed(() -> {
            byte m = held;
            for (int i = vks.length - 1; i >= 0; i--) {
                m &= (byte) ~flagFor(vks[i]);
                sink.sendKey(vks[i], false, m);
            }
        }, KEY_UP_DELAY_MS);
    }
}
