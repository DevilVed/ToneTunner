package com.devilved.tonetunner;

import android.content.Context;
import android.content.Intent;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
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

    private static int checkCredentialViews(View view) {
        int count = 0;
        if (view instanceof EditText) {
            EditText input = (EditText) view;
            if ((input.getInputType() & InputType.TYPE_MASK_VARIATION)
                    == InputType.TYPE_TEXT_VARIATION_PASSWORD) {
                assertFalse(input.isSaveEnabled());
                assertEquals("", input.getText().toString());
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
