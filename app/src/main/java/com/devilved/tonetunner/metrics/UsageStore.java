package com.devilved.tonetunner.metrics;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Private on-device store of {@link SessionMetric} rows for the usage dashboard. Excluded from
 * backup like every other app file. Holds counts and timings only, never transcript text.
 */
public final class UsageStore extends SQLiteOpenHelper {
    private static final String TAG = "UsageStore";
    private static final String NAME = "usage.db";
    private static final int VERSION = 1;
    private static final String TABLE = "sessions";
    /** Oldest rows beyond this are dropped; at ~300 bytes a row this stays near 60 MB at most. */
    static final int MAX_ROWS = 200_000;

    private static UsageStore instance;
    private final Context owner;

    public static synchronized UsageStore get(Context context) {
        Context app = context.getApplicationContext();
        // One helper per process; a new application object (as in tests) gets its own.
        if (instance == null || instance.owner != app) {
            if (instance != null) instance.close();
            instance = new UsageStore(app);
        }
        return instance;
    }

    private UsageStore(Context context) {
        super(context, NAME, null, VERSION);
        owner = context;
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "timestamp INTEGER NOT NULL, status TEXT NOT NULL, words INTEGER, characters INTEGER,"
                + "audio_ms INTEGER, processing_ms INTEGER,"
                + "t_provider TEXT, t_model TEXT, t_requests INTEGER, t_ms INTEGER, load_ms INTEGER,"
                + "billed_seconds REAL, t_input INTEGER, t_output INTEGER, t_audio INTEGER, t_cost REAL,"
                + "e_provider TEXT, e_model TEXT, e_requests INTEGER, e_ms INTEGER, prompt INTEGER,"
                + "cached INTEGER, completion INTEGER, reasoning INTEGER, estimated INTEGER, e_cost REAL,"
                + "mic_device TEXT, mic_fell_back INTEGER, mic_noise TEXT, mic_boost TEXT,"
                + "mic_level REAL, mic_peak REAL, mic_clipped REAL, mic_gain REAL)");
        db.execSQL("CREATE INDEX sessions_timestamp ON " + TABLE + "(timestamp)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Version 1 is the first schema.
    }

