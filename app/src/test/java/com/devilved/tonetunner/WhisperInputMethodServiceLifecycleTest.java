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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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

    @Test public void resultDeliveryAcknowledgesSuccessfulCommit() throws Exception {
        ServiceFixture fixture = startService(InputType.TYPE_CLASS_TEXT, 0);
        try {
            Whisper.WhisperListener listener = installListener(fixture.service);

            assertTrue(listener.onResultDelivery(result("accepted editor")));

            assertEquals(1, fixture.connection.commitCount);
            assertEquals("accepted editor ", fixture.connection.text());
        } finally {
            fixture.controller.destroy();
        }
    }

    @Test public void resultDeliveryRejectsWhenInputConnectionRejectsCommit() throws Exception {
        ServiceFixture fixture = startService(InputType.TYPE_CLASS_TEXT, 0, false);
        try {
            Whisper.WhisperListener listener = installListener(fixture.service);

            assertFalse(listener.onResultDelivery(result("rejected editor")));

            assertEquals(1, fixture.connection.commitCount);
            assertEquals("", fixture.connection.text());
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

    @Test public void restartingSameEditorWithSameConnectionPreservesPendingResult()
            throws Exception {
        ServiceFixture fixture = startService(InputType.TYPE_CLASS_TEXT, 0);
        try {
            Whisper.WhisperListener listener = installListener(fixture.service);
            EditorInfo restartedEditor = editor(InputType.TYPE_CLASS_TEXT, 0);
            fixture.service.focus(restartedEditor, fixture.connection);

            fixture.service.onStartInput(restartedEditor, true);
            listener.onResultReceived(result("same editor restart"));

            assertEquals(1, fixture.connection.commitCount);
            assertEquals("same editor restart ", fixture.connection.text());
        } finally {
            fixture.controller.destroy();
        }
    }

    @Test public void restartingDifferentEditorInvalidatesPendingResult() throws Exception {
        ServiceFixture fixture = startService(InputType.TYPE_CLASS_TEXT, 0);
        try {
            Whisper.WhisperListener listener = installListener(fixture.service);
            EditorInfo replacementEditor = editor(InputType.TYPE_CLASS_TEXT, 0);
            replacementEditor.packageName = "com.example.replacement";
            fixture.service.focus(replacementEditor, fixture.connection);

            fixture.service.onStartInput(replacementEditor, true);
            listener.onResultReceived(result("stale after editor restart"));

            assertEquals(0, fixture.connection.commitCount);
            assertEquals("", fixture.connection.text());
        } finally {
            fixture.controller.destroy();
        }
    }

    @Test public void sameEditorRestartThatBecomesPrivateInvalidatesPendingResult()
            throws Exception {
        ServiceFixture fixture = startService(InputType.TYPE_CLASS_TEXT, 0);
        try {
            Whisper.WhisperListener listener = installListener(fixture.service);
            EditorInfo privateEditor = editor(InputType.TYPE_CLASS_TEXT,
                    EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
            fixture.service.focus(privateEditor, fixture.connection);

            fixture.service.onStartInput(privateEditor, true);
            listener.onResultReceived(result("must stay out of private editor"));

            assertEquals(0, fixture.connection.commitCount);
            assertEquals("", fixture.connection.text());
        } finally {
            fixture.controller.destroy();
        }
    }

    @Test public void restartingFalseInvalidatesPendingResultEvenForSameEditor()
            throws Exception {
        ServiceFixture fixture = startService(InputType.TYPE_CLASS_TEXT, 0);
        try {
            Whisper.WhisperListener listener = installListener(fixture.service);
            EditorInfo restartedEditor = editor(InputType.TYPE_CLASS_TEXT, 0);
            fixture.service.focus(restartedEditor, fixture.connection);

            fixture.service.onStartInput(restartedEditor, false);
            listener.onResultReceived(result("new input session"));

            assertEquals(0, fixture.connection.commitCount);
            assertEquals("", fixture.connection.text());
        } finally {
            fixture.controller.destroy();
        }
    }

    @Test public void resultDeliveryRejectsEditorThatBecameStale() throws Exception {
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

            assertFalse(oldListener.onResultDelivery(result("late result")));

            assertEquals(0, oldConnection.commitCount);
            assertEquals(0, replacementConnection.commitCount);
            assertEquals("", replacementConnection.text());
        } finally {
            fixture.controller.destroy();
        }
    }

    @Test public void hostSelectionUpdateWithinSameEditorPreservesDictation()
            throws Exception {
        ServiceFixture fixture = startService(InputType.TYPE_CLASS_TEXT, 0);
        try {
            Whisper.WhisperListener listener = installListener(fixture.service);
            setBooleanField(fixture.service, "acceptRecording", true);

            fixture.service.onUpdateSelection(0, 0, 1, 1, -1, -1);
            listener.onResultReceived(result("continued after selection moved"));

            assertEquals(1, fixture.connection.commitCount);
            assertEquals("continued after selection moved ", fixture.connection.text());
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

    @Test public void resultDeliveryRejectsPasswordEditor() throws Exception {
        ServiceFixture fixture = startService(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, 0);
        try {
            Whisper.WhisperListener listener = installListener(fixture.service);

            assertFalse(listener.onResultDelivery(result("private")));

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

    @Test public void resultDeliveryRejectsNoPersonalizedLearningEditor() throws Exception {
        ServiceFixture fixture = startService(InputType.TYPE_CLASS_TEXT,
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        try {
            Whisper.WhisperListener listener = installListener(fixture.service);

            assertFalse(listener.onResultDelivery(result("private")));

            assertEquals(0, fixture.connection.commitCount);
            assertEquals("", fixture.connection.text());
        } finally {
            fixture.controller.destroy();
        }
    }

    private static ServiceFixture startService(int inputType, int imeOptions) {
        return startService(inputType, imeOptions, true);
    }

    private static ServiceFixture startService(int inputType, int imeOptions, boolean acceptCommit) {
        ServiceController<EditorTestService> controller =
                Robolectric.buildService(EditorTestService.class).create();
        EditorTestService service = controller.get();
        View inputView = service.onCreateInputView();
        EditorInfo editor = editor(inputType, imeOptions);
        RecordingInputConnection connection = new RecordingInputConnection(acceptCommit);
        service.focus(editor, connection);
        service.onStartInput(editor, false);
        service.onStartInputView(editor, false);
        return new ServiceFixture(controller, service, inputView, connection);
    }

    private static EditorInfo editor(int inputType, int imeOptions) {
        EditorInfo editor = new EditorInfo();
        editor.inputType = inputType;
        editor.imeOptions = imeOptions;
        editor.packageName = "com.example.editor";
        editor.fieldId = 17;
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
        private final boolean acceptCommit;

        RecordingInputConnection() {
            this(true);
        }

        RecordingInputConnection(boolean acceptCommit) {
            super(new View(RuntimeEnvironment.getApplication()), true);
            this.acceptCommit = acceptCommit;
        }

        @Override public boolean commitText(CharSequence text, int newCursorPosition) {
            commitCount++;
            if (!acceptCommit) return false;
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
