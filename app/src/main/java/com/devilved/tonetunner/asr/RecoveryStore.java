package com.devilved.tonetunner.asr;

import android.content.Context;
import android.util.AtomicFile;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.regex.Pattern;

/** Persistent storage for dictations that the user can retry or discard after processing fails. */
public final class RecoveryStore {
    private static final Object AUDIO_LOCK = new Object();
    private static final Set<String> OPEN_AUDIO = new HashSet<>();
    private static final Set<String> DELIVERY_AUDIO = new HashSet<>();
    private static final Object ORPHAN_IMPORT_LOCK = new Object();
    // AtomicFile readers can restore a backup. Serialize metadata readers and writers across
    // store instances so a UI list cannot roll back a worker's active checkpoint write.
    private static final Object METADATA_LOCK = new Object();
    private static final String DIRECTORY_NAME = "dictation-recovery";
    private static final String FALLBACK_DIRECTORY_NAME = "dictation-recovery-fallback";
    private static final String CAPTURE_DIRECTORY_NAME = "dictation-audio";
    private static final String AUDIO_NAME = "audio.pcm";
    private static final String METADATA_NAME = "entry.json";
    private static final String PENDING_PREFIX = ".pending-";
    private static final int MAX_METADATA_BYTES = 8 * 1024 * 1024;
    private static final Pattern ID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern ORPHAN_NAME_PATTERN = Pattern.compile(
            "recovery-orphan-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.pcm");

    private final File primaryRecoveryDirectory;
    private final File fallbackRecoveryDirectory;
    private final File captureDirectory;

    public RecoveryStore(Context context) {
        if (context == null) throw new IllegalArgumentException("Context is required.");
        File noBackup = context.getNoBackupFilesDir();
        primaryRecoveryDirectory = new File(noBackup, DIRECTORY_NAME);
        fallbackRecoveryDirectory = new File(noBackup, FALLBACK_DIRECTORY_NAME);
        captureDirectory = new File(noBackup, CAPTURE_DIRECTORY_NAME);
    }

    /** Saves a failed dictation or atomically advances an existing recovery checkpoint. */
    public Entry save(PcmRecording recording, long offset, String transcript,
                      String requestedLanguage, String detectedLanguage, boolean translate,
                      String existingId) throws IOException {
        return save(recording, offset, transcript, requestedLanguage, detectedLanguage,
                translate, existingId, false);
    }

    /** Saves a failed dictation or atomically advances an existing recovery checkpoint. */
    public synchronized Entry save(PcmRecording recording, long offset, String transcript,
                                   String requestedLanguage, String detectedLanguage,
                                   boolean translate, String existingId, boolean overlap)
            throws IOException {
        return saveLockedPolicy(recording, offset, transcript, requestedLanguage, detectedLanguage,
                translate, existingId, overlap, null);
    }

    /** Saves a checkpoint and may further restrict (never broaden) history persistence. */
    public synchronized Entry save(PcmRecording recording, long offset, String transcript,
                                   String requestedLanguage, String detectedLanguage,
                                   boolean translate, String existingId, boolean overlap,
                                   boolean historyEnabled) throws IOException {
        return saveLockedPolicy(recording, offset, transcript, requestedLanguage, detectedLanguage,
                translate, existingId, overlap, historyEnabled);
    }

    private Entry saveLockedPolicy(PcmRecording recording, long offset, String transcript,
                                   String requestedLanguage, String detectedLanguage,
                                   boolean translate, String existingId, boolean overlap,
                                   Boolean requestedHistoryEnabled) throws IOException {
        if (recording == null) throw new IllegalArgumentException("Recording is required.");
        // PcmRecording's methods lock the recording itself. Holding that monitor makes the length
        // check, the metadata snapshot and the rename one step that no close() can interleave.
        synchronized (recording) {
            return saveLocked(recording, offset, transcript, requestedLanguage, detectedLanguage,
                    translate, existingId, overlap, requestedHistoryEnabled);
        }
    }

