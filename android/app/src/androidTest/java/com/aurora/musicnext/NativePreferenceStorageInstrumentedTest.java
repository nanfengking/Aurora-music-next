package com.aurora.musicnext;

import static org.junit.Assert.*;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Map;
import org.json.JSONObject;

/** Real Android SQLite only; generated private DB names, never the user's library/settings. */
@RunWith(AndroidJUnit4.class)
public final class NativePreferenceStorageInstrumentedTest {
    private Context context;
    private String name;
    private MusicStore store;
    private SQLiteDatabase db;

    @Before public void before() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        name = "aurora-native-preferences-fixture-" + java.util.UUID.randomUUID() + ".db";
        store = new MusicStore(context, name); db = store.getWritableDatabase();
    }

    @After public void after() {
        if (store != null) store.close();
        if (context != null && name != null) context.deleteDatabase(name);
    }

    @Test public void newSchemaSeparatesPendingMigrationFromAnInitializedRecord() {
        try (Cursor row = db.rawQuery("SELECT initialized,version,revision,restore_revision,payload FROM native_preferences WHERE singleton=1", null)) {
            assertTrue(row.moveToFirst()); assertEquals(0, row.getInt(0)); assertEquals(1, row.getInt(1));
            assertEquals(0, row.getLong(2)); assertEquals(0, row.getLong(3)); assertTrue(row.isNull(4));
            assertFalse(row.moveToNext());
        }
        assertEquals(11, db.getVersion());
    }

    @Test public void legacyMigrationRunsOnceAndReopenIgnoresStaleLegacyValues() throws Exception {
        NativePreferenceRecord migrated = NativePreferenceStorage.initialize(db,
            Map.of("volume", .25f, "restoreRevision", 7L), Map.of("language", "fr"),
            Map.of("wifiOnly", false, "password", "fixture-only", "url", "https://example.com/"));
        assertEquals(.25, (Double) migrated.values.get("volume"), 0);
        assertEquals("fr", migrated.values.get("language")); assertEquals(false, migrated.values.get("wifiOnly"));
        assertEquals(7, migrated.restoreRevision); assertEquals(0, migrated.revision);
        assertFalse(migrated.values.json().has("password")); assertFalse(migrated.values.json().has("url"));
        NativePreferenceStorage.update(db, new JSONObject().put("volume", .6).put("language", "en"));
        reopen();
        NativePreferenceRecord restored = NativePreferenceStorage.initialize(db,
            Map.of("volume", .1f, "restoreRevision", 99L), Map.of("language", "zh-CN"), Map.of("wifiOnly", true));
        assertEquals(.6, (Double) restored.values.get("volume"), 0);
        assertEquals("en", restored.values.get("language")); assertEquals(false, restored.values.get("wifiOnly"));
        assertEquals(7, restored.restoreRevision); assertEquals(1, restored.revision);
    }

    @Test public void failedFirstMigrationRemainsPendingAndRetryable() {
        db.execSQL("CREATE TRIGGER reject_preference_init BEFORE UPDATE ON native_preferences BEGIN SELECT RAISE(ABORT,'fixture init failure'); END");
        assertThrows(RuntimeException.class, () -> NativePreferenceStorage.initialize(db,
            Map.of("volume", .25f), Map.of(), Map.of()));
        try (Cursor row = db.rawQuery("SELECT initialized,payload FROM native_preferences", null)) {
            assertTrue(row.moveToFirst()); assertEquals(0, row.getInt(0)); assertTrue(row.isNull(1));
        }
        db.execSQL("DROP TRIGGER reject_preference_init");
        assertEquals(.25, (Double) NativePreferenceStorage.initialize(db, Map.of("volume", .25f), Map.of(), Map.of()).values.get("volume"), 0);
    }

    @Test public void readRefusesPendingAndMissingRowsRatherThanExposingDefaults() {
        assertThrows(IllegalStateException.class, () -> NativePreferenceStorage.read(db));
        db.execSQL("DELETE FROM native_preferences");
        assertThrows(IllegalStateException.class, () -> NativePreferenceStorage.read(db));
        assertThrows(IllegalStateException.class, () -> defaults());
        assertEquals(0, count("native_preferences"));
    }

    @Test public void rememberToggleInvalidatesCheckpointAtomicallyButPreservesLibraryData() throws Exception {
        defaults(); seedCheckpoint();
        NativePreferenceRecord disabled = NativePreferenceStorage.update(db, new JSONObject().put("rememberPlayback", false));
        assertEquals(false, disabled.values.get("rememberPlayback")); assertEquals(1, disabled.restoreRevision);
        assertEquals(0, count("playback_state")); assertEquals(1, count("playback_queue")); assertEquals(1, count("history"));
        NativePreferenceRecord enabled = NativePreferenceStorage.update(db, new JSONObject().put("rememberPlayback", true));
        assertEquals(2, enabled.restoreRevision); assertEquals(0, count("playback_state"));
        reopen(); assertEquals(2, NativePreferenceStorage.read(db).restoreRevision);
        assertEquals(1, count("songs"));
    }

    @Test public void disablingDuringFirstMigrationAlsoRemovesAnOldCheckpoint() {
        seedCheckpoint();
        NativePreferenceStorage.initialize(db, Map.of("rememberPlayback", false), Map.of(), Map.of());
        assertEquals(0, count("playback_state")); assertEquals(1, count("playback_queue")); assertEquals(1, count("history"));
    }

    @Test public void checkpointDeleteFailureRollsBackAlreadyWrittenPreferences() throws Exception {
        defaults(); seedCheckpoint();
        db.execSQL("CREATE TRIGGER reject_checkpoint_clear BEFORE DELETE ON playback_state BEGIN SELECT RAISE(ABORT,'fixture clear failure'); END");
        assertThrows(RuntimeException.class, () -> NativePreferenceStorage.update(db, new JSONObject().put("rememberPlayback", false)));
        reopen();
        NativePreferenceRecord record = NativePreferenceStorage.read(db);
        assertEquals(true, record.values.get("rememberPlayback")); assertEquals(0, record.revision); assertEquals(0, record.restoreRevision);
        assertEquals(1, count("playback_state")); assertEquals(1, count("playback_queue")); assertEquals(1, count("history"));
    }

    @Test public void invalidMixedPatchAndWriteFailureLeavePreviousValuesIntact() throws Exception {
        defaults();
        assertThrows(IllegalArgumentException.class, () -> NativePreferenceStorage.update(db,
            new JSONObject().put("volume", .25).put("wifiOnly", "false")));
        db.execSQL("CREATE TRIGGER reject_preference_update BEFORE UPDATE ON native_preferences BEGIN SELECT RAISE(ABORT,'fixture update failure'); END");
        assertThrows(RuntimeException.class, () -> NativePreferenceStorage.update(db, new JSONObject().put("volume", .25)));
        assertEquals(1, (Double) NativePreferenceStorage.read(db).values.get("volume"), 0);
        assertEquals(true, NativePreferenceStorage.read(db).values.get("wifiOnly"));
        assertEquals(0, NativePreferenceStorage.read(db).revision);
    }

    @Test public void emptyOrEquivalentPatchDoesNotWriteOrAdvanceRevision() throws Exception {
        defaults();
        db.execSQL("CREATE TRIGGER reject_preference_noop BEFORE UPDATE ON native_preferences BEGIN SELECT RAISE(ABORT,'no unnecessary write'); END");
        assertEquals(0, NativePreferenceStorage.update(db, new JSONObject()).revision);
        assertEquals(0, NativePreferenceStorage.update(db, new JSONObject().put("volume", 1)).revision);
    }

    @Test public void corruptOrIncompletePayloadFailsClosedOnReadAndInitialize() throws Exception {
        defaults();
        String complete = NativePreferenceStorage.read(db).values.json().toString();
        JSONObject missing = new JSONObject(complete); missing.remove("wifiOnly");
        JSONObject unknown = new JSONObject(complete).put("password", "fixture-only");
        JSONObject coerced = new JSONObject(complete).put("prefetch", "true");
        for (String payload : new String[]{"{", "[]", missing.toString(), unknown.toString(), coerced.toString()}) {
            db.execSQL("UPDATE native_preferences SET payload=?", new Object[]{payload});
            assertThrows(RuntimeException.class, () -> NativePreferenceStorage.read(db));
            assertThrows(RuntimeException.class, () -> defaults());
            try (Cursor row = db.rawQuery("SELECT payload FROM native_preferences", null)) {
                assertTrue(row.moveToFirst()); assertEquals(payload, row.getString(0));
            }
        }
    }

    @Test public void initializedNullAndOversizedPayloadNeverBecomeFreshMigration() {
        defaults();
        db.execSQL("UPDATE native_preferences SET payload=NULL");
        assertThrows(RuntimeException.class, () -> defaults());
        StringBuilder oversized = new StringBuilder();
        for (int i = 0; i < 6000; i++) oversized.append('x');
        db.execSQL("UPDATE native_preferences SET payload=?", new Object[]{oversized.toString()});
        assertThrows(RuntimeException.class, () -> NativePreferenceStorage.read(db));
        assertThrows(RuntimeException.class, () -> defaults());
    }

    @Test public void futureVersionAndNonIntegerMetadataFailClosed() {
        defaults();
        for (String mutation : new String[]{"version=2", "version=4294967297", "revision=-1", "revision=0.5", "restore_revision=-1"}) {
            db.execSQL("UPDATE native_preferences SET version=1,revision=0,restore_revision=0");
            db.execSQL("UPDATE native_preferences SET " + mutation);
            assertThrows(RuntimeException.class, () -> NativePreferenceStorage.read(db));
            assertThrows(RuntimeException.class, () -> defaults());
        }
    }

    @Test public void revisionOverflowCannotChangeStoredValues() throws Exception {
        defaults();
        db.execSQL("UPDATE native_preferences SET revision=?", new Object[]{Long.MAX_VALUE});
        assertThrows(IllegalStateException.class, () -> NativePreferenceStorage.update(db, new JSONObject().put("volume", .2)));
        assertEquals(1, (Double) NativePreferenceStorage.read(db).values.get("volume"), 0);
        db.execSQL("UPDATE native_preferences SET revision=0,restore_revision=?", new Object[]{Long.MAX_VALUE});
        assertThrows(IllegalStateException.class, () -> NativePreferenceStorage.update(db, new JSONObject().put("rememberPlayback", false)));
        assertEquals(true, NativePreferenceStorage.read(db).values.get("rememberPlayback"));
    }

    @Test public void transactionPrimitiveCommitsOrRollsBackLibraryAndPreferencesTogether() throws Exception {
        NativePreferenceRecord before = defaults(); seedCheckpoint();
        NativePreferenceRecord next = before.patch(new JSONObject().put("volume", .25));
        assertThrows(IllegalStateException.class, () -> NativePreferenceStorage.writeInTransaction(db, before, next));
        db.beginTransaction();
        try {
            db.execSQL("UPDATE songs SET favorite=1");
            NativePreferenceStorage.writeInTransaction(db, before, next);
        } finally { db.endTransaction(); }
        assertEquals(0, favorite()); assertEquals(1, (Double) NativePreferenceStorage.read(db).values.get("volume"), 0);
        db.beginTransaction();
        try {
            db.execSQL("UPDATE songs SET favorite=1");
            NativePreferenceStorage.writeInTransaction(db, before, next);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        reopen(); assertEquals(1, favorite()); assertEquals(.25, (Double) NativePreferenceStorage.read(db).values.get("volume"), 0);
    }

    @Test public void staleOrForgedRecordCannotOverwriteACommittedSuccessor() throws Exception {
        NativePreferenceRecord before = defaults();
        NativePreferenceRecord staleNext = before.patch(new JSONObject().put("volume", .25));
        NativePreferenceStorage.update(db, new JSONObject().put("language", "fr"));
        db.beginTransaction();
        try { assertThrows(IllegalStateException.class, () -> NativePreferenceStorage.writeInTransaction(db, before, staleNext)); }
        finally { db.endTransaction(); }
        NativePreferenceRecord actual = NativePreferenceStorage.read(db);
        NativePreferenceRecord forged = NativePreferenceRecord.restore(1, 999, actual.restoreRevision, staleNext.values.json());
        db.beginTransaction();
        try { assertThrows(IllegalArgumentException.class, () -> NativePreferenceStorage.writeInTransaction(db, actual, forged)); }
        finally { db.endTransaction(); }
        assertEquals("fr", NativePreferenceStorage.read(db).values.get("language"));
        assertEquals(1, NativePreferenceStorage.read(db).revision);
    }

    @Test public void upgradingSchemaTenRetainsLibraryCheckpointAndPendingMigration() {
        seedCheckpoint();
        db.execSQL("DROP TABLE native_preferences"); db.setVersion(10); reopen();
        assertEquals(11, db.getVersion());
        assertEquals(1, count("songs")); assertEquals(1, count("playback_state")); assertEquals(1, count("history"));
        assertEquals(1, count("playback_queue"));
        NativePreferenceRecord migrated = NativePreferenceStorage.initialize(db, Map.of(), Map.of(), Map.of());
        assertEquals(true, migrated.values.get("rememberPlayback")); assertEquals(1, count("playback_state"));
    }

    @Test public void futureDatabaseCannotBeDowngradedOrReset() {
        defaults(); db.setVersion(12); store.close(); store = new MusicStore(context, name);
        assertThrows(RuntimeException.class, () -> store.getWritableDatabase());
        try (SQLiteDatabase raw = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)) {
            assertEquals(12, raw.getVersion()); assertEquals(true, NativePreferenceStorage.read(raw).values.get("wifiOnly"));
        }
    }

    private NativePreferenceRecord defaults() { return NativePreferenceStorage.initialize(db, Map.of(), Map.of(), Map.of()); }
    private void reopen() { store.close(); store = new MusicStore(context, name); db = store.getWritableDatabase(); }
    private int count(String table) {
        try (Cursor row = db.rawQuery("SELECT COUNT(*) FROM " + table, null)) { assertTrue(row.moveToFirst()); return row.getInt(0); }
    }
    private int favorite() {
        try (Cursor row = db.rawQuery("SELECT favorite FROM songs WHERE id='fixture'", null)) { assertTrue(row.moveToFirst()); return row.getInt(0); }
    }
    private void seedCheckpoint() {
        db.execSQL("INSERT INTO songs(id,title,format,url,created_at) VALUES('fixture','Fixture','mp3','https://example.com/fixture.mp3','fixture')");
        db.execSQL("INSERT INTO history(song_id,action,timestamp) VALUES('fixture','start',123)");
        db.execSQL("INSERT INTO playback_queue(position,song_id,original_position) VALUES(0,'fixture',0)");
        db.execSQL("INSERT INTO playback_state(id,song_id,position_ms,restore_revision) VALUES(1,'fixture',4567,0)");
    }
}
