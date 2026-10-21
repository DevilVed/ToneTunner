package com.devilved.tonetunner.asr;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PcmRecordingTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test public void longRecordingIsReadAsBoundedWindowsWithEveryBytePreserved() throws Exception {
        byte[] source = noisyPcm(65 * PcmRecording.SAMPLE_RATE);
        PcmRecording recording = PcmRecording.create(temporaryFolder.newFolder("recordings"));
        try {
            writeInFixedPieces(recording, source);
            recording.finish();
            assertEquals(source.length, recording.lengthBytes());

            ByteArrayOutputStream joined = new ByteArrayOutputStream(source.length);
            int chunks = 0;
            byte[] chunk;
            while ((chunk = recording.nextChunk()) != null) {
                assertTrue("window exceeded the configured bound", chunk.length <= PcmRecording.MAX_CHUNK_BYTES);
                assertEquals("PCM16 windows must end on a sample", 0, chunk.length & 1);
                joined.write(chunk);
                chunks++;
            }

            assertEquals(3, chunks);
            assertArrayEquals(source, joined.toByteArray());
            assertNull(recording.nextChunk());
        } finally {
            recording.close();
        }
    }

    @Test public void choosesMidpointOfLatestQualifyingQuietRunAndPreservesAudio() throws Exception {
        byte[] source = noisyPcm(32 * PcmRecording.SAMPLE_RATE);
        zeroMillis(source, 21_000, 21_400);
        zeroMillis(source, 25_000, 25_600);
        PcmRecording recording = PcmRecording.create(temporaryFolder.newFolder("recordings"));
        try {
            recording.write(source, 0, source.length);
            recording.finish();

            byte[] first = recording.nextChunk();
            assertNotNull(first);
            assertEquals(25_300L * PcmRecording.SAMPLE_RATE * PcmRecording.BYTES_PER_SAMPLE / 1_000L,
                    first.length);
            assertTrue(first.length >= 20 * PcmRecording.SAMPLE_RATE * PcmRecording.BYTES_PER_SAMPLE);
            assertTrue(first.length <= PcmRecording.MAX_CHUNK_BYTES);
            assertArrayEquals(source, join(first, recording.nextChunk()));
            assertNull(recording.nextChunk());
        } finally {
            recording.close();
        }
    }

    @Test public void allSilenceStillProducesBoundedWindowsAndKeepsEveryByte() throws Exception {
        byte[] source = new byte[32 * PcmRecording.SAMPLE_RATE * PcmRecording.BYTES_PER_SAMPLE];
        PcmRecording recording = PcmRecording.create(temporaryFolder.newFolder("recordings"));
        try {
            recording.write(source, 0, source.length);
            recording.finish();

            byte[] first = recording.nextChunk();
            assertEquals(24 * PcmRecording.SAMPLE_RATE * PcmRecording.BYTES_PER_SAMPLE, first.length);
            assertArrayEquals(source, join(first, recording.nextChunk()));
        } finally {
            recording.close();
        }
    }

    @Test public void absenceOfQuietRunFallsBackToMaximumWindow() throws Exception {
        byte[] source = noisyPcm(29 * PcmRecording.SAMPLE_RATE);
        PcmRecording recording = PcmRecording.create(temporaryFolder.newFolder("recordings"));
        try {
            recording.write(source, 0, source.length);
            recording.finish();

            byte[] first = recording.nextChunk();
            assertEquals(PcmRecording.MAX_CHUNK_BYTES, first.length);
            byte[] second = recording.nextChunk();
            assertEquals(PcmRecording.SAMPLE_RATE * PcmRecording.BYTES_PER_SAMPLE, second.length);
            assertArrayEquals(source, join(first, second));
        } finally {
            recording.close();
        }
    }

    @Test public void handlesTinyInputAndRejectsPartialPcm16Writes() throws Exception {
        PcmRecording recording = PcmRecording.create(temporaryFolder.newFolder("recordings"));
        try {
            byte[] source = {9, 1, 2, 3, 4, 9};
            recording.write(source, 1, 4);
            recording.write(source, 0, 0);
            try {
                recording.write(source, 1, 1);
                fail("expected an odd PCM16 write to be rejected");
            } catch (IllegalArgumentException expected) {
                // PCM16 samples must not be split across writes.
            }
            assertEquals(4L, recording.lengthBytes());
            recording.finish();
            assertArrayEquals(new byte[]{1, 2, 3, 4}, recording.nextChunk());
            assertNull(recording.nextChunk());
        } finally {
            recording.close();
        }
    }

    @Test public void writeFailurePreservesThePhysicalPcmPrefixWrittenBeforeDiskFull() throws Exception {
        File directory = temporaryFolder.newFolder("write-failure-recordings");
        PcmRecording recording = PcmRecording.create(directory);
        File spool = matchingSpoolFiles(directory)[0];
        int persistedBytes = 12_344;
        byte[] source = patternedPcm(128 * 1024);
        DiskFailureFixture fixture = installDiskFullOutput(recording, spool, persistedBytes);

        try {
            try {
                recording.write(source, 0, source.length);
                fail("expected the synthetic disk-full write to fail");
            } catch (IOException expected) {
                // The file-output wrapper writes the available prefix before reporting ENOSPC.
            }

            assertTrue("a useful prefix must remain on disk after the write failure", spool.isFile());
            assertEquals("reported length must match bytes physically written", persistedBytes,
                    recording.lengthBytes());
            assertArrayEquals(Arrays.copyOf(source, persistedBytes), Files.readAllBytes(spool.toPath()));
            assertArrayEquals(Arrays.copyOf(source, persistedBytes), recording.nextChunk());
            assertNull(recording.nextChunk());
            assertEquals("the direct write must not be retried during cleanup", 1,
                    fixture.diskOutput.writeAttempts);
        } finally {
            closeQuietly(fixture.output);
            recording.close();
        }
    }

    @Test public void flushFailurePreservesThePhysicalPcmPrefixWithoutReplayingBufferedBytes()
            throws Exception {
        File directory = temporaryFolder.newFolder("flush-failure-recordings");
        PcmRecording recording = PcmRecording.create(directory);
        File spool = matchingSpoolFiles(directory)[0];
        int persistedBytes = 6_000;
        byte[] source = patternedPcm(32 * 1024);
        DiskFailureFixture fixture = installDiskFullOutput(recording, spool, persistedBytes);

        try {
            recording.write(source, 0, source.length); // Still buffered; the failure occurs at finish().
            try {
                recording.finish();
                fail("expected the synthetic disk-full flush to fail");
            } catch (IOException expected) {
                // The sink accepted a prefix before reporting ENOSPC.
            }

            assertTrue("a useful prefix must remain on disk after the flush failure", spool.isFile());
            assertEquals("length must be reconciled to the persisted prefix", persistedBytes,
                    recording.lengthBytes());
            assertArrayEquals(Arrays.copyOf(source, persistedBytes), Files.readAllBytes(spool.toPath()));
            assertArrayEquals(Arrays.copyOf(source, persistedBytes), recording.nextChunk());
            assertNull(recording.nextChunk());
            assertEquals("failed buffered bytes must not be replayed during salvage", 1,
                    fixture.diskOutput.writeAttempts);
        } finally {
            closeQuietly(fixture.output);
            recording.close();
        }
    }

    @Test public void readingRequiresFinishAndFinishedRecordingCannotBeWritten() throws Exception {
        PcmRecording recording = PcmRecording.create(temporaryFolder.newFolder("recordings"));
        try {
            recording.write(new byte[]{1, 2}, 0, 2);
            try {
                recording.nextChunk();
                fail("expected unfinished audio to be unreadable");
            } catch (IOException expected) {
                // Capture must be stopped before sequential processing begins.
            }

            recording.finish();
            recording.finish();
            try {
                recording.write(new byte[]{3, 4}, 0, 2);
                fail("expected a finished recording to reject writes");
            } catch (IOException expected) {
                // finish() permanently closes the capture side.
            }
            assertArrayEquals(new byte[]{1, 2}, recording.nextChunk());
        } finally {
            recording.close();
        }
    }

    @Test public void closeDeletesBackingFileAndIsIdempotent() throws Exception {
        File directory = temporaryFolder.newFolder("recordings");
        PcmRecording recording = PcmRecording.create(directory);
        recording.write(new byte[]{1, 2}, 0, 2);
        File[] files = directory.listFiles();
        assertNotNull(files);
        assertEquals(1, files.length);
        assertTrue(files[0].getName().endsWith(".pcm"));

        recording.close();
        assertTrue(recording.isClosed());
        assertFalse(files[0].exists());
        recording.close();
        try {
            recording.write(new byte[]{3, 4}, 0, 2);
            fail("expected a closed recording to reject writes");
        } catch (IOException expected) {
            // The owner has released the temporary file.
        }
        try {
            recording.nextChunk();
            fail("expected a closed recording to reject reads");
        } catch (IOException expected) {
            // The owner has released the temporary file.
        }
    }

    @Test public void createRemovesOnlyStaleFilesWithThePcmSpoolName() throws Exception {
        File directory = temporaryFolder.newFolder("recordings");
        File stale = new File(directory, "tonetunner-process-crash.pcm");
        File unrelated = new File(directory, "notes.txt");
        File wrongPrefix = new File(directory, "other-process-crash.pcm");
        File wrongSuffix = new File(directory, "tonetunner-process-crash.wav");
        File matchingDirectory = new File(directory, "tonetunner-directory.pcm");
        assertTrue(stale.createNewFile());
        assertTrue(unrelated.createNewFile());
        assertTrue(wrongPrefix.createNewFile());
        assertTrue(wrongSuffix.createNewFile());
        assertTrue(matchingDirectory.mkdir());
        File child = new File(matchingDirectory, "keep.txt");
        assertTrue(child.createNewFile());

        PcmRecording recording = PcmRecording.create(directory);
        try {
            assertFalse(stale.exists());
            assertTrue(unrelated.exists());
            assertTrue(wrongPrefix.exists());
            assertTrue(wrongSuffix.exists());
            assertTrue(child.exists());
            assertEquals(1, matchingSpoolFiles(directory).length);
        } finally {
            recording.close();
        }
    }

    @Test public void createPreservesEveryLiveRecordingInThisProcess() throws Exception {
        File directory = temporaryFolder.newFolder("recordings");
        PcmRecording first = PcmRecording.create(directory);
        File firstFile = matchingSpoolFiles(directory)[0];
        PcmRecording second = null;
        try {
            second = PcmRecording.create(directory);
            assertTrue(firstFile.exists());
            assertEquals(2, matchingSpoolFiles(directory).length);

            second.close();
            assertTrue(firstFile.exists());
            assertEquals(1, matchingSpoolFiles(directory).length);
        } finally {
            if (second != null) second.close();
            first.close();
        }
        assertFalse(firstFile.exists());
    }

    @Test public void readFailureClosesHandlesAndRemovesTemporaryAudio() throws Exception {
        File directory = temporaryFolder.newFolder("recordings");
        PcmRecording recording = PcmRecording.create(directory);
        recording.write(new byte[]{1, 2, 3, 4}, 0, 4);
        recording.finish();
        File[] files = directory.listFiles();
        assertNotNull(files);
        assertEquals(1, files.length);
        assertTrue(files[0].delete()); // Force the first reader open to fail.

        try {
            recording.nextChunk();
            fail("expected the missing spool file to raise an I/O error");
        } catch (IOException expected) {
            assertTrue(recording.isClosed());
            assertEquals(0, filesIn(directory));
        } finally {
            recording.close();
        }
    }

    @Test public void retainToTransfersDeletionOwnershipAndKeepsTheCompletedAudio() throws Exception {
        File sourceDirectory = temporaryFolder.newFolder("source-recording");
        File recoveryDirectory = temporaryFolder.newFolder("retained-recording");
        byte[] source = {1, 0, 2, 0, 3, 0, 4, 0};
        PcmRecording recording = PcmRecording.create(sourceDirectory);
        recording.write(source, 0, source.length);
        recording.finish();
        File[] sourceFiles = sourceDirectory.listFiles();
        assertNotNull(sourceFiles);
        assertEquals(1, sourceFiles.length);

        File archivedAudio = new File(recoveryDirectory, "audio.pcm");
        recording.retainTo(archivedAudio);
        assertFalse(sourceFiles[0].exists());
        assertTrue(archivedAudio.isFile());
        recording.close();
        assertTrue("closing the old owner must not remove retained audio", archivedAudio.isFile());

        try (PcmRecording reader = PcmRecording.openExisting(archivedAudio, 0, null)) {
            assertArrayEquals(source, reader.nextChunk());
        }
        assertTrue("closing a read-only archive handle must not delete its PCM", archivedAudio.isFile());
    }

    @Test public void failedRecoveryCanKeepTheFinishedSpoolAsAnImportableOrphan() throws Exception {
        File captureDirectory = temporaryFolder.newFolder("dictation-audio");
        byte[] audio = {9, 0, 18, 0, 27, 0};
        PcmRecording recording = PcmRecording.create(captureDirectory);
        recording.write(audio, 0, audio.length);
        recording.finish();

        File orphan = recording.retainForRecoveryFailure();
        assertTrue(orphan.getParentFile().equals(captureDirectory));
        assertTrue(orphan.getName().startsWith("recovery-orphan-"));
        assertTrue(orphan.getName().endsWith(".pcm"));
        recording.close();
        assertTrue("closing the failed request must keep the orphan audio", orphan.isFile());
        assertArrayEquals(audio, java.nio.file.Files.readAllBytes(orphan.toPath()));

        PcmRecording nextCapture = PcmRecording.create(captureDirectory);
        try {
            assertTrue("new capture cleanup must not sweep recovery orphans", orphan.isFile());
        } finally {
            nextCapture.close();
            orphan.delete();
        }
    }

    @Test public void retainedReadFailureClosesHandleWithoutDeletingMovedArchive() throws Exception {
        File directory = temporaryFolder.newFolder("retained-read-failure");
        File archive = new File(directory, "audio.pcm");
        byte[] source = {1, 0, 2, 0};
        try (PcmRecording writer = PcmRecording.create(directory)) {
            writer.write(source, 0, source.length);
            writer.finish();
            writer.retainTo(archive);
        }

        PcmRecording reader = PcmRecording.openExisting(archive, 0, null);
        File movedArchive = new File(directory, "audio-preserved-after-read-failure.pcm");
        assertTrue(archive.renameTo(movedArchive)); // The reader's old path now fails to open.
        try {
            reader.nextChunk();
            fail("expected opening the moved archive at its old path to fail");
        } catch (IOException expected) {
            assertTrue(reader.isClosed());
            assertTrue("read failure must not delete a retained file", movedArchive.isFile());
            assertArrayEquals(source, java.nio.file.Files.readAllBytes(movedArchive.toPath()));
        } finally {
            reader.close();
        }
    }

    @Test public void seekAndHardCutMetadataTrackTheMostRecentWindow() throws Exception {
        byte[] source = noisyPcm(29 * PcmRecording.SAMPLE_RATE);
        PcmRecording recording = PcmRecording.create(temporaryFolder.newFolder("hard-cut-recording"));
        try {
            recording.write(source, 0, source.length);
            recording.finish();

            assertEquals(0L, recording.positionBytes());
            assertFalse(recording.wasHardCut());
            assertEquals(PcmRecording.MAX_CHUNK_BYTES, recording.nextChunk().length);
            assertTrue(recording.wasHardCut());
            assertEquals((long) PcmRecording.MAX_CHUNK_BYTES, recording.positionBytes());

            recording.seekTo(source.length - 2L);
            assertEquals((long) source.length - 2L, recording.positionBytes());
            assertArrayEquals(new byte[]{source[source.length - 2], source[source.length - 1]},
                    recording.nextChunk());
            assertFalse("the final window is not a hard cut", recording.wasHardCut());
        } finally {
            recording.close();
        }
    }

    private static void writeInFixedPieces(PcmRecording recording, byte[] source) throws IOException {
        int offset = 0;
        while (offset < source.length) {
            int count = Math.min(32 * 1024, source.length - offset);
            count &= ~1;
            recording.write(source, offset, count);
            offset += count;
        }
    }

    private static DiskFailureFixture installDiskFullOutput(PcmRecording recording, File spool,
                                                            int availableBytes) throws Exception {
        Field outputField = PcmRecording.class.getDeclaredField("output");
        outputField.setAccessible(true);
        OutputStream originalOutput = (OutputStream) outputField.get(recording);
        originalOutput.close(); // The newly-created spool is empty; replace only its stream fixture.
        PrefixThenDiskFullOutputStream diskOutput =
                new PrefixThenDiskFullOutputStream(spool, availableBytes);
        OutputStream injectedOutput = new BufferedOutputStream(diskOutput, 64 * 1024);
        outputField.set(recording, injectedOutput);
        return new DiskFailureFixture(injectedOutput, diskOutput);
    }

    private static byte[] patternedPcm(int length) {
        byte[] bytes = new byte[length & ~1];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * 37 + 11);
        return bytes;
    }

    private static void closeQuietly(OutputStream output) {
        if (output == null) return;
        try { output.close(); } catch (IOException ignored) {}
    }

    private static final class DiskFailureFixture {
        final OutputStream output;
        final PrefixThenDiskFullOutputStream diskOutput;

        DiskFailureFixture(OutputStream output, PrefixThenDiskFullOutputStream diskOutput) {
            this.output = output;
            this.diskOutput = diskOutput;
        }
    }

    private static byte[] noisyPcm(int samples) {
        byte[] pcm = new byte[samples * PcmRecording.BYTES_PER_SAMPLE];
        for (int i = 0; i < samples; i++) {
            int magnitude = 4_000 + (int) ((i * 1_103_515_245L + 12_345L) & 0x3fffL);
            int value = (i & 1) == 0 ? magnitude : -magnitude;
            pcm[i * 2] = (byte) value;
            pcm[i * 2 + 1] = (byte) (value >>> 8);
        }
        return pcm;
    }

    private static void zeroMillis(byte[] pcm, int startMillis, int endMillis) {
        int start = startMillis * PcmRecording.SAMPLE_RATE * PcmRecording.BYTES_PER_SAMPLE / 1_000;
        int end = endMillis * PcmRecording.SAMPLE_RATE * PcmRecording.BYTES_PER_SAMPLE / 1_000;
        for (int i = start; i < end; i++) pcm[i] = 0;
    }

    private static byte[] join(byte[] first, byte[] second) throws IOException {
        ByteArrayOutputStream joined = new ByteArrayOutputStream(first.length + second.length);
        joined.write(first);
        joined.write(second);
        return joined.toByteArray();
    }

    private static int filesIn(File directory) {
        File[] files = directory.listFiles();
        return files == null ? -1 : files.length;
    }

    private static File[] matchingSpoolFiles(File directory) {
        File[] files = directory.listFiles((dir, name) -> new File(dir, name).isFile()
                && name.startsWith("tonetunner-") && name.endsWith(".pcm"));
        return files == null ? new File[0] : files;
    }

    /** File-backed ENOSPC fixture: accepts a real prefix, then rejects every later byte. */
    private static final class PrefixThenDiskFullOutputStream extends OutputStream {
        private final FileOutputStream fileOutput;
        private int remainingBytes;
        private int writeAttempts;

        PrefixThenDiskFullOutputStream(File file, int availableBytes) throws IOException {
            fileOutput = new FileOutputStream(file);
            remainingBytes = availableBytes;
        }

        @Override public void write(int value) throws IOException {
            if (remainingBytes == 0) throw new IOException("No space left on device");
            fileOutput.write(value);
            remainingBytes--;
        }

        @Override public void write(byte[] data, int offset, int length) throws IOException {
            writeAttempts++;
            if (length == 0) return;
            if (remainingBytes == 0) throw new IOException("No space left on device");
            int accepted = Math.min(length, remainingBytes);
            fileOutput.write(data, offset, accepted);
            remainingBytes -= accepted;
            if (accepted < length) throw new IOException("No space left on device");
        }

        @Override public void flush() throws IOException {
            fileOutput.flush();
        }

        @Override public void close() throws IOException {
            fileOutput.close();
        }
    }
}
