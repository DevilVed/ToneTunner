package com.devilved.tonetunner.asr;

import android.content.Context;
import android.os.Looper;

import com.devilved.tonetunner.cloud.AiSettings;
import com.devilved.tonetunner.cloud.CloudClient;
import com.devilved.tonetunner.cloud.Provider;
import com.devilved.tonetunner.cloud.ProviderConfig;
import com.devilved.tonetunner.voice_translation.neural_networks.voice.Recognizer;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.rules.TemporaryFolder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Verifies Whisper's recording ownership and cancellation without models or provider calls. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class WhisperOwnershipTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private final List<Whisper> facades = new ArrayList<>();
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After public void tearDown() {
        for (Whisper facade : facades) facade.unloadModel();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void destroyedFacadeClosesSuppliedRecordingWithoutCallbacks() throws Exception {
        Whisper whisper = newWhisper();
        RecordingFixture recording = completedRecording("destroyed");
        CallbackCounter callbacks = new CallbackCounter();
        whisper.unloadModel();
        whisper.setListener(callbacks);

        whisper.start(recording.recording);
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertClosedAndDeleted(recording);
        assertEquals(0, callbacks.updates);
        assertEquals(0, callbacks.results);
        assertEquals(0, callbacks.errors);
    }

    @Test public void settingsFailureClosesOwnedRecordingAndReportsRecoverableError() throws Exception {
        Whisper whisper = newWhisper();
        RecordingFixture recording = completedRecording("settings-error");
        CallbackCounter callbacks = new CallbackCounter();
        whisper.setListener(callbacks);

        whisper.start(recording.recording); // Fresh settings have not granted cloud upload consent.
        drainWorker(whisper); // Failure preservation is disk I/O and runs off the UI thread.
        assertTrue(recording.recording.isClosed());
        assertFalse(recording.file.exists());
        assertTrue(whisper.isInProgress()); // Terminal delivery is queued on the main looper.
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertFalse(whisper.isInProgress());
        assertEquals(1, callbacks.errors);
        assertEquals(0, callbacks.results);
    }

    @Test public void busyStartClosesOnlyTheRejectedRequestInput() throws Exception {
        Whisper whisper = newWhisper();
        RecordingFixture first = completedRecording("busy-first");
        RecordingFixture rejected = completedRecording("busy-rejected");
        CallbackCounter callbacks = new CallbackCounter();
        whisper.setListener(callbacks);

        whisper.start(first.recording); // Settings failure keeps the operation busy until its queued error runs.
        drainWorker(whisper);
        assertTrue(first.recording.isClosed());
        assertTrue(whisper.isInProgress());
        whisper.start(rejected.recording);

        assertClosedAndDeleted(rejected);
        assertFalse(first.file.exists());
        assertTrue(whisper.isInProgress());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(whisper.isInProgress());
        assertEquals(1, callbacks.errors);
        assertEquals(0, callbacks.results);
    }

    @Test public void stopClosesLocalInputAndInvalidatesQueuedCallbacks() throws Exception {
        saveLocalSettings();
        Whisper whisper = newWhisper();
        RecordingFixture recording = completedRecording("cancelled");
        CallbackCounter callbacks = new CallbackCounter();
        whisper.setListener(callbacks);

        whisper.start(recording.recording);
        assertTrue(whisper.isInProgress());
        whisper.stop();
        assertClosedAndDeleted(recording);
        drainWorker(whisper);
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertFalse(whisper.isInProgress());
        assertEquals(0, callbacks.updates);
        assertEquals(0, callbacks.results);
        assertEquals(0, callbacks.errors);
    }

    @Test public void operationReusesOneLocalBackendForAllSpoolWindowsThenReleasesIt() throws Exception {
        saveLocalSettings();
        FakeLocalEngineFactory factory = new FakeLocalEngineFactory();
        Whisper whisper = newWhisper(factory);
        RecordingFixture recording = longCompletedRecording("three-windows", 60);
        CallbackCounter callbacks = new CallbackCounter();
        whisper.setListener(callbacks);

        whisper.start(recording.recording);
        drainWorker(whisper);
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(1, factory.backends.size());
        FakeLocalTranscriber backend = factory.backends.get(0);
        assertEquals(3, backend.transcribeCalls.get());
        assertClosedOnce(backend);
        assertNotNull(callbacks.lastResult);
        assertEquals("window-1 window-2 window-3", callbacks.lastResult.getResult());
        assertEquals(1, callbacks.results);
        assertEquals(0, callbacks.errors);
        assertClosedAndDeleted(recording);
    }

    @Test public void twoFacadesSerializeLocalBackendsAndSecondCompletesAfterFirstReleasesLease()
            throws Exception {
        saveLocalSettings();
        FakeLocalEngineFactory factory = new FakeLocalEngineFactory(true, false, false);
        Whisper first = newWhisper(factory);
        Whisper second = newWhisper(factory);
        RecordingFixture firstRecording = completedRecording("lease-first");
        RecordingFixture secondRecording = completedRecording("lease-second");
        CallbackCounter firstCallbacks = new CallbackCounter();
        CallbackCounter secondCallbacks = new CallbackCounter();
        first.setListener(firstCallbacks);
        second.setListener(secondCallbacks);
        try {
            first.start(firstRecording.recording);
            assertTrue("first fake transcriber did not start",
                    factory.firstTranscribeEntered.await(5, TimeUnit.SECONDS));
            second.start(secondRecording.recording);
            assertTrue("second facade did not request its backend",
                    factory.secondCreateAttempted.await(5, TimeUnit.SECONDS));
            assertFalse("second backend entered before the first lease was released",
                    factory.secondBackendCreated.await(100, TimeUnit.MILLISECONDS));

            factory.allowFirstTranscribe.countDown();
            drainWorker(first);
            assertTrue("second backend did not acquire the released lease",
                    factory.secondBackendCreated.await(5, TimeUnit.SECONDS));
            drainWorker(second);
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertEquals(2, factory.backends.size());
            assertClosedOnce(factory.backends.get(0));
            assertClosedOnce(factory.backends.get(1));
            assertEquals(1, firstCallbacks.results);
            assertEquals(1, secondCallbacks.results);
            assertEquals(0, firstCallbacks.errors);
            assertEquals(0, secondCallbacks.errors);
            assertClosedAndDeleted(firstRecording);
            assertClosedAndDeleted(secondRecording);
        } finally {
            factory.allowFirstTranscribe.countDown();
            first.stop();
            second.stop();
        }
    }

    @Test public void failedLocalBackendClosesAndLaterOperationGetsFreshBackend() throws Exception {
        saveLocalSettings();
        FakeLocalEngineFactory factory = new FakeLocalEngineFactory(false, true, false);
        Whisper whisper = newWhisper(factory);
        RecordingFixture firstRecording = completedRecording("retry-failed");
        RecordingFixture retryRecording = completedRecording("retry-success");
        CallbackCounter callbacks = new CallbackCounter();
        whisper.setListener(callbacks);

        whisper.start(firstRecording.recording);
        drainWorker(whisper);
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(1, callbacks.errors);
        assertEquals(0, callbacks.results);
        assertEquals(1, factory.backends.size());
        assertClosedOnce(factory.backends.get(0));
        assertClosedAndDeleted(firstRecording);

        whisper.start(retryRecording.recording);
        drainWorker(whisper);
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(2, factory.backends.size());
        assertClosedOnce(factory.backends.get(1));
        assertEquals(1, callbacks.errors);
        assertEquals(1, callbacks.results);
        assertEquals("window-1", callbacks.lastResult.getResult());
        assertClosedAndDeleted(retryRecording);
    }

    @Test public void secondFacadeWaitsForDelayedNativeCleanupAfterFirstIsCancelled() throws Exception {
        saveLocalSettings();
        FakeLocalEngineFactory factory = new FakeLocalEngineFactory(true, false, true);
        Whisper first = newWhisper(factory);
        Whisper second = newWhisper(factory);
        RecordingFixture firstRecording = completedRecording("cancel-lease-first");
        RecordingFixture secondRecording = completedRecording("cancel-lease-second");
        CallbackCounter firstCallbacks = new CallbackCounter();
        CallbackCounter secondCallbacks = new CallbackCounter();
        first.setListener(firstCallbacks);
        second.setListener(secondCallbacks);
        try {
            first.start(firstRecording.recording);
            assertTrue("first fake transcriber did not start",
                    factory.firstTranscribeEntered.await(5, TimeUnit.SECONDS));
            first.stop();
            assertClosedAndDeleted(firstRecording);
            assertTrue("cancellation did not request native cleanup",
                    factory.firstCloseRequested.await(5, TimeUnit.SECONDS));

            second.start(secondRecording.recording);
            assertTrue("second facade did not reach local lease acquisition",
                    factory.secondCreateAttempted.await(5, TimeUnit.SECONDS));
            assertFalse("cancelled backend released its lease before native cleanup completed",
                    factory.secondBackendCreated.await(100, TimeUnit.MILLISECONDS));

            factory.allowFirstCleanup.countDown();
            assertTrue("fake native cleanup did not release the lease",
                    factory.firstCleanupComplete.await(5, TimeUnit.SECONDS));
            assertTrue("second facade did not finish after native cleanup released the lease",
                    factory.secondBackendCreated.await(5, TimeUnit.SECONDS));
            drainWorker(first);
            drainWorker(second);
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertReleasedOnce(factory.backends.get(0));
            assertClosedOnce(factory.backends.get(1));
            assertEquals(0, firstCallbacks.results);
            assertEquals(0, firstCallbacks.errors);
            assertEquals(1, secondCallbacks.results);
            assertEquals(0, secondCallbacks.errors);
            assertClosedAndDeleted(secondRecording);
        } finally {
            factory.allowFirstTranscribe.countDown();
            factory.allowFirstCleanup.countDown();
            first.stop();
            second.stop();
        }
    }

    private Whisper newWhisper() {
        Whisper whisper = new Whisper(context);
        facades.add(whisper);
        return whisper;
    }

    private Whisper newWhisper(Whisper.LocalEngineFactory factory) {
        Whisper whisper = new Whisper(context, factory);
        facades.add(whisper);
        return whisper;
    }

    private RecordingFixture completedRecording(String name) throws Exception {
        File directory = temporaryFolder.newFolder(name);
        PcmRecording recording = PcmRecording.create(directory);
        recording.write(new byte[]{1, 2, 3, 4}, 0, 4);
        recording.finish();
        File[] files = directory.listFiles();
        assertNotNull(files);
        assertEquals(1, files.length);
        return new RecordingFixture(recording, files[0]);
    }

    private RecordingFixture longCompletedRecording(String name, int seconds) throws Exception {
        File directory = temporaryFolder.newFolder(name);
        PcmRecording recording = PcmRecording.create(directory);
        byte[] fixedBuffer = new byte[32 * 1024];
        for (int i = 0; i < fixedBuffer.length; i += 2) {
            fixedBuffer[i] = 1;
            fixedBuffer[i + 1] = 2;
        }
        long remaining = seconds * (long) PcmRecording.SAMPLE_RATE * PcmRecording.BYTES_PER_SAMPLE;
        while (remaining > 0) {
            int count = (int) Math.min(fixedBuffer.length, remaining);
            recording.write(fixedBuffer, 0, count);
            remaining -= count;
        }
        recording.finish();
        File[] files = directory.listFiles();
        assertNotNull(files);
        assertEquals(1, files.length);
        return new RecordingFixture(recording, files[0]);
    }

    private void saveLocalSettings() throws Exception {
        AiSettings settings = new AiSettings(context);
        ProviderConfig local = new ProviderConfig(Provider.LOCAL, "", "", "", false);
        ProviderConfig enhancement = new ProviderConfig(Provider.OPENROUTER,
                Provider.OPENROUTER.enhancementEndpoint(), Provider.OPENROUTER.defaultEnhancementModel(), "", true);
        settings.saveSettings(local, false, enhancement, "", "", false);
    }

    private static void drainWorker(Whisper whisper) throws Exception {
        Field field = Whisper.class.getDeclaredField("worker");
        field.setAccessible(true);
        ExecutorService worker = (ExecutorService) field.get(whisper);
        worker.submit(() -> { }).get(5, TimeUnit.SECONDS);
    }

    private static void assertClosedOnce(FakeLocalTranscriber backend) {
        assertTrue(backend.closed.get());
        assertEquals(1, backend.closeCalls.get());
        assertReleasedOnce(backend);
    }

    private static void assertReleasedOnce(FakeLocalTranscriber backend) {
        assertTrue(backend.closed.get());
        assertEquals(1, backend.releaseCalls.get());
        assertEquals(1, backend.closeEffects.get());
    }

    private static void assertClosedAndDeleted(RecordingFixture fixture) {
        assertTrue(fixture.recording.isClosed());
        assertFalse(fixture.file.exists());
    }

    private static final class RecordingFixture {
        final PcmRecording recording;
        final File file;

        RecordingFixture(PcmRecording recording, File file) {
            this.recording = recording;
            this.file = file;
        }
    }

    private static final class CallbackCounter implements Whisper.WhisperListener {
        int updates;
        int results;
        int errors;
        WhisperResult lastResult;

        @Override public void onUpdateReceived(String message) { updates++; }
        @Override public void onResultReceived(WhisperResult result) { results++; lastResult = result; }
        @Override public void onError(String message) { errors++; }
    }

    private static final class FakeLocalEngineFactory implements Whisper.LocalEngineFactory {
        final LocalModelGate gate = new LocalModelGate();
        final List<FakeLocalTranscriber> backends = new CopyOnWriteArrayList<>();
        final AtomicInteger attempts = new AtomicInteger();
        final CountDownLatch firstTranscribeEntered = new CountDownLatch(1);
        final CountDownLatch allowFirstTranscribe = new CountDownLatch(1);
        final CountDownLatch secondCreateAttempted = new CountDownLatch(1);
        final CountDownLatch secondBackendCreated = new CountDownLatch(1);
        final CountDownLatch firstCloseRequested = new CountDownLatch(1);
        final CountDownLatch allowFirstCleanup = new CountDownLatch(1);
        final CountDownLatch firstCleanupComplete = new CountDownLatch(1);
        final boolean blockFirstTranscription;
        final boolean failFirstTranscription;
        final boolean delayFirstCleanup;

        FakeLocalEngineFactory() {
            this(false, false, false);
        }

        FakeLocalEngineFactory(boolean blockFirstTranscription,
                               boolean failFirstTranscription,
                               boolean delayFirstCleanup) {
            this.blockFirstTranscription = blockFirstTranscription;
            this.failFirstTranscription = failFirstTranscription;
            this.delayFirstCleanup = delayFirstCleanup;
        }

        @Override public Whisper.LocalTranscriber create(Context ignored)
                throws IOException, InterruptedException {
            int index = attempts.incrementAndGet();
            if (index == 2) secondCreateAttempted.countDown();
            LocalModelGate.Lease lease = gate.acquire();
            FakeLocalTranscriber backend = new FakeLocalTranscriber(this, index, lease);
            backends.add(backend);
            if (index == 2) secondBackendCreated.countDown();
            return backend;
        }
    }

    private static final class FakeLocalTranscriber implements Whisper.LocalTranscriber {
        final FakeLocalEngineFactory factory;
        final int index;
        final LocalModelGate.Lease lease;
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicInteger closeCalls = new AtomicInteger();
        final AtomicInteger closeEffects = new AtomicInteger();
        final AtomicInteger releaseCalls = new AtomicInteger();
        final AtomicInteger transcribeCalls = new AtomicInteger();

        FakeLocalTranscriber(FakeLocalEngineFactory factory, int index, LocalModelGate.Lease lease) {
            this.factory = factory;
            this.index = index;
            this.lease = lease;
        }

        @Override public CloudClient.Transcript transcribe(byte[] pcm, String language,
                                                            Recognizer.Action action)
                throws IOException, InterruptedException {
            int call = transcribeCalls.incrementAndGet();
            if (index == 1 && call == 1 && factory.blockFirstTranscription) {
                factory.firstTranscribeEntered.countDown();
                if (!factory.allowFirstTranscribe.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("fake local transcription timed out");
                }
            }
            if (index == 1 && factory.failFirstTranscription) {
                throw new IOException("synthetic local inference failure");
            }
            return new CloudClient.Transcript("window-" + call, "en");
        }

        @Override public void close() {
            closeCalls.incrementAndGet();
            if (!closed.compareAndSet(false, true)) return;
            closeEffects.incrementAndGet();
            if (index == 1 && factory.delayFirstCleanup) {
                factory.firstCloseRequested.countDown();
                Thread cleanup = new Thread(() -> {
                    try {
                        factory.allowFirstCleanup.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        releaseLease();
                        factory.firstCleanupComplete.countDown();
                    }
                }, "fake-local-native-cleanup");
                cleanup.setDaemon(true);
                cleanup.start();
            } else {
                releaseLease();
            }
        }

        private void releaseLease() {
            releaseCalls.incrementAndGet();
            lease.close();
        }
    }
}
