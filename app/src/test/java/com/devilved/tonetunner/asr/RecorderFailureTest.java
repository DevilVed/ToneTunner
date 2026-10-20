package com.devilved.tonetunner.asr;

import android.Manifest;
import android.content.Context;
import android.media.AudioRecord;

import com.konovalov.vad.webrtc.VadWebRTC;
import com.konovalov.vad.webrtc.config.FrameSize;
import com.konovalov.vad.webrtc.config.Mode;
import com.konovalov.vad.webrtc.config.SampleRate;

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
import org.robolectric.shadows.ShadowAudioRecord;
import org.robolectric.shadows.ShadowSystemClock;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Regression coverage for recorder timeouts and microphone read failures without hardware. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = RecorderFailureTest.ShadowVadWebRTC.class)
public class RecorderFailureTest {
    private static final int READS_FOR_36_SECONDS = 1_200; // 480 samples at 16 kHz per read = 30 ms.
    private static final long SOURCE_WAIT_SECONDS = 5;

    @Before public void grantRecordingPermission() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
                .grantPermissions(Manifest.permission.RECORD_AUDIO);
    }

    @After public void resetAudioSource() {
        ShadowAudioRecord.clearSource();
    }

    @Test public void autoStopEndsAfterThirtySecondsOfInitialSilence() throws Exception {
        BlockingQueue<String> messages = new ArrayBlockingQueue<>(8);
        AtomicReference<AudioRecord> audioRecord = new AtomicReference<>();
        SilenceSource source = new SilenceSource(READS_FOR_36_SECONDS);
        installSource(source, audioRecord);

        Recorder recorder = new Recorder(application());
        recorder.setListener(messages::offer);
        try {
            recorder.initVad();
            recorder.start();

            assertEquals(Recorder.MSG_RECORDING, messages.poll(SOURCE_WAIT_SECONDS, TimeUnit.SECONDS));
            String terminalMessage = messages.poll(SOURCE_WAIT_SECONDS, TimeUnit.SECONDS);
            assertNotNull("VAD mode must terminate a capture with no initial speech", terminalMessage);
            assertTrue("the no-speech deadline must represent at least 30 seconds of captured silence",
                    source.capturedDurationMs() >= 30_000L);
            assertFalse("initial silence must not be reported as a completed dictation",
                    Recorder.MSG_RECORDING_DONE.equals(terminalMessage));
            assertTrue("the recorder must stop after its initial-speech deadline", awaitStopped(recorder));
            assertNull("no-speech timeout must not publish a transcript-ready recording",
                    recorder.takeRecording());
            assertMicrophoneReleased(audioRecord.get());
        } finally {
            source.resume();
            recorder.setListener(null);
            recorder.stop();
            recorder.release();
        }
    }

    @Test public void manualCaptureRemainsActivePastTheAutoModeInitialSilenceDeadline() throws Exception {
        BlockingQueue<String> messages = new ArrayBlockingQueue<>(8);
        AtomicReference<AudioRecord> audioRecord = new AtomicReference<>();
        SilenceSource source = new SilenceSource(READS_FOR_36_SECONDS);
        installSource(source, audioRecord);

        Recorder recorder = new Recorder(application());
        recorder.setListener(messages::offer);
        PcmRecording completed = null;
        try {
            recorder.start();

            assertEquals(Recorder.MSG_RECORDING, messages.poll(SOURCE_WAIT_SECONDS, TimeUnit.SECONDS));
            assertTrue("manual mode must remain active after more than 30 seconds of silence",
                    source.limitReached.await(SOURCE_WAIT_SECONDS, TimeUnit.SECONDS));
            assertTrue("only VAD auto mode has an initial-speech deadline", recorder.isInProgress());

            source.resume();
            recorder.stop();
            assertEquals(Recorder.MSG_RECORDING_DONE, messages.poll(SOURCE_WAIT_SECONDS, TimeUnit.SECONDS));
            completed = recorder.takeRecording();
            assertNotNull("manual stop should keep the captured silent PCM available", completed);
            assertTrue(completed.lengthBytes() > 30_000L);
            assertMicrophoneReleased(audioRecord.get());
        } finally {
            source.resume();
            recorder.setListener(null);
            recorder.stop();
            recorder.release();
            closeIfOpen(completed);
        }
    }

    @Test public void lateNegativeMicrophoneReadPublishesTheCapturedPrefixAndReleasesTheMicrophone()
            throws Exception {
        BlockingQueue<String> messages = new ArrayBlockingQueue<>(8);
        AtomicReference<AudioRecord> audioRecord = new AtomicReference<>();
        PrefixThenErrorSource source = new PrefixThenErrorSource(8, (byte) 0x35);
        installSource(source, audioRecord);

        Recorder recorder = new Recorder(application());
        recorder.setListener(messages::offer);
        PcmRecording captured = null;
        try {
            recorder.start();

            assertEquals(Recorder.MSG_RECORDING, messages.poll(SOURCE_WAIT_SECONDS, TimeUnit.SECONDS));
            assertEquals("a late microphone read failure should preserve useful captured audio",
                    Recorder.MSG_RECORDING_DONE, messages.poll(SOURCE_WAIT_SECONDS, TimeUnit.SECONDS));
            captured = recorder.takeRecording();
            assertNotNull("the successful prefix must be transferred to the request", captured);
            assertEquals(8L * 960L, captured.lengthBytes());
            assertArrayEquals(source.expectedPrefix(), captured.nextChunk());
            assertNull(captured.nextChunk());
            assertTrue("the recording worker should finish after a read error", awaitStopped(recorder));
            assertMicrophoneReleased(audioRecord.get());
        } finally {
            recorder.setListener(null);
            recorder.stop();
            recorder.release();
            closeIfOpen(captured);
        }
    }

    @Test public void emptyMicrophoneReadReportsAnErrorAndDoesNotPublishAudio() throws Exception {
        BlockingQueue<String> messages = new ArrayBlockingQueue<>(8);
        AtomicReference<AudioRecord> audioRecord = new AtomicReference<>();
        installSource(new EmptyReadSource(), audioRecord);

        Recorder recorder = new Recorder(application());
        recorder.setListener(messages::offer);
        try {
            recorder.start();

            assertEquals(Recorder.MSG_RECORDING, messages.poll(SOURCE_WAIT_SECONDS, TimeUnit.SECONDS));
            assertEquals(Recorder.MSG_RECORDING_ERROR, messages.poll(SOURCE_WAIT_SECONDS, TimeUnit.SECONDS));
            assertTrue("an empty read must finish the recording worker", awaitStopped(recorder));
            assertNull("empty input must not be published as audio", recorder.takeRecording());
            assertMicrophoneReleased(audioRecord.get());
        } finally {
            recorder.setListener(null);
            recorder.stop();
            recorder.release();
        }
    }

    private static Context application() {
        return RuntimeEnvironment.getApplication();
    }

    private static void installSource(ShadowAudioRecord.AudioRecordSource source,
                                      AtomicReference<AudioRecord> audioRecord) {
        ShadowAudioRecord.setSourceProvider(record -> {
            audioRecord.set(record);
            return source;
        });
    }

    private static void assertMicrophoneReleased(AudioRecord audioRecord) {
        assertNotNull("AudioRecord should have been constructed", audioRecord);
        assertEquals(AudioRecord.STATE_UNINITIALIZED, audioRecord.getState());
        assertEquals(AudioRecord.RECORDSTATE_STOPPED, audioRecord.getRecordingState());
    }

    private static boolean awaitStopped(Recorder recorder) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SOURCE_WAIT_SECONDS);
        while (recorder.isInProgress() && System.nanoTime() < deadline) Thread.sleep(1);
        return !recorder.isInProgress();
    }

    private static void closeIfOpen(PcmRecording recording) throws IOException {
        if (recording != null && !recording.isClosed()) recording.close();
    }

    private static class SilenceSource implements ShadowAudioRecord.AudioRecordSource {
        private final int maxFullReads;
        private final AtomicInteger reads = new AtomicInteger();
        private final AtomicInteger capturedDurationMs = new AtomicInteger();
        final CountDownLatch limitReached = new CountDownLatch(1);
        private final CountDownLatch resume = new CountDownLatch(1);

        SilenceSource(int maxFullReads) {
            this.maxFullReads = maxFullReads;
        }

        @Override public int readInByteArray(byte[] data, int offset, int size, boolean isBlocking) {
            int read = reads.incrementAndGet();
            if (read > maxFullReads) {
                limitReached.countDown();
                try {
                    resume.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return AudioRecord.ERROR_INVALID_OPERATION;
                }
            }
            Arrays.fill(data, offset, offset + size, (byte) 0);
            // Advance by the duration represented by this read. Recorder deadlines are based on
            // captured samples, so CI can cover a 30-second silence window without sleeping.
            int durationMs = (int) (size * 1_000L / (2L * 16_000L));
            capturedDurationMs.addAndGet(durationMs);
            ShadowSystemClock.advanceBy(Duration.ofMillis(durationMs));
            return size;
        }

        long capturedDurationMs() {
            return capturedDurationMs.get();
        }

        void resume() {
            resume.countDown();
        }
    }

    private static final class PrefixThenErrorSource implements ShadowAudioRecord.AudioRecordSource {
        private final int prefixReads;
        private final byte sample;
        private int reads;

        PrefixThenErrorSource(int prefixReads, byte sample) {
            this.prefixReads = prefixReads;
            this.sample = sample;
        }

        @Override public synchronized int readInByteArray(byte[] data, int offset, int size,
                                                           boolean isBlocking) {
            if (++reads > prefixReads) return AudioRecord.ERROR_INVALID_OPERATION;
            Arrays.fill(data, offset, offset + size, sample);
            return size;
        }

        byte[] expectedPrefix() {
            byte[] prefix = new byte[prefixReads * 960];
            Arrays.fill(prefix, sample);
            return prefix;
        }
    }

    private static final class EmptyReadSource implements ShadowAudioRecord.AudioRecordSource {
        @Override public int readInByteArray(byte[] data, int offset, int size, boolean isBlocking) {
            return 0;
        }
    }

    /** Keeps Robolectric tests independent of the WebRTC JNI library while preserving VAD timing. */
    @Implements(value = VadWebRTC.class, isInAndroidSdk = false)
    public static class ShadowVadWebRTC {
        @Implementation protected static void __staticInitializer__() { }

        @Implementation protected void __constructor__(SampleRate sampleRate, FrameSize frameSize,
                                                        Mode mode, int speechDurationMs,
                                                        int silenceDurationMs) { }

        @Implementation protected boolean isSpeech(byte[] audioData) {
            return false;
        }

        @Implementation protected void close() { }
    }
}
