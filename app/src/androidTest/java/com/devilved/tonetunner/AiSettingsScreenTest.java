package com.devilved.tonetunner;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

/** Opens AI settings on a real Android runtime: Robolectric does not lay out or draw the screen. */
@RunWith(AndroidJUnit4.class)
public class AiSettingsScreenTest {

    @Test public void aiSettingsOpensFromTheMainScreenAndDraws() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Instrumentation.ActivityMonitor monitor =
                instrumentation.addMonitor(AiSettingsActivity.class.getName(), null, false);
        try (ActivityScenario<MainActivity> main = ActivityScenario.launch(MainActivity.class)) {
            main.onActivity(activity -> activity.findViewById(R.id.btnAiSettings).performClick());
            Activity settings = instrumentation.waitForMonitorWithTimeout(monitor, 15_000);
            assertNotNull("Tapping AI settings must open the settings screen", settings);
            try {
                assertLaidOutAndDrawable(instrumentation, settings);
            } finally {
                instrumentation.runOnMainSync(settings::finish);
            }
        } finally {
            instrumentation.removeMonitor(monitor);
        }
    }

    @Test public void settingsOpensFromTheMainScreenAndLeadsToAiSettings() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Instrumentation.ActivityMonitor settingsMonitor =
                instrumentation.addMonitor(SettingsActivity.class.getName(), null, false);
        Instrumentation.ActivityMonitor aiMonitor =
                instrumentation.addMonitor(AiSettingsActivity.class.getName(), null, false);
        try (ActivityScenario<MainActivity> main = ActivityScenario.launch(MainActivity.class)) {
            main.onActivity(activity -> activity.findViewById(R.id.btnSettings).performClick());
            Activity settings = instrumentation.waitForMonitorWithTimeout(settingsMonitor, 15_000);
            assertNotNull("Tapping Settings must open the settings screen", settings);
            try {
                assertLaidOutAndDrawable(instrumentation, settings);
                instrumentation.runOnMainSync(() -> {
                    PreferenceFragmentCompat fragment = (PreferenceFragmentCompat)
                            ((AppCompatActivity) settings).getSupportFragmentManager()
                                    .findFragmentById(R.id.settings_container);
                    assertNotNull(fragment);
                    Preference aiPreference = fragment.findPreference("ai_settings");
                    assertNotNull(aiPreference);
                    aiPreference.getOnPreferenceClickListener().onPreferenceClick(aiPreference);
                });
                Activity ai = instrumentation.waitForMonitorWithTimeout(aiMonitor, 15_000);
                assertNotNull("AI providers must open from Settings", ai);
                try {
                    assertLaidOutAndDrawable(instrumentation, ai);
                } finally {
                    instrumentation.runOnMainSync(ai::finish);
                }
            } finally {
                instrumentation.runOnMainSync(settings::finish);
            }
        } finally {
            instrumentation.removeMonitor(settingsMonitor);
            instrumentation.removeMonitor(aiMonitor);
        }
    }

    @Test public void aiSettingsOpensAndDrawsInDarkMode() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(() ->
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES));
        try (ActivityScenario<AiSettingsActivity> scenario = ActivityScenario.launch(AiSettingsActivity.class)) {
            Activity[] settings = new Activity[1];
            scenario.onActivity(activity -> settings[0] = activity);
            assertLaidOutAndDrawable(instrumentation, settings[0]);
        } finally {
            instrumentation.runOnMainSync(() ->
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM));
        }
    }

    private static void assertLaidOutAndDrawable(Instrumentation instrumentation, Activity settings) {
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> {
            View decor = settings.getWindow().getDecorView();
            ViewGroup content = decor.findViewById(android.R.id.content);
            assertNotNull(content);
            assertTrue("The settings page must have content", content.getChildCount() > 0);
            View page = content.getChildAt(0);
            assertTrue("The settings page must be laid out", page.getWidth() > 0 && page.getHeight() > 0);
            // A full software draw surfaces drawable/theme failures as a test failure, not a crash.
            Bitmap bitmap = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
            try {
                decor.draw(new Canvas(bitmap));
            } finally {
                bitmap.recycle();
            }
        });
    }
}
