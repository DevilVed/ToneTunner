package com.devilved.tonetunner;

import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import com.devilved.tonetunner.cloud.Provider;
import com.devilved.tonetunner.cloud.SecretStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/** Keystore-backed reads must not block the settings screen's main thread. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = AiSettingsCredentialThreadTest.SecretStoreShadow.class)
public class AiSettingsCredentialThreadTest {
    @Before public void clearSettings() {
        RuntimeEnvironment.getApplication().getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
                .edit().clear().commit();
        SecretStoreShadow.reset();
    }

    @Test public void openingSettingsDoesNotReadSavedKeysOnMainThread() {
        try (ActivityController<AiSettingsActivity> controller =
                     Robolectric.buildActivity(AiSettingsActivity.class).setup()) {
            assertEquals("Opening the settings UI must not decrypt credentials on its main thread",
                    0, SecretStoreShadow.mainThreadKeyReads.get());
        }
    }

    @Test public void savingSettingsDoesNotReadExistingKeysOnMainThread() {
        try (ActivityController<AiSettingsActivity> controller =
                     Robolectric.buildActivity(AiSettingsActivity.class).setup()) {
            AiSettingsActivity activity = controller.get();
            CheckBox consent = findCheckBox(activity.findViewById(android.R.id.content),
                    activity.getString(R.string.ai_cloud_consent));
            assertNotNull("The cloud-consent checkbox should be present", consent);
            consent.setChecked(true);
            TextView save = findText(activity.findViewById(android.R.id.content),
                    activity.getString(R.string.ai_save));
            assertNotNull("The save button should be present", save);

            save.performClick();

            assertEquals("Saving settings must keep Keystore reads off the main thread",
                    0, SecretStoreShadow.mainThreadKeyReads.get());
        }
    }

    private static CheckBox findCheckBox(View view, String label) {
        if (view instanceof CheckBox && label.contentEquals(((CheckBox) view).getText())) {
            return (CheckBox) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); ++index) {
                CheckBox found = findCheckBox(group.getChildAt(index), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static TextView findText(View view, String label) {
        if (view instanceof TextView && label.contentEquals(((TextView) view).getText())) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); ++index) {
                TextView found = findText(group.getChildAt(index), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Implements(value = SecretStore.class, isInAndroidSdk = false)
    public static class SecretStoreShadow {
        static final AtomicInteger mainThreadKeyReads = new AtomicInteger();

        static void reset() { mainThreadKeyReads.set(0); }

        @Implementation protected void __constructor__(Context context) { }

        @Implementation protected String getKey(Provider provider, String stage, String endpoint)
                throws IOException {
            if (Looper.myLooper() == Looper.getMainLooper()) mainThreadKeyReads.incrementAndGet();
            return "";
        }

        @Implementation protected boolean hasKey(Provider provider, String stage, String endpoint)
                throws IOException {
            if (Looper.myLooper() == Looper.getMainLooper()) mainThreadKeyReads.incrementAndGet();
            return false;
        }
    }
}
