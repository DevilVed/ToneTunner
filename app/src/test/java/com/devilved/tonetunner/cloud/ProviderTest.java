package com.devilved.tonetunner.cloud;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ProviderTest {

    @Test
    public void presetsExposeFullApiEndpointsAndUsableDefaults() {
        assertEquals("https://api.groq.com/openai/v1/audio/transcriptions",
                Provider.GROQ.transcriptionEndpoint());
        assertEquals("https://api.groq.com/openai/v1/chat/completions",
                Provider.GROQ.enhancementEndpoint());
        assertEquals("whisper-large-v3-turbo", Provider.GROQ.defaultTranscriptionModel());

        assertEquals("https://api.openai.com/v1/audio/transcriptions",
                Provider.OPENAI.transcriptionEndpoint());
        assertEquals("gpt-4o-mini-transcribe", Provider.OPENAI.defaultTranscriptionModel());

        assertEquals("https://openrouter.ai/api/v1/audio/transcriptions",
                Provider.OPENROUTER.transcriptionEndpoint());
        assertEquals("openai/whisper-large-v3", Provider.OPENROUTER.defaultTranscriptionModel());
    }
}
