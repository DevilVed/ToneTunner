package com.devilved.tonetunner.asr;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.HashSet;
import java.util.Set;

/**
 * A disk-backed spool for mono, 16 kHz, signed PCM16 audio.
 *
 * <p>Capture writes directly to a private temporary file through a fixed-size buffer. Once
 * finished, callers can read bounded windows in order. Boundaries prefer a quiet 20 ms frame
 * run, inspired by the pause-aware segmentation used by faster-whisper; unlike a VAD filter,
 * this class never removes or overlaps audio.</p>
 */
public final class PcmRecording implements AutoCloseable {
    public static final int SAMPLE_RATE = 16_000;
    public static final int BYTES_PER_SAMPLE = 2;
    public static final int MAX_CHUNK_BYTES = SAMPLE_RATE * BYTES_PER_SAMPLE * 28;

    private static final int FRAME_MILLIS = 20;
    private static final int FRAME_BYTES = SAMPLE_RATE * BYTES_PER_SAMPLE * FRAME_MILLIS / 1_000;
    private static final long MIN_BOUNDARY_BYTES = SAMPLE_RATE * BYTES_PER_SAMPLE * 20L;
    private static final long LOOKBACK_BYTES = SAMPLE_RATE * BYTES_PER_SAMPLE * 8L;
    private static final int MIN_QUIET_FRAMES = 300 / FRAME_MILLIS;
    private static final int QUIET_MEAN_ABSOLUTE_THRESHOLD = 256;
    private static final int OUTPUT_BUFFER_BYTES = 64 * 1024;
    private static final Object LIVE_FILES_LOCK = new Object();
    private static final Set<String> LIVE_FILE_PATHS = new HashSet<>();

    private final File file;
    private final String registryPath;
    private OutputStream output;
    private RandomAccessFile input;
    private long lengthBytes;
    private long readPosition;
    private boolean finished;
    private boolean closed;

    private PcmRecording(File file, String registryPath, OutputStream output) {
        this.file = file;
        this.registryPath = registryPath;
        this.output = output;
    }

    /** Creates a unique temporary PCM file under the app's private storage directory. */
    public static PcmRecording create(File privateDirectory) throws IOException {
        if (privateDirectory == null) throw new IllegalArgumentException("Private directory is required.");
        if (privateDirectory.exists() && !privateDirectory.isDirectory()) {
            throw new IOException("Unable to create private recording storage.");
        }
        if (!privateDirectory.isDirectory() && !privateDirectory.mkdirs() && !privateDirectory.isDirectory()) {
            throw new IOException("Unable to create private recording storage.");
        }

        synchronized (LIVE_FILES_LOCK) {
            removeStaleSpoolFiles(privateDirectory);
            File file = File.createTempFile("tonetunner-", ".pcm", privateDirectory);
            final String registryPath;
            try {
                registryPath = file.getCanonicalPath();
            } catch (IOException e) {
                file.delete();
                throw new IOException("Unable to open private recording storage.");
            }
            try {
                OutputStream output = new BufferedOutputStream(
                        new FileOutputStream(file), OUTPUT_BUFFER_BYTES);
                LIVE_FILE_PATHS.add(registryPath);
                return new PcmRecording(file, registryPath, output);
            } catch (IOException e) {
                file.delete();
                throw new IOException("Unable to open private recording storage.");
            }
        }
    }

    /** Appends PCM16 bytes without retaining a copy of the captured audio in memory. */
    public synchronized void write(byte[] data, int offset, int length) throws IOException {
        if (data == null) throw new IllegalArgumentException("Audio data is required.");
        if (offset < 0 || length < 0 || offset > data.length - length) {
            throw new IndexOutOfBoundsException("Invalid audio data range.");
        }
        if ((length & 1) != 0) throw new IllegalArgumentException("PCM16 writes must contain whole samples.");
        ensureWritable();
        if (length == 0) return;
        if (Long.MAX_VALUE - lengthBytes < length) {
            failAndDelete();
            throw new IOException("Recording is too large to store safely.");
        }
        try {
            output.write(data, offset, length);
            lengthBytes += length;
        } catch (IOException e) {
            failAndDelete();
            throw new IOException("Unable to write recording audio to private storage.");
        }
    }

    /** Flushes and closes the capture stream so the completed recording can be read. */
    public synchronized void finish() throws IOException {
        ensureOpen();
        if (finished) return;
        try {
            output.flush();
            output.close();
            output = null;
            finished = true;
        } catch (IOException e) {
            failAndDelete();
            throw new IOException("Unable to finish recording audio in private storage.");
        }
    }

    /** Returns the number of bytes successfully written so far. */
    public synchronized long lengthBytes() {
        return lengthBytes;
    }

    /**
     * Reads the next bounded, even-length PCM16 window, or {@code null} at end of file.
     * A quiet run is preferred only when the recording has more than one maximum-sized window
     * remaining. Otherwise the final short window is returned unchanged.
     */
    public synchronized byte[] nextChunk() throws IOException {
        ensureOpen();
        if (!finished) throw new IOException("Finish the recording before reading audio windows.");
        if (readPosition >= lengthBytes) return null;

        try {
            if (input == null) input = new RandomAccessFile(file, "r");
            long end = chooseChunkEnd();
            long chunkLength = end - readPosition;
            if (chunkLength <= 0 || chunkLength > MAX_CHUNK_BYTES || (chunkLength & 1L) != 0) {
                throw new IOException("Invalid recording window boundary.");
            }
            byte[] chunk = new byte[(int) chunkLength];
            input.seek(readPosition);
            input.readFully(chunk);
            readPosition = end;
            return chunk;
        } catch (IOException e) {
            failAndDelete();
            throw new IOException("Unable to read recording audio from private storage.");
        }
    }