    private Entry saveLocked(PcmRecording recording, long offset, String transcript,
                             String requestedLanguage, String detectedLanguage,
                             boolean translate, String existingId, boolean overlap,
                             Boolean requestedHistoryEnabled)
            throws IOException {
        if (offset < 0 || (offset & 1L) != 0 || offset > recording.lengthBytes()) {
            throw new IllegalArgumentException("Recovery offset must be an even byte position within the audio.");
        }
        if (existingId != null && !existingId.isEmpty()) {
            return updateExisting(recording, offset, transcript, requestedLanguage,
                    detectedLanguage, translate, existingId, overlap, requestedHistoryEnabled);
        }

        String id = UUID.randomUUID().toString();
        File root = chooseAvailableRoot();
        File pendingDirectory = new File(root, PENDING_PREFIX + id);
        if (!pendingDirectory.mkdir()) throw new IOException("Unable to create recovery entry.");
        File audioFile = childFile(pendingDirectory, AUDIO_NAME);
        Entry entry = new Entry(id, clean(transcript), clean(requestedLanguage),
                clean(detectedLanguage), translate, offset, overlap,
                recording.lengthBytes(), recording.getCaptureWarning(),
                Boolean.TRUE.equals(requestedHistoryEnabled));
        byte[] metadataBytes = null;
        IOException metadataError = null;
        try {
            metadataBytes = encodeMetadata(entry);
        } catch (IOException e) {
            metadataError = e;
        }

        try {
            recording.retainTo(audioFile);
        } catch (IOException | RuntimeException e) {
            pendingDirectory.delete();
            throw e;
        }
        if (metadataError != null) {
            // Audio must survive even when its transcript cannot be represented in bounded metadata.
            throw metadataError;
        }
        writeMetadataAtomically(pendingDirectory, metadataBytes);

        File stableDirectory = new File(root, id);
        if (!pendingDirectory.renameTo(stableDirectory)) {
            // The pending directory remains visible to list() and can still be retried.
            throw new IOException("Recovery audio was retained, but the entry could not be finalized.");
        }
        return entry;
    }

    /** Returns every intact or recoverable pending entry, newest saved capture first. */
    public synchronized List<Entry> list() throws IOException {
        Map<String, Entry> entries = new LinkedHashMap<>();
        importRecoveryOrphans();
        File[] roots = recoveryRoots();
        boolean sawStorage = false;
        boolean sawUsableRoot = false;
        IOException storageFailure = null;
        // Stable names take precedence if a duplicate pending directory was left by interruption.
        for (int pass = 0; pass < 2; pass++) {
            for (File root : roots) {
                if (!root.exists()) continue;
                sawStorage = true;
                if (!root.isDirectory()) {
                    if (storageFailure == null) storageFailure = new IOException("Recovery storage is unavailable.");
                    continue;
                }
                sawUsableRoot = true;
                File[] directories = root.listFiles();
                if (directories == null) {
                    if (storageFailure == null) storageFailure = new IOException("Unable to inspect recovery storage.");
                    continue;
                }
                for (File directory : directories) {
                    String id = directoryId(directory.getName(), pass == 1);
                    if (id == null || !directory.isDirectory() || !isContained(root, directory)) continue;
                    Entry entry = readEntry(directory, id);
                    if (entry == null) continue;
                    if (!entries.containsKey(id)) entries.put(id, entry);
                }
            }
        }

        if (entries.isEmpty() && sawStorage && !sawUsableRoot && storageFailure != null) {
            throw storageFailure;
        }
        if (entries.isEmpty() && storageFailure != null && !sawUsableRoot) throw storageFailure;

        List<Entry> result = new ArrayList<>(entries.values());
        result.sort(Comparator.comparingLong((Entry entry) -> entry.createdAtEpochMillis)
                .reversed().thenComparing(entry -> entry.id));
        return result;
    }

    /** Returns one retained entry, or {@code null} when its ID is not present. */
    public synchronized Entry get(String id) throws IOException {
        validateId(id);
        File directory = findDirectory(id);
        return directory == null ? null : readEntry(directory, id);
    }

