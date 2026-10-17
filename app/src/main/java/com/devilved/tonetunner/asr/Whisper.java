package com.devilved.tonetunner.asr;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.devilved.tonetunner.SetupActivity;
import com.devilved.tonetunner.voice_translation.neural_networks.NeuralNetworkApi;
import com.devilved.tonetunner.voice_translation.neural_networks.voice.ErrorCodes;
import com.devilved.tonetunner.voice_translation.neural_networks.voice.Recognizer;
import com.devilved.tonetunner.voice_translation.neural_networks.voice.RecognizerListener;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class Whisper {

    public interface WhisperListener {
        void onUpdateReceived(String message);
        void onResultReceived(WhisperResult result);
        // Called when the model failed to load or a transcription failed, so the UI can stop waiting.
        default void onError(String message) {}
    }

    // Files of the Whisper Small model set (from RTranslator) that Recognizer loads.
    private static final String[] MODEL_FILES = {
            "Whisper_initializer.onnx",
            "Whisper_encoder.onnx",
            "Whisper_decoder.onnx",
            "Whisper_cache_initializer.onnx",
            "Whisper_cache_initializer_batch.onnx",
            "Whisper_detokenizer.onnx"
    };

    private static final String TAG = "Whisper";
    public static final String MSG_PROCESSING = "Processing...";
    public static final String MSG_PROCESSING_DONE = "Processing done...!";

    private final AtomicBoolean mInProgress = new AtomicBoolean(false);

    private Recognizer.Action mAction;
    private String mLangCode = "";
    private WhisperListener mUpdateListener;

    private final Lock taskLock = new ReentrantLock();
    private final Condition hasTask = taskLock.newCondition();
    private volatile boolean taskAvailable = false;
    private Recognizer recognizer = null;
    private Context mContext;
    private long startTime;

    public Whisper(Context context) {
        mContext = context;

        if (!isModelInstalled(mContext)) { //install model
            Intent intent = new Intent(mContext, SetupActivity.class);
            intent.addFlags(FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivity(intent);
        } else { // Start thread for RecordBuffer transcription
            Thread threadProcessRecordBuffer = new Thread(this::processRecordBufferLoop);
            threadProcessRecordBuffer.start();
        }

    }

    public static boolean isModelInstalled(Context context) {
        File modelDir = context.getExternalFilesDir(null); // null if shared storage is unavailable
        if (modelDir == null) return false;
        for (String name : MODEL_FILES) {
            if (!new File(modelDir, name).isFile()) return false;
        }
        return true;
    }

    public void setListener(WhisperListener listener) {
        this.mUpdateListener = listener;
    }

    public void loadModel() {
        recognizer = new Recognizer(mContext, false, new NeuralNetworkApi.InitListener() {
            @Override
            public void onInitializationFinished() {
                Log.d(TAG, "Recognizer initialized");
            }

            @Override
            public void onError(int[] reasons, long value) {
                Log.e(TAG, "Recognizer init error");
                sendError("Model could not be loaded");
            }
        });


        recognizer.addCallback(new RecognizerListener() {
            @Override
            public void onSpeechRecognizedResult(String text, String languageCode, double confidenceScore, boolean isFinal) {
                Log.d(TAG, languageCode + " " + text);
                WhisperResult whisperResult = new WhisperResult(text,languageCode, mAction);

                sendResult(whisperResult);

                long timeTaken = System.currentTimeMillis() - startTime;
                Log.d(TAG, "Time Taken for transcription: " + timeTaken + "ms");
                sendUpdate(MSG_PROCESSING_DONE);
            }

            @Override
            public void onError(int[] reasons, long value) {
                Log.e(TAG, "ERROR during recognition");
                sendError(reasons.length > 0 && reasons[0] == ErrorCodes.ERROR_MODEL_NOT_READY
                        ? "Model is still loading, please try again"
                        : "Transcription failed");
            }
        });
    }

    public void unloadModel() {
        if (recognizer != null) {
            recognizer.destroy();
        }
    }

    public void setAction(Recognizer.Action action) {
        this.mAction = action;
    }

    public void setLanguage(String language){
        this.mLangCode = language;
    }

    public void start() {
        if (!mInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Execution is already in progress...");
            return;
        }
        taskLock.lock();
        try {
            taskAvailable = true;
            hasTask.signal();
        } finally {
            taskLock.unlock();
        }
    }

    public void stop() {
        mInProgress.set(false);
    }

    public boolean isInProgress() {
        return mInProgress.get();
    }

    private void processRecordBufferLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            taskLock.lock();
            try {
                while (!taskAvailable) {
                    hasTask.await();
                }
                processRecordBuffer();
                taskAvailable = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                taskLock.unlock();
            }
        }
    }

    private void processRecordBuffer() {
        try {
            if (RecordBuffer.getOutputBuffer() != null) {
                startTime = System.currentTimeMillis();
                sendUpdate(MSG_PROCESSING);
                recognizer.recognize(RecordBuffer.getSamples(),1, mLangCode, mAction );
            } else {
                sendError("No recording available");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during transcription", e);
            sendError("Transcription failed: " + e.getMessage());
        } finally {
            mInProgress.set(false);
        }
    }

    private void sendUpdate(String message) {
        if (mUpdateListener != null) {
            mUpdateListener.onUpdateReceived(message);
        }
    }

    private void sendError(String message) {
        if (mUpdateListener != null) {
            mUpdateListener.onError(message);
        }
    }

    private void sendResult(WhisperResult whisperResult) {
        if (mUpdateListener != null) {
            mUpdateListener.onResultReceived(whisperResult);
        }
    }

}
