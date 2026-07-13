package com.whisperonnx.asr;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.whisperonnx.SetupActivity;
import com.whisperonnx.voice_translation.neural_networks.NeuralNetworkApi;
import com.whisperonnx.voice_translation.neural_networks.voice.Recognizer;
import com.whisperonnx.voice_translation.neural_networks.voice.RecognizerListener;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class Whisper {

    public interface WhisperListener {
        void onUpdateReceived(String message);
        void onResultReceived(WhisperResult result);
    }

    private static final String TAG = "Whisper";
    public static final String MSG_PROCESSING = "Processing...";
    public static final String MSG_PROCESSING_DONE = "Processing done...!";

    private final AtomicBoolean mInProgress = new AtomicBoolean(false);

    private Recognizer.Action mAction;
    private String mLangCode = "";
    private WhisperListener mUpdateListener;

    private ExecutorService executorService = null;
    private Recognizer recognizer = null;
    private Context mContext;
    private long startTime;

    public Whisper(Context context) {
        mContext = context;

        //check if model is installed
        File sdcardDataFolder = mContext.getExternalFilesDir(null);
        if (sdcardDataFolder == null) sdcardDataFolder.mkdir();

        File[] files = sdcardDataFolder.listFiles();

        int fileCount = 0;
        for (File file : files) {
            if (file.isFile()) {
                fileCount++;
            }
        }
        if (fileCount != 6) { //install model
            Intent intent = new Intent(mContext, SetupActivity.class);
            intent.addFlags(FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivity(intent);
        } else { // Init ExecutorService for RecordBuffer transcription
            executorService = Executors.newSingleThreadExecutor();
        }

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
                Log.d(TAG, "Recognizer init error");
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
                Log.d(TAG, "ERROR during recognition");
            }
        });
    }

    public void unloadModel() {
        if (executorService != null) {
            executorService.shutdownNow();
            executorService = null;
        }
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
        if (executorService != null) {
            executorService.submit(this::processRecordBuffer);
        } else {
            mInProgress.set(false);
            Log.d(TAG, "ExecutorService not initialized");
        }
    }

    public void stop() {
        mInProgress.set(false);
    }

    public boolean isInProgress() {
        return mInProgress.get();
    }

    private void processRecordBuffer() {
        try {
            if (RecordBuffer.getOutputBuffer() != null) {
                startTime = System.currentTimeMillis();
                sendUpdate(MSG_PROCESSING);
                recognizer.recognize(RecordBuffer.getSamples(),1, mLangCode, mAction );
            } else {
                sendUpdate("Engine not initialized or file path not set");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during transcription", e);
            sendUpdate("Transcription failed: " + e.getMessage());
        } finally {
            mInProgress.set(false);
        }
    }

    private void sendUpdate(String message) {
        if (mUpdateListener != null) {
            mUpdateListener.onUpdateReceived(message);
        }
    }

    private void sendResult(WhisperResult whisperResult) {
        if (mUpdateListener != null) {
            mUpdateListener.onResultReceived(whisperResult);
        }
    }

}