    /** Opens retained PCM at the saved offset through a non-owning, read-only recording handle. */
    public synchronized PcmRecording open(Entry entry) throws IOException {
        if (entry == null) throw new IllegalArgumentException("Recovery entry is required.");
        validateId(entry.id);
        File directory = findDirectory(entry.id);
        if (directory == null) throw new IOException("Recovery entry is unavailable.");
        File audioFile = childFile(directory, AUDIO_NAME);
        if (!audioFile.isFile() || !isContained(directory, audioFile)) {
            throw new IOException("Retained recording audio is unavailable.");
        }
        long audioLength = audioFile.length();
        if (entry.offset < 0 || (entry.offset & 1L) != 0 || entry.offset > audioLength
                || entry.lengthBytes != audioLength) {
            throw new IOException("Retained recording position is invalid.");
        }
        Entry current = readEntry(directory, entry.id);
        if (current == null || !sameSnapshot(entry, current)) {
            throw new IOException("Recovery entry changed; refresh it before retrying.");
        }
        String path = audioFile.getCanonicalPath();
        synchronized (AUDIO_LOCK) {
            if (DELIVERY_AUDIO.contains(path)) {
                throw new IOException("This saved dictation is being delivered.");
            }
            if (!OPEN_AUDIO.add(path)) throw new IOException("This saved dictation is already being retried.");
        }
        try {
            PcmRecording opened = PcmRecording.openExisting(audioFile, entry.offset, entry.captureWarning);
            opened.afterClose(() -> { synchronized (AUDIO_LOCK) { OPEN_AUDIO.remove(path); } });
            return opened;
        } catch (IOException | RuntimeException e) {
            synchronized (AUDIO_LOCK) { OPEN_AUDIO.remove(path); }
            throw e;
        }
    }

    /** Deletes only the selected retained entry and its files. */
    public synchronized void delete(String id) throws IOException {
        validateId(id);
        synchronized (AUDIO_LOCK) {
            // Keep this lock through removal; open() reserves the same canonical path here.
            for (File root : recoveryRoots()) {
                if (!root.exists() || !root.isDirectory()) continue;
                File stable = new File(root, id);
                File pending = new File(root, PENDING_PREFIX + id);
                for (File directory : new File[] {stable, pending}) {
                    if (!directory.exists() || !isContained(root, directory)) continue;
                    File audio = childFile(directory, AUDIO_NAME);
                    String path = audio.getCanonicalPath();
                    if (OPEN_AUDIO.contains(path) || DELIVERY_AUDIO.contains(path)) {
                        throw new IOException("Stop retrying this dictation before discarding it.");
                    }
                }
            }
            for (File root : recoveryRoots()) {
                if (!root.exists() || !root.isDirectory()) continue;
                deleteEntryDirectory(root, new File(root, id));
                deleteEntryDirectory(root, new File(root, PENDING_PREFIX + id));
            }
        }
    }

    /** Permanently restrict an existing retry to omit text history, preserving all other metadata. */
    public synchronized Entry restrictHistoryPolicy(String id) throws IOException {
        validateId(id);
        File directory = findDirectory(id);
        if (directory == null) throw new IOException("Recovery entry is unavailable.");
        File audioFile = childFile(directory, AUDIO_NAME);
        if (!audioFile.isFile() || !isContained(directory, audioFile)) {
            throw new IOException("Retained recording audio is unavailable.");
        }
        synchronized (METADATA_LOCK) {
            Entry previous = readEntry(directory, id);
            if (previous == null) throw new IOException("Recovery entry metadata is unavailable.");
            if (!previous.historyEnabled) return previous;
            Entry restricted = new Entry(previous.id, previous.transcript, previous.requestedLanguage,
                    previous.detectedLanguage, previous.translate, previous.offset, previous.overlap,
                    previous.lengthBytes, previous.captureWarning, false, previous.createdAtEpochMillis);
            writeMetadataAtomically(directory, encodeMetadata(restricted));
            return restricted;
        }
    }

