package com.devilved.tonetunner;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

import com.devilved.tonetunner.asr.Recorder;
import com.devilved.tonetunner.cloud.Provider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowActivity;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Translation choices must be checked before recording, without needing to read a saved key. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = EntryPointTranslationPreflightTest.RecorderShadow.class)
public class EntryPointTranslationPreflightTest {
    private static final String UNSUPPORTED_OPENAI_MODEL = "gpt-4o-mini-transcribe";

    @Before public void clearSettings() throws Exception {
        Context application = RuntimeEnvironment.getApplication();
        application.getSharedPreferences("ai_settings", Context.MODE_PRIVATE).edit().clear().commit();
        PreferenceManager.getDefaultSharedPreferences(application).edit()
                .putBoolean("imeModeAuto", false).commit();
        RecorderShadow.reset();
        Field translate = WhisperInputMethodService.class.getDeclaredField("translate");
        translate.setAccessible(true);
        translate.setBoolean(null, false);
    }

    @Test public void mainActivityRejectsUnsupportedTranslationBeforeCloudConsentOrKeyChecks()
            throws Exception {
        setTranscription(Provider.OPENAI, UNSUPPORTED_OPENAI_MODEL, false);
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        try {
            Shadows.shadowOf(RuntimeEnvironment.getApplication())
                    .grantPermissions(Manifest.permission.RECORD_AUDIO);
            ((CheckBox) activity.findViewById(R.id.mode_translate)).setChecked(true);

            assertFalse(invokeStartRecording(activity));

            String status = ((TextView) activity.findViewById(R.id.tvStatus)).getText().toString();
            assertTrue("The user should be told translation is unavailable before recording: " + status,
                    status.toLowerCase(Locale.ROOT).contains("translat"));
            assertFalse("No microphone request should start during translation preflight",
                    RecorderShadow.started);
        } finally {
            controller.destroy();
        }
    }

    @Test public void inputMethodRejectsUnsupportedTranslationBeforeCloudConsentOrKeyChecks()
            throws Exception {
        setTranscription(Provider.OPENAI, UNSUPPORTED_OPENAI_MODEL, false);
        ServiceController<TestInputMethodService> controller =
                Robolectric.buildService(TestInputMethodService.class).create();
        TestInputMethodService service = controller.get();
        try {
            Shadows.shadowOf(RuntimeEnvironment.getApplication())
                    .grantPermissions(Manifest.permission.RECORD_AUDIO);
            View inputView = service.onCreateInputView();
            EditorInfo editor = editor();
            service.focus(editor, null);
            service.onStartInput(editor, false);
            service.onStartInputView(editor, false);
            inputView.findViewById(R.id.btnTranslate).performClick();

            inputView.findViewById(R.id.btnRecord).performClick();

            String status = ((TextView) inputView.findViewById(R.id.tv_status)).getText().toString();
            assertTrue("The user should be told translation is unavailable before recording: " + status,
                    status.toLowerCase(Locale.ROOT).contains("translat"));
            assertFalse("No microphone request should start during translation preflight",
                    RecorderShadow.started);
        } finally {
            controller.destroy();
        }
    }

    @Test public void mainActivityAllowsLocalModelTranslationWithoutCloudConsentOrKey()
            throws Exception {
        setTranscription(Provider.LOCAL, "", false);
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        try {
            Shadows.shadowOf(RuntimeEnvironment.getApplication())
                    .grantPermissions(Manifest.permission.RECORD_AUDIO);
            ((CheckBox) activity.findViewById(R.id.mode_translate)).setChecked(true);

            assertTrue(invokeStartRecording(activity));

            assertTrue("Local translation remains available without cloud setup", RecorderShadow.started);
        } finally {
            controller.destroy();
        }
    }

