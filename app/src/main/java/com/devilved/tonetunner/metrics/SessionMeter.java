package com.devilved.tonetunner.metrics;

import com.devilved.tonetunner.asr.MicCapture;
import com.devilved.tonetunner.cloud.Usage;

/**
 * Collects one dictation's timings and usage while it is processed (on the dictation worker),
 * then produces its {@link SessionMetric}. Real provider usage is used whenever it is reported.
 */
public final class SessionMeter implements Usage.Listener {
    private final SessionMetric metric = new SessionMetric();
    private double reportedTranscriptionCost = Double.NaN;
    private double reportedEnhancementCost = Double.NaN;

    public SessionMeter(long timestamp, String transcriptionProvider, String transcriptionModel,
                        String enhancementProvider, String enhancementModel) {
        metric.timestamp = timestamp;
        metric.transcriptionProvider = transcriptionProvider;
        metric.transcriptionModel = transcriptionModel;
        metric.enhancementProvider = enhancementProvider;
        metric.enhancementModel = enhancementModel;
    }

    public synchronized void setAudioMs(long audioMs) {
        metric.audioMs = Math.max(0, audioMs);
    }

    public synchronized void setMicCapture(MicCapture capture) {
        if (capture == null) return;
        metric.micDevice = capture.device;
        metric.micFellBack = capture.fellBack;
        metric.micNoiseReduction = capture.noiseReduction;
        metric.micBoost = capture.boost;
        metric.micSpeechLevelDb = capture.speechLevelDb;
        metric.micPeakDb = capture.peakLevelDb;
        metric.micClippedRatio = capture.clippedRatio;
        metric.micGainDb = capture.averageGainDb;
    }

    public synchronized void onLocalModelLoaded(long elapsedMs) {
        metric.modelLoadMs += Math.max(0, elapsedMs);
    }

    public synchronized void onLocalInference(long elapsedMs) {
        metric.transcriptionRequests++;
        metric.transcriptionMs += Math.max(0, elapsedMs);
    }

    @Override public synchronized void onTranscription(Usage usage, long elapsedMs, double audioSeconds) {
        metric.transcriptionRequests++;
        metric.transcriptionMs += Math.max(0, elapsedMs);
        double seconds = Double.isNaN(usage.audioSeconds) ? audioSeconds : usage.audioSeconds;
        metric.billedAudioSeconds += PriceTable.billedSeconds(metric.transcriptionProvider, seconds);
        metric.transcriptionInputTokens += usage.inputTokens;
        metric.transcriptionOutputTokens += usage.outputTokens;
        metric.transcriptionAudioTokens += usage.audioTokens;
        reportedTranscriptionCost = add(reportedTranscriptionCost, usage.reportedCostUsd);
    }

    @Override public synchronized void onEnhancement(Usage usage, long elapsedMs,
                                                     int inputCharacters, int outputCharacters) {
        metric.enhancementRequests++;
        metric.enhancementMs += Math.max(0, elapsedMs);
        if (usage.reported) {
            metric.promptTokens += usage.inputTokens;
            metric.cachedTokens += usage.cachedTokens;
            metric.completionTokens += usage.outputTokens;
            metric.reasoningTokens += usage.reasoningTokens;
        } else {
            metric.tokensEstimated = true;
            metric.promptTokens += Words.estimatedTokens(inputCharacters);
            metric.completionTokens += Words.estimatedTokens(outputCharacters);
        }
        reportedEnhancementCost = add(reportedEnhancementCost, usage.reportedCostUsd);
    }

    public synchronized boolean hasRequests() {
        return metric.transcriptionRequests > 0 || metric.enhancementRequests > 0;
    }

    /** Completed dictations count their words; failed ones keep only usage, cost and timings. */
    public synchronized SessionMetric finish(String status, String text, long processingMs) {
        metric.status = status;
        boolean completed = SessionMetric.COMPLETED.equals(status);
        metric.words = completed ? Words.count(text) : 0;
        metric.characters = completed && text != null ? text.length() : 0;
        metric.processingMs = Math.max(0, processingMs);
        metric.transcriptionCostUsd = reportedTranscriptionCost;
        metric.enhancementCostUsd = reportedEnhancementCost;
        return metric;
    }

    private static double add(double total, double value) {
        if (Double.isNaN(value)) return total;
        return Double.isNaN(total) ? value : total + value;
    }
}
