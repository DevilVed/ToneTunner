package com.devilved.tonetunner.asr;

import android.content.Context;
import android.os.Looper;

import com.devilved.tonetunner.cloud.CloudClient;
import com.devilved.tonetunner.metrics.SessionMetric;
import com.devilved.tonetunner.metrics.UsageStore;
import com.devilved.tonetunner.voice_translation.neural_networks.voice.Recognizer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** The shared dictation pipeline records one usage row per dictation, completed or failed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class WhisperUsageTest {
    private Context context;
    private Whisper whisper;
    private boolean fail;
    private long readyAt;
    private long createdAt;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE).edit().clear()
                .putString("transcription.provider", "LOCAL").putBoolean("cloud.consent", true).commit();
        UsageStore.get(context).clear();
        whisper = new Whisper(context, ignored -> {
            createdAt = System.nanoTime();
            return new Whisper.LocalTranscriber() {
                public CloudClient.Transcript transcribe(byte[] pcm, String language, Recognizer.Action action)
                        throws IOException {
                    readyAt = System.nanoTime();
                    if (fail) throw new IOException("Synthetic local failure");
                    return new CloudClient.Transcript("Four words right here.", "en");
                }
                public void close() { }
                @Override public long createdAtNanos() { return createdAt; }
                @Override public long readyAtNanos() { return readyAt; }
            };
        });
        whisper.setListener(new Whisper.WhisperListener() {
            public void onUpdateReceived(String message) { }
            public void onResultReceived(WhisperResult result) { }
            public void onError(String message) { }
            public void onError(int code, String message) { }
        });
    }

    @After public void tearDown() {
        whisper.unloadModel();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void completedDictationIsRecordedWithoutItsText() throws Exception {
        PcmRecording recording = recording(2);
        recording.setMicCapture(new MicCapture("builtin", false, "maximum", "maximum", -35, -4, 0, 14));
        startAndDrain(recording);

        List<SessionMetric> rows = UsageStore.get(context).all();
        assertEquals(1, rows.size());
        SessionMetric m = rows.get(0);
        assertEquals(SessionMetric.COMPLETED, m.status);
        assertEquals(4, m.words);
        assertEquals(2_000, m.audioMs);
        assertEquals("LOCAL", m.transcriptionProvider);
        assertEquals(Whisper.LOCAL_MODEL_NAME, m.transcriptionModel);
        assertEquals(1, m.transcriptionRequests);
        assertEquals("on-device transcription is free", 0, m.billedAudioSeconds, 0);
        assertEquals("builtin", m.micDevice);
        assertEquals(14, m.micGainDb, 0);
        assertNull(m.enhancementModel);
        assertTrue(m.processingMs >= 0);
    }

    @Test public void failedDictationIsRecordedAsFailed() throws Exception {
        fail = true;
        startAndDrain(recording(1));
        List<SessionMetric> rows = UsageStore.get(context).all();
        assertEquals(1, rows.size());
        assertEquals(SessionMetric.FAILED, rows.get(0).status);
        assertEquals(0, rows.get(0).words);
    }

    private PcmRecording recording(int seconds) throws Exception {
        PcmRecording recording = PcmRecording.create(new File(context.getNoBackupFilesDir(), "fixture-spools"));
        byte[] frame = new byte[32_000];
        for (int i = 0; i < frame.length; i += 2) {
            int sample = ((i / 2) % 2 == 0) ? 2048 : -2048;
            frame[i] = (byte) sample;
            frame[i + 1] = (byte) (sample >> 8);
        }
        for (int i = 0; i < seconds; i++) recording.write(frame, 0, frame.length);
        recording.finish();
        return recording;
    }

    private void startAndDrain(PcmRecording recording) throws Exception {
        whisper.start(recording);
        Field field = Whisper.class.getDeclaredField("worker");
        field.setAccessible(true);
        ((ExecutorService) field.get(whisper)).submit(() -> { }).get(10, TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
}
