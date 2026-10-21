package com.devilved.tonetunner.asr;

import android.content.Context;
import android.os.Looper;

import com.devilved.tonetunner.cloud.CloudClient;
import com.devilved.tonetunner.cloud.Provider;
import com.devilved.tonetunner.cloud.ProviderConfig;
import com.devilved.tonetunner.cloud.SecretStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Cloud windows too short for a provider must not fail an otherwise complete dictation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = {WhisperCloudWindowTest.Transport.class, WhisperCloudWindowTest.Keys.class},
        instrumentedPackages = {"com.devilved.tonetunner.cloud"})
@LooperMode(LooperMode.Mode.PAUSED)
public class WhisperCloudWindowTest {
    private static final int BYTES_PER_SECOND = PcmRecording.SAMPLE_RATE * PcmRecording.BYTES_PER_SAMPLE;

    private Context context;
    private Whisper whisper;
    private WhisperResult result;
    private String error;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE).edit().clear()
                .putString("transcription.provider", Provider.GROQ.name())
                .putBoolean("cloud.consent", true).commit();
        Transport.windowBytes.clear();
        whisper = new Whisper(context);
        whisper.setListener(new Whisper.WhisperListener() {
            public void onUpdateReceived(String message) { }
            public void onResultReceived(WhisperResult value) { result = value; }
            @Override public void onError(int code, String message) { error = message; }
        });
    }

    @After public void tearDown() {
        whisper.unloadModel();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void subTenthSecondTailAfterASilentHardCutWindowIsNotUploaded() throws Exception {
        // Window 1 (0-28 s) has text, so window 2 starts 0.5 s earlier and hard-cuts at 55.5 s.
        // Window 2 has no speech, so no context is replayed and only a 20 ms tail remains.
        PcmRecording recording = loudRecording(55L * BYTES_PER_SECOND + BYTES_PER_SECOND / 2
                + BYTES_PER_SECOND / 50);

        whisper.start(recording);
        drainWorker();

        assertNull("A provider rejection of the tail must not fail the dictation: " + error, error);
        assertNotNull(result);
        assertEquals("First window text.", result.getResult());
        assertEquals(2, Transport.windowBytes.size());
        for (int bytes : Transport.windowBytes) assertTrue(bytes >= Whisper.MIN_CLOUD_WINDOW_BYTES);
    }

    private PcmRecording loudRecording(long totalBytes) throws Exception {
        PcmRecording recording = PcmRecording.create(new File(context.getNoBackupFilesDir(), "fixture-spools"));
        byte[] buffer = new byte[BYTES_PER_SECOND];
        for (int i = 0; i < buffer.length; i += 2) {
            int sample = ((i / 2) % 2 == 0) ? 2048 : -2048;
            buffer[i] = (byte) sample;
            buffer[i + 1] = (byte) (sample >> 8);
        }
        for (long remaining = totalBytes; remaining > 0; ) {
            int count = (int) Math.min(buffer.length, remaining);
            recording.write(buffer, 0, count);
            remaining -= count;
        }
        recording.finish();
        return recording;
    }

    private void drainWorker() throws Exception {
        Field field = Whisper.class.getDeclaredField("worker");
        field.setAccessible(true);
        ((ExecutorService) field.get(whisper)).submit(() -> { }).get(10, TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** Behaves like OpenAI: uploads under 0.1 s are rejected. */
    @Implements(value = CloudClient.class, isInAndroidSdk = false)
    public static class Transport {
        static final List<Integer> windowBytes = new CopyOnWriteArrayList<>();

        @Implementation public CloudClient.Transcript transcribe(byte[] pcm, ProviderConfig config,
                String language, boolean translate) throws IOException {
            windowBytes.add(pcm.length);
            if (pcm.length < BYTES_PER_SECOND / 10) {
                throw new IOException("Provider rejected the request. Check the model name, endpoint and account access.");
            }
            if (windowBytes.size() == 1) return new CloudClient.Transcript("First window text.", "en");
            throw new CloudClient.NoSpeechException();
        }
    }

    @Implements(value = SecretStore.class, isInAndroidSdk = false)
    public static class Keys {
        @Implementation protected void __constructor__(Context context) { }
        @Implementation protected String getKey(Provider provider, String stage, String endpoint) {
            return "test-key";
        }
    }
}
