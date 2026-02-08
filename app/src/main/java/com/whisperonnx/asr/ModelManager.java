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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Manages model discovery, configuration parsing, and model loading.
 * 
 * Scans the app's external files directory for installed models,
 * parses their config.json files, and provides ModelConfig instances.
 */
public class ModelManager {

    private static final String TAG = "ModelManager";

    // Async loading executor
    private static final Executor executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Context context;
    private final File modelsDir;
    
    // Cache for scan results
    private List<ModelConfig> cachedModels;
    private long lastScanTime = 0;
    private static final long CACHE_TTL_MS = 5000; // 5 second cache

    // Known model file patterns
    private static final String CONFIG_FILE = "config.json";
    private static final String GENERATION_CONFIG_FILE = "generation_config.json";
    private static final String VOCAB_FILE = "vocab.json";

    // Small model ONNX file names (for detection)
    private static final String SMALL_INITIALIZER = "Whisper_initializer.onnx";
    private static final String SMALL_ENCODER = "Whisper_encoder.onnx";
    private static final String SMALL_DECODER = "Whisper_decoder.onnx";
    private static final String SMALL_CACHE_INIT = "Whisper_cache_initializer.onnx";
    private static final String SMALL_DETOKENIZER = "Whisper_detokenizer.onnx";

    // Large model ONNX file names
    private static final String LARGE_ENCODER = "encoder_model.onnx";
    private static final String LARGE_DECODER = "decoder_model.onnx";
    private static final String LARGE_DECODER_MERGED = "decoder_model_merged.onnx";
    private static final String LARGE_DECODER_WITH_PAST = "decoder_with_past_model.onnx";

    public ModelManager(Context context) {
        this.context = context;
        this.modelsDir = context.getExternalFilesDir(null);
        Log.d(TAG, "ModelManager initialized. Models dir: " + modelsDir);
    }

    /**
     * Callback interface for async model scanning
     */
    public interface ScanCallback {
        void onModelsFound(List<ModelConfig> models);
        void onError(Exception e);
    }

    /**
     * Scan for installed models asynchronously.
     * This prevents ANR when calling from the main thread.
     * 
     * @param callback Called on main thread with results
     */
    public void scanModelsAsync(ScanCallback callback) {
        executor.execute(() -> {
            try {
                List<ModelConfig> models = scanModelsInternal();
                mainHandler.post(() -> callback.onModelsFound(models));
            } catch (Exception e) {
                Log.e(TAG, "Error scanning models", e);
                mainHandler.post(() -> callback.onError(e));
            }
        });
    }

    /**
     * Scan for installed models and return their configurations.
     * 
     * Models can be:
     * 1. Small INT8 - files directly in modelsDir (backward compatible)
     * 2. Large models - in subdirectories with config.json
     * 
     * NOTE: This method performs file I/O and may block. Consider using scanModelsAsync() from UI thread.
     */
    public List<ModelConfig> scanModels() {
        // Return cached results if recent
        if (cachedModels != null && System.currentTimeMillis() - lastScanTime < CACHE_TTL_MS) {
            Log.d(TAG, "Returning cached model list");
            return cachedModels;
        }
        return scanModelsInternal();
    }
    
    private List<ModelConfig> scanModelsInternal() {
        List<ModelConfig> models = new ArrayList<>();

        if (modelsDir == null || !modelsDir.exists()) {
            Log.w(TAG, "Models directory does not exist");
            return models;
        }

        // Check for Small model (files directly in modelsDir)
        if (isSmallModelInstalled(modelsDir)) {
            Log.d(TAG, "Found Small INT8 model in root directory");
            models.add(createSmallModelConfig(modelsDir));
        }

        // Check subdirectories for Large models
        File[] subdirs = modelsDir.listFiles(File::isDirectory);
        if (subdirs != null) {
            for (File subdir : subdirs) {
                // Skip hidden directories
                if (subdir.getName().startsWith(".")) {
                    continue;
                }
                ModelConfig config = tryLoadModelConfig(subdir);
                if (config != null) {
                    Log.d(TAG, "Found model: " + config.getModelName() + " in " + subdir.getName());
                    models.add(config);
                }
            }
        }

        Log.d(TAG, "Scan complete. Found " + models.size() + " models");
        
        // Cache results
        cachedModels = models;
        lastScanTime = System.currentTimeMillis();
        
        return models;
    }

    /**
     * Check if Small INT8 model files exist in directory
     */
    private boolean isSmallModelInstalled(File dir) {
        return new File(dir, SMALL_ENCODER).exists() &&
                new File(dir, SMALL_DECODER).exists();
    }

    /**
     * Check if Large model files exist in directory
     */
    private boolean isLargeModelInstalled(File dir) {
        return new File(dir, LARGE_ENCODER).exists() &&
                (new File(dir, LARGE_DECODER).exists() ||
                        new File(dir, LARGE_DECODER_MERGED).exists() ||
                        new File(dir, LARGE_DECODER_WITH_PAST).exists());
    }

    /**
     * Create ModelConfig for Small INT8 model
     */
    private ModelConfig createSmallModelConfig(File dir) {
        ModelConfig.Builder builder = new ModelConfig.Builder()
                .modelName("Whisper Small INT8")
                .modelPath(dir.getAbsolutePath())
                .modelType(ModelConfig.ModelType.SMALL_INT8)
                .numLayers(12)
                .numHeads(12)
                .numMelBins(80)
                .dModel(512)
                .vocabSize(51865)
                .maxTargetPositions(445)
                .startTokenId(50258)
                .transcribeTokenId(50359)
                .translateTokenId(50358)
                .noTimestampsTokenId(50363)
                .eosTokenId(50257)
                .isFp16(false)
                .isQuantized(true)
                .hasInitializerOnnx(new File(dir, SMALL_INITIALIZER).exists())
                .hasCacheInitializerOnnx(new File(dir, SMALL_CACHE_INIT).exists())
                .hasDetokenizerOnnx(new File(dir, SMALL_DETOKENIZER).exists());

        return builder.build();
    }

