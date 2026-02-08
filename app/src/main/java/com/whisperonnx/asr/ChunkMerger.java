package com.whisperonnx.asr;

import android.util.Log;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Merges transcription results from multiple audio chunks.
 * 
 * Handles overlap deduplication to avoid repeated words at chunk boundaries.
 */
public class ChunkMerger {

    private static final String TAG = "ChunkMerger";

    // Minimum word length to consider for overlap detection
    private static final int MIN_OVERLAP_WORDS = 3;

    // Overlap duration used for chunking (to estimate word overlap)
    private final float overlapSeconds;

    public ChunkMerger(float overlapSeconds) {
        this.overlapSeconds = overlapSeconds;
    }

    /**
     * Merge multiple chunk transcriptions into a single coherent text.
     * 
     * @param chunkResults List of transcription results from each chunk
     * @return Merged transcription
     */
    public String merge(List<String> chunkResults) {
        if (chunkResults == null || chunkResults.isEmpty()) {
            return "";
        }

        if (chunkResults.size() == 1) {
            return chunkResults.get(0).trim();
        }

        StringBuilder merged = new StringBuilder();

        for (int i = 0; i < chunkResults.size(); i++) {
            String currentChunk = chunkResults.get(i).trim();

            if (i == 0) {
                // First chunk: add entirely
                merged.append(currentChunk);
            } else {
                // Subsequent chunks: detect and remove overlap
                String previousText = merged.toString();
                String mergedText = mergeWithOverlapDetection(previousText, currentChunk);
                merged = new StringBuilder(mergedText);
            }

            Log.d(TAG, "After chunk " + (i + 1) + ": " + merged.length() + " chars");
        }

        return postProcess(merged.toString());
    }

    /**
     * Merge current chunk with previous text, detecting and removing overlap.
     */
    private String mergeWithOverlapDetection(String previous, String current) {
        if (current.isEmpty()) {
            return previous;
        }

        // Split into words
        String[] prevWords = previous.split("\\s+");
        String[] currWords = current.split("\\s+");

        if (prevWords.length < MIN_OVERLAP_WORDS || currWords.length < MIN_OVERLAP_WORDS) {
            // Too short to detect overlap reliably
            return previous + " " + current;
        }

        // Look for overlap: find where end of previous matches start of current
        // Check up to ~2-3 seconds worth of words (estimate ~2-3 words per second)
        int maxOverlapWords = Math.min(10, Math.min(prevWords.length, currWords.length));

        int bestOverlap = 0;

        for (int overlap = MIN_OVERLAP_WORDS; overlap <= maxOverlapWords; overlap++) {
            boolean matches = true;

            // Check if last 'overlap' words of previous match first 'overlap' words of
            // current
            for (int j = 0; j < overlap; j++) {
                int prevIdx = prevWords.length - overlap + j;
                String prevWord = normalizeWord(prevWords[prevIdx]);
                String currWord = normalizeWord(currWords[j]);

                if (!prevWord.equalsIgnoreCase(currWord)) {
                    matches = false;
                    break;
                }
            }

            if (matches) {
                bestOverlap = overlap;
            }
        }

        if (bestOverlap > 0) {
            Log.d(TAG, "Detected " + bestOverlap + " word overlap");

            // Remove overlapping words from current chunk
            StringBuilder result = new StringBuilder(previous);
            for (int i = bestOverlap; i < currWords.length; i++) {
                result.append(" ").append(currWords[i]);
            }
            return result.toString();
        } else {
            // No overlap detected, simple concatenation
            return previous + " " + current;
        }
    }

    /**
     * Normalize word for comparison (remove punctuation, lowercase)
     */
    private String normalizeWord(String word) {
        return word.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
    }

    /**
     * Post-process merged text
     */
    private String postProcess(String text) {
        // Remove double spaces
        text = text.replaceAll("\\s+", " ");

        // Trim
        text = text.trim();

        // Capitalize first letter
        if (text.length() >= 2 && Character.isLowerCase(text.charAt(0))) {
            text = Character.toUpperCase(text.charAt(0)) + text.substring(1);
        }

        return text;
    }
}
