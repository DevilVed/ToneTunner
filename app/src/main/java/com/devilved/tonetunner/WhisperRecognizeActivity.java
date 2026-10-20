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
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.devilved.tonetunner.asr.Recorder;
import com.devilved.tonetunner.asr.RecordingGesture;
import com.devilved.tonetunner.asr.Whisper;
import com.devilved.tonetunner.asr.WhisperResult;
import com.devilved.tonetunner.cloud.AiSettings;
import com.devilved.tonetunner.cloud.ProviderConfig;
import com.devilved.tonetunner.utils.HapticFeedback;
import com.github.houbb.opencc4j.util.ZhConverterUtil;

import java.util.ArrayList;
import java.util.Locale;

public class WhisperRecognizeActivity extends AppCompatActivity {
    private static final int REQUEST_RECORD_AUDIO = 11;

    private ImageButton btnRecord;
    private ImageButton btnCancel;
    private ImageButton btnModeAuto;
    private ProgressBar processingBar;
    private Recorder mRecorder;
    private Whisper mWhisper;
    private SharedPreferences sp;
    private boolean modeAuto;
    private boolean pendingAutoStart;
    private boolean sessionActive;
    private boolean recording;
    private final RecordingGesture recordingGesture = new RecordingGesture();
    private boolean processing;
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
                    if (recording && recordingGesture.up(event.getEventTime()) == RecordingGesture.Action.STOP)
                        stopRecordingForTranscription();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                        recordingGesture.cancel();
                    if (sessionActive) cancelAndFinish();
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
        if (recording || processing) return false;
        if (!allowCloudProcessing()) return false;

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            pendingAutoStart = automatic;
            Toast.makeText(this, R.string.need_record_audio_permission, Toast.LENGTH_SHORT).show();
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
            return false;
        }

        long token = ++sessionGeneration;
        sessionActive = true;
        recording = true;
        processing = false;
        updateRecordingScreen();
        try {
            ensureWhisper(token);
            createRecorder(token);
            if (modeAuto) mRecorder.initVad();
            HapticFeedback.vibrate(this);
            mRecorder.start();
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
            if (settings.isCloudTranscription()) {
                ProviderConfig config = settings.getTranscriptionConfig();
                if (config == null || TextUtils.isEmpty(config.apiKey)) {
                    Toast.makeText(this, R.string.entrypoint_cloud_key_missing, Toast.LENGTH_LONG).show();
                    setResult(RESULT_CANCELED, null);
                    finish();
                    return false;
                }
            }
            if (settings.isEnhancementEnabled()) {
                ProviderConfig config = settings.getEnhancementConfig();
                if (config == null || TextUtils.isEmpty(config.apiKey)) {
                    Toast.makeText(this, R.string.entrypoint_cloud_key_missing, Toast.LENGTH_LONG).show();
                    setResult(RESULT_CANCELED, null);
                    finish();
                    return false;
                }
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
                if (Whisper.MSG_PROCESSING.equals(message)) {
                    recording = false;
                    processing = true;
                    processingBar.setProgress(0);
                    processingBar.setIndeterminate(true);
                } else if (Whisper.MSG_ENHANCING.equals(message)) {
                    recording = false;
                    processing = true;
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
        });
        whisper.loadModel();
    }

    private void createRecorder(long token) {
        releaseRecorder(false);
        Recorder recorder = new Recorder(this);
        mRecorder = recorder;
        recorder.setListener(message -> runOnUiThread(() -> onRecorderMessage(token, message)));
    }

    private void onRecorderMessage(long token, String message) {
        if (!isCurrentSession(token)) return;
        if (Recorder.MSG_RECORDING_DONE.equals(message)) {
            HapticFeedback.vibrate(this);
            recording = false;
            updateRecordingScreen();
            startTranscription(token);
        } else if (Recorder.MSG_RECORDING.equals(message)) {
            updateRecordingScreen();
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
        btnRecord.setContentDescription(getString(R.string.record_button));
        btnRecord.setEnabled(false);
        processingBar.setProgress(0);
        processingBar.setIndeterminate(true);
        mRecorder.stop();
        resetRecordButton();
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
        mWhisper.start(mRecorder == null ? null : mRecorder.takeRecording());
    }

    private void handleResult(long token, WhisperResult whisperResult) {
        if (!isCurrentSession(token)) return;
        String rawText = whisperResult.getResult();
        String result = rawText == null ? "" : rawText.trim();
        if (result.isEmpty()) {
            showFailure(token, getString(R.string.error_no_input));
            return;
        }
        if ("zh".equals(whisperResult.getLanguage())) {
            boolean simpleChinese = sp.getBoolean("simpleChinese", false);
            result = simpleChinese ? ZhConverterUtil.toSimple(result) : ZhConverterUtil.toTraditional(result);
        }
        sendResult(token, result);
    }

    // EXTRA_LANGUAGE comes from another app: accept only a plain language code.
    static String sanitizeLanguageCode(String requested) {
        String code = requested.split("[-_]")[0].toLowerCase(Locale.ROOT);
        return code.matches("[a-z]{2,3}") ? code : "auto";
    }

    private void sendResult(long token, String result) {
        if (!isCurrentSession(token)) return;
        Intent resultIntent = new Intent();
        ArrayList<String> results = new ArrayList<>();
        results.add(result);
        resultIntent.putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, results);
        resultIntent.putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, new float[]{1.0f});
        setResult(RESULT_OK, resultIntent);
        sessionActive = false;
        recording = false;
        updateRecordingScreen();
        processing = false;
        releaseRecorder(true);
        deinitModel();
        finish();
    }

    private boolean isCurrentSession(long token) {
        return token == sessionGeneration && sessionActive && !isFinishing() && !isDestroyed();
    }

    private void showFailure(long token, String safeMessage) {
        if (token != sessionGeneration || !sessionActive || isFinishing() || isDestroyed()) return;
        sessionActive = false;
        recording = false;
        updateRecordingScreen();
        processing = false;
        processingBar.setIndeterminate(false);
        processingBar.setProgress(0);
        releaseRecorder(true);
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
        updateRecordingScreen();
        processing = false;
        if (processingBar != null) {
            processingBar.setIndeterminate(false);
            processingBar.setProgress(0);
        }
        releaseRecorder(true);
        deinitModel();
        resetRecordButton();
    }

    private void releaseRecorder(boolean stopFirst) {
        Recorder recorder = mRecorder;
        mRecorder = null;
        if (recorder == null) return;
        recorder.setListener(null);
        if (stopFirst && recorder.isInProgress()) recorder.stop();
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

    private void updateRecordingScreen() {
        if (recording) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onPause() {
        cancelCurrentSession();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        cancelCurrentSession();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_RECORD_AUDIO) return;
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted && pendingAutoStart && !isFinishing()) {
            pendingAutoStart = false;
            beginRecording(true);
        } else if (!granted) {
            pendingAutoStart = false;
            Toast.makeText(this, R.string.entrypoint_permission_denied, Toast.LENGTH_LONG).show();
            if (modeAuto) {
                setResult(RESULT_CANCELED, null);
                finish();
            }
        }
    }
}
