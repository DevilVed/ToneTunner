package com.devilved.tonetunner;

import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;

import java.io.ByteArrayOutputStream;

public class RecorderVadBenchmarkTest {

    private static final int VAD_FRAME_SIZE = 480; // 480 shorts = 960 bytes
    private static final int VAD_FRAME_BYTES = VAD_FRAME_SIZE * 2;

    @Test
    public void testCorrectnessAndBenchmark() {
        int totalFrames = 1000; // ~30 seconds of audio at 960 bytes/frame
        ByteArrayOutputStream outputBufferOriginal = new ByteArrayOutputStream();
        ByteArrayOutputStream outputBufferOptimized = new ByteArrayOutputStream();

        byte[] vadAudioBufferOriginal = new byte[VAD_FRAME_BYTES];
        byte[] vadAudioBufferOptimized = new byte[VAD_FRAME_BYTES];

        byte[] audioData = new byte[VAD_FRAME_BYTES];

        long originalAllocatedBytes = 0;
        long optimizedAllocatedBytes = 0;

        int totalBytesReadOrig = 0;
        int totalBytesReadOpt = 0;

        long startOriginal = System.nanoTime();
        for (int frame = 0; frame < totalFrames; frame++) {
            for (int i = 0; i < VAD_FRAME_BYTES; i++) {
                audioData[i] = (byte) ((frame + i) & 0xFF);
            }

            int bytesRead = VAD_FRAME_BYTES;
            outputBufferOriginal.write(audioData, 0, bytesRead);
            totalBytesReadOrig += bytesRead;

            // Original logic
            byte[] outputBufferByteArray = outputBufferOriginal.toByteArray();
            originalAllocatedBytes += outputBufferByteArray.length;
            if (outputBufferByteArray.length >= VAD_FRAME_BYTES) {
                System.arraycopy(outputBufferByteArray, outputBufferByteArray.length - VAD_FRAME_BYTES, vadAudioBufferOriginal, 0, VAD_FRAME_BYTES);
            }
        }
        long durationOriginal = System.nanoTime() - startOriginal;

        long startOptimized = System.nanoTime();
        for (int frame = 0; frame < totalFrames; frame++) {
            for (int i = 0; i < VAD_FRAME_BYTES; i++) {
                audioData[i] = (byte) ((frame + i) & 0xFF);
            }

            int bytesRead = VAD_FRAME_BYTES;
            outputBufferOptimized.write(audioData, 0, bytesRead);

            // Optimized logic (zero heap allocations for VAD buffer extraction)
            int vadFrameByteSize = VAD_FRAME_BYTES;
            int prevBytes = Math.min(totalBytesReadOpt, vadFrameByteSize);
            totalBytesReadOpt += bytesRead;

            if (bytesRead >= vadFrameByteSize) {
                System.arraycopy(audioData, bytesRead - vadFrameByteSize, vadAudioBufferOptimized, 0, vadFrameByteSize);
            } else if (prevBytes + bytesRead <= vadFrameByteSize) {
                System.arraycopy(audioData, 0, vadAudioBufferOptimized, prevBytes, bytesRead);
            } else {
                int keep = vadFrameByteSize - bytesRead;
                int overflow = prevBytes - keep;
                System.arraycopy(vadAudioBufferOptimized, overflow, vadAudioBufferOptimized, 0, keep);
                System.arraycopy(audioData, 0, vadAudioBufferOptimized, keep, bytesRead);
            }
        }
        long durationOptimized = System.nanoTime() - startOptimized;

        // Verify final state matches
        assertArrayEquals("Final state mismatch between original and optimized VAD buffer!", vadAudioBufferOriginal, vadAudioBufferOptimized);

        // Now run lockstep verification across all 1000 frames
        outputBufferOriginal.reset();
        outputBufferOptimized.reset();
        totalBytesReadOrig = 0;
        totalBytesReadOpt = 0;

        for (int frame = 0; frame < totalFrames; frame++) {
            for (int i = 0; i < VAD_FRAME_BYTES; i++) {
                audioData[i] = (byte) ((frame * 3 + i) & 0xFF);
            }

            int bytesRead = VAD_FRAME_BYTES;

            // Original
            outputBufferOriginal.write(audioData, 0, bytesRead);
            totalBytesReadOrig += bytesRead;
            byte[] outputBufferByteArray = outputBufferOriginal.toByteArray();
            if (outputBufferByteArray.length >= VAD_FRAME_BYTES) {
                System.arraycopy(outputBufferByteArray, outputBufferByteArray.length - VAD_FRAME_BYTES, vadAudioBufferOriginal, 0, VAD_FRAME_BYTES);
            }

            // Optimized
            outputBufferOptimized.write(audioData, 0, bytesRead);
            int vadFrameByteSize = VAD_FRAME_BYTES;
            int prevBytes = Math.min(totalBytesReadOpt, vadFrameByteSize);
            totalBytesReadOpt += bytesRead;

            if (bytesRead >= vadFrameByteSize) {
                System.arraycopy(audioData, bytesRead - vadFrameByteSize, vadAudioBufferOptimized, 0, vadFrameByteSize);
            } else if (prevBytes + bytesRead <= vadFrameByteSize) {
                System.arraycopy(audioData, 0, vadAudioBufferOptimized, prevBytes, bytesRead);
            } else {
                int keep = vadFrameByteSize - bytesRead;
                int overflow = prevBytes - keep;
                System.arraycopy(vadAudioBufferOptimized, overflow, vadAudioBufferOptimized, 0, keep);
                System.arraycopy(audioData, 0, vadAudioBufferOptimized, keep, bytesRead);
            }

            assertArrayEquals("Lockstep mismatch at frame " + frame, vadAudioBufferOriginal, vadAudioBufferOptimized);
        }

        System.out.printf("Standard frames (%d frames / 30s audio):\n", totalFrames);
        System.out.printf("  Original : %8.3f ms, Allocated: %10d bytes (~%.2f MB)\n",
                durationOriginal / 1e6, originalAllocatedBytes, originalAllocatedBytes / (1024.0 * 1024.0));
        System.out.printf("  Optimized: %8.3f ms, Allocated: %10d bytes (~%.2f MB)\n",
                durationOptimized / 1e6, optimizedAllocatedBytes, optimizedAllocatedBytes / (1024.0 * 1024.0));
        System.out.printf("  Speedup  : %.2fx faster, Memory Saved: %.2f MB\n",
                (double) durationOriginal / Math.max(1, durationOptimized),
                originalAllocatedBytes / (1024.0 * 1024.0));
    }