    /** Claims an entry for delivery after successful inference; a claim survives reader close. */
    public synchronized void prepareDelivery(Entry entry) throws IOException {
        if (entry == null) throw new IllegalArgumentException("Recovery entry is required.");
        validateId(entry.id);
        File directory = findDirectory(entry.id);
        if (directory == null) throw new IOException("Recovery entry is unavailable.");
        File audioFile = childFile(directory, AUDIO_NAME);
        if (!audioFile.isFile()) throw new IOException("Retained recording audio is unavailable.");
        String path = audioFile.getCanonicalPath();
        synchronized (AUDIO_LOCK) {
            if (!OPEN_AUDIO.contains(path)) {
                throw new IOException("Open the retained dictation before preparing delivery.");
            }
            if (!DELIVERY_AUDIO.add(path)) {
                throw new IOException("This saved dictation is already being delivered.");
            }
        }
    }

    /** Completes or releases a delivery claim; a failed delivery leaves the entry retryable. */
    public synchronized void finishDelivery(Entry entry, boolean delivered) throws IOException {
        if (entry == null) throw new IllegalArgumentException("Recovery entry is required.");
        validateId(entry.id);
        synchronized (AUDIO_LOCK) {
            File audioFile = findClaimedAudioFile(entry.id);
            if (audioFile == null) throw new IOException("Recovery entry has no active delivery claim.");
            String path = audioFile.getCanonicalPath();
            if (!DELIVERY_AUDIO.contains(path)) {
                throw new IOException("This dictation has no active delivery claim.");
            }
            if (!delivered) {
                DELIVERY_AUDIO.remove(path);
                return;
            }
            if (OPEN_AUDIO.contains(path)) {
                throw new IOException("Close the retained recording before completing delivery.");
            }
            try {
                File directory = audioFile.getParentFile();
                File root = containingRecoveryRoot(directory);
                if (root == null) throw new IOException("Recovery entry is outside private storage.");
                deleteEntryDirectory(root, directory);
            } finally {
                DELIVERY_AUDIO.remove(path);
            }
        }
    }

    private Entry updateExisting(PcmRecording recording, long offset, String transcript,
                                 String requestedLanguage, String detectedLanguage,
                                 boolean translate, String id, boolean overlap,
                                 Boolean requestedHistoryEnabled) throws IOException {
        validateId(id);
        File directory = findDirectory(id);
        if (directory == null) throw new IOException("Recovery entry is unavailable.");
        File audioFile = childFile(directory, AUDIO_NAME);
        if (!audioFile.isFile() || !isContained(directory, audioFile)) {
            throw new IOException("Retained recording audio is unavailable.");
        }
        long length = audioFile.length();
        if ((length & 1L) != 0 || offset > length) {
            throw new IllegalArgumentException("Recovery offset must be within the retained audio.");
        }
        String captureWarning = recording.getCaptureWarning();
        synchronized (METADATA_LOCK) {
            Entry previous = readEntry(directory, id);
            boolean historyEnabled = previous != null && previous.historyEnabled
                    && (requestedHistoryEnabled == null || requestedHistoryEnabled);
            Entry updated = new Entry(id, clean(transcript), clean(requestedLanguage),
                    clean(detectedLanguage), translate, offset, overlap, length,
                    captureWarning, historyEnabled, previous == null
                            ? Math.max(0L, audioFile.lastModified()) : previous.createdAtEpochMillis);
            writeMetadataAtomically(directory, encodeMetadata(updated));
            return updated;
        }
    }

