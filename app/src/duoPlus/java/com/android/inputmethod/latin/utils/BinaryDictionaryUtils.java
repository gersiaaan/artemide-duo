package com.android.inputmethod.latin.utils;

/** JNI surface of the LatinIME dictionary utilities (names must match the native side). */
public final class BinaryDictionaryUtils {
    private BinaryDictionaryUtils() {}

    static {
        System.loadLibrary("jni_latinime");
    }

    public static native boolean createEmptyDictFileNative(String filePath, long dictVersion,
            String locale, String[] attributeKeyStringArray, String[] attributeValueStringArray);
    public static native float calcNormalizedScoreNative(int[] before, int[] after, int score);
    public static native int setCurrentTimeForTestNative(int currentTime);
}
