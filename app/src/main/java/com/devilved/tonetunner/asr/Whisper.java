package com.devilved.tonetunner.asr;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.devilved.tonetunner.cloud.AiSettings;
import com.devilved.tonetunner.cloud.CloudClient;
import com.devilved.tonetunner.cloud.Provider;
import com.devilved.tonetunner.cloud.ProviderConfig;
import com.devilved.tonetunner.voice_translation.neural_networks.NeuralNetworkApi;
import com.devilved.tonetunner.voice_translation.neural_networks.voice.Recognizer;
import com.devilved.tonetunner.voice_translation.neural_networks.voice.RecognizerListener;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shared dictation facade for the keyboard, recorder Activity and recognition service. */
public class Whisper {
    private static final LocalModelGate LOCAL_MODEL_MEMORY = new LocalModelGate();
    public interface WhisperListener {
        void onUpdateReceived(String message);
        void onResultReceived(WhisperResult result);
        default void onError(String message) {}
    }

    private static final String[] MODEL_FILES = {
        "Whisper_initializer.onnx", "Whisper_encoder.onnx", "Whisper_decoder.onnx",
        "Whisper_cache_initializer.onnx", "Whisper_cache_initializer_batch.onnx",
        "Whisper_detokenizer.onnx"
    };
    public static final String MSG_PROCESSING = "Processing...";
    public static final String MSG_ENHANCING = "Enhancing...";
    public static final String MSG_PROCESSING_DONE = "Processing done...!";

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final OperationGate gate = new OperationGate();
    interface LocalTranscriber {
        CloudClient.Transcript transcribe(byte[] pcm, String language, Recognizer.Action action)
                throws IOException, InterruptedException;
        void close();
    }
    interface LocalEngineFactory {
        LocalTranscriber create(Context context) throws IOException, InterruptedException;
    }
    private final LocalEngineFactory localFactory;
    private volatile WhisperListener listener;
    private volatile boolean destroyed;
    private Recognizer.Action action = Recognizer.ACTION_TRANSCRIBE;
    private String language = "auto";
    private Future<?> work;
    private CloudClient cloud;
    private LocalTranscriber local;
    private PcmRecording activeRecording;

    public Whisper(Context context) { this(context, LocalEngine::new); }
    Whisper(Context context, LocalEngineFactory localFactory) {
        this.context = context.getApplicationContext();
        this.localFactory = localFactory;
    }

    public static boolean isModelInstalled(Context context) {
        File directory = context.getExternalFilesDir(null);
        if (directory == null) return false;
        for (String name : MODEL_FILES) if (!new File(directory, name).isFile()) return false;
        return true;
    }

    public void setListener(WhisperListener listener) { this.listener = listener; }
    /** Local models are loaded lazily; opening settings or the keyboard never starts a download. */
    public void loadModel() {}
    public synchronized void setAction(Recognizer.Action action) { this.action = action; }
    public synchronized void setLanguage(String language) {
        this.language = language == null || language.isEmpty() ? "auto" : language;
    }

    public void start() { start(null); }

