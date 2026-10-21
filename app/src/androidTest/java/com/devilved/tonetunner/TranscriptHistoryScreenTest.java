package com.devilved.tonetunner;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.devilved.tonetunner.asr.TranscriptHistoryStore;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Opens the history screen through the real main screen and draws a seeded persisted row. */
@RunWith(AndroidJUnit4.class)
public class TranscriptHistoryScreenTest {

    @Test public void transcriptHistoryOpensFromMainScreenAndDrawsPersistedEntry() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext().getApplicationContext();
        String fixtureId = "android-screen-test-" + UUID.randomUUID();
        String fixtureText = "Screen-test transcript fixture";
        ExecutorService storeWorker = Executors.newSingleThreadExecutor();
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
                TranscriptHistoryActivity.class.getName(), null, false);
        Activity history = null;
        try {
            storeWorker.submit(() -> {
                try (TranscriptHistoryStore store = new TranscriptHistoryStore(context)) {
                    store.saveResult(fixtureId, fixtureText, fixtureText, "en", null);
                }
                return null;
            }).get(15, TimeUnit.SECONDS);

            try (ActivityScenario<MainActivity> main = ActivityScenario.launch(MainActivity.class)) {
                main.onActivity(activity -> {
                    View historyButton = activity.findViewById(R.id.btnTranscriptHistory);
                    assertNotNull("The main screen must expose transcript history", historyButton);
                    historyButton.performClick();
                });
                history = instrumentation.waitForMonitorWithTimeout(monitor, 15_000);
                assertNotNull("Tapping transcript history must open its screen", history);

                awaitHistoryLoad(instrumentation, history);
                assertFixtureRow(instrumentation, history, fixtureId, fixtureText);
                assertLaidOutAndDrawable(instrumentation, history);
            }
        } finally {
            try {
                if (history != null) instrumentation.runOnMainSync(history::finish);
                instrumentation.removeMonitor(monitor);
                storeWorker.submit(() -> {
                    try (TranscriptHistoryStore store = new TranscriptHistoryStore(context)) {
                        store.delete(fixtureId);
                    }
                    return null;
                }).get(15, TimeUnit.SECONDS);
            } finally {
                storeWorker.shutdownNow();
            }
        }
    }

    private static void awaitHistoryLoad(Instrumentation instrumentation, Activity history) throws Exception {
        Field field = TranscriptHistoryActivity.class.getDeclaredField("historyIo");
        field.setAccessible(true);
        ExecutorService historyIo = (ExecutorService) field.get(history);
        historyIo.submit(() -> { }).get(15, TimeUnit.SECONDS);
        instrumentation.waitForIdleSync();
    }

    private static void assertFixtureRow(Instrumentation instrumentation, Activity history,
                                         String fixtureId, String fixtureText) {
        boolean[] found = {false};
        instrumentation.runOnMainSync(() -> {
            ListView list = history.findViewById(R.id.transcript_history_list);
            assertNotNull("History must render its transcript list", list);
            assertNotNull("History must attach a list adapter", list.getAdapter());
            for (int index = 0; index < list.getAdapter().getCount(); index++) {
                TranscriptHistoryStore.Summary summary =
                        (TranscriptHistoryStore.Summary) list.getAdapter().getItem(index);
                if (fixtureId.equals(summary.id)) {
                    assertTrue("The persisted preview must contain the seeded transcript",
                            summary.preview.contains(fixtureText));
                    View row = list.getAdapter().getView(index, null, list);
                    assertTrue(row instanceof TextView);
                    assertTrue(((TextView) row).getText().toString().contains(fixtureText));
                    found[0] = true;
                    break;
                }
            }
        });
        assertTrue("The history page must include the persisted fixture", found[0]);
    }

    private static void assertLaidOutAndDrawable(Instrumentation instrumentation, Activity activity) {
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> {
            View decor = activity.getWindow().getDecorView();
            ViewGroup content = decor.findViewById(android.R.id.content);
            assertNotNull(content);
            assertTrue("The history page must have content", content.getChildCount() > 0);
            View page = content.getChildAt(0);
            assertTrue("The history page must be laid out", page.getWidth() > 0 && page.getHeight() > 0);
            Bitmap bitmap = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
            try {
                decor.draw(new Canvas(bitmap));
            } finally {
                bitmap.recycle();
            }
        });
    }
}
