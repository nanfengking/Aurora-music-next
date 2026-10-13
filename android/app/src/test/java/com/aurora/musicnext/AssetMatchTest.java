package com.aurora.musicnext;

import org.junit.Test;
import static org.junit.Assert.*;

public class AssetMatchTest {
    @Test public void lyricSearchHasThreeLevels() {
        assertFalse(AssetMatch.lyrics("Song", "Artist", "Album", "song", "artist", "Other", 0));
        assertTrue(AssetMatch.lyrics("Song", "Artist", "Album", "song", "artist", "Other", 1));
        assertTrue(AssetMatch.lyrics("Song", "Artist", "Album", "song", "Other", "Other", 2));
        assertFalse(AssetMatch.lyrics("Song", "Artist", "Album", "Song Live", "Artist", "Album", 2));
    }
    @Test public void coversRejectWrongArtistAlbumAndDuration() {
        assertFalse(AssetMatch.cover("Song", "", "Album", 180, "Song", "Artist", "Album", 180));
        assertFalse(AssetMatch.cover("Song", "Artist", "Album", 180, "Song", "Other", "Album", 180));
        assertFalse(AssetMatch.cover("Song", "Artist", "Album", 180, "Song", "Artist", "Other", 180));
        assertFalse(AssetMatch.cover("Song", "Artist", "Album", 180, "Song", "Artist", "Album", 230));
        assertTrue(AssetMatch.cover("Song", "Artist", "Album", 180, "song", "artist", "album", 190));
    }
    @Test public void normalizationHandlesFullwidthAndPunctuation() { assertTrue(AssetMatch.same("Ａ Song!", "a song")); }
}
