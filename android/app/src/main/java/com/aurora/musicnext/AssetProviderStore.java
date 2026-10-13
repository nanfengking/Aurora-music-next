package com.aurora.musicnext;

import android.content.Context;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Each resource endpoint has its own native-only key; origin changes never reuse a saved key. */
final class AssetProviderStore {
    private final SecretStore secrets;
    AssetProviderStore(Context context) { secrets = new SecretStore(context); }
    JSONObject stored() throws Exception {
        synchronized (AssetProviderStore.class) {
            String text = secrets.protectedValue("asset_providers");
            return text.isEmpty() ? new JSONObject().put("providers", new JSONArray()).put("itunesEnabled", false).put("revision", "") : new JSONObject(text);
        }
    }
    JSONObject status() throws Exception { return publicStatus(stored()); }
    static JSONObject publicStatus(JSONObject config) throws Exception {
        JSONArray list = config.getJSONArray("providers"), result = new JSONArray();
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.getJSONObject(i);
            result.put(new JSONObject().put("id", item.getString("id")).put("name", item.getString("name"))
                .put("kind", item.getString("kind")).put("baseUrl", item.getString("baseUrl")).put("priority", i + 1)
                .put("enabled", item.getBoolean("enabled")).put("configured", !item.optString("key").isEmpty()));
        }
        return new JSONObject().put("providers", result).put("itunesEnabled", config.optBoolean("itunesEnabled"));
    }
    static JSONArray normalize(JSONArray input, JSONArray previous) throws Exception {
        if (input == null || input.length() > 10) throw new IllegalArgumentException("At most 10 resource services are allowed");
        Map<String, JSONObject> old = new HashMap<>(); Set<String> ids = new HashSet<>(); JSONArray result = new JSONArray();
        for (int i = 0; i < previous.length(); i++) old.put(previous.getJSONObject(i).getString("id"), previous.getJSONObject(i));
        for (int i = 0; i < input.length(); i++) {
            JSONObject item = input.getJSONObject(i); String id = item.optString("id");
            if (!id.matches("[\\w-]{1,80}") || !ids.add(id)) throw new IllegalArgumentException("Invalid resource service ID");
            String kind = AssetWire.kind(item.optString("kind")), url = PublicAssetUrl.endpoint(item.optString("baseUrl"));
            String key = item.optString("apiKey", "").trim(), name = item.optString("name", "API").trim();
            if (key.length() > 4096 || key.contains("\r") || key.contains("\n")) throw new IllegalArgumentException("Invalid resource key");
            JSONObject before = old.get(id);
            if (key.isEmpty() && before != null && !before.optString("key").isEmpty() && !url.equals(before.optString("baseUrl")))
                throw new IllegalArgumentException("Re-enter the resource key after changing the URL");
            if (key.isEmpty() && before != null) key = before.optString("key");
            if (name.isEmpty()) name = "API";
            result.put(new JSONObject().put("id", id).put("name", name.substring(0, Math.min(60, name.length())))
                .put("kind", kind).put("baseUrl", url).put("key", key).put("enabled", item.has("enabled") ? item.getBoolean("enabled") : true));
        }
        return result;
    }
    JSONObject save(JSONArray input, boolean itunesEnabled) throws Exception {
        synchronized (AssetProviderStore.class) {
            JSONObject before = stored(); JSONArray list = normalize(input, before.getJSONArray("providers"));
            boolean changed = !list.toString().equals(before.getJSONArray("providers").toString()) || itunesEnabled != before.optBoolean("itunesEnabled");
            JSONObject next = new JSONObject().put("providers", list).put("itunesEnabled", itunesEnabled)
                .put("revision", changed ? java.util.UUID.randomUUID().toString() : before.optString("revision"));
            if (changed) secrets.saveProtected("asset_providers", next.toString());
            return publicStatus(next).put("changed", changed);
        }
    }
}
