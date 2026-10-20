package com.devilved.tonetunner;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import com.devilved.tonetunner.asr.Whisper;
import com.devilved.tonetunner.asr.WhisperResult;
import com.devilved.tonetunner.voice_translation.neural_networks.voice.Recognizer;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Exercises the IME's installed Whisper listener across real service lifecycle callbacks. No
 * recording is started and Whisper.loadModel() is lazy, so these checks need no microphone, model,
 * provider request, or credential.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class WhisperInputMethodServiceLifecycleTest {
    @Before public void clearSettings() {
        RuntimeEnvironment.getApplication()
                .getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @Test public void resultForCurrentEditorCommitsToItsInputConnection() throws Exception {
        ServiceFixture fixture = startService(InputType.TYPE_CLASS_TEXT, 0);
        try {
            Whisper.WhisperListener listener = installListener(fixture.service);

            listener.onResultReceived(result("current editor"));

            assertEquals(1, fixture.connection.commitCount);
            assertEquals("current editor ", fixture.connection.text());
        } finally {
            fixture.controller.destroy();
        }
    }

    @Test public void resultFromFinishedEditorCannotCommitToEitherOldOrReplacementConnection()
            throws Exception {
        ServiceFixture fixture = startService(InputType.TYPE_CLASS_TEXT, 0);
        try {
            Whisper.WhisperListener oldListener = installListener(fixture.service);
            RecordingInputConnection oldConnection = fixture.connection;

            fixture.service.onFinishInputView(true);
            fixture.service.onFinishInput();

            EditorInfo replacementEditor = editor(InputType.TYPE_CLASS_TEXT, 0);
            RecordingInputConnection replacementConnection = new RecordingInputConnection();
            fixture.service.focus(replacementEditor, replacementConnection);
            fixture.service.onStartInput(replacementEditor, false);
            fixture.service.onStartInputView(replacementEditor, false);

            oldListener.onResultReceived(result("late result"));

            assertEquals(0, oldConnection.commitCount);
            assertEquals(0, replacementConnection.commitCount);
            assertEquals("", replacementConnection.text());
        } finally {
            fixture.controller.destroy();
        }
    }

    @Test public void selectionChangeCancellationRejectsAResultAlreadyHoldingTheListener()
            throws Exception {
        ServiceFixture fixture = startService(InputType.TYPE_CLASS_TEXT, 0);
        try {
            Whisper.WhisperListener listener = installListener(fixture.service);
            setBooleanField(fixture.service, "acceptRecording", true);

            fixture.service.onUpdateSelection(0, 0, 1, 1, -1, -1);
            listener.onResultReceived(result("cancelled after selection moved"));

            assertEquals(0, fixture.connection.commitCount);
            assertEquals("", fixture.connection.text());
        } finally {
            fixture.controller.destroy();
        }
    }

    @Test public void cancelButtonRejectsAResultAlreadyHoldingTheListener() throws Exception {
        ServiceFixture fixture = startService(InputType.TYPE_CLASS_TEXT, 0);
        try {
            Whisper.WhisperListener listener = installListener(fixture.service);
            setBooleanField(fixture.service, "acceptRecording", true);

            fixture.inputView.findViewById(R.id.btnCancelDictation).performClick();
            listener.onResultReceived(result("cancelled from keyboard"));

            assertEquals(0, fixture.connection.commitCount);
            assertEquals("", fixture.connection.text());
        } finally {
            fixture.controller.destroy();
        }
    }

    @Test public void passwordEditorNeverReceivesAResult() throws Exception {
        ServiceFixture fixture = startService(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, 0);
        try {
            Whisper.WhisperListener listener = installListener(fixture.service);

            listener.onResultReceived(result("private"));

            assertEquals(0, fixture.connection.commitCount);
            assertEquals("", fixture.connection.text());
        } finally {
            fixture.controller.destroy();
        }
    }

    @Test public void noPersonalizedLearningEditorNeverReceivesCloudResult() throws Exception {
        ServiceFixture fixture = startService(InputType.TYPE_CLASS_TEXT,
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        try {
            Whisper.WhisperListener listener = installListener(fixture.service);

            listener.onResultReceived(result("private"));

            assertEquals(0, fixture.connection.commitCount);
            assertEquals("", fixture.connection.text());
        } finally {
            fixture.controller.destroy();
        }
    }

    private static ServiceFixture startService(int inputType, int imeOptions) {
        ServiceController<EditorTestService> controller =
                Robolectric.buildService(EditorTestService.class).create();
        EditorTestService service = controller.get();
        View inputView = service.onCreateInputView();
        EditorInfo editor = editor(inputType, imeOptions);
        RecordingInputConnection connection = new RecordingInputConnection();
        service.focus(editor, connection);
        service.onStartInput(editor, false);
        service.onStartInputView(editor, false);
        return new ServiceFixture(controller, service, inputView, connection);
    }

    private static EditorInfo editor(int inputType, int imeOptions) {
        EditorInfo editor = new EditorInfo();
        editor.inputType = inputType;
        editor.imeOptions = imeOptions;
        return editor;
    }

    private static Whisper.WhisperListener installListener(EditorTestService service)
            throws Exception {
        Method initModel = WhisperInputMethodService.class.getDeclaredMethod("initModel");
        initModel.setAccessible(true);
        initModel.invoke(service);

        Whisper whisper = (Whisper) field(WhisperInputMethodService.class, "mWhisper").get(service);
        assertNotNull(whisper);
        Whisper.WhisperListener listener = (Whisper.WhisperListener)
                field(Whisper.class, "listener").get(whisper);
        assertNotNull(listener);

        // startRecording normally snapshots the current editor generation before it installs the
        // listener. Seed that snapshot directly so this test can exercise listener delivery without
        // starting Recorder or touching the microphone.
        Field generation = field(WhisperInputMethodService.class, "editorGeneration");
        field(WhisperInputMethodService.class, "requestEditorGeneration")
                .setLong(service, generation.getLong(service));
        return listener;
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void setBooleanField(Object owner, String name, boolean value) throws Exception {
        field(owner.getClass().getSuperclass(), name).setBoolean(owner, value);
    }

    private static WhisperResult result(String text) {
        return new WhisperResult(text, "en", Recognizer.ACTION_TRANSCRIBE);
    }

    public static final class EditorTestService extends WhisperInputMethodService {
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

    private static final class RecordingInputConnection extends BaseInputConnection {
        private int commitCount;

        RecordingInputConnection() {
            super(new View(RuntimeEnvironment.getApplication()), true);
        }

        @Override public boolean commitText(CharSequence text, int newCursorPosition) {
            commitCount++;
            return super.commitText(text, newCursorPosition);
        }

        String text() {
            Editable editable = getEditable();
            return editable == null ? "" : editable.toString();
        }
    }

    private static final class ServiceFixture {
        final ServiceController<EditorTestService> controller;
        final EditorTestService service;
        final View inputView;
        final RecordingInputConnection connection;

        ServiceFixture(ServiceController<EditorTestService> controller, EditorTestService service,
                View inputView, RecordingInputConnection connection) {
            this.controller = controller;
            this.service = service;
            this.inputView = inputView;
            this.connection = connection;
        }
    }
}
