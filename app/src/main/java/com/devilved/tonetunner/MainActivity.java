package com.devilved.tonetunner;

import static com.devilved.tonetunner.voice_translation.neural_networks.voice.Recognizer.ACTION_TRANSCRIBE;
import static com.devilved.tonetunner.voice_translation.neural_networks.voice.Recognizer.ACTION_TRANSLATE;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.text.TextUtils;
import android.util.Pair;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.devilved.tonetunner.asr.Recorder;
import com.devilved.tonetunner.asr.RecoveryStore;
import com.devilved.tonetunner.asr.RecordingGesture;
import com.devilved.tonetunner.asr.Whisper;
import com.devilved.tonetunner.asr.WhisperResult;
import com.devilved.tonetunner.cloud.AiSettings;
import com.devilved.tonetunner.cloud.CloudClient;
import com.devilved.tonetunner.utils.HapticFeedback;
import com.devilved.tonetunner.utils.LanguagePairAdapter;
import com.devilved.tonetunner.utils.ThemeUtils;
import com.devilved.tonetunner.voice_translation.neural_networks.voice.Recognizer;
import com.github.houbb.opencc4j.util.ZhConverterUtil;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_RECORD_AUDIO = 10;

    private TextView tvStatus;
    private TextView tvResult;
    private TextView tvProviderSummary;
    private FloatingActionButton fabCopy;
    private ImageButton btnRecord;
    private ImageButton btnInfo;
    private Button btnAiSettings;
    private Button btnEnableKeyboard;
    private Button btnCancelProcessing;
    private Button btnSavedDictations;
    private final ExecutorService recoveryFiles = Executors.newSingleThreadExecutor();
    private final ExecutorService entrypointTasks = Executors.newSingleThreadExecutor();
    private LinearLayout layoutModeChinese;
    private LinearLayout layoutTTS;
    private CheckBox append;
    private CheckBox translate;
    private CheckBox modeSimpleChinese;
    private CheckBox modeTTS;
    private ProgressBar processingBar;

    private Recorder mRecorder;
    private Whisper mWhisper;
    private SharedPreferences sp;
    private Spinner spinnerLanguage;
    private String langCode = "auto";
    private long startTime;
    private long sessionGeneration;
    private boolean sessionActive;
    private boolean recording;
    private boolean sessionTranslateRequested;
    private final RecordingGesture recordingGesture = new RecordingGesture();
    private boolean processing;
    // True once Whisper owns the finished capture: it is archived and survives cancellation.
    private boolean submitted;
    private boolean validating;
    private boolean pendingStartAfterPermission;
    private int providerSummaryGeneration;
    private TextToSpeech tts;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ThemeUtils.setStatusBarAppearance(this);
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);

        sp = PreferenceManager.getDefaultSharedPreferences(this);
        processingBar = findViewById(R.id.processing_bar);
        append = findViewById(R.id.mode_append);
        translate = findViewById(R.id.mode_translate);
        modeSimpleChinese = findViewById(R.id.mode_simple_chinese);
        modeTTS = findViewById(R.id.mode_tts);
        layoutModeChinese = findViewById(R.id.layout_mode_chinese);
        layoutTTS = findViewById(R.id.layout_tts);
        tvStatus = findViewById(R.id.tvStatus);
        tvResult = findViewById(R.id.tvResult);
        tvProviderSummary = findViewById(R.id.tvProviderSummary);
        btnRecord = findViewById(R.id.btnRecord);
        btnInfo = findViewById(R.id.btnInfo);
        btnAiSettings = findViewById(R.id.btnAiSettings);
        btnEnableKeyboard = findViewById(R.id.btnEnableKeyboard);
        btnCancelProcessing = findViewById(R.id.btnCancelProcessing);
        btnSavedDictations = findViewById(R.id.btnSavedDictations);
        btnSavedDictations.setOnClickListener(view -> showSavedDictations());
        fabCopy = findViewById(R.id.fabCopy);

        modeTTS.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            if (isChecked) {
                tts = new TextToSpeech(this, status -> {
                    if (status == TextToSpeech.SUCCESS && tts != null) {
                        int result = tts.setLanguage(Locale.US);
                        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                            runOnUiThread(() -> {
                                Toast.makeText(this, R.string.tts_language_not_supported, Toast.LENGTH_SHORT).show();
                                modeTTS.setChecked(false);
                            });
                        }
                    } else {
                        runOnUiThread(() -> {
                            Toast.makeText(this, R.string.tts_initialization_failed, Toast.LENGTH_SHORT).show();
                            modeTTS.setChecked(false);
                        });
                    }
                });
            } else {
                deinitTTS();
            }
        });

        translate.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            layoutTTS.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            if (!isChecked) modeTTS.setChecked(false);
        });

        spinnerLanguage = findViewById(R.id.spnrLanguage);
        List<Pair<String, String>> languagePairs = LanguagePairAdapter.getLanguagePairs(this);
        LanguagePairAdapter languagePairAdapter = new LanguagePairAdapter(this, android.R.layout.simple_spinner_item,
                languagePairs);
        languagePairAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerLanguage.setAdapter(languagePairAdapter);
        langCode = sp.getString("language", "auto");
        spinnerLanguage.setSelection(languagePairAdapter.getIndexByCode(langCode));
        spinnerLanguage.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                langCode = languagePairs.get(position).first;
                sp.edit().putString("language", langCode).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });

        modeSimpleChinese.setChecked(sp.getBoolean("simpleChinese", false));
        modeSimpleChinese.setOnCheckedChangeListener((button, isChecked) -> {
            sp.edit().putBoolean("simpleChinese", isChecked).apply();
            tvResult.setText("");
        });

        btnRecord.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        if (processing) return true;
                        if (recordingGesture.down(recording, event.getEventTime()) == RecordingGesture.Action.STOP) {
                            stopRecordingForTranscription();
                        } else if (startRecording()) {
                            btnRecord.setBackgroundResource(R.drawable.rounded_button_background_pressed);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (recordingGesture.up(event.getEventTime()) == RecordingGesture.Action.STOP) {
                            if (recording) stopRecordingForTranscription();
                            else if (validating) cancelCurrentSession(R.string.entrypoint_cancelled);
                            else if (pendingStartAfterPermission) {
                                pendingStartAfterPermission = false;
                                tvStatus.setText(R.string.entrypoint_cancelled);
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        recordingGesture.cancel();
                        if (recording || validating || pendingStartAfterPermission) {
                            pendingStartAfterPermission = false;
                            cancelCurrentSession(R.string.entrypoint_cancelled);
                        }
                        else resetRecordButton();
                        return true;
                    default:
                        return true;
                }
            }
        });
        btnRecord.setOnClickListener(view -> {
            if (processing) return;
            if (recording) stopRecordingForTranscription();
            else startRecording();
        });

        btnCancelProcessing.setOnClickListener(view -> cancelCurrentSession(R.string.entrypoint_cancelled));
        btnAiSettings.setOnClickListener(view -> startActivity(new Intent(this, AiSettingsActivity.class)));
        btnEnableKeyboard.setOnClickListener(view -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        btnInfo.setOnClickListener(view -> startActivity(
                new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/DevilVed/ToneTunner"))));

        fabCopy.setOnClickListener(view -> {
            String textToCopy = tvResult.getText().toString().trim();
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.model_output), textToCopy));
            }
        });

        tvStatus.setText(R.string.entrypoint_ready);
        setControlsForState();

        if (GithubStar.shouldShowStarDialog(this)) {
            GithubStar.starDialog(this, "https://github.com/DevilVed/ToneTunner");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshProviderSummary();
        updateKeyboardStatus();
    }

    @Override
    protected void onPause() {
        boolean hadActiveSession = sessionActive || recording || processing || validating;
        boolean wasSubmitted = submitted;
        cancelCurrentSession(null);
        if (hadActiveSession && !isFinishing() && !isDestroyed()) {
            tvStatus.setText(wasSubmitted ? R.string.entrypoint_interrupted_saved
                    : R.string.entrypoint_interrupted_capture);
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        cancelCurrentSession(null);
        ++providerSummaryGeneration;
        entrypointTasks.shutdownNow();
        recoveryFiles.shutdownNow();
        deinitTTS();
        super.onDestroy();
    }

    private void refreshProviderSummary() {
        int generation = ++providerSummaryGeneration;
        try {
            entrypointTasks.execute(() -> {
                String summary;
                try {
                    AiSettings settings = new AiSettings(this);
                    summary = settings.getSummary();
                    if (TextUtils.isEmpty(summary)) {
                        summary = getString(R.string.entrypoint_provider_summary_unknown);
                    }
                    if (hasCloudWork(settings)) {
                        if (!settings.isCloudConsentGranted()) {
                            summary = getString(R.string.entrypoint_cloud_consent_missing);
                        } else {
                            int error = settings.validateCloudCredentials();
                            if (error != 0) summary = getString(error);
                        }
                    }
                } catch (RuntimeException e) {
                    summary = getString(R.string.entrypoint_provider_summary_unknown);
                }
                String result = summary;
                runOnUiThread(() -> {
                    if (generation == providerSummaryGeneration && !isFinishing() && !isDestroyed()) {
                        tvProviderSummary.setText(result);
                    }
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // The Activity is already being destroyed.
        }
    }

    private void updateKeyboardStatus() {
        InputMethodManager manager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        boolean enabled = false;
        if (manager != null) {
            String myInputMethodId = getPackageName() + "/" + WhisperInputMethodService.class.getName();
            List<InputMethodInfo> enabledMethods = manager.getEnabledInputMethodList();
            for (InputMethodInfo method : enabledMethods) {
                if (method.getId().equals(myInputMethodId)) {
                    enabled = true;
                    break;
                }
            }
        }
        int label = enabled ? R.string.entrypoint_keyboard_enabled : R.string.entrypoint_enable_keyboard;
        btnEnableKeyboard.setText(label);
        btnEnableKeyboard.setContentDescription(getString(label));
    }

    private boolean hasCloudWork(AiSettings settings) {
        return settings.isCloudTranscription() || settings.isEnhancementEnabled();
    }

    private boolean startRecording() {
        if (recording || processing || validating) return false;
        final AiSettings settings;
        final boolean translateRequested = translate.isChecked();
        try {
            settings = new AiSettings(this);
            if (translateRequested && !settings.isTranslationSupported()) {
                tvStatus.setText(R.string.entrypoint_translation_unsupported);
                resetRecordButton();
                return false;
            }
            if (hasCloudWork(settings) && !settings.isCloudConsentGranted()) {
                tvStatus.setText(R.string.entrypoint_cloud_consent_missing);
                Toast.makeText(this, R.string.entrypoint_cloud_consent_toast, Toast.LENGTH_LONG).show();
                resetRecordButton();
                return false;
            }
        } catch (RuntimeException e) {
            tvStatus.setText(R.string.entrypoint_settings_unavailable);
            resetRecordButton();
            return false;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            pendingStartAfterPermission = true;
            Toast.makeText(this, R.string.need_record_audio_permission, Toast.LENGTH_SHORT).show();
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
            resetRecordButton();
            return false;
        }

        long token = ++sessionGeneration;
        sessionActive = true;
        recording = false;
        processing = false;
        validating = hasCloudWork(settings);
        if (validating) {
            tvStatus.setText(R.string.entrypoint_validating);
            processingBar.setProgress(0);
            processingBar.setIndeterminate(true);
            setControlsForState();
            try {
                entrypointTasks.execute(() -> {
                    int error;
                    try { error = settings.validateCloudCredentials(); }
                    catch (RuntimeException e) { error = R.string.entrypoint_settings_unavailable; }
                    final int validationError = error;
                    runOnUiThread(() -> {
                        if (!isCurrentSession(token) || !validating) return;
                        validating = false;
                        if (validationError != 0) {
                            failCurrentSession(token, getString(validationError));
                        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                                != PackageManager.PERMISSION_GRANTED) {
                            failCurrentSession(token, getString(R.string.need_record_audio_permission));
                        } else {
                            beginCapture(token, translateRequested);
                        }
                    });
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                failCurrentSession(token, getString(R.string.entrypoint_settings_unavailable));
                return false;
            }
            return true;
        }
        beginCapture(token, translateRequested);
        return recording;
    }

    private void beginCapture(long token, boolean translateRequested) {
        if (!isCurrentSession(token)) return;
        validating = false;
        sessionActive = true;
        recording = true;
        processing = false;
        sessionTranslateRequested = translateRequested;
        try {
            ensureWhisper(token);
            createRecorder(token);
            HapticFeedback.vibrate(this);
            mWhisper.setAction(translateRequested ? ACTION_TRANSLATE : ACTION_TRANSCRIBE);
            mRecorder.start();
            tvStatus.setText(R.string.entrypoint_recording);
            processingBar.setProgress(0);
            processingBar.setIndeterminate(true);
            setControlsForState();
        } catch (RuntimeException e) {
            failCurrentSession(token, getString(R.string.entrypoint_processing_error));
        }
    }

    private void ensureWhisper(long token) {
        boolean loadModel = mWhisper == null;
        if (loadModel) mWhisper = new Whisper(this);
        Whisper whisper = mWhisper;
        whisper.setLanguage(langCode);
        whisper.setListener(new Whisper.WhisperListener() {
            @Override
            public void onUpdateReceived(String message) {
                if (!isCurrentSession(token)) return;
                if (Whisper.MSG_PREPARING.equals(message)) {
                    validating = false;
                    processing = true;
                    recording = false;
                    tvStatus.setText(R.string.entrypoint_preparing);
                    processingBar.setIndeterminate(true);
                    setControlsForState();
                } else if (Whisper.MSG_PROCESSING.equals(message)) {
                    startTime = System.currentTimeMillis();
                    processing = true;
                    recording = false;
                    tvStatus.setText(R.string.entrypoint_transcribing);
                    processingBar.setIndeterminate(true);
                    setControlsForState();
                } else if (Whisper.MSG_ENHANCING.equals(message)) {
                    processing = true;
                    recording = false;
                    tvStatus.setText(R.string.entrypoint_enhancing);
                    processingBar.setIndeterminate(true);
                    setControlsForState();
                } else if (Whisper.MSG_PROCESSING_DONE.equals(message)) {
                    processingBar.setIndeterminate(false);
                }
            }

            @Override
            public void onError(String message) {
                if (!isCurrentSession(token)) return;
                failCurrentSession(token, TextUtils.isEmpty(message)
                        ? getString(R.string.entrypoint_processing_error) : message);
            }

            @Override
            public void onResultReceived(WhisperResult whisperResult) {
                handleResult(token, whisperResult);
            }

            @Override
            public boolean onResultDelivery(WhisperResult result) {
                return handleResult(token, result);
            }
        });
        if (loadModel) whisper.loadModel();
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
        } else if (Recorder.MSG_RECORDING.equals(message)) {
            tvStatus.setText(R.string.entrypoint_recording);
            processingBar.setIndeterminate(true);
        } else if (Recorder.MSG_RECORDING_DONE.equals(message)) {
            HapticFeedback.vibrate(this);
            recording = false;
            processing = true;
            updateRecordingScreen();
            startProcessing(token, sessionTranslateRequested ? ACTION_TRANSLATE : ACTION_TRANSCRIBE);
        } else {
            failCurrentSession(token, getString(R.string.error_no_input));
        }
    }

    private void stopRecordingForTranscription() {
        if (!recording || mRecorder == null || !mRecorder.isInProgress()) {
            resetRecordButton();
            return;
        }
        recording = false;
        processing = true;
        tvStatus.setText(R.string.entrypoint_transcribing);
        processingBar.setProgress(0);
        processingBar.setIndeterminate(true);
        setControlsForState();
        Recorder stopping = mRecorder;
        try {
            entrypointTasks.execute(stopping::stop);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Teardown is already releasing this recorder and suppressing callbacks.
        }
        btnRecord.setBackgroundResource(R.drawable.rounded_button_background);
    }

    private void startProcessing(long token, Recognizer.Action action) {
        if (!isCurrentSession(token) || mWhisper == null) return;
        recording = false;
        processing = true;
        processingBar.setProgress(0);
        processingBar.setIndeterminate(true);
        tvStatus.setText(R.string.entrypoint_transcribing);
        setControlsForState();
        mWhisper.setAction(action);
        mWhisper.setLanguage(langCode);
        submitted = true;
        mWhisper.start(mRecorder == null ? null : mRecorder.takeRecording());
    }

    private boolean handleResult(long token, WhisperResult whisperResult) {
        if (!isCurrentSession(token)) return false;
        String rawText = whisperResult.getResult();
        String text = rawText == null ? "" : rawText.trim();
        if (text.isEmpty()) {
            failCurrentSession(token, getString(R.string.error_no_input));
            return false;
        }

        long timeTaken = System.currentTimeMillis() - startTime;
        boolean isTranscription = whisperResult.getTask() == ACTION_TRANSCRIBE;
        // "auto" is a request mode, not a language; show a language only when one was reported.
        String languageCode = CloudClient.normalizeDetectedLanguage(whisperResult.getLanguage());
        String status = getString(R.string.processing_done) + timeTaken + "\u2009ms" + "\n";
        if (!languageCode.isEmpty()) {
            status += getString(R.string.language) + " "
                    + new Locale(languageCode).getDisplayLanguage() + " ";
        }
        tvStatus.setText(status
                + (isTranscription ? getString(R.string.mode_transcription) : getString(R.string.mode_translation)));

        if ("zh".equals(languageCode) && isTranscription) {
            layoutModeChinese.setVisibility(View.VISIBLE);
            boolean simpleChinese = sp.getBoolean("simpleChinese", false);
            text = simpleChinese ? ZhConverterUtil.toSimple(text) : ZhConverterUtil.toTraditional(text);
        } else {
            layoutModeChinese.setVisibility(View.GONE);
        }

        if (!append.isChecked()) tvResult.setText("");
        tvResult.append(text);
        if (!TextUtils.isEmpty(whisperResult.getWarning())) {
            tvStatus.append("\n" + whisperResult.getWarning());
            Toast.makeText(this, whisperResult.getWarning(), Toast.LENGTH_LONG).show();
        }
        if (modeTTS.isChecked() && tts != null) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
        }
        completeCurrentSession(token);
        return true;
    }

    private boolean isCurrentSession(long token) {
        return token == sessionGeneration && sessionActive && !isFinishing() && !isDestroyed();
    }

    private void completeCurrentSession(long token) {
        if (!isCurrentSession(token)) return;
        sessionActive = false;
        recording = false;
        processing = false;
        submitted = false;
        processingBar.setIndeterminate(false);
        processingBar.setProgress(0);
        setControlsForState();
    }

    private void failCurrentSession(long token, String safeMessage) {
        if (token != sessionGeneration || !sessionActive) return;
        sessionActive = false;
        recording = false;
        processing = false;
        submitted = false;
        validating = false;
        updateRecordingScreen();
        processingBar.setIndeterminate(false);
        processingBar.setProgress(0);
        releaseRecorder();
        deinitModel();
        if (!isFinishing() && !isDestroyed()) {
            tvStatus.setText(safeMessage);
            setControlsForState();
        }
    }

    private void cancelCurrentSession(Integer statusString) {
        if (submitted && statusString != null && statusString == R.string.entrypoint_cancelled) {
            statusString = R.string.entrypoint_cancelled_saved;
        }
        submitted = false;
        sessionGeneration++;
        sessionActive = false;
        recording = false;
        processing = false;
        validating = false;
        updateRecordingScreen();
        processingBar.setIndeterminate(false);
        processingBar.setProgress(0);
        releaseRecorder();
        deinitModel();
        if (statusString != null && !isFinishing() && !isDestroyed()) {
            tvStatus.setText(statusString);
        }
        if (!isFinishing() && !isDestroyed()) setControlsForState();
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
        btnRecord.setBackgroundResource(R.drawable.rounded_button_background);
    }

    private void setControlsForState() {
        updateRecordingScreen();
        if (btnRecord == null) return;
        boolean busy = processing || recording || validating;
        // Keep the touch target enabled through validation so its long-hold release can cancel
        // the pending start before the worker opens the microphone.
        btnRecord.setEnabled(!processing);
        if (btnSavedDictations != null) btnSavedDictations.setEnabled(!busy);
        btnCancelProcessing.setVisibility(busy ? View.VISIBLE : View.GONE);
        btnCancelProcessing.setEnabled(busy);
        btnRecord.setBackgroundResource(recording
                ? R.drawable.rounded_button_background_pressed : R.drawable.rounded_button_background);
        btnRecord.setContentDescription(recording ? getString(R.string.entrypoint_stop_recording)
                : getString(R.string.record_button));
    }

    private void updateRecordingScreen() {
        if (recording || processing || validating) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void showSavedDictations() {
        if (recording || processing || validating) return;
        btnSavedDictations.setEnabled(false);
        recoveryFiles.execute(() -> {
            try {
                List<RecoveryStore.Entry> entries = new RecoveryStore(this).list();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    setControlsForState();
                    if (entries.isEmpty()) {
                        new AlertDialog.Builder(this).setTitle(R.string.recovery_title)
                                .setMessage(R.string.recovery_empty).setPositiveButton(android.R.string.ok, null).show();
                        return;
                    }
                    String[] labels = new String[entries.size()];
                    long totalBytes = 0L;
                    for (int i = 0; i < entries.size(); i++) {
                        RecoveryStore.Entry entry = entries.get(i);
                        totalBytes += Math.max(0L, entry.lengthBytes);
                        double seconds = entry.lengthBytes / 32_000.0;
                        String duration = seconds < 1.0
                                ? getString(R.string.recovery_duration_subsecond)
                                : getString(R.string.recovery_duration_seconds, seconds);
                        labels[i] = getString(R.string.recovery_entry, i + 1, duration);
                    }
                    String storage = getResources().getQuantityString(R.plurals.recovery_storage_summary,
                            entries.size(), entries.size(), totalBytes / (1024.0 * 1024.0));
                    new AlertDialog.Builder(this).setTitle(R.string.recovery_title)
                            .setMessage(storage)
                            .setItems(labels, (dialog, which) -> showSavedDictation(entries.get(which)))
                            .setNegativeButton(android.R.string.cancel, null).show();
                });
            } catch (Exception e) { showRecoveryStorageError(null); }
        });
    }

    private void showSavedDictation(RecoveryStore.Entry entry) {
        String preview = entry.transcript.isEmpty() ? getString(R.string.recovery_no_text) : entry.transcript;
        new AlertDialog.Builder(this).setTitle(R.string.recovery_title)
                .setMessage(getString(R.string.recovery_disclosure) + "\n\n" + preview)
                .setPositiveButton(R.string.recovery_retry, (dialog, which) -> resumeSavedDictation(entry.id))
                .setNeutralButton(R.string.recovery_copy, (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null && !entry.transcript.isEmpty()) clipboard.setPrimaryClip(
                            ClipData.newPlainText(getString(R.string.model_output), entry.transcript));
                })
                .setNegativeButton(R.string.recovery_discard, (dialog, which) -> {
                    try {
                        recoveryFiles.execute(() -> {
                            // RecoveryStore only reports fixed, app-authored messages (never dictated text).
                            try { new RecoveryStore(this).delete(entry.id); }
                            catch (IOException e) { showRecoveryStorageError(e.getMessage()); }
                            catch (RuntimeException e) { showRecoveryStorageError(null); }
                        });
                    } catch (java.util.concurrent.RejectedExecutionException ignored) {
                        // The Activity is being destroyed; the saved dictation is left unchanged.
                    }
                }).show();
    }

    private void resumeSavedDictation(String id) {
        if (recording || processing || validating) return;
        long token = ++sessionGeneration;
        sessionActive = true;
        processing = true;
        recording = false;
        submitted = true;
        startTime = System.currentTimeMillis();
        setControlsForState();
        ensureWhisper(token);
        mWhisper.retrySaved(id);
    }

    private void showRecoveryStorageError(String safeMessage) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            setControlsForState();
            if (TextUtils.isEmpty(safeMessage)) {
                Toast.makeText(this, R.string.recovery_storage_error, Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, safeMessage, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void deinitTTS() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO && !isFinishing()) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                tvStatus.setText(R.string.entrypoint_ready);
                boolean shouldStart = pendingStartAfterPermission;
                pendingStartAfterPermission = false;
                if (shouldStart) startRecording();
            } else {
                pendingStartAfterPermission = false;
                tvStatus.setText(R.string.entrypoint_permission_denied);
                Toast.makeText(this, R.string.entrypoint_permission_denied, Toast.LENGTH_LONG).show();
            }
        }
    }
}
