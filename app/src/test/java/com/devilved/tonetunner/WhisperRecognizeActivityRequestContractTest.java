package com.devilved.tonetunner;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Looper;
import android.speech.RecognizerIntent;

import androidx.preference.PreferenceManager;

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
import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

/**
 * Checks that each speech request has its own result-capable Activity and its own language/session.
 * The test installs the real lazy Whisper listeners but never starts recording or loads a model.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class WhisperRecognizeActivityRequestContractTest {
    @Before public void resetPreferences() {
        Context application = RuntimeEnvironment.getApplication();
        PreferenceManager.getDefaultSharedPreferences(application).edit()
                .clear().putString("language", "ja").putBoolean("imeModeAuto", false).commit();
        application.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @Test public void speechActivityUsesStandardLaunchModeToPreserveEachCallerResultRoute()
            throws Exception {
        ActivityInfo info = RuntimeEnvironment.getApplication().getPackageManager().getActivityInfo(
                new ComponentName(RuntimeEnvironment.getApplication(), WhisperRecognizeActivity.class), 0);

        assertEquals(ActivityInfo.LAUNCH_MULTIPLE, info.launchMode);
    }

    @Test public void externalLanguageTagsMapAliasesAndRejectMalformedSuffixes() {
        assertEquals("he", WhisperRecognizeActivity.sanitizeLanguageCode("iw_IL"));
        assertEquals("id", WhisperRecognizeActivity.sanitizeLanguageCode("in-ID"));
        assertEquals("yi", WhisperRecognizeActivity.sanitizeLanguageCode("ji"));
        assertEquals("yue", WhisperRecognizeActivity.sanitizeLanguageCode("yue-Hant-TW"));
        assertEquals("haw", WhisperRecognizeActivity.sanitizeLanguageCode("haw"));
        assertEquals("en", WhisperRecognizeActivity.sanitizeLanguageCode("eng"));
        assertEquals("auto", WhisperRecognizeActivity.sanitizeLanguageCode("en-../../evil"));
        assertEquals("auto", WhisperRecognizeActivity.sanitizeLanguageCode(null));
    }

    @Test public void separateRecognitionRequestsKeepSanitizedLanguagesAndResultsIndependent()
            throws Exception {
        ActivityController<WhisperRecognizeActivity> firstController =
                Robolectric.buildActivity(WhisperRecognizeActivity.class,
                        recognitionIntent("FR-CA")).setup();
        ActivityController<WhisperRecognizeActivity> secondController = null;
        try {
            WhisperRecognizeActivity first = firstController.get();
            assertEquals("fr", stringField(first, "langCode"));
            Whisper.WhisperListener firstListener = startSession(first, 41L);

            secondController = Robolectric.buildActivity(WhisperRecognizeActivity.class,
                    recognitionIntent("PT_br")).setup();
            WhisperRecognizeActivity second = secondController.get();
            assertEquals("pt", stringField(second, "langCode"));
            Whisper.WhisperListener secondListener = startSession(second, 73L);

            firstListener.onResultReceived(result("first caller"));
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertEquals(Activity.RESULT_OK, Shadows.shadowOf(first).getResultCode());
            assertEquals("first caller", firstResult(first));
            assertFalse(second.isFinishing());
            assertEquals(Activity.RESULT_CANCELED, Shadows.shadowOf(second).getResultCode());

            secondListener.onResultReceived(result("second caller"));
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertEquals(Activity.RESULT_OK, Shadows.shadowOf(second).getResultCode());
            assertEquals("second caller", firstResult(second));
        } finally {
            if (secondController != null) secondController.destroy();
            firstController.destroy();
        }
    }

    private static Whisper.WhisperListener startSession(WhisperRecognizeActivity activity,
            long token) throws Exception {
        field(WhisperRecognizeActivity.class, "sessionGeneration").setLong(activity, token);
        field(WhisperRecognizeActivity.class, "sessionActive").setBoolean(activity, true);
        Method ensureWhisper = WhisperRecognizeActivity.class.getDeclaredMethod(
                "ensureWhisper", long.class);
        ensureWhisper.setAccessible(true);
        ensureWhisper.invoke(activity, token);

        Whisper whisper = (Whisper) field(WhisperRecognizeActivity.class, "mWhisper").get(activity);
        assertNotNull(whisper);
        Whisper.WhisperListener listener = (Whisper.WhisperListener)
                field(Whisper.class, "listener").get(whisper);
        assertNotNull(listener);
        return listener;
    }

    private static String firstResult(WhisperRecognizeActivity activity) {
        Intent result = Shadows.shadowOf(activity).getResultIntent();
        assertNotNull(result);
        ArrayList<String> results = result.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        assertNotNull(results);
        assertEquals(1, results.size());
        return results.get(0);
    }

    private static Intent recognitionIntent(String language) {
        return new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language);
    }

    private static String stringField(Object target, String name) throws Exception {
        return (String) field(WhisperRecognizeActivity.class, name).get(target);
    }

    private static WhisperResult result(String text) {
        return new WhisperResult(text, "en", Recognizer.ACTION_TRANSCRIBE);
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
