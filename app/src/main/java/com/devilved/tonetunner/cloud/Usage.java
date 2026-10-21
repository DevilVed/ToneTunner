package com.devilved.tonetunner.cloud;

import org.json.JSONObject;

/** Provider-reported usage of one successful request; unknown values are 0 (tokens) or NaN. */
public final class Usage {

    /** Receives usage after each successful cloud request; called on the request thread. */
    public interface Listener {
        void onTranscription(Usage usage, long elapsedMs, double audioSeconds);

        void onEnhancement(Usage usage, long elapsedMs, int inputCharacters, int outputCharacters);
    }

    public final boolean reported;
    public final int inputTokens;
    public final int cachedTokens;
    public final int outputTokens;
    public final int reasoningTokens;
    public final int audioTokens;
    /** Audio length the provider reported (whisper-1 usage seconds or verbose JSON duration). */
    public final double audioSeconds;
    /** The amount charged as reported by the provider (OpenRouter), in USD. */
    public final double reportedCostUsd;

    private Usage(boolean reported, int inputTokens, int cachedTokens, int outputTokens,
                  int reasoningTokens, int audioTokens, double audioSeconds, double reportedCostUsd) {
        this.reported = reported;
        this.inputTokens = inputTokens;
        this.cachedTokens = Math.min(cachedTokens, inputTokens);
        this.outputTokens = outputTokens;
        this.reasoningTokens = reasoningTokens;
        this.audioTokens = audioTokens;
        this.audioSeconds = audioSeconds;
        this.reportedCostUsd = reportedCostUsd;
    }

    /** Chat completions: prompt/completion tokens, cached and reasoning details, OpenRouter cost. */
    public static Usage fromChat(JSONObject response) {
        JSONObject usage = response == null ? null : response.optJSONObject("usage");
        if (usage == null) return new Usage(false, 0, 0, 0, 0, 0, Double.NaN, Double.NaN);
        JSONObject prompt = usage.optJSONObject("prompt_tokens_details");
        JSONObject completion = usage.optJSONObject("completion_tokens_details");
        return new Usage(usage.has("prompt_tokens") || usage.has("completion_tokens"),
                count(usage, "prompt_tokens"), count(prompt, "cached_tokens"),
                count(usage, "completion_tokens"), count(completion, "reasoning_tokens"),
                count(prompt, "audio_tokens"), Double.NaN, amount(usage, "cost"));
    }

    /** Transcriptions: token usage (gpt-4o transcribe), duration usage (whisper-1) or verbose duration. */
    public static Usage fromTranscription(JSONObject response) {
        if (response == null) return new Usage(false, 0, 0, 0, 0, 0, Double.NaN, Double.NaN);
        JSONObject usage = response.optJSONObject("usage");
        double seconds = amount(usage, "seconds");
        if (Double.isNaN(seconds)) seconds = amount(response, "duration");
        if (usage == null) return new Usage(false, 0, 0, 0, 0, 0, seconds, Double.NaN);
        JSONObject details = usage.optJSONObject("input_token_details");
        return new Usage(usage.has("input_tokens") || usage.has("output_tokens"),
                count(usage, "input_tokens"), 0, count(usage, "output_tokens"), 0,
                count(details, "audio_tokens"), seconds, amount(usage, "cost"));
    }

    private static int count(JSONObject json, String key) {
        if (json == null) return 0;
        long value = json.optLong(key, 0);
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, value));
    }

    private static double amount(JSONObject json, String key) {
        if (json == null || !json.has(key)) return Double.NaN;
        double value = json.optDouble(key, Double.NaN);
        return value >= 0 && !Double.isInfinite(value) ? value : Double.NaN;
    }
}
