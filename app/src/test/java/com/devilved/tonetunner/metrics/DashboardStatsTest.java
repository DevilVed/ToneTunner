package com.devilved.tonetunner.metrics;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.TimeZone;

import static org.junit.Assert.*;

/** Dashboard aggregation: periods, totals, time saved, charts, peak hours, models and microphone. */
public class DashboardStatsTest {
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    private static final long NOW = at(2026, Calendar.OCTOBER, 21, 15, 30); // a Wednesday
    private final PriceTable prices = new PriceTable(new HashMap<>());

    @Test public void emptyHistoryProducesZeroesNotErrors() {
        for (DashboardStats.Period period : DashboardStats.Period.values()) {
            DashboardStats s = DashboardStats.compute(Collections.emptyList(), period, NOW, UTC, prices);
            assertEquals(0, s.completed);
            assertEquals(0, s.costUsd, 0);
            assertTrue(Double.isNaN(s.cacheHitRate));
            assertTrue(Double.isNaN(s.avgSpeedFactor));
            assertEquals(-1, s.peakStartHour);
            assertFalse(s.buckets.isEmpty());
        }
    }

    @Test public void periodsUseTheReferenceWindows() {
        List<SessionMetric> all = new ArrayList<>();
        all.add(local(NOW - 60_000, 10));                                    // today
        all.add(local(at(2026, Calendar.OCTOBER, 15, 0, 1), 20));            // 6 days ago: in 7 days
        all.add(local(at(2026, Calendar.OCTOBER, 14, 23, 59), 40));          // 7 days ago: in 30 only
        all.add(local(at(2026, Calendar.SEPTEMBER, 22, 0, 0), 80));          // 29 days ago: in 30 days
        all.add(local(at(2026, Calendar.JANUARY, 1, 0, 0), 160));            // this year
        all.add(local(at(2025, Calendar.DECEMBER, 31, 23, 0), 320));         // last year
        assertEquals(10, words(all, DashboardStats.Period.TODAY));
        assertEquals(30, words(all, DashboardStats.Period.WEEK));
        assertEquals(150, words(all, DashboardStats.Period.MONTH));
        assertEquals(310, words(all, DashboardStats.Period.YEAR));
        assertEquals(630, words(all, DashboardStats.Period.ALL));
    }

    @Test public void bucketsMatchThePeriod() {
        assertEquals(24, compute(DashboardStats.Period.TODAY).buckets.size());
        assertEquals(7, compute(DashboardStats.Period.WEEK).buckets.size());
        assertEquals(30, compute(DashboardStats.Period.MONTH).buckets.size());
        assertEquals("January to October", 10, compute(DashboardStats.Period.YEAR).buckets.size());
        List<SessionMetric> old = Collections.singletonList(local(at(2020, Calendar.MARCH, 1, 0, 0), 5));
        DashboardStats all = DashboardStats.compute(old, DashboardStats.Period.ALL, NOW, UTC, prices);
        assertEquals("all time is capped at 36 monthly bars", 36, all.buckets.size());
        assertEquals("totals still include older dictations", 5, all.words);
    }

    @Test public void wordsAndCostLandInTheirBuckets() {
        List<SessionMetric> all = new ArrayList<>();
        SessionMetric groq = local(at(2026, Calendar.OCTOBER, 20, 9, 0), 100);
        groq.transcriptionProvider = "GROQ";
        groq.transcriptionModel = "whisper-large-v3-turbo";
        groq.billedAudioSeconds = 3600;
        all.add(groq);
        DashboardStats s = DashboardStats.compute(all, DashboardStats.Period.WEEK, NOW, UTC, prices);
        DashboardStats.Bucket yesterday = s.buckets.get(5);
        assertEquals(100, yesterday.words);
        assertEquals(0.04, yesterday.costUsd, 1e-9);
        assertEquals(0.04, s.costUsd, 1e-9);
        assertEquals(0, s.buckets.get(6).words);
    }