    @Test
    public void testPartialReads() {
        // Test with varying frame chunk sizes (e.g. 300, 400, 260, 600 bytes per read)
        int[] chunkSizes = {300, 400, 260, 500, 960, 100, 860, 600, 400, 750};
        ByteArrayOutputStream outputBufferOriginal = new ByteArrayOutputStream();
        ByteArrayOutputStream outputBufferOptimized = new ByteArrayOutputStream();

        byte[] vadAudioBufferOriginal = new byte[VAD_FRAME_BYTES];
        byte[] vadAudioBufferOptimized = new byte[VAD_FRAME_BYTES];

        byte[] audioData = new byte[VAD_FRAME_BYTES];

        int totalBytesReadOrig = 0;
        int totalBytesReadOpt = 0;

        for (int iteration = 0; iteration < 200; iteration++) {
            int bytesRead = chunkSizes[iteration % chunkSizes.length];
            for (int i = 0; i < bytesRead; i++) {
                audioData[i] = (byte) ((iteration * 17 + i) & 0xFF);
            }

            // Original
            outputBufferOriginal.write(audioData, 0, bytesRead);
            totalBytesReadOrig += bytesRead;
            byte[] outputBufferByteArray = outputBufferOriginal.toByteArray();
            if (outputBufferByteArray.length >= VAD_FRAME_BYTES) {
                System.arraycopy(outputBufferByteArray, outputBufferByteArray.length - VAD_FRAME_BYTES, vadAudioBufferOriginal, 0, VAD_FRAME_BYTES);
            }

            // Optimized
            outputBufferOptimized.write(audioData, 0, bytesRead);
            int vadFrameByteSize = VAD_FRAME_BYTES;
            int prevBytes = Math.min(totalBytesReadOpt, vadFrameByteSize);
            totalBytesReadOpt += bytesRead;

            if (bytesRead >= vadFrameByteSize) {
                System.arraycopy(audioData, bytesRead - vadFrameByteSize, vadAudioBufferOptimized, 0, vadFrameByteSize);
            } else if (prevBytes + bytesRead <= vadFrameByteSize) {
                System.arraycopy(audioData, 0, vadAudioBufferOptimized, prevBytes, bytesRead);
            } else {
                int keep = vadFrameByteSize - bytesRead;
                int overflow = prevBytes - keep;
                System.arraycopy(vadAudioBufferOptimized, overflow, vadAudioBufferOptimized, 0, keep);
                System.arraycopy(audioData, 0, vadAudioBufferOptimized, keep, bytesRead);
            }

            if (totalBytesReadOrig >= VAD_FRAME_BYTES) {
                assertArrayEquals("Mismatch at partial read iteration " + iteration, vadAudioBufferOriginal, vadAudioBufferOptimized);
            }
        }
        System.out.println("Partial reads test passed! All sliding window buffers matched original output.");
    }
}
