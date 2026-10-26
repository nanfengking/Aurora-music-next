package com.aurora.musicnext;

import org.junit.Test;
import static org.junit.Assert.assertThrows;

public class ServerScopeTest {
    private static final String ROOT = "https://music.example/dav/";

    @Test public void acceptsSameServerAndExplicitDefaultPort() {
        ServerScope.requireInside(ROOT, "https://MUSIC.example:443/dav/album/song.m4a");
        ServerScope.requireInside(ROOT, "https://music.example/dav/%E6%AD%8C.mp3");
        ServerScope.requireInside(ROOT, ROOT);
    }

    @Test public void rejectsOtherServersAndRootSiblings() {
        for (String address : new String[]{
            "https://other.example/dav/song.mp3", "https://music.example:8443/dav/song.mp3",
            "http://music.example/dav/song.mp3", "https://music.example/dav-other/song.mp3",
            "https://user@music.example/dav/song.mp3", "https://music.example/dav/song.mp3#fragment"
        }) assertThrows(address, IllegalArgumentException.class, () -> ServerScope.requireInside(ROOT, address));
    }

    @Test public void rejectsEncodedTraversalAndNestedEscapes() {
        for (String path : new String[]{"../secret", "%2e%2e/secret", "%252e%252e/secret", "a%2f..%2f../secret", "a%5csecret"})
            assertThrows(path, IllegalArgumentException.class, () -> ServerScope.requireInside(ROOT, ROOT + path));
    }
}
