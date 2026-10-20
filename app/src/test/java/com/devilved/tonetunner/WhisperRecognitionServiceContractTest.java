package com.devilved.tonetunner;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class WhisperRecognitionServiceContractTest {
    @Test
    public void staleCallbackCannotControlReplacementRequest() {
        Object oldCallback = new Object();
        Object replacementCallback = new Object();

        assertTrue(WhisperRecognitionService.callbackOwnsRequest(replacementCallback, replacementCallback));
        assertFalse(WhisperRecognitionService.callbackOwnsRequest(replacementCallback, oldCallback));
        assertFalse(WhisperRecognitionService.callbackOwnsRequest(replacementCallback, null));
    }

    @Test
    public void serviceSilenceStopDefaultsOnAndCanBeDisabled() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        preferences.edit().remove(WhisperRecognitionService.PREF_AUTO_STOP_AFTER_SILENCE).commit();

        assertTrue(WhisperRecognitionService.isAutoStopAfterSilenceEnabled(preferences));
        preferences.edit().putBoolean(WhisperRecognitionService.PREF_AUTO_STOP_AFTER_SILENCE, false).commit();
        assertFalse(WhisperRecognitionService.isAutoStopAfterSilenceEnabled(preferences));
    }
}
