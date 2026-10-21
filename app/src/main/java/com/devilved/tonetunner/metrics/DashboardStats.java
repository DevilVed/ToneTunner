package com.devilved.tonetunner.metrics;

import java.text.DateFormatSymbols;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Aggregates stored {@link SessionMetric}s for one period, following the reference app's
 * dashboard: its periods, 40 words-per-minute time saved, per-model summaries and its peak
 * two-hour window. Adds the API usage the reference only estimates: cost, tokens and cache hits.
 */
public final class DashboardStats {
    public enum Period { TODAY, WEEK, MONTH, YEAR, ALL }

    private static final double TYPING_WORDS_PER_MINUTE = 40;
    private static final int MAX_MONTH_BARS = 36;

    public static final class Bucket {
        public final String label;
        public final long start;
        public long words;
        public int dictations;
        public double costUsd;
        public long uncachedTokens;
        public long cachedTokens;
        public long outputTokens;

        Bucket(String label, long start) {
            this.label = label;
            this.start = start;
        }
    }

    public static final class ModelRow {
        public final String kind;
        public final String provider;
        public final String model;
        public int sessions;
        public long audioMs;
        public long tokens;
        public double costUsd;
        public boolean unpriced;
        long latencyMs;
        int requests;

        ModelRow(String kind, String provider, String model) {
            this.kind = kind;
            this.provider = provider;
            this.model = model;
        }

        /** Average time per request, or NaN. */
        public double avgRequestMs() {
            return requests == 0 ? Double.NaN : (double) latencyMs / requests;
        }
    }

    public int completed;
    public int failed;
    public int cancelled;
    public long words;
    public long audioMs;
    public long processingMs;
    public long timeSavedMs;
    public double costUsd;
    public int unpriced;
    public long promptTokens;
    public long cachedTokens;
    public long completionTokens;
    public long transcriptionTokens;
    public long transcriptionInputTokens;
    public long transcriptionOutputTokens;
    public long totalTokens;
    public double cacheHitRate = Double.NaN;

    public double avgProcessingMs = Double.NaN;
    public double avgSpeedFactor = Double.NaN;
    public double avgModelLoadMs = Double.NaN;
    public double avgCloudRequestMs = Double.NaN;
    public double avgEnhancementRequestMs = Double.NaN;
    public double speakingWpm = Double.NaN;

    public final List<Bucket> buckets = new ArrayList<>();
    public final long[] hourWords = new long[24];
    public final int[] hourSessions = new int[24];
    public int peakStartHour = -1;
    public final List<ModelRow> models = new ArrayList<>();

    public final Map<String, Integer> micDevices = new LinkedHashMap<>();
    public int micFallbacks;
    public double avgSpeechLevelDb = Double.NaN;
    public double maxPeakDb = Double.NaN;
    public double avgClippedRatio = Double.NaN;
    public double avgGainDb = Double.NaN;

