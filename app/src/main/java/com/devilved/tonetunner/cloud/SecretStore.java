package com.devilved.tonetunner.cloud;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores API keys as AES-GCM ciphertext under noBackupFilesDir, with the AES key in Android Keystore. */
public final class SecretStore {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "com.devilved.tonetunner.cloud.api-keys.v1";
    private static final String FILE_DIRECTORY = "ai-secrets";
    private static final int FILE_VERSION = 1;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int MAX_CIPHERTEXT_BYTES = 64 * 1024;

    private final File directory;
    private final KeySource keySource;

    /** Package-private seam for tests; production always uses the Android Keystore implementation. */
    interface KeySource {
        SecretKey getOrCreateKey() throws GeneralSecurityException, IOException;
    }

    public SecretStore(Context context) {
        this(context, new AndroidKeystoreKeySource());
    }

    SecretStore(Context context, KeySource keySource) {
        if (keySource == null) throw new IllegalArgumentException("Key source is required.");
        Context appContext = context.getApplicationContext();
        directory = new File(appContext.getNoBackupFilesDir(), FILE_DIRECTORY);
        this.keySource = keySource;
    }

    /** Returns whether a credential is saved for precisely this provider, stage and endpoint. */
    public synchronized boolean hasKey(Provider provider, String stage, String endpoint) throws IOException {
        File file = fileFor(identity(provider, stage, endpoint));
        if (file.exists()) return true;
        File backup = new File(file.getPath() + ".bak");
        return backup.exists();
    }

