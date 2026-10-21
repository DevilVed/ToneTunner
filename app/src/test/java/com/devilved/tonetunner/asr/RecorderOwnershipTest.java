package com.devilved.tonetunner.asr;

import android.content.Context;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.rules.TemporaryFolder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.lang.reflect.Field;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Verifies per-recorder ownership using completed spool files, without opening a microphone. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class RecorderOwnershipTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test public void recorderOwnersCanOnlyClaimTheirOwnCompletedFiles() throws Exception {
        Recorder firstOwner = new Recorder(application());
        Recorder secondOwner = new Recorder(application());
        AudioFixture first = completedRecording("first");
        AudioFixture second = completedRecording("second");
        try {
            publish(firstOwner, first.recording);
            publish(secondOwner, second.recording);

            assertSame(first.recording, firstOwner.takeRecording());
            assertSame(second.recording, secondOwner.takeRecording());
            assertNull(firstOwner.takeRecording());
            assertNull(secondOwner.takeRecording());
        } finally {
            firstOwner.release();
            secondOwner.release();
            closeIfOpen(first.recording);
            closeIfOpen(second.recording);
        }
    }

    @Test public void releaseDeletesOnlyItsUnclaimedFileAndPreservesTransferredFile() throws Exception {
        Recorder claimant = new Recorder(application());
        Recorder ownerWithUnclaimedFile = new Recorder(application());
        AudioFixture transferred = completedRecording("transferred");
        AudioFixture unclaimed = completedRecording("unclaimed");
        try {
            publish(claimant, transferred.recording);
            publish(ownerWithUnclaimedFile, unclaimed.recording);

            assertSame(transferred.recording, claimant.takeRecording());
            claimant.release();
            ownerWithUnclaimedFile.release();

            assertTrue(transferred.file.exists());
            assertFalse(transferred.recording.isClosed());
            assertFalse(unclaimed.file.exists());
            assertTrue(unclaimed.recording.isClosed());
        } finally {
            claimant.release();
            ownerWithUnclaimedFile.release();
            closeIfOpen(transferred.recording);
            closeIfOpen(unclaimed.recording);
        }
    }

    private AudioFixture completedRecording(String name) throws Exception {
        File directory = temporaryFolder.newFolder(name);
        PcmRecording recording = PcmRecording.create(directory);
        recording.write(new byte[]{1, 2, 3, 4}, 0, 4);
        recording.finish();
        File[] files = directory.listFiles();
        assertNotNull(files);
        assertEquals(1, files.length);
        return new AudioFixture(recording, files[0]);
    }

    private static void publish(Recorder owner, PcmRecording recording) throws Exception {
        Field field = Recorder.class.getDeclaredField("publishedRecording");
        field.setAccessible(true);
        field.set(owner, recording);
    }

    private static Context application() {
        return RuntimeEnvironment.getApplication();
    }

    private static void closeIfOpen(PcmRecording recording) throws Exception {
        if (!recording.isClosed()) recording.close();
    }

    private static final class AudioFixture {
        final PcmRecording recording;
        final File file;

        AudioFixture(PcmRecording recording, File file) {
            this.recording = recording;
            this.file = file;
        }
    }
}
