package com.devilved.tonetunner.cloud;

/** Cloud API presets understood by {@link CloudClient}. */
public enum Provider {
    LOCAL("", "", "", ""),
    GROQ(
            "https://api.groq.com/openai/v1/audio/transcriptions",
            "https://api.groq.com/openai/v1/chat/completions",
            "whisper-large-v3-turbo",
            "openai/gpt-oss-20b"),
    OPENAI(
            "https://api.openai.com/v1/audio/transcriptions",
            "https://api.openai.com/v1/chat/completions",
            "gpt-4o-mini-transcribe",
            "gpt-4o-mini"),
    OPENROUTER(
            "https://openrouter.ai/api/v1/audio/transcriptions",
            "https://openrouter.ai/api/v1/chat/completions",
            "openai/whisper-large-v3",
            "openai/gpt-4o-mini"),
    CUSTOM("", "", "", "");

    private final String transcriptionEndpoint;
    private final String enhancementEndpoint;
    private final String defaultTranscriptionModel;
    private final String defaultEnhancementModel;

    Provider(String transcriptionEndpoint, String enhancementEndpoint,
             String defaultTranscriptionModel, String defaultEnhancementModel) {
        this.transcriptionEndpoint = transcriptionEndpoint;
        this.enhancementEndpoint = enhancementEndpoint;
        this.defaultTranscriptionModel = defaultTranscriptionModel;
        this.defaultEnhancementModel = defaultEnhancementModel;
    }

    /** Returns the full default URL for transcription, or an empty string for local/custom. */
    public String transcriptionEndpoint() {
        return transcriptionEndpoint;
    }

    /** Returns the full default URL for enhancement, or an empty string for local/custom. */
    public String enhancementEndpoint() {
        return enhancementEndpoint;
    }

    public String defaultTranscriptionModel() {
        return defaultTranscriptionModel;
    }

    public String defaultEnhancementModel() {
        return defaultEnhancementModel;
    }
}
