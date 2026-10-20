package com.devilved.tonetunner.cloud;

import android.content.Context;
import android.content.ContextWrapper;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.rules.TemporaryFolder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.KeyGenerator;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.crypto.SecretKey;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Exercises the encrypted file format and AtomicFile path with synthetic credentials only. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SecretStoreTest {
    private static final String SAFE_STORAGE_ERROR =
            "Secure credential storage is unavailable. Try again or remove the saved key.";
    private static final String FIXTURE_KEY = "fixture-secret-not-a-real-key-012345";
    private static final String TRANSCRIPTION_ENDPOINT =
            "https://api.example.invalid/v1/audio/transcriptions";

    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Context appContext;
    private Context storageContext;
    private File noBackupDirectory;
    private SecretKey aesKey;
    private SecretStore.KeySource keySource;
    private SecretStore store;

    @Before public void setUp() throws Exception {
        appContext = RuntimeEnvironment.getApplication();
        appContext.getSharedPreferences("ai_settings", Context.MODE_PRIVATE).edit().clear().commit();
        noBackupDirectory = temporaryFolder.newFolder("no-backup");
        storageContext = new StorageContext(appContext, noBackupDirectory);
        aesKey = newAesKey();
        keySource = new FixedKeySource(aesKey);
        store = new SecretStore(storageContext, keySource);
    }

    @Test public void persistsAesGcmCiphertextAndDecryptsItAfterReopeningStore() throws Exception {
        store.putKey(Provider.GROQ, "transcription", TRANSCRIPTION_ENDPOINT, FIXTURE_KEY);
        File ciphertextFile = onlyCiphertextFile();
        byte[] packed = readFile(ciphertextFile);

        assertEquals(1, packed[0] & 0xff);
        assertEquals(12, packed[1] & 0xff);
        int encryptedLength = ByteBuffer.wrap(packed, 2, 4).getInt();
        assertEquals(packed.length - 18, encryptedLength);
        assertEquals(FIXTURE_KEY.getBytes(StandardCharsets.UTF_8).length + 16, encryptedLength);
        assertFalse(contains(packed, FIXTURE_KEY.getBytes(StandardCharsets.UTF_8)));

        SecretStore reopened = new SecretStore(storageContext, new FixedKeySource(aesKey));
        assertCredentialEquals(FIXTURE_KEY,
                reopened.getKey(Provider.GROQ, "transcription", TRANSCRIPTION_ENDPOINT));
    }

    @Test public void providerStageAndEndpointEachHaveIndependentCredentialIdentity() throws Exception {
        String otherEndpoint = "https://api.example.invalid/v2/audio/transcriptions";
        store.putKey(Provider.GROQ, "transcription", TRANSCRIPTION_ENDPOINT, FIXTURE_KEY);

        assertCredentialEquals("", store.getKey(Provider.OPENAI, "transcription", TRANSCRIPTION_ENDPOINT));
        assertCredentialEquals("", store.getKey(Provider.GROQ, "enhancement", TRANSCRIPTION_ENDPOINT));
        assertCredentialEquals("", store.getKey(Provider.GROQ, "transcription", otherEndpoint));
        assertCredentialEquals(FIXTURE_KEY,
                store.getKey(Provider.GROQ, "transcription", TRANSCRIPTION_ENDPOINT));
    }

    @Test public void movingCiphertextToAnotherIdentityFailsGcmAuthentication() throws Exception {
        store.putKey(Provider.GROQ, "transcription", TRANSCRIPTION_ENDPOINT, FIXTURE_KEY);
        File original = onlyCiphertextFile();
        String changedEndpoint = "https://api.example.invalid/other/audio/transcriptions";
        File reassigned = fileForIdentity(Provider.OPENAI, "enhancement", changedEndpoint);
        assertTrue(original.renameTo(reassigned));

        IOException error = expectIOException(() -> store.getKey(
                Provider.OPENAI, "enhancement", changedEndpoint));
        assertSafeStorageError(error);
        assertFalse(error.getMessage().contains(FIXTURE_KEY));
    }

    @Test public void corruptRecordReturnsOnlyTheSafeStorageError() throws Exception {
        store.putKey(Provider.GROQ, "transcription", TRANSCRIPTION_ENDPOINT, FIXTURE_KEY);
        try (FileOutputStream output = new FileOutputStream(onlyCiphertextFile(), false)) {
            output.write(new byte[]{99, 0, 0, 0, 0, 0});
        }

        IOException error = expectIOException(() -> store.getKey(
                Provider.GROQ, "transcription", TRANSCRIPTION_ENDPOINT));
        assertSafeStorageError(error);
        assertFalse(error.getMessage().contains(FIXTURE_KEY));
    }

    @Test public void unavailableKeySourceFailsSafelyOnSaveAndLoad() throws Exception {
        SecretStore unavailable = new SecretStore(storageContext, new FailingKeySource());
        IOException saveError = expectIOException(() -> unavailable.putKey(
                Provider.GROQ, "transcription", TRANSCRIPTION_ENDPOINT, FIXTURE_KEY));
        assertSafeStorageError(saveError);
        assertEquals(0, ciphertextFiles().length);

        store.putKey(Provider.GROQ, "transcription", TRANSCRIPTION_ENDPOINT, FIXTURE_KEY);
        SecretStore unavailableReader = new SecretStore(storageContext, new FailingKeySource());
        IOException loadError = expectIOException(() -> unavailableReader.getKey(
                Provider.GROQ, "transcription", TRANSCRIPTION_ENDPOINT));
        assertSafeStorageError(loadError);
        assertFalse(loadError.getMessage().contains(FIXTURE_KEY));
    }

    @Test public void noPlaintextCredentialAppearsInPreferencesOrStoredFiles() throws Exception {
        store.putKey(Provider.GROQ, "transcription", TRANSCRIPTION_ENDPOINT, FIXTURE_KEY);

        assertNoFileContains(noBackupDirectory);
        File preferencesDirectory = new File(appContext.getApplicationInfo().dataDir, "shared_prefs");
        if (preferencesDirectory.exists()) assertNoFileContains(preferencesDirectory);
        for (Object value : appContext.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
                .getAll().values()) {
            assertFalse("credential leaked into preferences", String.valueOf(value).contains(FIXTURE_KEY));
        }
    }

    @Test public void removingOneDestinationLeavesOtherCredentialsIntact() throws Exception {
        String otherEndpoint = "https://api.example.invalid/v2/audio/transcriptions";
        String otherKey = "fixture-secondary-secret-not-real";
        store.putKey(Provider.GROQ, "transcription", TRANSCRIPTION_ENDPOINT, FIXTURE_KEY);
        store.putKey(Provider.GROQ, "transcription", otherEndpoint, otherKey);

        store.removeKey(Provider.GROQ, "transcription", TRANSCRIPTION_ENDPOINT);

        assertCredentialEquals("", store.getKey(Provider.GROQ, "transcription", TRANSCRIPTION_ENDPOINT));
        assertCredentialEquals(otherKey, store.getKey(Provider.GROQ, "transcription", otherEndpoint));
        assertEquals(1, ciphertextFiles().length);
    }

    @Test public void concurrentWritesKeepCredentialsBoundToTheirOwnIdentities() throws Exception {
        int count = 8;
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> writes = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                final int index = i;
                writes.add(executor.submit(() -> {
                    start.await();
                    store.putKey(Provider.OPENROUTER, "stage-" + index,
                            "https://api.example.invalid/endpoint/" + index,
                            "fixture-concurrent-secret-" + index);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> write : writes) write.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(count, ciphertextFiles().length);
        for (int i = 0; i < count; i++) {
            assertCredentialEquals("fixture-concurrent-secret-" + i,
                    store.getKey(Provider.OPENROUTER, "stage-" + i,
                            "https://api.example.invalid/endpoint/" + i));
        }
    }

    private File onlyCiphertextFile() {
        File[] files = ciphertextFiles();
        assertEquals(1, files.length);
        return files[0];
    }

    private File[] ciphertextFiles() {
        File directory = new File(noBackupDirectory, "ai-secrets");
        File[] files = directory.listFiles((parent, name) -> name.endsWith(".bin"));
        return files == null ? new File[0] : files;
    }

    private File fileForIdentity(Provider provider, String stage, String endpoint) throws Exception {
        String identity = provider.name() + "\u0000" + stage + "\u0000" + endpoint.trim();
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return new File(new File(noBackupDirectory, "ai-secrets"), hex + ".bin");
    }

    private static SecretKey newAesKey() throws GeneralSecurityException {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(128);
        return generator.generateKey();
    }

    private static byte[] readFile(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private static void assertNoFileContains(File root) throws IOException {
        if (!root.exists()) return;
        if (root.isFile()) {
            assertFalse("credential leaked into a stored file",
                    contains(readFile(root), FIXTURE_KEY.getBytes(StandardCharsets.UTF_8)));
            return;
        }
        File[] children = root.listFiles();
        if (children == null) return;
        for (File child : children) assertNoFileContains(child);
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        if (needle.length == 0 || needle.length > haystack.length) return false;
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    private static void assertCredentialEquals(String expected, String actual) {
        assertTrue("credential value mismatch", expected.equals(actual));
    }

    private static IOException expectIOException(IoAction action) throws Exception {
        try {
            action.run();
            fail("expected secure storage operation to fail");
        } catch (IOException e) {
            return e;
        }
        throw new AssertionError("unreachable");
    }

    private static void assertSafeStorageError(IOException error) {
        assertNotNull(error.getMessage());
        assertEquals(SAFE_STORAGE_ERROR, error.getMessage());
    }

    private interface IoAction {
        void run() throws IOException;
    }

    private static final class FixedKeySource implements SecretStore.KeySource {
        private final SecretKey key;

        FixedKeySource(SecretKey key) {
            this.key = key;
        }

        @Override public SecretKey getOrCreateKey() {
            return key;
        }
    }

    private static final class FailingKeySource implements SecretStore.KeySource {
        @Override public SecretKey getOrCreateKey() throws GeneralSecurityException {
            throw new GeneralSecurityException("synthetic unavailable key source");
        }
    }

    private static final class StorageContext extends ContextWrapper {
        private final File noBackupDirectory;

        StorageContext(Context base, File noBackupDirectory) {
            super(base);
            this.noBackupDirectory = noBackupDirectory;
        }

        @Override public Context getApplicationContext() {
            return this;
        }

        @Override public File getNoBackupFilesDir() {
            return noBackupDirectory;
        }
    }
}
