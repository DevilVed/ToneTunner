package com.devilved.tonetunner.metrics;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;

import static org.junit.Assert.*;

/** The on-device metrics table keeps every field, including unknown (NaN) values and nulls. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class UsageStoreTest {
    private UsageStore store;

    @Before public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        store = UsageStore.get(context);
        store.clear();
    }

    @Test public void rowsRoundTripInTimeOrder() {
        SessionMetric later = metric(2_000);
        later.status = SessionMetric.FAILED;
        later.enhancementProvider = "OPENROUTER";
        later.enhancementModel = "openai/gpt-4o-mini";
        later.promptTokens = 300;
        later.cachedTokens = 256;
        later.completionTokens = 5;
        later.reasoningTokens = 2;
        later.tokensEstimated = true;
        later.enhancementCostUsd = 0.0004;
        later.micDevice = "builtin";
        later.micFellBack = true;
        later.micNoiseReduction = "maximum";
        later.micBoost = "maximum";
        later.micSpeechLevelDb = -31.5;
        later.micPeakDb = -2.25;
        later.micClippedRatio = 0.001;
        later.micGainDb = 12.5;
        store.insert(later);
        store.insert(metric(1_000));

        List<SessionMetric> rows = store.all();
        assertEquals(2, rows.size());
        SessionMetric first = rows.get(0);
        assertEquals(1_000, first.timestamp);
        assertEquals("GROQ", first.transcriptionProvider);
        assertEquals(12.0, first.billedAudioSeconds, 0);
        assertTrue("unknown costs stay unknown", Double.isNaN(first.transcriptionCostUsd));
        assertTrue(Double.isNaN(first.enhancementCostUsd));
        assertNull(first.enhancementModel);
        assertNull(first.micDevice);
        assertTrue(Double.isNaN(first.micSpeechLevelDb));

        SessionMetric second = rows.get(1);
        assertEquals(SessionMetric.FAILED, second.status);
        assertEquals(256, second.cachedTokens);
        assertEquals(2, second.reasoningTokens);
        assertTrue(second.tokensEstimated);
        assertEquals(0.0004, second.enhancementCostUsd, 1e-12);
        assertTrue(second.micFellBack);
        assertEquals(-31.5, second.micSpeechLevelDb, 0);
        assertEquals(-2.25, second.micPeakDb, 0);
        assertEquals(12.5, second.micGainDb, 0);
        assertEquals("maximum", second.micBoost);
    }

    @Test public void clearDeletesEverything() {
        store.insert(metric(1));
        store.clear();
        assertTrue(store.all().isEmpty());
    }

    @Test public void infiniteLevelsAreStoredAsUnknown() {
        SessionMetric m = metric(5);
        m.micPeakDb = Double.NEGATIVE_INFINITY;
        store.insert(m);
        assertTrue(Double.isNaN(store.all().get(0).micPeakDb));
    }

    private static SessionMetric metric(long timestamp) {
        SessionMetric m = new SessionMetric();
        m.timestamp = timestamp;
        m.words = 7;
        m.characters = 40;
        m.audioMs = 3_000;
        m.processingMs = 900;
        m.transcriptionProvider = "GROQ";
        m.transcriptionModel = "whisper-large-v3-turbo";
        m.transcriptionRequests = 1;
        m.transcriptionMs = 500;
        m.billedAudioSeconds = 12;
        return m;
    }
}
