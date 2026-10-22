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
        double billed = PriceTable.billedSeconds(metric.transcriptionProvider, seconds);
        metric.billedAudioSeconds += billed;
        metric.transcriptionInputTokens += usage.inputTokens;
        metric.transcriptionOutputTokens += usage.outputTokens;
        metric.transcriptionAudioTokens += usage.audioTokens;
        if (!Double.isNaN(usage.reportedCostUsd)) {
            reportedTranscriptionCost = add(reportedTranscriptionCost, usage.reportedCostUsd);
            metric.costedTranscriptionRequests++;
            metric.costedBilledAudioSeconds += billed;
            metric.costedTranscriptionInputTokens += usage.inputTokens;
            metric.costedTranscriptionOutputTokens += usage.outputTokens;
        }
    }

    @Override public synchronized void onEnhancement(Usage usage, long elapsedMs,
                                                     int inputCharacters, int outputCharacters) {
        metric.enhancementRequests++;
        metric.enhancementMs += Math.max(0, elapsedMs);
        int prompt, cached, completion;
        if (usage.reported) {
            prompt = usage.inputTokens;
            cached = usage.cachedTokens;
            completion = usage.outputTokens;
            metric.reasoningTokens += usage.reasoningTokens;
        } else {
            metric.tokensEstimated = true;
            prompt = Words.estimatedTokens(inputCharacters);
            cached = 0;
            completion = Words.estimatedTokens(outputCharacters);
        }
        metric.promptTokens += prompt;
        metric.cachedTokens += cached;
        metric.completionTokens += completion;
        if (!Double.isNaN(usage.reportedCostUsd)) {
            reportedEnhancementCost = add(reportedEnhancementCost, usage.reportedCostUsd);
            metric.costedEnhancementRequests++;
            metric.costedPromptTokens += prompt;
            metric.costedCachedTokens += cached;
            metric.costedCompletionTokens += completion;
        }
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
        return Double.isNaN(total) ? value : total + value;
    }
}