    public synchronized void start(PcmRecording recording) {
        if (destroyed) { closeRecording(recording); return; }
        long token = gate.begin();
        if (token < 0) { closeRecording(recording); return; }
        activeRecording = recording;
        Recognizer.Action requestedAction = action;
        String requestedLanguage = language;
        // One settings snapshot and one destination per stage for the entire operation.
        final ProviderConfig transcription;
        final ProviderConfig enhancement;
        final boolean enhance;
        final String prompt;
        final String vocabulary;
        try {
            AiSettings settings = new AiSettings(context);
            transcription = settings.getTranscriptionConfig();
            enhance = settings.isEnhancementEnabled();
            enhancement = enhance ? settings.getEnhancementConfig() : null;
            prompt = settings.getEnhancementPrompt();
            vocabulary = settings.getVocabulary();
            if ((transcription.provider != Provider.LOCAL || enhance) && !settings.isCloudConsentGranted()) {
                throw new IOException("Open AI settings and save your cloud processing choice before recording.");
            }
        } catch (IOException e) {
            closeRecording(recording);
            error(token, e.getMessage());
            return;
        } catch (Exception e) {
            closeRecording(recording);
            error(token, "Could not read AI settings. Open AI settings and save your configuration again.");
            return;
        }
        if (recording == null || recording.lengthBytes() < 2) {
            closeRecording(recording);
            error(token, "No recording available. Record your speech again.");
            return;
        }
        CloudClient client = new CloudClient();
        cloud = client;
        update(token, MSG_PROCESSING);
        work = worker.submit(() -> {
            LocalTranscriber engine = null;
            try {
                if (!gate.isCurrent(token)) return;
                if (transcription.provider == Provider.LOCAL) {
                    // Await native cleanup off the UI thread and outside the facade lock.
                    LocalTranscriber candidate = localFactory.create(context);
                    synchronized (Whisper.this) {
                        if (!gate.isCurrent(token)) { candidate.close(); return; }
                        local = candidate;
                        engine = candidate;
                    }
                }
                StringBuilder transcript = new StringBuilder();
                String detectedLanguage = requestedLanguage;
                byte[] chunk;
                while (gate.isCurrent(token) && (chunk = recording.nextChunk()) != null) {
                    try {
                        // Digital silence contains no speech; do not ask a model to hallucinate it.
                        boolean silence = true;
                        for (byte value : chunk) { if (value != 0) { silence = false; break; } }
                        if (silence) continue;
                        CloudClient.Transcript part = engine == null
                                ? client.transcribe(chunk, transcription, requestedLanguage,
                                        requestedAction == Recognizer.ACTION_TRANSLATE)
                                : engine.transcribe(chunk, requestedLanguage, requestedAction);
                        if (part.text != null && !part.text.trim().isEmpty()) {
                            TranscriptJoiner.append(transcript, part.text);
                        }
                        if (part.language != null && !part.language.isEmpty()
                                && !part.language.equals("auto")) detectedLanguage = part.language;
                    } catch (CloudClient.NoSpeechException ignored) {
                        // A thinking pause may fill a whole window. Continue with later speech.
                    } finally { clear(chunk); }
                }
                if (!gate.isCurrent(token)) return;
                // Audio inference is finished; cloud text cleanup does not need native model RAM.
                closeLocal(engine);
                engine = null;
                String text = transcript.toString();
                if (text.trim().isEmpty()) throw new IOException("No speech was recognized. Try recording again.");
                if (enhance) {
                    update(token, MSG_ENHANCING);
                    StringBuilder enhanced = new StringBuilder();
                    for (String piece : TextWindows.split(text, 3000)) {
                        if (!gate.isCurrent(token)) return;
                        TranscriptJoiner.append(enhanced, client.enhance(piece, enhancement, prompt, vocabulary));
                    }
                    text = enhanced.toString();
                }
                if (!gate.isCurrent(token)) return;
                WhisperResult result = new WhisperResult(text,
                        detectedLanguage == null ? requestedLanguage : detectedLanguage, requestedAction);
                main.post(() -> {
                    if (!gate.complete(token)) return;
                    WhisperListener current = listener;
                    if (current != null) {
                        current.onUpdateReceived(MSG_PROCESSING_DONE);
                        current.onResultReceived(result);
                    }
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                error(token, e.getMessage());
            } catch (Exception e) {
                error(token, "Dictation failed. Check your AI settings and try again.");
            } finally {
                // Retain the model across this operation's chunks, then free idle native memory so
                // another facade (keyboard, Activity or recognition service) can acquire the lease.
                closeLocal(engine);
                closeRecording(recording);
                synchronized (Whisper.this) {
                    if (cloud == client) cloud = null;
                }
            }
        });
    }

    public synchronized void stop() {
        gate.cancel();
        closeRecording(activeRecording);
        activeRecording = null;
        if (cloud != null) { cloud.cancel(); cloud = null; }
        if (work != null) { work.cancel(true); work = null; }
        if (local != null) { local.close(); local = null; }
    }

    public synchronized void unloadModel() {
        destroyed = true;
        stop();
        listener = null;
        worker.shutdownNow();
    }

    public boolean isInProgress() { return gate.isBusy(); }
    private void update(long token, String message) {
        main.post(() -> {
            WhisperListener current = listener;
            if (gate.isCurrent(token) && current != null) current.onUpdateReceived(message);
        });
    }
    private void error(long token, String message) {
        main.post(() -> {
            if (!gate.complete(token)) return;
            WhisperListener current = listener;
            if (current != null) current.onError(message == null ? "Dictation failed. Try again." : message);
        });
    }
    private void closeRecording(PcmRecording recording) {
        if (recording == null) return;
        try { recording.close(); } catch (IOException ignored) {}
        synchronized (this) { if (activeRecording == recording) activeRecording = null; }
    }
    private void closeLocal(LocalTranscriber engine) {
        if (engine == null) return;
        engine.close();
        synchronized (this) { if (local == engine) local = null; }
    }
    private static void clear(byte[] bytes) { if (bytes != null) Arrays.fill(bytes, (byte) 0); }

    /** Each engine owns its callbacks, so a cancelled engine cannot complete a later request. */
    private static final class LocalEngine implements LocalTranscriber {
        private final CountDownLatch ready = new CountDownLatch(1);
        private final Recognizer recognizer;
        private final LocalModelGate.Lease memoryLease;
        private final AtomicBoolean closing = new AtomicBoolean();
        private volatile boolean loadFailed;
        private volatile LocalRequest pending;

        LocalEngine(Context context) throws IOException, InterruptedException {
            if (!isModelInstalled(context)) {
                throw new IOException("Install the local Whisper model from AI settings, or choose a cloud provider.");
            }
            memoryLease = LOCAL_MODEL_MEMORY.acquire();
            try {
            recognizer = new Recognizer(context, false, new NeuralNetworkApi.InitListener() {
                @Override public void onInitializationFinished() { ready.countDown(); }
                @Override public void onError(int[] reasons, long value) {
                    loadFailed = true;
                    ready.countDown();
                }
            });
            } catch (RuntimeException | Error e) {
                memoryLease.close();
                throw e;
            }
            recognizer.addCallback(new RecognizerListener() {
                @Override public void onSpeechRecognizedResult(String text, String language,
                        double confidence, boolean isFinal) {
                    LocalRequest request = pending;
                    if (request != null && isFinal) {
                        request.result = new CloudClient.Transcript(text, language);
                        request.done.countDown();
                    }
                }
                @Override public void onError(int[] reasons, long value) {
                    LocalRequest request = pending;
                    if (request != null) { request.failed = true; request.done.countDown(); }
                }
            });
        }
        @Override public CloudClient.Transcript transcribe(byte[] pcm, String language, Recognizer.Action action)
                throws IOException, InterruptedException {
            if (!ready.await(90, TimeUnit.SECONDS) || loadFailed) {
                close();
                throw new IOException("The local model could not be loaded. Check the installed model in AI settings.");
            }
            LocalRequest request = new LocalRequest();
            pending = request;
            try {
                recognizer.recognize(RecordBuffer.samples(pcm), 1, language, action);
                if (!request.done.await(180, TimeUnit.SECONDS)) {
                    close();
                    throw new IOException("Local transcription timed out. Try a shorter recording.");
                }
                if (request.failed || request.result == null) {
                    close();
                    throw new IOException("Local transcription failed. Try again.");
                }
                return request.result;
            } finally { pending = null; }
        }
        @Override public void close() {
            if (closing.compareAndSet(false, true)) recognizer.destroy(memoryLease::close);
        }
    }
    private static final class LocalRequest {
        final CountDownLatch done = new CountDownLatch(1);
        volatile CloudClient.Transcript result;
        volatile boolean failed;
    }
}
