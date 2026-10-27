package com.android.inputmethod.keyboard;

/** JNI surface of the LatinIME key geometry (names must match the native side). */
public final class ProximityInfo {
    private ProximityInfo() {}

    public static final int MAX_PROXIMITY_CHARS_SIZE = 16;

    static {
        System.loadLibrary("jni_latinime");
    }

    public static native long setProximityInfoNative(int displayWidth, int displayHeight,
            int gridWidth, int gridHeight, int mostCommonKeyWidth, int mostCommonKeyHeight,
            int[] proximityCharsArray, int keyCount, int[] keyXCoordinates, int[] keyYCoordinates,
            int[] keyWidths, int[] keyHeights, int[] keyCharCodes, float[] sweetSpotCenterXs,
            float[] sweetSpotCenterYs, float[] sweetSpotRadii);
    public static native void releaseProximityInfoNative(long nativeProximityInfo);
}
