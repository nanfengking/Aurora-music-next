package com.aurora.musicnext;

import org.junit.Test;
import static org.junit.Assert.*;

public class CacheIdentityTest {
    @Test public void cacheIdentityIsStableScopedAndDoesNotContainAddress() {
        String address = "https://example.com/private/music.m4a?token=fixture";
        String key = CacheIdentity.key("scope-one", address);
        assertEquals(key, CacheIdentity.key("scope-one", address));
        assertTrue(key.matches("stream-v3:[a-f0-9]{64}"));
        assertNotEquals(key, CacheIdentity.key("scope-two", address));
        assertNotEquals(key, CacheIdentity.key("scope-one", address + "2"));
        assertFalse(key.contains("private")); assertFalse(key.contains("token"));
        assertThrows(IllegalArgumentException.class, () -> CacheIdentity.key("", address));
    }
    @Test public void localAndOfflineSchemesNeverEnterTheStreamingCache() {
        assertTrue(CacheIdentity.remote("HTTPS")); assertTrue(CacheIdentity.remote("http"));
        assertFalse(CacheIdentity.remote(null)); assertFalse(CacheIdentity.remote("file"));
        assertFalse(CacheIdentity.remote("content")); assertFalse(CacheIdentity.remote("asset"));
    }
    @Test public void refreshedFileSizeAndModificationProduceNewKeysWithoutExposingTags() {
        String first = CacheIdentity.source(5000, "Fixture modified date");
        assertEquals(first, CacheIdentity.source(5000, "Fixture modified date")); assertTrue(first.matches("source-v1:[a-f0-9]{64}"));
        assertNotEquals(first, CacheIdentity.source(5001, "Fixture modified date"));
        assertNotEquals(first, CacheIdentity.source(5000, "Another date")); assertFalse(first.contains("Fixture"));
        assertNotEquals(CacheIdentity.key("scope", "https://example.com/a.m4a", first), CacheIdentity.key("scope", "https://example.com/a.m4a", CacheIdentity.source(5001, "Fixture modified date")));
        assertNotEquals(CacheIdentity.key("scope", "https://example.com/a.m4a", first), CacheIdentity.key("other-scope", "https://example.com/a.m4a", first));
    }
}
