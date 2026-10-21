package com.devilved.tonetunner;

import static android.speech.SpeechRecognizer.ERROR_CLIENT;
import static android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.devilved.tonetunner.asr.Recorder;
import com.devilved.tonetunner.asr.Whisper;
import com.devilved.tonetunner.asr.WhisperResult;
import com.devilved.tonetunner.cloud.AiSettings;
import com.devilved.tonetunner.utils.HapticFeedback;
import com.devilved.tonetunner.voice_translation.neural_networks.voice.Recognizer;
import com.github.houbb.opencc4j.util.ZhConverterUtil;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public class WhisperRecognitionService extends RecognitionService {
    static final String PREF_AUTO_STOP_AFTER_SILENCE = "recognitionServiceAutoStop";
    public static final String EXTRA_WARNING = "com.devilved.tonetunner.WARNING";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService entrypointTasks = Executors.newSingleThreadExecutor();
    private volatile RecognitionRequest activeRequest;
    private long requestSequence;
    private volatile boolean serviceDestroyed;

    private static final class RecognitionRequest {
        final long id;
        final Callback callback;
        final String languageCode;
        final boolean simpleChinese;
        final boolean autoStopAfterSilence;
        volatile Recorder recorder;
        volatile Whisper whisper;
        volatile boolean cancelled;
        volatile boolean completed;
        boolean readySent;
        boolean speechStarted;

        RecognitionRequest(long id, Callback callback, String languageCode, boolean simpleChinese,
                           boolean autoStopAfterSilence) {
            this.id = id;
            this.callback = callback;
            this.languageCode = languageCode;
            this.simpleChinese = simpleChinese;
            this.autoStopAfterSilence = autoStopAfterSilence;
        }
    }

    @Override
    protected void onStartListening(Intent recognizerIntent, Callback callback) {
        RecognitionRequest oldRequest = activeRequest;
        if (oldRequest != null) cancelRequest(oldRequest);

        final RecognitionRequest request;
        try {
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
            String languageCode = preferences.getString("recognitionServiceLanguage", "auto");
            String targetLanguage = recognizerIntent == null
                    ? null : recognizerIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE);
            if (targetLanguage != null) languageCode = WhisperRecognizeActivity.sanitizeLanguageCode(targetLanguage);
            request = new RecognitionRequest(++requestSequence, callback, languageCode,
                    preferences.getBoolean("RecognitionServiceSimpleChinese", false),
                    isAutoStopAfterSilenceEnabled(preferences));
        } catch (RuntimeException e) {
            RecognitionRequest requestWithoutSettings = new RecognitionRequest(++requestSequence, callback,
                    "auto", false, true);
            activeRequest = requestWithoutSettings;
            reportError(requestWithoutSettings, SpeechRecognizer.ERROR_SERVER);
            return;
        }
        activeRequest = request;

        if (!checkRecordPermission(request)) return;
        if (!allowCloudProcessing(request)) return;

        final AiSettings settings;
        final boolean cloudWork;
        try {
            settings = new AiSettings(this);
            cloudWork = settings.isCloudTranscription() || settings.isEnhancementEnabled();
        } catch (RuntimeException e) {
            reportError(request, SpeechRecognizer.ERROR_SERVER);
            return;
        }
        if (cloudWork) {
            try {
                entrypointTasks.execute(() -> {
                    int error;
                    try { error = settings.validateCloudCredentials(); }
                    catch (RuntimeException e) { error = R.string.entrypoint_settings_unavailable; }
                    final int validationError = error;
                    runOnMain(() -> {
                        if (!isCurrentRequest(request)) return;
                        if (validationError != 0) {
                            reportError(request, SpeechRecognizer.ERROR_SERVER);
                            return;
                        }
                        try {
                            startRequest(request);
                        } catch (RuntimeException e) {
                            reportError(request, SpeechRecognizer.ERROR_SERVER);
                        }
                    });
                });
            } catch (RejectedExecutionException e) {
                reportError(request, SpeechRecognizer.ERROR_SERVER);
            }
        } else {
            try {
                startRequest(request);
            } catch (RuntimeException e) {
                reportError(request, SpeechRecognizer.ERROR_SERVER);
            }
        }
    }

    private void startRequest(RecognitionRequest request) {
        Whisper whisper = new Whisper(this);
        request.whisper = whisper;
        whisper.setLanguage(request.languageCode);
        whisper.setListener(new Whisper.WhisperListener() {
            @Override
            public void onUpdateReceived(String message) { }

            @Override
            public void onError(String message) {
                reportError(request, SpeechRecognizer.ERROR_SERVER);
            }

            @Override
            public void onError(int errorCode, String message) {
                reportError(request, errorCode);
            }

            @Override
            public void onResultReceived(WhisperResult result) {
                runOnMain(() -> handleResult(request, result));
            }

            @Override
            public boolean onResultDelivery(WhisperResult result) {
                return handleResult(request, result);
            }
        });
        whisper.loadModel();
        if (!isCurrentRequest(request)) return;

        Recorder recorder = new Recorder(this);
        request.recorder = recorder;
        recorder.setListener(message -> runOnMain(() -> onRecorderMessage(request, message)));
        if (request.autoStopAfterSilence) recorder.initVad();
        recorder.start();
        HapticFeedback.vibrate(this);
    }

    private boolean allowCloudProcessing(RecognitionRequest request) {
        try {
            AiSettings settings = new AiSettings(this);
            boolean cloudWork = settings.isCloudTranscription() || settings.isEnhancementEnabled();
            if (!cloudWork) return true;
            if (!settings.isCloudConsentGranted()) {
                reportError(request, SpeechRecognizer.ERROR_SERVER);
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            // Keystore and settings failures, including direct-boot locked storage, share a safe result.
        }
        reportError(request, SpeechRecognizer.ERROR_SERVER);
        return false;
    }

    private boolean checkRecordPermission(RecognitionRequest request) {
        int permission = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO);
        if (permission == PackageManager.PERMISSION_GRANTED) return true;
        reportError(request, ERROR_INSUFFICIENT_PERMISSIONS);
        return false;
    }

    private void onRecorderMessage(RecognitionRequest request, String message) {
        if (!isCurrentRequest(request)) return;
        if (Recorder.MSG_RECORDING.equals(message)) {
            if (request.readySent) return;
            request.readySent = true;
            try {
                request.callback.readyForSpeech(new Bundle());
                request.callback.rmsChanged(10);
            } catch (RemoteException e) {
                cancelRequest(request);
            }
        } else if (Recorder.MSG_SPEECH_STARTED.equals(message)) {
            if (!request.readySent || request.speechStarted) return;
            request.speechStarted = true;
            try {
                request.callback.beginningOfSpeech();
            } catch (RemoteException e) {
                cancelRequest(request);
            }
        } else if (Recorder.MSG_RECORDING_DONE.equals(message)) {
            HapticFeedback.vibrate(this);
            // Hand the completed capture to Whisper before an optional protocol update can fail.
            // If the client disconnected, Whisper's worker still archives the accepted audio before
            // cancellation/unload can release the request.
            startTranscription(request);
            if (!isCurrentRequest(request)) return;
            try {
                request.callback.rmsChanged(-20.0f);
            } catch (RemoteException e) {
                cancelRequest(request);
            }
        } else if (Recorder.MSG_RECORDING_ERROR.equals(message)) {
            reportError(request, SpeechRecognizer.ERROR_AUDIO);
        } else if (Recorder.MSG_SPEECH_TIMEOUT.equals(message)) {
            reportError(request, SpeechRecognizer.ERROR_SPEECH_TIMEOUT);
        } else {
            reportError(request, ERROR_CLIENT);
        }
    }

    private void startTranscription(RecognitionRequest request) {
        if (!isCurrentRequest(request)) return;
        Whisper whisper = request.whisper;
        if (whisper == null) {
            reportError(request, SpeechRecognizer.ERROR_SERVER);
            return;
        }
        Toast.makeText(this, R.string.processing, Toast.LENGTH_SHORT).show();
        whisper.setAction(Recognizer.ACTION_TRANSCRIBE);
        whisper.setLanguage(request.languageCode);
        whisper.start(request.recorder == null ? null : request.recorder.takeRecording());
    }

    private boolean handleResult(RecognitionRequest request, WhisperResult whisperResult) {
        if (!isCurrentRequest(request)) return false;
        String rawText = whisperResult.getResult();
        String text = rawText == null ? "" : rawText.trim();
        if (text.isEmpty()) {
            reportError(request, SpeechRecognizer.ERROR_NO_MATCH);
            return false;
        }
        if ("zh".equals(whisperResult.getLanguage())) {
            text = request.simpleChinese ? ZhConverterUtil.toSimple(text) : ZhConverterUtil.toTraditional(text);
        }

        Bundle results = new Bundle();
        ArrayList<String> resultList = new ArrayList<>();
        resultList.add(text);
        results.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, resultList);
        if (!TextUtils.isEmpty(whisperResult.getWarning())) {
            results.putString(EXTRA_WARNING, whisperResult.getWarning());
        }
        try {
            request.callback.endOfSpeech();
            request.callback.results(results);
            return true;
        } catch (RemoteException e) {
            // Keep the saved dictation when the caller can no longer receive it.
            return false;
        } finally {
            finishRequest(request);
        }
    }

    private void reportError(RecognitionRequest request, int errorCode) {
        runOnMain(() -> {
            if (!isCurrentRequest(request)) return;
            try {
                request.callback.error(errorCode);
            } catch (RemoteException e) {
                // The caller may have closed; cleanup still releases audio and model resources.
            } finally {
                finishRequest(request);
            }
        });
    }

    @Override
    protected void onStopListening(Callback callback) {
        RecognitionRequest request = requestForCallback(callback);
        if (request == null) return;
        Recorder recorder = request.recorder;
        if (recorder == null) {
            // A caller can stop before off-main credential validation finishes. Do not open the
            // microphone afterward for a request that has already ended.
            reportError(request, ERROR_CLIENT);
        } else if (recorder.isInProgress()) {
            try {
                entrypointTasks.execute(recorder::stop);
            } catch (RejectedExecutionException ignored) {
                // Service teardown releases the recorder without waiting on the caller thread.
            }
        }
    }

    @Override
    protected void onCancel(Callback callback) {
        RecognitionRequest request = requestForCallback(callback);
        if (request != null) cancelRequest(request);
    }

    private RecognitionRequest requestForCallback(Callback callback) {
        RecognitionRequest request = activeRequest;
        if (request == null || !callbackOwnsRequest(request.callback, callback) || !isCurrentRequest(request)) {
            return null;
        }
        return request;
    }

    static boolean callbackOwnsRequest(Object requestCallback, Object suppliedCallback) {
        return requestCallback != null && requestCallback == suppliedCallback;
    }

    static boolean isAutoStopAfterSilenceEnabled(SharedPreferences preferences) {
        return preferences.getBoolean(PREF_AUTO_STOP_AFTER_SILENCE, true);
    }

    private boolean isCurrentRequest(RecognitionRequest request) {
        return request != null && request.id == requestSequence && activeRequest == request
                && !request.cancelled && !request.completed
                && !serviceDestroyed;
    }

    private void cancelRequest(RecognitionRequest request) {
        if (request == null || request.cancelled || request.completed) return;
        request.cancelled = true;
        if (activeRequest == request) activeRequest = null;
        releaseRecorder(request);
        unloadWhisper(request);
    }

    private void finishRequest(RecognitionRequest request) {
        if (request == null || request.completed) return;
        request.completed = true;
        if (activeRequest == request) activeRequest = null;
        releaseRecorder(request);
        unloadWhisper(request);
    }

    private void releaseRecorder(RecognitionRequest request) {
        Recorder recorder = request.recorder;
        request.recorder = null;
        if (recorder == null) return;
        recorder.setListener(null);
        recorder.release();
    }

    private void unloadWhisper(RecognitionRequest request) {
        Whisper whisper = request.whisper;
        request.whisper = null;
        if (whisper == null) return;
        whisper.setListener(null);
        whisper.stop();
        whisper.unloadModel();
    }

    private void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else mainHandler.post(action);
    }

    @Override
    public void onDestroy() {
        serviceDestroyed = true;
        RecognitionRequest request = activeRequest;
        if (request != null) cancelRequest(request);
        entrypointTasks.shutdownNow();
        super.onDestroy();
    }
}
