package com.aurora.musicnext;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.ByteArrayDataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.cache.CacheSpan;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Private fixture cache, preferences and database. No network, production data or Keystore writes. */
@RunWith(AndroidJUnit4.class)
@OptIn(markerClass = UnstableApi.class)
public class StreamCacheInstrumentedTest {
    private static final String PREFIX = "aurora-cache-test-";
    private static final String ADDRESS = "https://example.com/music/fixture.m4a";
    private final byte[] audio = new byte[256];
    private Context context;
    private File offline, cover;
    @Before public void before() throws Exception {
        Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context = new ContextWrapper(base) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getCacheDir() { return new File(base.getCacheDir(), PREFIX + "cache"); }
            @Override public File getFilesDir() { return new File(base.getFilesDir(), PREFIX + "files"); }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) { return base.getSharedPreferences(PREFIX + name, mode); }
            @Override public File getDatabasePath(String name) { return base.getDatabasePath(PREFIX + name); }
            @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory) {
                return base.openOrCreateDatabase(PREFIX + name, mode, factory);
            }
            @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler errorHandler) {
                return base.openOrCreateDatabase(PREFIX + name, mode, factory, errorHandler);
            }
        };
        assertTrue(context.getCacheDir().isDirectory() || context.getCacheDir().mkdirs());
        assertTrue(context.getFilesDir().isDirectory() || context.getFilesDir().mkdirs());
        // An active production cache is rejected by StreamCache's directory guard, never cleared.
        StreamCache.clear(context);
        context.getSharedPreferences("connection", Context.MODE_PRIVATE).edit().clear()
            .putString("url", "https://example.com/music/").putString("revision", "scope-one").commit();
        Arrays.fill(audio, (byte) 42);
        offline = new File(context.getFilesDir(), "fixture-offline.m4a");
        cover = new File(context.getFilesDir(), "fixture-cover.jpg");
        write(offline, audio); write(cover, new byte[]{1,2,3});
    }
    @After public void after() throws Exception {
        StreamCache.clear(context);
        if (offline != null) assertTrue(offline.delete());
        if (cover != null) assertTrue(cover.delete());
        context.getSharedPreferences("connection", Context.MODE_PRIVATE).edit().clear().commit();
    }
    private static void write(File file, byte[] value) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(file)) { stream.write(value); }
    }
    private void seed() throws Exception {
        StandaloneDatabaseProvider database = new StandaloneDatabaseProvider(context);
        SimpleCache cache = new SimpleCache(new File(context.getCacheDir(), "audio-stream"),
            new LeastRecentlyUsedCacheEvictor(StreamCache.LIMIT), database);
        try {
            for (String address : new String[]{ADDRESS, ADDRESS + "-other"}) {
                String key = CacheIdentity.key("scope-one", address);
                CacheSpan hole = cache.startReadWrite(key, 0, audio.length);
                assertFalse(hole.isCached);
                try {
                    File file = cache.startFile(key, 0, audio.length);
                    write(file, audio); cache.commitFile(file, audio.length);
                } finally { cache.releaseHoleSpan(hole); }
            }
        } finally { try { cache.release(); } finally { database.close(); } }
    }
    @Test public void offlinePlaybackBypassesCacheAndClearingCannotDeleteUserMedia() throws Exception {
        DataSource source = StreamCache.factory(context).createDataSource();
        try {
            source.open(new DataSpec(Uri.fromFile(offline)));
            byte[] value = new byte[audio.length]; assertEquals(audio.length, source.read(value, 0, value.length));
            assertArrayEquals(audio, value);
        } finally { source.close(); }
        assertEquals(0, StreamCache.inspect(context).getLong("streamBytes"));
        seed(); assertEquals(audio.length * 2, StreamCache.inspect(context).getLong("streamBytes"));
        assertEquals(audio.length * 2, StreamCache.clear(context).getLong("freedBytes"));
        assertTrue(offline.isFile()); assertTrue(cover.isFile());
    }
    @Test public void manualClearRetainsAnActiveReaderAndItsOpenFile() throws Exception {
        seed();
        DataSource source = StreamCache.factory(context).createDataSource();
        try {
            source.open(new DataSpec.Builder().setUri(ADDRESS).setLength(audio.length).build());
            byte[] value = new byte[16]; assertEquals(16, source.read(value, 0, value.length));
            JSONObject result = StreamCache.clear(context);
            assertEquals(audio.length, result.getLong("freedBytes")); assertEquals(audio.length, result.getLong("retainedBytes"));
            assertEquals(16, source.read(value, 0, value.length));
            for (byte b : value) assertEquals(42, b);
        } finally { source.close(); }
        assertEquals(audio.length, StreamCache.clear(context).getLong("freedBytes"));
        assertTrue(offline.isFile()); assertTrue(cover.isFile());
    }
    @Test public void prefetchUsesThePlaybackKeyAndDoesNotDuplicateAnAlreadyCachedSong() throws Exception {
        String stamp = CacheIdentity.source(audio.length, "fixture-modified");
        String key = CacheIdentity.key("scope-one", ADDRESS, stamp);
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        DataSource.Factory upstream = () -> { requests.incrementAndGet(); return new ByteArrayDataSource(audio); };
        StreamCache.prefetch(context, key, ADDRESS, audio.length, upstream, new PrefetchCancellation());
        assertEquals(1, requests.get()); assertEquals(audio.length, StreamCache.inspect(context).getLong("streamBytes"));
        StreamCache.prefetch(context, key, ADDRESS, audio.length, upstream, new PrefetchCancellation()); assertEquals(1, requests.get());
        DataSource playback = StreamCache.factory(context).createDataSource();
        try {
            playback.open(new DataSpec.Builder().setUri(ADDRESS).setKey(stamp).setLength(audio.length).build());
            byte[] read = new byte[audio.length]; assertEquals(audio.length, playback.read(read, 0, read.length)); assertArrayEquals(audio, read);
        } finally { playback.close(); }
        assertEquals(audio.length, StreamCache.inspect(context).getLong("streamBytes")); assertTrue(offline.isFile()); assertTrue(cover.isFile());
    }
    @Test public void cancelledUnknownOrOversizedPrefetchNeverOpensAnUpstream() throws Exception {
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        DataSource.Factory upstream = () -> { requests.incrementAndGet(); return new ByteArrayDataSource(audio); };
        String key = CacheIdentity.key("scope-one", ADDRESS);
        PrefetchCancellation cancelled = new PrefetchCancellation(); cancelled.cancel();
        assertThrows(java.io.InterruptedIOException.class, () -> StreamCache.prefetch(context, key, ADDRESS, audio.length, upstream, cancelled));
        StreamCache.prefetch(context, key, ADDRESS, 0, upstream, new PrefetchCancellation());
        StreamCache.prefetch(context, key, ADDRESS, PrefetchPolicy.MAX_TRACK_BYTES + 1, upstream, new PrefetchCancellation());
        assertEquals(0, requests.get()); assertEquals(0, StreamCache.inspect(context).getLong("streamBytes"));
    }
    @Test public void truncatedPrefetchCannotBeReportedAsTheCompleteFile() throws Exception {
        String key = CacheIdentity.key("scope-one", ADDRESS);
        assertThrows(java.io.IOException.class, () -> StreamCache.prefetch(context, key, ADDRESS, audio.length * 2, () -> new ByteArrayDataSource(audio), new PrefetchCancellation()));
        assertEquals(audio.length, StreamCache.inspect(context).getLong("streamBytes"));
        assertTrue(offline.isFile()); assertEquals(audio.length, StreamCache.clear(context).getLong("freedBytes"));
    }
    @Test public void cancelledHttpSourceCannotOpenANewSocket() throws Exception {
        ScopedHttpDataSource source = new ScopedHttpDataSource(new SecretStore.Connection("https://example.com/music/", "", "fixture-scope"), audio.length);
        source.cancel();
        try {
            AudioHttp.Failure failure = assertThrows(AudioHttp.Failure.class, () -> source.open(new DataSpec.Builder().setUri(ADDRESS).setLength(audio.length).build()));
            assertEquals("AUDIO_CANCELLED", failure.getMessage());
        } finally { source.close(); }
    }
}