    private Entry readEntry(File directory, String id) throws IOException {
        File audioFile = childFile(directory, AUDIO_NAME);
        if (!audioFile.isFile() || !isContained(directory, audioFile)) return null;
        long length = audioFile.length();
        if ((length & 1L) != 0) return null;

        File metadataFile = childFile(directory, METADATA_NAME);
        File backupFile = childFile(directory, METADATA_NAME + ".bak");
        if ((!metadataFile.isFile() && !backupFile.isFile()) || !isContained(directory, metadataFile)) {
            return fallbackEntry(id, length, audioFile.lastModified());
        }

        try {
            byte[] metadataBytes = readBounded(metadataFile);
            JSONObject metadata = new JSONObject(new String(metadataBytes, StandardCharsets.UTF_8));
            if (!id.equals(metadata.optString("id", ""))) return fallbackEntry(id, length, audioFile.lastModified());

            Object rawOffset = metadata.opt("offset");
            if (!(rawOffset instanceof Number)) return fallbackEntry(id, length, audioFile.lastModified());
            Number numericOffset = (Number) rawOffset;
            long offset = numericOffset.longValue();
            double preciseOffset = numericOffset.doubleValue();
            if (preciseOffset != (double) offset || offset < 0 || (offset & 1L) != 0
                    || offset > length) {
                return fallbackEntry(id, length, audioFile.lastModified());
            }
            String warning = metadata.isNull("captureWarning")
                    ? null : metadata.optString("captureWarning", null);
            Object rawHistoryEnabled = metadata.opt("historyEnabled");
            boolean historyEnabled = rawHistoryEnabled instanceof Boolean && (Boolean) rawHistoryEnabled;
            return new Entry(id, clean(metadata.optString("transcript", "")),
                    clean(metadata.optString("requestedLanguage", "auto")),
                    clean(metadata.optString("detectedLanguage", "")),
                    metadata.optBoolean("translate", false),
                    offset,
                    metadata.optBoolean("overlap", false),
                    length, warning, historyEnabled, Math.max(0L,
                            metadata.optLong("createdAtEpochMillis", audioFile.lastModified())));
        } catch (JSONException | IOException | RuntimeException e) {
            // Keep the audio discoverable even when its metadata was interrupted or damaged.
            return fallbackEntry(id, length, audioFile.lastModified());
        }
    }

    private Entry fallbackEntry(String id, long length, long createdAt) {
        return new Entry(id, "", "auto", "", false, 0L, false, length, null,
                false, Math.max(0L, createdAt));
    }

    private File findDirectory(String id) throws IOException {
        IOException failure = null;
        for (int pass = 0; pass < 2; pass++) {
            for (File root : recoveryRoots()) {
                if (!root.exists()) continue;
                if (!root.isDirectory()) {
                    if (failure == null) failure = new IOException("Recovery storage is unavailable.");
                    continue;
                }
                File candidate = new File(root, pass == 0 ? id : PENDING_PREFIX + id);
                if (candidate.isDirectory() && isContained(root, candidate)) return candidate;
            }
        }
        if (failure != null) {
            boolean anyUsableRoot = false;
            for (File root : recoveryRoots()) anyUsableRoot |= root.isDirectory();
            if (!anyUsableRoot) throw failure;
        }
        return null;
    }

    private File findClaimedAudioFile(String id) throws IOException {
        for (int pass = 0; pass < 2; pass++) {
            for (File root : recoveryRoots()) {
                File directory = new File(root, pass == 0 ? id : PENDING_PREFIX + id);
                if (!isContained(root, directory)) continue;
                File audio = childFile(directory, AUDIO_NAME);
                if (isContained(directory, audio) && DELIVERY_AUDIO.contains(audio.getCanonicalPath())) {
                    return audio;
                }
            }
        }
        return null;
    }

    private File[] recoveryRoots() {
        return new File[] {primaryRecoveryDirectory, fallbackRecoveryDirectory};
    }

    private File chooseAvailableRoot() throws IOException {
        IOException failure = null;
        for (File root : recoveryRoots()) {
            try {
                ensureRecoveryDirectory(root);
                File probe = File.createTempFile(".recovery-write-", ".tmp", root);
                if (!probe.delete()) throw new IOException("Unable to verify recovery storage.");
                return root;
            } catch (IOException e) {
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
            }
        }
        throw failure == null ? new IOException("Unable to create recovery storage.") : failure;
    }

    private static void ensureRecoveryDirectory(File recoveryDirectory) throws IOException {
        if (recoveryDirectory.exists()) {
            if (!recoveryDirectory.isDirectory()) throw new IOException("Recovery storage is unavailable.");
            return;
        }
        if (!recoveryDirectory.mkdirs() && !recoveryDirectory.isDirectory()) {
            throw new IOException("Unable to create recovery storage.");
        }
    }

