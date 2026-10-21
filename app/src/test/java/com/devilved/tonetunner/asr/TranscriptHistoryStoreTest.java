package com.devilved.tonetunner.asr;

import android.content.Context;
import android.content.ContextWrapper;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.*;

/** Contract tests for durable text history, independent from audio recovery. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class TranscriptHistoryStoreTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private File noBackupDirectory;
    private Context context;

    @Before public void setUp() throws IOException {
        noBackupDirectory = temporaryFolder.newFolder("no-backup");
        context = new StorageContext(RuntimeEnvironment.getApplication(), noBackupDirectory);
    }

    @Test public void rawCheckpointsAndFinalResultReopenUnderOneStableId() throws Exception {
        TranscriptHistoryStore store = new TranscriptHistoryStore(context);
        store.saveRaw("session-1", "first words", "auto");
        TranscriptHistoryStore.Entry initial = store.get("session-1");
        assertNotNull(initial);
        assertEquals("first words", initial.rawText);
        assertEquals("in-progress text falls back to raw transcript", "first words", initial.text);
        assertFalse(initial.isComplete);

        store.saveRaw("session-1", "first words and more", "en");
        TranscriptHistoryStore.Entry checkpoint = store.get("session-1");
        assertEquals(initial.createdAt, checkpoint.createdAt);
        assertEquals("first words and more", checkpoint.rawText);

        store.saveResult("session-1", "first words and more", "First words and more.", "en", "warning");
        TranscriptHistoryStore reopened = new TranscriptHistoryStore(context);
        TranscriptHistoryStore.Entry result = reopened.get("session-1");
        assertNotNull(result);
        assertEquals("session-1", result.id);
        assertEquals(initial.createdAt, result.createdAt);
        assertEquals("first words and more", result.rawText);
        assertEquals("First words and more.", result.text);
        assertEquals("en", result.language);
        assertEquals("warning", result.warning);
        assertTrue(result.isComplete);
        assertEquals("upsert must keep one history entry", 1, reopened.list(10, 0).size());
    }

    @Test public void retryCheckpointDoesNotReplaceACompletedEntryBeforeNextSuccessfulResult()
            throws Exception {
        TranscriptHistoryStore store = new TranscriptHistoryStore(context);
        store.saveResult("retry-session", "original recognized text", "Enhanced final text.",
                "en", "Original enhancement note.");
        TranscriptHistoryStore.Entry completed = store.get("retry-session");
        assertNotNull(completed);

        // Whisper checkpoints the same recovery id when a retry starts. If that retry is canceled,
        // the previously completed and delivered text must remain available in text history.
        store.saveRaw("retry-session", "new partial retry text", "fr");

        TranscriptHistoryStore.Entry afterInterruptedRetry = store.get("retry-session");
        assertNotNull(afterInterruptedRetry);
        assertTrue(afterInterruptedRetry.isComplete);
        assertEquals(completed.createdAt, afterInterruptedRetry.createdAt);
        assertEquals(completed.updatedAt, afterInterruptedRetry.updatedAt);
        assertEquals("original recognized text", afterInterruptedRetry.rawText);
        assertEquals("Enhanced final text.", afterInterruptedRetry.text);
        assertEquals("en", afterInterruptedRetry.language);
        assertEquals("Original enhancement note.", afterInterruptedRetry.warning);
        assertEquals("a retry checkpoint must not create a duplicate row", 1, store.list(10, 0).size());

        store.saveResult("retry-session", "new complete retry transcript", "Retry completed.",
                "fr", "Retry note.");
        TranscriptHistoryStore.Entry retryResult = store.get("retry-session");
        assertTrue(retryResult.isComplete);
        assertEquals(completed.createdAt, retryResult.createdAt);
        assertEquals("new complete retry transcript", retryResult.rawText);
        assertEquals("Retry completed.", retryResult.text);
        assertEquals("Retry note.", retryResult.warning);
    }

    @Test public void longUnicodeRawAndFinalTextRoundTripWithoutCursorWindowTruncation() throws Exception {
        String raw = "漢🙂e\u0301".repeat(240_000);
        assertTrue(raw.getBytes(StandardCharsets.UTF_8).length > 2 * 1024 * 1024);
        String enhanced = "開始\n" + raw + "\n完了✅";

        new TranscriptHistoryStore(context).saveResult("large", raw, enhanced, "ja", null);

        TranscriptHistoryStore reopened = new TranscriptHistoryStore(context);
        TranscriptHistoryStore.Entry entry = reopened.get("large");
        assertNotNull(entry);
        assertEquals(raw, entry.rawText);
        assertEquals(enhanced, entry.text);

        TranscriptHistoryStore.Summary summary = reopened.list(1, 0).get(0);
        assertTrue("list previews stay bounded instead of loading transcript bodies",
                summary.preview.codePointCount(0, summary.preview.length())
                        <= TranscriptHistoryStore.Summary.PREVIEW_CODE_POINT_LIMIT);
        assertTrue(summary.hasDifferentRaw);
    }

    @Test public void pagesAreNewestFirstAndExposeOnlyBoundedPreviews() throws Exception {
        TranscriptHistoryStore store = new TranscriptHistoryStore(context);
        String longText = "字".repeat(2_000);
        store.saveResult("first", longText, longText, "zh", null);
        Thread.sleep(2);
        store.saveResult("second", "second raw", "second result", "en", null);
        Thread.sleep(2);
        store.saveResult("third", "third raw", "third result", "fr", null);

        List<TranscriptHistoryStore.Summary> firstPage = store.list(2, 0);
        List<TranscriptHistoryStore.Summary> secondPage = store.list(2, 2);
        assertEquals(2, firstPage.size());
        assertEquals(1, secondPage.size());
        assertEquals("third", firstPage.get(0).id);
        assertEquals("third result", firstPage.get(0).preview);
        assertEquals("second", firstPage.get(1).id);
        assertEquals("first", secondPage.get(0).id);
        assertTrue(firstPage.get(0).preview.codePointCount(0, firstPage.get(0).preview.length())
                <= TranscriptHistoryStore.Summary.PREVIEW_CODE_POINT_LIMIT);
    }

    @Test public void deleteRemovesOnlyTheSelectedTextHistoryEntry() throws Exception {
        TranscriptHistoryStore store = new TranscriptHistoryStore(context);
        store.saveResult("first", "first raw", "first result", "en", null);
        store.saveResult("second", "second raw", "second result", "en", null);

        store.delete("first");

        assertNull(store.get("first"));
        TranscriptHistoryStore.Entry remaining = store.get("second");
        assertNotNull(remaining);
        assertEquals("second result", remaining.text);
        assertEquals(1, store.list(10, 0).size());
    }

    @Test public void blankRawTextDoesNotCreateAHistoryEntry() throws Exception {
        TranscriptHistoryStore store = new TranscriptHistoryStore(context);
        store.saveRaw("blank", " \n\t", "auto");
        store.saveResult("also-blank", "", "generated output", "en", null);
        assertTrue(store.list(10, 0).isEmpty());
    }

    @Test public void storageFailureIsReportedAsIOException() throws Exception {
        File blockedPath = temporaryFolder.newFile("not-a-directory");
        Context blockedContext = new StorageContext(RuntimeEnvironment.getApplication(), blockedPath);
        try {
            new TranscriptHistoryStore(blockedContext).saveRaw("session", "recognized", "en");
            fail("a database path below a regular file must report its storage failure");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
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
