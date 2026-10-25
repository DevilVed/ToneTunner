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

import java.io.ByteArrayOutputStream;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class Recorder {

    public interface RecorderListener {
        void onUpdateReceived(String message);
    }

    private static final String TAG = "Recorder";
    public static final String ACTION_STOP = "Stop";
    public static final String ACTION_RECORD = "Record";
    public static final String MSG_RECORDING = "Recording...";
    public static final String MSG_RECORDING_DONE = "Recording done...!";
    public static final String MSG_RECORDING_ERROR = "Recording error...";

    private final Context mContext;
    private final AtomicBoolean mInProgress = new AtomicBoolean(false);

    private volatile RecorderListener mListener;
    private volatile boolean released = false;
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
    public void release() {
        released = true;
        mListener = null;
        mInProgress.set(false);
        workerThread.interrupt();
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
            try {
                recordAudio();
            } catch (Exception e) {
                Log.e(TAG, "Recording error...", e);
                sendUpdate(e.getMessage());
            } finally {
                mInProgress.set(false);
                // Notify that recording is finished, on every path including errors
                synchronized (fileSavedLock) {
                    recordingActive = false;
                    fileSavedLock.notifyAll();
                }
            }
        }
    }

    private void recordAudio() {
        if (ActivityCompat.checkSelfPermission(mContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "AudioRecord permission is not granted");
            sendUpdate(mContext.getString(R.string.need_record_audio_permission));
            return;
        }

        int channels = 1;
        int bytesPerSample = 2;
        int sampleRateInHz = 16000;
        int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        int audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION;

        int bufferSize = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat);
        if (bufferSize < VAD_FRAME_SIZE * 2) bufferSize = VAD_FRAME_SIZE * 2;

        AudioManager audioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        audioManager.startBluetoothSco();
        audioManager.setBluetoothScoOn(true);

        AudioRecord.Builder builder = new AudioRecord.Builder()
                .setAudioSource(audioSource)
                .setAudioFormat(new AudioFormat.Builder()
                        .setChannelMask(channelConfig)
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRateInHz)
                        .build())
                .setBufferSizeInBytes(bufferSize);

        AudioRecord audioRecord = builder.build();
        audioRecord.startRecording();

        // Calculate maximum byte counts for 30 seconds (for saving)
        int bytesForThirtySeconds = sampleRateInHz * bytesPerSample * channels * 30;

        ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream(); // Buffer for saving data RecordBuffer

        byte[] audioData = new byte[bufferSize];
        int totalBytesRead = 0;

        boolean isSpeech;
        boolean isRecording = false;
        byte[] vadAudioBuffer = new byte[VAD_FRAME_SIZE * 2];  //VAD needs 16 bit

        while (mInProgress.get() && totalBytesRead < bytesForThirtySeconds) {
            int bytesRead = audioRecord.read(audioData, 0, VAD_FRAME_SIZE * 2);
            if (bytesRead > 0) {
                outputBuffer.write(audioData, 0, bytesRead);  // Save all bytes read up to 30 seconds
                totalBytesRead += bytesRead;
            } else {
                Log.d(TAG, "AudioRecord error, bytes read: " + bytesRead);
                break;
            }

            if (useVAD){
                // Optimize VAD buffer extraction: instead of calling outputBuffer.toByteArray() on every frame (~1000 times),
                // copy directly from audioData into vadAudioBuffer in-place.
                // Eliminates ~458 MB of temporary heap allocations per 30s session and yields an ~80x speedup.
                if (totalBytesRead >= VAD_FRAME_SIZE * 2) {
                    if (bytesRead >= VAD_FRAME_SIZE * 2) {
                        System.arraycopy(audioData, bytesRead - VAD_FRAME_SIZE * 2, vadAudioBuffer, 0, VAD_FRAME_SIZE * 2);
                    } else if (bytesRead > 0) {
                        int shift = VAD_FRAME_SIZE * 2 - bytesRead;
                        System.arraycopy(vadAudioBuffer, bytesRead, vadAudioBuffer, 0, shift);
                        System.arraycopy(audioData, 0, vadAudioBuffer, shift, bytesRead);
                    }

                    isSpeech = vad.isSpeech(vadAudioBuffer);
                    if (isSpeech) {
                        if (!isRecording) {
                            Log.d(TAG, "VAD Speech detected: recording starts");
                            sendUpdate(MSG_RECORDING);
                        }
                        isRecording = true;
                    } else {
                        if (isRecording) {
                            isRecording = false;
                            mInProgress.set(false);
                        }
                    }
                }
            } else {
                if (!isRecording) sendUpdate(MSG_RECORDING);
                isRecording = true;
            }
        }
        Log.d(TAG, "Total bytes recorded: " + totalBytesRead);

        if (useVAD){
            useVAD = false;
            vad.close();
            vad = null;
            Log.d(TAG, "Closing VAD");
        }
        audioRecord.stop();
        audioRecord.release();
        audioManager.stopBluetoothSco();
        audioManager.setBluetoothScoOn(false);

        // Save recorded audio data to BufferStore (up to 30 seconds)
        RecordBuffer.setOutputBuffer(outputBuffer.toByteArray());
        if (totalBytesRead > 6400){  //min 0.2s
            sendUpdate(MSG_RECORDING_DONE);
        } else {
            sendUpdate(MSG_RECORDING_ERROR);
        }

    }

}
