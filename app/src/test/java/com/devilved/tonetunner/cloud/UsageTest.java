package com.devilved.tonetunner.cloud;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

/** Real provider usage is read from chat and transcription responses; absent fields stay unknown. */
public class UsageTest {

    @Test public void openAiChatUsageWithCachedAndReasoningTokens() throws Exception {
        Usage usage = Usage.fromChat(new JSONObject("{\"usage\":{\"prompt_tokens\":1200,\"completion_tokens\":300,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":1024},"
                + "\"completion_tokens_details\":{\"reasoning_tokens\":64}}}"));
        assertTrue(usage.reported);
        assertEquals(1200, usage.inputTokens);
        assertEquals(1024, usage.cachedTokens);
        assertEquals(300, usage.outputTokens);
        assertEquals(64, usage.reasoningTokens);
        assertTrue(Double.isNaN(usage.reportedCostUsd));
    }

    @Test public void openRouterReportsTheChargedCost() throws Exception {
        Usage usage = Usage.fromChat(new JSONObject(
                "{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"cost\":0.000123}}"));
        assertEquals(0.000123, usage.reportedCostUsd, 1e-12);
        assertEquals(0, usage.cachedTokens);
    }

    @Test public void missingOrMalformedChatUsageIsUnreported() throws Exception {
        assertFalse(Usage.fromChat(new JSONObject("{}")).reported);
        assertFalse(Usage.fromChat(new JSONObject("{\"usage\":\"x\"}")).reported);
        assertFalse(Usage.fromChat(null).reported);
        Usage negative = Usage.fromChat(new JSONObject(
                "{\"usage\":{\"prompt_tokens\":-5,\"completion_tokens\":\"7\",\"cost\":-1}}"));
        assertFalse("a negative count is not valid usage", negative.reported);
        assertEquals(0, negative.inputTokens);
        assertEquals(7, negative.outputTokens);
        assertTrue(Double.isNaN(negative.reportedCostUsd));
        Usage cachedAboveInput = Usage.fromChat(new JSONObject(
                "{\"usage\":{\"prompt_tokens\":10,\"prompt_tokens_details\":{\"cached_tokens\":50}}}"));
        assertEquals("cached tokens can never exceed the prompt", 10, cachedAboveInput.cachedTokens);
    }

    @Test public void presentButInvalidTokenFieldsAreUnreported() throws Exception {
        assertFalse(Usage.fromChat(new JSONObject(
                "{\"usage\":{\"prompt_tokens\":null,\"completion_tokens\":\"broken\"}}")).reported);
        assertFalse(Usage.fromChat(new JSONObject(
                "{\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":null}}")).reported);
        assertTrue("one valid field and the other absent", Usage.fromChat(new JSONObject(
                "{\"usage\":{\"prompt_tokens\":0}}")).reported);
        assertFalse(Usage.fromTranscription(new JSONObject(
                "{\"text\":\"hi\",\"usage\":{\"input_tokens\":\"x\",\"output_tokens\":3}}")).reported);
    }

    @Test public void tokenBasedTranscriptionUsage() throws Exception {
        Usage usage = Usage.fromTranscription(new JSONObject("{\"text\":\"hi\",\"usage\":{\"type\":\"tokens\","
                + "\"input_tokens\":140,\"output_tokens\":12,\"input_token_details\":{\"audio_tokens\":130,"
                + "\"text_tokens\":10}}}"));
        assertTrue(usage.reported);
        assertEquals(140, usage.inputTokens);
        assertEquals(130, usage.audioTokens);
        assertEquals(12, usage.outputTokens);
    }

    @Test public void durationBasedTranscriptionUsage() throws Exception {
        assertEquals(4.5, Usage.fromTranscription(new JSONObject(
                "{\"text\":\"hi\",\"usage\":{\"type\":\"duration\",\"seconds\":4.5}}")).audioSeconds, 1e-9);
        assertEquals(3.25, Usage.fromTranscription(new JSONObject(
                "{\"text\":\"hi\",\"duration\":3.25}")).audioSeconds, 1e-9);
        assertTrue(Double.isNaN(Usage.fromTranscription(new JSONObject("{\"text\":\"hi\"}")).audioSeconds));
    }
}
