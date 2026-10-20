package com.devilved.tonetunner.asr;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import com.konovalov.vad.webrtc.Vad;
import com.konovalov.vad.webrtc.VadWebRTC;
import com.konovalov.vad.webrtc.config.FrameSize;
import com.konovalov.vad.webrtc.config.Mode;
import com.konovalov.vad.webrtc.config.SampleRate;
import com.devilved.tonetunner.R;

import java.io.File;
import java.io.IOException;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class Recorder {

    public interface RecorderListener {
        void onUpdateReceived(String message);
    }

    private static final String TAG = "Recorder";
    private static final int INITIAL_SPEECH_TIMEOUT_SECONDS = 30;
    private static final long INITIAL_SPEECH_TIMEOUT_NANOS =
            INITIAL_SPEECH_TIMEOUT_SECONDS * 1_000_000_000L;
    public static final String ACTION_STOP = "Stop";
    public static final String ACTION_RECORD = "Record";
    public static final String MSG_RECORDING = "Recording...";
    public static final String MSG_RECORDING_DONE = "Recording done...!";
    public static final String MSG_RECORDING_ERROR = "Recording error...";
    public static final String MSG_SPEECH_TIMEOUT = "Speech timeout...";

    private final Context mContext;
    private final AtomicBoolean mInProgress = new AtomicBoolean(false);

    private volatile RecorderListener mListener;
    private volatile boolean released = false;
    private PcmRecording publishedRecording;
    private final Lock lock = new ReentrantLock();
    private final Condition hasTask = lock.newCondition();
    private final Object fileSavedLock = new Object(); // Lock object for wait/notify
    private boolean recordingActive = false; // guarded by fileSavedLock; true from start() until the recording thread is done
    private static final long STOP_TIMEOUT_MS = 3000;

    private volatile boolean shouldStartRecording = false;
    private boolean useVAD = false;
    private VadWebRTC vad = null;
    private static final int VAD_FRAME_SIZE = 480;

    private final Thread workerThread;

    public Recorder(Context context) {
        this.mContext = context;

        // Initialize and start the worker thread
        workerThread = new Thread(this::recordLoop);
        workerThread.start();
    }

    public void setListener(RecorderListener listener) {
        this.mListener = listener;
    }


    public void start() {
        if (released) return;
        if (!mInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Recording is already in progress...");
            return;
        }
        synchronized (fileSavedLock) {
            recordingActive = true;
        }
        lock.lock();
        try {
            Log.d(TAG, "Recording starts now");
            shouldStartRecording = true;
            hasTask.signal();
        } finally {
            lock.unlock();
        }
    }

    public void initVad(){
        vad = Vad.builder()
                .setSampleRate(SampleRate.SAMPLE_RATE_16K)
                .setFrameSize(FrameSize.FRAME_SIZE_480)
                .setMode(Mode.VERY_AGGRESSIVE)
                .setSilenceDurationMs(800)
                .setSpeechDurationMs(200)
                .build();
        useVAD = true;
        Log.d(TAG, "VAD initialized");
    }


    public void stop() {
        Log.d(TAG, "Recording stopped");
        mInProgress.set(false);

        // Wait for the recording thread to finish. Waiting on a condition (not a bare wait()) means a
        // recording that already ended, failed or lacked permission cannot leave the caller blocked forever.
        synchronized (fileSavedLock) {
            long deadline = System.currentTimeMillis() + STOP_TIMEOUT_MS;
            try {
                while (recordingActive) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) {
                        Log.w(TAG, "Timed out waiting for the recording thread to finish");
                        break;
                    }
                    fileSavedLock.wait(remaining);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Restore interrupted status
            }
        }
    }

    // Ends the worker thread; call when this Recorder is no longer used, otherwise the thread lives forever.
    // Also silences the recorder: a recording cut short by release() must not report "done" (or an error) to a
    // caller that is being torn down, e.g. MainActivity.onDestroy() after its model was unloaded.
    public synchronized void release() {
        released = true;
        mListener = null;
        mInProgress.set(false);
        workerThread.interrupt();
        if (publishedRecording != null) {
            try { publishedRecording.close(); } catch (IOException ignored) {}
        }
        publishedRecording = null;
    }

    /** Transfers this recorder's completed file to its own request, never a global audio slot. */
    public synchronized PcmRecording takeRecording() {
        PcmRecording result = publishedRecording;
        publishedRecording = null;
        return result;
    }

    public boolean isInProgress() {
        return mInProgress.get();
    }

    private void sendUpdate(String message) {
        RecorderListener listener = mListener;
        if (listener != null && !released)
            listener.onUpdateReceived(message);
    }


    private void recordLoop() {
        while (true) {
            lock.lock();
            try {
                while (!shouldStartRecording) {
                    hasTask.await();
                }
                shouldStartRecording = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                lock.unlock();
            }

            // Start recording process
            String terminalMessage = null;
            try {
                terminalMessage = recordAudio();
            } catch (Exception e) {
                Log.e(TAG, "Recording error...", e);
                terminalMessage = MSG_RECORDING_ERROR;
            } finally {
                mInProgress.set(false);
                // Notify that recording is finished, on every path including errors
                synchronized (fileSavedLock) {
                    recordingActive = false;
                    fileSavedLock.notifyAll();
                }
            }
            // Terminal state is observable only after recordAudio() has released the microphone,
            // Bluetooth SCO, and VAD resources, and the worker has cleared its active state.
            if (terminalMessage != null) sendUpdate(terminalMessage);
        }
    }

    private String recordAudio() throws IOException {
        if (ActivityCompat.checkSelfPermission(mContext, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            return MSG_RECORDING_ERROR;
        }
        int sampleRate = 16000;
        int bufferSize = Math.max(AudioRecord.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), VAD_FRAME_SIZE * 2);
        AudioManager manager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        AudioRecord audio = null;
        PcmRecording recording = null;
        boolean transferred = false;
        String terminalMessage = null;
        try {
            recording = PcmRecording.create(new File(mContext.getNoBackupFilesDir(), "dictation-audio"));
            manager.startBluetoothSco();
            manager.setBluetoothScoOn(true);
            audio = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(new AudioFormat.Builder().setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).build())
                    .setBufferSizeInBytes(bufferSize).build();
            if (audio.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("Microphone unavailable");
            audio.startRecording();
            byte[] frame = new byte[VAD_FRAME_SIZE * 2];
            boolean heardSpeech = false;
            boolean microphoneReadFailed = false;
            boolean speechTimedOut = false;
            long capturedSamples = 0;
            long initialSpeechDeadlineSamples = (long) sampleRate * INITIAL_SPEECH_TIMEOUT_SECONDS;
            long initialSpeechStartNanos = System.nanoTime();
            sendUpdate(MSG_RECORDING);
            // Fixed capture buffer; duration is bounded by available disk, never by a RAM array or timer.
            while (mInProgress.get() && !released) {
                int count = audio.read(frame, 0, frame.length);
                if (count <= 0 || (count & 1) != 0) {
                    microphoneReadFailed = true;
                    mInProgress.set(false);
                    break;
                }
                recording.write(frame, 0, count);
                capturedSamples += count / PcmRecording.BYTES_PER_SAMPLE;
                if (useVAD && count == frame.length) {
                    boolean speech = vad.isSpeech(frame);
                    if (speech) heardSpeech = true;
                    else if (heardSpeech) mInProgress.set(false);
                }
                if (useVAD && !heardSpeech
                        && (capturedSamples >= initialSpeechDeadlineSamples
                        || System.nanoTime() - initialSpeechStartNanos >= INITIAL_SPEECH_TIMEOUT_NANOS)) {
                    speechTimedOut = true;
                    mInProgress.set(false);
                }
            }
            recording.finish();
            synchronized (this) {
                if (!released && speechTimedOut) {
                    terminalMessage = MSG_SPEECH_TIMEOUT;
                } else if (!released && recording.lengthBytes() >= PcmRecording.BYTES_PER_SAMPLE) {
                    if (microphoneReadFailed) {
                        recording.setCaptureWarning(
                                "Microphone interrupted. Returned the audio captured before interruption.");
                    }
                    publishedRecording = recording;
                    transferred = true;
                    terminalMessage = MSG_RECORDING_DONE;
                } else if (!released) {
                    terminalMessage = MSG_RECORDING_ERROR;
                }
            }
        } finally {
            if (audio != null) {
                try { audio.stop(); } catch (IllegalStateException ignored) {}
                audio.release();
            }
            try { manager.stopBluetoothSco(); manager.setBluetoothScoOn(false); }
            catch (RuntimeException ignored) {}
            if (useVAD) {
                useVAD = false;
                if (vad != null) { vad.close(); vad = null; }
            }
            if (!transferred && recording != null) recording.close();
        }
        return terminalMessage;
    }
}
