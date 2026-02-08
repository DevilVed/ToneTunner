package com.whisperonnx.asr;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.whisperonnx.SetupActivity;
import com.whisperonnx.voice_translation.neural_networks.NeuralNetworkApi;
import com.whisperonnx.voice_translation.neural_networks.voice.Recognizer;
import com.whisperonnx.voice_translation.neural_networks.voice.RecognizerListener;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class Whisper {

    public interface WhisperListener {
        void onUpdateReceived(String message);

        void onResultReceived(WhisperResult result);
        
        void onError(String error);
    }

    private static final String TAG = "Whisper";
    public static final String MSG_PROCESSING = "Processing...";
    public static final String MSG_PROCESSING_DONE = "Processing done...!";
    public static final String MSG_MODEL_NOT_FOUND = "Model files not found. Please install a model.";
    public static final String MSG_INITIALIZING = "Initializing...";

    private final AtomicBoolean mInProgress = new AtomicBoolean(false);

    private Recognizer.Action mAction;
    private String mLangCode = "";
    private AtomicReference<WhisperListener> mUpdateListener = new AtomicReference<>();

    // Use blocking queue instead of condition variable to avoid lost signal
    private final LinkedBlockingQueue<Object> taskQueue = new LinkedBlockingQueue<>();
    private Recognizer recognizer = null;
    private Context mContext;
    private long startTime;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    // Initialization state
    private volatile boolean isInitialized = false;
    private volatile boolean modelFilesFound = false;

    public Whisper(Context context) {
        mContext = context.getApplicationContext();
        
        // Start background initialization immediately
        new Thread(this::backgroundInitialize, "WhisperInit").start();
    }
    
    /**
     * Background initialization to avoid blocking main thread
     */
    private void backgroundInitialize() {
        // Check if model is installed
        File sdcardDataFolder = mContext.getExternalFilesDir(null);
        if (sdcardDataFolder == null) {
            sdcardDataFolder.mkdir();
        }

        File[] files = sdcardDataFolder.listFiles();
        int fileCount = 0;
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    fileCount++;
                }
            }
        }
        
        if (fileCount < 6) { // install model
            Log.d(TAG, "Model not found, redirecting to SetupActivity");
            modelFilesFound = false;
            mainHandler.post(() -> {
                Intent intent = new Intent(mContext, SetupActivity.class);
                intent.addFlags(FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivity(intent);
            });
        } else {
            modelFilesFound = true;
            // Start thread for RecordBuffer transcription
            Thread threadProcessRecordBuffer = new Thread(this::processRecordBufferLoop, "WhisperProcess");
            threadProcessRecordBuffer.start();
        }
        isInitialized = true;
    }

    public void setListener(WhisperListener listener) {
        this.mUpdateListener.set(listener);
    }

    public void loadModel() {
        // Run on background thread to avoid ANR
        new Thread(() -> {
            try {
                recognizer = new Recognizer(mContext, false, new NeuralNetworkApi.InitListener() {
                    @Override
                    public void onInitializationFinished() {
                        Log.d(TAG, "Recognizer initialized");
                    }

                    @Override
                    public void onError(int[] reasons, long value) {
                        Log.e(TAG, "Recognizer init error: " + value);
                        sendUpdate("Model initialization failed");
                    }
                });

                recognizer.addCallback(new RecognizerListener() {
                    @Override
                    public void onSpeechRecognizedResult(String text, String languageCode, double confidenceScore,
                            boolean isFinal) {
                        Log.d(TAG, languageCode + " " + text);
                        WhisperResult whisperResult = new WhisperResult(text, languageCode, mAction);

                        sendResult(whisperResult);

                        long timeTaken = System.currentTimeMillis() - startTime;
                        Log.d(TAG, "Time Taken for transcription: " + timeTaken + "ms");
                        sendUpdate(MSG_PROCESSING_DONE);
                    }

                    @Override
                    public void onError(int[] reasons, long value) {
                        Log.e(TAG, "ERROR during recognition: " + value);
                        sendUpdate("Recognition error");
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to load model", e);
                sendUpdate("Failed to load model: " + e.getMessage());
            }
        }, "WhisperLoadModel").start();
    }

    public void unloadModel() {
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
    }

    public void setAction(Recognizer.Action action) {
        this.mAction = action;
    }

    public void setLanguage(String language) {
        this.mLangCode = language;
    }

    public void start() {
        if (!mInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Execution is already in progress...");
            return;
        }
        if (!isInitialized) {
            Log.w(TAG, "Whisper not initialized yet");
            sendUpdate(MSG_INITIALIZING);
            mInProgress.set(false);
            return;
        }
        if (!modelFilesFound) {
            Log.w(TAG, "Model files not found");
            sendUpdate(MSG_MODEL_NOT_FOUND);
            mInProgress.set(false);
            return;
        }
        // Use blocking queue to avoid lost signal issues
        taskQueue.offer(new Object());
    }

    public void stop() {
        mInProgress.set(false);
    }

    public boolean isInProgress() {
        return mInProgress.get();
    }

    private void processRecordBufferLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                // Blocking take() will wait until a task is available
                taskQueue.take();
                if (mInProgress.get()) {
                    processRecordBuffer();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void processRecordBuffer() {
        try {
            if (recognizer == null) {
                Log.e(TAG, "Recognizer not initialized");
                sendUpdate("Model not loaded");
                return;
            }
            if (RecordBuffer.getOutputBuffer() == null) {
                sendUpdate("Engine not initialized or file path not set");
                return;
            }
            
            startTime = System.currentTimeMillis();
            sendUpdate(MSG_PROCESSING);

            float[] allSamples = RecordBuffer.getSamples();
            if (allSamples == null || allSamples.length == 0) {
                Log.w(TAG, "No audio samples available");
                sendUpdate("No audio captured");
                return;
            }

            // Check if audio needs chunking (longer than 30 seconds)
            if (allSamples.length > AudioChunker.CHUNK_SAMPLES) {
                processChunkedAudio(allSamples);
            } else {
                // Short audio - process directly
                recognizer.recognize(allSamples, 1, mLangCode, mAction);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during transcription", e);
            sendUpdate("Transcription failed: " + e.getMessage());
        } finally {
            mInProgress.set(false);
        }
    }

    /**
     * Process audio longer than 30 seconds by splitting into chunks.
     */
    private void processChunkedAudio(float[] allSamples) {
        AudioChunker chunker = new AudioChunker(2.5f);
        ChunkMerger merger = new ChunkMerger(2.5f);

        List<float[]> chunks = chunker.chunk(allSamples);
        List<String> chunkResults = new ArrayList<>();
        String[] detectedLanguage = new String[] { mLangCode };

        Log.d(TAG, "Processing " + chunks.size() + " chunks for " +
                (allSamples.length / 16000f) + " seconds of audio");

        // Process each chunk sequentially
        for (int i = 0; i < chunks.size(); i++) {
            final int chunkIndex = i;
            final float[] chunk = chunks.get(i);
            final CountDownLatch latch = new CountDownLatch(1);
            final String[] result = new String[1];

            // Create temporary listener for this chunk
            RecognizerListener chunkListener = new RecognizerListener() {
                @Override
                public void onSpeechRecognizedResult(String text, String languageCode,
                        double confidenceScore, boolean isFinal) {
                    result[0] = text;
                    if (chunkIndex == 0) {
                        detectedLanguage[0] = languageCode;
                    }
                    latch.countDown();
                }

                @Override
                public void onError(int[] reasons, long value) {
                    result[0] = "";
                    latch.countDown();
                }
            };

            recognizer.addCallback(chunkListener);
            recognizer.recognize(chunk, 1, mLangCode, mAction);

            try {
                // Wait for chunk to complete (timeout after 60 seconds)
                latch.await(60, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Log.e(TAG, "Chunk " + (i + 1) + " interrupted");
            }

            recognizer.removeCallback(chunkListener);

            if (result[0] != null && !result[0].isEmpty()) {
                chunkResults.add(result[0]);
            }

            Log.d(TAG, "Chunk " + (i + 1) + "/" + chunks.size() + " completed: " +
                    (result[0] != null ? result[0].length() : 0) + " chars");
        }

        // Merge all chunk results
        String mergedText = merger.merge(chunkResults);

        // Send final result
        WhisperResult whisperResult = new WhisperResult(mergedText, detectedLanguage[0], mAction);
        sendResult(whisperResult);

        long timeTaken = System.currentTimeMillis() - startTime;
        Log.d(TAG, "Total time for " + chunks.size() + " chunks: " + timeTaken + "ms");
        sendUpdate(MSG_PROCESSING_DONE);
    }

    private void sendUpdate(String message) {
        WhisperListener listener = mUpdateListener.get();
        if (listener != null) {
            listener.onUpdateReceived(message);
        }
    }

    private void sendResult(WhisperResult whisperResult) {
        WhisperListener listener = mUpdateListener.get();
        if (listener != null) {
            listener.onResultReceived(whisperResult);
        }
    }

}
