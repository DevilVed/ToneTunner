package com.devilved.tonetunner;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import com.devilved.tonetunner.asr.Whisper;
import com.devilved.tonetunner.asr.WhisperResult;
import com.devilved.tonetunner.voice_translation.neural_networks.voice.Recognizer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

/** Launch-only checks never start the microphone, local model or a paid API request. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CloudOnboardingTest {
    @Before public void clearSettings() {
        RuntimeEnvironment.getApplication().getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @Test public void mainScreenOpensWithoutStartingLocalModelSetup() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            assertNotNull(activity.findViewById(R.id.btnAiSettings));
            Intent started;
            while ((started = Shadows.shadowOf(activity).getNextStartedActivity()) != null) {
                assertNotEquals(SetupActivity.class.getName(),
                        started.getComponent() == null ? "" : started.getComponent().getClassName());
            }
            activity.findViewById(R.id.btnAiSettings).performClick();
            assertEquals(AiSettingsActivity.class.getName(), Shadows.shadowOf(activity)
                    .getNextStartedActivity().getComponent().getClassName());
        }
    }

    @Test public void settingsProtectsBothCredentialInputsFromScreenshotsAndStateSaving() {
        try (ActivityController<AiSettingsActivity> controller = Robolectric.buildActivity(AiSettingsActivity.class).setup()) {
            AiSettingsActivity activity = controller.get();
            assertTrue((activity.getWindow().getAttributes().flags
                    & WindowManager.LayoutParams.FLAG_SECURE) != 0);
            assertEquals(2, checkCredentialViews(activity.findViewById(android.R.id.content)));
        }
    }

    @Test
    @Config(sdk = 30)
    public void settingsExplicitlyExcludeCredentialInputsFromContentCaptureOnAndroid30() {
        try (ActivityController<AiSettingsActivity> controller =
                     Robolectric.buildActivity(AiSettingsActivity.class).setup()) {
            AiSettingsActivity activity = controller.get();
            assertEquals(2, checkCredentialViews(activity.findViewById(android.R.id.content)));
        }
    }

    @Test public void mainScreenDoesNotDisplayAutoAsADetectedLanguage() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            field(MainActivity.class, "sessionGeneration").setLong(activity, 51L);
            field(MainActivity.class, "sessionActive").setBoolean(activity, true);
            Method ensureWhisper = MainActivity.class.getDeclaredMethod("ensureWhisper", long.class);
            ensureWhisper.setAccessible(true);
            ensureWhisper.invoke(activity, 51L);

            Whisper whisper = (Whisper) field(MainActivity.class, "mWhisper").get(activity);
            Whisper.WhisperListener listener = (Whisper.WhisperListener)
                    field(Whisper.class, "listener").get(whisper);
            listener.onResultReceived(new WhisperResult("recognized words", "auto",
                    Recognizer.ACTION_TRANSCRIBE));

            String status = ((TextView) activity.findViewById(R.id.tvStatus)).getText().toString();
            assertFalse("Automatic language detection is a request mode, not a detected language",
                    status.toLowerCase(java.util.Locale.ROOT).contains(" auto "));
        }
    }

    @Test public void typedApiKeyIsNeverIncludedInSavedActivityState() {
        final String marker = "memory-only-credential-fixture";
        try (ActivityController<AiSettingsActivity> controller =
                     Robolectric.buildActivity(AiSettingsActivity.class).setup()) {
            AiSettingsActivity activity = controller.get();
            typeIntoCredentialViews(activity.findViewById(android.R.id.content), marker);
            Bundle state = new Bundle();

            controller.saveInstanceState(state);

            assertFalse("Credential text must stay out of saved Activity state",
                    bundleContains(state, marker));
        }
    }

    @Test public void unsavedCredentialAndPromptDraftSurviveConfigurationChangeInMemoryOnly()
            throws Exception {
        final String marker = "rotation-only-credential-fixture";
        final String draftPrompt = "Keep this unsaved prompt after rotation";
        try (ActivityController<AiSettingsActivity> controller =
                     Robolectric.buildActivity(AiSettingsActivity.class).setup()) {
            AiSettingsActivity first = controller.get();
            Object transcriptionStage = field(AiSettingsActivity.class, "transcription").get(first);
            EditText keyInput = (EditText) field(transcriptionStage.getClass(), "apiKey")
                    .get(transcriptionStage);
            EditText promptInput = (EditText) field(AiSettingsActivity.class, "promptInput").get(first);
            keyInput.setText(marker);
            promptInput.setText(draftPrompt);

            Bundle state = new Bundle();
            controller.saveInstanceState(state);
            assertFalse("A typed key must not be written into Activity saved state",
                    bundleContains(state, marker));

            controller.recreate();

            AiSettingsActivity recreated = controller.get();
            Object recreatedStage = field(AiSettingsActivity.class, "transcription").get(recreated);
            EditText recreatedKey = (EditText) field(recreatedStage.getClass(), "apiKey")
                    .get(recreatedStage);
            EditText recreatedPrompt = (EditText) field(AiSettingsActivity.class, "promptInput")
                    .get(recreated);
            assertEquals("An unsaved typed key should remain available after rotation",
                    marker, recreatedKey.getText().toString());
            assertEquals("An unsaved prompt should remain available after rotation",
                    draftPrompt, recreatedPrompt.getText().toString());
        }
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void typeIntoCredentialViews(View view, String value) {
        if (view instanceof EditText) {
            EditText input = (EditText) view;
            if ((input.getInputType() & InputType.TYPE_MASK_VARIATION)
                    == InputType.TYPE_TEXT_VARIATION_PASSWORD) input.setText(value);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); ++index)
                typeIntoCredentialViews(group.getChildAt(index), value);
        }
    }

    private static boolean bundleContains(Bundle bundle, String value) {
        android.os.Parcel parcel = android.os.Parcel.obtain();
        try {
            bundle.writeToParcel(parcel, 0);
            byte[] data = parcel.marshall();
            return contains(data, value.getBytes(StandardCharsets.UTF_8))
                    || contains(data, value.getBytes(StandardCharsets.UTF_16LE))
                    || contains(data, value.getBytes(StandardCharsets.UTF_16BE));
        } finally {
            parcel.recycle();
        }
    }

    private static boolean contains(byte[] source, byte[] needle) {
        for (int start = 0; start <= source.length - needle.length; ++start) {
            boolean match = true;
            for (int offset = 0; offset < needle.length; ++offset) {
                if (source[start + offset] != needle[offset]) {
                    match = false;
                    break;
                }
            }
            if (match) return true;
        }
        return false;
    }

    private static int checkCredentialViews(View view) {
        int count = 0;
        if (view instanceof EditText) {
            EditText input = (EditText) view;
            if ((input.getInputType() & InputType.TYPE_MASK_VARIATION)
                    == InputType.TYPE_TEXT_VARIATION_PASSWORD) {
                assertFalse(input.isSaveEnabled());
                assertEquals("", input.getText().toString());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    assertEquals(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS,
                            input.getImportantForContentCapture());
                }
                count++;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); ++index)
                count += checkCredentialViews(group.getChildAt(index));
        }
        return count;
    }
}