    private File containingRecoveryRoot(File directory) throws IOException {
        for (File root : recoveryRoots()) {
            if (root.isDirectory() && isContained(root, directory)) return root;
        }
        return null;
    }

    private void importRecoveryOrphans() throws IOException {
        synchronized (ORPHAN_IMPORT_LOCK) {
            if (!captureDirectory.exists()) return;
            if (!captureDirectory.isDirectory()) {
                throw new IOException("Unable to inspect private recording storage.");
            }
            File[] candidates = captureDirectory.listFiles();
            if (candidates == null) throw new IOException("Unable to inspect private recording storage.");
            for (File orphan : candidates) {
                String name = orphan.getName();
                if (!ORPHAN_NAME_PATTERN.matcher(name).matches() || !orphan.isFile()
                        || !isContained(captureDirectory, orphan)) continue;
                importOrphan(orphan, name.substring("recovery-orphan-".length(), name.length() - ".pcm".length()));
            }
        }
    }

    private void importOrphan(File orphan, String id) throws IOException {
        validateId(id);
        File root = chooseAvailableRoot();
        File pending = new File(root, PENDING_PREFIX + id);
        File stable = new File(root, id);
        if (pending.exists() || stable.exists()) return;
        if (!pending.mkdir()) return;
        File audio = childFile(pending, AUDIO_NAME);
        if (!orphan.renameTo(audio)) {
            pending.delete();
            return;
        }

        Entry imported = fallbackEntry(id, audio.length(), audio.lastModified());
        try {
            writeMetadataAtomically(pending, encodeMetadata(imported));
        } catch (IOException e) {
            // The pending audio remains readable through the offset-zero fallback metadata.
            return;
        }
        if (!pending.renameTo(stable)) {
            // Keep the pending entry visible; the next list() can still recover its audio.
        }
    }

