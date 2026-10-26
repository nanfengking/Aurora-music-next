package com.aurora.musicnext;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Actual private SQLite and production facades; no real credentials or user database. */
@RunWith(AndroidJUnit4.class)
public final class NativeFacadeInstrumentedTest {
    private final List<Fixture> fixtures = new ArrayList<>();
    @After public void close() { for (Fixture fixture : fixtures) fixture.close(); }

    @Test public void coldFacadeConstructionAndCachedGettersNeverReadMainThreadDisk() throws Exception {
        Fixture f = fixture();
        main(() -> {
            PlaybackSettings playback = new PlaybackSettings(f.context);
            AppearanceSettings appearance = new AppearanceSettings(f.context);
            try { playback.volume(); fail("Cold playback getter must refuse defaults"); } catch (IllegalStateException expected) {}
            try { appearance.autoLookup(); fail("Cold appearance getter must refuse defaults"); } catch (IllegalStateException expected) {}
        });
        await(f.preferences().ready()); int reads = f.legacyReads.get(); f.forbidLegacy = true;
        main(() -> {
            assertEquals(1f, new PlaybackSettings(f.context).volume(), 0);
            assertFalse(new AppearanceSettings(f.context).autoLookup());
            try { assertEquals("", new AppearanceSettings(f.context).status().getString("language")); }
            catch (Exception error) { throw new AssertionError(error); }
        });
        assertEquals(reads, f.legacyReads.get());
    }

    @Test public void migrationPreservesNativeLanguageAndNeverCopiesCredentialFields() throws Exception {
        Fixture f = fixture();
        assertTrue(f.legacy("appearance").edit().putString("language", "fr").putString("mode", "dark").putBoolean("motion", false).commit());
        assertTrue(f.legacy("playback-options").edit().putFloat("volume", .45f).putLong("restoreRevision", 7).commit());
        assertTrue(f.legacy("connection").edit().putString("username", "fixture-private-user").putString("password", "fixture-not-a-real-secret").putBoolean("wifiOnly", false).commit());
        NativePreferenceRecord ready = await(f.preferences().ready());
        JSONObject appearance = new AppearanceSettings(f.context).status();
        assertEquals("fr", appearance.getString("language")); assertEquals("fr", appearance.getString("effectiveLanguage"));
        assertEquals(.45f, new PlaybackSettings(f.context).volume(), 0); assertEquals(7, new PlaybackSettings(f.context).revision());
        assertFalse(new SecretStore(f.context).wifiOnly());
        assertFalse(ready.values.json().toString().contains("fixture-private"));
        assertFalse(ready.values.json().toString().contains("fixture-not-a-real-secret"));
        await(new AppearanceSettings(f.context).language(""));
        assertEquals("", new AppearanceSettings(f.context).status().getString("language"));
    }

    @Test public void partialAppearancePatchesDoNotResetOtherPreferences() throws Exception {
        Fixture f = fixture(); await(f.preferences().ready()); AppearanceSettings theme = new AppearanceSettings(f.context);
        await(theme.save(new JSONObject().put("mode", "dark").put("motion", false).put("accent", "#123456")));
        await(theme.save(new JSONObject().put("lyricsSize", 36)));
        JSONObject status = theme.status();
        assertEquals("dark", status.getString("mode")); assertFalse(status.getBoolean("motion"));
        assertEquals("#123456", status.getString("accent")); assertEquals(36, status.getInt("lyricsSize"));
        assertTrue(failed(theme.save(new JSONObject().put("dark", true))) instanceof IllegalArgumentException);
        assertTrue(failed(theme.save(new JSONObject().put("autoLookup", "true"))) instanceof IllegalArgumentException);
        assertFalse(theme.autoLookup());
    }

    @Test public void playbackFacadeRejectsTransientInternalAndForeignFields() throws Exception {
        Fixture f = fixture(); await(f.preferences().ready()); PlaybackSettings playback = new PlaybackSettings(f.context);
        await(playback.save(new JSONObject().put("volume", .3).put("repeatMode", "all")));
        for (JSONObject patch : new JSONObject[]{new JSONObject().put("sleepMinutes", 15), new JSONObject().put("restoreRevision", 8),
            new JSONObject().put("mode", "dark"), new JSONObject().put("shuffle", 1), new JSONObject().put("volume", JSONObject.NULL)})
            assertTrue(failed(playback.save(patch)) instanceof IllegalArgumentException);
        assertEquals(.3f, playback.volume(), 0); assertEquals("all", playback.repeat());
        assertEquals(8, playback.status().length());
    }

    @Test public void canonicalValuesWinOverLaterLegacyWritesAndNewFacadeInstances() throws Exception {
        Fixture f = fixture(); await(f.preferences().ready());
        await(new PlaybackSettings(f.context).save(new JSONObject().put("volume", .25)));
        await(new AppearanceSettings(f.context).language("fr"));
        assertTrue(f.legacy("playback-options").edit().putFloat("volume", .9f).commit());
        assertTrue(f.legacy("appearance").edit().putString("language", "en").commit());
        int reads = f.legacyReads.get(); f.forbidLegacy = true;
        main(() -> {
            assertEquals(.25f, new PlaybackSettings(f.context).volume(), 0);
            try { assertEquals("fr", new AppearanceSettings(f.context).status().getString("language")); }
            catch (Exception error) { throw new AssertionError(error); }
        });
        assertEquals(reads, f.legacyReads.get());
    }

