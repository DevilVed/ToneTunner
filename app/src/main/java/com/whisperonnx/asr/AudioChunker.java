package com.whisperonnx.asr;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits long audio recordings into processable 30-second chunks.
 * 
 * Whisper has a fixed 30-second context window, so longer recordings
 * must be split into chunks with overlap to prevent word cutoff.
 */
public class AudioChunker {

    private static final String TAG = "AudioChunker";

    // Audio parameters
    public static final int SAMPLE_RATE = 16000;
    public static final int CHUNK_SECONDS = 30;
    public static final int CHUNK_SAMPLES = SAMPLE_RATE * CHUNK_SECONDS; // 480,000 samples

    // Overlap between chunks (in seconds)
    private final float overlapSeconds;
    private final int overlapSamples;

    /**
     * Create chunker with specified overlap
     * 
     * @param overlapSeconds Overlap between chunks (typically 2-3 seconds)
     */
    public AudioChunker(float overlapSeconds) {
        this.overlapSeconds = overlapSeconds;
        this.overlapSamples = (int) (overlapSeconds * SAMPLE_RATE);
        Log.d(TAG, "Initialized AudioChunker with " + overlapSeconds + "s overlap (" + overlapSamples + " samples)");
    }

    /**
     * Default constructor with 2.5 second overlap
     */
    public AudioChunker() {
        this(2.5f);
    }

    /**
     * Split audio into 30-second chunks with overlap.
     * 
     * @param audio Full audio recording
     * @return List of audio chunks
     */
    public List<float[]> chunk(float[] audio) {
        List<float[]> chunks = new ArrayList<>();

        int audioLength = audio.length;
        int stepSize = CHUNK_SAMPLES - overlapSamples;

        // Calculate number of chunks needed
        int numChunks = (int) Math.ceil((double) (audioLength - overlapSamples) / stepSize);
        numChunks = Math.max(1, numChunks);

        Log.d(TAG, "Splitting " + (audioLength / SAMPLE_RATE) + "s audio into " + numChunks + " chunks");

        for (int i = 0; i < numChunks; i++) {
            int startSample = i * stepSize;
            int endSample = Math.min(startSample + CHUNK_SAMPLES, audioLength);
            int chunkLength = endSample - startSample;

            float[] chunk;
            if (chunkLength < CHUNK_SAMPLES) {
                // Pad last chunk with zeros if needed
                chunk = new float[CHUNK_SAMPLES];
                System.arraycopy(audio, startSample, chunk, 0, chunkLength);
            } else {
                chunk = new float[CHUNK_SAMPLES];
                System.arraycopy(audio, startSample, chunk, 0, CHUNK_SAMPLES);
            }

            chunks.add(chunk);
            Log.d(TAG, "Chunk " + (i + 1) + "/" + numChunks +
                    ": samples " + startSample + "-" + endSample +
                    " (" + (chunkLength / SAMPLE_RATE) + "s)");
        }

        return chunks;
    }

    /**
     * Get metadata about how audio will be chunked.
     * 
     * @param audioLengthSamples Total audio length in samples
     * @return ChunkInfo with chunk count and timing details
     */
    public ChunkInfo getChunkInfo(int audioLengthSamples) {
        int stepSize = CHUNK_SAMPLES - overlapSamples;
        int numChunks = (int) Math.ceil((double) (audioLengthSamples - overlapSamples) / stepSize);
        numChunks = Math.max(1, numChunks);

        return new ChunkInfo(numChunks, overlapSeconds, audioLengthSamples / (float) SAMPLE_RATE);
    }

    /**
     * Metadata about audio chunking
     */
    public static class ChunkInfo {
        public final int numChunks;
        public final float overlapSeconds;
        public final float totalDurationSeconds;

        public ChunkInfo(int numChunks, float overlapSeconds, float totalDurationSeconds) {
            this.numChunks = numChunks;
            this.overlapSeconds = overlapSeconds;
            this.totalDurationSeconds = totalDurationSeconds;
        }
    }

    /**
     * Get overlap in seconds
     */
    public float getOverlapSeconds() {
        return overlapSeconds;
    }

    /**
     * Get overlap in samples
     */
    public int getOverlapSamples() {
        return overlapSamples;
    }
}
