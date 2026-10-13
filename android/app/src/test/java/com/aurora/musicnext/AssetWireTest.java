package com.aurora.musicnext;

import okhttp3.Request;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Pure Java wire logic uses a JVM JSON fixture library; not Android framework/runtime evidence. */
public class AssetWireTest {
    private JSONObject provider(String kind) throws Exception { return new JSONObject().put("kind", kind).put("baseUrl", "https://example.com/asset").put("key", "fixture-key"); }
    private JSONObject meta() throws Exception { return new JSONObject().put("title", "Song & 你好").put("artist", "Artist").put("album", "Album").put("duration", 180); }
    @Test public void customRequestUsesThreeTiersAndNeverUploadsAudioOrUrls() throws Exception {
        JSONObject query = meta();
        for (int level = 0; level < 3; level++) {
            Request request = AssetWire.request(provider("lyrics"), query, level);
            assertEquals("GET", request.method()); assertNull(request.body()); assertEquals("Bearer fixture-key", request.header("Authorization"));
            assertEquals("Song & 你好", request.url().queryParameter("title")); assertEquals("lyrics", request.url().queryParameter("kind"));
            assertEquals(level < 2 ? "Artist" : "", request.url().queryParameter("artist"));
            assertEquals(level == 0 ? "Album" : "", request.url().queryParameter("album"));
            assertNull(request.url().queryParameter("songId")); assertNull(request.url().queryParameter("filePath"));
        }
        Request builtin = AssetWire.request(provider("lyrics").put("builtin", "lrclib"), query, 1);
        assertNull(builtin.header("Authorization")); assertEquals("Song & 你好", builtin.url().queryParameter("track_name")); assertNull(builtin.url().queryParameter("album_name"));
    }
    @Test public void canonicalAndBuiltinJsonNormalizeToBoundedMetadata() throws Exception {
        JSONArray rows = AssetWire.rows("lyrics", "{\"results\":[{\"title\":\"Song\",\"artist\":\"Artist\",\"album\":\"Album\",\"lyrics\":\"[00:00]Fixture lyric\"}]}", false);
        assertEquals(1, rows.length()); assertTrue(rows.getJSONObject(0).getBoolean("synced"));
        assertEquals("[00:00]Fixture lyric", rows.getJSONObject(0).getString("text"));
        assertEquals(0, AssetWire.rows("lyrics", "[{\"instrumental\":true,\"plainLyrics\":\"no lyrics\"}]", false).length());
        JSONArray tooMany = new JSONArray(); for (int i = 0; i < 100; i++) tooMany.put(new JSONObject().put("title", "Song").put("lyrics", "fixture"));
        assertEquals(30, AssetWire.rows("lyrics", tooMany.toString(), false).length());
        assertThrows(Exception.class, () -> AssetWire.rows("lyrics", "{\"error\":\"fixture\"}", false));
        assertThrows(Exception.class, () -> AssetWire.rows("lyrics", "[] trailing", false));
    }
    @Test public void coversAndLyricsKeepConservativeAutomaticMatching() throws Exception {
        JSONObject query = new JSONObject().put("title", "Song").put("artist", "Artist").put("album", "Album").put("duration", 180);
        JSONObject row = new JSONObject().put("title", "Song").put("artist", "Artist").put("album", "Other").put("duration", 180);
        assertFalse(AssetWire.matches(query, row, "lyrics", 0)); assertTrue(AssetWire.matches(query, row, "lyrics", 1));
        assertFalse(AssetWire.matches(query, row, "cover", 0));
        row.put("title", "Album artwork").put("album", "Album"); assertTrue(AssetWire.matches(query, row, "cover", 0));
        row.put("artist", "Other"); assertFalse(AssetWire.matches(query, row, "cover", 0));
        String image = "{\"results\":[{\"title\":\"Song\",\"imageUrl\":\"https://127.0.0.1/image\"}]}";
        assertEquals(0, AssetWire.rows("cover", image, false).length());
        assertEquals(0, AssetWire.rows("cover", image.replace("127.0.0.1", "example.com"), true).length());
    }
    @Test public void providerStatusRedactsKeysAndUrlChangesCannotReuseOldKeys() throws Exception {
        JSONObject item = provider("lyrics").put("id", "fixture").put("name", "Fixture").put("enabled", true).put("apiKey", "fixture-key");
        JSONArray stored = AssetProviderStore.normalize(new JSONArray().put(item), new JSONArray());
        JSONObject status = AssetProviderStore.publicStatus(new JSONObject().put("providers", stored).put("itunesEnabled", false));
        assertFalse(status.toString().contains("fixture-key")); assertTrue(status.getJSONArray("providers").getJSONObject(0).getBoolean("configured"));
        item.remove("apiKey"); assertEquals("fixture-key", AssetProviderStore.normalize(new JSONArray().put(item), stored).getJSONObject(0).getString("key"));
        item.put("baseUrl", "https://other.example.com/asset"); assertThrows(Exception.class, () -> AssetProviderStore.normalize(new JSONArray().put(item), stored));
        item.put("apiKey", "new-fixture-key"); assertEquals("new-fixture-key", AssetProviderStore.normalize(new JSONArray().put(item), stored).getJSONObject(0).getString("key"));
        assertThrows(Exception.class, () -> AssetWire.query(new JSONObject().put("title", new JSONArray())));
        assertThrows(Exception.class, () -> AssetWire.query(new JSONObject()));
    }
}