    @Test public void failedCommitKeepsSettingsCheckpointQueueAndHistory() throws Exception {
        Fixture f = fixture(); await(f.preferences().ready()); f.seed();
        PlaybackSettings playback = new PlaybackSettings(f.context); long revision = playback.revision();
        f.store().savePlayback("a", 120, revision);
        f.database().execSQL("INSERT INTO history(song_id,action,timestamp,progress) VALUES('a','complete',1,1)");
        f.database().execSQL("CREATE TRIGGER facade_fail_checkpoint BEFORE DELETE ON playback_state BEGIN SELECT RAISE(ABORT,'fixture rollback'); END");
        assertNotNull(failed(playback.save(new JSONObject().put("rememberPlayback", false))));
        assertTrue(playback.remember()); assertEquals(revision, playback.revision());
        assertArrayEquals(new String[]{"a", "120"}, f.store().savedPlayback()); assertEquals(1, f.store().queueSize());
        try (android.database.Cursor row = f.database().rawQuery("SELECT COUNT(*) FROM history", null)) { assertTrue(row.moveToFirst()); assertEquals(1, row.getInt(0)); }
    }

    @Test public void offOnAndLateClearCannotRestoreOrDeleteANewerCheckpoint() throws Exception {
        Fixture f = fixture(); await(f.preferences().ready()); f.seed(); PlaybackSettings playback = new PlaybackSettings(f.context);
        long old = playback.revision(); f.store().savePlayback("a", 100, old);
        await(playback.save(new JSONObject().put("rememberPlayback", false))); long disabled = playback.revision();
        assertNull(f.store().savedPlayback()); assertEquals(1, f.store().queueSize());
        await(playback.save(new JSONObject().put("rememberPlayback", true))); long current = playback.revision();
        f.store().savePlayback("a", 999, old); assertNull(f.store().savedPlayback());
        f.store().savePlayback("a", 200, current); f.store().forgetPlayback(disabled);
        assertArrayEquals(new String[]{"a", "200"}, f.store().savedPlayback());
    }

    @Test public void wifiOnlyMovesWithoutChangingTheConnectionStore() throws Exception {
        Fixture f = fixture(); assertTrue(f.legacy("connection").edit().putBoolean("wifiOnly", true).putString("username", "fixture-user").commit());
        await(f.preferences().ready()); SecretStore secrets = new SecretStore(f.context);
        await(secrets.wifiOnly(false)); assertFalse(secrets.wifiOnly());
        assertTrue(f.legacy("connection").getBoolean("wifiOnly", false)); assertEquals("fixture-user", secrets.username());
        assertTrue((Boolean) f.preferences().requireReady().values.get("rememberPlayback"));
    }

    @Test public void widgetLocalizationUsesAllFourCanonicalLanguagesAndCanFollowSystem() throws Exception {
        Fixture f = fixture(); await(f.preferences().ready()); AppearanceSettings theme = new AppearanceSettings(f.context);
        for (String language : new String[]{"zh-CN", "zh-TW", "en", "fr"}) {
            await(theme.language(language));
            main(() -> {
                Context localized = AppearanceSettings.localized(f.context);
                assertEquals(language, localized.getResources().getConfiguration().getLocales().get(0).toLanguageTag());
                try { assertEquals(language, theme.status().getString("effectiveLanguage")); }
                catch (Exception error) { throw new AssertionError(error); }
            });
        }
        await(theme.language("")); main(() -> assertSame(f.context, AppearanceSettings.localized(f.context)));
        assertEquals("", theme.status().getString("language"));
    }

    private Fixture fixture() { Fixture f = new Fixture(); fixtures.add(f); return f; }
    private static void main(Runnable work) { InstrumentationRegistry.getInstrumentation().runOnMainSync(work); }
    private static NativePreferenceRecord await(ListenableFuture<NativePreferenceRecord> future) throws Exception { return future.get(6, TimeUnit.SECONDS); }
    private static Throwable failed(ListenableFuture<?> future) throws Exception {
        try { future.get(6, TimeUnit.SECONDS); fail("Expected failed save"); return null; }
        catch (ExecutionException error) { return error.getCause(); }
    }
    private static final class Fixture implements AutoCloseable {
        final Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final String prefix = "aurora-facade-" + java.util.UUID.randomUUID() + "-";
        final AtomicInteger legacyReads = new AtomicInteger(); volatile boolean forbidLegacy;
        final Context context = new ContextWrapper(base) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getDatabasePath(String name) { offMain(); return base.getDatabasePath(prefix + name); }
            @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory) { offMain(); return base.openOrCreateDatabase(prefix + name, mode, factory); }
            @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler handler) { offMain(); return base.openOrCreateDatabase(prefix + name, mode, factory, handler); }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                offMain(); assertFalse("Legacy read after initialization", forbidLegacy); legacyReads.incrementAndGet();
                return base.getSharedPreferences(prefix + name, mode);
            }
        };
        MusicStore helper;
        NativePreferences preferences() { return NativePreferences.forApplication(context); }
        SharedPreferences legacy(String name) { return base.getSharedPreferences(prefix + name, Context.MODE_PRIVATE); }
        MusicStore store() { if (helper == null) helper = new MusicStore(context); return helper; }
        SQLiteDatabase database() { return store().getWritableDatabase(); }
        void seed() throws Exception {
            database().execSQL("INSERT INTO songs(id,title,format,url,created_at) VALUES('a','A','mp3','https://example.com/a.mp3','fixture')");
            store().replaceQueue(new JSONArray().put("a"), "", false, "a");
        }
        static void offMain() { assertNotEquals("Preference disk I/O on main", Looper.getMainLooper(), Looper.myLooper()); }
        @Override public void close() {
            if (helper != null) helper.close(); base.deleteDatabase(prefix + "aurora-mobile.db");
            for (String name : new String[]{"playback-options", "appearance", "connection"}) base.deleteSharedPreferences(prefix + name);
        }
    }
}
