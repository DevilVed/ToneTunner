package com.devilved.tonetunner;

import android.app.Activity;
import android.content.Context;
import android.os.Looper;
import android.view.WindowManager;

import androidx.preference.PreferenceManager;

import com.devilved.tonetunner.asr.Recorder;
import com.devilved.tonetunner.asr.Whisper;
import com.devilved.tonetunner.asr.WhisperResult;
import com.devilved.tonetunner.voice_translation.neural_networks.voice.Recognizer;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** The capture screens keep the display awake for the whole active dictation session. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class RecordingScreenAwakeTest {
    private static final long SESSION = 41L;

    @Before public void resetPreferences() {
        Context application = RuntimeEnvironment.getApplication();
        PreferenceManager.getDefaultSharedPreferences(application).edit()
                .clear().putBoolean("imeModeAuto", false).putString("language", "auto").commit();
        application.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @Test public void mainActivityKeepsScreenOnThroughProcessingAndReleasesAfterSuccess()
            throws Exception {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        InProgressRecorder recorder = null;
        try {
            assertKeepScreenOn(activity, false);
            activateMainRecording(activity, SESSION);
            Whisper.WhisperListener listener = installMainWhisperListener(activity, SESSION);
            assertKeepScreenOn(activity, true);

            recorder = new InProgressRecorder(activity);
            field(MainActivity.class, "mRecorder").set(activity, recorder);
            invoke(activity, "stopRecordingForTranscription", new Class<?>[0]);

            assertProcessing(activity, MainActivity.class);
            assertKeepScreenOn(activity, true);
            listener.onUpdateReceived(Whisper.MSG_PROCESSING);
            assertKeepScreenOn(activity, true);
            listener.onUpdateReceived(Whisper.MSG_ENHANCING);
            assertKeepScreenOn(activity, true);

            listener.onResultReceived(result("recognized text"));

            assertKeepScreenOn(activity, false);
        } finally {
            controller.destroy();
            if (recorder != null) recorder.release();
        }
    }

    @Test public void mainActivityReleasesScreenOnProcessingError() throws Exception {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        try {
            activateMainRecording(activity, SESSION);
            Whisper.WhisperListener listener = installMainWhisperListener(activity, SESSION);
            listener.onUpdateReceived(Whisper.MSG_PROCESSING);

            assertProcessing(activity, MainActivity.class);
            assertKeepScreenOn(activity, true);
            listener.onError("transcription failed");

            assertKeepScreenOn(activity, false);
        } finally {
            controller.destroy();
        }
    }

    @Test public void mainActivityReleasesScreenWhenProcessingIsCancelled() throws Exception {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        try {
            activateMainRecording(activity, SESSION);
            Whisper.WhisperListener listener = installMainWhisperListener(activity, SESSION);
            listener.onUpdateReceived(Whisper.MSG_PROCESSING);

            assertProcessing(activity, MainActivity.class);
            assertKeepScreenOn(activity, true);
            invoke(activity, "cancelCurrentSession", new Class<?>[]{Integer.class},
                    R.string.entrypoint_cancelled);

            assertKeepScreenOn(activity, false);
        } finally {
            controller.destroy();
        }
    }

    @Test public void mainActivityReleasesScreenWhenPausedDuringProcessing() throws Exception {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        try {
            activateMainRecording(activity, SESSION);
            installMainWhisperListener(activity, SESSION).onUpdateReceived(Whisper.MSG_PROCESSING);

            assertProcessing(activity, MainActivity.class);
            assertKeepScreenOn(activity, true);
            controller.pause();

            assertKeepScreenOn(activity, false);
            String status = ((android.widget.TextView) activity.findViewById(R.id.tvStatus))
                    .getText().toString();
            assertFalse("A cancelled foreground session must not keep showing an active status",
                    activity.getString(R.string.entrypoint_transcribing).equals(status)
                            || activity.getString(R.string.entrypoint_recording).equals(status));
        } finally {
            controller.destroy();
        }
    }

    @Test public void mainActivityReleasesScreenOnRecorderError() throws Exception {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        try {
            activateMainRecording(activity, SESSION);
            invoke(activity, "onRecorderMessage", new Class<?>[]{long.class, String.class},
                    SESSION, Recorder.MSG_RECORDING_ERROR);
            assertKeepScreenOn(activity, false);
        } finally {
            controller.destroy();
        }
    }

    @Test public void recognizeActivityKeepsScreenOnThroughProcessingAndReleasesAfterSuccess()
            throws Exception {
        ActivityController<WhisperRecognizeActivity> controller =
                Robolectric.buildActivity(WhisperRecognizeActivity.class).setup();
        WhisperRecognizeActivity activity = controller.get();
        InProgressRecorder recorder = null;
        try {
            assertKeepScreenOn(activity, false);
            activateRecognizeRecording(activity, SESSION);
            Whisper.WhisperListener listener = installRecognizeWhisperListener(activity, SESSION);
            assertKeepScreenOn(activity, true);

            recorder = new InProgressRecorder(activity);
            field(WhisperRecognizeActivity.class, "mRecorder").set(activity, recorder);
            invoke(activity, "stopRecordingForTranscription", new Class<?>[0]);

            assertProcessing(activity, WhisperRecognizeActivity.class);
            assertKeepScreenOn(activity, true);
            listener.onUpdateReceived(Whisper.MSG_PROCESSING);
            assertKeepScreenOn(activity, true);
            listener.onUpdateReceived(Whisper.MSG_ENHANCING);
            assertKeepScreenOn(activity, true);
            listener.onResultReceived(result("recognized text"));
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertKeepScreenOn(activity, false);
        } finally {
            controller.destroy();
            if (recorder != null) recorder.release();
        }
    }

    @Test public void recognizeActivityReleasesScreenOnProcessingError() throws Exception {
        ActivityController<WhisperRecognizeActivity> controller =
                Robolectric.buildActivity(WhisperRecognizeActivity.class).setup();
        WhisperRecognizeActivity activity = controller.get();
        try {
            activateRecognizeRecording(activity, SESSION);
            Whisper.WhisperListener listener = installRecognizeWhisperListener(activity, SESSION);
            listener.onUpdateReceived(Whisper.MSG_PROCESSING);

            assertProcessing(activity, WhisperRecognizeActivity.class);
            assertKeepScreenOn(activity, true);
            listener.onError("transcription failed");
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertKeepScreenOn(activity, false);
        } finally {
            controller.destroy();
        }
    }

    @Test public void recognizeActivityReleasesScreenWhenProcessingIsCancelled() throws Exception {
        ActivityController<WhisperRecognizeActivity> controller =
                Robolectric.buildActivity(WhisperRecognizeActivity.class).setup();
        WhisperRecognizeActivity activity = controller.get();
        try {
            activateRecognizeRecording(activity, SESSION);
            Whisper.WhisperListener listener = installRecognizeWhisperListener(activity, SESSION);
            listener.onUpdateReceived(Whisper.MSG_PROCESSING);

            assertProcessing(activity, WhisperRecognizeActivity.class);
            assertKeepScreenOn(activity, true);
            invoke(activity, "cancelCurrentSession", new Class<?>[0]);

            assertKeepScreenOn(activity, false);
        } finally {
            controller.destroy();
        }
    }

    @Test public void recognizeActivityReleasesScreenWhenPausedDuringProcessing() throws Exception {
        ActivityController<WhisperRecognizeActivity> controller =
                Robolectric.buildActivity(WhisperRecognizeActivity.class).setup();
        WhisperRecognizeActivity activity = controller.get();
        try {
            activateRecognizeRecording(activity, SESSION);
            installRecognizeWhisperListener(activity, SESSION)
                    .onUpdateReceived(Whisper.MSG_PROCESSING);

            assertProcessing(activity, WhisperRecognizeActivity.class);
            assertKeepScreenOn(activity, true);
            controller.pause();

            assertKeepScreenOn(activity, false);
        } finally {
            controller.destroy();
        }
    }

    @Test public void recognizeActivityReleasesScreenOnRecorderError() throws Exception {
        ActivityController<WhisperRecognizeActivity> controller =
                Robolectric.buildActivity(WhisperRecognizeActivity.class).setup();
        WhisperRecognizeActivity activity = controller.get();
        try {
            activateRecognizeRecording(activity, SESSION);
            invoke(activity, "onRecorderMessage", new Class<?>[]{long.class, String.class},
                    SESSION, Recorder.MSG_RECORDING_ERROR);
            assertKeepScreenOn(activity, false);
        } finally {
            controller.destroy();
        }
    }

    private static void activateMainRecording(MainActivity activity, long session) throws Exception {
        field(MainActivity.class, "sessionGeneration").setLong(activity, session);
        field(MainActivity.class, "sessionActive").setBoolean(activity, true);
        field(MainActivity.class, "recording").setBoolean(activity, true);
        invoke(activity, "setControlsForState", new Class<?>[0]);
    }

    private static void activateRecognizeRecording(WhisperRecognizeActivity activity, long session)
            throws Exception {
        field(WhisperRecognizeActivity.class, "sessionGeneration").setLong(activity, session);
        field(WhisperRecognizeActivity.class, "sessionActive").setBoolean(activity, true);
        field(WhisperRecognizeActivity.class, "recording").setBoolean(activity, true);
        invoke(activity, "onRecorderMessage", new Class<?>[]{long.class, String.class},
                session, Recorder.MSG_RECORDING);
    }

    private static Whisper.WhisperListener installMainWhisperListener(MainActivity activity,
            long session) throws Exception {
        invoke(activity, "ensureWhisper", new Class<?>[]{long.class}, session);
        Whisper whisper = (Whisper) field(MainActivity.class, "mWhisper").get(activity);
        assertNotNull(whisper);
        Whisper.WhisperListener listener = (Whisper.WhisperListener)
                field(Whisper.class, "listener").get(whisper);
        assertNotNull(listener);
        return listener;
    }

    private static Whisper.WhisperListener installRecognizeWhisperListener(
            WhisperRecognizeActivity activity, long session) throws Exception {
        invoke(activity, "ensureWhisper", new Class<?>[]{long.class}, session);
        Whisper whisper = (Whisper) field(WhisperRecognizeActivity.class, "mWhisper").get(activity);
        assertNotNull(whisper);
        Whisper.WhisperListener listener = (Whisper.WhisperListener)
                field(Whisper.class, "listener").get(whisper);
        assertNotNull(listener);
        return listener;
    }

    private static WhisperResult result(String text) {
        return new WhisperResult(text, "en", Recognizer.ACTION_TRANSCRIBE);
    }

    private static void assertProcessing(Activity activity, Class<?> owner) throws Exception {
        assertTrue("processing should remain active until a terminal callback",
                field(owner, "processing").getBoolean(activity));
    }

    private static void assertKeepScreenOn(Activity activity, boolean expected) {
        boolean actual = (activity.getWindow().getAttributes().flags
                & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0;
        assertEquals("FLAG_KEEP_SCREEN_ON should match the active dictation session", expected, actual);
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes,
            Object... arguments) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }

    /** Prevents this lifecycle test from starting a real microphone capture. */
    private static final class InProgressRecorder extends Recorder {
        private boolean inProgress = true;

        InProgressRecorder(Context context) {
            super(context);
        }

        @Override public void start() { }

        @Override public void stop() {
            inProgress = false;
        }

        @Override public boolean isInProgress() {
            return inProgress;
        }

        @Override public void release() {
            inProgress = false;
            super.release();
        }
    }
}
