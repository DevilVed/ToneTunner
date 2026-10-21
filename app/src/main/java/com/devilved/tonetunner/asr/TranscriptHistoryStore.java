package com.devilved.tonetunner.asr;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Durable text-only transcript history, independent from the audio recovery store. */
public final class TranscriptHistoryStore implements AutoCloseable {
    private static final String DIRECTORY_NAME = "transcript-history";
    private static final String DATABASE_NAME = "history.db";
    private static final int DATABASE_VERSION = 1;
    private static final String TABLE_TRANSCRIPTS = "transcripts";
    private static final String TABLE_CHUNKS = "transcript_chunks";
    private static final String COL_ID = "id";
    private static final String COL_CREATED_AT = "created_at";
    private static final String COL_UPDATED_AT = "updated_at";
    private static final String COL_LANGUAGE = "language";
    private static final String COL_WARNING = "warning";
    private static final String COL_HAS_RESULT = "has_result";
    private static final String COL_HAS_DIFFERENT_RAW = "has_different_raw";
    private static final String COL_RAW_PREVIEW = "raw_preview";
    private static final String COL_TEXT_PREVIEW = "text_preview";
    private static final String COL_RAW_LENGTH = "raw_length";
    private static final String COL_TEXT_LENGTH = "text_length";
    private static final String COL_KIND = "kind";
    private static final String COL_SEQUENCE = "sequence";
    private static final String COL_CONTENT = "content";
    private static final int KIND_RAW = 0;
    private static final int KIND_RESULT = 1;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_WARNING_CODE_POINTS = 512;
    private static final int MAX_LANGUAGE_CODE_POINTS = 64;
    private static final int MAX_ID_CODE_POINTS = 256;
    private static final int CHUNK_MAX_UTF16_UNITS = 16_384;
    private static final Object DATABASE_LOCK = new Object();

    private final Context applicationContext;

    /** Small metadata returned by {@link #list(int, int)}; transcript bodies are never queried here. */
    public static final class Summary {
        public static final int PREVIEW_CODE_POINT_LIMIT = 160;

        public final String id;
        public final String preview;
        public final String language;
        public final String warning;
        public final long createdAt;
        public final long updatedAt;
        public final boolean hasDifferentRaw;
        public final boolean isComplete;

        private Summary(String id, String preview, String language, String warning,
                        long createdAt, long updatedAt, boolean hasDifferentRaw, boolean isComplete) {
            this.id = id;
            this.preview = preview;
            this.language = language;
            this.warning = warning;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.hasDifferentRaw = hasDifferentRaw;
            this.isComplete = isComplete;
        }
    }

    /** Full text is loaded only for a selected history entry. */
    public static final class Entry {
        public final String id;
        public final String rawText;
        public final String text;
        public final String language;
        public final String warning;
        public final long createdAt;
        public final long updatedAt;
        public final boolean isComplete;

        private Entry(String id, String rawText, String text, String language, String warning,
                      long createdAt, long updatedAt, boolean isComplete) {
            this.id = id;
            this.rawText = rawText;
            this.text = text;
            this.language = language;
            this.warning = warning;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.isComplete = isComplete;
        }
    }

    public TranscriptHistoryStore(Context context) {
        if (context == null) throw new IllegalArgumentException("context must not be null");
        Context appContext = context.getApplicationContext();
        applicationContext = appContext == null ? context : appContext;
    }

    /** Store the latest recognized progress before enhancement or delivery. */
    public void saveRaw(String id, String rawText, String language) throws IOException {
        if (!hasVisibleText(rawText)) return;
        requireId(id);
        final String raw = rawText;
        final String resolvedLanguage = normalizeLanguage(language);
        withDatabase(db -> {
            db.beginTransaction();
            try {
                Metadata previous = metadata(db, id);
                if (previous != null && previous.isComplete) {
                    // A same-id retry may be canceled after this checkpoint. Keep the last
                    // delivered result and its warning until a new saveResult commits atomically.
                    db.setTransactionSuccessful();
                    return null;
                }
                long now = System.currentTimeMillis();
                long createdAt = previous == null ? now : previous.createdAt;
                long updatedAt = previous == null ? now : Math.max(now, previous.updatedAt + 1);
                writeMetadata(db, id, createdAt, updatedAt, resolvedLanguage, "", false,
                        false, preview(raw), preview(raw), raw.length(), raw.length());
                replaceTextChunks(db, id, raw, null);
                db.setTransactionSuccessful();
                return null;
            } finally {
                db.endTransaction();
            }
        });
    }

