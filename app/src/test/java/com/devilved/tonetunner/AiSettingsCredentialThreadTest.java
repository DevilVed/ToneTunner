package com.devilved.tonetunner;

import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import com.devilved.tonetunner.cloud.Provider;
import com.devilved.tonetunner.cloud.SecretStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Keystore-backed reads must not block the settings screen's main thread. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = AiSettingsCredentialThreadTest.SecretStoreShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
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

    @After public void finishAcceptedSaves() throws Exception {
        SecretStoreShadow.releaseFirst.countDown();
        Field writes = AiSettingsActivity.class.getDeclaredField("settingsWrites");
        writes.setAccessible(true);
        ((ExecutorService) writes.get(null)).submit(() -> { }).get(10, TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void acceptedSavesSurviveRotationAndCommitInUserSubmissionOrder() throws Exception {
        RuntimeEnvironment.getApplication().getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
                .edit().putString("transcription.provider", Provider.GROQ.name())
                .putBoolean("cloud.consent", true).commit();
        SecretStoreShadow.blockFirstCredentialRead = true;
        try (ActivityController<AiSettingsActivity> controller =
                     Robolectric.buildActivity(AiSettingsActivity.class).setup()) {
            AiSettingsActivity first = controller.get();
            prompt(first).setText("Earlier accepted edit");
            findText(first.findViewById(android.R.id.content), first.getString(R.string.ai_save))
                    .performClick();
            assertTrue("The first save must reach its credential operation",
                    SecretStoreShadow.firstEntered.await(10, TimeUnit.SECONDS));

            controller.recreate();
            AiSettingsActivity replacement = controller.get();
            prompt(replacement).setText("Latest accepted edit");
            findText(replacement.findViewById(android.R.id.content), replacement.getString(R.string.ai_save))
                    .performClick();

            assertFalse("Rotation must not interrupt an accepted credential write",
                    SecretStoreShadow.firstInterrupted);
            assertFalse("A newer save must wait until the earlier accepted save finishes",
                    SecretStoreShadow.secondEntered.await(1, TimeUnit.SECONDS));
            SecretStoreShadow.releaseFirst.countDown();
            finishAcceptedSaves();
            assertEquals("Latest accepted edit", RuntimeEnvironment.getApplication()
                    .getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
                    .getString("enhancement.prompt", ""));
        } finally {
            SecretStoreShadow.releaseFirst.countDown();
        }
    }

    private static TextView prompt(AiSettingsActivity activity) throws Exception {
        Field prompt = AiSettingsActivity.class.getDeclaredField("promptInput");
        prompt.setAccessible(true);
        return (TextView) prompt.get(activity);
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
        static final AtomicInteger credentialReads = new AtomicInteger();
        static volatile boolean blockFirstCredentialRead;
        static volatile boolean firstInterrupted;
        static CountDownLatch firstEntered = new CountDownLatch(1);
        static CountDownLatch secondEntered = new CountDownLatch(1);
        static CountDownLatch releaseFirst = new CountDownLatch(1);

        static void reset() {
            mainThreadKeyReads.set(0);
            credentialReads.set(0);
            blockFirstCredentialRead = false;
            firstInterrupted = false;
            firstEntered = new CountDownLatch(1);
            secondEntered = new CountDownLatch(1);
            releaseFirst = new CountDownLatch(1);
        }

        @Implementation protected void __constructor__(Context context) { }

        @Implementation protected String getKey(Provider provider, String stage, String endpoint)
                throws IOException {
            if (Looper.myLooper() == Looper.getMainLooper()) mainThreadKeyReads.incrementAndGet();
            if (!blockFirstCredentialRead) return "";
            if (credentialReads.incrementAndGet() == 1) {
                firstEntered.countDown();
                boolean released = false;
                while (!released) {
                    try { released = releaseFirst.await(10, TimeUnit.SECONDS); }
                    catch (InterruptedException ignored) { firstInterrupted = true; continue; }
                    if (!released) throw new IOException("Synthetic save timed out");
                }
            } else {
                secondEntered.countDown();
            }
            return "synthetic-existing-key";
        }

        @Implementation protected boolean hasKey(Provider provider, String stage, String endpoint)
                throws IOException {
            if (Looper.myLooper() == Looper.getMainLooper()) mainThreadKeyReads.incrementAndGet();
            return false;
        }
    }
}
