package com.devilved.tonetunner.asr;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

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

    private static void writeInFixedPieces(PcmRecording recording, byte[] source) throws IOException {
        int offset = 0;
        while (offset < source.length) {
            int count = Math.min(32 * 1024, source.length - offset);
            count &= ~1;
            recording.write(source, offset, count);
            offset += count;
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
}
