// Client microphone upstream: encodes 20 ms mono 48 kHz PCM frames with Opus and sends them to hosts
// that support it (MacShine, see LiSendMicrophoneData()).
#include <jni.h>
#include <pthread.h>
#include <opus.h>

#include <Limelight.h>

#define MIC_SAMPLE_RATE 48000
#define MIC_FRAME_SAMPLES 960
#define MIC_MAX_PACKET 240

static pthread_mutex_t MicLock = PTHREAD_MUTEX_INITIALIZER;
static OpusEncoder* MicEncoder;

JNIEXPORT jboolean JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_isMicrophoneSupported(JNIEnv *env, jclass clazz) {
    return (LiGetHostFeatureFlags() & LI_FF_MIC_UPSTREAM) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_startMicrophone(JNIEnv *env, jclass clazz, jint bitrate) {
    int err;

    pthread_mutex_lock(&MicLock);
    if (MicEncoder == NULL) {
        MicEncoder = opus_encoder_create(MIC_SAMPLE_RATE, 1, OPUS_APPLICATION_VOIP, &err);
        if (err != OPUS_OK) {
            MicEncoder = NULL;
        }
        else {
            opus_encoder_ctl(MicEncoder, OPUS_SET_BITRATE(bitrate));
            opus_encoder_ctl(MicEncoder, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
            opus_encoder_ctl(MicEncoder, OPUS_SET_INBAND_FEC(1));
            opus_encoder_ctl(MicEncoder, OPUS_SET_PACKET_LOSS_PERC(5));
            opus_encoder_ctl(MicEncoder, OPUS_SET_COMPLEXITY(8));
        }
    }
    pthread_mutex_unlock(&MicLock);

    return MicEncoder != NULL ? JNI_TRUE : JNI_FALSE;
}

// Returns 0 on success, LI_ERR_UNSUPPORTED if the host can't take audio, or another negative value on error.
JNIEXPORT jint JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_sendMicrophoneFrame(JNIEnv *env, jclass clazz, jshortArray pcm) {
    unsigned char packet[MIC_MAX_PACKET];
    jshort samples[MIC_FRAME_SAMPLES];
    int length;
    int ret;

    if ((*env)->GetArrayLength(env, pcm) != MIC_FRAME_SAMPLES) {
        return -2;
    }
    (*env)->GetShortArrayRegion(env, pcm, 0, MIC_FRAME_SAMPLES, samples);

    pthread_mutex_lock(&MicLock);
    if (MicEncoder == NULL) {
        pthread_mutex_unlock(&MicLock);
        return -3;
    }
    length = opus_encode(MicEncoder, samples, MIC_FRAME_SAMPLES, packet, sizeof(packet));
    pthread_mutex_unlock(&MicLock);

    if (length < 0) {
        return -4;
    }
    // Even 1-2 byte silence frames go out, or the host would count them as lost.
    ret = LiSendMicrophoneData(packet, length);
    return ret;
}

JNIEXPORT void JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_stopMicrophone(JNIEnv *env, jclass clazz) {
    pthread_mutex_lock(&MicLock);
    if (MicEncoder != NULL) {
        opus_encoder_destroy(MicEncoder);
        MicEncoder = NULL;
    }
    pthread_mutex_unlock(&MicLock);

    LiSendMicrophoneStop();
}
