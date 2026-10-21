package com.devilved.tonetunner;

import android.content.Context;
import android.os.Looper;
import android.widget.Button;

import com.devilved.tonetunner.cloud.CloudClient;
import com.devilved.tonetunner.cloud.Provider;
import com.devilved.tonetunner.cloud.ProviderConfig;
import com.devilved.tonetunner.cloud.SecretStore;

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
import org.robolectric.shadows.ShadowToast;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;

/** Keeps provider discovery transport failures distinct from credential-storage failures. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = {
        AiSettingsModelDiscoveryTest.SecretStoreShadow.class,
        AiSettingsModelDiscoveryTest.CloudClientShadow.class
})
@LooperMode(LooperMode.Mode.PAUSED)
public class AiSettingsModelDiscoveryTest {
    @Before public void resetPreferences() {
        RuntimeEnvironment.getApplication().getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
                .edit().clear().putString("transcription.provider", Provider.GROQ.name()).commit();
        CloudClientShadow.calls = 0;
        SecretStoreShadow.failKeyRead = false;
    }

    @Test public void networkFailureUsesDiscoveryErrorInsteadOfSecureStorageError() throws Exception {
        try (ActivityController<AiSettingsActivity> controller =
                     Robolectric.buildActivity(AiSettingsActivity.class).setup()) {
            AiSettingsActivity activity = controller.get();
            Button discover = transcriptionRefreshButton(activity);

            discover.performClick();
            finishModelTasks(activity);

            assertEquals(1, CloudClientShadow.calls);
            assertEquals(activity.getString(R.string.ai_model_discovery_failed),
                    ShadowToast.getTextOfLatestToast());
        }
    }

    @Test public void keyStoreReadFailureKeepsSecureStorageError() throws Exception {
        SecretStoreShadow.failKeyRead = true;
        try (ActivityController<AiSettingsActivity> controller =
                     Robolectric.buildActivity(AiSettingsActivity.class).setup()) {
            AiSettingsActivity activity = controller.get();
            transcriptionRefreshButton(activity).performClick();
            finishModelTasks(activity);

            assertEquals(0, CloudClientShadow.calls);
            assertEquals(activity.getString(R.string.ai_secure_storage_error),
                    ShadowToast.getTextOfLatestToast());
        }
    }

    private static Button transcriptionRefreshButton(AiSettingsActivity activity) throws Exception {
        Field stageField = AiSettingsActivity.class.getDeclaredField("transcription");
        stageField.setAccessible(true);
        Object stage = stageField.get(activity);
        Field refreshField = stage.getClass().getDeclaredField("refresh");
        refreshField.setAccessible(true);
        return (Button) refreshField.get(stage);
    }

    private static void finishModelTasks(AiSettingsActivity activity) throws Exception {
        Field executorField = AiSettingsActivity.class.getDeclaredField("modelExecutor");
        executorField.setAccessible(true);
        ExecutorService executor = (ExecutorService) executorField.get(activity);
        executor.submit(() -> { }).get(10, TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @Implements(value = SecretStore.class, isInAndroidSdk = false)
    public static class SecretStoreShadow {
        static boolean failKeyRead;

        @Implementation protected void __constructor__(Context context) { }

        @Implementation protected String getKey(Provider provider, String stage, String endpoint)
                throws IOException {
            if (failKeyRead) throw new IOException("Synthetic credential-storage failure");
            return "fixture-key";
        }

        @Implementation protected boolean hasKey(Provider provider, String stage, String endpoint) {
            return false;
        }
    }

    @Implements(value = CloudClient.class, isInAndroidSdk = false)
    public static class CloudClientShadow {
        static int calls;

        @Implementation public List<CloudClient.Model> models(ProviderConfig config, boolean transcription)
                throws IOException {
            calls++;
            throw new IOException("Synthetic provider discovery transport failure");
        }
    }
}
