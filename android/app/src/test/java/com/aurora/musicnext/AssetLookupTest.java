package com.aurora.musicnext;

import okhttp3.Request;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Pure source ordering/header checks, no Android execution or external requests. */
public class AssetLookupTest {
    @Test public void conservativeDefaultsAndPerKindPriorities() throws Exception {
        JSONObject config = new JSONObject().put("providers", new JSONArray()).put("itunesEnabled", false);
        assertEquals(0, AssetLookup.sources(config, "cover").length());
        assertEquals(0, AssetLookup.sources(config, "background").length());
        assertEquals("lrclib", AssetLookup.sources(config, "lyrics").getJSONObject(0).getString("builtin"));
        JSONArray providers = config.getJSONArray("providers");
        providers.put(new JSONObject().put("name", "Cover first").put("kind", "cover").put("enabled", true));
        providers.put(new JSONObject().put("name", "Disabled").put("kind", "cover").put("enabled", false));
        providers.put(new JSONObject().put("name", "Lyrics").put("kind", "lyrics").put("enabled", true));
        providers.put(new JSONObject().put("name", "Cover second").put("kind", "cover").put("enabled", true));
        config.put("itunesEnabled", true); JSONArray covers = AssetLookup.sources(config, "cover");
        assertEquals(3, covers.length()); assertEquals("Cover first", covers.getJSONObject(0).getString("name"));
        assertEquals("Cover second", covers.getJSONObject(1).getString("name")); assertEquals("itunes", covers.getJSONObject(2).getString("builtin"));
        assertEquals("Lyrics", AssetLookup.sources(config, "lyrics").getJSONObject(0).getString("name"));
    }
    @Test public void imageKeysAreBoundToExactOriginAndNeverBuiltinOrCdn() throws Exception {
        JSONObject provider = new JSONObject().put("baseUrl", "https://example.com/api/cover").put("key", "fixture-key");
        Request same = AssetLookup.imageRequest(provider, "https://example.com/image.jpg");
        assertEquals("Bearer fixture-key", same.header("Authorization"));
        assertNull(AssetLookup.imageRequest(provider, "https://cdn.example.com/image.jpg").header("Authorization"));
        assertNull(AssetLookup.imageRequest(provider, "https://example.com:444/image.jpg").header("Authorization"));
        assertNull(AssetLookup.imageRequest(provider.put("builtin", "itunes"), "https://example.com/image.jpg").header("Authorization"));
        assertThrows(IllegalArgumentException.class, () -> AssetLookup.imageRequest(provider, "https://127.0.0.1/image.jpg"));
    }
}
