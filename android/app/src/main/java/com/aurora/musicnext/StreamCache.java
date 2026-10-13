package com.aurora.musicnext;

import android.content.Context;
import android.net.Uri;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheDataSink;
import androidx.media3.datasource.cache.CacheSpan;
import androidx.media3.datasource.cache.CacheWriter;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/** One cache per directory; leases release the native index when no reader/inspection needs it. */
@androidx.annotation.OptIn(markerClass = UnstableApi.class)
final class StreamCache {
    static final long LIMIT = 128L * 1024 * 1024;
    private static final long FRAGMENT_BYTES = 2L * 1024 * 1024;
    private static SimpleCache instance;
    private static StandaloneDatabaseProvider database;
    private static File ownerDirectory;
    private static int leases;
    private static final Map<String, Integer> readers = new HashMap<>();
    private static File directory(Context context) { return new File(context.getCacheDir(), "audio-stream"); }

    private static synchronized Lease acquire(Context context) throws IOException {
        File target = directory(context).getCanonicalFile();
        if (!context.getCacheDir().getCanonicalFile().equals(target.getParentFile())) throw new IOException("Invalid private cache directory");
        if (instance != null && !target.equals(ownerDirectory)) throw new IOException("Another cache directory is in use");
        if (instance == null) {
            StandaloneDatabaseProvider provider = new StandaloneDatabaseProvider(context);
            try { instance = new SimpleCache(target, new LeastRecentlyUsedCacheEvictor(LIMIT), provider); database = provider; ownerDirectory = target; }
            catch (RuntimeException error) { provider.close(); throw error; }
        }
        leases++; return new Lease(instance);
    }
    private static final class Lease implements AutoCloseable {
        final SimpleCache cache; private boolean closed;
        Lease(SimpleCache cache) { this.cache = cache; }
        public void close() {
            synchronized (StreamCache.class) {
                if (closed) return; closed = true;
                if (--leases == 0) {
                    try { cache.release(); }
                    finally {
                        try { if (database != null) database.close(); }
                        finally { database = null; instance = null; ownerDirectory = null; readers.clear(); }
                    }
                }
            }
        }
    }
    static JSONObject inspect(Context context) throws JSONException, IOException {
        if (!directory(context).isDirectory()) return new JSONObject().put("streamBytes", 0).put("streamLimitBytes", LIMIT);
        try (Lease lease = acquire(context)) {
            return new JSONObject().put("streamBytes", lease.cache.getCacheSpace()).put("streamLimitBytes", LIMIT);
        }
    }
    static JSONObject clear(Context context) throws JSONException, IOException {
        if (!directory(context).isDirectory()) return new JSONObject().put("freedBytes", 0).put("retainedBytes", 0);
        try (Lease lease = acquire(context)) {
            synchronized (lease.cache) {
                long before = lease.cache.getCacheSpace();
                // Media3 updates its index and files together. Never recursively delete cache or app directories.
                for (String key : lease.cache.getKeys()) if (!readers.containsKey(key)) lease.cache.removeResource(key);
                long after = lease.cache.getCacheSpace();
                return new JSONObject().put("freedBytes", Math.max(0, before - after)).put("retainedBytes", after);
            }
        }
    }
    static DataSource.Factory factory(Context context) {
        return factory(context, null);
    }
    static DataSource.Factory factory(Context context, CancellationGroup cancellations) {
        Context application = context.getApplicationContext(); return () -> new Source(application, cancellations);
    }
    /** Shared writer configuration: playback and prefetch must use the same cache. */
    static CacheDataSink.Factory writeFactory(Cache cache) {
        return new CacheDataSink.Factory().setCache(cache).setFragmentSize(FRAGMENT_BYTES);
    }
    /** Whole-track best-effort prefetch shares playback bytes, but never marks a song as offline. */
    static void prefetch(Context context, SecretStore.Connection connection, String address, String stamp, long size, PrefetchCancellation cancellation) throws IOException {
        connection.authorizationFor(address);
        ScopedHttpDataSource upstream = new ScopedHttpDataSource(connection, size);
        cancellation.bind(upstream::cancel);
        try {
            cancellation.check();
            prefetch(context, CacheIdentity.key(connection.revision, address, stamp), address, size, () -> upstream, cancellation);
        } finally { cancellation.unbind(); }
    }
    /** Injected upstream also lets isolated Android fixtures exercise the real native cache without a network. */
    static void prefetch(Context context, String key, String address, long size, DataSource.Factory upstream, PrefetchCancellation cancellation) throws IOException {
        if (!PrefetchPolicy.sizeAllowed(size)) return;
        cancellation.check();
        try (Lease lease = acquire(context)) {
            synchronized (lease.cache) {
                cancellation.check();
                if (lease.cache.isCached(key, 0, size)) return;
                long protectedBytes = 0;
                for (String activeKey : readers.keySet()) if (!activeKey.equals(key))
                    for (CacheSpan span : lease.cache.getCachedSpans(activeKey)) protectedBytes += span.length;
                // Only currently writable space counts: optional work must not ask Android to reclaim other data.
                long freeBytes = new android.os.StatFs(context.getCacheDir().getAbsolutePath()).getAvailableBytes();
                if (!PrefetchPolicy.fits(size, protectedBytes, LIMIT, freeBytes)) return;
                long missing = size - lease.cache.getCachedBytes(key, 0, size);
                List<CacheSpan> old = new ArrayList<>();
                for (String oldKey : lease.cache.getKeys()) if (!oldKey.equals(key) && !readers.containsKey(oldKey)) old.addAll(lease.cache.getCachedSpans(oldKey));
                old.sort(java.util.Comparator.comparingLong(span -> span.lastTouchTimestamp));
                for (CacheSpan span : old) {
                    if (lease.cache.getCacheSpace() + missing + PrefetchPolicy.PLAYBACK_HEADROOM_BYTES <= LIMIT) break;
                    cancellation.check(); lease.cache.removeSpan(span);
                }
                if (lease.cache.getCacheSpace() + missing + PrefetchPolicy.PLAYBACK_HEADROOM_BYTES > LIMIT) return;
                readers.put(key, readers.getOrDefault(key, 0) + 1);
            }
            CacheDataSource source = null;
            try {
                source = new CacheDataSource.Factory().setCache(lease.cache).setUpstreamDataSourceFactory(upstream)
                    .setCacheWriteDataSinkFactory(writeFactory(lease.cache)).createDataSource();
                DataSpec spec = new DataSpec.Builder().setUri(address).setKey(key).setLength(size).setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION).build();
                CacheWriter writer = new CacheWriter(source, spec, new byte[32768], null);
                cancellation.bind(writer::cancel);
                cancellation.check(); writer.cache(); cancellation.check();
                if (!lease.cache.isCached(key, 0, size)) throw new IOException("PREFETCH_INCOMPLETE");
            } finally {
                try { if (source != null) source.close(); }
                finally { synchronized (lease.cache) { releaseReader(key); } }
            }
        }
    }
    private static void releaseReader(String key) {
        int count = readers.getOrDefault(key, 0); if (count <= 1) readers.remove(key); else readers.put(key, count - 1);
    }
    /** Local/offline files bypass the cache. Each remote open freezes the same scope for auth and key. */
    private static final class Source implements DataSource {
        private final Context context;
        private final CancellationGroup cancellations;
        private final List<TransferListener> listeners = new ArrayList<>();
        private DataSource source; private Lease lease; private String key;
        Source(Context context, CancellationGroup cancellations) { this.context = context; this.cancellations = cancellations; }
        public void addTransferListener(TransferListener listener) {
            if (listeners.contains(listener)) return;
            listeners.add(listener); if (source != null) source.addTransferListener(listener);
        }
        public long open(DataSpec spec) throws IOException {
            if (source != null) throw new IOException("Audio source is already open");
            try {
                if (!CacheIdentity.remote(spec.uri.getScheme())) {
                    source = new DefaultDataSource.Factory(context, () -> new ScopedHttpDataSource(context, cancellations)).createDataSource();
                } else {
                    SecretStore.Connection connection = new SecretStore(context).connection();
                    connection.authorizationFor(spec.uri.toString());
                    key = CacheIdentity.key(connection.revision, spec.uri.toString(), spec.key);
                    // Optional cache initialization failures must not block an authorized network stream.
                    try { lease = acquire(context); } catch (IOException | RuntimeException unavailable) { key = null; }
                    if (lease == null) source = new ScopedHttpDataSource(connection, cancellations);
                    else {
                        synchronized (lease.cache) { readers.put(key, readers.getOrDefault(key, 0) + 1); }
                        source = new CacheDataSource.Factory().setCache(lease.cache)
                            .setUpstreamDataSourceFactory(() -> new ScopedHttpDataSource(connection, cancellations))
                            .setCacheWriteDataSinkFactory(writeFactory(lease.cache))
                            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR).createDataSource();
                        spec = spec.buildUpon().setKey(key).setFlags(spec.flags | DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION).build();
                    }
                }
                for (TransferListener listener : listeners) source.addTransferListener(listener);
                return source.open(spec);
            } catch (Exception error) {
                try { close(); } catch (Exception cleanup) { error.addSuppressed(cleanup); }
                throw error instanceof IOException ? (IOException) error : new IOException("Unable to open audio cache", error);
            }
        }
        public int read(byte[] buffer, int offset, int length) throws IOException { if (source == null) throw new IOException("Audio source is closed"); return source.read(buffer, offset, length); }
        public Uri getUri() { return source == null ? null : source.getUri(); }
        public Map<String, List<String>> getResponseHeaders() { return source == null ? java.util.Collections.emptyMap() : source.getResponseHeaders(); }
        public void close() throws IOException {
            try { if (source != null) source.close(); }
            finally {
                source = null;
                if (lease != null) {
                    synchronized (lease.cache) { releaseReader(key); }
                    lease.close(); lease = null; key = null;
                }
            }
        }
    }
}