    @Test public void timeSavedUsesFortyWordsPerMinuteTyping() {
        SessionMetric m = local(NOW, 400); // 10 minutes of typing
        m.audioMs = 120_000;
        DashboardStats s = DashboardStats.compute(Collections.singletonList(m), DashboardStats.Period.TODAY, NOW, UTC, prices);
        assertEquals(8 * 60_000, s.timeSavedMs);
        m.audioMs = 20 * 60_000;
        s = DashboardStats.compute(Collections.singletonList(m), DashboardStats.Period.TODAY, NOW, UTC, prices);
        assertEquals("never negative", 0, s.timeSavedMs);
    }

    @Test public void tokensAndCacheHitRate() {
        SessionMetric m = local(NOW, 5);
        m.enhancementProvider = "OPENAI";
        m.enhancementModel = "gpt-4o-mini";
        m.promptTokens = 1000;
        m.cachedTokens = 250;
        m.completionTokens = 100;
        m.transcriptionInputTokens = 40;
        m.transcriptionOutputTokens = 10;
        DashboardStats s = DashboardStats.compute(Collections.singletonList(m), DashboardStats.Period.TODAY, NOW, UTC, prices);
        assertEquals(0.25, s.cacheHitRate, 1e-9);
        assertEquals(1150, s.totalTokens);
        DashboardStats.Bucket hour = s.buckets.get(15);
        assertEquals(750 + 40, hour.uncachedTokens);
        assertEquals(250, hour.cachedTokens);
        assertEquals(110, hour.outputTokens);
    }

    @Test public void failedDictationsCountForCostAndReliabilityOnly() {
        SessionMetric failed = local(NOW, 0);
        failed.status = SessionMetric.FAILED;
        failed.transcriptionProvider = "GROQ";
        failed.transcriptionModel = "whisper-large-v3";
        failed.billedAudioSeconds = 3600;
        failed.audioMs = 3_600_000;
        DashboardStats s = DashboardStats.compute(Collections.singletonList(failed), DashboardStats.Period.TODAY, NOW, UTC, prices);
        assertEquals(0, s.completed);
        assertEquals(1, s.failed);
        assertEquals(0, s.audioMs);
        assertEquals(0.111, s.costUsd, 1e-9);
        failed.status = SessionMetric.CANCELLED;
        s = DashboardStats.compute(Collections.singletonList(failed), DashboardStats.Period.TODAY, NOW, UTC, prices);
        assertEquals(0, s.failed);
        assertEquals(1, s.cancelled);
        assertEquals("a cancelled dictation's paid requests still count", 0.111, s.costUsd, 1e-9);
    }

    @Test public void speedFactorsAndLatencies() {
        SessionMetric a = local(NOW, 30);
        a.audioMs = 10_000;
        a.transcriptionMs = 2_000;
        a.processingMs = 2_500;
        a.modelLoadMs = 900;
        a.transcriptionRequests = 1;
        SessionMetric b = local(NOW, 30);
        b.transcriptionProvider = "GROQ";
        b.transcriptionModel = "whisper-large-v3-turbo";
        b.audioMs = 20_000;
        b.transcriptionMs = 1_000;
        b.processingMs = 1_500;
        b.transcriptionRequests = 2;
        b.enhancementMs = 600;
        b.enhancementRequests = 2;
        List<SessionMetric> all = new ArrayList<>();
        all.add(a);
        all.add(b);
        DashboardStats s = DashboardStats.compute(all, DashboardStats.Period.TODAY, NOW, UTC, prices);
        assertEquals((5.0 + 20.0) / 2, s.avgSpeedFactor, 1e-9);
        assertEquals(2_000, s.avgProcessingMs, 1e-9);
        assertEquals(900, s.avgModelLoadMs, 1e-9);
        assertEquals("per cloud request", 500, s.avgCloudRequestMs, 1e-9);
        assertEquals(300, s.avgEnhancementRequestMs, 1e-9);
        assertEquals("60 words in 30 s of speech", 120, s.speakingWpm, 1e-9);
        assertEquals(2, s.models.size());
    }

