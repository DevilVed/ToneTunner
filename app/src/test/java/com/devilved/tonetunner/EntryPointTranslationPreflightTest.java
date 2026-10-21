package com.devilved.tonetunner;

import android.Manifest;
import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.CheckBox;
import android.widget.TextView;

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

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.Locale;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Translation choices must be checked before recording, without needing to read a saved key. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = EntryPointTranslationPreflightTest.RecorderShadow.class)
public class EntryPointTranslationPreflightTest {
    private static final String UNSUPPORTED_OPENAI_MODEL = "gpt-4o-mini-transcribe";

    @Before public void clearSettings() throws Exception {
        RuntimeEnvironment.getApplication().getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
                .edit().clear().commit();
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

        static void reset() { started = false; }

        @Implementation protected void __constructor__(Context context) { }
        @Implementation protected void setListener(Recorder.RecorderListener listener) { }
        @Implementation protected void start() { started = true; }
        @Implementation protected boolean isInProgress() { return started; }
        @Implementation protected void stop() { started = false; }
        @Implementation protected void release() { started = false; }
    }
}
