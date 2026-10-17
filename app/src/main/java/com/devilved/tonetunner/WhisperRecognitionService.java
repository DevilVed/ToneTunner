package com.devilved.tonetunner;

import static android.speech.SpeechRecognizer.ERROR_CLIENT;
import static android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS;

import static com.devilved.tonetunner.voice_translation.neural_networks.voice.Recognizer.ACTION_TRANSCRIBE;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.github.houbb.opencc4j.util.ZhConverterUtil;
import com.devilved.tonetunner.asr.Recorder;
import com.devilved.tonetunner.asr.Whisper;
import com.devilved.tonetunner.asr.WhisperResult;
import com.devilved.tonetunner.utils.HapticFeedback;
import java.util.ArrayList;
import java.util.Locale;

public class WhisperRecognitionService extends RecognitionService {
    private static final String TAG = "WhisperRecognitionService";
    // volatile: model errors arrive on the model-loading thread while a request is starting on the main thread
    private volatile Recorder mRecorder = null;
    private volatile Whisper mWhisper = null;
    private volatile boolean recognitionCancelled = false;
    private SharedPreferences sp = null;

    @Override
    protected void onStartListening(Intent recognizerIntent, Callback callback) {
        String targetLang = recognizerIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE);
        sp = PreferenceManager.getDefaultSharedPreferences(this);
        String langCode = sp.getString("recognitionServiceLanguage", "auto");
        Log.d(TAG,"default langCode " + langCode);

        if (targetLang != null) {
            Log.d(TAG,"StartListening in " + targetLang);
            langCode = targetLang.split("[-_]")[0].toLowerCase(Locale.ROOT);   //support both de_DE and de-DE
        } else {
            Log.d(TAG,"StartListening, no language specified");
        }

        if (!checkRecordPermission(callback)) return;

        initModel(callback, langCode);

        if (mRecorder != null) mRecorder.release();  // each request creates a new recorder
        mRecorder = new Recorder(this);
        mRecorder.setListener(message -> {
            if (message.equals(Recorder.MSG_RECORDING)){
                try {
                    callback.rmsChanged(10);
                } catch (RemoteException e) {
                    Log.w(TAG, "Recognition client is no longer reachable", e);  // e.g. the calling app was closed; must not crash ToneTunner
                }
            } else if (message.equals(Recorder.MSG_RECORDING_DONE)) {
                HapticFeedback.vibrate(this);
                try {
                    callback.rmsChanged(-20.0f);
                } catch (RemoteException e) {
                    Log.w(TAG, "Recognition client is no longer reachable", e);  // e.g. the calling app was closed; must not crash ToneTunner
                }
                startTranscription();
            } else if (message.equals(Recorder.MSG_RECORDING_ERROR)) {
                try {
                    callback.error(ERROR_CLIENT);
                } catch (RemoteException e) {
                    Log.w(TAG, "Recognition client is no longer reachable", e);  // e.g. the calling app was closed; must not crash ToneTunner
                }
            }
        });

