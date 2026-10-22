package com.devilved.tonetunner.metrics;

import com.devilved.tonetunner.asr.MicCapture;
import com.devilved.tonetunner.cloud.Usage;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

/** One dictation's measurements, accumulated across windows and requests. */
public class SessionMeterTest {

    @Test public void accumulatesCloudRequestsAndCountsWords() throws Exception {
        SessionMeter meter = new SessionMeter(1_000L, "GROQ", "whisper-large-v3-turbo", "OPENAI", "gpt-4o-mini");
        meter.setAudioMs(20_000);
        meter.onTranscription(Usage.fromTranscription(new JSONObject("{\"duration\":2.0}")), 400, 2.0);
        meter.onTranscription(Usage.fromTranscription(new JSONObject("{}")), 600, 18.0);
        meter.onEnhancement(Usage.fromChat(new JSONObject("{\"usage\":{\"prompt_tokens\":100,"
                + "\"completion_tokens\":20,\"prompt_tokens_details\":{\"cached_tokens\":64}}}")), 300, 80, 70);
        SessionMetric m = meter.finish(SessionMetric.COMPLETED, "Hello there, world!", 2_500L);

        assertEquals(1_000L, m.timestamp);
        assertEquals(SessionMetric.COMPLETED, m.status);
        assertEquals(3, m.words);
        assertEquals(20_000, m.audioMs);
        assertEquals("wall-clock processing time from the pipeline", 2_500, m.processingMs);
        assertEquals(2, m.transcriptionRequests);
        assertEquals(1_000, m.transcriptionMs);
        assertEquals("Groq bills each short request as 10 s", 10 + 18, m.billedAudioSeconds, 1e-9);
        assertEquals(1, m.enhancementRequests);
        assertEquals(300, m.enhancementMs);
        assertEquals(100, m.promptTokens);
        assertEquals(64, m.cachedTokens);
        assertEquals(20, m.completionTokens);
        assertFalse(m.tokensEstimated);
    }

    @Test public void missingChatUsageFallsBackToTheCharacterEstimate() {
        SessionMeter meter = new SessionMeter(0, "LOCAL", "whisper", "GROQ", "openai/gpt-oss-20b");
        meter.onEnhancement(Usage.fromChat(null), 100, 401, 37);
        SessionMetric m = meter.finish(SessionMetric.COMPLETED, "x", 0);
        assertTrue(m.tokensEstimated);
        assertEquals((401 + 3) / 4, m.promptTokens);
        assertEquals((37 + 3) / 4, m.completionTokens);
    }

    @Test public void localInferenceAndModelLoadAreSeparate() {
        SessionMeter meter = new SessionMeter(0, "LOCAL", "whisper", null, null);
        meter.onLocalModelLoaded(1_200);
        meter.onLocalInference(800);
        meter.onLocalInference(700);
        SessionMetric m = meter.finish(SessionMetric.COMPLETED, "one two", 3_000);
        assertEquals(1_200, m.modelLoadMs);
        assertEquals(1_500, m.transcriptionMs);
        assertEquals(2, m.transcriptionRequests);
        assertEquals(0, m.billedAudioSeconds, 0);
        assertNull(m.enhancementProvider);
    }

    @Test public void failedDictationKeepsCostButNotWords() {
        SessionMeter meter = new SessionMeter(0, "OPENROUTER", "openai/whisper-large-v3", null, null);
        meter.onTranscription(Usage.fromTranscription(null), 100, 5);
        SessionMetric m = meter.finish(SessionMetric.FAILED, "partial words here", 10);
        assertEquals(0, m.words);
        assertEquals(1, m.transcriptionRequests);
        assertEquals(5, m.billedAudioSeconds, 1e-9);
    }

    @Test public void reportedCostsAddUp() throws Exception {
        SessionMeter meter = new SessionMeter(0, "OPENROUTER", "m", "OPENROUTER", "c");
        meter.onTranscription(Usage.fromTranscription(new JSONObject("{\"usage\":{\"cost\":0.01}}")), 1, 1);
        meter.onTranscription(Usage.fromTranscription(new JSONObject("{\"usage\":{\"cost\":0.02}}")), 1, 1);
        meter.onEnhancement(Usage.fromChat(new JSONObject("{\"usage\":{\"cost\":0.005}}")), 1, 1, 1);
        SessionMetric m = meter.finish(SessionMetric.COMPLETED, "a", 0);
        assertEquals(0.03, m.transcriptionCostUsd, 1e-12);
        assertEquals(0.005, m.enhancementCostUsd, 1e-12);
        assertEquals(2, m.costedTranscriptionRequests);
        assertEquals(1, m.costedEnhancementRequests);
        SessionMetric none = new SessionMeter(0, "GROQ", "m", null, null).finish(SessionMetric.COMPLETED, "a", 0);
        assertTrue(Double.isNaN(none.transcriptionCostUsd));
    }

    @Test public void requestsWithoutAReportedCostAreKeptApart() throws Exception {
        SessionMeter meter = new SessionMeter(0, "LOCAL", "w", "OPENAI", "gpt-4o-mini");
        meter.onEnhancement(Usage.fromChat(new JSONObject(
                "{\"usage\":{\"cost\":0.01,\"prompt_tokens\":1000,\"completion_tokens\":0}}")), 1, 0, 0);
        meter.onEnhancement(Usage.fromChat(new JSONObject(
                "{\"usage\":{\"prompt_tokens\":1000,\"completion_tokens\":0}}")), 1, 0, 0);
        SessionMetric m = meter.finish(SessionMetric.COMPLETED, "x", 1);
        assertEquals(2_000, m.promptTokens);
        assertEquals(1_000, m.costedPromptTokens);
        assertEquals(1, m.costedEnhancementRequests);
        assertEquals(0.01015, new PriceTable(null).cost(m).usd, 1e-12);
    }

    @Test public void malformedTokenFieldsUseTheEstimate() throws Exception {
        Usage usage = Usage.fromChat(new JSONObject(
                "{\"usage\":{\"prompt_tokens\":null,\"completion_tokens\":\"broken\"}}"));
        assertFalse(usage.reported);
        SessionMeter meter = new SessionMeter(0, "LOCAL", "w", "CUSTOM", "m");
        meter.onEnhancement(usage, 1, 400, 80);
        SessionMetric m = meter.finish(SessionMetric.COMPLETED, "x", 1);
        assertTrue(m.tokensEstimated);
        assertEquals(100, m.promptTokens);
        assertEquals(20, m.completionTokens);
    }

    @Test public void microphoneDetailsAreCopied() {
        SessionMeter meter = new SessionMeter(0, "LOCAL", "w", null, null);
        meter.setMicCapture(new MicCapture("bluetooth", true, "standard", "medium", -30, -3, 0.01, 6));
        SessionMetric m = meter.finish(SessionMetric.COMPLETED, "a", 0);
        assertEquals("bluetooth", m.micDevice);
        assertTrue(m.micFellBack);
        assertEquals(-30, m.micSpeechLevelDb, 0);
        assertEquals(6, m.micGainDb, 0);
        assertNull(new SessionMeter(0, "LOCAL", "w", null, null).finish(SessionMetric.COMPLETED, "a", 0).micDevice);
    }

    @Test public void wordCountHandlesPunctuationNumbersAndEmptyText() {
        assertEquals(0, Words.count(null));
        assertEquals(0, Words.count("   ... !!"));
        assertEquals(4, Words.count("It's 5 o'clock, now."));
        assertEquals(2, Words.count("naïve café"));
    }
}