    /** Persist the baseline and final/fallback text atomically before delivery is scheduled. */
    public void saveResult(String id, String rawText, String text, String language, String warning)
            throws IOException {
        if (!hasVisibleText(rawText)) return;
        requireId(id);
        String raw = rawText;
        String result = hasVisibleText(text) ? text : raw;
        String safeWarning = normalizeWarning(warning);
        String resolvedLanguage = normalizeLanguage(language);
        withDatabase(db -> {
            db.beginTransaction();
            try {
                Metadata previous = metadata(db, id);
                long now = System.currentTimeMillis();
                long createdAt = previous == null ? now : previous.createdAt;
                long updatedAt = previous == null ? now : Math.max(now, previous.updatedAt + 1);
                writeMetadata(db, id, createdAt, updatedAt, resolvedLanguage, safeWarning, true,
                        !raw.equals(result), preview(raw), preview(result), raw.length(), result.length());
                replaceTextChunks(db, id, raw, result);
                db.setTransactionSuccessful();
                return null;
            } finally {
                db.endTransaction();
            }
        });
    }

    /** Return a full entry, or {@code null} when the id is absent. */
    public Entry get(String id) throws IOException {
        if (id == null || id.isEmpty()) return null;
        return withDatabase(db -> {
            Metadata metadata = metadata(db, id);
            if (metadata == null) return null;
            String raw = readTextChunks(db, id, KIND_RAW);
            if (raw.length() != metadata.rawLength) {
                throw new IOException("Transcript history text could not be read completely.");
            }
            String result = metadata.isComplete ? readTextChunks(db, id, KIND_RESULT) : raw;
            if (metadata.isComplete && result.length() != metadata.textLength) {
                throw new IOException("Transcript history text could not be read completely.");
            }
            return new Entry(id, raw, result, metadata.language, metadata.warning,
                    metadata.createdAt, metadata.updatedAt, metadata.isComplete);
        });
    }

    /** Return newest-first, bounded-preview metadata only. Use {@link #get(String)} for full text. */
    public List<Summary> list(int limit, int offset) throws IOException {
        if (limit <= 0 || offset < 0) throw new IllegalArgumentException("limit must be positive and offset nonnegative");
        int safeLimit = Math.min(limit, MAX_PAGE_SIZE);
        return withDatabase(db -> {
            List<Summary> entries = new ArrayList<>();
            String[] columns = {COL_ID, COL_TEXT_PREVIEW, COL_LANGUAGE, COL_WARNING,
                    COL_CREATED_AT, COL_UPDATED_AT, COL_HAS_DIFFERENT_RAW, COL_HAS_RESULT};
            String orderBy = COL_CREATED_AT + " DESC, " + COL_ID + " DESC";
            try (Cursor cursor = db.query(TABLE_TRANSCRIPTS, columns, null, null, null, null,
                    orderBy, offset + "," + safeLimit)) {
                while (cursor.moveToNext()) {
                    entries.add(new Summary(
                            cursor.getString(0),
                            cursor.getString(1),
                            cursor.getString(2),
                            cursor.getString(3),
                            cursor.getLong(4),
                            cursor.getLong(5),
                            cursor.getInt(6) != 0,
                            cursor.getInt(7) != 0));
                }
            }
            return entries.isEmpty() ? Collections.emptyList() : entries;
        });
    }

    /** Delete only text-history rows and their text chunks; audio recovery is a separate store. */
    public void delete(String id) throws IOException {
        if (id == null || id.isEmpty()) return;
        withDatabase(db -> {
            db.beginTransaction();
            try {
                db.delete(TABLE_CHUNKS, COL_ID + "=?", new String[]{id});
                db.delete(TABLE_TRANSCRIPTS, COL_ID + "=?", new String[]{id});
                db.setTransactionSuccessful();
                return null;
            } finally {
                db.endTransaction();
            }
        });
    }

    /** Each operation opens and closes its own helper, so there are no persistent handles to release. */
    @Override public void close() { }

