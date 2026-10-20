package com.devilved.tonetunner;

import android.Manifest;
import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import androidx.preference.PreferenceManager;

import com.devilved.tonetunner.asr.PcmRecording;
import com.devilved.tonetunner.asr.Recorder;
import com.devilved.tonetunner.cloud.AiSettings;
import com.devilved.tonetunner.cloud.Provider;
import com.devilved.tonetunner.cloud.ProviderConfig;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;

import java.lang.reflect.Field;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Exercises the IME's real startRecording and recorder-listener paths without opening a microphone. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = WhisperInputMethodServiceScreenAwakeTest.NoMicrophoneRecorderShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class WhisperInputMethodServiceScreenAwakeTest {
    @Before public void resetPreferences() throws Exception {
        Context application = RuntimeEnvironment.getApplication();
        PreferenceManager.getDefaultSharedPreferences(application).edit()
                .clear().putBoolean("imeModeAuto", false).putString("language", "auto").commit();
        application.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
                .edit().clear().commit();
        ProviderConfig local = new ProviderConfig(Provider.LOCAL, "", "", "", false);
        ProviderConfig enhancement = new ProviderConfig(Provider.OPENROUTER,
                Provider.OPENROUTER.enhancementEndpoint(),
                Provider.OPENROUTER.defaultEnhancementModel(), "", true);
        new AiSettings(application).saveSettings(local, false, enhancement, "", "", false);
    }

    @Test public void manualRecordingKeepsInputViewAwakeUntilRecorderReportsDone() throws Exception {
        ServiceFixture fixture = startService();
        try {
            assertFalse(fixture.inputView.getKeepScreenOn());
            NoMicrophoneRecorderShadow recorder = startManualRecording(fixture);

            recorder.emit(Recorder.MSG_RECORDING);
            idleMainLooper();
            assertTrue(fixture.inputView.getKeepScreenOn());

            recorder.emit(Recorder.MSG_RECORDING_DONE);
            idleMainLooper();
            assertFalse(fixture.inputView.getKeepScreenOn());
        } finally {
            fixture.controller.destroy();
        }
    }

    @Test public void recorderErrorReleasesInputViewAwakePolicy() throws Exception {
        ServiceFixture fixture = startService();
        try {
            NoMicrophoneRecorderShadow recorder = startManualRecording(fixture);

            recorder.emit(Recorder.MSG_RECORDING_ERROR);
            idleMainLooper();

            assertFalse(fixture.inputView.getKeepScreenOn());
        } finally {
            fixture.controller.destroy();
        }
    }

    @Test public void cancelButtonReleasesInputViewAwakePolicy() throws Exception {
        ServiceFixture fixture = startService();
        try {
            startManualRecording(fixture);

            fixture.inputView.findViewById(R.id.btnCancelDictation).performClick();

            assertFalse(fixture.inputView.getKeepScreenOn());
        } finally {
            fixture.controller.destroy();
        }
    }

    @Test public void finishingInputViewReleasesInputViewAwakePolicy() throws Exception {
        ServiceFixture fixture = startService();
        try {
            startManualRecording(fixture);

            fixture.service.onFinishInputView(true);

            assertFalse(fixture.inputView.getKeepScreenOn());
        } finally {
            fixture.controller.destroy();
        }
    }

    private static NoMicrophoneRecorderShadow startManualRecording(ServiceFixture fixture)
            throws Exception {
        fixture.inputView.findViewById(R.id.btnRecord).performClick();
        assertTrue("The service should accept the recording request",
                field(WhisperInputMethodService.class, "acceptRecording").getBoolean(fixture.service));

        Recorder recorder = (Recorder) field(WhisperInputMethodService.class, "mRecorder")
                .get(fixture.service);
        assertNotNull(recorder);
        assertTrue("The input view should stay awake during manual recording",
                fixture.inputView.getKeepScreenOn());
        return Shadow.extract(recorder);
    }

    private static ServiceFixture startService() {
        ServiceController<TestInputMethodService> controller =
                Robolectric.buildService(TestInputMethodService.class).create();
        TestInputMethodService service = controller.get();
        Shadows.shadowOf(service).grantPermissions(Manifest.permission.RECORD_AUDIO);

        View inputView = service.onCreateInputView();
        EditorInfo editor = new EditorInfo();
        editor.inputType = InputType.TYPE_CLASS_TEXT;
        service.focus(editor, null);
        service.onStartInput(editor, false);
        service.onStartInputView(editor, false);
        return new ServiceFixture(controller, service, inputView);
    }

    private static void idleMainLooper() {
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    public static final class TestInputMethodService extends WhisperInputMethodService {
        private EditorInfo currentEditor;
        private InputConnection currentConnection;

        void focus(EditorInfo editor, InputConnection connection) {
            currentEditor = editor;
            currentConnection = connection;
        }

        @Override public InputConnection getCurrentInputConnection() {
            return currentConnection;
        }

        @Override public EditorInfo getCurrentInputEditorInfo() {
            return currentEditor;
        }
    }

    /** Replaces only Recorder's worker and microphone effects; service callbacks stay real. */
    @Implements(value = Recorder.class, isInAndroidSdk = false)
    public static final class NoMicrophoneRecorderShadow {
        private Recorder.RecorderListener listener;

        @Implementation protected void __constructor__(Context context) { }

        @Implementation protected void setListener(Recorder.RecorderListener listener) {
            this.listener = listener;
        }

        @Implementation protected void start() { }

        @Implementation protected void release() { }

        @Implementation protected PcmRecording takeRecording() { return null; }

        void emit(String message) {
            if (listener == null) {
                throw new AssertionError("The service did not install its Recorder listener");
            }
            listener.onUpdateReceived(message);
        }
    }

    private static final class ServiceFixture {
        final ServiceController<TestInputMethodService> controller;
        final TestInputMethodService service;
        final View inputView;

        ServiceFixture(ServiceController<TestInputMethodService> controller,
                TestInputMethodService service, View inputView) {
            this.controller = controller;
            this.service = service;
            this.inputView = inputView;
        }
    }
}