    @Test public void mainActivityResumesRecordingAfterMicrophonePermissionGrant() throws Exception {
        setTranscription(Provider.LOCAL, "", false);
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        try {
            Shadows.shadowOf(RuntimeEnvironment.getApplication())
                    .denyPermissions(Manifest.permission.RECORD_AUDIO);

            View button = activity.findViewById(R.id.btnRecord);
            dispatchTouch(button, MotionEvent.ACTION_DOWN, 1000L);
            ShadowActivity.PermissionsRequest request = requestedPermission(activity);
            assertNotNull("A microphone permission request should be submitted", request);
            assertEquals("The request must be for microphone access", Manifest.permission.RECORD_AUDIO,
                    request.requestedPermissions[0]);
            assertEquals("Only microphone access should be requested", 1,
                    request.requestedPermissions.length);
            assertFalse("Capture must wait for the permission result", RecorderShadow.started);
            dispatchTouch(button, MotionEvent.ACTION_UP, 1200L);
            assertTrue("A short tap should preserve the intended permission-to-start action",
                    field(MainActivity.class, "pendingStartAfterPermission").getBoolean(activity));

            Shadows.shadowOf(RuntimeEnvironment.getApplication())
                    .grantPermissions(Manifest.permission.RECORD_AUDIO);
            activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                    new int[]{PackageManager.PERMISSION_GRANTED});

            assertTrue("Granting the pending request should resume capture", RecorderShadow.started);
            assertTrue(field(MainActivity.class, "sessionActive").getBoolean(activity));
            assertTrue(field(MainActivity.class, "recording").getBoolean(activity));
            assertFalse(field(MainActivity.class, "pendingStartAfterPermission").getBoolean(activity));
        } finally {
            controller.destroy();
        }
    }

    @Test public void mainActivityDoesNotStartRecordingAfterMicrophonePermissionDenial()
            throws Exception {
        setTranscription(Provider.LOCAL, "", false);
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        try {
            Shadows.shadowOf(RuntimeEnvironment.getApplication())
                    .denyPermissions(Manifest.permission.RECORD_AUDIO);

            assertFalse(invokeStartRecording(activity));
            ShadowActivity.PermissionsRequest request = requestedPermission(activity);
            assertNotNull("A microphone permission request should be submitted", request);
            activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                    new int[]{PackageManager.PERMISSION_DENIED});

            assertFalse("Denied microphone permission must not start capture", RecorderShadow.started);
            assertFalse(field(MainActivity.class, "sessionActive").getBoolean(activity));
            assertFalse(field(MainActivity.class, "recording").getBoolean(activity));
            assertFalse(field(MainActivity.class, "pendingStartAfterPermission").getBoolean(activity));
            String status = ((TextView) activity.findViewById(R.id.tvStatus)).getText().toString();
            assertEquals(activity.getString(R.string.entrypoint_permission_denied), status);
        } finally {
            controller.destroy();
        }
    }

    @Test public void mainActivityDoesNotResumeHeldPermissionRequestAfterRelease()
            throws Exception {
        setTranscription(Provider.LOCAL, "", false);
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        try {
            Shadows.shadowOf(RuntimeEnvironment.getApplication())
                    .denyPermissions(Manifest.permission.RECORD_AUDIO);
            View button = activity.findViewById(R.id.btnRecord);
            dispatchTouch(button, MotionEvent.ACTION_DOWN, 1000L);

            ShadowActivity.PermissionsRequest request = requestedPermission(activity);
            assertNotNull("A microphone permission request should be submitted", request);
            assertTrue(field(MainActivity.class, "pendingStartAfterPermission").getBoolean(activity));
            dispatchTouch(button, MotionEvent.ACTION_UP, 1400L);
            assertFalse("Releasing a held request should clear its deferred start",
                    field(MainActivity.class, "pendingStartAfterPermission").getBoolean(activity));

            Shadows.shadowOf(RuntimeEnvironment.getApplication())
                    .grantPermissions(Manifest.permission.RECORD_AUDIO);
            activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                    new int[]{PackageManager.PERMISSION_GRANTED});

            assertFalse("A permission grant after release must not open the microphone",
                    RecorderShadow.started);
            assertFalse(field(MainActivity.class, "recording").getBoolean(activity));
            assertFalse(field(MainActivity.class, "sessionActive").getBoolean(activity));
        } finally {
            controller.destroy();
        }
    }

    @Test public void mainActivityDoesNotResumePermissionRequestAfterTouchCancellation()
            throws Exception {
        setTranscription(Provider.LOCAL, "", false);
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        try {
            Shadows.shadowOf(RuntimeEnvironment.getApplication())
                    .denyPermissions(Manifest.permission.RECORD_AUDIO);
            View button = activity.findViewById(R.id.btnRecord);
            dispatchTouch(button, MotionEvent.ACTION_DOWN, 1000L);
            ShadowActivity.PermissionsRequest request = requestedPermission(activity);
            assertNotNull("A microphone permission request should be submitted", request);
            assertTrue(field(MainActivity.class, "pendingStartAfterPermission").getBoolean(activity));

            dispatchTouch(button, MotionEvent.ACTION_CANCEL, 1200L);
            assertFalse("A canceled touch must clear its deferred permission start",
                    field(MainActivity.class, "pendingStartAfterPermission").getBoolean(activity));
            Shadows.shadowOf(RuntimeEnvironment.getApplication())
                    .grantPermissions(Manifest.permission.RECORD_AUDIO);
            activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                    new int[]{PackageManager.PERMISSION_GRANTED});

            assertFalse("A permission grant after touch cancellation must not open the microphone",
                    RecorderShadow.started);
            assertFalse(field(MainActivity.class, "recording").getBoolean(activity));
            assertFalse(field(MainActivity.class, "sessionActive").getBoolean(activity));
        } finally {
            controller.destroy();
        }
    }

    @Test public void recognizeActivityDoesNotStartAfterPermissionRequestIsHeldAndReleased()
            throws Exception {
        setTranscription(Provider.LOCAL, "", false);
        ActivityController<WhisperRecognizeActivity> controller =
                Robolectric.buildActivity(WhisperRecognizeActivity.class).setup();
        WhisperRecognizeActivity activity = controller.get();
        try {
            Shadows.shadowOf(RuntimeEnvironment.getApplication())
                    .denyPermissions(Manifest.permission.RECORD_AUDIO);
            View button = activity.findViewById(R.id.btnRecord);
            dispatchTouch(button, MotionEvent.ACTION_DOWN, 1000L);

            ShadowActivity.PermissionsRequest request = requestedPermission(activity);
            assertNotNull("A microphone permission request should be submitted", request);
            assertTrue(field(WhisperRecognizeActivity.class, "pendingStartAfterPermission")
                    .getBoolean(activity));
            dispatchTouch(button, MotionEvent.ACTION_UP, 1400L);
            assertFalse("Releasing a held request should clear its deferred start",
                    field(WhisperRecognizeActivity.class, "pendingStartAfterPermission")
                            .getBoolean(activity));

            Shadows.shadowOf(RuntimeEnvironment.getApplication())
                    .grantPermissions(Manifest.permission.RECORD_AUDIO);
            activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                    new int[]{PackageManager.PERMISSION_GRANTED});

            assertFalse("A grant after held release must not open the microphone", RecorderShadow.started);
            assertFalse(field(WhisperRecognizeActivity.class, "sessionActive").getBoolean(activity));
            assertFalse(field(WhisperRecognizeActivity.class, "recording").getBoolean(activity));
        } finally {
            controller.destroy();
        }
    }

    @Test public void recognizeActivityDoesNotStartAfterPermissionRequestTouchCancellation()
            throws Exception {
        setTranscription(Provider.LOCAL, "", false);
        ActivityController<WhisperRecognizeActivity> controller =
                Robolectric.buildActivity(WhisperRecognizeActivity.class).setup();
        WhisperRecognizeActivity activity = controller.get();
        try {
            Shadows.shadowOf(RuntimeEnvironment.getApplication())
                    .denyPermissions(Manifest.permission.RECORD_AUDIO);
            View button = activity.findViewById(R.id.btnRecord);
            dispatchTouch(button, MotionEvent.ACTION_DOWN, 1000L);
            ShadowActivity.PermissionsRequest request = requestedPermission(activity);
            assertNotNull("A microphone permission request should be submitted", request);
            assertTrue(field(WhisperRecognizeActivity.class, "pendingStartAfterPermission")
                    .getBoolean(activity));

            dispatchTouch(button, MotionEvent.ACTION_CANCEL, 1200L);
            assertFalse("A canceled touch must clear its deferred permission start",
                    field(WhisperRecognizeActivity.class, "pendingStartAfterPermission")
                            .getBoolean(activity));
            Shadows.shadowOf(RuntimeEnvironment.getApplication())
                    .grantPermissions(Manifest.permission.RECORD_AUDIO);
            activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                    new int[]{PackageManager.PERMISSION_GRANTED});

            assertFalse("A grant after touch cancellation must not open the microphone",
                    RecorderShadow.started);
            assertFalse(field(WhisperRecognizeActivity.class, "sessionActive").getBoolean(activity));
        } finally {
            controller.destroy();
        }
    }

    @Test public void recognizeActivityStartsAfterShortPermissionRequestIsGranted() throws Exception {
        setTranscription(Provider.LOCAL, "", false);
        ActivityController<WhisperRecognizeActivity> controller =
                Robolectric.buildActivity(WhisperRecognizeActivity.class).setup();
        WhisperRecognizeActivity activity = controller.get();
        try {
            Shadows.shadowOf(RuntimeEnvironment.getApplication())
                    .denyPermissions(Manifest.permission.RECORD_AUDIO);
            View button = activity.findViewById(R.id.btnRecord);
            dispatchTouch(button, MotionEvent.ACTION_DOWN, 1000L);
            ShadowActivity.PermissionsRequest request = requestedPermission(activity);
            assertNotNull("A microphone permission request should be submitted", request);
            dispatchTouch(button, MotionEvent.ACTION_UP, 1200L);
            assertTrue("A short tap should preserve the deferred permission start",
                    field(WhisperRecognizeActivity.class, "pendingStartAfterPermission")
                            .getBoolean(activity));

            Shadows.shadowOf(RuntimeEnvironment.getApplication())
                    .grantPermissions(Manifest.permission.RECORD_AUDIO);
            activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                    new int[]{PackageManager.PERMISSION_GRANTED});

            assertTrue("A grant after a short tap should resume capture", RecorderShadow.started);
            assertTrue(field(WhisperRecognizeActivity.class, "sessionActive").getBoolean(activity));
            assertTrue(field(WhisperRecognizeActivity.class, "recording").getBoolean(activity));
        } finally {
            controller.destroy();
        }
    }

    @Test public void recognizeActivityStillResumesAutomaticPermissionStart() throws Exception {
        setTranscription(Provider.LOCAL, "", false);
        PreferenceManager.getDefaultSharedPreferences(RuntimeEnvironment.getApplication()).edit()
                .putBoolean("imeModeAuto", true).commit();
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
                .denyPermissions(Manifest.permission.RECORD_AUDIO);
        ActivityController<WhisperRecognizeActivity> controller =
                Robolectric.buildActivity(WhisperRecognizeActivity.class).setup();
        WhisperRecognizeActivity activity = controller.get();
        try {
            // Auto mode asks for permission as the activity starts, without a touch gesture.
            ShadowActivity.PermissionsRequest request = requestedPermission(activity);
            assertNotNull("Automatic startup should request microphone access", request);
            assertTrue(field(WhisperRecognizeActivity.class, "pendingAutoStart").getBoolean(activity));

            Shadows.shadowOf(RuntimeEnvironment.getApplication())
                    .grantPermissions(Manifest.permission.RECORD_AUDIO);
            activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                    new int[]{PackageManager.PERMISSION_GRANTED});

            assertTrue("Automatic capture should still resume after permission grant",
                    RecorderShadow.started);
            assertTrue(field(WhisperRecognizeActivity.class, "sessionActive").getBoolean(activity));
        } finally {
            controller.destroy();
        }
    }

    @Test public void mainActivityIgnoresVADSpeechStartedNotification() throws Exception {
        setTranscription(Provider.LOCAL, "", false);
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        try {
            Shadows.shadowOf(RuntimeEnvironment.getApplication())
                    .grantPermissions(Manifest.permission.RECORD_AUDIO);
            assertTrue(invokeStartRecording(activity));

            RecorderShadow.emit(Recorder.MSG_SPEECH_STARTED);
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertTrue(field(MainActivity.class, "sessionActive").getBoolean(activity));
            assertTrue(field(MainActivity.class, "recording").getBoolean(activity));
        } finally {
            controller.destroy();
        }
    }

    @Test public void recognizeActivityIgnoresVADSpeechStartedNotification() throws Exception {
        setTranscription(Provider.LOCAL, "", false);
        ActivityController<WhisperRecognizeActivity> controller =
                Robolectric.buildActivity(WhisperRecognizeActivity.class).setup();
        WhisperRecognizeActivity activity = controller.get();
        try {
            Shadows.shadowOf(RuntimeEnvironment.getApplication())
                    .grantPermissions(Manifest.permission.RECORD_AUDIO);
            Method begin = WhisperRecognizeActivity.class.getDeclaredMethod("beginRecording", boolean.class);
            begin.setAccessible(true);
            assertTrue((Boolean) begin.invoke(activity, false));

            RecorderShadow.emit(Recorder.MSG_SPEECH_STARTED);
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertTrue(field(WhisperRecognizeActivity.class, "sessionActive").getBoolean(activity));
            assertTrue(field(WhisperRecognizeActivity.class, "recording").getBoolean(activity));
        } finally {
            controller.destroy();
        }
    }

    private static void setTranscription(Provider provider, String model, boolean consent) {
        RuntimeEnvironment.getApplication().getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
                .edit()
                .putString("transcription.provider", provider.name())
                .putString("transcription.endpoint", provider.transcriptionEndpoint())
                .putString("transcription.model", model)
                .putBoolean("enhancement.enabled", false)
                .putString("enhancement.provider", Provider.LOCAL.name())
                .putString("enhancement.endpoint", "")
                .putString("enhancement.model", "")
                .putBoolean("cloud.consent", consent)
                .commit();
    }

    private static boolean invokeStartRecording(MainActivity activity) throws Exception {
        Method method = MainActivity.class.getDeclaredMethod("startRecording");
        method.setAccessible(true);
        return (Boolean) method.invoke(activity);
    }

    private static void dispatchTouch(View view, int action, long eventTime) {
        MotionEvent event = MotionEvent.obtain(1000L, eventTime, action, 1f, 1f, 0);
        try {
            assertTrue(view.dispatchTouchEvent(event));
        } finally {
            event.recycle();
        }
    }

    private static ShadowActivity.PermissionsRequest requestedPermission(Activity activity) {
        return Shadows.shadowOf(activity).getLastRequestedPermission();
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static EditorInfo editor() {
        EditorInfo editor = new EditorInfo();
        editor.inputType = InputType.TYPE_CLASS_TEXT;
        editor.packageName = "com.example.editor";
        editor.fieldId = 1;
        return editor;
    }

    public static final class TestInputMethodService extends WhisperInputMethodService {
        private EditorInfo editor;
        private InputConnection connection;

        void focus(EditorInfo editor, InputConnection connection) {
            this.editor = editor;
            this.connection = connection;
        }

        @Override public EditorInfo getCurrentInputEditorInfo() { return editor; }
        @Override public InputConnection getCurrentInputConnection() { return connection; }
    }

    @Implements(value = Recorder.class, isInAndroidSdk = false)
    public static class RecorderShadow {
        static boolean started;
        static Recorder.RecorderListener listener;

        static void reset() { started = false; listener = null; }
        static void emit(String message) {
            if (listener != null) listener.onUpdateReceived(message);
        }

        @Implementation protected void __constructor__(Context context) { }
        @Implementation protected void setListener(Recorder.RecorderListener listener) {
            RecorderShadow.listener = listener;
        }
        // Auto mode loads the native WebRTC VAD, which the JVM test runtime cannot link.
        @Implementation protected void initVad() { }
        @Implementation protected void start() { started = true; }
        @Implementation protected boolean isInProgress() { return started; }
        @Implementation protected void stop() { started = false; }
        @Implementation protected void release() { started = false; }
    }
}
