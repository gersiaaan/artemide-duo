package com.limelight.duo;

/** AMOLED palette: true black, near-black keys with thin dark borders, dark blue highlights. */
final class DuoColors {
    private DuoColors() {}

    static final int BG = 0xFF000000;
    static final int SURFACE = 0xFF060708;
    static final int KEY = 0xFF070809;
    static final int KEY_FN = 0xFF0A0B0C;
    static final int PRESSED = 0xFF15171B;
    static final int BORDER = 0xFF17191C;
    static final int TEXT = 0xFFC4C8CE;
    static final int MUTED = 0xFF8A9099;

    /** Secondary keys (numbers, punctuation, modifiers, F-row). */
    static final int SUBTLE_BG = 0xFF040405;
    static final int SUBTLE_BORDER = 0xFF0F1012;

    /** Shift, backspace, enter. */
    static final int FUNC_BG = 0xFF060607;
    static final int FUNC_BORDER = 0xFF131416;
    static final int FUNC_TEXT = 0xFF868C94;

    /** Highlight: active tab, held modifier, popups. */
    static final int HI_BG = 0xFF10192B;
    static final int HI_BORDER = 0xFF2A3D66;
    static final int HI_TEXT = 0xFFA9C1F0;

    // Kept for older references.
    static final int ACCENT = HI_BG;
    static final int ON_ACCENT = HI_TEXT;
}
