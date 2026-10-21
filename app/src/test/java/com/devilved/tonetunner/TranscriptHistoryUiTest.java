package com.devilved.tonetunner;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.ActivityInfo;
import android.content.Intent;
import android.os.Looper;
import android.widget.Button;
import android.widget.ListView;
import androidx.appcompat.app.AlertDialog;
import com.devilved.tonetunner.asr.TranscriptHistoryStore;
import org.junit.After;
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
import org.robolectric.shadows.ShadowDialog;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Verifies transcript copy and deletion separately from audio recovery. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class TranscriptHistoryUiTest {
    private Context context;
    private TranscriptHistoryStore store;
    private ActivityController<TranscriptHistoryActivity> controller;
    private ActivityController<MainActivity> mainController;

    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        deleteTree(new File(context.getNoBackupFilesDir(), "transcript-history"));
        deleteTree(new File(context.getNoBackupFilesDir(), "dictation-recovery"));
        store = new TranscriptHistoryStore(context);
    }

    @After public void cleanup() {
        if (controller != null) controller.pause().stop().destroy();
        if (mainController != null) mainController.pause().stop().destroy();
        deleteTree(new File(context.getNoBackupFilesDir(), "transcript-history"));
    }

    @Test public void selectedEntryCopiesBothVersionsAndRequiresDeleteConfirmation() throws Exception {
        store.saveResult("entry-1", "recognized words", "Polished final words.", "en", "note");
        controller = Robolectric.buildActivity(TranscriptHistoryActivity.class).setup();
        TranscriptHistoryActivity activity = controller.get();
        drain(activity);
        ListView list = activity.findViewById(R.id.transcript_history_list);
        assertEquals(1, list.getAdapter().getCount());

        openFirstEntry(activity, list);
        AlertDialog details = (AlertDialog) ShadowDialog.getLatestDialog();
        details.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        idleMain();
        assertEquals("Polished final words.", clipboardText());

        openFirstEntry(activity, list);
        details = (AlertDialog) ShadowDialog.getLatestDialog();
        details.getButton(DialogInterface.BUTTON_NEUTRAL).performClick();
        idleMain();
        assertEquals("recognized words", clipboardText());

        openFirstEntry(activity, list);
        details = (AlertDialog) ShadowDialog.getLatestDialog();
        details.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
        AlertDialog confirmation = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(store.get("entry-1"));
        confirmation.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
        idleMain();
        assertNotNull("cancel keeps history", store.get("entry-1"));

        openFirstEntry(activity, list);
        details = (AlertDialog) ShadowDialog.getLatestDialog();
        details.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
        confirmation = (AlertDialog) ShadowDialog.getLatestDialog();
        confirmation.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        drain(activity);
        assertNull("confirm removes only text history", store.get("entry-1"));
    }

    @Test public void listPaginatesAndShowsOnlyTheRequestedPage() throws Exception {
        for (int i = 0; i < 23; i++) {
            store.saveResult("entry-" + i, "raw " + i, "final " + i, "en", null);
        }
        controller = Robolectric.buildActivity(TranscriptHistoryActivity.class).setup();
        TranscriptHistoryActivity activity = controller.get();
        drain(activity);
        ListView list = activity.findViewById(R.id.transcript_history_list);
        assertEquals(20, list.getAdapter().getCount());
        Button more = activity.findViewById(R.id.transcript_history_load_more);
        assertEquals(android.view.View.VISIBLE, more.getVisibility());
        more.performClick();
        drain(activity);
        assertEquals(23, list.getAdapter().getCount());
        assertEquals(android.view.View.GONE, more.getVisibility());
    }

    @Test public void historyActivityIsNotExported() throws Exception {
        ActivityInfo info = context.getPackageManager().getActivityInfo(
                new ComponentName(context, TranscriptHistoryActivity.class), 0);
        assertFalse(info.exported);
    }

    @Test public void mainScreenOpensTheSeparateTranscriptHistory() {
        context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE).edit().clear()
                .putString("transcription.provider", "LOCAL").commit();
        mainController = Robolectric.buildActivity(MainActivity.class).setup();
        mainController.get().findViewById(R.id.btnTranscriptHistory).performClick();
        Intent started = Shadows.shadowOf(mainController.get()).getNextStartedActivity();
        assertNotNull(started);
        assertEquals(TranscriptHistoryActivity.class.getName(),
                started.getComponent().getClassName());
    }

    private void openFirstEntry(TranscriptHistoryActivity activity, ListView list) throws Exception {
        list.performItemClick(list.getAdapter().getView(0, null, list), 0, list.getAdapter().getItemId(0));
        drain(activity);
    }

    private String clipboardText() {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = clipboard.getPrimaryClip();
        return clip == null ? null : clip.getItemAt(0).getText().toString();
    }

    private static void drain(Object activity) throws Exception {
        java.lang.reflect.Field field = activity.getClass().getDeclaredField("historyIo");
        field.setAccessible(true);
        ((ExecutorService) field.get(activity)).submit(() -> { }).get(10, TimeUnit.SECONDS);
        idleMain();
    }

    private static void idleMain() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }

    private static void deleteTree(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        if (file.exists()) file.delete();
    }
}
