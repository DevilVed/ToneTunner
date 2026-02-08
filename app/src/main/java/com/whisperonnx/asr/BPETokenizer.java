package com.whisperonnx.asr;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * BPE (Byte-Pair Encoding) Tokenizer for Whisper.
 * Replaces Whisper_detokenizer.onnx for models that don't include it.
 * 
 * Decodes token IDs to text using vocab.json from the model directory.
 */
public class BPETokenizer {

    private static final String TAG = "BPETokenizer";

    // Token ID -> String mapping
    private final Map<Integer, String> vocab;

    // Special tokens to skip during decoding
    private final int eosTokenId;
    private final int startTokenId;
    private final int noTimestampsTokenId;
    private final int transcribeTokenId;
    private final int translateTokenId;

    // Language token ID range (50259-50358 for Large V3)
    private static final int LANG_TOKEN_START = 50259;
    private static final int LANG_TOKEN_END = 50358;

    // Async loading executor
    private static final Executor executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * Callback interface for async tokenizer creation
     */
    public interface TokenizerCallback {
        void onReady(BPETokenizer tokenizer);
        void onError(Exception e);
    }

    /**
     * Create tokenizer from vocab.json file asynchronously.
     * This prevents ANR when loading large vocab files on the main thread.
     */
    public static void createAsync(String vocabPath, ModelConfig config, TokenizerCallback callback) {
        executor.execute(() -> {
            try {
                BPETokenizer tokenizer = new BPETokenizer(vocabPath, config);
                mainHandler.post(() -> callback.onReady(tokenizer));
            } catch (Exception e) {
                Log.e(TAG, "Failed to load tokenizer", e);
                mainHandler.post(() -> callback.onError(e));
            }
        });
    }

    /**
     * Create tokenizer from vocab.json file synchronously.
     * WARNING: This may block the calling thread for 50-200ms.
     * Consider using createAsync() if calling from UI thread.
     */
    public BPETokenizer(String vocabPath, ModelConfig config) throws IOException, JSONException {
        this.vocab = loadVocab(vocabPath);
        this.eosTokenId = config.getEosTokenId();
        this.startTokenId = config.getStartTokenId();
        this.noTimestampsTokenId = config.getNoTimestampsTokenId();
        this.transcribeTokenId = config.getTranscribeTokenId();
        this.translateTokenId = config.getTranslateTokenId();

        Log.d(TAG, "Loaded vocab with " + vocab.size() + " tokens");
    }

    /**
     * Create tokenizer with explicit token IDs (for backward compatibility)
     */
    public BPETokenizer(String vocabPath, int eosTokenId, int startTokenId,
            int noTimestampsTokenId, int transcribeTokenId, int translateTokenId)
            throws IOException, JSONException {
        this.vocab = loadVocab(vocabPath);
        this.eosTokenId = eosTokenId;
        this.startTokenId = startTokenId;
        this.noTimestampsTokenId = noTimestampsTokenId;
        this.transcribeTokenId = transcribeTokenId;
        this.translateTokenId = translateTokenId;

        Log.d(TAG, "Loaded vocab with " + vocab.size() + " tokens");
    }

    /**
     * Load vocabulary from JSON file.
     * vocab.json format: {"token_string": token_id, ...}
     */
    private Map<Integer, String> loadVocab(String vocabPath) throws IOException, JSONException {
        // Pre-size HashMap to avoid rehashing (Whisper Large V3 has ~51866 tokens)
        Map<Integer, String> vocabMap = new HashMap<>(60000);

        File vocabFile = new File(vocabPath);
        
        // Check file size to prevent OOM with corrupted files
        long fileSize = vocabFile.length();
        if (fileSize > 20 * 1024 * 1024) { // 20MB limit
            throw new IOException("Vocab file too large: " + fileSize + " bytes");
        }
        
        StringBuilder jsonContent = new StringBuilder((int) fileSize);

        try (BufferedReader reader = new BufferedReader(new FileReader(vocabFile), 8192)) { // 8KB buffer
            String line;
            while ((line = reader.readLine()) != null) {
                jsonContent.append(line);
            }
        }

        JSONObject json = new JSONObject(jsonContent.toString());
        Iterator<String> keys = json.keys();

        while (keys.hasNext()) {
            String token = keys.next();
            int id = json.getInt(token);
            vocabMap.put(id, token);
        }

        return vocabMap;
    }

    /**
     * Decode token IDs to text.
     * 
     * @param tokenIds Array of token IDs from decoder
     * @return Decoded text string
     */
    public String decode(int[] tokenIds) {
        StringBuilder result = new StringBuilder();

        for (int tokenId : tokenIds) {
            // Skip special tokens
            if (isSpecialToken(tokenId)) {
                continue;
            }

            // Skip timestamp tokens (format: <|0.00|>)
            if (isTimestampToken(tokenId)) {
                continue;
            }

            String token = vocab.get(tokenId);
            if (token != null) {
                // Whisper uses "Ġ" (U+0120) to represent leading space
                token = token.replace("Ġ", " ");
                // Handle other BPE artifacts
                token = token.replace("Ċ", "\n");
                result.append(token);
            }
        }

        return postProcess(result.toString());
    }

    /**
     * Check if token is a special token that should be skipped
     */
    private boolean isSpecialToken(int tokenId) {
        return tokenId == eosTokenId ||
                tokenId == startTokenId ||
                tokenId == noTimestampsTokenId ||
                tokenId == transcribeTokenId ||
                tokenId == translateTokenId ||
                isLanguageToken(tokenId);
    }

    /**
     * Check if token is a language identifier token
     */
    private boolean isLanguageToken(int tokenId) {
        return tokenId >= LANG_TOKEN_START && tokenId <= LANG_TOKEN_END;
    }

    /**
     * Check if token is a timestamp token (>50364 for Large V3)
     */
    private boolean isTimestampToken(int tokenId) {
        // Timestamp tokens are after special tokens
        return tokenId > noTimestampsTokenId;
    }

    /**
     * Post-process decoded text
     */
    private String postProcess(String text) {
        // Remove timestamp patterns like <|0.00|>
        text = text.replaceAll("<\\|[^>]*\\|>\\s*", "");

        // Trim whitespace
        text = text.trim();

        // Capitalize first letter if lowercase
        if (text.length() >= 2) {
            char firstChar = text.charAt(0);
            if (Character.isLowerCase(firstChar)) {
                text = Character.toUpperCase(firstChar) + text.substring(1);
            }
        }

        // Remove "..." artifacts that Whisper sometimes produces
        text = text.replace("...", "");

        return text;
    }

    /**
     * Get the vocabulary size
     */
    public int getVocabSize() {
        return vocab.size();
    }

    /**
     * Check if a token ID exists in the vocabulary
     */
    public boolean hasToken(int tokenId) {
        return vocab.containsKey(tokenId);
    }

    /**
     * Get the string representation of a token ID
     */
    public String getToken(int tokenId) {
        return vocab.get(tokenId);
    }
}