        Whisper whisper = mWhisper;
        if (whisper == null) return;  // the model failed to load meanwhile; onError() already reported it
        if (!whisper.isInProgress()) {
            HapticFeedback.vibrate(this);
            startRecording();
            try {
                callback.beginningOfSpeech();
            } catch (RemoteException e) {
                Log.w(TAG, "Recognition client is no longer reachable", e);  // e.g. the calling app was closed; must not crash ToneTunner
            }
        }


    }

    private void stopRecording() {
        Recorder recorder = mRecorder;
        if (recorder != null && recorder.isInProgress()) {
            recorder.stop();
        }
    }

    @Override
    protected void onCancel(Callback callback) {
        Log.d(TAG,"cancel");
        stopRecording();
        deinitModel();
        recognitionCancelled = true;
    }

    @Override
    protected void onStopListening(Callback callback) {
        Log.d(TAG,"StopListening");
        stopRecording();
    }

    // Model initialization
    private void initModel(Callback callback, String langCode) {

        Whisper whisper = new Whisper(this);  // local: a model error may clear mWhisper while this runs
        mWhisper = whisper;
        whisper.loadModel();
        whisper.setLanguage(langCode);
        Log.d(TAG, "Language token " + langCode);
        whisper.setListener(new Whisper.WhisperListener() {
            @Override
            public void onUpdateReceived(String message) { }

            @Override
            public void onError(String message) {
                Log.e(TAG, message);
                // Cancel and silence the recorder before dropping the model, so no later "recording done"
                // callback tries to transcribe with it
                recognitionCancelled = true;
                Recorder recorder = mRecorder;
                if (recorder != null) recorder.release();
                deinitModel();
                try {
                    callback.error(SpeechRecognizer.ERROR_SERVER);  // the recognition engine (model) failed
                } catch (RemoteException e) {
                    Log.w(TAG, "Recognition client is no longer reachable", e);
                }
            }

            @Override
            public void onResultReceived(WhisperResult whisperResult) {
                if (whisperResult.getResult().trim().isEmpty()) {
                    deinitModel();
                    try {
                        callback.error(SpeechRecognizer.ERROR_NO_MATCH);  // tell the caller instead of leaving it waiting
                    } catch (RemoteException e) {
                        Log.w(TAG, "Recognition client is no longer reachable", e);
                    }
                    return;
                }
                if (whisperResult.getResult().trim().length() > 0){
                    Log.d(TAG, whisperResult.getResult().trim());
                    try {
                        callback.endOfSpeech();
                        deinitModel();
                        Bundle results = new Bundle();
                        ArrayList<String> resultList = new ArrayList<>();

                        String result = whisperResult.getResult();
                        if (whisperResult.getLanguage().equals("zh")){
                            boolean simpleChinese = sp.getBoolean("RecognitionServiceSimpleChinese",false);
                            result = simpleChinese ? ZhConverterUtil.toSimple(result) : ZhConverterUtil.toTraditional(result);
                        }

                        resultList.add(result.trim());
                        results.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, resultList);
                        callback.results(results);
                    } catch (RemoteException e) {
                        Log.w(TAG, "Recognition client is no longer reachable", e);  // e.g. the calling app was closed; must not crash ToneTunner
                    }
                }
            }
        });
    }

    private void startRecording() {
        mRecorder.initVad();
        mRecorder.start();
        recognitionCancelled = false;
    }

    private void startTranscription() {
        Whisper whisper = mWhisper;
        if (!recognitionCancelled && whisper != null){
            Handler handler = new Handler(Looper.getMainLooper());
            handler.post(()-> {
                Toast toast = new Toast(this);
                toast.setDuration(Toast.LENGTH_SHORT);
                toast.setText(R.string.processing);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    toast.addCallback(new Toast.Callback() {
                        @Override
                        public void onToastHidden() {
                            super.onToastHidden();
                            if (mWhisper!=null) toast.show();
                        }
                    });
                }
                toast.show();
            });
            whisper.setAction(ACTION_TRANSCRIBE);
            whisper.start();
            Log.d(TAG,"Start Transcription");
        }
    }

    @Override
    public void onDestroy (){
        deinitModel();
        if (mRecorder != null) mRecorder.release();
        super.onDestroy();
    }
    // synchronized: called from the main thread (cancel, destroy) and from model callbacks (error, result)
    private synchronized void deinitModel() {
        if (mWhisper != null) {
            mWhisper.unloadModel();
            mWhisper = null;
        }
    }

    private boolean checkRecordPermission(Callback callback) {
        int permission = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO);
        boolean granted = permission == PackageManager.PERMISSION_GRANTED;
        if (!granted){
            Log.d(TAG,getString(R.string.need_record_audio_permission));
            try {
                callback.error(ERROR_INSUFFICIENT_PERMISSIONS);
            } catch (RemoteException e) {
                Log.w(TAG, "Recognition client is no longer reachable", e);  // e.g. the calling app was closed; must not crash ToneTunner
            }
        }
        return granted;
    }

}
