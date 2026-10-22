package com.devilved.tonetunner;

import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import com.devilved.tonetunner.metrics.PriceTable;
import com.devilved.tonetunner.metrics.SessionMetric;
import com.devilved.tonetunner.metrics.UsageStore;
import com.devilved.tonetunner.utils.BarChartView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import static org.junit.Assert.*;

/** The dashboard loads stored usage off the main thread and shows totals, charts and prices. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class DashboardActivityTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        UsageStore.get(context).clear();
        context.getSharedPreferences(DashboardActivity.PRICE_PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void emptyHistoryShowsZeroes() throws Exception {
        try (ActivityController<DashboardActivity> controller = Robolectric.buildActivity(DashboardActivity.class).setup()) {
            DashboardActivity activity = loaded(controller);
            assertEquals("0", tile(activity, "words"));
            assertEquals("$0", tile(activity, "cost"));
            BarChartView chart = activity.getWindow().getDecorView().findViewWithTag("chart_words");
            assertNotNull(chart);
            assertEquals(activity.getString(R.string.dashboard_no_data), chart.getContentDescription());
        }
    }

    @Test public void storedDictationsAppearInTilesChartsAndReliability() throws Exception {
        long now = System.currentTimeMillis();
        SessionMetric groq = metric(now - 3_000, 100);
        groq.transcriptionProvider = "GROQ";
        groq.transcriptionModel = "whisper-large-v3-turbo";
        groq.billedAudioSeconds = 3600;
        groq.enhancementProvider = "OPENAI";
        groq.enhancementModel = "gpt-4o-mini";
        groq.promptTokens = 1000;
        groq.cachedTokens = 500;
        groq.completionTokens = 100;
        UsageStore.get(context).insert(groq);
        UsageStore.get(context).insert(metric(now - 2_000, 30));
        SessionMetric failed = metric(now - 1_000, 0);
        failed.status = SessionMetric.FAILED;
        UsageStore.get(context).insert(failed);

        try (ActivityController<DashboardActivity> controller = Robolectric.buildActivity(DashboardActivity.class).setup()) {
            DashboardActivity activity = loaded(controller);
            assertEquals("130", tile(activity, "words"));
            assertEquals("2", tile(activity, "dictations"));
            // 1 h of Groq turbo ($0.04) + 500 uncached, 500 cached and 100 output gpt-4o-mini tokens.
            double expected = 0.04 + (500 * 0.15 + 500 * 0.075 + 100 * 0.60) / 1_000_000;
            assertEquals(String.format(java.util.Locale.getDefault(), "$%.2f", expected), tile(activity, "cost"));
            TextView cache = activity.getWindow().getDecorView().findViewWithTag("cache_rate");
            assertTrue(cache.getText().toString().contains("50%"));
            TextView reliability = activity.getWindow().getDecorView().findViewWithTag("reliability");
            assertTrue(reliability.getText().toString().startsWith("Completed: 2 · Failed: 1"));
            BarChartView tokens = activity.getWindow().getDecorView().findViewWithTag("chart_tokens");
            assertTrue(String.valueOf(tokens.getContentDescription()).contains("500"));
            assertNotNull(activity.getWindow().getDecorView().findViewWithTag("model_enhancement_gpt-4o-mini"));

            View today = activity.getWindow().getDecorView().findViewWithTag("period_TODAY");
            today.performClick();
            assertEquals("130", tile(activity, "words"));
        }
    }

    @Test public void editedPricesAreReadBack() {
        context.getSharedPreferences(DashboardActivity.PRICE_PREFERENCES, Context.MODE_PRIVATE).edit()
                .putString(PriceTable.key("CUSTOM", "mine"), new PriceTable.Price(1, 0.5, 2, 3).encode())
                .putString("broken", "not,a,price")
                .commit();
        PriceTable table = DashboardActivity.readPrices(context);
        assertTrue(table.isEdited("CUSTOM", "mine"));
        assertEquals(3, table.price("CUSTOM", "mine").audioPerHour, 0);
    }

    @Test public void compactNumbersAndDurations() {
        assertEquals("999", DashboardActivity.number(999));
        assertEquals("1.5K", DashboardActivity.number(1_500));
        assertEquals("2M", DashboardActivity.number(2_000_000));
        assertEquals("45 s", DashboardActivity.duration(45_000));
        assertEquals("2 min 5 s", DashboardActivity.duration(125_000));
        assertEquals("1 h 1 min", DashboardActivity.duration(3_660_000));
    }

    private static DashboardActivity loaded(ActivityController<DashboardActivity> controller) throws Exception {
        DashboardActivity activity = controller.get();
        for (int i = 0; i < 500; i++) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            if (activity.getWindow().getDecorView().findViewWithTag("tile_words") != null) return activity;
            Thread.sleep(10);
        }
        fail("dashboard did not load");
        return activity;
    }

    private static String tile(DashboardActivity activity, String name) {
        TextView view = activity.getWindow().getDecorView().findViewWithTag("tile_" + name);
        assertNotNull(name, view);
        return view.getText().toString();
    }

    private static SessionMetric metric(long timestamp, int words) {
        SessionMetric m = new SessionMetric();
        m.timestamp = timestamp;
        m.words = words;
        m.audioMs = 10_000;
        m.processingMs = 1_000;
        m.transcriptionProvider = "LOCAL";
        m.transcriptionModel = "Whisper Small (on device)";
        m.transcriptionRequests = 1;
        m.transcriptionMs = 800;
        return m;
    }
}
