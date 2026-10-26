package com.aurora.musicnext;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Worker-side SQL authority. A returned record is publishable only after the owning commit. */
final class NativePreferenceStorage {
    private static final int MAX_CHARACTERS = 4096;

    private NativePreferenceStorage() {}

    static void createTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE native_preferences(singleton INTEGER PRIMARY KEY CHECK(singleton=1),initialized INTEGER NOT NULL CHECK(initialized IN (0,1)),version INTEGER NOT NULL,revision INTEGER NOT NULL,restore_revision INTEGER NOT NULL,payload TEXT)");
        db.execSQL("INSERT INTO native_preferences(singleton,initialized,version,revision,restore_revision,payload) VALUES(1,0,1,0,0,NULL)");
    }

    static NativePreferenceRecord initialize(SQLiteDatabase db, Map<String, ?> playback,
        Map<String, ?> appearance, Map<String, ?> connection) {
        return initialize(db, () -> NativePreferenceRecord.initial(playback, appearance, connection));
    }

    /** Read legacy files only for the pending singleton, never for an initialized canonical row. */
    static NativePreferenceRecord initialize(SQLiteDatabase db, java.util.function.Supplier<NativePreferenceRecord> migration) {
        requireOwnTransaction(db);
        NativePreferenceRecord result;
        db.beginTransaction();
        try {
            result = load(db);
            if (result == null) {
                result = migration.get();
                ContentValues row = rowValues(result);
                row.put("initialized", 1);
                if (db.update("native_preferences", row, "singleton=1 AND initialized=0", null) != 1) throw unavailable();
                if (!(Boolean) result.values.get("rememberPlayback")) db.delete("playback_state", null, null);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        return result;
    }

    static NativePreferenceRecord read(SQLiteDatabase db) {
        NativePreferenceRecord result = load(db);
        if (result == null) throw unavailable();
        return result;
    }

    static NativePreferenceRecord update(SQLiteDatabase db, JSONObject patch) {
        requireOwnTransaction(db);
        NativePreferenceRecord after;
        db.beginTransaction();
        try {
            NativePreferenceRecord before = read(db);
            after = before.patch(patch);
            writeInTransaction(db, before, after);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        return after;
    }

    /** Composable with a future library import. Does not commit or publish an in-memory value. */
    static void writeInTransaction(SQLiteDatabase db, NativePreferenceRecord before, NativePreferenceRecord after) {
        if (!db.inTransaction()) throw new IllegalStateException("Preference write requires a transaction");
        if (before == null || after == null) throw new IllegalArgumentException("Invalid native preference change");
        NativePreferenceRecord actual = read(db);
        if (!sameRecord(actual, before)) throw new IllegalStateException("Native preferences changed");
        NativePreferenceRecord expected = before.patch(after.values.json());
        if (!sameRecord(expected, after)) throw new IllegalArgumentException("Invalid native preference change");
        if (sameRecord(before, after)) return;
        if (db.update("native_preferences", rowValues(after), "singleton=1 AND initialized=1 AND revision=? AND restore_revision=?",
            new String[]{Long.toString(before.revision), Long.toString(before.restoreRevision)}) != 1) throw unavailable();
        if (before.restoreRevision != after.restoreRevision) db.delete("playback_state", null, null);
    }

    /** Null means only the explicit pristine pending row, never a corrupt initialized record. */
    private static NativePreferenceRecord load(SQLiteDatabase db) {
        // CASE bounds the text copied into the CursorWindow, before Java allocates a String.
        try (Cursor row = db.rawQuery("SELECT initialized,version,revision,restore_revision,length(payload),CASE WHEN length(payload)<=4096 THEN payload ELSE NULL END FROM native_preferences WHERE singleton=1", null)) {
            if (!row.moveToFirst()) throw unavailable();
            for (int column = 0; column < 4; column++)
                if (row.getType(column) != Cursor.FIELD_TYPE_INTEGER) throw unavailable();
            long initialized = row.getLong(0), version = row.getLong(1), revision = row.getLong(2), restoreRevision = row.getLong(3);
            if (version != NativePreferenceRecord.VERSION || revision < 0 || restoreRevision < 0) throw unavailable();
            if (initialized == 0) {
                if (revision != 0 || restoreRevision != 0 || !row.isNull(4) || !row.isNull(5)) throw unavailable();
                return null;
            }
            if (initialized != 1 || row.isNull(4) || row.getLong(4) > MAX_CHARACTERS
                || row.getType(5) != Cursor.FIELD_TYPE_STRING) throw unavailable();
            try {
                JSONTokener parser = new JSONTokener(row.getString(5));
                Object value = parser.nextValue();
                if (!(value instanceof JSONObject) || parser.nextClean() != 0) throw unavailable();
                return NativePreferenceRecord.restore((int) version, revision, restoreRevision, (JSONObject) value);
            } catch (JSONException | IllegalArgumentException invalid) {
                // Parser messages can contain supplied text; never expose it as a startup error.
                throw unavailable();
            }
        }
    }

    private static ContentValues rowValues(NativePreferenceRecord record) {
        String payload = record.values.json().toString();
        if (payload == null || payload.length() > MAX_CHARACTERS) throw new IllegalArgumentException("Invalid native preference record");
        ContentValues row = new ContentValues();
        row.put("version", NativePreferenceRecord.VERSION); row.put("revision", record.revision);
        row.put("restore_revision", record.restoreRevision); row.put("payload", payload);
        return row;
    }

    private static boolean sameRecord(NativePreferenceRecord one, NativePreferenceRecord two) {
        return one.revision == two.revision && one.restoreRevision == two.restoreRevision && one.values.sameValues(two.values);
    }

    private static void requireOwnTransaction(SQLiteDatabase db) {
        if (db.inTransaction()) throw new IllegalStateException("Use the transaction preference writer");
    }

    private static IllegalStateException unavailable() { return new IllegalStateException("Native preferences unavailable"); }
}
