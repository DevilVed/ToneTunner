package com.devilved.tonetunner.metrics;

/**
 * One dictation's measurements, the Android counterpart of the reference app's SessionMetric.
 * Holds counts and timings only, never transcript text. Unknown costs and levels are NaN.
 */
public final class SessionMetric {
    public static final String COMPLETED = "completed";
    public static final String FAILED = "failed";
    /** Stopped by the user after provider requests were already made (and paid for). */
    public static final String CANCELLED = "cancelled";

    public long id;
    public long timestamp;
    public String status = COMPLETED;
    public int words;
    public int characters;
    public long audioMs;
    public long processingMs;

    public String transcriptionProvider;
    public String transcriptionModel;
    public int transcriptionRequests;
    public long transcriptionMs;
    public long modelLoadMs;
    public double billedAudioSeconds;
    public int transcriptionInputTokens;
    public int transcriptionOutputTokens;
    public int transcriptionAudioTokens;
    public double transcriptionCostUsd = Double.NaN;

    public String enhancementProvider;
    public String enhancementModel;
    public int enhancementRequests;
    public long enhancementMs;
    public int promptTokens;
    public int cachedTokens;
    public int completionTokens;
    public int reasoningTokens;
    public boolean tokensEstimated;
    public double enhancementCostUsd = Double.NaN;

    public String micDevice;
    public boolean micFellBack;
    public String micNoiseReduction;
    public String micBoost;
    public double micSpeechLevelDb = Double.NaN;
    public double micPeakDb = Double.NaN;
    public double micClippedRatio;
    public double micGainDb;

    public boolean isCompleted() {
        return COMPLETED.equals(status);
    }

    public boolean isLocalTranscription() {
        return transcriptionProvider == null || "LOCAL".equals(transcriptionProvider);
    }
}
