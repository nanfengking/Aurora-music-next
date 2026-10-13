package com.aurora.musicnext;

import android.app.ActivityManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in production service check, restricted to an owned, credential-free AOSP emulator. */
@RunWith(AndroidJUnit4.class)
@OptIn(markerClass = UnstableApi.class)
public class ServicePlaybackInstrumentedTest {
    private static final String ROOT = "https://example.com/aurora-service-fixture/";
    private Context context;
    private MusicStore store;
    private ActivityScenario<MainActivity> activity;
    private ListenableFuture<MediaController> connection;
    private MediaController controller;
    private boolean ownsFixture;

    @Before public void prepare() throws Exception {
        assumeTrue("Requires explicit disposable service-fixture opt-in",
            "true".equals(InstrumentationRegistry.getArguments().getString("serviceFixture"))
            && "AuroraNativeChecksAPI36".equals(InstrumentationRegistry.getArguments().getString("fixtureOwner")));
        assumeTrue("Never modify a physical device's production library",
            "ranchu".equals(Build.HARDWARE) && Build.FINGERPRINT.startsWith("Android/sdk_phone64_x86_64/emu64x:"));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        // WebDAV, AI and resource credentials all live here. Do not read or print their values.
        assumeTrue("Refusing an emulator with connection/provider settings",
            context.getSharedPreferences("connection", Context.MODE_PRIVATE).getAll().isEmpty());
        SharedPreferences marker = context.getSharedPreferences("service-fixture-owner", Context.MODE_PRIVATE);
        boolean priorFixture = marker.getBoolean("owned", false);
        assumeTrue("Refusing an existing production database",
            priorFixture || !context.getDatabasePath("aurora-mobile.db").exists());
        assumeTrue("Refusing existing playback settings",
            priorFixture || context.getSharedPreferences("playback-options", Context.MODE_PRIVATE).getAll().isEmpty());
        store = new MusicStore(context);
        if (priorFixture) {
            try (Cursor rows = store.getReadableDatabase().rawQuery("SELECT url FROM songs", null)) {
                while (rows.moveToNext()) assumeTrue("Refusing non-fixture songs", isFixture(rows.getString(0)));
            }
        }
        assertTrue(marker.edit().putBoolean("owned", true).commit());
        ownsFixture = true;
        new PlaybackSettings(context).save(new JSONObject().put("volume", 0).put("repeatMode", "off")
            .put("shuffle", false).put("prefetch", false).put("crossfadeSeconds", 0).put("rememberPlayback", true));
        seed("a", "A"); seed("b", "B");
        store.replaceQueue(new JSONArray().put(id("a")).put(id("b")), "", false, id("a"));
    }

    @After public void close() throws Exception {
        if (!ownsFixture) { if (store != null) store.close(); return; }
        try {
            try {
                if (activity != null) { activity.close(); activity = null; }
            } finally {
                try { releaseController(); }
                finally { context.stopService(new Intent(context, PlaybackService.class)); }
            }
            await("Service must stop after fixture cleanup", () -> service() == null, false);
        } finally { if (store != null) store.close(); }
        // Keep only this owned emulator's synthetic state; never delete a production DB
        // while the service's final ordered writes/source closures may still be draining.
    }

    @Test public void realServicePlaysWithoutUiAndAcceptsBothWidgetControls() throws Exception {
        activity = ActivityScenario.launch(MainActivity.class);
        List<MediaItem> queue = store.queueWindow(1, 40);
        assertEquals(2, queue.size());
        for (MediaItem item : queue) {
            assertNotNull(item.localConfiguration);
            assertEquals("file", item.localConfiguration.uri.getScheme());
        }
        main(() -> connection = new MediaController.Builder(context,
            new SessionToken(context, new ComponentName(context, PlaybackService.class))).buildAsync());
        controller = connection.get(10, TimeUnit.SECONDS);
        main(() -> { controller.setMediaItems(queue, 0, 0); controller.prepare(); controller.play(); });
        await("First song must actually play through the production service", () -> playing("a") && position() > .5);
        await("Playback service must enter foreground", () -> service() != null && service().foreground);
        await("First widget metadata must reach the native snapshot", () -> widget("a", "A", true));

        activity.close(); activity = null;
        releaseController();
        double before = positionOnMain();
        await("Playback must progress without any activity or bound test controller",
            () -> playing("a") && position() > before + 1 && service() != null && service().foreground);

        control(MusicWidgetProvider.class, "toggle");
        await("Song widget must pause", () -> !state().optBoolean("playWhenReady") && widget("a", "A", false));
        control(LyricsWidgetProvider.class, "toggle");
        await("Lyric widget must resume", () -> playing("a") && widget("a", "A", true));
        control(LyricsWidgetProvider.class, "next");
        await("Lyric widget must advance the real service", () -> playing("b") && widget("b", "B", true));
        control(MusicWidgetProvider.class, "previous");
        await("Song widget must return to the first song", () -> playing("a") && widget("a", "A", true));
    }

