package com.devilved.tonetunner;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.speech.RecognizerIntent;
import android.system.Os;
import android.system.OsConstants;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.preference.PreferenceManager;

import com.devilved.tonetunner.asr.PcmRecording;
import com.devilved.tonetunner.asr.Recorder;
import com.devilved.tonetunner.asr.Whisper;
import com.devilved.tonetunner.asr.WhisperResult;
import com.devilved.tonetunner.voice_translation.neural_networks.voice.Recognizer;
import com.devilved.tonetunner.cloud.AiSettings;
import com.devilved.tonetunner.cloud.Provider;
import com.devilved.tonetunner.cloud.ProviderConfig;
import com.devilved.tonetunner.cloud.SecretStore;
import com.konovalov.vad.webrtc.Vad;
import com.konovalov.vad.webrtc.VadWebRTC;
import com.konovalov.vad.webrtc.config.FrameSize;
import com.konovalov.vad.webrtc.config.Mode;
import com.konovalov.vad.webrtc.config.SampleRate;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.extensions.OrtxPackage;

import static org.junit.Assert.*;

/** Actual Android framework/native tests. No network, model weights, or user credentials. */
@RunWith(AndroidJUnit4.class)
public class AndroidRuntimeTest {
    @Rule public final GrantPermissionRule microphone =
            GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO);

    private Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @Test public void runtimeUsesTheExpectedPageSize() throws Exception {
        long expected = Long.parseLong(InstrumentationRegistry.getArguments()
                .getString("expectedPageSize", "4096"));
        assertEquals("The emulator must exercise its advertised native environment", expected,
                Os.sysconf(OsConstants._SC_PAGESIZE));
    }

    @Test public void productionKeystorePersistsAndSeparatesCloudCredentials() throws Exception {
        Context context = context();
        String endpoint = Provider.GROQ.transcriptionEndpoint();
        String fixtureKey = "android-runtime-fixture-only";
        SecretStore store = new SecretStore(context);
        try {
            ProviderConfig transcription = new ProviderConfig(Provider.GROQ, endpoint,
                    "fixture-transcriber", fixtureKey, false);
            ProviderConfig enhancement = new ProviderConfig(Provider.OPENROUTER,
                    Provider.OPENROUTER.enhancementEndpoint(), "fixture-editor", "", true);
            new AiSettings(context).saveSettings(transcription, false, enhancement, "", "", true);
            ProviderConfig reopened = new AiSettings(context).getTranscriptionConfig();
            assertEquals(fixtureKey, reopened.apiKey);
            assertEquals("fixture-transcriber", reopened.model);
            assertTrue(new AiSettings(context).isCloudConsentGranted());
            assertEquals("", store.getKey(Provider.GROQ, "enhancement", endpoint));
            assertEquals("", store.getKey(Provider.GROQ, "transcription", endpoint + "/other"));
            assertFalse(context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
                    .getAll().containsValue(fixtureKey));
        } finally {
            store.removeKey(Provider.GROQ, "transcription", endpoint);
            context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE).edit().clear().commit();
        }
    }

    @Test public void nativeOnnxInferenceAndExtensionsLoadOnThisAndroidRuntime() throws Exception {
        OrtEnvironment environment = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.registerCustomOpLibrary(OrtxPackage.getLibraryPath());
            try (OrtSession session = environment.createSession(identityModel(), options);
                 OnnxTensor input = OnnxTensor.createTensor(environment,
                         FloatBuffer.wrap(new float[]{1.25f, -2.5f}), new long[]{2});
                 OrtSession.Result output = session.run(Collections.singletonMap("input", input))) {
                assertArrayEquals(new float[]{1.25f, -2.5f}, (float[]) output.get(0).getValue(), 0f);
            }
        }
    }

    @Test public void nativeVadAcceptsARealPcmFrame() {
        VadWebRTC vad = Vad.builder().setSampleRate(SampleRate.SAMPLE_RATE_16K)
                .setFrameSize(FrameSize.FRAME_SIZE_480).setMode(Mode.VERY_AGGRESSIVE)
                .setSilenceDurationMs(800).setSpeechDurationMs(200).build();
        try { assertFalse(vad.isSpeech(new byte[960])); }
        finally { vad.close(); }
    }

    @Test public void speechIntentReturnsItsTranscriptToTheActualCallingActivity() throws Exception {
        PreferenceManager.getDefaultSharedPreferences(context()).edit()
                .putBoolean("imeModeAuto", false).commit();
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
                WhisperRecognizeActivity.class.getName(), null, false);
        AtomicReference<ActivityResult> result = new AtomicReference<>();
        AtomicReference<ActivityResultLauncher<Intent>> launcher = new AtomicReference<>();
        CountDownLatch returned = new CountDownLatch(1);
        try (ActivityScenario<MainActivity> caller = ActivityScenario.launch(MainActivity.class)) {
            caller.onActivity(activity -> {
                launcher.set(activity.getActivityResultRegistry().register("recognition-runtime-test",
                        new ActivityResultContracts.StartActivityForResult(), response -> {
                            result.set(response);
                            returned.countDown();
                        }));
                launcher.get().launch(new Intent(activity, WhisperRecognizeActivity.class)
                        .setAction(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en"));
            });
            Activity child = monitor.waitForActivityWithTimeout(10_000);
            assertTrue("The recognition Activity did not open", child instanceof WhisperRecognizeActivity);
            instrumentation.runOnMainSync(() -> {
                try {
                    // Supply a deterministic result at the recognizer callback boundary. The caller,
                    // Android result routing, child Activity, and result Intent are all real.
                    WhisperRecognizeActivity activity = (WhisperRecognizeActivity) child;
                    field(WhisperRecognizeActivity.class, "sessionGeneration").setLong(activity, 1L);
                    field(WhisperRecognizeActivity.class, "sessionActive").setBoolean(activity, true);
                    Method ensure = WhisperRecognizeActivity.class.getDeclaredMethod("ensureWhisper", long.class);
                    ensure.setAccessible(true);
                    ensure.invoke(activity, 1L);
                    Whisper whisper = (Whisper) field(WhisperRecognizeActivity.class, "mWhisper").get(activity);
                    Whisper.WhisperListener listener = (Whisper.WhisperListener)
                            field(Whisper.class, "listener").get(whisper);
                    listener.onResultReceived(new WhisperResult("recognition fixture", "en",
                            Recognizer.ACTION_TRANSCRIBE));
                } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            });
            assertTrue("The calling Activity never received a speech result", returned.await(10, TimeUnit.SECONDS));
            assertEquals(Activity.RESULT_OK, result.get().getResultCode());
            assertNotNull(result.get().getData());
            assertEquals(Collections.singletonList("recognition fixture"), result.get().getData()
                    .getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS));
        } finally {
            instrumentation.removeMonitor(monitor);
            if (launcher.get() != null) launcher.get().unregister();
        }
    }

    @Test public void manualMicrophoneRecordingSurvivesThirtySecondsUntilExplicitStop() throws Exception {
        AtomicReference<Recorder> reference = new AtomicReference<>();
        AtomicReference<String> terminalMessage = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch terminal = new CountDownLatch(1);
        PcmRecording recording = null;
        // A visible Activity satisfies Android's foreground microphone policy. The emulator supplies audio.
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                Recorder recorder = new Recorder(activity);
                reference.set(recorder);
                recorder.setListener(message -> {
                    if (Recorder.MSG_RECORDING.equals(message)) started.countDown();
                    else { terminalMessage.set(message); terminal.countDown(); }
                });
                recorder.start(); // Manual mode deliberately does not initialize VAD.
            });
            assertTrue("Microphone capture did not start", started.await(10, TimeUnit.SECONDS));
            assertFalse("Manual capture terminated without an explicit stop: " + terminalMessage.get(),
                    terminal.await(32, TimeUnit.SECONDS));
            assertTrue(reference.get().isInProgress());
            reference.get().stop();
            assertTrue("Microphone capture did not finish after stop", terminal.await(5, TimeUnit.SECONDS));
            assertEquals(Recorder.MSG_RECORDING_DONE, terminalMessage.get());
            recording = reference.get().takeRecording();
            assertNotNull(recording);
            assertTrue("Audio must extend past the former 30-second cap", recording.lengthBytes() > 960_000);
            long total = 0;
            int chunks = 0;
            byte[] chunk;
            while ((chunk = recording.nextChunk()) != null) {
                assertTrue(chunk.length <= PcmRecording.MAX_CHUNK_BYTES);
                total += chunk.length;
                chunks++;
            }
            assertEquals(recording.lengthBytes(), total);
            assertTrue(chunks >= 2);
        } finally {
            if (reference.get() != null) reference.get().release();
            if (recording != null) { recording.close(); assertTrue(recording.isClosed()); }
        }
    }

    // Tiny self-generated ONNX Identity graph: IR8/opset13, float input[2] -> output[2].
    // Protobuf wire fields follow ONNX's public onnx.proto; no third-party model weights are used.
    private static byte[] identityModel() {
        byte[] shape = field(1, number(1, 2));
        byte[] tensorType = concat(number(1, 1), field(2, shape));
        byte[] type = field(1, tensorType);
        byte[] input = concat(text(1, "input"), field(2, type));
        byte[] output = concat(text(1, "output"), field(2, type));
        byte[] node = concat(text(1, "input"), text(2, "output"), text(4, "Identity"));
        byte[] graph = concat(field(1, node), text(2, "runtime-test"), field(11, input), field(12, output));
        return concat(number(1, 8), field(7, graph), field(8, number(2, 13)));
    }
    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
    private static byte[] text(int tag, String value) {
        return field(tag, value.getBytes(StandardCharsets.UTF_8));
    }
    private static byte[] field(int tag, byte[] value) {
        return concat(varint((tag << 3) | 2), varint(value.length), value);
    }
    private static byte[] number(int tag, int value) { return concat(varint(tag << 3), varint(value)); }
    private static byte[] varint(int value) {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        while (value > 127) { result.write((value & 127) | 128); value >>>= 7; }
        result.write(value);
        return result.toByteArray();
    }
    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        for (byte[] part : parts) result.write(part, 0, part.length);
        return result.toByteArray();
    }
}
