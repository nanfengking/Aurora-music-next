package com.aurora.musicnext;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import androidx.core.content.ContextCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import com.google.common.util.concurrent.SettableFuture;
import java.io.File;
import java.lang.reflect.Field;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Actual plugin preference entry points, private SQL, no Activity/WebView or production profile.
 * Reflection only supplies the plugin's owned context/executors; no production test hooks. */
@RunWith(AndroidJUnit4.class)
public final class PluginPreferencesInstrumentedTest {
    private final Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final String prefix = "aurora-plugin-pref-" + java.util.UUID.randomUUID() + "-";
    private final AtomicInteger binds = new AtomicInteger();
    private final Context context = new ContextWrapper(base) {
        @Override public Context getApplicationContext() { return this; }
        @Override public File getDatabasePath(String name) { return base.getDatabasePath(prefix + name); }
        @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory) { return base.openOrCreateDatabase(prefix + name, mode, factory); }
        @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler handler) { return base.openOrCreateDatabase(prefix + name, mode, factory, handler); }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) { return base.getSharedPreferences(prefix + name, mode); }
        @Override public boolean bindService(Intent intent, ServiceConnection connection, int flags) {
            binds.incrementAndGet(); throw new AssertionError("Idle preferences must not bind a playback service");
        }
    };
    private final NativePreferences preferences = NativePreferences.forApplication(context);
    private final AuroraMusicPlugin plugin = new AuroraMusicPlugin();
    private MusicStore store;
    private void prepare() throws Exception {
        put("application", context); put("main", ContextCompat.getMainExecutor(context)); put("preferences", preferences); put("foreground", false);
    }
    private SQLiteDatabase database() { if (store == null) store = new MusicStore(context); return store.getWritableDatabase(); }
    @After public void close() throws Exception {
        put("destroyed", true);
        for (String name : new String[]{"io", "aiIo", "assetIo"}) {
            ExecutorService executor = (ExecutorService) field(name).get(plugin); executor.shutdown();
            assertTrue(executor.awaitTermination(6, TimeUnit.SECONDS));
        }
        if (store != null) store.close(); base.deleteDatabase(prefix + "aurora-mobile.db");
        for (String name : new String[]{"playback-options", "appearance", "connection", "widget_snapshot"}) base.deleteSharedPreferences(prefix + name);
    }

    @Test public void appearanceWaitsForRealColdHydration() throws Exception {
        prepare(); SQLiteDatabase db = database(); db.beginTransaction(); Captured call = call("appearance", new JSObject());
        try { main(() -> { plugin.appearance(call); assertFalse(call.result.isDone()); }); }
        finally { db.endTransaction(); }
        assertEquals("", call.result.get(6, TimeUnit.SECONDS).getString("language")); assertEquals(1, call.count.get());
    }

    @Test public void appearanceSuccessWaitsForCommitAndPreservesOtherFields() throws Exception {
        prepare(); preferences.ready().get(6, TimeUnit.SECONDS);
        preferences.update(new JSONObject().put("motion", false).put("language", "fr")).get(6, TimeUnit.SECONDS);
        SQLiteDatabase db = database(); db.beginTransaction(); Captured call = call("setAppearance", json("lyricsSize", 36));
        try { main(() -> { plugin.setAppearance(call); assertFalse("No premature success", call.result.isDone()); }); }
        finally { db.endTransaction(); }
        JSObject result = call.result.get(6, TimeUnit.SECONDS);
        assertEquals(36, result.getInt("lyricsSize")); assertFalse(result.getBoolean("motion")); assertEquals("fr", result.getString("language"));
        assertEquals(1, call.count.get());
    }

    @Test public void failedNativeCommitRejectsOnceAndPreservesPreviousValue() throws Exception {
        prepare(); preferences.ready().get(6, TimeUnit.SECONDS);
        database().execSQL("CREATE TRIGGER refuse_plugin_update BEFORE UPDATE ON native_preferences BEGIN SELECT RAISE(ABORT,'fixture'); END");
        Captured call = call("setLanguage", json("language", "fr")); main(() -> plugin.setLanguage(call));
        assertEquals("PREFERENCES_FAILED", failure(call)); assertEquals("", preferences.requireReady().values.get("language")); assertEquals(1, call.count.get());
    }

    @Test public void followSystemAndStrictWifiPatchesUseCanonicalAuthority() throws Exception {
        prepare(); preferences.ready().get(6, TimeUnit.SECONDS);
        Captured language = call("setLanguage", json("language", "fr")); main(() -> plugin.setLanguage(language)); language.result.get(6, TimeUnit.SECONDS);
        Captured system = call("setLanguage", json("language", "")); main(() -> plugin.setLanguage(system)); system.result.get(6, TimeUnit.SECONDS);
        Captured invalid = call("setWifiOnly", json("enabled", "false")); main(() -> plugin.setWifiOnly(invalid)); assertEquals("PREFERENCES_FAILED", failure(invalid));
        assertEquals(true, preferences.requireReady().values.get("wifiOnly")); assertEquals("", preferences.requireReady().values.get("language"));
        Captured wifi = call("setWifiOnly", json("enabled", false)); main(() -> plugin.setWifiOnly(wifi)); wifi.result.get(6, TimeUnit.SECONDS);
        assertEquals(false, preferences.requireReady().values.get("wifiOnly"));
    }

    @Test public void closedPluginRejectsNewPreferenceWritesWithoutCommitting() throws Exception {
        prepare(); preferences.ready().get(6, TimeUnit.SECONDS); put("destroyed", true);
        Captured call = call("setAppearance", json("lyricsSize", 36)); main(() -> plugin.setAppearance(call));
        assertEquals("PLUGIN_CLOSED", failure(call));
        assertEquals("A call submitted after destruction must not persist", 18, preferences.requireReady().values.get("lyricsSize"));
        Captured language = call("setLanguage", json("language", "fr")); main(() -> plugin.setLanguage(language));
        Captured wifi = call("setWifiOnly", json("enabled", false)); main(() -> plugin.setWifiOnly(wifi));
        assertEquals("PLUGIN_CLOSED", failure(language)); assertEquals("PLUGIN_CLOSED", failure(wifi));
        assertEquals("", preferences.requireReady().values.get("language")); assertEquals(true, preferences.requireReady().values.get("wifiOnly"));
    }

    @Test public void destroyedColdGetterDoesNotReturnLateDefaults() throws Exception {
        prepare(); SQLiteDatabase db = database(); db.beginTransaction(); Captured call = call("appearance", new JSObject());
        try { main(() -> plugin.appearance(call)); put("destroyed", true); }
        finally { db.endTransaction(); }
        assertEquals("PLUGIN_CLOSED", failure(call)); assertEquals(1, call.count.get());
    }

    @Test public void idlePlaybackSettingsAwaitCommitWithoutStartingServiceOrPersistingTimer() throws Exception {
        prepare(); preferences.ready().get(6, TimeUnit.SECONDS);
        SQLiteDatabase db = database(); db.beginTransaction(); Captured save = call("setPlaybackOptions", json("speed", 1.25));
        try { main(() -> { plugin.setPlaybackOptions(save); assertFalse(save.result.isDone()); }); }
        finally { db.endTransaction(); }
        assertEquals(1.25, save.result.get(6, TimeUnit.SECONDS).getDouble("speed"), 0);
        Captured read = call("playbackOptions", new JSObject()); main(() -> plugin.playbackOptions(read));
        assertEquals(1.25, read.result.get(6, TimeUnit.SECONDS).getDouble("speed"), 0);
        JSObject timer = json("speed", 1.5); timer.put("sleepMinutes", 15);
        Captured invalid = call("setPlaybackOptions", timer); main(() -> plugin.setPlaybackOptions(invalid));
        assertEquals("PREFERENCES_FAILED", failure(invalid));
        assertEquals(1.25, (Double) preferences.requireReady().values.get("speed"), 0);
        assertFalse(preferences.requireReady().values.json().has("sleepMinutes")); assertEquals(0, binds.get());
    }

    @Test public void staleWebViewCannotStartAutomaticAssetLookupWithoutNativeConsent() throws Exception {
        prepare(); preferences.ready().get(6, TimeUnit.SECONDS); database(); put("store", store); put("foreground", true);
        String address = "https://example.com/aurora-plugin-fixture/song.mp3";
        store.upsert(address, "Fixture artist - Fixture song.mp3", 100, "");
        JSObject query = json("songId", MusicStore.idFor(address)); query.put("force", false);
        Captured lookup = call("lookupAssets", query); main(() -> plugin.lookupAssets(lookup));
        assertEquals("Fixture song", lookup.result.get(6, TimeUnit.SECONDS).getJSONObject("song").getString("title"));
        assertEquals(false, preferences.requireReady().values.get("autoLookup")); assertEquals(1, lookup.count.get());
    }

    private static JSObject json(String key, Object value) throws Exception { JSObject result = new JSObject(); result.put(key, value); return result; }
    private Captured call(String method, JSObject data) { return new Captured(method, data); }
    private static String failure(Captured call) throws Exception {
        try { call.result.get(6, TimeUnit.SECONDS); fail("Expected native rejection"); return ""; }
        catch (ExecutionException expected) { return expected.getCause().getMessage(); }
    }
    private static void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private static Field field(String name) throws Exception { Field field = AuroraMusicPlugin.class.getDeclaredField(name); field.setAccessible(true); return field; }
    private void put(String name, Object value) throws Exception { field(name).set(plugin, value); }
    private static final class Captured extends PluginCall {
        final SettableFuture<JSObject> result = SettableFuture.create(); final AtomicInteger count = new AtomicInteger();
        Captured(String method, JSObject data) { super(null, "AuroraMusic", "fixture", method, data); }
        @Override public void resolve(JSObject value) { count.incrementAndGet(); result.set(value); }
        @Override public void resolve() { resolve(new JSObject()); }
        @Override public void reject(String message, String code, Exception error, JSObject data) { count.incrementAndGet(); result.setException(new IllegalStateException(message)); }
        @Override public void errorCallback(String message) { reject(message, null, null, null); }
    }
}