    private static boolean isFixture(String address) {
        return (ROOT + "a.wav").equals(address) || (ROOT + "b.wav").equals(address);
    }
    private static String id(String name) { return MusicStore.idFor(ROOT + name + ".wav"); }
    private void seed(String name, String label) throws Exception {
        File directory = new File(context.getFilesDir(), "offline");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File file = new File(directory, id(name) + ".wav");
        int dataBytes = 8000 * 2 * 60;
        ByteBuffer wav = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + dataBytes);
        wav.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1);
        wav.putInt(8000).putInt(16000).putShort((short) 2).putShort((short) 16);
        wav.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(dataBytes);
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(wav.array()); }
        store.upsert(ROOT + name + ".wav", "Service fixture artist - Service fixture " + label + ".wav", file.length(), "");
        store.finishDownload(id(name), file.getAbsolutePath());
        store.lyrics(id(name), "[00:00.00]Service fixture lyric " + label);
    }
    private void control(Class<?> provider, String action) throws Exception {
        Intent intent = new Intent(context, provider).setAction("com.aurora.musicnext.WIDGET_CONTROL")
            .setData(Uri.parse("aurora-widget://service-fixture/" + provider.getSimpleName() + "/" + action))
            .putExtra("control", action);
        PendingIntent command = PendingIntent.getBroadcast(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try { command.send(); } finally { command.cancel(); }
    }
    private void releaseController() {
        if (connection != null) main(() -> { MediaController.releaseFuture(connection); connection = null; controller = null; });
    }
    private boolean playing(String name) {
        JSONObject value = state();
        return id(name).equals(value.optString("songId")) && value.optBoolean("playing");
    }
    private double position() { return state().optDouble("position"); }
    private double positionOnMain() {
        AtomicReference<Double> value = new AtomicReference<>(); main(() -> value.set(position())); return value.get();
    }
    private JSONObject state() {
        try { return PlaybackService.snapshot(); } catch (Exception error) { throw new AssertionError(error); }
    }
    private boolean widget(String name, String label, boolean playing) {
        WidgetSnapshot value = WidgetSnapshot.load(context);
        return id(name).equals(value.songId) && ("Service fixture " + label).equals(value.title)
            && "Service fixture artist".equals(value.artist) && ("Service fixture lyric " + label).equals(value.lyric)
            && value.playing == playing;
    }
    @SuppressWarnings("deprecation") private ActivityManager.RunningServiceInfo service() {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo value : manager.getRunningServices(20))
            if (PlaybackService.class.getName().equals(value.service.getClassName())) return value;
        return null;
    }
    private void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private void await(String description, BooleanSupplier check) throws Exception {
        await(description, check, true);
    }
    private void await(String description, BooleanSupplier check, boolean requireHealthyPlayback) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 10000;
        AtomicReference<String> last = new AtomicReference<>();
        while (SystemClock.elapsedRealtime() < deadline) {
            AtomicReference<Boolean> done = new AtomicReference<>();
            main(() -> {
                JSONObject value = state(); last.set(value.toString());
                if (requireHealthyPlayback && !value.isNull("error")) fail(description + ": " + value);
                done.set(check.getAsBoolean());
            });
            if (done.get()) return;
            Thread.sleep(25);
        }
        fail(description + ": " + last.get());
    }
}