    public static DashboardStats compute(List<SessionMetric> all, Period period, long now,
                                         TimeZone zone, PriceTable prices) {
        DashboardStats s = new DashboardStats();
        long start = periodStart(period, now, zone, all);
        long end = period == Period.ALL ? Long.MAX_VALUE : now + 1;
        s.buildBuckets(period, start, now, zone, all);

        Calendar calendar = Calendar.getInstance(zone);
        Map<String, ModelRow> models = new LinkedHashMap<>();
        double speedSum = 0;
        int speedCount = 0;
        long loadSum = 0;
        int loadCount = 0;
        long cloudMs = 0;
        int cloudRequests = 0;
        long enhancementMs = 0;
        int enhancementRequests = 0;
        double levelSum = 0;
        int levelCount = 0;
        double clipSum = 0;
        double gainSum = 0;
        int micCount = 0;

        for (SessionMetric m : all) {
            if (m == null || m.timestamp < start || m.timestamp >= end) continue;
            PriceTable.Cost cost = prices.cost(m);
            s.costUsd += cost.usd;
            if (!cost.priced) s.unpriced++;
            int cached = Math.min(m.cachedTokens, m.promptTokens);
            s.promptTokens += m.promptTokens;
            s.cachedTokens += cached;
            s.completionTokens += m.completionTokens;
            s.transcriptionInputTokens += m.transcriptionInputTokens;
            s.transcriptionOutputTokens += m.transcriptionOutputTokens;
            s.transcriptionTokens += m.transcriptionInputTokens + m.transcriptionOutputTokens;

            Bucket bucket = s.bucketFor(m.timestamp);
            if (bucket != null) {
                bucket.costUsd += cost.usd;
                bucket.uncachedTokens += m.promptTokens - cached + m.transcriptionInputTokens;
                bucket.cachedTokens += cached;
                bucket.outputTokens += m.completionTokens + m.transcriptionOutputTokens;
            }

            if (!m.isLocalTranscription()) {
                cloudMs += m.transcriptionMs;
                cloudRequests += m.transcriptionRequests;
            }
            enhancementMs += m.enhancementMs;
            enhancementRequests += m.enhancementRequests;
            s.addModels(models, m, prices);

            if (!m.isCompleted()) {
                if (SessionMetric.CANCELLED.equals(m.status)) s.cancelled++;
                else s.failed++;
                continue;
            }
            s.completed++;
            s.words += m.words;
            s.audioMs += m.audioMs;
            s.processingMs += m.processingMs;
            if (bucket != null) {
                bucket.words += m.words;
                bucket.dictations++;
            }
            calendar.setTimeInMillis(m.timestamp);
            int hour = calendar.get(Calendar.HOUR_OF_DAY);
            s.hourWords[hour] += m.words;
            s.hourSessions[hour]++;
            if (m.audioMs > 0 && m.transcriptionMs > 0) {
                speedSum += (double) m.audioMs / m.transcriptionMs;
                speedCount++;
            }
            if (m.modelLoadMs > 0) {
                loadSum += m.modelLoadMs;
                loadCount++;
            }
            if (m.micDevice != null) {
                micCount++;
                s.micDevices.put(m.micDevice, s.micDevices.getOrDefault(m.micDevice, 0) + 1);
                if (m.micFellBack) s.micFallbacks++;
                if (!Double.isNaN(m.micSpeechLevelDb)) {
                    levelSum += m.micSpeechLevelDb;
                    levelCount++;
                }
                if (!Double.isNaN(m.micPeakDb)) {
                    s.maxPeakDb = Double.isNaN(s.maxPeakDb) ? m.micPeakDb : Math.max(s.maxPeakDb, m.micPeakDb);
                }
                clipSum += m.micClippedRatio;
                gainSum += m.micGainDb;
            }
        }

        s.totalTokens = s.promptTokens + s.completionTokens + s.transcriptionTokens;
        if (s.promptTokens > 0) s.cacheHitRate = (double) s.cachedTokens / s.promptTokens;
        double typingMs = s.words / TYPING_WORDS_PER_MINUTE * 60_000;
        s.timeSavedMs = Math.max(0, Math.round(typingMs) - s.audioMs);
        if (s.completed > 0) s.avgProcessingMs = (double) s.processingMs / s.completed;
        if (speedCount > 0) s.avgSpeedFactor = speedSum / speedCount;
        if (loadCount > 0) s.avgModelLoadMs = (double) loadSum / loadCount;
        if (cloudRequests > 0) s.avgCloudRequestMs = (double) cloudMs / cloudRequests;
        if (enhancementRequests > 0) s.avgEnhancementRequestMs = (double) enhancementMs / enhancementRequests;
        if (s.audioMs > 0) s.speakingWpm = s.words / (s.audioMs / 60_000.0);
        if (levelCount > 0) s.avgSpeechLevelDb = levelSum / levelCount;
        if (micCount > 0) {
            s.avgClippedRatio = clipSum / micCount;
            s.avgGainDb = gainSum / micCount;
        }
        s.models.addAll(models.values());
        s.findPeakHours();
        return s;
    }

    private void addModels(Map<String, ModelRow> rows, SessionMetric m, PriceTable prices) {
        if (m.transcriptionModel != null && !m.transcriptionModel.isEmpty()) {
            ModelRow row = row(rows, "transcription", m.transcriptionProvider, m.transcriptionModel);
            row.sessions++;
            if (m.isCompleted()) row.audioMs += m.audioMs;
            row.tokens += m.transcriptionInputTokens + m.transcriptionOutputTokens;
            row.latencyMs += m.transcriptionMs;
            row.requests += m.transcriptionRequests;
            PriceTable.Cost cost = prices.transcriptionCost(m);
            row.costUsd += cost.usd;
            row.unpriced |= !cost.priced;
        }
        if (m.enhancementModel != null && !m.enhancementModel.isEmpty()) {
            ModelRow row = row(rows, "enhancement", m.enhancementProvider, m.enhancementModel);
            row.sessions++;
            row.tokens += m.promptTokens + m.completionTokens;
            row.latencyMs += m.enhancementMs;
            row.requests += m.enhancementRequests;
            PriceTable.Cost cost = prices.enhancementCost(m);
            row.costUsd += cost.usd;
            row.unpriced |= !cost.priced;
        }
    }