    /** Returns an empty string if no credential is saved. */
    public synchronized String getKey(Provider provider, String stage, String endpoint) throws IOException {
        String identity = identity(provider, stage, endpoint);
        File file = fileFor(identity);
        AtomicFile atomicFile = new AtomicFile(file);
        if (!file.exists() && !new File(file.getPath() + ".bak").exists()) return "";

        try (FileInputStream input = atomicFile.openRead()) {
            byte[] packed = readBounded(input, MAX_CIPHERTEXT_BYTES + 64);
            try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(packed))) {
                int version = data.readUnsignedByte();
                int nonceLength = data.readUnsignedByte();
                int encryptedLength = data.readInt();
                if (version != FILE_VERSION || nonceLength != NONCE_BYTES || encryptedLength < 16
                        || encryptedLength > MAX_CIPHERTEXT_BYTES || encryptedLength != data.available() - nonceLength) {
                    throw storageFailure();
                }
                byte[] nonce = new byte[nonceLength];
                data.readFully(nonce);
                byte[] encrypted = new byte[encryptedLength];
                data.readFully(encrypted);

                byte[] clear = null;
                try {
                    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                    cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(TAG_BITS, nonce));
                    cipher.updateAAD(identity.getBytes(StandardCharsets.UTF_8));
                    clear = cipher.doFinal(encrypted);
                    return new String(clear, StandardCharsets.UTF_8);
                } catch (GeneralSecurityException | RuntimeException e) {
                    throw storageFailure();
                } finally {
                    wipe(nonce);
                    wipe(encrypted);
                    wipe(clear);
                    wipe(packed);
                }
            } catch (IOException e) {
                wipe(packed);
                if (isSafeStorageFailure(e)) throw e;
                throw storageFailure();
            }
        } catch (IOException e) {
            if (isSafeStorageFailure(e)) throw e;
            throw storageFailure();
        }
    }

    /** Stores a non-empty credential. Empty input intentionally leaves any saved key unchanged. */
    public synchronized void putKey(Provider provider, String stage, String endpoint, String apiKey) throws IOException {
        if (apiKey == null || apiKey.isEmpty()) return;
        String identity = identity(provider, stage, endpoint);
        byte[] clear = apiKey.getBytes(StandardCharsets.UTF_8);
        byte[] nonce = null;
        byte[] encrypted = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            nonce = cipher.getIV();
            cipher.updateAAD(identity.getBytes(StandardCharsets.UTF_8));
            encrypted = cipher.doFinal(clear);
            if (nonce == null || nonce.length != NONCE_BYTES || encrypted.length > MAX_CIPHERTEXT_BYTES) {
                throw storageFailure();
            }

            ensureDirectory();
            AtomicFile atomicFile = new AtomicFile(fileFor(identity));
            FileOutputStream output = null;
            try {
                output = atomicFile.startWrite();
                DataOutputStream data = new DataOutputStream(output);
                data.writeByte(FILE_VERSION);
                data.writeByte(nonce.length);
                data.writeInt(encrypted.length);
                data.write(nonce);
                data.write(encrypted);
                data.flush();
                atomicFile.finishWrite(output);
                output = null;
            } catch (IOException | RuntimeException e) {
                if (output != null) atomicFile.failWrite(output);
                throw storageFailure();
            }
        } catch (GeneralSecurityException | RuntimeException e) {
            throw storageFailure();
        } finally {
            wipe(clear);
            wipe(nonce);
            wipe(encrypted);
        }
    }

    /** Removes only the key associated with this exact provider, stage and endpoint. */
    public synchronized void removeKey(Provider provider, String stage, String endpoint) throws IOException {
        String identity = identity(provider, stage, endpoint);
        File file = fileFor(identity);
        AtomicFile atomicFile = new AtomicFile(file);
        try {
            atomicFile.delete();
            if (file.exists() || new File(file.getPath() + ".bak").exists()
                    || new File(file.getPath() + ".new").exists()) {
                throw storageFailure();
            }
        } catch (RuntimeException e) {
            if (isSafeStorageFailure(e)) throw new IOException("Secure credential storage could not be updated.");
            throw storageFailure();
        }
    }

    private SecretKey getOrCreateKey() throws GeneralSecurityException, IOException {
        try {
            return keySource.getOrCreateKey();
        } catch (GeneralSecurityException e) {
            throw e;
        } catch (IOException e) {
            throw storageFailure();
        } catch (RuntimeException e) {
            throw storageFailure();
        }
    }

    private static final class AndroidKeystoreKeySource implements KeySource {
        @Override
        public SecretKey getOrCreateKey() throws GeneralSecurityException, IOException {
            try {
                KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
                keyStore.load(null);
                if (keyStore.containsAlias(KEY_ALIAS)) {
                    java.security.Key key = keyStore.getKey(KEY_ALIAS, null);
                    if (key instanceof SecretKey) return (SecretKey) key;
                    throw storageFailure();
                }

                KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
                KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .setUserAuthenticationRequired(false)
                        .build();
                generator.init(spec);
                return generator.generateKey();
            } catch (GeneralSecurityException e) {
                throw e;
            } catch (IOException e) {
                throw storageFailure();
            } catch (Exception e) {
                throw storageFailure();
            }
        }
    }

    private void ensureDirectory() throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw storageFailure();
        }
    }

    private File fileFor(String identity) throws IOException {
        if (!directory.isDirectory() && !directory.exists()) ensureDirectory();
        return new File(directory, hex(sha256(identity.getBytes(StandardCharsets.UTF_8))) + ".bin");
    }

    private static String identity(Provider provider, String stage, String endpoint) throws IOException {
        if (provider == null || stage == null || stage.isEmpty() || endpoint == null) throw storageFailure();
        return provider.name() + "\u0000" + stage + "\u0000" + endpoint.trim();
    }

    private static byte[] readBounded(FileInputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > maxBytes) throw storageFailure();
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static byte[] sha256(byte[] data) throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw storageFailure();
        }
    }

    private static String hex(byte[] value) {
        StringBuilder builder = new StringBuilder(value.length * 2);
        for (byte b : value) builder.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return builder.toString();
    }

    private static void wipe(byte[] value) {
        if (value == null) return;
        for (int i = 0; i < value.length; i++) value[i] = 0;
    }

    private static IOException storageFailure() {
        return new IOException("Secure credential storage is unavailable. Try again or remove the saved key.");
    }

    private static boolean isSafeStorageFailure(Throwable error) {
        String message = error.getMessage();
        return message != null && (message.startsWith("Secure credential storage "));
    }
}