    /** Closes open handles and deletes the temporary recording. Safe to call repeatedly. */
    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            deleteFileIfPresent();
            return;
        }

        closed = true;
        IOException failure = null;
        if (output != null) {
            try {
                output.close();
            } catch (IOException e) {
                failure = new IOException("Unable to close private recording storage.");
            } finally {
                output = null;
            }
        }
        if (input != null) {
            try {
                input.close();
            } catch (IOException e) {
                if (failure == null) failure = new IOException("Unable to close private recording storage.");
            } finally {
                input = null;
            }
        }
        try {
            deleteFileIfPresent();
        } catch (IOException e) {
            if (failure == null) failure = e;
        }
        if (failure != null) throw failure;
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    private long chooseChunkEnd() throws IOException {
        long remaining = lengthBytes - readPosition;
        if (remaining <= MAX_CHUNK_BYTES) return lengthBytes;

        long hardEnd = readPosition + MAX_CHUNK_BYTES;
        long scanStart = Math.max(readPosition + MIN_BOUNDARY_BYTES, hardEnd - LOOKBACK_BYTES);
        scanStart = roundUpToFrame(scanStart);
        long scanEnd = hardEnd - (hardEnd % FRAME_BYTES);
        if (scanEnd - scanStart < MIN_QUIET_FRAMES * (long) FRAME_BYTES) return hardEnd;

        int scanBytes = (int) (scanEnd - scanStart); // At most eight seconds of PCM.
        byte[] scan = new byte[scanBytes];
        input.seek(scanStart);
        input.readFully(scan);

        long quietStart = -1;
        long latestBoundary = -1;
        for (int frameOffset = 0; frameOffset < scan.length; frameOffset += FRAME_BYTES) {
            boolean quiet = isQuietFrame(scan, frameOffset);
            long absolutePosition = scanStart + frameOffset;
            if (quiet) {
                if (quietStart < 0) quietStart = absolutePosition;
            } else if (quietStart >= 0) {
                long runEnd = absolutePosition;
                if (runEnd - quietStart >= MIN_QUIET_FRAMES * (long) FRAME_BYTES) {
                    latestBoundary = quietStart + ((runEnd - quietStart) / FRAME_BYTES / 2) * FRAME_BYTES;
                }
                quietStart = -1;
            }
        }
        if (quietStart >= 0) {
            long runEnd = scanEnd;
            if (runEnd - quietStart >= MIN_QUIET_FRAMES * (long) FRAME_BYTES) {
                latestBoundary = quietStart + ((runEnd - quietStart) / FRAME_BYTES / 2) * FRAME_BYTES;
            }
        }
        return latestBoundary >= 0 ? latestBoundary : hardEnd;
    }

    private static boolean isQuietFrame(byte[] bytes, int offset) {
        long absoluteSum = 0;
        int samples = FRAME_BYTES / BYTES_PER_SAMPLE;
        for (int i = 0; i < FRAME_BYTES; i += BYTES_PER_SAMPLE) {
            int low = bytes[offset + i] & 0xff;
            int high = bytes[offset + i + 1];
            int sample = (short) (low | (high << 8));
            absoluteSum += Math.abs(sample);
        }
        return absoluteSum / samples <= QUIET_MEAN_ABSOLUTE_THRESHOLD;
    }

    private static long roundUpToFrame(long position) {
        long remainder = position % FRAME_BYTES;
        return remainder == 0 ? position : position + (FRAME_BYTES - remainder);
    }

    private void ensureWritable() throws IOException {
        ensureOpen();
        if (finished || output == null) throw new IOException("Recording is already finished.");
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("Recording is closed.");
    }

    private void failAndDelete() {
        try {
            close();
        } catch (IOException ignored) {
            // The original operation already failed; always attempt every cleanup step.
        }
    }

    private void deleteFileIfPresent() throws IOException {
        IOException failure = null;
        if (file.exists() && !file.delete()) {
            failure = new IOException("Unable to delete temporary recording from private storage.");
        }
        synchronized (LIVE_FILES_LOCK) {
            LIVE_FILE_PATHS.remove(registryPath);
        }
        if (failure != null) throw failure;
    }

    private static void removeStaleSpoolFiles(File privateDirectory) throws IOException {
        File[] files = privateDirectory.listFiles();
        if (files == null) throw new IOException("Unable to inspect private recording storage.");
        for (File candidate : files) {
            String name = candidate.getName();
            if (!candidate.isFile() || !name.startsWith("tonetunner-") || !name.endsWith(".pcm")) continue;
            final String path;
            try {
                path = candidate.getCanonicalPath();
            } catch (IOException e) {
                throw new IOException("Unable to clean stale recording audio.");
            }
            if (LIVE_FILE_PATHS.contains(path)) continue;
            if (!candidate.delete() && candidate.exists()) {
                throw new IOException("Unable to clean stale recording audio.");
            }
        }
    }
}