    /**
     * Try to load model config from a directory containing config.json
     */
    private ModelConfig tryLoadModelConfig(File dir) {
        File configFile = new File(dir, CONFIG_FILE);
        File genConfigFile = new File(dir, GENERATION_CONFIG_FILE);

        // For Large models, we need config.json
        if (!configFile.exists()) {
            // Maybe it's a Small model in a subdirectory
            if (isSmallModelInstalled(dir)) {
                return createSmallModelConfig(dir);
            }
            return null;
        }

        // Must have encoder and decoder
        if (!isLargeModelInstalled(dir)) {
            Log.d(TAG, "Skipping " + dir.getName() + ": missing encoder/decoder");
            return null;
        }

        try {
            return parseLargeModelConfig(dir, configFile, genConfigFile);
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse config for " + dir.getName(), e);
            return null;
        }
    }

    /**
     * Parse config.json and generation_config.json for Large model
     */
    private ModelConfig parseLargeModelConfig(File dir, File configFile, File genConfigFile)
            throws IOException, JSONException {

        JSONObject config = readJson(configFile);
        JSONObject genConfig = genConfigFile.exists() ? readJson(genConfigFile) : null;

        // Detect model type based on file sizes and names
        ModelConfig.ModelType modelType = detectModelType(dir);
        boolean isFp16 = modelType == ModelConfig.ModelType.LARGE_FP16;
        boolean isQuantized = modelType == ModelConfig.ModelType.LARGE_QUANTIZED;

        // Build config from JSON
        ModelConfig.Builder builder = new ModelConfig.Builder()
                .modelName(config.optString("_name_or_path", dir.getName()))
                .modelPath(dir.getAbsolutePath())
                .modelType(modelType)
                .numLayers(config.optInt("num_hidden_layers", 32))
                .numHeads(config.optInt("encoder_attention_heads", 20))
                .numMelBins(config.optInt("num_mel_bins", 128))
                .dModel(config.optInt("d_model", 1280))
                .vocabSize(config.optInt("vocab_size", 51866))
                .maxTargetPositions(config.optInt("max_target_positions", 448))
                .eosTokenId(config.optInt("eos_token_id", 50257))
                .isFp16(isFp16)
                .isQuantized(isQuantized)
                .hasInitializerOnnx(false)
                .hasCacheInitializerOnnx(false)
                .hasDetokenizerOnnx(false);

        // Get token IDs from generation_config if available
        if (genConfig != null) {
            builder.startTokenId(genConfig.optInt("decoder_start_token_id", 50258));
            builder.noTimestampsTokenId(genConfig.optInt("no_timestamps_token_id", 50364));

            // Get task token IDs
            JSONObject taskToId = genConfig.optJSONObject("task_to_id");
            if (taskToId != null) {
                builder.transcribeTokenId(taskToId.optInt("transcribe", 50360));
                builder.translateTokenId(taskToId.optInt("translate", 50359));
            } else {
                builder.transcribeTokenId(50360);
                builder.translateTokenId(50359);
            }
        } else {
            // Large V3 defaults
            builder.startTokenId(50258);
            builder.transcribeTokenId(50360);
            builder.translateTokenId(50359);
            builder.noTimestampsTokenId(50364);
        }

        return builder.build();
    }

    /**
     * Detect model type based on directory name and file patterns
     */
    private ModelConfig.ModelType detectModelType(File dir) {
        String name = dir.getName().toLowerCase();

        // Check for external data files (indicates FP32)
        File encoderData = new File(dir, "encoder_model.onnx_data");
        File decoderData = new File(dir, "decoder_model_merged.onnx_data");

        if (encoderData.exists() || decoderData.exists()) {
            return ModelConfig.ModelType.LARGE_FP32;
        }

        if (name.contains("fp16") || name.contains("float16")) {
            return ModelConfig.ModelType.LARGE_FP16;
        }

        if (name.contains("quantized") || name.contains("int8") || name.contains("q8")) {
            return ModelConfig.ModelType.LARGE_QUANTIZED;
        }

        // Default to FP16 for Large models without clear indication
        return ModelConfig.ModelType.LARGE_FP16;
    }

    /**
     * Read JSON file
     */
    private JSONObject readJson(File file) throws IOException, JSONException {
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line);
            }
        }
        return new JSONObject(content.toString());
    }

    /**
     * Get the currently selected model config.
     * For now, returns the first available model (preference-based selection to be
     * added).
     */
    public ModelConfig getActiveModel() {
        List<ModelConfig> models = scanModels();
        if (models.isEmpty()) {
            Log.w(TAG, "No models installed, returning Small default");
            return ModelConfig.createSmallDefault();
        }

        // TODO: Add SharedPreferences-based model selection
        // For now, prefer Small model for backward compatibility
        for (ModelConfig model : models) {
            if (model.getModelType() == ModelConfig.ModelType.SMALL_INT8) {
                return model;
            }
        }

        return models.get(0);
    }

    /**
     * Check if any model is installed
     */
    public boolean hasInstalledModel() {
        return !scanModels().isEmpty();
    }

    /**
     * Get vocab.json path for a model
     */
    public String getVocabPath(ModelConfig config) {
        return new File(config.getModelPath(), VOCAB_FILE).getAbsolutePath();
    }

    /**
     * Get models directory
     */
    public File getModelsDir() {
        return modelsDir;
    }
}
