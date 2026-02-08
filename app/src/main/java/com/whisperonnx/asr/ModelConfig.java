package com.whisperonnx.asr;

/**
 * POJO containing model-specific configuration parameters.
 * Values are parsed from config.json of each model.
 */
public class ModelConfig {

    // Model identification
    private String modelName;
    private String modelPath;
    private ModelType modelType;

    // Architecture parameters
    private int numLayers; // 12 for Small, 32 for Large
    private int numHeads; // 12 for Small, 20 for Large
    private int numMelBins; // 80 for Small, 128 for Large
    private int dModel; // 512 for Small, 1280 for Large
    private int vocabSize; // 51865 for Small, 51866 for Large
    private int maxTargetPositions; // 445 for Small, 448 for Large

    // Token IDs (differ between model versions)
    private int startTokenId;
    private int transcribeTokenId;
    private int translateTokenId;
    private int noTimestampsTokenId;
    private int eosTokenId;

    // Precision
    private boolean isFp16;
    private boolean isQuantized;

    // Helper ONNX files availability
    private boolean hasInitializerOnnx;
    private boolean hasCacheInitializerOnnx;
    private boolean hasDetokenizerOnnx;

    public enum ModelType {
        SMALL_INT8,
        LARGE_QUANTIZED,
        LARGE_FP16,
        LARGE_FP32
    }

    // Default constructor
    public ModelConfig() {
    }

    // Builder pattern for easy construction
    public static class Builder {
        private final ModelConfig config = new ModelConfig();

        public Builder modelName(String name) {
            config.modelName = name;
            return this;
        }

        public Builder modelPath(String path) {
            config.modelPath = path;
            return this;
        }

        public Builder modelType(ModelType type) {
            config.modelType = type;
            return this;
        }

        public Builder numLayers(int layers) {
            config.numLayers = layers;
            return this;
        }

        public Builder numHeads(int heads) {
            config.numHeads = heads;
            return this;
        }

        public Builder numMelBins(int mels) {
            config.numMelBins = mels;
            return this;
        }

        public Builder dModel(int d) {
            config.dModel = d;
            return this;
        }

        public Builder vocabSize(int vocab) {
            config.vocabSize = vocab;
            return this;
        }

        public Builder maxTargetPositions(int max) {
            config.maxTargetPositions = max;
            return this;
        }

        public Builder startTokenId(int id) {
            config.startTokenId = id;
            return this;
        }

        public Builder transcribeTokenId(int id) {
            config.transcribeTokenId = id;
            return this;
        }

        public Builder translateTokenId(int id) {
            config.translateTokenId = id;
            return this;
        }

        public Builder noTimestampsTokenId(int id) {
            config.noTimestampsTokenId = id;
            return this;
        }

        public Builder eosTokenId(int id) {
            config.eosTokenId = id;
            return this;
        }

        public Builder isFp16(boolean fp16) {
            config.isFp16 = fp16;
            return this;
        }

        public Builder isQuantized(boolean quantized) {
            config.isQuantized = quantized;
            return this;
        }

        public Builder hasInitializerOnnx(boolean has) {
            config.hasInitializerOnnx = has;
            return this;
        }

        public Builder hasCacheInitializerOnnx(boolean has) {
            config.hasCacheInitializerOnnx = has;
            return this;
        }

        public Builder hasDetokenizerOnnx(boolean has) {
            config.hasDetokenizerOnnx = has;
            return this;
        }

        public ModelConfig build() {
            return config;
        }
    }

    // Getters
    public String getModelName() {
        return modelName;
    }

    public String getModelPath() {
        return modelPath;
    }

    public ModelType getModelType() {
        return modelType;
    }

    public int getNumLayers() {
        return numLayers;
    }

    public int getNumHeads() {
        return numHeads;
    }

    public int getNumMelBins() {
        return numMelBins;
    }

    public int getDModel() {
        return dModel;
    }

    public int getVocabSize() {
        return vocabSize;
    }

    public int getMaxTargetPositions() {
        return maxTargetPositions;
    }

    public int getStartTokenId() {
        return startTokenId;
    }

    public int getTranscribeTokenId() {
        return transcribeTokenId;
    }

    public int getTranslateTokenId() {
        return translateTokenId;
    }

    public int getNoTimestampsTokenId() {
        return noTimestampsTokenId;
    }

    public int getEosTokenId() {
        return eosTokenId;
    }

    public boolean isFp16() {
        return isFp16;
    }

    public boolean isQuantized() {
        return isQuantized;
    }

    public boolean hasInitializerOnnx() {
        return hasInitializerOnnx;
    }

    public boolean hasCacheInitializerOnnx() {
        return hasCacheInitializerOnnx;
    }

    public boolean hasDetokenizerOnnx() {
        return hasDetokenizerOnnx;
    }

    // Computed properties
    public int getHeadDimension() {
        return dModel / numHeads; // Should be 64 for both Small and Large
    }

    /**
     * Creates a default config for Small INT8 model (backward compatibility)
     */
    public static ModelConfig createSmallDefault() {
        return new Builder()
                .modelName("whisper-small-int8")
                .modelType(ModelType.SMALL_INT8)
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
                .hasInitializerOnnx(true)
                .hasCacheInitializerOnnx(true)
                .hasDetokenizerOnnx(true)
                .build();
    }

    /**
     * Creates a default config for Large V3 model
     */
    public static ModelConfig createLargeV3Default(boolean isFp16, boolean isQuantized) {
        return new Builder()
                .modelName("whisper-large-v3")
                .modelType(isFp16 ? ModelType.LARGE_FP16
                        : (isQuantized ? ModelType.LARGE_QUANTIZED : ModelType.LARGE_FP32))
                .numLayers(32)
                .numHeads(20)
                .numMelBins(128)
                .dModel(1280)
                .vocabSize(51866)
                .maxTargetPositions(448)
                .startTokenId(50258)
                .transcribeTokenId(50360)
                .translateTokenId(50359)
                .noTimestampsTokenId(50364)
                .eosTokenId(50257)
                .isFp16(isFp16)
                .isQuantized(isQuantized)
                .hasInitializerOnnx(false)
                .hasCacheInitializerOnnx(false)
                .hasDetokenizerOnnx(false)
                .build();
    }
}
