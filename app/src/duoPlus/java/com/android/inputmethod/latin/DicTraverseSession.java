package com.android.inputmethod.latin;

/** JNI surface of the LatinIME traverse session (names must match the native side). */
public final class DicTraverseSession {
    private DicTraverseSession() {}

    static {
        System.loadLibrary("jni_latinime");
    }

    public static native long setDicTraverseSessionNative(String locale, long dictSize);
    public static native void initDicTraverseSessionNative(long nativeDicTraverseSession,
            long dictionary, int[] previousWord, int previousWordLength);
    public static native void releaseDicTraverseSessionNative(long nativeDicTraverseSession);
}
