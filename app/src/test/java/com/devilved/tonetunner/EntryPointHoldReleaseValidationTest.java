package com.devilved.tonetunner;

import android.Manifest;
import android.content.Context;
import android.os.Looper;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;

import androidx.preference.PreferenceManager;

import com.devilved.tonetunner.cloud.Provider;
import com.devilved.tonetunner.cloud.SecretStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** A released push-to-talk hold must invalidate an async cloud start before it can open the mic. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = {
        EntryPointTranslationPreflightTest.RecorderShadow.class,
        EntryPointHoldReleaseValidationTest.SecretStoreShadow.class
})
public class EntryPointHoldReleaseValidationTest {
    private static final long DOWN_TIME = 1000L;
    private static final long HELD_UP_TIME = DOWN_TIME + 400L;
    private static final long SHORT_TAP_DOWN_TIME = DOWN_TIME + 1000L;
    private static final long SHORT_TAP_UP_TIME = SHORT_TAP_DOWN_TIME + 100L;

    @Before public void clearSettings() throws Exception {
        Context application = RuntimeEnvironment.getApplication();
        application.getSharedPreferences("ai_settings", Context.MODE_PRIVATE).edit().clear().commit();
        PreferenceManager.getDefaultSharedPreferences(application).edit()
                .clear().putBoolean("imeModeAuto", false).commit();
        EntryPointTranslationPreflightTest.RecorderShadow.reset();
        SecretStoreShadow.apiKey = "";
        Field translate = WhisperInputMethodService.class.getDeclaredField("translate");
        translate.setAccessible(true);
        translate.setBoolean(null, false);
    }

    @Test public void mainActivityDoesNotStartMicrophoneAfterHoldReleasesDuringValidation()
            throws Exception {
        configureValidCloudCredentials();
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        BlockedWorker blocked = null;
        try {
            Shadows.shadowOf(RuntimeEnvironment.getApplication())
                    .grantPermissions(Manifest.permission.RECORD_AUDIO);
            ExecutorService executor = entrypointExecutor(activity, MainActivity.class);
            blocked = block(executor);
            View button = activity.findViewById(R.id.btnRecord);
            sendTouch(button, MotionEvent.ACTION_DOWN, DOWN_TIME, DOWN_TIME);
            assertTrue("Cloud credential validation should be pending", state(activity,
                    MainActivity.class, "validating"));

            sendTouch(button, MotionEvent.ACTION_UP, DOWN_TIME, HELD_UP_TIME);
            assertFalse("A held release should cancel validation", state(activity,
                    MainActivity.class, "validating"));

            blocked.releaseAndDrain();
            assertFalse("Late validation must not open the microphone", EntryPointTranslationPreflightTest
                    .RecorderShadow.started);
            assertFalse(state(activity, MainActivity.class, "recording"));
            assertFalse(state(activity, MainActivity.class, "sessionActive"));

            blocked = block(executor);
            sendTouch(button, MotionEvent.ACTION_DOWN, SHORT_TAP_DOWN_TIME, SHORT_TAP_DOWN_TIME);
            sendTouch(button, MotionEvent.ACTION_UP, SHORT_TAP_DOWN_TIME, SHORT_TAP_UP_TIME);
            assertTrue("A short tap should leave validation active", state(activity,
                    MainActivity.class, "validating"));
            blocked.releaseAndDrain();
            assertTrue("The short tap should still start capture after validation",
                    EntryPointTranslationPreflightTest.RecorderShadow.started);
        } finally {
            if (blocked != null) blocked.release();
            controller.destroy();
        }
    }

    @Test public void recognizeActivityDoesNotStartMicrophoneAfterHoldReleasesDuringValidation()
            throws Exception {
        configureValidCloudCredentials();
        ActivityController<WhisperRecognizeActivity> controller =
                Robolectric.buildActivity(WhisperRecognizeActivity.class).setup();
        WhisperRecognizeActivity activity = controller.get();
        BlockedWorker blocked = null;
        try {
            Shadows.shadowOf(RuntimeEnvironment.getApplication())
                    .grantPermissions(Manifest.permission.RECORD_AUDIO);
            ExecutorService executor = entrypointExecutor(activity, WhisperRecognizeActivity.class);
            blocked = block(executor);
            View button = activity.findViewById(R.id.btnRecord);
            sendTouch(button, MotionEvent.ACTION_DOWN, DOWN_TIME, DOWN_TIME);
            assertTrue("Cloud credential validation should be pending", state(activity,
                    WhisperRecognizeActivity.class, "validating"));

            sendTouch(button, MotionEvent.ACTION_UP, DOWN_TIME, HELD_UP_TIME);
            assertFalse("A held release should cancel validation", state(activity,
                    WhisperRecognizeActivity.class, "validating"));

            blocked.releaseAndDrain();
            assertFalse("Late validation must not open the microphone", EntryPointTranslationPreflightTest
                    .RecorderShadow.started);
            assertFalse(state(activity, WhisperRecognizeActivity.class, "recording"));
            assertFalse(state(activity, WhisperRecognizeActivity.class, "sessionActive"));

            blocked = block(executor);
            sendTouch(button, MotionEvent.ACTION_DOWN, SHORT_TAP_DOWN_TIME, SHORT_TAP_DOWN_TIME);
            sendTouch(button, MotionEvent.ACTION_UP, SHORT_TAP_DOWN_TIME, SHORT_TAP_UP_TIME);
            assertTrue("A short tap should leave validation active", state(activity,
                    WhisperRecognizeActivity.class, "validating"));
            blocked.releaseAndDrain();
            assertTrue("The short tap should still start capture after validation",
                    EntryPointTranslationPreflightTest.RecorderShadow.started);
        } finally {
            if (blocked != null) blocked.release();
            controller.destroy();
        }
    }