    private byte[] encodeMetadata(Entry entry) throws IOException {
        JSONObject metadata = new JSONObject();
        try {
            metadata.put("id", entry.id);
            metadata.put("createdAtEpochMillis", entry.createdAtEpochMillis);
            metadata.put("transcript", entry.transcript);
            metadata.put("requestedLanguage", entry.requestedLanguage);
            metadata.put("detectedLanguage", entry.detectedLanguage);
            metadata.put("translate", entry.translate);
            metadata.put("offset", entry.offset);
            metadata.put("overlap", entry.overlap);
            metadata.put("captureWarning", entry.captureWarning == null
                    ? JSONObject.NULL : entry.captureWarning);
            metadata.put("historyEnabled", entry.historyEnabled);
        } catch (JSONException e) {
            throw new IOException("Unable to encode recovery metadata.");
        }

        byte[] bytes = metadata.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_METADATA_BYTES) {
            throw new IOException("Recovery metadata is too large to store safely.");
        }
        return bytes;
    }

    private void writeMetadataAtomically(File directory, byte[] bytes) throws IOException {
        synchronized (METADATA_LOCK) {
            File destination = childFile(directory, METADATA_NAME);
            childFile(directory, METADATA_NAME + ".bak");
            childFile(directory, METADATA_NAME + ".new");
            AtomicFile atomic = new AtomicFile(destination);
            FileOutputStream output = null;
            try {
                output = atomic.startWrite();
                output.write(bytes);
                output.flush();
                output.getFD().sync();
                atomic.finishWrite(output);
                output = null;
                // Some framework versions log commit failures rather than throwing. Never claim
                // a new checkpoint until a bounded read returns the bytes that were committed.
                if (!Arrays.equals(bytes, readBounded(destination))) {
                    throw new IOException("Recovery metadata could not be committed.");
                }
            } catch (IOException | RuntimeException e) {
                if (output != null) atomic.failWrite(output);
                throw new IOException("Recovery metadata could not be saved safely.");
            }
        }
    }

    private static boolean sameSnapshot(Entry first, Entry second) {
        return first.id.equals(second.id)
                && first.transcript.equals(second.transcript)
                && first.requestedLanguage.equals(second.requestedLanguage)
                && first.detectedLanguage.equals(second.detectedLanguage)
                && first.translate == second.translate
                && first.offset == second.offset
                && first.overlap == second.overlap
                && first.historyEnabled == second.historyEnabled
                && first.lengthBytes == second.lengthBytes
                && first.createdAtEpochMillis == second.createdAtEpochMillis
                && (first.captureWarning == null
                    ? second.captureWarning == null
                    : first.captureWarning.equals(second.captureWarning));
    }

    private void deleteEntryDirectory(File root, File directory) throws IOException {
        if (!directory.exists()) return;
        if (!isContained(root, directory)) {
            throw new IOException("Recovery entry is outside private storage.");
        }
        deleteRecursively(root, directory);
    }

    private void deleteRecursively(File root, File file) throws IOException {
        if (!isContained(root, file)) {
            throw new IOException("Recovery entry is outside private storage.");
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Unable to inspect recovery entry.");
            for (File child : children) deleteRecursively(root, child);
        }
        if (!file.delete() && file.exists()) throw new IOException("Unable to discard recovery entry.");
    }

    private File childFile(File directory, String name) throws IOException {
        File child = new File(directory, name);
        if (!isContained(directory, child)) throw new IOException("Recovery path is invalid.");
        return child;
    }

    private static boolean isContained(File parent, File candidate) throws IOException {
        String parentPath = parent.getCanonicalPath();
        String candidatePath = candidate.getCanonicalPath();
        return candidatePath.startsWith(parentPath + File.separator);
    }

    private static byte[] readBounded(File file) throws IOException {
        synchronized (METADATA_LOCK) {
            try (FileInputStream input = new AtomicFile(file).openRead();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (output.size() > MAX_METADATA_BYTES - read) {
                        throw new IOException("Recovery metadata is too large.");
                    }
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            }
        }
    }

    private static String directoryId(String name, boolean pending) {
        String id = name;
        if (pending) {
            if (!name.startsWith(PENDING_PREFIX)) return null;
            id = name.substring(PENDING_PREFIX.length());
        } else if (name.startsWith(PENDING_PREFIX)) {
            return null;
        }
        return ID_PATTERN.matcher(id).matches() ? id : null;
    }

    private static void validateId(String id) {
        if (id == null || !ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("Recovery ID is invalid.");
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value;
    }

    /** Immutable metadata exposed to the retry and discard UI. */
    public static final class Entry {
        public final String id;
        public final String transcript;
        public final String requestedLanguage;
        public final String detectedLanguage;
        public final boolean translate;
        public final long offset;
        public final boolean overlap;
        public final long lengthBytes;
        public final String captureWarning;
        /** Whether the originating operation permits permanent transcript history. */
        public final boolean historyEnabled;
        public final long createdAtEpochMillis;

        private Entry(String id, String transcript, String requestedLanguage,
                      String detectedLanguage, boolean translate, long offset,
                      boolean overlap, long lengthBytes, String captureWarning, boolean historyEnabled) {
            this(id, transcript, requestedLanguage, detectedLanguage, translate, offset,
                    overlap, lengthBytes, captureWarning, historyEnabled, System.currentTimeMillis());
        }

        private Entry(String id, String transcript, String requestedLanguage,
                      String detectedLanguage, boolean translate, long offset,
                      boolean overlap, long lengthBytes, String captureWarning, boolean historyEnabled,
                      long createdAtEpochMillis) {
            this.id = id;
            this.transcript = transcript;
            this.requestedLanguage = requestedLanguage;
            this.detectedLanguage = detectedLanguage;
            this.translate = translate;
            this.offset = offset;
            this.overlap = overlap;
            this.lengthBytes = lengthBytes;
            this.captureWarning = captureWarning;
            this.historyEnabled = historyEnabled;
            this.createdAtEpochMillis = createdAtEpochMillis;
        }
    }
}
