package com.limelight.duo;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.limelight.LimeLog;
import com.limelight.nvstream.jni.MoonBridge;

/**
 * Sends the Duo's microphone to the host while a stream runs (MacShine extension).
 * The host plays it into its "MacShine" input device, so apps on the Mac (Claude, Zoom, ...) hear the Duo.
 *
 * Audio: 48 kHz mono, 20 ms frames, Opus with in-band FEC, voice-call source with echo
 * cancellation so the stream audio from the speaker does not loop back.
 */
public final class DuoMic {
    private DuoMic() {}

    private static final int SAMPLE_RATE = 48000;
    private static final int FRAME_SAMPLES = 960;
    private static final int BITRATE = 32000;
    private static final int REQUEST_CODE = 0x4D49;
    private static final int LI_ERR_UNSUPPORTED = -5501;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean running;
    private static Thread thread;
    private static Runnable listener;

    /** Whether the microphone is currently sent to the host. */
    public static boolean isOn() {
        return running;
    }

    /** Whether the connected host can receive the microphone. */
    public static boolean hostSupports() {
        try {
            return MoonBridge.isMicrophoneSupported();
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** Called on the main thread whenever the microphone turns on or off. */
    public static void setListener(Runnable r) {
        listener = r;
    }

    /** Microphone button: turns the microphone on or off. */
    public static void toggle(Context context) {
        if (running) {
            stop();
            return;
        }

        if (!hostSupports()) {
            Toast.makeText(context, "The host can't receive the microphone (needs MacShine)", Toast.LENGTH_SHORT).show();
            return;
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Activity activity = context instanceof Activity ? (Activity) context : DuoBridge.getStreamActivity();
            if (activity != null) {
                ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_CODE);
            }
            Toast.makeText(context, "Allow the microphone, then tap again", Toast.LENGTH_SHORT).show();
            return;
        }

        start(context.getApplicationContext());
    }

    @SuppressLint("MissingPermission")
    private static synchronized void start(Context context) {
        if (running) {
            return;
        }

        int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        final AudioRecord record;
        try {
            record = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBuffer, FRAME_SAMPLES * 2 * 4));
        } catch (RuntimeException e) {
            LimeLog.warning("Microphone: can't open recorder: " + e);
            Toast.makeText(context, "Microphone not available", Toast.LENGTH_SHORT).show();
            return;
        }
        if (record.getState() != AudioRecord.STATE_INITIALIZED || !MoonBridge.startMicrophone(BITRATE)) {
            record.release();
            Toast.makeText(context, "Microphone not available", Toast.LENGTH_SHORT).show();
            return;
        }

        running = true;
        notifyListener();

        thread = new Thread(() -> capture(context, record), "DuoMic");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    private static void capture(Context context, AudioRecord record) {
        int session = record.getAudioSessionId();
        AcousticEchoCanceler aec = AcousticEchoCanceler.isAvailable() ? AcousticEchoCanceler.create(session) : null;
        NoiseSuppressor ns = NoiseSuppressor.isAvailable() ? NoiseSuppressor.create(session) : null;
        // Automatic gain stays off: it pumps up the noise in pauses and clips loud speech.
        // The Mac filters the noise (voice isolation) and sets the level itself.
        AutomaticGainControl agc = AutomaticGainControl.isAvailable() ? AutomaticGainControl.create(session) : null;
        if (aec != null) aec.setEnabled(true);
        if (ns != null) ns.setEnabled(true);
        if (agc != null) agc.setEnabled(false);

        short[] frame = new short[FRAME_SAMPLES];
        boolean unsupported = false;
        try {
            record.startRecording();
            while (running) {
                int filled = 0;
                while (filled < FRAME_SAMPLES && running) {
                    int n = record.read(frame, filled, FRAME_SAMPLES - filled);
                    if (n < 0) {
                        LimeLog.warning("Microphone: read failed: " + n);
                        running = false;
                        break;
                    }
                    filled += n;
                }
                if (!running) {
                    break;
                }
                int ret = MoonBridge.sendMicrophoneFrame(frame);
                if (ret == LI_ERR_UNSUPPORTED) {
                    unsupported = true;
                    running = false;
                }
            }
        } catch (RuntimeException e) {
            LimeLog.warning("Microphone: capture stopped: " + e);
        } finally {
            running = false;
            try {
                record.stop();
            } catch (RuntimeException ignored) {
            }
            record.release();
            if (aec != null) aec.release();
            if (ns != null) ns.release();
            if (agc != null) agc.release();
            MoonBridge.stopMicrophone();
            notifyListener();
            if (unsupported) {
                MAIN.post(() -> Toast.makeText(context, "The host can't receive the microphone", Toast.LENGTH_SHORT).show());
            }
        }
    }

    /** Turns the microphone off and tells the host; blocks until the capture thread has finished. */
    public static synchronized void stop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void notifyListener() {
        MAIN.post(() -> {
            Runnable r = listener;
            if (r != null) {
                r.run();
            }
        });
    }
}