    @Test public void inputMethodDoesNotStartMicrophoneAfterHoldReleasesDuringValidation()
            throws Exception {
        configureValidCloudCredentials();
        ServiceController<EntryPointTranslationPreflightTest.TestInputMethodService> controller =
                Robolectric.buildService(EntryPointTranslationPreflightTest.TestInputMethodService.class)
                        .create();
        EntryPointTranslationPreflightTest.TestInputMethodService service = controller.get();
        BlockedWorker blocked = null;
        try {
            Shadows.shadowOf(RuntimeEnvironment.getApplication())
                    .grantPermissions(Manifest.permission.RECORD_AUDIO);
            View inputView = service.onCreateInputView();
            EditorInfo editor = editor();
            service.focus(editor, null);
            service.onStartInput(editor, false);
            service.onStartInputView(editor, false);
            ExecutorService executor = entrypointExecutor(service, WhisperInputMethodService.class);
            blocked = block(executor);
            View button = inputView.findViewById(R.id.btnRecord);
            sendTouch(button, MotionEvent.ACTION_DOWN, DOWN_TIME, DOWN_TIME);
            assertTrue("Cloud credential validation should be pending", state(service,
                    WhisperInputMethodService.class, "validating"));

            sendTouch(button, MotionEvent.ACTION_UP, DOWN_TIME, HELD_UP_TIME);
            assertFalse("A held release should cancel validation", state(service,
                    WhisperInputMethodService.class, "validating"));

            blocked.releaseAndDrain();
            assertFalse("Late validation must not open the microphone", EntryPointTranslationPreflightTest
                    .RecorderShadow.started);
            assertFalse(state(service, WhisperInputMethodService.class, "acceptRecording"));
            assertFalse(state(service, WhisperInputMethodService.class, "processing"));

            blocked = block(executor);
            sendTouch(button, MotionEvent.ACTION_DOWN, SHORT_TAP_DOWN_TIME, SHORT_TAP_DOWN_TIME);
            sendTouch(button, MotionEvent.ACTION_UP, SHORT_TAP_DOWN_TIME, SHORT_TAP_UP_TIME);
            assertTrue("A short tap should leave validation active", state(service,
                    WhisperInputMethodService.class, "validating"));
            blocked.releaseAndDrain();
            assertTrue("The short tap should still start capture after validation",
                    EntryPointTranslationPreflightTest.RecorderShadow.started);
        } finally {
            if (blocked != null) blocked.release();
            controller.destroy();
        }
    }

    private static void configureValidCloudCredentials() {
        SecretStoreShadow.apiKey = "test-key-for-validation";
        RuntimeEnvironment.getApplication().getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
                .edit()
                .putString("transcription.provider", Provider.OPENAI.name())
                .putString("transcription.endpoint", Provider.OPENAI.transcriptionEndpoint())
                .putString("transcription.model", "whisper-1")
                .putBoolean("enhancement.enabled", false)
                .putString("enhancement.provider", Provider.LOCAL.name())
                .putString("enhancement.endpoint", "")
                .putString("enhancement.model", "")
                .putBoolean("cloud.consent", true)
                .commit();
    }

    private static ExecutorService entrypointExecutor(Object target, Class<?> owner) throws Exception {
        return (ExecutorService) field(owner, "entrypointTasks").get(target);
    }

    private static BlockedWorker block(ExecutorService executor) throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executor.execute(() -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        boolean started = entered.await(5, TimeUnit.SECONDS);
        if (!started) release.countDown();
        assertTrue("The executor blocker should be running before validation starts", started);
        return new BlockedWorker(executor, release);
    }

    private static void sendTouch(View view, int action, long downTime, long eventTime) {
        MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, 1f, 1f, 0);
        try {
            assertTrue("The record control should consume its touch event", view.dispatchTouchEvent(event));
        } finally {
            event.recycle();
        }
    }

    private static EditorInfo editor() {
        EditorInfo editor = new EditorInfo();
        editor.inputType = InputType.TYPE_CLASS_TEXT;
        editor.packageName = "com.example.editor";
        editor.fieldId = 1;
        return editor;
    }

    private static boolean state(Object target, Class<?> owner, String name) throws Exception {
        return field(owner, name).getBoolean(target);
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final class BlockedWorker {
        private final ExecutorService executor;
        private final CountDownLatch release;

        BlockedWorker(ExecutorService executor, CountDownLatch release) {
            this.executor = executor;
            this.release = release;
        }

        void release() { release.countDown(); }

        void releaseAndDrain() throws InterruptedException {
            CountDownLatch drained = new CountDownLatch(1);
            executor.execute(drained::countDown);
            release.countDown();
            assertTrue("Credential validation should finish before assertions",
                    drained.await(5, TimeUnit.SECONDS));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
    }

    @Implements(value = SecretStore.class, isInAndroidSdk = false)
    public static class SecretStoreShadow {
        static volatile String apiKey = "";

        @Implementation protected void __constructor__(Context context) { }

        @Implementation protected String getKey(Provider provider, String stage, String endpoint)
                throws IOException {
            return apiKey;
        }

        @Implementation protected boolean hasKey(Provider provider, String stage, String endpoint)
                throws IOException {
            return !apiKey.isEmpty();
        }
    }
}
