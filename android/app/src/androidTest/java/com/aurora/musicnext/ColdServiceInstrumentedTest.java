package com.aurora.musicnext;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.SystemClock;
import androidx.media3.common.MediaItem;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Run EACH method in a fresh instrumentation process with explicit owned-AOSP fixture opt-in.
 * A private DB lock delays the real process worker, not a substituted service or player. */
@RunWith(AndroidJUnit4.class)
public final class ColdServiceInstrumentedTest {
    private static final String ROOT = "https://example.com/aurora-service-fixture/";
    private Context context; private MusicStore store, blocker;
    private SQLiteDatabase blocked; private boolean locked, ownsFixture;
    private String blockName;
    private ListenableFuture<NativePreferenceRecord> worker;
    private ListenableFuture<MediaController> connection;
    private MediaController controller;
    private android.media.browse.MediaBrowser legacy;
    private ActivityScenario<MainActivity> activity;
    @Before public void prepare() throws Exception {
        assumeTrue("Requires explicit disposable service-fixture opt-in",
            "true".equals(InstrumentationRegistry.getArguments().getString("serviceFixture"))
            && "AuroraNativeChecksAPI36".equals(InstrumentationRegistry.getArguments().getString("fixtureOwner")));
        assumeTrue("Never touch a physical device", "ranchu".equals(Build.HARDWARE)
            && Build.FINGERPRINT.startsWith("Android/sdk_phone64_x86_64/emu64x:"));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        assumeTrue("Refusing connection/provider settings", context.getSharedPreferences("connection", Context.MODE_PRIVATE).getAll().isEmpty());
        SharedPreferences marker = context.getSharedPreferences("service-fixture-owner", Context.MODE_PRIVATE);
        boolean prior = marker.getBoolean("owned", false);
        assumeTrue("Refusing non-owned database", prior || !context.getDatabasePath("aurora-mobile.db").exists());
        assumeTrue("Refusing existing playback preferences", prior || context.getSharedPreferences("playback-options", Context.MODE_PRIVATE).getAll().isEmpty());
        store = new MusicStore(context);
        try (Cursor rows = store.getReadableDatabase().rawQuery("SELECT url FROM songs", null)) {
            while (rows.moveToNext()) assumeTrue("Refusing non-fixture songs", (ROOT + "a.wav").equals(rows.getString(0)) || (ROOT + "b.wav").equals(rows.getString(0)));
        }
        assertTrue(marker.edit().putBoolean("owned", true).commit()); ownsFixture = true;
        assertThrows("Each cold test requires a fresh process", IllegalStateException.class,
            () -> NativePreferences.forApplication(context).requireReady());
        NativePreferenceStorage.initialize(store.getWritableDatabase(), Map.of(), Map.of(), Map.of());
        NativePreferenceStorage.update(store.getWritableDatabase(), new JSONObject().put("volume", 0).put("speed", 1).put("prefetch", false)
            .put("crossfadeSeconds", 0).put("repeatMode", "off").put("shuffle", false).put("rememberPlayback", true));
        seed("a"); seed("b"); store.replaceQueue(new JSONArray().put(id("a")).put(id("b")), "", false, id("a"));
        CountDownLatch entered = new CountDownLatch(1); blockName = "aurora-service-hydration-" + java.util.UUID.randomUUID() + ".db";
        Context gateContext = new ContextWrapper(context) {
            @Override public File getDatabasePath(String name) { if (locked) entered.countDown(); return super.getDatabasePath(name); }
        };
        blocker = new MusicStore(gateContext, blockName); blocked = blocker.getWritableDatabase(); blocked.beginTransaction(); locked = true;
        worker = NativePreferences.forDatabase(gateContext, blockName).ready();
        assertTrue(entered.await(3, TimeUnit.SECONDS)); assertFalse(worker.isDone());
    }
    @After public void close() throws Exception {
        if (!ownsFixture) { if (store != null) store.close(); return; }
        try {
            if (activity != null) { activity.close(); activity = null; }
            main(() -> {
                if (legacy != null) { legacy.disconnect(); legacy = null; }
                if (connection != null) { MediaController.releaseFuture(connection); connection = null; controller = null; }
            });
            context.stopService(new Intent(context, PlaybackService.class));
        } finally {
            unlock(); if (worker != null) worker.get(6, TimeUnit.SECONDS);
            await("Service teardown", () -> !running());
            if (blocker != null) blocker.close(); if (blockName != null) context.deleteDatabase(blockName);
            if (store != null) store.close();
        }
    }
    @Test public void coldExternalControllerRetainsFirstPlayWithoutActivity() throws Exception {
        connect(); await("Cold real service exists", this::running);
        main(() -> { assertFalse(connection.isDone()); assertTrue(state().isNull("songId")); assertFalse(state().optBoolean("playing")); });
        unlock(); controller = connection.get(8, TimeUnit.SECONDS);
        List<MediaItem> queue = store.queueWindow(1, 40); assertEquals(2, queue.size());
        main(() -> { controller.setMediaItems(queue, 0, 0); controller.prepare(); controller.play(); });
        await("Actual production service plays after readiness", () -> state().optBoolean("playing") && state().optDouble("position") > .1);
        main(() -> { assertEquals(id("a"), state().optString("songId")); assertEquals(0f, controller.getVolume(), 0); });
    }
    @Test public void corruptPreferencesRejectColdConnectionWithoutPublishingActivePlayer() throws Exception {
        store.getWritableDatabase().execSQL("UPDATE native_preferences SET version=2");
        try {
            connect(); await("Cold service exists", this::running); assertFalse(connection.isDone()); unlock();
            try { connection.get(8, TimeUnit.SECONDS); fail("Corrupt preferences must reject connection"); }
            catch (ExecutionException expected) { assertNotNull(expected.getCause()); }
            main(() -> { assertTrue(state().isNull("songId")); assertFalse(state().optBoolean("playWhenReady")); });
        } finally { store.getWritableDatabase().execSQL("UPDATE native_preferences SET version=1"); }
    }
    @Test public void disconnectAndDestroyDuringHydrationCannotRevivePlayback() throws Exception {
        connect(); await("Cold service exists", this::running);
        main(() -> { MediaController.releaseFuture(connection); connection = null; });
        context.stopService(new Intent(context, PlaybackService.class));
        await("Cold service can be destroyed before disk initialization", () -> !running());
        unlock(); worker.get(6, TimeUnit.SECONDS);
        NativePreferences.forApplication(context).ready().get(6, TimeUnit.SECONDS);
        main(() -> { assertFalse(running()); assertTrue(state().isNull("songId")); assertFalse(state().optBoolean("playWhenReady")); });
    }
    @Test public void interleavedRealSettingsWaitForQueueEffectsAndKeepCommitOrder() throws Exception {
        connect(); await("Cold service exists", this::running); unlock(); controller = connection.get(8, TimeUnit.SECONDS);
        List<MediaItem> queue = store.queueWindow(1, 40);
        main(() -> { controller.setMediaItems(queue, 0, 0); controller.prepare(); controller.play(); });
        await("Real service is playing", () -> state().optBoolean("playing"));
        AtomicReference<ListenableFuture<JSONObject>> first = new AtomicReference<>(), second = new AtomicReference<>();
        java.util.List<JSONObject> observed = new java.util.concurrent.CopyOnWriteArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SQLiteDatabase db = store.getWritableDatabase(); db.beginTransaction();
        try {
            main(() -> {
                try {
                    first.set(PlaybackService.setOptions(context, new JSONObject().put("shuffle", true).put("speed", 1.25)));
                    second.set(PlaybackService.setOptions(context, new JSONObject().put("repeatMode", "all").put("volume", .2)));
                    for (ListenableFuture<JSONObject> future : java.util.Arrays.asList(first.get(), second.get()))
                        future.addListener(() -> { try { observed.add(PlaybackService.options(context)); } catch (Throwable error) { failure.set(error); } }, Runnable::run);
                    assertFalse(first.get().isDone()); assertFalse(second.get().isDone());
                } catch (org.json.JSONException error) { throw new AssertionError(error); }
            });
        } finally { db.endTransaction(); }
        first.get().get(8, TimeUnit.SECONDS); second.get().get(8, TimeUnit.SECONDS); main(() -> {});
        assertNull(failure.get()); assertEquals(2, observed.size());
        assertTrue(observed.get(0).getBoolean("shuffle")); assertEquals("off", observed.get(0).getString("repeatMode"));
        assertEquals(0, observed.get(0).getDouble("volume"), 0); assertEquals(1.25, observed.get(0).getDouble("speed"), 0);
        assertEquals("all", observed.get(1).getString("repeatMode")); assertEquals(.2, observed.get(1).getDouble("volume"), 0);
        assertEquals(1.25, observed.get(1).getDouble("speed"), 0); main(() -> assertEquals(.2f, controller.getVolume(), 0));
    }
    @Test public void idleSaveAppliesToServiceStartedBeforeItsCommit() throws Exception {
        unlock(); worker.get(6, TimeUnit.SECONDS);
        NativePreferences preferences = NativePreferences.forApplication(context);
        preferences.ready().get(6, TimeUnit.SECONDS);
        CountDownLatch applying = new CountDownLatch(1);
        com.google.common.util.concurrent.SettableFuture<Void> hold = com.google.common.util.concurrent.SettableFuture.create();
        ListenableFuture<NativePreferenceRecord> preceding = preferences.update(new JSONObject(),
            androidx.core.content.ContextCompat.getMainExecutor(context), new NativePreferences.Effects() {
                @Override public ListenableFuture<?> apply(NativePreferenceRecord before, NativePreferenceRecord after) {
                    applying.countDown(); return hold;
                }
            });
        AtomicReference<ListenableFuture<JSONObject>> pending = new AtomicReference<>();
        try {
            assertTrue(applying.await(3, TimeUnit.SECONDS));
            main(() -> {
                assertFalse("The save starts while playback is idle", running());
                try { pending.set(PlaybackService.setOptions(context, new JSONObject().put("speed", 1.5).put("volume", .25))); }
                catch (org.json.JSONException error) { throw new AssertionError(error); }
            });
            connect(); controller = connection.get(8, TimeUnit.SECONDS);
            main(() -> { assertEquals(1, state().optDouble("speed"), 0); assertEquals(0f, controller.getVolume(), 0); });
            assertFalse(pending.get().isDone());
            hold.set(null); preceding.get(6, TimeUnit.SECONDS);
            JSONObject saved = pending.get().get(8, TimeUnit.SECONDS);
            assertEquals(1.5, saved.getDouble("speed"), 0);
            main(() -> assertEquals("Acknowledgement includes the newly started live player's effects", 1.5, state().optDouble("speed"), 0));
            await("Controller receives committed volume", () -> controller.getVolume() == .25f);
        } finally { hold.set(null); }
    }
    @Test public void nativeLanguageHydratesRealWebViewBeforePreferenceWritersMount() throws Exception {
        NativePreferenceRecord expected = NativePreferenceStorage.update(store.getWritableDatabase(), new JSONObject().put("language", "fr"));
        activity = ActivityScenario.launch(MainActivity.class);
        awaitJavascript("Boolean(document.querySelector('.preference-startup')) && !document.querySelector('.mobile-app')");
        assertEquals("true", javascript("localStorage.setItem('aurora-language','zh-CN'); true"));
        main(() -> assertFalse(running())); unlock();
        awaitJavascript("Boolean(document.querySelector('.mobile-app')) && document.documentElement.lang === 'fr'");
        assertEquals("\"zh-CN\"", javascript("localStorage.getItem('aurora-language')"));
        NativePreferenceRecord actual = NativePreferences.forApplication(context).requireReady();
        assertEquals("fr", actual.values.get("language")); assertEquals(expected.revision, actual.revision);
        main(() -> assertFalse("Opening the interface must not start playback", running()));
    }
    @Test public void destroyedActivitySettlesColdPluginCallBeforeHydrationCompletes() throws Exception {
        activity = ActivityScenario.launch(MainActivity.class);
        CapturedCall call = new CapturedCall("appearance", new com.getcapacitor.JSObject());
        activity.onActivity(screen -> ((AuroraMusicPlugin) screen.getBridge().getPlugin("AuroraMusic").getInstance()).appearance(call));
        assertFalse(call.result.isDone()); assertFalse(worker.isDone());
        activity.close(); activity = null; main(() -> {});
        assertTrue("Destroy must reject the native call without waiting for shared disk initialization", call.result.isDone());
        assertClosed(call); assertFalse("Destroy cannot cancel shared initialization", worker.isDone());
        unlock(); worker.get(6, TimeUnit.SECONDS);
        NativePreferences.forApplication(context).ready().get(6, TimeUnit.SECONDS); main(() -> {});
        assertEquals("Late readiness must not settle a closed call twice", 1, call.count.get());
    }
    @Test public void destroyedActivitySettlesSaveWaitWithoutCancellingAcceptedWrite() throws Exception {
        NativePreferenceStorage.update(store.getWritableDatabase(), new JSONObject().put("language", "en"));
        unlock(); worker.get(6, TimeUnit.SECONDS);
        NativePreferences preferences = NativePreferences.forApplication(context); preferences.ready().get(6, TimeUnit.SECONDS);
        activity = ActivityScenario.launch(MainActivity.class);
        awaitJavascript("Boolean(document.querySelector('.mobile-app'))");
        CountDownLatch applying = new CountDownLatch(1);
        com.google.common.util.concurrent.SettableFuture<Void> hold = com.google.common.util.concurrent.SettableFuture.create();
        ListenableFuture<NativePreferenceRecord> preceding = preferences.update(new JSONObject(),
            androidx.core.content.ContextCompat.getMainExecutor(context), new NativePreferences.Effects() {
                @Override public ListenableFuture<?> apply(NativePreferenceRecord before, NativePreferenceRecord after) {
                    applying.countDown(); return hold;
                }
            });
        com.getcapacitor.JSObject patch = new com.getcapacitor.JSObject(); patch.put("language", "fr");
        CapturedCall call = new CapturedCall("setLanguage", patch);
        try {
            assertTrue(applying.await(3, TimeUnit.SECONDS));
            activity.onActivity(screen -> ((AuroraMusicPlugin) screen.getBridge().getPlugin("AuroraMusic").getInstance()).setLanguage(call));
            assertFalse(call.result.isDone());
            activity.close(); activity = null; main(() -> {});
            assertTrue("Destroy must settle the caller even while its accepted write is queued", call.result.isDone());
            assertClosed(call);
        } finally { hold.set(null); preceding.get(6, TimeUnit.SECONDS); }
        preferences.update(new JSONObject()).get(8, TimeUnit.SECONDS); main(() -> {});
        assertEquals("fr", preferences.requireReady().values.get("language"));
        assertEquals("The durable result cannot resolve the closed call again", 1, call.count.get());
    }
    private static void assertClosed(CapturedCall call) throws Exception {
        try { call.result.get(1, TimeUnit.SECONDS); fail("Expected closed-plugin rejection"); }
        catch (ExecutionException expected) { assertEquals("PLUGIN_CLOSED", expected.getCause().getMessage()); }
    }
    private static final class CapturedCall extends com.getcapacitor.PluginCall {
        final com.google.common.util.concurrent.SettableFuture<com.getcapacitor.JSObject> result = com.google.common.util.concurrent.SettableFuture.create();
        final java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();
        CapturedCall(String method, com.getcapacitor.JSObject data) { super(null, "AuroraMusic", "fixture", method, data); }
        @Override public void resolve(com.getcapacitor.JSObject value) { count.incrementAndGet(); result.set(value); }
        @Override public void resolve() { resolve(new com.getcapacitor.JSObject()); }
        @Override public void reject(String message, String code, Exception error, com.getcapacitor.JSObject data) {
            count.incrementAndGet(); result.setException(new IllegalStateException(message));
        }
        @Override public void errorCallback(String message) { reject(message, null, null, null); }
    }
    private String javascript(String script) throws Exception {
        com.google.common.util.concurrent.SettableFuture<String> result = com.google.common.util.concurrent.SettableFuture.create();
        activity.onActivity(screen -> screen.getBridge().getWebView().evaluateJavascript(script, result::set));
        return result.get(3, TimeUnit.SECONDS);
    }
    private void awaitJavascript(String expression) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 8000;
        while (SystemClock.elapsedRealtime() < deadline) { if ("true".equals(javascript(expression))) return; Thread.sleep(30); }
        fail("Real WebView did not reach expected startup state: " + expression);
    }
    @SuppressWarnings("deprecation")
    @Test public void legacyBrowserConnectCompletesWhileServiceMainWaitsForWorkerPermission() throws Exception {
        CountDownLatch connected = new CountDownLatch(1); AtomicReference<String> error = new AtomicReference<>();
        main(() -> {
            legacy = new android.media.browse.MediaBrowser(context, new ComponentName(context, PlaybackService.class),
                new android.media.browse.MediaBrowser.ConnectionCallback() {
                    @Override public void onConnected() { connected.countDown(); }
                    @Override public void onConnectionFailed() { error.set("Legacy connection rejected"); connected.countDown(); }
                }, android.os.Bundle.EMPTY);
            legacy.connect();
        });
        boolean blockedInRoot = false; long deadline = SystemClock.elapsedRealtime() + 5000;
        try {
            while (SystemClock.elapsedRealtime() < deadline) {
                boolean root = false, wait = false;
                for (StackTraceElement frame : android.os.Looper.getMainLooper().getThread().getStackTrace()) {
                    root |= frame.getClassName().endsWith("MediaSessionServiceLegacyStub") && frame.getMethodName().equals("onGetRoot");
                    wait |= frame.getClassName().equals("androidx.media3.common.util.ConditionVariable") && frame.getMethodName().equals("block");
                }
                if (root && wait) { blockedInRoot = true; break; }
                Thread.sleep(10);
            }
            assertTrue("Must exercise the actual legacy browser-root blocking handshake", blockedInRoot);
            assertEquals(1, connected.getCount());
        } finally { unlock(); }
        assertTrue("Worker-completed permission must unblock main, not require it", connected.await(6, TimeUnit.SECONDS));
        assertNull(error.get()); main(() -> {
            assertTrue(legacy.isConnected()); assertFalse(state().optBoolean("playing"));
            new android.media.session.MediaController(context, legacy.getSessionToken()).getTransportControls().play();
        });
        await("Legacy first play restores the saved queue without an Activity", () -> state().optBoolean("playing") && state().optDouble("position") > .1);
    }
    private void connect() { main(() -> connection = new MediaController.Builder(context,
        new SessionToken(context, new ComponentName(context, PlaybackService.class))).buildAsync()); }
    private void unlock() { if (locked) { locked = false; blocked.endTransaction(); } }
    private static String id(String name) { return MusicStore.idFor(ROOT + name + ".wav"); }
    private void seed(String name) throws Exception {
        File directory = new File(context.getFilesDir(), "offline"); assertTrue(directory.isDirectory() || directory.mkdirs());
        File file = new File(directory, id(name) + ".wav"); int bytes = 8000 * 2 * 30;
        ByteBuffer wave = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN);
        wave.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + bytes).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        wave.putInt(16).putShort((short) 1).putShort((short) 1).putInt(8000).putInt(16000).putShort((short) 2).putShort((short) 16);
        wave.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(bytes);
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(wave.array()); }
        store.upsert(ROOT + name + ".wav", "Service fixture artist - Service fixture " + name.toUpperCase(java.util.Locale.ROOT) + ".wav", file.length(), "");
        store.finishDownload(id(name), file.getAbsolutePath());
    }
    @SuppressWarnings("deprecation") private boolean running() {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(20))
            if (PlaybackService.class.getName().equals(service.service.getClassName())) return true;
        return false;
    }
    private JSONObject state() { try { return PlaybackService.snapshot(); } catch (Exception failure) { throw new AssertionError(failure); } }
    private static void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private void await(String label, BooleanSupplier condition) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 8000;
        while (SystemClock.elapsedRealtime() < deadline) {
            AtomicReference<Boolean> value = new AtomicReference<>(); main(() -> value.set(condition.getAsBoolean()));
            if (value.get()) return; Thread.sleep(20);
        }
        fail(label);
    }
}
