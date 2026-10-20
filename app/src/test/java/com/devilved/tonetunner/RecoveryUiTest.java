package com.devilved.tonetunner;

import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Looper;
import android.view.WindowManager;
import androidx.appcompat.app.AlertDialog;
import com.devilved.tonetunner.asr.PcmRecording;
import com.devilved.tonetunner.asr.RecoveryStore;
import com.devilved.tonetunner.asr.Whisper;
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
import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Exercises the user-facing recovery actions against actual private saved files. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class RecoveryUiTest {
    private Context context;
    private RecoveryStore store;
    private ActivityController<MainActivity> controller;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE).edit().clear()
                .putString("transcription.provider", "LOCAL").commit();
        deleteTree(new File(context.getNoBackupFilesDir(), "dictation-recovery"));
        store = new RecoveryStore(context);
    }

    @After public void cleanup() {
        if (controller != null) controller.pause().stop().destroy();
        deleteTree(new File(context.getNoBackupFilesDir(), "dictation-recovery"));
    }

    @Test public void copyAndDiscardUseTheSelectedSavedDictation() throws Exception {
        save("First completed words");
        save("Second completed words");
        RecoveryStore.Entry selected = store.list().get(0);
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();

        AlertDialog details = openFirstSaved(activity);
        details.getButton(DialogInterface.BUTTON_NEUTRAL).performClick();
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        assertEquals(selected.transcript, clipboard.getPrimaryClip().getItemAt(0).getText().toString());
        assertEquals("copying must not discard audio", 2, store.list().size());

        details = openFirstSaved(activity);
        details.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
        drain(activity, "recoveryFiles");
        assertNull(store.get(selected.id));
        assertEquals("discard must leave the other dictation intact", 1, store.list().size());
    }

    @Test public void failedRetryKeepsAudioAndReleasesTheForegroundDisplayPolicy() throws Exception {
        RecoveryStore.Entry saved = save("Earlier completed words");
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        openFirstSaved(activity).getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        assertTrue((activity.getWindow().getAttributes().flags
                & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0);
        Field field = MainActivity.class.getDeclaredField("mWhisper");
        field.setAccessible(true);
        Whisper facade = (Whisper) field.get(activity);
        // No real model is installed. The real facade must fail recoverably, preserving this entry.
        drain(facade, "worker");
        assertNotNull(store.get(saved.id));
        assertEquals("Earlier completed words", store.get(saved.id).transcript);
        assertEquals(0, activity.getWindow().getAttributes().flags
                & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        assertTrue(activity.findViewById(R.id.btnSavedDictations).isEnabled());
    }

    private AlertDialog openFirstSaved(MainActivity activity) throws Exception {
        activity.findViewById(R.id.btnSavedDictations).performClick();
        drain(activity, "recoveryFiles");
        AlertDialog list = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(list.getListView());
        list.getListView().performItemClick(list.getListView().getChildAt(0), 0, 0);
        return (AlertDialog) ShadowDialog.getLatestDialog();
    }

    private RecoveryStore.Entry save(String text) throws Exception {
        PcmRecording recording = PcmRecording.create(new File(context.getNoBackupFilesDir(), "ui-fixture"));
        recording.write(new byte[]{0, 8, 0, 8}, 0, 4);
        recording.finish();
        RecoveryStore.Entry entry = store.save(recording, 0, text, "auto", "en", false, null);
        recording.close();
        return entry;
    }

    private static void drain(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        ((ExecutorService) field.get(object)).submit(() -> { }).get(10, TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
}
