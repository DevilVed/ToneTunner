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
import com.devilved.tonetunner.cloud.ProviderConfig;
import com.devilved.tonetunner.utils.HapticFeedback;
import com.devilved.tonetunner.utils.LanguagePairAdapter;
import com.devilved.tonetunner.utils.ThemeUtils;
import com.devilved.tonetunner.voice_translation.neural_networks.voice.Recognizer;
import com.github.houbb.opencc4j.util.ZhConverterUtil;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

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
    private final RecordingGesture recordingGesture = new RecordingGesture();
    private boolean processing;
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
                        if (recording && recordingGesture.up(event.getEventTime()) == RecordingGesture.Action.STOP)
                            stopRecordingForTranscription();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        recordingGesture.cancel();
                        if (recording) cancelCurrentSession(R.string.entrypoint_cancelled);
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
        cancelCurrentSession(null);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        cancelCurrentSession(null);
        recoveryFiles.shutdownNow();
        deinitTTS();
        super.onDestroy();
    }

    private void refreshProviderSummary() {
        try {
            AiSettings settings = new AiSettings(this);
            String summary = settings.getSummary();
            if (TextUtils.isEmpty(summary)) summary = getString(R.string.entrypoint_provider_summary_unknown);
            if (hasMissingCloudKey(settings)) {
                summary = getString(R.string.entrypoint_cloud_key_missing);
            } else if (hasCloudWork(settings) && !settings.isCloudConsentGranted()) {
                summary = getString(R.string.entrypoint_cloud_consent_missing);
            }
            tvProviderSummary.setText(summary);
        } catch (RuntimeException e) {
            // Keep settings failures safe and direct the user to the settings screen.
            tvProviderSummary.setText(R.string.entrypoint_provider_summary_unknown);
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

    private boolean hasMissingCloudKey(AiSettings settings) {
        if (settings.isCloudTranscription()) {
            ProviderConfig transcription = settings.getTranscriptionConfig();
            if (transcription == null || TextUtils.isEmpty(transcription.apiKey)) return true;
        }
        if (settings.isEnhancementEnabled()) {
            ProviderConfig enhancement = settings.getEnhancementConfig();
            return enhancement == null || TextUtils.isEmpty(enhancement.apiKey);
        }
        return false;
    }

    private boolean startRecording() {
        if (recording || processing) return false;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.need_record_audio_permission, Toast.LENGTH_SHORT).show();
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
            resetRecordButton();
            return false;
        }

        try {
            AiSettings settings = new AiSettings(this);
            if (hasCloudWork(settings)) {
                if (!settings.isCloudConsentGranted()) {
                    tvStatus.setText(R.string.entrypoint_cloud_consent_missing);
                    Toast.makeText(this, R.string.entrypoint_cloud_consent_toast, Toast.LENGTH_LONG).show();
                    resetRecordButton();
                    return false;
                }
                if (hasMissingCloudKey(settings)) {
                    tvStatus.setText(R.string.entrypoint_cloud_key_missing);
                    Toast.makeText(this, R.string.entrypoint_cloud_key_missing, Toast.LENGTH_LONG).show();
                    resetRecordButton();
                    return false;
                }
            }
        } catch (RuntimeException e) {
            tvStatus.setText(R.string.entrypoint_settings_unavailable);
            resetRecordButton();
            return false;
        }

        long token = ++sessionGeneration;
        sessionActive = true;
        recording = true;
        processing = false;
        try {
            ensureWhisper(token);
            createRecorder(token);
            HapticFeedback.vibrate(this);
            mRecorder.start();
            tvStatus.setText(R.string.entrypoint_recording);
            processingBar.setProgress(0);
            processingBar.setIndeterminate(true);
            setControlsForState();
            return true;
        } catch (RuntimeException e) {
            failCurrentSession(token, getString(R.string.entrypoint_processing_error));
            return false;
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
                if (Whisper.MSG_PROCESSING.equals(message)) {
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
        releaseRecorder(false);
        Recorder recorder = new Recorder(this);
        mRecorder = recorder;
        recorder.setListener(message -> runOnUiThread(() -> onRecorderMessage(token, message)));
    }

    private void onRecorderMessage(long token, String message) {
        if (!isCurrentSession(token)) return;
        if (Recorder.MSG_RECORDING.equals(message)) {
            tvStatus.setText(R.string.entrypoint_recording);
            processingBar.setIndeterminate(true);
        } else if (Recorder.MSG_RECORDING_DONE.equals(message)) {
            HapticFeedback.vibrate(this);
            recording = false;
            processing = true;
            updateRecordingScreen();
            startProcessing(token, translate.isChecked() ? ACTION_TRANSLATE : ACTION_TRANSCRIBE);
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
        mRecorder.stop();
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
        tvStatus.setText(getString(R.string.processing_done) + timeTaken + "\u2009ms" + "\n"
                + getString(R.string.language) + " "
                + new Locale(whisperResult.getLanguage()).getDisplayLanguage() + " "
                + (isTranscription ? getString(R.string.mode_transcription) : getString(R.string.mode_translation)));

        if ("zh".equals(whisperResult.getLanguage()) && isTranscription) {
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
        processingBar.setIndeterminate(false);
        processingBar.setProgress(0);
        setControlsForState();
    }

    private void failCurrentSession(long token, String safeMessage) {
        if (token != sessionGeneration || !sessionActive) return;
        sessionActive = false;
        recording = false;
        processing = false;
        updateRecordingScreen();
        processingBar.setIndeterminate(false);
        processingBar.setProgress(0);
        releaseRecorder(true);
        deinitModel();
        if (!isFinishing() && !isDestroyed()) {
            tvStatus.setText(safeMessage);
            setControlsForState();
        }
    }

    private void cancelCurrentSession(Integer statusString) {
        sessionGeneration++;
        sessionActive = false;
        recording = false;
        processing = false;
        updateRecordingScreen();
        processingBar.setIndeterminate(false);
        processingBar.setProgress(0);
        releaseRecorder(true);
        deinitModel();
        if (statusString != null && !isFinishing() && !isDestroyed()) {
            tvStatus.setText(statusString);
        }
        if (!isFinishing() && !isDestroyed()) setControlsForState();
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
        btnRecord.setBackgroundResource(R.drawable.rounded_button_background);
    }

    private void setControlsForState() {
        updateRecordingScreen();
        if (btnRecord == null) return;
        btnRecord.setEnabled(!processing);
        if (btnSavedDictations != null) btnSavedDictations.setEnabled(!processing && !recording);
        btnCancelProcessing.setVisibility(processing || recording ? View.VISIBLE : View.GONE);
        btnCancelProcessing.setEnabled(processing || recording);
        btnRecord.setBackgroundResource(recording
                ? R.drawable.rounded_button_background_pressed : R.drawable.rounded_button_background);
        btnRecord.setContentDescription(recording ? getString(R.string.entrypoint_stop_recording)
                : getString(R.string.record_button));
    }

    private void updateRecordingScreen() {
        if (recording || processing) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void showSavedDictations() {
        if (recording || processing) return;
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
                    for (int i = 0; i < entries.size(); i++) labels[i] = getString(
                            R.string.recovery_entry, i + 1, entries.get(i).lengthBytes / 32_000L);
                    new AlertDialog.Builder(this).setTitle(R.string.recovery_title)
                            .setItems(labels, (dialog, which) -> showSavedDictation(entries.get(which)))
                            .setNegativeButton(android.R.string.cancel, null).show();
                });
            } catch (Exception e) { showRecoveryStorageError(); }
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
                .setNegativeButton(R.string.recovery_discard, (dialog, which) -> recoveryFiles.execute(() -> {
                    try { new RecoveryStore(this).delete(entry.id); }
                    catch (Exception e) { showRecoveryStorageError(); }
                })).show();
    }

    private void resumeSavedDictation(String id) {
        if (recording || processing) return;
        long token = ++sessionGeneration;
        sessionActive = true;
        processing = true;
        recording = false;
        startTime = System.currentTimeMillis();
        setControlsForState();
        ensureWhisper(token);
        mWhisper.retrySaved(id);
    }

    private void showRecoveryStorageError() {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            setControlsForState();
            Toast.makeText(this, R.string.recovery_storage_error, Toast.LENGTH_LONG).show();
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
            } else {
                tvStatus.setText(R.string.entrypoint_permission_denied);
                Toast.makeText(this, R.string.entrypoint_permission_denied, Toast.LENGTH_LONG).show();
            }
        }
    }
}