    @Test public void peakHoursFindTheBestTwoHourWindowAcrossMidnight() {
        List<SessionMetric> all = new ArrayList<>();
        all.add(local(at(2026, Calendar.OCTOBER, 20, 23, 10), 50));
        all.add(local(at(2026, Calendar.OCTOBER, 21, 0, 10), 50));
        all.add(local(at(2026, Calendar.OCTOBER, 21, 9, 0), 70));
        DashboardStats s = DashboardStats.compute(all, DashboardStats.Period.WEEK, NOW, UTC, prices);
        assertEquals(23, s.peakStartHour);
        assertEquals(50, s.hourWords[0]);
        assertEquals(1, s.hourSessions[9]);
    }

    @Test public void peakTiesPreferMoreSessions() {
        List<SessionMetric> all = new ArrayList<>();
        all.add(local(at(2026, Calendar.OCTOBER, 21, 8, 0), 60));
        all.add(local(at(2026, Calendar.OCTOBER, 21, 13, 0), 30));
        all.add(local(at(2026, Calendar.OCTOBER, 21, 13, 30), 30));
        DashboardStats s = DashboardStats.compute(all, DashboardStats.Period.TODAY, NOW, UTC, prices);
        assertEquals("windows 12-14 and 13-15 tie with 60 words and 2 sessions; earliest wins", 12, s.peakStartHour);
    }

    @Test public void microphoneSummary() {
        SessionMetric a = local(NOW, 1);
        a.micDevice = "builtin";
        a.micSpeechLevelDb = -30;
        a.micPeakDb = -6;
        a.micGainDb = 10;
        SessionMetric b = local(NOW, 1);
        b.micDevice = "bluetooth";
        b.micFellBack = true;
        b.micSpeechLevelDb = Double.NaN;
        b.micPeakDb = -2;
        b.micGainDb = 0;
        b.micClippedRatio = 0.02;
        List<SessionMetric> all = new ArrayList<>();
        all.add(a);
        all.add(b);
        DashboardStats s = DashboardStats.compute(all, DashboardStats.Period.TODAY, NOW, UTC, prices);
        assertEquals(Integer.valueOf(1), s.micDevices.get("builtin"));
        assertEquals(Integer.valueOf(1), s.micDevices.get("bluetooth"));
        assertEquals(1, s.micFallbacks);
        assertEquals("NaN levels are skipped", -30, s.avgSpeechLevelDb, 1e-9);
        assertEquals(-2, s.maxPeakDb, 1e-9);
        assertEquals(0.01, s.avgClippedRatio, 1e-9);
        assertEquals(5, s.avgGainDb, 1e-9);
    }

    @Test public void futureTimestampsAreIgnoredByBoundedPeriods() {
        SessionMetric future = local(NOW + 3_600_000L * 48, 99);
        DashboardStats s = DashboardStats.compute(Collections.singletonList(future), DashboardStats.Period.TODAY, NOW, UTC, prices);
        assertEquals(0, s.words);
    }

    // ---- helpers ----

    private DashboardStats compute(DashboardStats.Period period) {
        return DashboardStats.compute(Collections.emptyList(), period, NOW, UTC, prices);
    }

    private int words(List<SessionMetric> all, DashboardStats.Period period) {
        return (int) DashboardStats.compute(all, period, NOW, UTC, prices).words;
    }

    private static SessionMetric local(long timestamp, int words) {
        SessionMetric m = new SessionMetric();
        m.timestamp = timestamp;
        m.status = SessionMetric.COMPLETED;
        m.words = words;
        m.transcriptionProvider = "LOCAL";
        m.transcriptionModel = "whisper-small";
        return m;
    }

    private static long at(int year, int month, int day, int hour, int minute) {
        Calendar c = Calendar.getInstance(UTC);
        c.clear();
        c.set(year, month, day, hour, minute, 0);
        return c.getTimeInMillis();
    }
}
