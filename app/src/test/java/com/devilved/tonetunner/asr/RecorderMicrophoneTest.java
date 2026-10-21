package com.devilved.tonetunner.asr;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import androidx.preference.PreferenceManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.AudioDeviceInfoBuilder;
import org.robolectric.shadows.ShadowAudioRecord;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** The microphone setting, Bluetooth SCO policy, capture source and in-line processing of the real recorder. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class RecorderMicrophoneTest {
    private static final int READS = 100; // 3 s of 30 ms frames.

    private final AtomicReference<AudioRecord> microphone = new AtomicReference<>();
    private final AtomicBoolean scoDuringCapture = new AtomicBoolean();
    private final List<byte[]> delivered = new ArrayList<>();
    private AudioManager audioManager;

    @Before public void setUp() {
        Shadows.shadowOf(app()).grantPermissions(Manifest.permission.RECORD_AUDIO);
        audioManager = (AudioManager) app().getSystemService(Context.AUDIO_SERVICE);
        audioManager.setBluetoothScoOn(false);
        PreferenceManager.getDefaultSharedPreferences(app()).edit().clear().commit();
    }

    @After public void tearDown() { ShadowAudioRecord.clearSource(); }

    @Test public void defaultIsThePhoneMicrophoneEvenWithABluetoothHeadsetConnected() throws Exception {
        connect(device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, 7, "00:11"),
                device(AudioDeviceInfo.TYPE_BUILTIN_MIC, 3, "back"),
                device(AudioDeviceInfo.TYPE_BUILTIN_MIC, 4, "bottom"));
        PcmRecording recording = record();
        try {
            assertFalse("Bluetooth SCO must stay off by default", scoDuringCapture.get());
            assertEquals(4, microphone.get().getPreferredDevice().getId());
            MicCapture capture = recording.getMicCapture();
            assertEquals("builtin", capture.device);
            assertFalse(capture.fellBack);
            assertEquals("maximum", capture.noiseReduction);
            assertEquals("maximum", capture.boost);
        } finally { recording.close(); }
    }

    @Test public void maximumNoiseReductionUsesCallGradeCaptureAndStandardDoesNot() throws Exception {
        record().close();
        assertEquals(MediaRecorder.AudioSource.VOICE_COMMUNICATION, microphone.get().getAudioSource());
        setPreference(MicSettings.KEY_NOISE_REDUCTION, "standard");
        record().close();
        assertEquals(MediaRecorder.AudioSource.VOICE_RECOGNITION, microphone.get().getAudioSource());
    }

    @Test public void bluetoothSettingOpensScoOnlyWhileRecordingAHeadset() throws Exception {
        setPreference(MicSettings.KEY_INPUT, "bluetooth");
        connect(device(AudioDeviceInfo.TYPE_BUILTIN_MIC, 3, "bottom"),
                device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, 7, "00:11"));
        PcmRecording recording = record();
        try {
            assertTrue(scoDuringCapture.get());
            assertFalse("SCO is closed again after the recording", audioManager.isBluetoothScoOn());
            assertEquals(7, microphone.get().getPreferredDevice().getId());
            assertEquals("bluetooth", recording.getMicCapture().device);
        } finally { recording.close(); }
    }

    @Test public void bluetoothSettingWithoutAHeadsetFallsBackToThePhone() throws Exception {
        setPreference(MicSettings.KEY_INPUT, "bluetooth");
        connect(device(AudioDeviceInfo.TYPE_BUILTIN_MIC, 3, "bottom"));
        PcmRecording recording = record();
        try {
            assertFalse(scoDuringCapture.get());
            assertEquals("builtin", recording.getMicCapture().device);
            assertTrue(recording.getMicCapture().fellBack);
        } finally { recording.close(); }
    }

    @Test public void automaticSettingLeavesTheChoiceToAndroid() throws Exception {
        setPreference(MicSettings.KEY_INPUT, "automatic");
        connect(device(AudioDeviceInfo.TYPE_BUILTIN_MIC, 3, "bottom"));
        record().close();
        assertNull(microphone.get().getPreferredDevice());
        assertFalse(scoDuringCapture.get());
    }

    @Test public void defaultBoostRaisesQuietSpeechAndOffKeepsTheExactAudio() throws Exception {
        PcmRecording boosted = record();
        long boostedEnergy;
        try { boostedEnergy = energy(boosted); } finally { boosted.close(); }

        setPreference(MicSettings.KEY_NOISE_REDUCTION, "off");
        setPreference(MicSettings.KEY_BOOST, "off");
        delivered.clear();
        PcmRecording raw = record();
        try {
            byte[] expected = concat(delivered);
            byte[] actual = readAll(raw);
            assertArrayEquals("with processing off the saved audio equals the microphone audio", expected, actual);
            assertTrue("maximum boost makes quiet speech louder",
                    boostedEnergy > 4 * energy(actual));
        } finally { raw.close(); }
    }

    @Test public void recorderWorksWhenNoInputDeviceIsReported() throws Exception {
        PcmRecording recording = record();
        try {
            assertEquals("automatic", recording.getMicCapture().device);
            assertTrue(recording.lengthBytes() > 0);
        } finally { recording.close(); }
    }

    // ---- harness ----

    private PcmRecording record() throws Exception {
        BlockingQueue<String> messages = new ArrayBlockingQueue<>(8);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        ShadowAudioRecord.setSourceProvider(record -> {
            microphone.set(record);
            return new ShadowAudioRecord.AudioRecordSource() {
                int reads;
                @Override public int readInByteArray(byte[] audio, int offset, int size, boolean blocking) {
                    if (reads == 0) scoDuringCapture.set(audioManager.isBluetoothScoOn());
                    if (reads++ >= READS) {
                        done.countDown();
                        try { Thread.sleep(5); } catch (InterruptedException ignored) { }
                    }
                    for (int i = 0; i + 1 < size; i += 2) {
                        int n = (reads * size + i) / 2;
                        // Quiet voiced sound around -40 dBFS with a 4 Hz syllable rhythm.
                        double v = 330 * (0.6 + 0.4 * Math.sin(2 * Math.PI * 4 * n / 16000.0))
                                * Math.sin(2 * Math.PI * 200 * n / 16000.0);
                        short s = (short) Math.round(v);
                        audio[offset + i] = (byte) s;
                        audio[offset + i + 1] = (byte) (s >> 8);
                    }
                    synchronized (delivered) {
                        delivered.add(Arrays.copyOfRange(audio, offset, offset + size));
                    }
                    return size;
                }
            };
        });
        Recorder recorder = new Recorder(app());
        recorder.setListener(messages::offer);
        try {
            recorder.start();
            assertEquals(Recorder.MSG_RECORDING, messages.poll(5, TimeUnit.SECONDS));
            assertTrue(done.await(5, TimeUnit.SECONDS));
            recorder.stop();
            assertEquals(Recorder.MSG_RECORDING_DONE, messages.poll(5, TimeUnit.SECONDS));
            PcmRecording recording = recorder.takeRecording();
            assertNotNull(recording);
            assertNotNull(recording.getMicCapture());
            return recording;
        } finally {
            recorder.setListener(null);
            recorder.release();
        }
    }

    private static Application app() { return RuntimeEnvironment.getApplication(); }

    private static void setPreference(String key, String value) {
        PreferenceManager.getDefaultSharedPreferences(app()).edit().putString(key, value).commit();
    }

    private void connect(AudioDeviceInfo... devices) {
        Shadows.shadowOf(audioManager).setInputDevices(Arrays.asList(devices));
    }

    private static AudioDeviceInfo device(int type, int id, String address) {
        AudioDeviceInfo info = AudioDeviceInfoBuilder.newBuilder().setType(type).build();
        Object port = ReflectionHelpers.getField(info, "mPort");
        ReflectionHelpers.setField(port, "mAddress", address);
        ReflectionHelpers.setField(port, "mRole", 1); // AudioPort.ROLE_SOURCE (hidden API)
        ReflectionHelpers.setField(ReflectionHelpers.getField(port, "mHandle"), "mId", id);
        return info;
    }

    private static byte[] readAll(PcmRecording recording) throws Exception {
        List<byte[]> chunks = new ArrayList<>();
        byte[] chunk;
        while ((chunk = recording.nextChunk()) != null) chunks.add(chunk);
        return concat(chunks);
    }

    private static long energy(PcmRecording recording) throws Exception {
        return energy(readAll(recording));
    }

    private static long energy(byte[] pcm) {
        long sum = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int s = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
            sum += (long) s * s;
        }
        return sum;
    }

    private static byte[] concat(List<byte[]> parts) {
        int length = 0;
        synchronized (parts) { for (byte[] part : parts) length += part.length; }
        byte[] all = new byte[length];
        int at = 0;
        synchronized (parts) {
            for (byte[] part : parts) { System.arraycopy(part, 0, all, at, part.length); at += part.length; }
        }
        return all;
    }
}
