package com.aurora.musicnext;

import androidx.media3.datasource.cache.Cache;
import java.lang.reflect.Proxy;
import org.junit.Test;
import static org.junit.Assert.*;

/** Cache construction must succeed before either playback or prefetch starts any network I/O. */
public class StreamCacheFactoryTest {
    @Test public void writeSinkIsBoundBeforeItIsCreated() {
        // SQLite belongs to Android; constructing the real Media3 sink must not access it yet.
        Cache cache = (Cache) Proxy.newProxyInstance(Cache.class.getClassLoader(), new Class<?>[]{Cache.class},
            (proxy, method, arguments) -> { throw new AssertionError("Unexpected cache I/O during construction"); });
        assertNotNull(StreamCache.writeFactory(cache).createDataSink());
    }

    @Test public void missingCacheIsRejectedInsteadOfCreatingAnUnusableSink() {
        assertThrows(NullPointerException.class, () -> StreamCache.writeFactory(null).createDataSink());
    }
}
