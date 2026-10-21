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
        /** Called on the main thread. Return true only after the destination accepts the text. */
        default boolean onResultDelivery(WhisperResult result) {
            onResultReceived(result);
            return true;
        }
        default void onError(String message) {}
        default void onError(int code, String message) { onError(message); }
    }

    private static final String[] MODEL_FILES = {
        "Whisper_initializer.onnx", "Whisper_encoder.onnx", "Whisper_decoder.onnx",
        "Whisper_cache_initializer.onnx", "Whisper_cache_initializer_batch.onnx",
        "Whisper_detokenizer.onnx"
    };
    public static final String MSG_PREPARING = "Preparing...";
    public static final String MSG_PROCESSING = "Processing...";
    public static final String MSG_ENHANCING = "Enhancing...";
    public static final String MSG_PROCESSING_DONE = "Processing done...!";
    /** OpenAI rejects uploads under 0.1 s; a window that short cannot hold a spoken word. */
    static final int MIN_CLOUD_WINDOW_BYTES = PcmRecording.SAMPLE_RATE * PcmRecording.BYTES_PER_SAMPLE / 10;

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
    private boolean archiving;
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

    public void start(PcmRecording recording) { start(recording, null); }

    /** Explicit user retry; retained audio is never sent again automatically. */
    public void retrySaved(String id) { start(null, id); }

    private synchronized void start(PcmRecording recording, String savedId) {
        if (destroyed) { closeRecording(recording); return; }
        long token = gate.begin();
        if (token < 0) { closeRecording(recording); return; }
        Recognizer.Action requestedAction = action;
        String requestedLanguage = language;
        // The task owns this finished capture until archival is complete. Cancellation must not
        // cancel a queued archive or close/delete its still-disposable source on the main thread.
        archiving = true;
        update(token, MSG_PREPARING);
        work = worker.submit(() -> prepareAndProcess(token, recording, savedId,
                requestedAction, requestedLanguage));
    }

    private void prepareAndProcess(long token, PcmRecording recording, String savedId,
                                   Recognizer.Action initialAction, String initialLanguage) {
        RecoveryStore.Entry retained = null;
        Recognizer.Action requestedAction = initialAction;
        String requestedLanguage = initialLanguage;
        boolean explicitRetry = savedId != null;
        try {
            if (explicitRetry) {
                retained = new RecoveryStore(context).get(savedId);
                if (retained == null) throw new IOException("The saved dictation is unavailable.");
                requestedAction = retained.translate ? Recognizer.ACTION_TRANSLATE : Recognizer.ACTION_TRANSCRIBE;
                requestedLanguage = retained.requestedLanguage;
            } else if (recording != null && recording.lengthBytes() >= 2) {
                // Always finish archival, even after cancellation, before checking the generation.
                // Rename and atomic metadata/fsync run on this worker; no PCM copy is needed.
                retained = new RecoveryStore(context).save(recording, 0, "", requestedLanguage,
                        requestedLanguage, requestedAction == Recognizer.ACTION_TRANSLATE, null, false);
            }
        } catch (IOException | RuntimeException e) {
            if (recording != null) {
                synchronized (recording) {
                    if (!recording.isClosed()) {
                        try { recording.retainForRecoveryFailure(); } catch (IOException ignored) { }
                    }
                }
            }
            synchronized (this) { if (isCurrent(token)) archiving = false; }
            // Retry lookups fail with fixed RecoveryStore messages (e.g. an entry already discarded).
            String message = explicitRetry && e instanceof IOException ? e.getMessage()
                    : "Could not prepare saved dictation storage. Free storage and open Saved dictations before trying again.";
            if (isCurrent(token)) error(token, message);
            return;
        } finally {
            closeRecording(recording);
        }
        synchronized (this) {
            if (!isCurrent(token)) return;
            archiving = false;
        }
        final RecoveryStore.Entry session = retained;
        if (session == null) {
            error(token, android.speech.SpeechRecognizer.ERROR_CLIENT,
                    "No recording available. Record your speech again.");
            return;
        }
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
        } catch (Exception e) {
            String message = e instanceof IOException ? e.getMessage()
                    : "Could not read AI settings. Open AI settings and save your configuration again.";
            if (isCurrent(token)) preserveAndReport(token, null, session, session.offset,
                    session.transcript, requestedLanguage, session.detectedLanguage, requestedAction,
                    session.overlap, message, android.speech.SpeechRecognizer.ERROR_SERVER);
            return;
        }
        CloudClient client = new CloudClient();
        synchronized (this) {
            if (!isCurrent(token)) return;
            cloud = client;
        }
        update(token, MSG_PROCESSING);
        LocalTranscriber engine = null;
        PcmRecording input = null;
        StringBuilder transcript = new StringBuilder(session.transcript);
        String detectedLanguage = session.detectedLanguage;
        long resumeOffset = session.offset;
        boolean overlap = session.overlap;
        try {
            if (!isCurrent(token)) return;
            PcmRecording opened = new RecoveryStore(context).open(session);
            synchronized (Whisper.this) {
                if (!isCurrent(token)) { opened.close(); return; }
                activeRecording = opened;
                input = opened;
            }
            String inferenceLanguage = CloudClient.normalizeLanguage(requestedLanguage);
            boolean automaticLanguage = inferenceLanguage.isEmpty();
            if (automaticLanguage) inferenceLanguage = CloudClient.normalizeDetectedLanguage(detectedLanguage);
            if (inferenceLanguage.isEmpty()) inferenceLanguage = "auto";
            while (isCurrent(token)) {
                resumeOffset = input.positionBytes();
                byte[] chunk = input.nextChunk();
                if (chunk == null) break;
                long end = input.positionBytes();
                boolean hardCut = input.wasHardCut();
                boolean hasText = false;
                // A sub-0.1 s tail (e.g. after a silent window ended at the 28 s bound) would make the
                // provider reject the whole dictation, including on every explicit retry.
                boolean cloudSliver = transcription.provider != Provider.LOCAL
                        && chunk.length < MIN_CLOUD_WINDOW_BYTES;
                try {
                    if (!cloudSliver && (explicitRetry || !isVeryQuiet(chunk))) {
                        if (transcription.provider == Provider.LOCAL && engine == null) {
                            LocalTranscriber candidate = localFactory.create(context);
                            synchronized (Whisper.this) {
                                if (!isCurrent(token)) { candidate.close(); return; }
                                local = candidate;
                                engine = candidate;
                            }
                        }
                        CloudClient.Transcript part = engine == null
                                ? client.transcribe(chunk, transcription, inferenceLanguage,
                                        requestedAction == Recognizer.ACTION_TRANSLATE)
                                : engine.transcribe(chunk, inferenceLanguage, requestedAction);
                        if (engine != null && part.text != null && "[(und)]".equals(part.text.trim())) {
                            throw new IOException("The local model could not decode this audio window. The dictation can be retried.");
                        }
                        if (part.text != null && !part.text.trim().isEmpty()) {
                            if (overlap) TranscriptJoiner.appendOverlapping(transcript, part.text);
                            else TranscriptJoiner.append(transcript, part.text);
                            hasText = true;
                            String reported = CloudClient.normalizeDetectedLanguage(part.language);
                            if (automaticLanguage && "auto".equals(inferenceLanguage) && !reported.isEmpty()) {
                                inferenceLanguage = reported;
                                detectedLanguage = reported;
                            } else if (!automaticLanguage) detectedLanguage = inferenceLanguage;
                        }
                    }
                } catch (CloudClient.NoSpeechException ignored) {
                    // A pause does not discard earlier speech or stop the rest of the recording.
                } finally { clear(chunk); }
                if (!isCurrent(token)) return;
                overlap = hardCut && hasText;
                resumeOffset = end - (overlap ? PcmRecording.SAMPLE_RATE : 0); // 500 ms of PCM16.
                input.seekTo(resumeOffset);
                synchronized (Whisper.this) {
                    if (!isCurrent(token)) return;
                    new RecoveryStore(context).save(input, resumeOffset, transcript.toString(),
                            requestedLanguage, detectedLanguage,
                            requestedAction == Recognizer.ACTION_TRANSLATE, session.id, overlap);
                }
            }
            if (!isCurrent(token)) return;
            closeLocal(engine);
            engine = null;
            String text = transcript.toString();
            if (text.trim().isEmpty()) throw new CloudClient.NoSpeechException();
            String warning = input.getCaptureWarning();
            if (enhance) {
                update(token, MSG_ENHANCING);
                try {
                    StringBuilder enhanced = new StringBuilder();
                    for (String piece : TextWindows.split(text, 3000)) {
                        if (!isCurrent(token)) return;
                        TranscriptJoiner.appendEnhanced(enhanced, piece,
                                client.enhance(piece, enhancement, prompt, vocabulary));
                    }
                    text = enhanced.toString();
                } catch (IOException | RuntimeException e) {
                    if (!isCurrent(token)) return;
                    warning = joinWarning(warning, "Enhancement failed. Returned the complete raw transcript.");
                }
            }
            if (!isCurrent(token)) return;
            WhisperResult result = new WhisperResult(text,
                    detectedLanguage == null ? requestedLanguage : detectedLanguage, requestedAction, warning);
            new RecoveryStore(context).prepareDelivery(session);
            closeRecording(input);
            input = null;
            main.post(() -> {
                boolean delivered = false;
                WhisperListener destination = listener;
                try {
                    if (!gate.complete(token)) return;
                    if (destination != null) {
                        destination.onUpdateReceived(MSG_PROCESSING_DONE);
                        delivered = destination.onResultDelivery(result);
                    }
                } catch (RuntimeException ignored) {
                    // Destination failures may contain dictated text; never log their exception/body.
                    android.util.Log.w("ToneTunner", "Could not deliver dictation; saved audio retained.");
                    if (destination != null && listener == destination) {
                        try {
                            destination.onError(android.speech.SpeechRecognizer.ERROR_CLIENT,
                                    "Could not deliver the result. Open Saved dictations in ToneTunner to retry or copy the completed text.");
                        } catch (RuntimeException ignoredAgain) {
                            android.util.Log.w("ToneTunner", "The dictation destination could not display its recovery notice.");
                        }
                    }
                } finally {
                    try { new RecoveryStore(context).finishDelivery(session, delivered); }
                    catch (IOException | RuntimeException ignored) { /* Keep the recoverable copy. */ }
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (isCurrent(token)) {
                int code = e instanceof CloudClient.NoSpeechException
                        ? android.speech.SpeechRecognizer.ERROR_NO_MATCH
                        : android.speech.SpeechRecognizer.ERROR_SERVER;
                String message = e instanceof IOException ? e.getMessage()
                        : "Dictation failed. Check your AI settings and try again.";
                if (e instanceof CloudClient.NoSpeechException && transcript.length() == 0) {
                    resumeOffset = 0;
                    overlap = false;
                }
                preserveAndReport(token, input, session, resumeOffset, transcript.toString(),
                        requestedLanguage, detectedLanguage, requestedAction, overlap, message, code);
            }
        } finally {
            closeLocal(engine);
            closeRecording(input);
            synchronized (Whisper.this) { if (cloud == client) cloud = null; }
        }
    }

    private void preserveAndReport(long token, PcmRecording recording, RecoveryStore.Entry saved,
            long offset, String transcript, String requestedLanguage, String detectedLanguage,
            Recognizer.Action requestedAction, boolean overlap, String message, int code) {
        String recoveryNotice = "";
        try {
            if (recording != null && !recording.isClosed() && recording.lengthBytes() >= 2) {
                new RecoveryStore(context).save(recording, offset, transcript, requestedLanguage,
                        detectedLanguage, requestedAction == Recognizer.ACTION_TRANSLATE,
                        saved == null ? null : saved.id, overlap);
                recoveryNotice = " Audio and completed text are saved. Open Saved dictations in ToneTunner to retry or discard.";
            } else if (saved != null) recoveryNotice = " The saved dictation is still available in ToneTunner.";
        } catch (IOException | RuntimeException e) {
            recoveryNotice = " Recovery could not be fully saved. Check Saved dictations and available storage.";
        }
        error(token, code, (message == null ? "Dictation failed." : message) + recoveryNotice);
    }

    /** A short-frame floor, not a speech classifier: silence must not dilute a soft utterance. */
    private static boolean isVeryQuiet(byte[] pcm) {
        if (pcm == null || pcm.length < 2) return true;
        final int frameBytes = PcmRecording.SAMPLE_RATE * PcmRecording.BYTES_PER_SAMPLE / 50; // 20 ms.
        for (int frameStart = 0; frameStart + 1 < pcm.length; frameStart += frameBytes) {
            int end = Math.min(pcm.length & ~1, frameStart + frameBytes);
            long squares = 0;
            for (int i = frameStart; i + 1 < end; i += 2) {
                int value = (short) ((pcm[i] & 255) | (pcm[i + 1] << 8));
                squares += (long) value * value;
            }
            // About -54 dBFS per frame; any useful-energy frame preserves the entire window.
            if (squares > 64L * 64L * ((end - frameStart) / 2)) return false;
        }
        return true;
    }

    private static String joinWarning(String first, String second) {
        return first == null || first.isEmpty() ? second : first + " " + second;
    }

    public synchronized void stop() {
        gate.cancel();
        PcmRecording reader = activeRecording;
        activeRecording = null;
        if (cloud != null) { cloud.cancel(); cloud = null; }
        if (work != null) {
            if (!archiving) work.cancel(true);
            work = null;
        }
        archiving = false;
        if (local != null) { local.close(); local = null; }
        if (reader != null) {
            // A bounded read can still be slow under storage pressure. Close on the worker rather
            // than waiting for its recording monitor from the UI cancellation path.
            try { worker.execute(() -> closeRecording(reader)); }
            catch (java.util.concurrent.RejectedExecutionException ignored) { closeRecording(reader); }
        }
    }

    public synchronized void unloadModel() {
        destroyed = true;
        stop();
        listener = null;
        // Queued accepted recordings must finish archival even after their inference was cancelled.
        worker.shutdown();
    }

    public boolean isInProgress() { return gate.isBusy(); }
    /**
     * Worker-side generation check. It takes the same monitor as stop(), so a check made inside a
     * synchronized (this) block stays true until that block's side effect (publishing a client or
     * engine, writing a checkpoint) is complete. Main-thread callbacks use gate.complete() directly:
     * that call is the single authority on whether a result or error is delivered.
     */
    private synchronized boolean isCurrent(long token) { return gate.isCurrent(token); }
    private void update(long token, String message) {
        main.post(() -> {
            WhisperListener current = listener;
            if (gate.isCurrent(token) && current != null) {
                try { current.onUpdateReceived(message); }
                catch (RuntimeException ignored) {
                    android.util.Log.w("ToneTunner", "The dictation destination could not display progress.");
                }
            }
        });
    }
    private void error(long token, String message) {
        error(token, android.speech.SpeechRecognizer.ERROR_SERVER, message);
    }
    private void error(long token, int code, String message) {
        main.post(() -> {
            if (!gate.complete(token)) return;
            WhisperListener current = listener;
            if (current != null) {
                try { current.onError(code, message == null ? "Dictation failed. Try again." : message); }
                catch (RuntimeException ignored) {
                    android.util.Log.w("ToneTunner", "The dictation destination could not display its error.");
                }
            }
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
