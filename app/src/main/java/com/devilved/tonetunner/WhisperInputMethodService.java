package com.devilved.tonetunner;


import static com.devilved.tonetunner.voice_translation.neural_networks.voice.Recognizer.ACTION_TRANSCRIBE;
import static com.devilved.tonetunner.voice_translation.neural_networks.voice.Recognizer.ACTION_TRANSLATE;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.inputmethodservice.InputMethodService;

import android.os.Handler;
import android.os.Looper;
import androidx.preference.PreferenceManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.github.houbb.opencc4j.util.ZhConverterUtil;
import com.devilved.tonetunner.asr.Recorder;
import com.devilved.tonetunner.asr.RecordingGesture;
import com.devilved.tonetunner.asr.InputPolicy;
import com.devilved.tonetunner.cloud.AiSettings;
import com.devilved.tonetunner.asr.Whisper;
import com.devilved.tonetunner.asr.WhisperResult;
import com.devilved.tonetunner.utils.HapticFeedback;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public class WhisperInputMethodService extends InputMethodService {
    private ImageButton btnRecord;
    private ImageButton btnKeyboard;
    private ImageButton btnTranslate;
    private ImageButton btnModeAuto;
    private ImageButton btnEnter;
    private ImageButton btnDel;
    private TextView tvStatus;
    private Recorder mRecorder = null;
    private Whisper mWhisper = null;
    private ProgressBar processingBar = null;
    private SharedPreferences sp = null;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService entrypointTasks = Executors.newSingleThreadExecutor();
    private Context mContext;
    private static boolean translate = false;
    private boolean modeAuto = false;
    private LinearLayout layoutButtons;
    private View inputRootView;
    private long editorGeneration;
    private long requestEditorGeneration;
    private long validationGeneration;
    private long providerSummaryGeneration;
    private EditorIdentity activeEditorIdentity;
    private InputConnection activeInputConnection;
    private boolean inputActive;
    private boolean acceptRecording;
    private boolean processing;
    private boolean validating;
    private final RecordingGesture recordingGesture = new RecordingGesture();
    private final Runnable repeatDelete = new Runnable() {
        @Override public void run() {
            if (!inputActive) return;
            sendKey(KeyEvent.KEYCODE_DEL);
            handler.postDelayed(this, 100);
        }
    };

    @Override
    public void onCreate() {
        mContext = this;
        super.onCreate();
    }

    @Override
    public void onDestroy() {
        inputActive = false;
        ++editorGeneration;
        cancelDictation();
        ++providerSummaryGeneration;
        entrypointTasks.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        InputConnection connection = getCurrentInputConnection();
        EditorIdentity nextIdentity = EditorIdentity.from(attribute);
        boolean sameRestart = restarting && activeEditorIdentity != null
                && activeEditorIdentity.equals(nextIdentity)
                && activeInputConnection != null && activeInputConnection == connection;
        if (!sameRestart) {
            cancelDictation();
            ++editorGeneration;
        }
        activeEditorIdentity = nextIdentity;
        activeInputConnection = connection;
        inputActive = attribute != null;
    }

    @Override
    public void onStartInputView(EditorInfo attribute, boolean restarting) {
        super.onStartInputView(attribute, restarting);
        inputActive = attribute != null;
        if (tvStatus != null) {
            refreshAiSummary();
            layoutButtons.setVisibility(View.VISIBLE);
        }
        if (modeAuto && !restarting) startRecording();
    }

    @Override
    public void onFinishInput() {
        inputActive = false;
        ++editorGeneration;
        activeEditorIdentity = null;
        activeInputConnection = null;
        cancelDictation();
        super.onFinishInput();
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        inputActive = false;
        ++editorGeneration;
        activeEditorIdentity = null;
        activeInputConnection = null;
        cancelDictation();
        super.onFinishInputView(finishingInput);
    }

    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd,
            int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd);
        // Host apps also move selection for formatting/composition. The current editor owns the
        // operation; selection notifications alone must not destroy its recording or pending text.
        // Editor replacement/finish and explicit cancellation still invalidate the listener.

    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public View onCreateInputView() {  //runs before onStartInputView
        sp = PreferenceManager.getDefaultSharedPreferences(this);
        View view = getLayoutInflater().inflate(R.layout.voice_service, null);
        if (inputRootView != null && inputRootView != view) {
            inputRootView.setKeepScreenOn(false);
        }
        inputRootView = view;
        updateInputViewKeepScreenOn();
        btnRecord = view.findViewById(R.id.btnRecord);
        btnKeyboard = view.findViewById(R.id.btnKeyboard);
        btnTranslate = view.findViewById(R.id.btnTranslate);
        btnModeAuto = view.findViewById(R.id.btnModeAuto);
        btnEnter = view.findViewById(R.id.btnEnter);
        btnDel = view.findViewById(R.id.btnDel);
        processingBar = view.findViewById(R.id.processing_bar);
        tvStatus = view.findViewById(R.id.tv_status);
        btnTranslate.setImageResource(translate ? R.drawable.ic_english_on_36dp : R.drawable.ic_english_off_36dp);
        modeAuto = sp.getBoolean("imeModeAuto",false);
        btnModeAuto.setImageResource(modeAuto ? R.drawable.ic_auto_on_36dp : R.drawable.ic_auto_off_36dp);
        layoutButtons = view.findViewById(R.id.layout_buttons);
        checkRecordPermission();

        view.findViewById(R.id.btnAiSettings).setOnClickListener(v -> {
            cancelDictation();
            Intent settings = new Intent(this, AiSettingsActivity.class);
            settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(settings);
        });
        view.findViewById(R.id.btnCancelDictation).setOnClickListener(v -> {
            cancelDictation();
            showStatus(getString(R.string.dictation_cancelled));
        });

        btnDel.setOnClickListener(v -> sendKey(KeyEvent.KEYCODE_DEL));
        btnDel.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                handler.removeCallbacks(repeatDelete);
                sendKey(KeyEvent.KEYCODE_DEL);
                handler.postDelayed(repeatDelete, 500);
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                handler.removeCallbacks(repeatDelete);
            }
            return true;
        });

        btnRecord.setOnClickListener(v -> {
            if (acceptRecording && mRecorder != null) requestStopRecording();
            else startRecording();
        });
        btnRecord.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                if (recordingGesture.down(acceptRecording, event.getEventTime()) == RecordingGesture.Action.STOP) {
                    if (mRecorder != null) requestStopRecording();
                } else startRecording();
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                if (recordingGesture.up(event.getEventTime()) == RecordingGesture.Action.STOP
                        && mRecorder != null && mRecorder.isInProgress()) requestStopRecording();
            } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                cancelDictation();
            }
            return true;
        });

        btnKeyboard.setOnClickListener(v -> {
            cancelDictation();
            switchToPreviousInputMethod();
        });

        btnTranslate.setOnClickListener(v -> {
            translate = !translate;
            btnTranslate.setImageResource(translate ? R.drawable.ic_english_on_36dp : R.drawable.ic_english_off_36dp);
        });

        btnEnter.setOnClickListener(v -> {
            sendKey(KeyEvent.KEYCODE_ENTER);
        });

        btnModeAuto.setOnClickListener(v -> {
            modeAuto = !modeAuto;
            SharedPreferences.Editor editor = sp.edit();
            editor.putBoolean("imeModeAuto", modeAuto);
            editor.apply();
            layoutButtons.setVisibility(modeAuto ? View.GONE : View.VISIBLE);
            btnModeAuto.setImageResource(modeAuto ? R.drawable.ic_auto_on_36dp : R.drawable.ic_auto_off_36dp);
            switchToPreviousInputMethod();
        });
        return view;
    }

    private void sendKey(int keyCode) {
        if (!inputActive) return;
        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection != null) {
            inputConnection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
            inputConnection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
        }
    }

    private void refreshAiSummary() {
        long generation = ++providerSummaryGeneration;
        try {
            entrypointTasks.execute(() -> {
                String summary;
                try {
                    summary = new AiSettings(this).getSummary();
                } catch (RuntimeException e) {
                    summary = getString(R.string.ai_key_unavailable_summary);
                }
                String result = summary;
                handler.post(() -> {
                    if (generation == providerSummaryGeneration && inputActive && tvStatus != null
                            && tvStatus.getVisibility() == View.GONE
                            && !acceptRecording && !processing && !validating) {
                        showStatus(result);
                    }
                });
            });
        } catch (RejectedExecutionException ignored) {
            // The service is already shutting down.
        }
    }

    private void startRecording() {
        if (!inputActive || !checkRecordPermission()) return;
        if (acceptRecording || processing || validating || (mWhisper != null && mWhisper.isInProgress())) {
            showStatus(getString(R.string.please_wait));
            return;
        }
        final long token = ++validationGeneration;
        final long generation = editorGeneration;
        final EditorIdentity editorIdentity = activeEditorIdentity;
        final InputConnection inputConnection = activeInputConnection;
        final boolean translateRequested = translate;
        final AiSettings settings;
        final boolean cloud;
        try {
            settings = new AiSettings(this);
            cloud = settings.isCloudTranscription() || settings.isEnhancementEnabled();
            if (translateRequested && !settings.isTranslationSupported()) {
                showStatus(getString(R.string.dictation_translation_unsupported));
                return;
            }
            if (!eligibleEditor(cloud)) {
                showStatus(getString(R.string.dictation_sensitive_field));
                return;
            }
            if (cloud && !settings.isCloudConsentGranted()) {
                showStatus(getString(R.string.dictation_setup_required));
                return;
            }
        } catch (Exception e) {
            showStatus(getString(R.string.dictation_setup_required));
            return;
        }

        if (cloud) {
            validating = true;
            updateInputViewKeepScreenOn();
            showStatus(getString(R.string.dictation_validating));
            try {
                entrypointTasks.execute(() -> {
                    int error;
                    try { error = settings.validateCloudCredentials(); }
                    catch (RuntimeException e) { error = R.string.entrypoint_settings_unavailable; }
                    final int validationError = error;
                    handler.post(() -> {
                        if (!isCurrentStart(token, generation, editorIdentity, inputConnection)) return;
                        validating = false;
                        updateInputViewKeepScreenOn();
                        if (validationError != 0) {
                            showStatus(getString(validationError));
                            return;
                        }
                        beginCapture(token, generation, editorIdentity, inputConnection, translateRequested);
                    });
                });
            } catch (RejectedExecutionException e) {
                validating = false;
                updateInputViewKeepScreenOn();
                showStatus(getString(R.string.dictation_setup_required));
            }
            return;
        }
        beginCapture(token, generation, editorIdentity, inputConnection, translateRequested);
    }

    private boolean isCurrentStart(long token, long generation, EditorIdentity editorIdentity,
                                   InputConnection inputConnection) {
        return token == validationGeneration && generation == editorGeneration && inputActive
                && java.util.Objects.equals(activeEditorIdentity, editorIdentity)
                && activeInputConnection == inputConnection
                && getCurrentInputConnection() == inputConnection;
    }

    private void beginCapture(long token, long generation, EditorIdentity editorIdentity,
                              InputConnection inputConnection, boolean translateRequested) {
        if (!isCurrentStart(token, generation, editorIdentity, inputConnection)) return;
        if (mWhisper == null) initModel();
        if (mRecorder != null) mRecorder.release();
        Recorder recorder = new Recorder(this);
        mRecorder = recorder;
        requestEditorGeneration = generation;
        processing = false;
        acceptRecording = true;
        updateInputViewKeepScreenOn();
        recorder.setListener(message -> handler.post(() -> {
            if (mRecorder != recorder || token != validationGeneration || !inputActive
                    || generation != editorGeneration || !java.util.Objects.equals(activeEditorIdentity, editorIdentity)
                    || activeInputConnection != inputConnection) return;
            if (Recorder.MSG_SPEECH_STARTED.equals(message)) {
                return;
            } else if (Recorder.MSG_RECORDING.equals(message)) {
                if (!acceptRecording) return;
                btnRecord.setBackgroundResource(R.drawable.rounded_button_background_pressed);
                showStatus(getString(R.string.dictation_recording));
            } else if (Recorder.MSG_RECORDING_DONE.equals(message)) {
                acceptRecording = false;
                processing = true;
                updateInputViewKeepScreenOn();
                btnRecord.setBackgroundResource(R.drawable.rounded_button_background);
                mWhisper.setAction(translateRequested ? ACTION_TRANSLATE : ACTION_TRANSCRIBE);
                startTranscription(translateRequested);
            } else {
                acceptRecording = false;
                processing = false;
                updateInputViewKeepScreenOn();
                processingBar.setProgress(0);
                btnRecord.setBackgroundResource(R.drawable.rounded_button_background);
                showStatus(getString(R.string.error_no_input));
            }
        }));
        if (modeAuto) recorder.initVad();
        HapticFeedback.vibrate(this);
        recorder.start();
        processingBar.setIndeterminate(true);
    }

    private void requestStopRecording() {
        Recorder recorder = mRecorder;
        if (recorder == null || !recorder.isInProgress()) return;
        acceptRecording = false;
        processing = true;
        updateInputViewKeepScreenOn();
        if (btnRecord != null) btnRecord.setBackgroundResource(R.drawable.rounded_button_background);
        try {
            entrypointTasks.execute(recorder::stop);
        } catch (RejectedExecutionException ignored) {
            // Service teardown releases the recorder and suppresses any late callback.
        }
    }

    private boolean eligibleEditor(boolean sendsToCloud) {
        EditorInfo editor = getCurrentInputEditorInfo();
        return inputActive && editor != null && InputPolicy.allowsDictation(editor.inputType, editor.imeOptions, sendsToCloud);
    }

    private void showStatus(String message) {
        if (tvStatus == null) return;
        tvStatus.setText(message);
        tvStatus.setVisibility(View.VISIBLE);
    }

    private void updateInputViewKeepScreenOn() {
        if (inputRootView != null) inputRootView.setKeepScreenOn(acceptRecording || processing || validating);
    }

    private void cancelDictation() {
        handler.removeCallbacks(repeatDelete);
        recordingGesture.cancel();
        ++validationGeneration;
        acceptRecording = false;
        processing = false;
        validating = false;
        updateInputViewKeepScreenOn();
        if (mRecorder != null) { mRecorder.release(); mRecorder = null; }
        if (mWhisper != null) { mWhisper.unloadModel(); mWhisper = null; }
        if (processingBar != null) { processingBar.setIndeterminate(false); processingBar.setProgress(0); }
        if (btnRecord != null) btnRecord.setBackgroundResource(R.drawable.rounded_button_background);
    }

    // Model initialization
    private void initModel() {

        mWhisper = new Whisper(this);
        Whisper requestWhisper = mWhisper;
        mWhisper.loadModel();
        mWhisper.setListener(new Whisper.WhisperListener() {
            @Override
            public void onUpdateReceived(String message) {
                if (mWhisper != requestWhisper || requestEditorGeneration != editorGeneration || !inputActive) return;
                if (Whisper.MSG_PREPARING.equals(message)) {
                    processing = true;
                    updateInputViewKeepScreenOn();
                    showStatus(getString(R.string.dictation_preparing));
                } else if (Whisper.MSG_ENHANCING.equals(message)) {
                    processing = true;
                    updateInputViewKeepScreenOn();
                    showStatus(getString(R.string.dictation_enhancing));
                } else if (Whisper.MSG_PROCESSING.equals(message)) {
                    processing = true;
                    updateInputViewKeepScreenOn();
                    showStatus(getString(R.string.dictation_transcribing));
                }
            }

            @Override
            public void onError(String message) {
                if (mWhisper != requestWhisper || requestEditorGeneration != editorGeneration || !inputActive) return;
                processing = false;
                updateInputViewKeepScreenOn();
                processingBar.setIndeterminate(false);
                processingBar.setProgress(0);
                showStatus(message);
            }

            @Override
            public void onResultReceived(WhisperResult whisperResult) {
                onResultDelivery(whisperResult);
            }

            @Override
            public boolean onResultDelivery(WhisperResult whisperResult) {
                if (mWhisper != requestWhisper || requestEditorGeneration != editorGeneration || !inputActive
                        || activeInputConnection != getCurrentInputConnection()) return false;
                processing = false;
                updateInputViewKeepScreenOn();
                AiSettings settings = new AiSettings(WhisperInputMethodService.this);
                if (!eligibleEditor(settings.isCloudTranscription() || settings.isEnhancementEnabled())) return false;
                processingBar.setIndeterminate(false);
                processingBar.setProgress(0);
                String result = whisperResult.getResult();
                if ("zh".equals(whisperResult.getLanguage())) {
                    result = sp.getBoolean("simpleChinese", false)
                            ? ZhConverterUtil.toSimple(result) : ZhConverterUtil.toTraditional(result);
                }
                InputConnection connection = getCurrentInputConnection();
                boolean committed = connection != null && !result.trim().isEmpty()
                        && connection.commitText(result.trim() + " ", 1);
                showStatus(getString(committed ? R.string.dictation_inserted : R.string.dictation_editor_closed));
                if (!whisperResult.getWarning().isEmpty()) {
                    showStatus(whisperResult.getWarning());
                    android.widget.Toast.makeText(WhisperInputMethodService.this,
                            whisperResult.getWarning(), android.widget.Toast.LENGTH_LONG).show();
                }
                if (modeAuto && committed) {
                    long deliveredGeneration = editorGeneration;
                    handler.postDelayed(() -> {
                        if (inputActive && deliveredGeneration == editorGeneration) switchToPreviousInputMethod();
                    }, 100);
                }
                return committed;
            }

        });
    }

    private void startTranscription(boolean translateRequested) {
        handler.post(() -> processingBar.setProgress(0));
        handler.post(() -> processingBar.setIndeterminate(true));
        if (mWhisper!=null){
            if (translateRequested) mWhisper.setAction(ACTION_TRANSLATE);
            else mWhisper.setAction(ACTION_TRANSCRIBE);

            String langCode = sp.getString("language", "auto");
            Log.d("WhisperIME","default langCode " + langCode);
            mWhisper.setLanguage(langCode);
            mWhisper.start(mRecorder == null ? null : mRecorder.takeRecording());
        }
    }

    private boolean checkRecordPermission() {
        int permission = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO);
        if (permission != PackageManager.PERMISSION_GRANTED) {
            tvStatus.setVisibility(View.VISIBLE);
            tvStatus.setText(getString(R.string.need_record_audio_permission));
        }
        return (permission == PackageManager.PERMISSION_GRANTED);
    }

    private static final class EditorIdentity {
        final String packageName;
        final int fieldId;
        final int inputType;
        final int imeOptions;

        private EditorIdentity(String packageName, int fieldId, int inputType, int imeOptions) {
            this.packageName = packageName;
            this.fieldId = fieldId;
            this.inputType = inputType;
            this.imeOptions = imeOptions;
        }

        static EditorIdentity from(EditorInfo editor) {
            return editor == null ? null : new EditorIdentity(editor.packageName, editor.fieldId,
                    editor.inputType, editor.imeOptions);
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof EditorIdentity)) return false;
            EditorIdentity that = (EditorIdentity) other;
            return fieldId == that.fieldId && inputType == that.inputType && imeOptions == that.imeOptions
                    && java.util.Objects.equals(packageName, that.packageName);
        }

        @Override public int hashCode() {
            return java.util.Objects.hash(packageName, fieldId, inputType, imeOptions);
        }
    }

}
