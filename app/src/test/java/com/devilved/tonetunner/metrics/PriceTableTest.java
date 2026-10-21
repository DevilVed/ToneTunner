package com.devilved.tonetunner.metrics;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/** Prices: provider-reported cost first, then the built-in or edited rates; unknown models stay unpriced. */
public class PriceTableTest {
    private final PriceTable table = new PriceTable(new HashMap<>());

    @Test public void localTranscriptionIsFree() {
        SessionMetric m = metric("LOCAL", "whisper-small");
        m.audioMs = 60_000;
        PriceTable.Cost cost = table.cost(m);
        assertTrue(cost.priced);
        assertEquals(0, cost.usd, 0);
    }

    @Test public void groqWhisperIsBilledPerAudioHour() {
        SessionMetric m = metric("GROQ", "whisper-large-v3-turbo");
        m.billedAudioSeconds = 3600;
        assertEquals(0.04, table.cost(m).usd, 1e-9);
        m.transcriptionModel = "whisper-large-v3";
        assertEquals(0.111, table.cost(m).usd, 1e-9);
    }

    @Test public void groqBillsAtLeastTenSecondsPerRequest() {
        assertEquals(10, PriceTable.billedSeconds("GROQ", 2.0), 0);
        assertEquals(12.5, PriceTable.billedSeconds("GROQ", 12.5), 0);
        assertEquals(2.0, PriceTable.billedSeconds("OPENAI", 2.0), 0);
    }

    @Test public void tokenTranscriptionUsesTokensAndFallsBackToMinutes() {
        SessionMetric m = metric("OPENAI", "gpt-4o-mini-transcribe");
        m.transcriptionInputTokens = 1_000_000;
        m.transcriptionOutputTokens = 1_000_000;
        assertEquals(1.25 + 5.00, table.cost(m).usd, 1e-9);
        SessionMetric estimate = metric("OPENAI", "gpt-4o-mini-transcribe");
        estimate.billedAudioSeconds = 60;
        assertEquals(0.003, table.cost(estimate).usd, 1e-9);
    }

    @Test public void enhancementSeparatesCachedInput() {
        SessionMetric m = metric("LOCAL", "");
        m.enhancementProvider = "OPENAI";
        m.enhancementModel = "gpt-4o-mini";
        m.promptTokens = 2_000_000;
        m.cachedTokens = 1_000_000;
        m.completionTokens = 1_000_000;
        assertEquals(0.15 + 0.075 + 0.60, table.cost(m).usd, 1e-9);
    }

    @Test public void reportedCostWinsOverRates() {
        SessionMetric m = metric("OPENROUTER", "openai/whisper-large-v3");
        m.transcriptionCostUsd = 0.002;
        m.enhancementProvider = "OPENROUTER";
        m.enhancementModel = "anything/unknown";
        m.promptTokens = 999;
        m.enhancementCostUsd = 0.001;
        PriceTable.Cost cost = table.cost(m);
        assertTrue(cost.priced);
        assertEquals(0.003, cost.usd, 1e-12);
    }

    @Test public void unknownModelIsUnpricedButKnownPartsStillCount() {
        SessionMetric m = metric("CUSTOM", "my-model");
        m.billedAudioSeconds = 60;
        m.enhancementProvider = "GROQ";
        m.enhancementModel = "openai/gpt-oss-20b";
        m.promptTokens = 1_000_000;
        PriceTable.Cost cost = table.cost(m);
        assertFalse(cost.priced);
        assertEquals(0.075, cost.usd, 1e-9);
    }

    @Test public void editedRatesOverrideBuiltInOnesAndCanBeCleared() {
        Map<String, PriceTable.Price> edits = new HashMap<>();
        edits.put(PriceTable.key("CUSTOM", "my-model"), new PriceTable.Price(0, 0, 0, 1.0));
        PriceTable edited = new PriceTable(edits);
        SessionMetric m = metric("CUSTOM", "My-Model ");
        m.billedAudioSeconds = 1800;
        assertEquals("keys ignore case and spaces", 0.5, edited.cost(m).usd, 1e-9);
        assertTrue(edited.isEdited("CUSTOM", "my-model"));
        assertNull(table.price("CUSTOM", "my-model"));
    }

    @Test public void priceEncodingRoundTrips() {
        PriceTable.Price price = new PriceTable.Price(0.15, 0.075, 0.6, 0.36);
        PriceTable.Price decoded = PriceTable.Price.decode(price.encode());
        assertEquals(0.15, decoded.inputPerMillion, 0);
        assertEquals(0.075, decoded.cachedPerMillion, 0);
        assertEquals(0.6, decoded.outputPerMillion, 0);
        assertEquals(0.36, decoded.audioPerHour, 0);
        assertNull(PriceTable.Price.decode("garbage"));
        assertNull(PriceTable.Price.decode("1,2,3"));
        assertNull(PriceTable.Price.decode("1,-2,3,4"));
        assertNull(PriceTable.Price.decode(null));
    }

    private static SessionMetric metric(String provider, String model) {
        SessionMetric m = new SessionMetric();
        m.transcriptionProvider = provider;
        m.transcriptionModel = model;
        return m;
    }
}
