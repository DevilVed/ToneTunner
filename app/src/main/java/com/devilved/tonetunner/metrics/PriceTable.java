package com.devilved.tonetunner.metrics;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * API prices in USD. A provider-reported cost always wins; otherwise usage is multiplied by the
 * user's edited rate or the built-in rate (verified 21 October 2026, see the design document).
 * Models without a rate are reported as unpriced instead of being counted as free.
 */
public final class PriceTable {

    public static final class Price {
        public final double inputPerMillion;
        public final double cachedPerMillion;
        public final double outputPerMillion;
        public final double audioPerHour;

        public Price(double inputPerMillion, double cachedPerMillion, double outputPerMillion, double audioPerHour) {
            this.inputPerMillion = inputPerMillion;
            this.cachedPerMillion = cachedPerMillion;
            this.outputPerMillion = outputPerMillion;
            this.audioPerHour = audioPerHour;
        }

        boolean hasTokenRates() {
            return inputPerMillion > 0 || outputPerMillion > 0;
        }

        public String encode() {
            return inputPerMillion + "," + cachedPerMillion + "," + outputPerMillion + "," + audioPerHour;
        }

        public static Price decode(String encoded) {
            if (encoded == null) return null;
            String[] parts = encoded.split(",");
            if (parts.length != 4) return null;
            double[] values = new double[4];
            try {
                for (int i = 0; i < 4; i++) {
                    values[i] = Double.parseDouble(parts[i].trim());
                    if (!(values[i] >= 0) || Double.isInfinite(values[i])) return null;
                }
            } catch (NumberFormatException e) {
                return null;
            }
            return new Price(values[0], values[1], values[2], values[3]);
        }
    }

    public static final class Cost {
        public final double usd;
        /** False when some cloud usage had no rate; {@link #usd} then covers the priced parts only. */
        public final boolean priced;

        Cost(double usd, boolean priced) {
            this.usd = usd;
            this.priced = priced;
        }
    }

    private static final Map<String, Price> BUILT_IN;

    static {
        Map<String, Price> prices = new HashMap<>();
        prices.put(key("GROQ", "whisper-large-v3-turbo"), new Price(0, 0, 0, 0.04));
        prices.put(key("GROQ", "whisper-large-v3"), new Price(0, 0, 0, 0.111));
        prices.put(key("GROQ", "openai/gpt-oss-20b"), new Price(0.075, 0.0375, 0.30, 0));
        prices.put(key("GROQ", "openai/gpt-oss-120b"), new Price(0.15, 0.075, 0.60, 0));
        prices.put(key("OPENAI", "gpt-4o-mini-transcribe"), new Price(1.25, 1.25, 5.00, 0.18));
        prices.put(key("OPENAI", "gpt-4o-transcribe"), new Price(2.50, 2.50, 10.00, 0.36));
        prices.put(key("OPENAI", "whisper-1"), new Price(0, 0, 0, 0.36));
        prices.put(key("OPENAI", "gpt-4o-mini"), new Price(0.15, 0.075, 0.60, 0));
        prices.put(key("OPENAI", "gpt-4.1-mini"), new Price(0.40, 0.10, 1.60, 0));
        prices.put(key("OPENAI", "gpt-4.1-nano"), new Price(0.10, 0.025, 0.40, 0));
        prices.put(key("OPENAI", "gpt-5-mini"), new Price(0.25, 0.025, 2.00, 0));
        prices.put(key("OPENAI", "gpt-5-nano"), new Price(0.05, 0.005, 0.40, 0));
        BUILT_IN = Collections.unmodifiableMap(prices);
    }

    private final Map<String, Price> edited;

    public PriceTable(Map<String, Price> edited) {
        this.edited = edited == null ? new HashMap<>() : new HashMap<>(edited);
    }

    public static String key(String provider, String model) {
        return (provider == null ? "" : provider.trim().toUpperCase(Locale.ROOT)) + "|"
                + (model == null ? "" : model.trim().toLowerCase(Locale.ROOT));
    }

    /** Groq bills every transcription request as at least 10 seconds of audio. */
    public static double billedSeconds(String provider, double seconds) {
        double clean = seconds > 0 ? seconds : 0;
        return "GROQ".equals(provider) ? Math.max(10, clean) : clean;
    }

    /** The rate used for a model: the user's edit, else the built-in one, else null. */
    public Price price(String provider, String model) {
        String key = key(provider, model);
        Price price = edited.get(key);
        return price != null ? price : BUILT_IN.get(key);
    }

    public Price builtInPrice(String provider, String model) {
        return BUILT_IN.get(key(provider, model));
    }

    public boolean isEdited(String provider, String model) {
        return edited.containsKey(key(provider, model));
    }

    public Cost cost(SessionMetric m) {
        Cost transcription = transcriptionCost(m);
        Cost enhancement = enhancementCost(m);
        return new Cost(transcription.usd + enhancement.usd, transcription.priced && enhancement.priced);
    }

    public Cost transcriptionCost(SessionMetric m) {
        if (m.isLocalTranscription()) return new Cost(0, true);
        if (!Double.isNaN(m.transcriptionCostUsd)) return new Cost(m.transcriptionCostUsd, true);
        Price price = price(m.transcriptionProvider, m.transcriptionModel);
        boolean tokens = m.transcriptionInputTokens > 0 || m.transcriptionOutputTokens > 0;
        if (price != null && tokens && price.hasTokenRates()) {
            return new Cost(perMillion(m.transcriptionInputTokens, price.inputPerMillion)
                    + perMillion(m.transcriptionOutputTokens, price.outputPerMillion), true);
        }
        if (price != null && price.audioPerHour > 0) {
            return new Cost(m.billedAudioSeconds / 3600.0 * price.audioPerHour, true);
        }
        return new Cost(0, m.transcriptionRequests == 0 && m.billedAudioSeconds <= 0 && !tokens);
    }

    public Cost enhancementCost(SessionMetric m) {
        if (m.enhancementModel == null || m.enhancementModel.isEmpty()
                || "LOCAL".equals(m.enhancementProvider)) {
            return new Cost(0, true);
        }
        if (!Double.isNaN(m.enhancementCostUsd)) return new Cost(m.enhancementCostUsd, true);
        Price price = price(m.enhancementProvider, m.enhancementModel);
        if (price == null) {
            return new Cost(0, m.enhancementRequests == 0 && m.promptTokens == 0 && m.completionTokens == 0);
        }
        int cached = Math.min(m.cachedTokens, m.promptTokens);
        return new Cost(perMillion(m.promptTokens - cached, price.inputPerMillion)
                + perMillion(cached, price.cachedPerMillion)
                + perMillion(m.completionTokens, price.outputPerMillion), true);
    }

    private static double perMillion(long tokens, double rate) {
        return tokens * rate / 1_000_000.0;
    }
}
