package com.devilved.tonetunner.metrics;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

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
        later.costedEnhancementRequests = 1;
        later.costedPromptTokens = 200;
        later.costedCachedTokens = 128;
        later.costedCompletionTokens = 4;
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
        assertEquals(1, second.costedEnhancementRequests);
        assertEquals(200, second.costedPromptTokens);
        assertEquals(128, second.costedCachedTokens);
        assertEquals(4, second.costedCompletionTokens);
        assertEquals(0, first.costedTranscriptionRequests);
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

    @Test public void versionOneRowsKeepTheirReportedCostForTheWholeStage() {
        Context context = RuntimeEnvironment.getApplication();
        store.close();
        context.deleteDatabase("usage.db");
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("usage.db"), null);
        db.execSQL("CREATE TABLE sessions (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "timestamp INTEGER NOT NULL, status TEXT NOT NULL, words INTEGER, characters INTEGER,"
                + "audio_ms INTEGER, processing_ms INTEGER,"
                + "t_provider TEXT, t_model TEXT, t_requests INTEGER, t_ms INTEGER, load_ms INTEGER,"
                + "billed_seconds REAL, t_input INTEGER, t_output INTEGER, t_audio INTEGER, t_cost REAL,"
                + "e_provider TEXT, e_model TEXT, e_requests INTEGER, e_ms INTEGER, prompt INTEGER,"
                + "cached INTEGER, completion INTEGER, reasoning INTEGER, estimated INTEGER, e_cost REAL,"
                + "mic_device TEXT, mic_fell_back INTEGER, mic_noise TEXT, mic_boost TEXT,"
                + "mic_level REAL, mic_peak REAL, mic_clipped REAL, mic_gain REAL)");
        db.execSQL("INSERT INTO sessions (timestamp, status, words, characters, audio_ms, processing_ms,"
                + " t_provider, t_model, t_requests, t_ms, load_ms, billed_seconds, t_input, t_output,"
                + " t_audio, t_cost, e_provider, e_model, e_requests, e_ms, prompt, cached, completion,"
                + " reasoning, estimated, e_cost, mic_fell_back, mic_clipped, mic_gain) VALUES"
                + " (1, 'completed', 3, 10, 1000, 500, 'OPENROUTER', 'm', 2, 100, 0, 20, 0, 0, 0, 0.002,"
                + " 'OPENAI', 'gpt-4o-mini', 1, 50, 900, 0, 30, 0, 0, NULL, 0, 0, 0)");
        db.setVersion(1);
        db.close();

        SessionMetric old = UsageStore.get(context).all().get(0);
        assertEquals(2, old.costedTranscriptionRequests);
        assertEquals(20, old.costedBilledAudioSeconds, 0);
        assertEquals("no reported enhancement cost: priced by rate", 0, old.costedEnhancementRequests);
        PriceTable.Cost cost = new PriceTable(null).cost(old);
        assertTrue(cost.priced);
        assertEquals(0.002 + 900 * 0.15 / 1e6 + 30 * 0.60 / 1e6, cost.usd, 1e-12);
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
