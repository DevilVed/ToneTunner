package com.devilved.tonetunner.asr;

import android.Manifest;
import android.media.AudioRecord;

import com.konovalov.vad.webrtc.VadWebRTC;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAudioRecord;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Drives the real recorder's post-speech stop branch without loading a native classifier. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = RecorderVadTest.Classifier.class,
        instrumentedPackages = {"com.konovalov.vad.webrtc"})
public class RecorderVadTest {
    @After public void clearSource() { ShadowAudioRecord.clearSource(); }

    @Test public void automaticModeContinuesPastThirtySecondsOfSpeechThenStopsAfterVadSilence()
            throws Exception {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO);
        Classifier.calls = 0;
        Classifier.closed = false;
        AtomicReference<AudioRecord> microphone = new AtomicReference<>();
        ShadowAudioRecord.setSourceProvider(record -> {
            microphone.set(record);
            return new ShadowAudioRecord.AudioRecordSource() {
                @Override public int readInByteArray(byte[] audio, int offset, int size, boolean blocking) {
                    Arrays.fill(audio, offset, offset + size, (byte) 0x35);
                    return size;
                }
            };
        });
        BlockingQueue<String> events = new ArrayBlockingQueue<>(8);
        AtomicReference<Boolean> cleanedBeforeDone = new AtomicReference<>(false);
        Recorder recorder = new Recorder(RuntimeEnvironment.getApplication());
        recorder.setListener(message -> {
            if (Recorder.MSG_RECORDING_DONE.equals(message)) {
                cleanedBeforeDone.set(microphone.get().getState() == AudioRecord.STATE_UNINITIALIZED
                        && Classifier.closed && !recorder.isInProgress());
            }
            events.offer(message);
        });
        Field useVad = Recorder.class.getDeclaredField("useVAD");
        useVad.setAccessible(true);
        useVad.setBoolean(recorder, true);
        Field vad = Recorder.class.getDeclaredField("vad");
        vad.setAccessible(true);
        vad.set(recorder, Shadow.newInstanceOf(VadWebRTC.class));
        PcmRecording captured = null;
        try {
            recorder.start();
            assertEquals(Recorder.MSG_RECORDING, events.poll(10, TimeUnit.SECONDS));
            assertEquals(Recorder.MSG_SPEECH_STARTED, events.poll(10, TimeUnit.SECONDS));
            assertEquals(Recorder.MSG_RECORDING_DONE, events.poll(10, TimeUnit.SECONDS));
            assertTrue("Terminal callback must follow microphone/VAD cleanup", cleanedBeforeDone.get());
            assertEquals("Speech-start is emitted once and no timeout/error follows", 0, events.size());
            assertEquals(1201, Classifier.calls); // 1200 * 30 ms = 36 seconds of speech, then silence.
            captured = recorder.takeRecording();
            assertNotNull(captured);
            assertEquals(1201L * 960L, captured.lengthBytes());
            assertNull(captured.getCaptureWarning());
            assertNull("The completed recording transfers ownership once", recorder.takeRecording());
        } finally {
            recorder.setListener(null);
            recorder.release();
            if (captured != null) captured.close();
        }
    }

    @Implements(value = VadWebRTC.class, isInAndroidSdk = false)
    public static class Classifier {
        static volatile int calls;
        static volatile boolean closed;
        @Implementation protected static void __staticInitializer__() { }
        @Implementation public boolean isSpeech(byte[] frame) { return ++calls <= 1200; }
        @Implementation public void close() { closed = true; }
    }
}
