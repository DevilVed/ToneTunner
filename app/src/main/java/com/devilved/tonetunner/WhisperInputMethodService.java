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
    private Context mContext;
    private static boolean translate = false;
    private boolean modeAuto = false;
    private LinearLayout layoutButtons;
    private long editorGeneration;
    private long requestEditorGeneration;
    private boolean inputActive;
    private boolean acceptRecording;
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
        super.onDestroy();
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        cancelDictation();
        ++editorGeneration;
        inputActive = attribute != null;
    }

    @Override
    public void onStartInputView(EditorInfo attribute, boolean restarting) {
        super.onStartInputView(attribute, restarting);
        inputActive = attribute != null;
        if (tvStatus != null) {
            showStatus(new AiSettings(this).getSummary());
            layoutButtons.setVisibility(View.VISIBLE);
        }
        if (modeAuto && !restarting) startRecording();
    }

    @Override
    public void onFinishInput() {
        inputActive = false;
        ++editorGeneration;
        cancelDictation();
        super.onFinishInput();
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        inputActive = false;
        ++editorGeneration;
        cancelDictation();
        super.onFinishInputView(finishingInput);
    }

    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd,
            int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd);
        if ((acceptRecording || (mWhisper != null && mWhisper.isInProgress()))
                && (oldSelStart != newSelStart || oldSelEnd != newSelEnd)) {
            cancelDictation();
            showStatus(getString(R.string.dictation_cancelled));
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public View onCreateInputView() {  //runs before onStartInputView
        sp = PreferenceManager.getDefaultSharedPreferences(this);
        View view = getLayoutInflater().inflate(R.layout.voice_service, null);
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
            if (acceptRecording && mRecorder != null) mRecorder.stop();
            else startRecording();
        });
        btnRecord.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                if (recordingGesture.down(acceptRecording, event.getEventTime()) == RecordingGesture.Action.STOP) {
                    if (mRecorder != null) mRecorder.stop();
                } else startRecording();
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                if (recordingGesture.up(event.getEventTime()) == RecordingGesture.Action.STOP
                        && mRecorder != null && mRecorder.isInProgress()) mRecorder.stop();
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

    private void startRecording() {
        if (!inputActive || !checkRecordPermission()) return;
        if (acceptRecording || (mWhisper != null && mWhisper.isInProgress())) {
            showStatus(getString(R.string.please_wait));
            return;
        }
        try {
            AiSettings settings = new AiSettings(this);
            boolean cloud = settings.isCloudTranscription() || settings.isEnhancementEnabled();
            if (!eligibleEditor(cloud)) {
                showStatus(getString(R.string.dictation_sensitive_field));
                return;
            }
            if (cloud && !settings.isCloudConsentGranted()) {
                showStatus(getString(R.string.dictation_setup_required));
                return;
            }
            if ((settings.isCloudTranscription() && settings.getTranscriptionConfig().apiKey.isEmpty())
                    || (settings.isEnhancementEnabled() && settings.getEnhancementConfig().apiKey.isEmpty())) {
                showStatus(getString(R.string.dictation_setup_required));
                return;
            }
        } catch (Exception e) {
            showStatus(getString(R.string.dictation_setup_required));
            return;
        }
        if (mWhisper == null) initModel();
        if (mRecorder != null) mRecorder.release();
        Recorder recorder = new Recorder(this);
        mRecorder = recorder;
        long generation = editorGeneration;
        requestEditorGeneration = generation;
        acceptRecording = true;
        recorder.setListener(message -> handler.post(() -> {
            if (mRecorder != recorder || !acceptRecording || !inputActive || generation != editorGeneration) return;
            if (Recorder.MSG_RECORDING.equals(message)) {
                btnRecord.setBackgroundResource(R.drawable.rounded_button_background_pressed);
                showStatus(getString(R.string.dictation_recording));
            } else if (Recorder.MSG_RECORDING_DONE.equals(message)) {
                acceptRecording = false;
                btnRecord.setBackgroundResource(R.drawable.rounded_button_background);
                startTranscription();
            } else {
                acceptRecording = false;
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

    private boolean eligibleEditor(boolean sendsToCloud) {
        EditorInfo editor = getCurrentInputEditorInfo();
        return inputActive && editor != null && InputPolicy.allowsDictation(editor.inputType, editor.imeOptions, sendsToCloud);
    }

    private void showStatus(String message) {
        if (tvStatus == null) return;
        tvStatus.setText(message);
        tvStatus.setVisibility(View.VISIBLE);
    }

    private void cancelDictation() {
        handler.removeCallbacks(repeatDelete);
        recordingGesture.cancel();
        acceptRecording = false;
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
                if (Whisper.MSG_ENHANCING.equals(message)) showStatus(getString(R.string.dictation_enhancing));
                else if (Whisper.MSG_PROCESSING.equals(message)) showStatus(getString(R.string.dictation_transcribing));
            }

            @Override
            public void onError(String message) {
                if (mWhisper != requestWhisper || requestEditorGeneration != editorGeneration || !inputActive) return;
                processingBar.setIndeterminate(false);
                processingBar.setProgress(0);
                showStatus(message);
            }

            @Override
            public void onResultReceived(WhisperResult whisperResult) {
                if (mWhisper != requestWhisper || requestEditorGeneration != editorGeneration || !inputActive) return;
                AiSettings settings = new AiSettings(WhisperInputMethodService.this);
                if (!eligibleEditor(settings.isCloudTranscription() || settings.isEnhancementEnabled())) return;
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
                if (modeAuto && committed) {
                    long deliveredGeneration = editorGeneration;
                    handler.postDelayed(() -> {
                        if (inputActive && deliveredGeneration == editorGeneration) switchToPreviousInputMethod();
                    }, 100);
                }
            }

        });
    }

    private void startTranscription() {
        handler.post(() -> processingBar.setProgress(0));
        handler.post(() -> processingBar.setIndeterminate(true));
        if (mWhisper!=null){
            if (translate) mWhisper.setAction(ACTION_TRANSLATE);
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

}