    private static ModelRow row(Map<String, ModelRow> rows, String kind, String provider, String model) {
        String key = kind + "|" + PriceTable.key(provider, model);
        ModelRow row = rows.get(key);
        if (row == null) {
            row = new ModelRow(kind, provider, model);
            rows.put(key, row);
        }
        return row;
    }

    /** The reference app's peak window: the best two consecutive hours by words, then by sessions. */
    private void findPeakHours() {
        long bestWords = 0;
        int bestSessions = 0;
        for (int hour = 0; hour < 24; hour++) {
            int next = (hour + 1) % 24;
            long windowWords = hourWords[hour] + hourWords[next];
            int windowSessions = hourSessions[hour] + hourSessions[next];
            if (windowWords > bestWords || (windowWords == bestWords && windowSessions > bestSessions)) {
                bestWords = windowWords;
                bestSessions = windowSessions;
                peakStartHour = hour;
            }
        }
    }

    private static long periodStart(Period period, long now, TimeZone zone, List<SessionMetric> all) {
        Calendar c = Calendar.getInstance(zone);
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        switch (period) {
            case TODAY: return c.getTimeInMillis();
            case WEEK: c.add(Calendar.DAY_OF_MONTH, -6); return c.getTimeInMillis();
            case MONTH: c.add(Calendar.DAY_OF_MONTH, -29); return c.getTimeInMillis();
            case YEAR: c.set(Calendar.DAY_OF_YEAR, 1); return c.getTimeInMillis();
            default: return Long.MIN_VALUE;
        }
    }

    private void buildBuckets(Period period, long start, long now, TimeZone zone, List<SessionMetric> all) {
        Calendar c = Calendar.getInstance(zone);
        Locale locale = Locale.getDefault();
        String[] months = new DateFormatSymbols(locale).getShortMonths();
        switch (period) {
            case TODAY:
                c.setTimeInMillis(start);
                for (int hour = 0; hour < 24; hour++) {
                    buckets.add(new Bucket(String.valueOf(hour), c.getTimeInMillis()));
                    c.add(Calendar.HOUR_OF_DAY, 1);
                }
                break;
            case WEEK:
            case MONTH:
                c.setTimeInMillis(start);
                int days = period == Period.WEEK ? 7 : 30;
                for (int day = 0; day < days; day++) {
                    String label = period == Period.WEEK
                            ? c.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, locale)
                            : String.valueOf(c.get(Calendar.DAY_OF_MONTH));
                    buckets.add(new Bucket(label, c.getTimeInMillis()));
                    c.add(Calendar.DAY_OF_MONTH, 1);
                }
                break;
            default:
                Calendar first = Calendar.getInstance(zone);
                first.setTimeInMillis(period == Period.YEAR ? start : earliest(all, now));
                Calendar last = Calendar.getInstance(zone);
                last.setTimeInMillis(now);
                int span = (last.get(Calendar.YEAR) - first.get(Calendar.YEAR)) * 12
                        + last.get(Calendar.MONTH) - first.get(Calendar.MONTH) + 1;
                int count = Math.max(1, Math.min(MAX_MONTH_BARS, span));
                c.setTimeInMillis(now);
                c.set(Calendar.DAY_OF_MONTH, 1);
                c.set(Calendar.HOUR_OF_DAY, 0);
                c.set(Calendar.MINUTE, 0);
                c.set(Calendar.SECOND, 0);
                c.set(Calendar.MILLISECOND, 0);
                c.add(Calendar.MONTH, -(count - 1));
                for (int i = 0; i < count; i++) {
                    buckets.add(new Bucket(months[c.get(Calendar.MONTH)], c.getTimeInMillis()));
                    c.add(Calendar.MONTH, 1);
                }
                break;
        }
    }

    private static long earliest(List<SessionMetric> all, long now) {
        long earliest = now;
        for (SessionMetric m : all) if (m != null && m.timestamp < earliest) earliest = m.timestamp;
        return earliest;
    }

    /** The bar a timestamp belongs to; buckets are sorted and contiguous. Older data has no bar. */
    private Bucket bucketFor(long timestamp) {
        Bucket found = null;
        for (Bucket bucket : buckets) {
            if (bucket.start <= timestamp) found = bucket;
            else break;
        }
        return found;
    }
}
