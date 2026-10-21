package com.devilved.tonetunner;

import android.content.Intent;

import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/** The main screen has a Settings entry that reaches every detailed settings screen. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SettingsScreenTest {

    @Test public void mainScreenSettingsButtonOpensSettings() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            activity.findViewById(R.id.btnSettings).performClick();
            Intent started = Shadows.shadowOf(activity).getNextStartedActivity();
            assertNotNull(started);
            assertEquals(SettingsActivity.class.getName(), started.getComponent().getClassName());
        }
    }

    @Test public void settingsLinksToEachDetailedScreen() {
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class).setup()) {
            SettingsActivity activity = controller.get();
            PreferenceFragmentCompat fragment = (PreferenceFragmentCompat)
                    activity.getSupportFragmentManager().findFragmentById(R.id.settings_container);
            assertNotNull(fragment);
            assertOpens(activity, fragment, "ai_settings", AiSettingsActivity.class.getName());
            assertOpens(activity, fragment, "local_model", SetupActivity.class.getName());
            assertOpens(activity, fragment, "voice_input", WhisperRecognitionServiceSettingsActivity.class.getName());
            assertNotNull("Hands-free mode is a setting", fragment.findPreference("imeModeAuto"));
            assertNotNull(fragment.findPreference("keyboard"));
            Preference version = fragment.findPreference("version");
            assertNotNull(version);
            assertFalse(String.valueOf(version.getSummary()).isEmpty());
        }
    }

    private static void assertOpens(SettingsActivity activity, PreferenceFragmentCompat fragment,
                                    String key, String expectedClass) {
        Preference preference = fragment.findPreference(key);
        assertNotNull(key, preference);
        assertTrue(preference.getOnPreferenceClickListener().onPreferenceClick(preference));
        Intent started = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(key + " should open a screen", started);
        assertEquals(expectedClass, started.getComponent().getClassName());
    }
}