    private <T> T withDatabase(DatabaseWork<T> work) throws IOException {
        synchronized (DATABASE_LOCK) {
            SQLiteOpenHelper helper = null;
            T result = null;
            IOException failure = null;
            try {
                File directory = new File(applicationContext.getNoBackupFilesDir(), DIRECTORY_NAME);
                if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
                    throw new IOException("Transcript history storage is unavailable.");
                }
                File databaseFile = new File(directory, DATABASE_NAME);
                helper = new SQLiteOpenHelper(applicationContext, databaseFile.getAbsolutePath(), null,
                        DATABASE_VERSION, db -> {
                    throw new SQLiteException("Transcript history database is corrupt.");
                }) {
                    @Override public void onConfigure(SQLiteDatabase db) {
                        db.setForeignKeyConstraintsEnabled(true);
                    }

                    @Override public void onCreate(SQLiteDatabase db) {
                        db.execSQL("CREATE TABLE " + TABLE_TRANSCRIPTS + " ("
                                + COL_ID + " TEXT NOT NULL PRIMARY KEY, "
                                + COL_CREATED_AT + " INTEGER NOT NULL, "
                                + COL_UPDATED_AT + " INTEGER NOT NULL, "
                                + COL_LANGUAGE + " TEXT NOT NULL, "
                                + COL_WARNING + " TEXT NOT NULL, "
                                + COL_HAS_RESULT + " INTEGER NOT NULL, "
                                + COL_HAS_DIFFERENT_RAW + " INTEGER NOT NULL, "
                                + COL_RAW_PREVIEW + " TEXT NOT NULL, "
                                + COL_TEXT_PREVIEW + " TEXT NOT NULL, "
                                + COL_RAW_LENGTH + " INTEGER NOT NULL, "
                                + COL_TEXT_LENGTH + " INTEGER NOT NULL)");
                        db.execSQL("CREATE TABLE " + TABLE_CHUNKS + " ("
                                + COL_ID + " TEXT NOT NULL REFERENCES " + TABLE_TRANSCRIPTS
                                + " (" + COL_ID + ") ON DELETE CASCADE, "
                                + COL_KIND + " INTEGER NOT NULL, "
                                + COL_SEQUENCE + " INTEGER NOT NULL, "
                                + COL_CONTENT + " TEXT NOT NULL, "
                                + "PRIMARY KEY (" + COL_ID + ", " + COL_KIND + ", "
                                + COL_SEQUENCE + "))");
                        db.execSQL("CREATE INDEX transcripts_created_id ON " + TABLE_TRANSCRIPTS
                                + " (" + COL_CREATED_AT + " DESC, " + COL_ID + " DESC)");
                    }

                    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
                        throw new SQLiteException("Transcript history schema migration is unavailable.");
                    }

                    @Override public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
                        throw new SQLiteException("Transcript history schema downgrade is unavailable.");
                    }

                };
                result = work.run(helper.getWritableDatabase());
            } catch (IOException e) {
                failure = e;
            } catch (SQLiteException | SecurityException | IllegalArgumentException e) {
                failure = new IOException("Transcript history storage is unavailable.", e);
            } finally {
                if (helper != null) {
                    try {
                        helper.close();
                    } catch (SQLiteException | SecurityException | IllegalArgumentException e) {
                        if (failure == null) {
                            failure = new IOException("Transcript history storage is unavailable.", e);
                        }
                    }
                }
            }
            if (failure != null) throw failure;
            return result;
        }
    }

    private static void writeMetadata(SQLiteDatabase db, String id, long createdAt, long updatedAt,
                                      String language, String warning, boolean hasResult,
                                      boolean hasDifferentRaw, String rawPreview, String textPreview,
                                      int rawLength, int textLength) {
        ContentValues values = new ContentValues();
        values.put(COL_ID, id);
        values.put(COL_CREATED_AT, createdAt);
        values.put(COL_UPDATED_AT, updatedAt);
        values.put(COL_LANGUAGE, language);
        values.put(COL_WARNING, warning);
        values.put(COL_HAS_RESULT, hasResult ? 1 : 0);
        values.put(COL_HAS_DIFFERENT_RAW, hasDifferentRaw ? 1 : 0);
        values.put(COL_RAW_PREVIEW, rawPreview);
        values.put(COL_TEXT_PREVIEW, textPreview);
        values.put(COL_RAW_LENGTH, rawLength);
        values.put(COL_TEXT_LENGTH, textLength);
        if (db.insertWithOnConflict(TABLE_TRANSCRIPTS, null, values,
                SQLiteDatabase.CONFLICT_REPLACE) == -1L) {
            throw new SQLiteException("Transcript history metadata could not be saved.");
        }
    }

    private static Metadata metadata(SQLiteDatabase db, String id) {
        String[] columns = {COL_CREATED_AT, COL_UPDATED_AT, COL_LANGUAGE, COL_WARNING,
                COL_HAS_RESULT, COL_HAS_DIFFERENT_RAW, COL_RAW_LENGTH, COL_TEXT_LENGTH};
        try (Cursor cursor = db.query(TABLE_TRANSCRIPTS, columns, COL_ID + "=?",
                new String[]{id}, null, null, null)) {
            if (!cursor.moveToFirst()) return null;
            return new Metadata(cursor.getLong(0), cursor.getLong(1), cursor.getString(2),
                    cursor.getString(3), cursor.getInt(4) != 0, cursor.getInt(5) != 0,
                    cursor.getInt(6), cursor.getInt(7));
        }
    }

    private static void replaceTextChunks(SQLiteDatabase db, String id, String raw, String result) {
        db.delete(TABLE_CHUNKS, COL_ID + "=?", new String[]{id});
        insertChunks(db, id, KIND_RAW, raw);
        if (result != null) insertChunks(db, id, KIND_RESULT, result);
    }

    private static void insertChunks(SQLiteDatabase db, String id, int kind, String text) {
        int sequence = 0;
        int index = 0;
        while (index < text.length()) {
            int start = index;
            int chunkLength = 0;
            while (index < text.length()) {
                int codePoint = text.codePointAt(index);
                int charCount = Character.charCount(codePoint);
                if (chunkLength > 0 && chunkLength + charCount > CHUNK_MAX_UTF16_UNITS) break;
                chunkLength += charCount;
                index += charCount;
            }
            ContentValues values = new ContentValues();
            values.put(COL_ID, id);
            values.put(COL_KIND, kind);
            values.put(COL_SEQUENCE, sequence++);
            values.put(COL_CONTENT, text.substring(start, index));
            db.insertOrThrow(TABLE_CHUNKS, null, values);
        }
    }

    private static String readTextChunks(SQLiteDatabase db, String id, int kind) {
        StringBuilder text = new StringBuilder();
        try (Cursor cursor = db.query(TABLE_CHUNKS, new String[]{COL_CONTENT},
                COL_ID + "=? AND " + COL_KIND + "=?", new String[]{id, String.valueOf(kind)},
                null, null, COL_SEQUENCE + " ASC")) {
            while (cursor.moveToNext()) text.append(cursor.getString(0));
        }
        return text.toString();
    }

    private static String preview(String text) {
        int codePoints = text.codePointCount(0, text.length());
        if (codePoints <= Summary.PREVIEW_CODE_POINT_LIMIT) return text;
        int end = text.offsetByCodePoints(0, Summary.PREVIEW_CODE_POINT_LIMIT - 1);
        return text.substring(0, end) + "…";
    }

    private static String normalizeLanguage(String language) throws IOException {
        String value = nonNull(language).trim();
        if (value.codePointCount(0, value.length()) > MAX_LANGUAGE_CODE_POINTS) {
            throw new IOException("Transcript history language is too long to store safely.");
        }
        return value;
    }

    private static String normalizeWarning(String warning) throws IOException {
        String value = nonNull(warning).trim();
        if (value.codePointCount(0, value.length()) > MAX_WARNING_CODE_POINTS) {
            throw new IOException("Transcript history warning is too long to store safely.");
        }
        return value;
    }

    private static boolean hasVisibleText(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int index = 0; index < text.length();) {
            int codePoint = text.codePointAt(index);
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) return true;
            index += Character.charCount(codePoint);
        }
        return false;
    }

    private static String nonNull(String value) { return value == null ? "" : value; }

    private static void requireId(String id) throws IOException {
        if (id == null || id.trim().isEmpty()) throw new IOException("Transcript history id is missing.");
        if (id.codePointCount(0, id.length()) > MAX_ID_CODE_POINTS) {
            throw new IOException("Transcript history id is too long to store safely.");
        }
    }

    private interface DatabaseWork<T> {
        T run(SQLiteDatabase db) throws IOException;
    }

    private static final class Metadata {
        final long createdAt;
        final long updatedAt;
        final String language;
        final String warning;
        final boolean isComplete;
        @SuppressWarnings("unused") final boolean hasDifferentRaw;
        final int rawLength;
        final int textLength;

        Metadata(long createdAt, long updatedAt, String language, String warning,
                 boolean isComplete, boolean hasDifferentRaw, int rawLength, int textLength) {
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.language = language;
            this.warning = warning;
            this.isComplete = isComplete;
            this.hasDifferentRaw = hasDifferentRaw;
            this.rawLength = rawLength;
            this.textLength = textLength;
        }
    }
}
