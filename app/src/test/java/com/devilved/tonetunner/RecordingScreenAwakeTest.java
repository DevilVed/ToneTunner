package com.devilved.tonetunner;

import android.app.Activity;
import android.content.Context;
import android.view.WindowManager;

import androidx.preference.PreferenceManager;

import com.devilved.tonetunner.asr.Recorder;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;

/** The capture UI keeps its display awake only while a recording session is active. */
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

    @Test public void mainActivityKeepsScreenOnOnlyDuringRecordingAndReleasesAfterStop()
            throws Exception {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        InProgressRecorder recorder = null;
        try {
            assertKeepScreenOn(activity, false);
            activateMainRecording(activity, SESSION);
            assertKeepScreenOn(activity, true);

            recorder = new InProgressRecorder(activity);
            field(MainActivity.class, "mRecorder").set(activity, recorder);
            invoke(activity, "stopRecordingForTranscription", new Class<?>[0]);

            assertKeepScreenOn(activity, false);
        } finally {
            controller.destroy();
            if (recorder != null) recorder.release();
        }
    }

    @Test public void mainActivityReleasesScreenOnCancelPauseAndRecorderError() throws Exception {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        try {
            activateMainRecording(activity, SESSION);
            invoke(activity, "cancelCurrentSession", new Class<?>[]{Integer.class},
                    R.string.entrypoint_cancelled);
            assertKeepScreenOn(activity, false);

            activateMainRecording(activity, SESSION + 1);
            controller.pause();
            assertKeepScreenOn(activity, false);
        } finally {
            controller.destroy();
        }

        ActivityController<MainActivity> errorController =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity errorActivity = errorController.get();
        try {
            activateMainRecording(errorActivity, SESSION + 2);
            invoke(errorActivity, "onRecorderMessage",
                    new Class<?>[]{long.class, String.class}, SESSION + 2, Recorder.MSG_RECORDING_ERROR);
            assertKeepScreenOn(errorActivity, false);
        } finally {
            errorController.destroy();
        }
    }

    @Test public void recognizeActivityKeepsScreenOnForRecordingCallbackAndReleasesAfterStop()
            throws Exception {
        ActivityController<WhisperRecognizeActivity> controller =
                Robolectric.buildActivity(WhisperRecognizeActivity.class).setup();
        WhisperRecognizeActivity activity = controller.get();
        InProgressRecorder recorder = null;
        try {
            assertKeepScreenOn(activity, false);
            activateRecognizeRecording(activity, SESSION);
            assertKeepScreenOn(activity, true);

            recorder = new InProgressRecorder(activity);
            field(WhisperRecognizeActivity.class, "mRecorder").set(activity, recorder);
            invoke(activity, "stopRecordingForTranscription", new Class<?>[0]);

            assertKeepScreenOn(activity, false);
        } finally {
            controller.destroy();
            if (recorder != null) recorder.release();
        }
    }

    @Test public void recognizeActivityReleasesScreenOnCancelPauseAndRecorderError()
            throws Exception {
        ActivityController<WhisperRecognizeActivity> controller =
                Robolectric.buildActivity(WhisperRecognizeActivity.class).setup();
        WhisperRecognizeActivity activity = controller.get();
        try {
            activateRecognizeRecording(activity, SESSION);
            invoke(activity, "cancelCurrentSession", new Class<?>[0]);
            assertKeepScreenOn(activity, false);

            activateRecognizeRecording(activity, SESSION + 1);
            controller.pause();
            assertKeepScreenOn(activity, false);
        } finally {
            controller.destroy();
        }

        ActivityController<WhisperRecognizeActivity> errorController =
                Robolectric.buildActivity(WhisperRecognizeActivity.class).setup();
        WhisperRecognizeActivity errorActivity = errorController.get();
        try {
            activateRecognizeRecording(errorActivity, SESSION + 2);
            invoke(errorActivity, "onRecorderMessage",
                    new Class<?>[]{long.class, String.class}, SESSION + 2, Recorder.MSG_RECORDING_ERROR);
            assertKeepScreenOn(errorActivity, false);
        } finally {
            errorController.destroy();
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

    private static void assertKeepScreenOn(Activity activity, boolean expected) {
        boolean actual = (activity.getWindow().getAttributes().flags
                & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0;
        assertEquals("FLAG_KEEP_SCREEN_ON should match active recording", expected, actual);
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
