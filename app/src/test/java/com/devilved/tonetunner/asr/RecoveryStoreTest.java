package com.devilved.tonetunner.asr;

import android.content.Context;
import android.content.ContextWrapper;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.rules.TemporaryFolder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Contract tests for the explicitly retained, disk-backed failed-dictation store. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class RecoveryStoreTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private File noBackupDirectory;
    private StorageContext context;

    @Before public void setUp() throws IOException {
        noBackupDirectory = temporaryFolder.newFolder("no-backup");
        context = new StorageContext(RuntimeEnvironment.getApplication(), noBackupDirectory);
    }

    @Test public void entrySurvivesStoreReopenAndResumesAtExactPcmOffset() throws Exception {
        byte[] audio = {10, 0, 20, 0, 30, 0, 40, 0, 50, 0, 60, 0};
        PcmRecording source = recording("source-first", audio);
        source.setCaptureWarning("Audio capture may have ended early.");
        source.seekTo(4);
        RecoveryStore store = new RecoveryStore(context);
        RecoveryStore.Entry saved = store.save(source, 4, "completed words", "auto", "en", false, null);
        assertEquals("saving must not move the source recording cursor", 4L, source.positionBytes());
        source.close();

        RecoveryStore reopenedStore = new RecoveryStore(context);
        RecoveryStore.Entry reopenedEntry = reopenedStore.get(saved.id);
        assertNotNull(reopenedEntry);
        assertEquals(saved.id, reopenedEntry.id);
        assertEquals("completed words", reopenedEntry.transcript);
        assertEquals("auto", reopenedEntry.requestedLanguage);
        assertEquals("en", reopenedEntry.detectedLanguage);
        assertFalse(reopenedEntry.translate);
        assertFalse(reopenedEntry.overlap);
        assertEquals(4L, reopenedEntry.offset);
        assertEquals((long) audio.length, reopenedEntry.lengthBytes);
        assertEquals("Audio capture may have ended early.", reopenedEntry.captureWarning);

        try (PcmRecording resumed = reopenedStore.open(reopenedEntry)) {
            assertEquals(4L, resumed.positionBytes());
            assertEquals("Audio capture may have ended early.", resumed.getCaptureWarning());
            assertArrayEquals(new byte[]{30, 0, 40, 0, 50, 0, 60, 0}, resumed.nextChunk());
        }

        // Closing a reader releases its handle; it must not discard the retained source.
        assertNotNull(reopenedStore.get(saved.id));
        try (PcmRecording reopenedAgain = reopenedStore.open(reopenedStore.get(saved.id))) {
            assertEquals(4L, reopenedAgain.positionBytes());
            assertArrayEquals(new byte[]{30, 0, 40, 0, 50, 0, 60, 0}, reopenedAgain.nextChunk());
        }
    }

    @Test public void discardRemovesOnlyTheSelectedEntryAndItsAudio() throws Exception {
        RecoveryStore store = new RecoveryStore(context);
        byte[] firstAudio = {1, 0, 2, 0, 3, 0};
        byte[] secondAudio = {7, 0, 8, 0, 9, 0};
        PcmRecording firstSource = recording("source-one", firstAudio);
        PcmRecording secondSource = recording("source-two", secondAudio);
        RecoveryStore.Entry first = store.save(firstSource, 0, "first", "auto", "en", false, null);
        RecoveryStore.Entry second = store.save(secondSource, 2, "second", "fr", "fr", true, null);
        firstSource.close();
        secondSource.close();

        assertTrue("each independently retained dictation needs its own ID", !first.id.equals(second.id));
        assertEquals("saving a new capture must retain earlier failed dictations", 2, store.list().size());
        store.delete(first.id);

        assertNull(store.get(first.id));
        RecoveryStore.Entry remaining = store.get(second.id);
        assertNotNull(remaining);
        assertEquals(1, store.list().size());
        assertEquals(second.id, store.list().get(0).id);
        try (PcmRecording resumed = store.open(remaining)) {
            assertEquals(2L, resumed.positionBytes());
            assertArrayEquals(new byte[]{8, 0, 9, 0}, resumed.nextChunk());
        }
        assertEquals(1, filesWithSuffix(new File(noBackupDirectory, "dictation-recovery"), ".pcm").size());
    }

    @Test public void separateStoresCannotRetryOrDiscardTheSameOpenDictation() throws Exception {
        RecoveryStore first = new RecoveryStore(context);
        RecoveryStore second = new RecoveryStore(context);
        PcmRecording source = recording("lease-source", new byte[]{1, 0, 2, 0});
        RecoveryStore.Entry entry = first.save(source, 0, "", "auto", "", false, null);
        source.close();
        PcmRecording opened = first.open(entry);
        try {
            org.junit.Assert.assertThrows(IOException.class, () -> second.open(second.get(entry.id)));
            org.junit.Assert.assertThrows(IOException.class, () -> second.delete(entry.id));
        } finally { opened.close(); }
        try (PcmRecording afterClose = second.open(second.get(entry.id))) {
            assertArrayEquals(new byte[]{1, 0, 2, 0}, afterClose.nextChunk());
        }
        second.delete(entry.id);
        assertNull(first.get(entry.id));
    }

    @Test public void deliveryClaimBlocksRetryAndDiscardAfterReaderCloses() throws Exception {
        RecoveryStore first = new RecoveryStore(context);
        RecoveryStore second = new RecoveryStore(context);
        PcmRecording source = recording("delivery-source", new byte[]{1, 0, 2, 0});
        RecoveryStore.Entry entry = first.save(source, 0, "finished", "auto", "en", false, null);
        source.close();

        PcmRecording opened = first.open(entry);
        first.prepareDelivery(entry);
        opened.close();
        try {
            org.junit.Assert.assertThrows(IOException.class, () -> second.open(second.get(entry.id)));
            org.junit.Assert.assertThrows(IOException.class, () -> second.delete(entry.id));
        } finally {
            first.finishDelivery(entry, false);
        }

        PcmRecording retried = second.open(second.get(entry.id));
        first.prepareDelivery(entry);
        retried.close();
        first.finishDelivery(entry, true);
        assertNull("successful delivery removes only after the reader has closed", second.get(entry.id));
    }

    @Test public void fallbackRootRemainsSearchableAfterPrimaryIsRepaired() throws Exception {
        File primary = new File(noBackupDirectory, "dictation-recovery");
        File fallback = new File(noBackupDirectory, "dictation-recovery-fallback");
        Files.write(primary.toPath(), new byte[]{1});

        RecoveryStore store = new RecoveryStore(context);
        byte[] audio = {11, 0, 22, 0, 33, 0};
        PcmRecording source = recording("fallback-source", audio);
        RecoveryStore.Entry saved = store.save(source, 2, "earlier words", "auto", "en", false, null);
        source.close();
        assertTrue(fallback.isDirectory());

        assertTrue("repairing the primary root must not hide fallback entries", primary.delete());
        assertTrue(primary.mkdir());
        RecoveryStore reopenedStore = new RecoveryStore(context);
        assertEquals(saved.id, reopenedStore.list().get(0).id);
        RecoveryStore.Entry reopened = reopenedStore.get(saved.id);
        assertNotNull(reopened);
        assertEquals("earlier words", reopened.transcript);
        try (PcmRecording reader = reopenedStore.open(reopened)) {
            assertArrayEquals(new byte[]{22, 0, 33, 0}, reader.nextChunk());
        }

        reopenedStore.delete(saved.id);
        assertNull(reopenedStore.get(saved.id));
        assertEquals("deletion must reach the fallback root", 0, filesWithSuffix(fallback, ".pcm").size());
    }

    @Test public void recoveryOrphanSurvivesUnavailableRootsAndImportsWhenStorageReturns() throws Exception {
        File primary = new File(noBackupDirectory, "dictation-recovery");
        File fallback = new File(noBackupDirectory, "dictation-recovery-fallback");
        Files.write(primary.toPath(), new byte[]{1});
        Files.write(fallback.toPath(), new byte[]{1});
        byte[] audio = {41, 0, 42, 0, 43, 0};
        PcmRecording source = recording("dictation-audio", audio);
        source.retainForRecoveryFailure();
        source.close();
        File captureDirectory = new File(noBackupDirectory, "dictation-audio");
        File[] orphanFiles = captureDirectory.listFiles((directory, name) ->
                name.startsWith("recovery-orphan-") && name.endsWith(".pcm"));
        assertNotNull(orphanFiles);
        assertEquals(1, orphanFiles.length);
        File orphan = orphanFiles[0];
        try {
            new RecoveryStore(context).list();
            fail("unavailable recovery roots should not make an orphan disappear");
        } catch (IOException expected) {
            assertTrue(orphan.isFile());
            assertArrayEquals(audio, Files.readAllBytes(orphan.toPath()));
        }

        assertTrue(primary.delete());
        assertTrue(fallback.delete());
        RecoveryStore repairedStore = new RecoveryStore(context);
        List<RecoveryStore.Entry> entries = repairedStore.list();
        assertEquals(1, entries.size());
        RecoveryStore.Entry imported = entries.get(0);
        assertEquals("", imported.transcript);
        assertEquals("auto", imported.requestedLanguage);
        assertEquals(0L, imported.offset);
        assertFalse(imported.translate);
        assertFalse(orphan.exists());
        try (PcmRecording reader = repairedStore.open(imported)) {
            assertArrayEquals(audio, reader.nextChunk());
        }
        repairedStore.delete(imported.id);
    }

    @Test public void retryProgressUpdateReusesTheSameIdAndKeepsTheFullAudio() throws Exception {
        RecoveryStore store = new RecoveryStore(context);
        byte[] audio = {1, 0, 2, 0, 3, 0, 4, 0, 5, 0};
        PcmRecording originalSource = recording("source-before-retry", audio);
        originalSource.setCaptureWarning("Some captured audio may be missing.");
        RecoveryStore.Entry original = store.save(
                originalSource, 2, "first window", "auto", "en", false, null);
        originalSource.close();

        PcmRecording retrySource = store.open(store.get(original.id));
        RecoveryStore.Entry updated = store.save(
                retrySource, 6, "first window plus retry window", "auto", "en", false, original.id);
        retrySource.close();

        assertEquals("a retry failure must update its retained entry, not create a duplicate",
                original.id, updated.id);
        assertEquals(1, store.list().size());
        RecoveryStore.Entry retained = store.get(original.id);
        assertNotNull(retained);
        assertEquals(6L, retained.offset);
        assertEquals((long) audio.length, retained.lengthBytes);
        assertEquals("Some captured audio may be missing.", retained.captureWarning);
        assertEquals("first window plus retry window", retained.transcript);
        try {
            store.open(original);
            fail("a stale recovery snapshot must not be combined with newer progress metadata");
        } catch (IOException expected) {
            // The retry UI must refresh the entry before opening a changed checkpoint.
        }
        try (PcmRecording resumed = store.open(retained)) {
            assertEquals(6L, resumed.positionBytes());
            resumed.seekTo(0);
            assertArrayEquals(audio, resumed.nextChunk());
        }
    }

    @Test public void overlapEligibilitySurvivesReopenAndCanBeUpdatedForTheNextWindow() throws Exception {
        RecoveryStore store = new RecoveryStore(context);
        byte[] audio = {1, 0, 2, 0, 3, 0, 4, 0};
        PcmRecording source = recording("source-overlap", audio);
        RecoveryStore.Entry boundary = store.save(
                source, 2, "words before hard cut", "auto", "en", false, null, true);
        source.close();

        RecoveryStore reopenedStore = new RecoveryStore(context);
        RecoveryStore.Entry persistedBoundary = reopenedStore.get(boundary.id);
        assertNotNull(persistedBoundary);
        assertTrue("resume must know when transcript deduplication is eligible", persistedBoundary.overlap);

        PcmRecording retrySource = reopenedStore.open(persistedBoundary);
        RecoveryStore.Entry nextWindow = reopenedStore.save(
                retrySource, 6, "words before hard cut and later words", "auto", "en", false,
                boundary.id, false);
        retrySource.close();

        assertEquals(boundary.id, nextWindow.id);
        assertFalse("a quiet boundary must disable hard-cut deduplication", nextWindow.overlap);
        assertFalse(reopenedStore.get(boundary.id).overlap);
    }

    @Test public void invalidRetryCheckpointKeepsTheExistingIdMetadataAndAudio() throws Exception {
        RecoveryStore store = new RecoveryStore(context);
        byte[] originalAudio = {11, 0, 22, 0, 33, 0, 44, 0};
        PcmRecording originalSource = recording("source-original", originalAudio);
        RecoveryStore.Entry original = store.save(
                originalSource, 2, "already completed", "auto", "en", false, null);
        originalSource.close();

        PcmRecording retrySource = store.open(store.get(original.id));
        try {
            store.save(retrySource, originalAudio.length + 2L, "replacement text", "en", "fr", true,
                    original.id);
            fail("expected an invalid resume offset to fail before replacing recovery metadata");
        } catch (IOException expected) {
            // A failed checkpoint must leave the prior entry recoverable.
        } catch (IllegalArgumentException expected) {
            // The API may reject invalid offsets as argument errors.
        } finally {
            retrySource.close();
        }

        RecoveryStore.Entry retained = store.get(original.id);
        assertNotNull(retained);
        assertEquals(original.id, retained.id);
        assertEquals("already completed", retained.transcript);
        assertEquals("auto", retained.requestedLanguage);
        assertEquals("en", retained.detectedLanguage);
        assertFalse(retained.translate);
        assertEquals(2L, retained.offset);
        try (PcmRecording reopened = store.open(retained)) {
            reopened.seekTo(0);
            assertArrayEquals(originalAudio, reopened.nextChunk());
        }
    }

    @Test public void failedRetrySavePreservesExistingEntryAndAudioWhenStorageIsUnavailable() throws Exception {
        RecoveryStore store = new RecoveryStore(context);
        byte[] originalAudio = {12, 0, 23, 0, 34, 0, 45, 0};
        PcmRecording originalSource = recording("source-interrupted-update", originalAudio);
        RecoveryStore.Entry original = store.save(
                originalSource, 2, "completed before interruption", "auto", "en", false, null);
        originalSource.close();

        File recoveryDirectory = new File(noBackupDirectory, "dictation-recovery");
        File parkedRecoveryDirectory = new File(noBackupDirectory, "recovery-parked-during-write");
        assertTrue("test setup must be able to isolate the store directory",
                recoveryDirectory.renameTo(parkedRecoveryDirectory));
        PcmRecording retrySource = recording("source-for-interrupted-update", originalAudio);
        try {
            // Replacing the store directory with a file forces recovery I/O to fail without
            // depending on permission behavior, which differs between Android and host systems.
            Files.write(recoveryDirectory.toPath(), new byte[]{1});
            try {
                store.save(retrySource, 6, "uncommitted retry text", "auto", "en", false,
                        original.id, true);
                fail("expected the retry checkpoint write to fail while storage is unavailable");
            } catch (IOException | IllegalArgumentException expected) {
                // A failed metadata write must leave the previous snapshot and PCM recoverable.
            }
        } finally {
            retrySource.close();
            if (recoveryDirectory.isFile()) assertTrue(recoveryDirectory.delete());
            assertTrue("test must restore the original recovery directory",
                    parkedRecoveryDirectory.renameTo(recoveryDirectory));
        }

        RecoveryStore.Entry retained = store.get(original.id);
        assertNotNull(retained);
        assertEquals("completed before interruption", retained.transcript);
        assertEquals(2L, retained.offset);
        assertFalse("failed checkpoint must not replace the previous overlap state", retained.overlap);
        try (PcmRecording reopened = store.open(retained)) {
            reopened.seekTo(0);
            assertArrayEquals("failed checkpoint must preserve all original PCM", originalAudio,
                    reopened.nextChunk());
        }
    }

    @Test public void failedOpenPreservesRetainedAudioAndMetadata() throws Exception {
        RecoveryStore store = new RecoveryStore(context);
        byte[] audio = {1, 0, 2, 0, 3, 0, 4, 0};
        PcmRecording source = recording("source-invalid-offset", audio);
        RecoveryStore.Entry saved = store.save(source, 6, "done", "auto", "en", false, null);
        source.close();

        File recoveryDirectory = new File(noBackupDirectory, "dictation-recovery");
        File archivedAudio = onlyPcmFile(recoveryDirectory);
        File archivedMetadata = onlySuffixedFile(recoveryDirectory, ".json");
        byte[] metadataBefore = Files.readAllBytes(archivedMetadata.toPath());
        File temporarilyMovedAudio = new File(archivedAudio.getParentFile(), "temporarily-moved.pcm");
        assertTrue("test setup must make retained audio temporarily unavailable",
                archivedAudio.renameTo(temporarilyMovedAudio));

        PcmRecording opened = null;
        try {
            opened = store.open(saved);
            fail("resume must reject an entry whose retained audio is temporarily unavailable");
        } catch (IOException | IllegalArgumentException expected) {
            // Failed opens must not treat the persistent PCM file as a disposable spool.
        } finally {
            if (opened != null) opened.close();
            assertTrue("test setup must restore the retained audio", temporarilyMovedAudio.renameTo(archivedAudio));
        }

        assertTrue("failed open must preserve the retained audio path", archivedAudio.isFile());
        assertArrayEquals(audio, Files.readAllBytes(archivedAudio.toPath()));
        assertArrayEquals("failed open must preserve the metadata bytes", metadataBefore,
                Files.readAllBytes(archivedMetadata.toPath()));
    }

    @Test public void pendingAudioWithoutMetadataRemainsDiscoverableFromOffsetZero() throws Exception {
        RecoveryStore store = new RecoveryStore(context);
        String id = UUID.randomUUID().toString();
        byte[] audio = {31, 0, 32, 0, 33, 0};
        File recoveryDirectory = new File(noBackupDirectory, "dictation-recovery");
        File pendingDirectory = new File(recoveryDirectory, ".pending-" + id);
        assertTrue(pendingDirectory.mkdirs());
        Files.write(new File(pendingDirectory, "audio.pcm").toPath(), audio);

        List<RecoveryStore.Entry> entries = store.list();
        assertEquals(1, entries.size());
        RecoveryStore.Entry orphan = entries.get(0);
        assertEquals(id, orphan.id);
        assertEquals(0L, orphan.offset);
        assertEquals("", orphan.transcript);
        try (PcmRecording reopened = store.open(orphan)) {
            assertEquals(0L, reopened.positionBytes());
            assertArrayEquals(audio, reopened.nextChunk());
        }
    }

    @Test public void malformedMetadataAndTraversalIdsCannotAffectValidOrOutsideData() throws Exception {
        RecoveryStore store = new RecoveryStore(context);
        byte[] validAudio = {3, 0, 4, 0};
        PcmRecording source = recording("source-valid", validAudio);
        RecoveryStore.Entry valid = store.save(source, 2, "valid", "auto", "en", false, null);
        source.close();

        File recoveryDirectory = new File(noBackupDirectory, "dictation-recovery");
        File malformedMetadata = onlySuffixedFile(recoveryDirectory, ".json");
        Files.write(malformedMetadata.toPath(),
                ("{\"id\":\"" + valid.id + "\",\"transcript\":\"completed prefix\","
                        + "\"requestedLanguage\":\"auto\",\"detectedLanguage\":\"en\","
                        + "\"offset\":999,\"audio\":\"../../outside.pcm\"}")
                        .getBytes(StandardCharsets.UTF_8));
        File outsideSentinel = new File(noBackupDirectory, "outside.pcm");
        byte[] outsideBytes = {71, 0, 72, 0};
        Files.write(outsideSentinel.toPath(), outsideBytes);

        List<RecoveryStore.Entry> listed = store.list();
        assertEquals(1, listed.size());
        assertEquals(valid.id, listed.get(0).id);
        assertTrue("listing should not erase an unparseable metadata file", malformedMetadata.isFile());
        assertEquals("corrupt progress must safely resume from the start", 0L, listed.get(0).offset);
        assertEquals("corrupt metadata must not trust a claimed transcript", "", listed.get(0).transcript);
        try (PcmRecording reopened = store.open(listed.get(0))) {
            assertEquals(0L, reopened.positionBytes());
            assertArrayEquals(validAudio, reopened.nextChunk());
        }

        // Invalid IDs may be rejected or treated as missing; either way, they must stay in storage.
        for (String invalidId : Arrays.asList("../outside", "../../outside.pcm", outsideSentinel.getAbsolutePath())) {
            try { store.get(invalidId); } catch (IOException | IllegalArgumentException expected) { }
            try { store.delete(invalidId); } catch (IOException | IllegalArgumentException expected) { }
        }
        assertTrue(outsideSentinel.isFile());
        assertArrayEquals(outsideBytes, Files.readAllBytes(outsideSentinel.toPath()));
        assertNotNull(store.get(valid.id));
    }

    private PcmRecording recording(String directoryName, byte[] audio) throws Exception {
        PcmRecording recording = PcmRecording.create(new File(noBackupDirectory, directoryName));
        recording.write(audio, 0, audio.length);
        recording.finish();
        return recording;
    }

    private static File onlyPcmFile(File directory) {
        return onlySuffixedFile(directory, ".pcm");
    }

    private static File onlySuffixedFile(File directory, String suffix) {
        List<File> files = filesWithSuffix(directory, suffix);
        assertEquals("expected exactly one " + suffix + " file in " + directory, 1, files.size());
        return files.get(0);
    }

    private static List<File> filesWithSuffix(File directory, String suffix) {
        List<File> result = new java.util.ArrayList<>();
        collectFiles(directory, suffix, result);
        return result;
    }

    private static void collectFiles(File directory, String suffix, List<File> result) {
        File[] children = directory.listFiles();
        assertNotNull("unable to inspect " + directory, children);
        for (File child : children) {
            if (child.isDirectory()) collectFiles(child, suffix, result);
            else if (child.getName().endsWith(suffix)) result.add(child);
        }
    }

    private static final class StorageContext extends ContextWrapper {
        private final File noBackupDirectory;

        StorageContext(Context base, File noBackupDirectory) {
            super(base);
            this.noBackupDirectory = noBackupDirectory;
        }

        @Override public Context getApplicationContext() { return this; }

        @Override public File getNoBackupFilesDir() { return noBackupDirectory; }
    }
}
