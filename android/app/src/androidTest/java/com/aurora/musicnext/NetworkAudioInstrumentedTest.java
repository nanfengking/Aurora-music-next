package com.aurora.musicnext;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.SystemClock;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in HTTPS/decoder checks. Official Media3 samples, isolated state, no user credentials. */
@RunWith(AndroidJUnit4.class)
@OptIn(markerClass = UnstableApi.class)
public class NetworkAudioInstrumentedTest {
    private static final String HOST = "https://storage.googleapis.com/";
    private Context base, context;
    private String prefix;
    private ExoPlayer player;
    private final CancellationGroup cancellations = new CancellationGroup();
    private final AtomicInteger openSources = new AtomicInteger();

    @Before public void prepare() {
        // External availability must not make the ordinary offline regression suite flaky.
        assumeTrue("Requires explicit public-media network test opt-in",
            "true".equals(InstrumentationRegistry.getArguments().getString("audioNetwork")));
        base = InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefix = "aurora-network-test-" + UUID.randomUUID() + "-";
        context = new ContextWrapper(base) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getCacheDir() { return new File(base.getCacheDir(), prefix + "cache"); }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) { return base.getSharedPreferences(prefix + name, mode); }
            @Override public File getDatabasePath(String name) { return base.getDatabasePath(prefix + name); }
            @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory) {
                return base.openOrCreateDatabase(prefix + name, mode, factory);
            }
            @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler handler) {
                return base.openOrCreateDatabase(prefix + name, mode, factory, handler);
            }
        };
        assertTrue(context.getCacheDir().isDirectory() || context.getCacheDir().mkdirs());
    }

    @After public void close() throws Exception {
        cancellations.cancel();
        if (player != null) main(() -> player.release());
        awaitSourcesClosed();
        if (context != null) {
            StreamCache.clear(context);
            base.deleteSharedPreferences(prefix + "connection");
            for (String database : base.databaseList()) if (database.startsWith(prefix)) base.deleteDatabase(database);
        }
    }

    @Test public void mp3DecodesAndSeeksThroughProductionHttpsCache() throws Exception {
        play("exoplayer-test-media-0/play.mp3", MimeTypes.AUDIO_MPEG);
    }

    @Test public void aacMp4DecodesAndSeeksThroughProductionHttpsCache() throws Exception {
        play("exoplayer-test-media-1/gen-3/screens/dash-vod-single-segment/audio-141.mp4", MimeTypes.AUDIO_AAC);
    }

    private void play(String path, String expectedMime) throws Exception {
        String address = HOST + path;
        String root = address.substring(0, address.lastIndexOf('/') + 1);
        assertTrue(context.getSharedPreferences("connection", Context.MODE_PRIVATE).edit()
            .putString("url", root).putString("revision", prefix).commit());
        main(() -> {
            player = new ExoPlayer.Builder(context)
                .setMediaSourceFactory(new DefaultMediaSourceFactory((DataSource.Factory) () ->
                    observed(StreamCache.factory(context, cancellations).createDataSource())))
                .setLoadControl(new DefaultLoadControl.Builder().setBufferDurationsMs(1000, 2000, 250, 500)
                    .setTargetBufferBytes(512 * 1024).build()).build();
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).build(), false);
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build());
            player.setVolume(0);
            // No forced container MIME: exercise production sniffing, including video/mp4 responses.
            player.setMediaItem(MediaItem.fromUri(address)); player.prepare(); player.play();
        });
        await(() -> player.isPlaying() && player.getCurrentPosition() >= 500 && rendered() > 0);
        main(() -> {
            assertNotNull(player.getAudioFormat()); assertEquals(expectedMime, player.getAudioFormat().sampleMimeType);
            assertTrue("Fixture must be long enough to exercise a seek", player.getDuration() > 4000);
            player.seekTo(3000);
        });
        await(() -> player.isPlaying() && player.getCurrentPosition() >= 3500 && rendered() > 0);
        // Small in-flight fragments are committed when the native source closes, not at pause.
        cancellations.cancel();
        main(() -> { player.release(); player = null; });
        awaitSourcesClosed();
        long bytes = StreamCache.inspect(context).getLong("streamBytes");
        assertTrue("Remote playback must populate the real native cache", bytes > 0);
        assertTrue("Streaming cache must remain bounded", bytes <= StreamCache.LIMIT);
    }

    /** Only observe lifetime: every byte, request and cache operation still uses the production source. */
    private DataSource observed(DataSource delegate) {
        return new DataSource() {
            final AtomicBoolean open = new AtomicBoolean();
            public void addTransferListener(TransferListener listener) { delegate.addTransferListener(listener); }
            public long open(DataSpec spec) throws IOException {
                if (open.compareAndSet(false, true)) openSources.incrementAndGet();
                return delegate.open(spec);
            }
            public int read(byte[] buffer, int offset, int length) throws IOException { return delegate.read(buffer, offset, length); }
            public Uri getUri() { return delegate.getUri(); }
            public Map<String, List<String>> getResponseHeaders() { return delegate.getResponseHeaders(); }
            public void close() throws IOException {
                try { delegate.close(); }
                finally { if (open.compareAndSet(true, false)) openSources.decrementAndGet(); }
            }
        };
    }
    private void awaitSourcesClosed() throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        // Media3 may close a loader after player.release returns. Never remove its live fixture database.
        while (openSources.get() != 0 && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20);
        assertEquals("Released player must finish closing its native sources", 0, openSources.get());
    }

    private int rendered() {
        DecoderCounters counters = player.getAudioDecoderCounters();
        if (counters == null) return 0;
        counters.ensureUpdated(); return counters.renderedOutputBufferCount;
    }
    private void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private void await(BooleanSupplier condition) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 30000;
        while (SystemClock.elapsedRealtime() < deadline) {
            AtomicReference<Boolean> ready = new AtomicReference<>();
            main(() -> { if (player.getPlayerError() != null) throw new AssertionError("Native network audio failed", player.getPlayerError()); ready.set(condition.getAsBoolean()); });
            if (ready.get()) return;
            Thread.sleep(50);
        }
        fail("Public HTTPS audio did not decode/advance within the bounded test deadline");
    }
}
