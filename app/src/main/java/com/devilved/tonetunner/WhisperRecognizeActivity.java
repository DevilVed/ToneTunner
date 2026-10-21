package com.devilved.tonetunner;

import static com.devilved.tonetunner.voice_translation.neural_networks.voice.Recognizer.ACTION_TRANSCRIBE;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.devilved.tonetunner.asr.Recorder;
import com.devilved.tonetunner.asr.RecordingGesture;
import com.devilved.tonetunner.asr.Whisper;
import com.devilved.tonetunner.asr.WhisperResult;
import com.devilved.tonetunner.cloud.AiSettings;
import com.devilved.tonetunner.cloud.CloudClient;
import com.devilved.tonetunner.utils.HapticFeedback;
import com.github.houbb.opencc4j.util.ZhConverterUtil;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public class WhisperRecognizeActivity extends AppCompatActivity {
    private static final int REQUEST_RECORD_AUDIO = 11;

    private ImageButton btnRecord;
    private ImageButton btnCancel;
    private ImageButton btnModeAuto;
    private ProgressBar processingBar;
    private TextView tvStatus;
    private Recorder mRecorder;
    private Whisper mWhisper;
    private SharedPreferences sp;
    private boolean modeAuto;
    private boolean pendingAutoStart;
    private boolean pendingStartAfterPermission;
    private boolean sessionActive;
    private boolean recording;
    private final RecordingGesture recordingGesture = new RecordingGesture();
    private final ExecutorService entrypointTasks = Executors.newSingleThreadExecutor();
    private boolean processing;
    // True once Whisper owns the finished capture: it is archived and survives cancellation.
    private boolean submitted;
    private boolean validating;
    private long sessionGeneration;
    private String langCode = "auto";

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sp = PreferenceManager.getDefaultSharedPreferences(this);

        String targetLang = getIntent().getStringExtra(RecognizerIntent.EXTRA_LANGUAGE);
        langCode = sp.getString("language", "auto");
        if (targetLang != null) langCode = sanitizeLanguageCode(targetLang);

        setContentView(R.layout.activity_recognize);
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.width = WindowManager.LayoutParams.MATCH_PARENT;
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        params.gravity = Gravity.BOTTOM;

        btnCancel = findViewById(R.id.btnCancel);
        btnRecord = findViewById(R.id.btnRecord);
        btnModeAuto = findViewById(R.id.btnModeAuto);
        processingBar = findViewById(R.id.processing_bar);
        tvStatus = findViewById(R.id.tv_status);

        modeAuto = sp.getBoolean("imeModeAuto", false);
        btnModeAuto.setImageResource(modeAuto ? R.drawable.ic_auto_on_36dp : R.drawable.ic_auto_off_36dp);

        btnModeAuto.setOnClickListener(view -> {
            cancelCurrentSession();
            modeAuto = !modeAuto;
            sp.edit().putBoolean("imeModeAuto", modeAuto).apply();
            btnRecord.setVisibility(modeAuto ? View.GONE : View.VISIBLE);
            btnModeAuto.setImageResource(modeAuto ? R.drawable.ic_auto_on_36dp : R.drawable.ic_auto_off_36dp);
            setResult(RESULT_CANCELED, null);
            finish();
        });

        btnRecord.setOnTouchListener((view, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    if (processing) return true;
                    if (recordingGesture.down(recording, event.getEventTime()) == RecordingGesture.Action.STOP) {
                        stopRecordingForTranscription();
                    } else if (beginRecording(false)) {
                        btnRecord.setBackgroundResource(R.drawable.rounded_button_background_pressed);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (recordingGesture.up(event.getEventTime()) == RecordingGesture.Action.STOP) {
                        if (recording) stopRecordingForTranscription();
                        else if (validating) {
                            cancelCurrentSession();
                            setStatus(R.string.entrypoint_cancelled);
                        } else if (pendingStartAfterPermission) {
                            pendingStartAfterPermission = false;
                            setStatus(R.string.entrypoint_cancelled);
                        }
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    recordingGesture.cancel();
                    if (pendingStartAfterPermission) {
                        pendingStartAfterPermission = false;
                        setStatus(R.string.entrypoint_cancelled);
                    } else if (sessionActive) cancelAndFinish();
                    else resetRecordButton();
                    return true;
                default:
                    return true;
            }
        });
        btnRecord.setOnClickListener(view -> {
            if (processing) return;
            if (recording) stopRecordingForTranscription();
            else beginRecording(false);
        });

        btnCancel.setOnClickListener(view -> cancelAndFinish());
        btnRecord.setVisibility(modeAuto ? View.GONE : View.VISIBLE);
        if (modeAuto) beginRecording(true);
    }

    private boolean beginRecording(boolean automatic) {
        if (recording || processing || validating) return false;
        if (!allowCloudProcessing()) return false;

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            pendingAutoStart = automatic;
            pendingStartAfterPermission = !automatic;
            Toast.makeText(this, R.string.need_record_audio_permission, Toast.LENGTH_SHORT).show();
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
            return false;
        }

        long token = ++sessionGeneration;
        sessionActive = true;
        recording = false;
        processing = false;
        final AiSettings settings;
        final boolean cloud;
        try {
            settings = new AiSettings(this);
            cloud = settings.isCloudTranscription() || settings.isEnhancementEnabled();
        } catch (RuntimeException e) {
            showFailure(token, getString(R.string.entrypoint_settings_unavailable));
            return false;
        }
        if (cloud) {
            validating = true;
            setStatus(R.string.entrypoint_validating);
            processingBar.setProgress(0);
            processingBar.setIndeterminate(true);
            updateRecordingScreen();
            try {
                entrypointTasks.execute(() -> {
                    int error;
                    try { error = settings.validateCloudCredentials(); }
                    catch (RuntimeException e) { error = R.string.entrypoint_settings_unavailable; }
                    final int validationError = error;
                    runOnUiThread(() -> {
                        if (!isCurrentSession(token) || !validating) return;
                        validating = false;
                        btnRecord.setEnabled(true);
                        updateRecordingScreen();
                        if (validationError != 0) showFailure(token, getString(validationError));
                        else startCapture(token);
                    });
                });
            } catch (RejectedExecutionException e) {
                validating = false;
                showFailure(token, getString(R.string.entrypoint_settings_unavailable));
            }
            return true;
        }
        return startCapture(token);
    }

    private boolean startCapture(long token) {
        if (!isCurrentSession(token)) return false;
        validating = false;
        recording = true;
        processing = false;
        updateRecordingScreen();
        try {
            ensureWhisper(token);
            createRecorder(token);
            if (modeAuto) mRecorder.initVad();
            HapticFeedback.vibrate(this);
            mRecorder.start();
            setStatus(R.string.entrypoint_recording);
            btnRecord.setContentDescription(getString(R.string.entrypoint_stop_recording));
            processingBar.setProgress(0);
            processingBar.setIndeterminate(true);
            return true;
        } catch (RuntimeException e) {
            showFailure(token, getString(R.string.entrypoint_processing_error));
            return false;
        }
    }

    private boolean allowCloudProcessing() {
        try {
            AiSettings settings = new AiSettings(this);
            boolean cloudWork = settings.isCloudTranscription() || settings.isEnhancementEnabled();
            if (!cloudWork) return true;
            if (!settings.isCloudConsentGranted()) {
                Toast.makeText(this, R.string.entrypoint_cloud_consent_toast, Toast.LENGTH_LONG).show();
                setResult(RESULT_CANCELED, null);
                finish();
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.entrypoint_settings_unavailable, Toast.LENGTH_LONG).show();
            setResult(RESULT_CANCELED, null);
            finish();
            return false;
        }
    }

    private void ensureWhisper(long token) {
        Whisper whisper = new Whisper(this);
        mWhisper = whisper;
        whisper.setLanguage(langCode);
        whisper.setListener(new Whisper.WhisperListener() {
            @Override
            public void onUpdateReceived(String message) {
                if (!isCurrentSession(token)) return;
                if (Whisper.MSG_PREPARING.equals(message)) {
                    recording = false;
                    processing = true;
                    setStatus(R.string.entrypoint_preparing);
                    processingBar.setIndeterminate(true);
                } else if (Whisper.MSG_PROCESSING.equals(message)) {
                    recording = false;
                    processing = true;
                    setStatus(R.string.entrypoint_transcribing);
                    processingBar.setProgress(0);
                    processingBar.setIndeterminate(true);
                } else if (Whisper.MSG_ENHANCING.equals(message)) {
                    recording = false;
                    processing = true;
                    setStatus(R.string.entrypoint_enhancing);
                    processingBar.setProgress(0);
                    processingBar.setIndeterminate(true);
                }
                updateRecordingScreen();
            }

            @Override
            public void onError(String message) {
                if (!isCurrentSession(token)) return;
                runOnUiThread(() -> showFailure(token, TextUtils.isEmpty(message)
                        ? getString(R.string.entrypoint_processing_error) : message));
            }

            @Override
            public void onResultReceived(WhisperResult result) {
                if (!isCurrentSession(token)) return;
                runOnUiThread(() -> handleResult(token, result));
            }

            @Override
            public boolean onResultDelivery(WhisperResult result) {
                return handleResult(token, result);
            }
        });
        whisper.loadModel();
    }

    private void createRecorder(long token) {
        releaseRecorder();
        Recorder recorder = new Recorder(this);
        mRecorder = recorder;
        recorder.setListener(message -> runOnUiThread(() -> onRecorderMessage(token, message)));
    }

    private void onRecorderMessage(long token, String message) {
        if (!isCurrentSession(token)) return;
        if (Recorder.MSG_SPEECH_STARTED.equals(message)) {
            return;
        } else if (Recorder.MSG_RECORDING_DONE.equals(message)) {
            HapticFeedback.vibrate(this);
            recording = false;
            processing = true;
            updateRecordingScreen();
            startTranscription(token);
        } else if (Recorder.MSG_RECORDING.equals(message)) {
            updateRecordingScreen();
            setStatus(R.string.entrypoint_recording);
            btnRecord.setBackgroundResource(R.drawable.rounded_button_background_pressed);
            processingBar.setIndeterminate(true);
        } else {
            showFailure(token, getString(R.string.error_no_input));
        }
    }

    private void stopRecordingForTranscription() {
        if (!recording || mRecorder == null || !mRecorder.isInProgress()) {
            resetRecordButton();
            return;
        }
        recording = false;
        processing = true;
        updateRecordingScreen();
        setStatus(R.string.entrypoint_transcribing);
        btnRecord.setContentDescription(getString(R.string.record_button));
        btnRecord.setEnabled(false);
        processingBar.setProgress(0);
        processingBar.setIndeterminate(true);
        Recorder stopping = mRecorder;
        try {
            entrypointTasks.execute(stopping::stop);
        } catch (RejectedExecutionException ignored) {
            // Teardown releases the recorder and suppresses stale callbacks.
        }
    }

    private void startTranscription(long token) {
        if (!isCurrentSession(token) || mWhisper == null) return;
        recording = false;
        processing = true;
        updateRecordingScreen();
        btnRecord.setContentDescription(getString(R.string.record_button));
        btnRecord.setEnabled(false);
        processingBar.setProgress(0);
        processingBar.setIndeterminate(true);
        mWhisper.setAction(ACTION_TRANSCRIBE);
        mWhisper.setLanguage(langCode);
        submitted = true;
        mWhisper.start(mRecorder == null ? null : mRecorder.takeRecording());
    }

    private boolean handleResult(long token, WhisperResult whisperResult) {
        if (!isCurrentSession(token)) return false;
        String rawText = whisperResult.getResult();
        String result = rawText == null ? "" : rawText.trim();
        if (result.isEmpty()) {
            showFailure(token, getString(R.string.error_no_input));
            return false;
        }
        if ("zh".equals(whisperResult.getLanguage())) {
            boolean simpleChinese = sp.getBoolean("simpleChinese", false);
            result = simpleChinese ? ZhConverterUtil.toSimple(result) : ZhConverterUtil.toTraditional(result);
        }
        return sendResult(token, result, whisperResult.getWarning());
    }

    // EXTRA_LANGUAGE comes from another app: accept only a plain language code.
    static String sanitizeLanguageCode(String requested) {
        String code = CloudClient.normalizeLanguage(requested == null ? null : requested.replace('_', '-'));
        return code.isEmpty() ? "auto" : code;
    }

    private boolean sendResult(long token, String result, String warning) {
        if (!isCurrentSession(token)) return false;
        Intent resultIntent = new Intent();
        ArrayList<String> results = new ArrayList<>();
        results.add(result);
        resultIntent.putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, results);
        if (!warning.isEmpty()) {
            resultIntent.putExtra("com.devilved.tonetunner.WARNING", warning);
            Toast.makeText(this, warning, Toast.LENGTH_LONG).show();
        }
        resultIntent.putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, new float[]{1.0f});
        setResult(RESULT_OK, resultIntent);
        sessionActive = false;
        recording = false;
        processing = false;
        submitted = false;
        validating = false;
        updateRecordingScreen();
        releaseRecorder();
        deinitModel();
        finish();
        return true;
    }

    private boolean isCurrentSession(long token) {
        return token == sessionGeneration && sessionActive && !isFinishing() && !isDestroyed();
    }

    private void showFailure(long token, String safeMessage) {
        if (token != sessionGeneration || !sessionActive || isFinishing() || isDestroyed()) return;
        sessionActive = false;
        recording = false;
        processing = false;
        submitted = false;
        validating = false;
        setStatusText(safeMessage);
        updateRecordingScreen();
        processingBar.setIndeterminate(false);
        processingBar.setProgress(0);
        releaseRecorder();
        deinitModel();
        Toast.makeText(this, safeMessage, Toast.LENGTH_LONG).show();
        btnRecord.setEnabled(true);
        if (modeAuto) {
            setResult(RESULT_CANCELED, null);
            finish();
        }
        resetRecordButton();
    }

    private void cancelAndFinish() {
        cancelCurrentSession();
        setResult(RESULT_CANCELED, null);
        finish();
    }

    private void cancelCurrentSession() {
        sessionGeneration++;
        sessionActive = false;
        recording = false;
        processing = false;
        submitted = false;
        validating = false;
        // Stop/transcription disabled the button; a cancelled session must leave it usable.
        if (btnRecord != null) btnRecord.setEnabled(true);
        updateRecordingScreen();
        if (processingBar != null) {
            processingBar.setIndeterminate(false);
            processingBar.setProgress(0);
        }
        releaseRecorder();
        deinitModel();
        resetRecordButton();
    }

    private void releaseRecorder() {
        Recorder recorder = mRecorder;
        mRecorder = null;
        if (recorder == null) return;
        recorder.setListener(null);
        recorder.release();
    }

    private void deinitModel() {
        Whisper whisper = mWhisper;
        mWhisper = null;
        if (whisper != null) {
            whisper.setListener(null);
            whisper.stop();
            whisper.unloadModel();
        }
    }

    private void resetRecordButton() {
        if (btnRecord != null) {
            btnRecord.setBackgroundResource(R.drawable.rounded_button_background);
            btnRecord.setContentDescription(getString(R.string.record_button));
        }
    }

    private void setStatus(int resource) {
        if (tvStatus == null) return;
        tvStatus.setText(resource);
        tvStatus.setVisibility(View.VISIBLE);
    }

    private void setStatusText(String text) {
        if (tvStatus == null) return;
        tvStatus.setText(text);
        tvStatus.setVisibility(View.VISIBLE);
    }

    private void updateRecordingScreen() {
        if (recording || processing || validating) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onPause() {
        boolean hadActiveSession = sessionActive || recording || processing || validating;
        boolean wasSubmitted = submitted;
        cancelCurrentSession();
        if (hadActiveSession && !isFinishing() && !isDestroyed()) {
            setStatus(wasSubmitted ? R.string.entrypoint_interrupted_saved
                    : R.string.entrypoint_interrupted_capture);
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        cancelCurrentSession();
        entrypointTasks.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_RECORD_AUDIO) return;
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted && (pendingAutoStart || pendingStartAfterPermission) && !isFinishing()) {
            boolean automaticStart = pendingAutoStart;
            pendingAutoStart = false;
            pendingStartAfterPermission = false;
            beginRecording(automaticStart);
        } else if (!granted) {
            pendingAutoStart = false;
            pendingStartAfterPermission = false;
            Toast.makeText(this, R.string.entrypoint_permission_denied, Toast.LENGTH_LONG).show();
            if (modeAuto) {
                setResult(RESULT_CANCELED, null);
                finish();
            }
        }
    }
}
