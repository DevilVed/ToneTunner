package com.devilved.tonetunner.asr;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.Looper;
import com.devilved.tonetunner.cloud.CloudClient;
import com.devilved.tonetunner.cloud.ProviderConfig;
import com.devilved.tonetunner.voice_translation.neural_networks.voice.Recognizer;
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
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Exercises failure boundaries of the actual spool/orchestrator; only model/API calls are fakes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = WhisperFailureRecoveryTest.FailingEnhancement.class,
        instrumentedPackages = {"com.devilved.tonetunner.cloud"})
@LooperMode(LooperMode.Mode.PAUSED)
public class WhisperFailureRecoveryTest {
    private Context context;
    private Whisper whisper;
    private final List<String> languages = new ArrayList<>();
    private final List<Integer> firstSamples = new ArrayList<>();
    private final List<Integer> windowSizes = new ArrayList<>();
    private int calls;
    private int failAt;
    private String rawText = "The complete raw transcript.";
    private String resultLanguage = "en";
    private List<String> windowTexts;
    private WhisperResult result;
    private int errors;
    private int errorCode;
    private boolean acceptDelivery = true;
    private boolean throwingDestination;
    private int blockAt;
    private final CountDownLatch blocked = new CountDownLatch(1);
    private final CountDownLatch allowBlocked = new CountDownLatch(1);

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE).edit().clear()
                .putString("transcription.provider", "LOCAL").putBoolean("cloud.consent", true).commit();
        FailingEnhancement.calls = 0;
        deleteTree(new File(context.getNoBackupFilesDir(), "dictation-recovery"));
        whisper = new Whisper(context, ignored -> new Whisper.LocalTranscriber() {
            public CloudClient.Transcript transcribe(byte[] pcm, String language, Recognizer.Action action)
                    throws IOException, InterruptedException {
                languages.add(language);
                firstSamples.add((int) (short) ((pcm[0] & 255) | (pcm[1] << 8)));
                windowSizes.add(pcm.length);
                calls++;
                if (calls == blockAt) {
                    blocked.countDown();
                    if (!allowBlocked.await(10, TimeUnit.SECONDS)) throw new IOException("Blocked fixture timed out");
                }
                if (calls == failAt) throw new IOException("Synthetic later-window failure");
                String text = windowTexts != null ? windowTexts.get(calls - 1)
                        : failAt > 0 ? "part-" + calls : rawText;
                return new CloudClient.Transcript(text,
                        calls == 1 ? resultLanguage : "fr");
            }
            public void close() { }
        });
        whisper.setListener(new Whisper.WhisperListener() {
            public void onUpdateReceived(String message) { }
            public void onResultReceived(WhisperResult value) { result = value; }
            @Override public boolean onResultDelivery(WhisperResult value) {
                if (throwingDestination) throw new IllegalStateException("Synthetic destination failure");
                if (!acceptDelivery) return false;
                onResultReceived(value);
                return true;
            }
            public void onError(String message) { errors++; }
            public void onError(int code, String message) { errorCode = code; errors++; }
        });
    }

    @After public void tearDown() {
        allowBlocked.countDown();
        whisper.unloadModel();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        deleteTree(new File(context.getNoBackupFilesDir(), "dictation-recovery"));
    }

    @Test public void enhancementFailureDeliversTheWholeRawTranscript() throws Exception {
        context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE).edit()
                .putBoolean("enhancement.enabled", true).commit();
        startAndDrain(recording(1, 2048));
        assertNotNull("valid transcription must survive enhancement failure", result);
        assertEquals(rawText, result.getResult());
        assertEquals(0, errors);
        assertEquals(1, FailingEnhancement.calls);
        assertTrue(result.getWarning().contains("raw transcript"));
    }

    @Test public void laterEnhancementFailureDoesNotDeliverAnEnhancedPrefix() throws Exception {
        rawText = "Original sentence. ".repeat(220).trim();
        context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE).edit()
                .putBoolean("enhancement.enabled", true).commit();
        FailingEnhancement.failAt = 2;
        try {
            startAndDrain(recording(1, 2048));
            assertNotNull(result);
            assertEquals(rawText, result.getResult());
            assertEquals(0, errors);
        } finally { FailingEnhancement.failAt = 1; }
    }

    @Test public void laterTranscriptionFailureRetainsAudioAndCompletedTextAfterFacadeCloses()
            throws Exception {
        failAt = 3;
        startAndDrain(recording(60, 2048));
        assertEquals(1, errors);
        assertNull("partial text must not masquerade as complete dictation", result);
        whisper.unloadModel();
        File directory = new File(context.getNoBackupFilesDir(), "dictation-recovery");
        assertTrue("failed dictation audio must remain available for explicit retry", hasAudio(directory));
        assertTrue("completed window text must survive terminal cleanup", hasText(directory, "part-1 part-2"));
    }

    @Test public void autoLanguagePinsFirstSpeechLanguageForLaterWindows() throws Exception {
        startAndDrain(recording(60, 2048));
        assertEquals(List.of("auto", "en", "en"), languages);
        assertNotNull(result);
        assertEquals("en", result.getLanguage());
    }

    @Test public void retryResumesAtTheFailedWindowWithoutReplayingCompletedAudio() throws Exception {
        failAt = 3;
        startAndDrain(recording(60, 2048));
        RecoveryStore store = new RecoveryStore(context);
        assertEquals(1, store.list().size());
        String id = store.list().get(0).id;
        assertEquals("part-1 part-2", store.get(id).transcript);
        assertEquals(3, calls);

        whisper.retrySaved(id);
        drainWorker();

        assertEquals("only the failed window should be sent again", 4, calls);
        assertNotNull(result);
        assertEquals("part-1 part-2 part-4", result.getResult());
        assertTrue("successful delivery removes the retained recording", store.list().isEmpty());
        assertEquals(1, errors);
    }

    @Test public void failedRetryCheckpointsNewProgressWithoutReplacingTheOriginalAudio() throws Exception {
        failAt = 2;
        startAndDrain(recording(60, 2048));
        RecoveryStore store = new RecoveryStore(context);
        String id = store.list().get(0).id;
        failAt = 4;
        whisper.retrySaved(id);
        drainWorker();

        assertEquals(1, store.list().size());
        assertEquals("part-1 part-3", store.get(id).transcript);
        assertEquals(60L * 32_000, store.get(id).lengthBytes);
        assertEquals(2, errors);
        assertNull(result);
    }

    @Test public void cancellingASavedRetryDoesNotDiscardTheRetainedOriginal() throws Exception {
        failAt = 2;
        startAndDrain(recording(60, 2048));
        RecoveryStore store = new RecoveryStore(context);
        String id = store.list().get(0).id;
        whisper.retrySaved(id);
        whisper.stop();
        drainWorker();
        assertNotNull(store.get(id));
        assertEquals(60L * 32_000, store.get(id).lengthBytes);
        assertNull(result);
    }

    @Test public void cancellationDuringALaterWindowKeepsAudioAndCompletedProgress() throws Exception {
        blockAt = 2;
        whisper.start(recording(60, 2048));
        assertTrue(blocked.await(5, TimeUnit.SECONDS));
        whisper.stop();
        allowBlocked.countDown();
        drainWorker();
        RecoveryStore.Entry entry = new RecoveryStore(context).list().get(0);
        assertEquals(rawText, entry.transcript);
        assertEquals(60L * 32_000, entry.lengthBytes);
        assertNull(result);
    }

    @Test public void cancellingBeforeQueuedSuccessDeliveryKeepsTheCompleteTextRecoverable() throws Exception {
        whisper.start(recording(1, 2048));
        Field field = Whisper.class.getDeclaredField("worker");
        field.setAccessible(true);
        ((ExecutorService) field.get(whisper)).submit(() -> { }).get(10, TimeUnit.SECONDS);
        // Do not drain the UI queue: inference is done but its result has not been delivered.
        whisper.stop();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        RecoveryStore store = new RecoveryStore(context);
        RecoveryStore.Entry entry = store.list().get(0);
        assertEquals(rawText, entry.transcript);
        assertEquals(32_000, entry.offset);
        assertNull(result);
        whisper.retrySaved(entry.id);
        drainWorker();
        assertNotNull(result);
        assertEquals(rawText, result.getResult());
        assertEquals("already completed audio must not be uploaded again", 1, calls);
        assertTrue(store.list().isEmpty());
    }

    @Test public void veryQuietNonzeroRoomNoiseDoesNotReachTheModel() throws Exception {
        startAndDrain(recording(2, 12));
        assertEquals("room noise must not become full-scale speech", 0, calls);
        assertNull(result);
        assertEquals(1, errors);
    }

    @Test public void shortSoftSpeechInsideALongPauseIsNotDilutedOutOfTheWindow() throws Exception {
        PcmRecording capture = PcmRecording.create(new File(context.getNoBackupFilesDir(), "fixture-spools"));
        byte[] audio = new byte[28 * 32_000];
        for (int i = 5 * 32_000; i < 6 * 32_000; i += 2) {
            audio[i] = (byte) 320;
            audio[i + 1] = (byte) (320 >> 8);
        }
        capture.write(audio, 0, audio.length);
        capture.finish();
        startAndDrain(capture);
        assertEquals("a short quiet utterance must reach inference despite surrounding silence", 1, calls);
        assertNotNull(result);
        assertEquals(rawText, result.getResult());
    }

    @Test public void localDecoderFailureMarkerIsRetainedAsAFailedWindowRatherThanDeliveredAsText()
            throws Exception {
        rawText = "[(und)]";
        startAndDrain(recording(1, 2048));
        assertNull("the engine failure sentinel is not dictated text", result);
        assertEquals(1, errors);
        RecoveryStore.Entry saved = new RecoveryStore(context).list().get(0);
        assertEquals("", saved.transcript);
        assertEquals(0, saved.offset);
        assertEquals(32_000, saved.lengthBytes);
    }

    @Test public void throwingDestinationDoesNotCrashDeliveryOrDiscardCompletedText() throws Exception {
        throwingDestination = true;
        startAndDrain(recording(1, 2048));
        assertNull(result);
        RecoveryStore store = new RecoveryStore(context);
        RecoveryStore.Entry saved = store.list().get(0);
        assertEquals(rawText, saved.transcript);
        assertEquals(32_000, saved.offset);
        throwingDestination = false;
        whisper.retrySaved(saved.id);
        drainWorker();
        assertNotNull("the delivery claim must be released after an exception", result);
        assertTrue(store.list().isEmpty());
    }

    @Test public void startDoesNotOpenPrivateStorageOrAiSettingsOnTheMainThread() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean storageOnMain = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean settingsOnMain = new java.util.concurrent.atomic.AtomicBoolean();
        Context wrapper = new ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getNoBackupFilesDir() {
                if (Looper.myLooper() == Looper.getMainLooper()) storageOnMain.set(true);
                return super.getNoBackupFilesDir();
            }
            @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
                if ("ai_settings".equals(name) && Looper.myLooper() == Looper.getMainLooper()) {
                    settingsOnMain.set(true);
                }
                return super.getSharedPreferences(name, mode);
            }
        };
        whisper.unloadModel();
        whisper = new Whisper(wrapper, ignored -> new Whisper.LocalTranscriber() {
            public CloudClient.Transcript transcribe(byte[] pcm, String language, Recognizer.Action action) {
                return new CloudClient.Transcript(rawText, "en");
            }
            public void close() { }
        });
        whisper.start(recording(1, 2048));
        assertFalse("archive work must run off the UI thread", storageOnMain.get());
        assertFalse("AI settings/Keystore work must run off the UI thread", settingsOnMain.get());
        drainWorker();
    }

    @Test public void unloadingImmediatelyAfterSubmissionStillArchivesTheAcceptedRecording() throws Exception {
        whisper.start(recording(1, 2048));
        whisper.unloadModel();
        Field field = Whisper.class.getDeclaredField("worker");
        field.setAccessible(true);
        assertTrue(((ExecutorService) field.get(whisper)).awaitTermination(10, TimeUnit.SECONDS));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        RecoveryStore store = new RecoveryStore(context);
        assertEquals(1, store.list().size());
        assertEquals(32_000, store.list().get(0).lengthBytes);
        assertNull(result);
    }

    @Test public void rejectedResultDeliveryKeepsCompletedAudioAndTextForRetry() throws Exception {
        acceptDelivery = false;
        startAndDrain(recording(1, 2048));
        assertNull("a closed editor/client must not accept a result", result);
        RecoveryStore store = new RecoveryStore(context);
        RecoveryStore.Entry saved = store.list().get(0);
        assertEquals(rawText, saved.transcript);
        assertEquals(32_000, saved.offset);
        acceptDelivery = true;
        whisper.retrySaved(saved.id);
        drainWorker();
        assertNotNull(result);
        assertEquals(rawText, result.getResult());
        assertEquals("delivery retry must not retranscribe completed audio", 1, calls);
        assertTrue(store.list().isEmpty());
    }

    @Test public void noSpeechHasANoMatchErrorInsteadOfAGenericServerError() throws Exception {
        startAndDrain(recording(1, 0));
        assertEquals(android.speech.SpeechRecognizer.ERROR_NO_MATCH, errorCode);
        assertNull(result);
        assertEquals(1, errors);
    }

    @Test public void explicitRetryCanSendQuietAudioWithoutLosingItsBeginning() throws Exception {
        startAndDrain(recording(2, 12));
        RecoveryStore store = new RecoveryStore(context);
        RecoveryStore.Entry entry = store.list().get(0);
        assertEquals(0, entry.offset);
        whisper.retrySaved(entry.id);
        drainWorker();
        assertEquals(1, calls);
        assertNotNull(result);
        assertEquals(rawText, result.getResult());
        assertTrue(store.list().isEmpty());
    }

    @Test public void hardCutContextDoesNotDuplicateAnOverlappingPhrase() throws Exception {
        windowTexts = List.of("First words at the boundary", "at the boundary then more", "last words");
        startAndDrain(recording(60, 2048));
        assertNotNull(result);
        assertEquals("First words at the boundary then more last words", result.getResult());
    }

    @Test public void hardCutCarriesHalfSecondOfAudioContextWithinTheRequestBound() throws Exception {
        PcmRecording recording = PcmRecording.create(new File(context.getNoBackupFilesDir(), "fixture-spools"));
        byte[] second = new byte[32_000];
        for (int s = 0; s < 60; s++) {
            int sample = 2000 + 100 * s;
            for (int i = 0; i < second.length; i += 2) {
                second[i] = (byte) sample;
                second[i + 1] = (byte) (sample >> 8);
            }
            recording.write(second, 0, second.length);
        }
        recording.finish();
        startAndDrain(recording);
        assertEquals(List.of(2000, 4700, 7500), firstSamples);
        for (int length : windowSizes) assertTrue(length <= 896_000);
    }

    private PcmRecording recording(int seconds, int amplitude) throws Exception {
        PcmRecording recording = PcmRecording.create(new File(context.getNoBackupFilesDir(), "fixture-spools"));
        byte[] frame = new byte[32_000];
        for (int i = 0; i < frame.length; i += 2) {
            int sample = ((i / 2) % 2 == 0) ? amplitude : -amplitude;
            frame[i] = (byte) sample;
            frame[i + 1] = (byte) (sample >> 8);
        }
        for (int i = 0; i < seconds; i++) recording.write(frame, 0, frame.length);
        recording.finish();
        return recording;
    }

    private void startAndDrain(PcmRecording recording) throws Exception {
        whisper.start(recording);
        drainWorker();
    }

    private void drainWorker() throws Exception {
        Field field = Whisper.class.getDeclaredField("worker");
        field.setAccessible(true);
        ((ExecutorService) field.get(whisper)).submit(() -> { }).get(10, TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private static boolean hasAudio(File file) {
        if (file.isFile()) return file.getName().endsWith(".pcm") && file.length() >= 60 * 32_000;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) if (hasAudio(child)) return true;
        return false;
    }
    private static boolean hasText(File file, String text) throws IOException {
        if (file.isFile() && file.getName().endsWith(".json")) return new String(
                Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8).contains(text);
        File[] children = file.listFiles();
        if (children != null) for (File child : children) if (hasText(child, text)) return true;
        return false;
    }
    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
    @Implements(value = CloudClient.class, isInAndroidSdk = false)
    public static class FailingEnhancement {
        static int calls;
        static int failAt = 1;
        @Implementation public String enhance(String text, ProviderConfig config, String prompt, String vocabulary)
                throws IOException {
            if (++calls == failAt) throw new IOException("Synthetic enhancement refusal/rate limit");
            return "Edited prefix";
        }
    }
}