    /** Stores one dictation; failures are logged and ignored so metrics never break dictation. */
    public void insert(SessionMetric m) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            m.id = db.insert(TABLE, null, values(m));
            if (m.id > MAX_ROWS && m.id % 1000 == 0) {
                db.execSQL("DELETE FROM " + TABLE + " WHERE id <= ?", new Object[]{m.id - MAX_ROWS});
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not store usage metrics", e);
        }
    }

    public List<SessionMetric> all() {
        List<SessionMetric> rows = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(TABLE, null, null, null, null, null, "timestamp")) {
            while (c.moveToNext()) rows.add(read(c));
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not read usage metrics", e);
        }
        return rows;
    }

    public void clear() {
        getWritableDatabase().delete(TABLE, null, null);
    }

    private static ContentValues values(SessionMetric m) {
        ContentValues v = new ContentValues();
        v.put("timestamp", m.timestamp);
        v.put("status", m.status);
        v.put("words", m.words);
        v.put("characters", m.characters);
        v.put("audio_ms", m.audioMs);
        v.put("processing_ms", m.processingMs);
        v.put("t_provider", m.transcriptionProvider);
        v.put("t_model", m.transcriptionModel);
        v.put("t_requests", m.transcriptionRequests);
        v.put("t_ms", m.transcriptionMs);
        v.put("load_ms", m.modelLoadMs);
        v.put("billed_seconds", m.billedAudioSeconds);
        v.put("t_input", m.transcriptionInputTokens);
        v.put("t_output", m.transcriptionOutputTokens);
        v.put("t_audio", m.transcriptionAudioTokens);
        putReal(v, "t_cost", m.transcriptionCostUsd);
        v.put("e_provider", m.enhancementProvider);
        v.put("e_model", m.enhancementModel);
        v.put("e_requests", m.enhancementRequests);
        v.put("e_ms", m.enhancementMs);
        v.put("prompt", m.promptTokens);
        v.put("cached", m.cachedTokens);
        v.put("completion", m.completionTokens);
        v.put("reasoning", m.reasoningTokens);
        v.put("estimated", m.tokensEstimated ? 1 : 0);
        putReal(v, "e_cost", m.enhancementCostUsd);
        v.put("mic_device", m.micDevice);
        v.put("mic_fell_back", m.micFellBack ? 1 : 0);
        v.put("mic_noise", m.micNoiseReduction);
        v.put("mic_boost", m.micBoost);
        putReal(v, "mic_level", m.micSpeechLevelDb);
        putReal(v, "mic_peak", m.micPeakDb);
        putReal(v, "mic_clipped", m.micClippedRatio);
        putReal(v, "mic_gain", m.micGainDb);
        return v;
    }

    private static SessionMetric read(Cursor c) {
        SessionMetric m = new SessionMetric();
        m.id = c.getLong(c.getColumnIndexOrThrow("id"));
        m.timestamp = c.getLong(c.getColumnIndexOrThrow("timestamp"));
        m.status = text(c, "status");
        m.words = c.getInt(c.getColumnIndexOrThrow("words"));
        m.characters = c.getInt(c.getColumnIndexOrThrow("characters"));
        m.audioMs = c.getLong(c.getColumnIndexOrThrow("audio_ms"));
        m.processingMs = c.getLong(c.getColumnIndexOrThrow("processing_ms"));
        m.transcriptionProvider = text(c, "t_provider");
        m.transcriptionModel = text(c, "t_model");
        m.transcriptionRequests = c.getInt(c.getColumnIndexOrThrow("t_requests"));
        m.transcriptionMs = c.getLong(c.getColumnIndexOrThrow("t_ms"));
        m.modelLoadMs = c.getLong(c.getColumnIndexOrThrow("load_ms"));
        m.billedAudioSeconds = real(c, "billed_seconds", 0);
        m.transcriptionInputTokens = c.getInt(c.getColumnIndexOrThrow("t_input"));
        m.transcriptionOutputTokens = c.getInt(c.getColumnIndexOrThrow("t_output"));
        m.transcriptionAudioTokens = c.getInt(c.getColumnIndexOrThrow("t_audio"));
        m.transcriptionCostUsd = real(c, "t_cost", Double.NaN);
        m.enhancementProvider = text(c, "e_provider");
        m.enhancementModel = text(c, "e_model");
        m.enhancementRequests = c.getInt(c.getColumnIndexOrThrow("e_requests"));
        m.enhancementMs = c.getLong(c.getColumnIndexOrThrow("e_ms"));
        m.promptTokens = c.getInt(c.getColumnIndexOrThrow("prompt"));
        m.cachedTokens = c.getInt(c.getColumnIndexOrThrow("cached"));
        m.completionTokens = c.getInt(c.getColumnIndexOrThrow("completion"));
        m.reasoningTokens = c.getInt(c.getColumnIndexOrThrow("reasoning"));
        m.tokensEstimated = c.getInt(c.getColumnIndexOrThrow("estimated")) != 0;
        m.enhancementCostUsd = real(c, "e_cost", Double.NaN);
        m.micDevice = text(c, "mic_device");
        m.micFellBack = c.getInt(c.getColumnIndexOrThrow("mic_fell_back")) != 0;
        m.micNoiseReduction = text(c, "mic_noise");
        m.micBoost = text(c, "mic_boost");
        m.micSpeechLevelDb = real(c, "mic_level", Double.NaN);
        m.micPeakDb = real(c, "mic_peak", Double.NaN);
        m.micClippedRatio = real(c, "mic_clipped", 0);
        m.micGainDb = real(c, "mic_gain", 0);
        return m;
    }

    private static void putReal(ContentValues v, String key, double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) v.putNull(key);
        else v.put(key, value);
    }

    private static String text(Cursor c, String column) {
        int index = c.getColumnIndexOrThrow(column);
        return c.isNull(index) ? null : c.getString(index);
    }

    private static double real(Cursor c, String column, double fallback) {
        int index = c.getColumnIndexOrThrow(column);
        return c.isNull(index) ? fallback : c.getDouble(index);
    }
}
