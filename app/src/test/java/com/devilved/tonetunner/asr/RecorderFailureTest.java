package com.devilved.tonetunner.asr;

import android.Manifest;
import android.content.Context;
import android.media.AudioRecord;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAudioRecord;
import org.robolectric.shadows.ShadowSystemClock;

import java.io.IOException;
import java.lang.reflect.Field;
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
@Config(sdk = 28)
public class RecorderFailureTest {
    private static final int READS_FOR_36_SECONDS = 1_200; // 480 samples at 16 kHz per read = 30 ms.
    private static final int PARTIAL_READS_FOR_36_SECONDS = 2_400; // 240 samples per read = 15 ms.
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
        SilenceSource source = new SilenceSource(PARTIAL_READS_FOR_36_SECONDS, 480);
        installSource(source, audioRecord);

        Recorder recorder = new Recorder(application());
        recorder.setListener(messages::offer);
        try {
            setVadEnabledWithoutNativeClassifier(recorder);
            recorder.start();

            assertEquals(Recorder.MSG_RECORDING, messages.poll(SOURCE_WAIT_SECONDS, TimeUnit.SECONDS));
            String terminalMessage = messages.poll(SOURCE_WAIT_SECONDS, TimeUnit.SECONDS);
            assertNotNull("VAD mode must terminate a capture with no initial speech", terminalMessage);
            assertTrue("the no-speech deadline must represent at least 30 seconds of captured silence",
                    source.capturedDurationMs() >= 30_000L);
            assertTrue("auto mode must terminate before the 36-second runaway guard",
                    source.capturedDurationMs() < 36_000L);
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

    private static void setVadEnabledWithoutNativeClassifier(Recorder recorder) throws Exception {
        Field field = Recorder.class.getDeclaredField("useVAD");
        field.setAccessible(true);
        field.setBoolean(recorder, true);
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
        for (int i = 0; recorder.isInProgress() && i < SOURCE_WAIT_SECONDS * 1_000; i++) {
            Thread.sleep(1);
        }
        return !recorder.isInProgress();
    }

    private static void closeIfOpen(PcmRecording recording) throws IOException {
        if (recording != null && !recording.isClosed()) recording.close();
    }

    private static class SilenceSource implements ShadowAudioRecord.AudioRecordSource {
        private final int maxFullReads;
        private final int bytesPerRead;
        private final AtomicInteger reads = new AtomicInteger();
        private final AtomicInteger capturedDurationMs = new AtomicInteger();
        final CountDownLatch limitReached = new CountDownLatch(1);
        private final CountDownLatch resume = new CountDownLatch(1);

        SilenceSource(int maxFullReads) {
            this(maxFullReads, Integer.MAX_VALUE);
        }

        SilenceSource(int maxFullReads, int bytesPerRead) {
            this.maxFullReads = maxFullReads;
            this.bytesPerRead = bytesPerRead;
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
            int returnedBytes = Math.min(size, bytesPerRead);
            Arrays.fill(data, offset, offset + returnedBytes, (byte) 0);
            // Advance by the duration represented by this read. Recorder deadlines are based on
            // captured samples, so CI can cover a 30-second silence window without sleeping.
            int durationMs = (int) (returnedBytes * 1_000L / (2L * 16_000L));
            capturedDurationMs.addAndGet(durationMs);
            ShadowSystemClock.advanceBy(Duration.ofMillis(durationMs));
            return returnedBytes;
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

}
