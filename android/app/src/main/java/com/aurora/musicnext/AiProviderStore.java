package com.aurora.musicnext;

import android.content.Context;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

final class AiProviderStore {
    private final SecretStore secrets;
    AiProviderStore(Context context) { secrets = new SecretStore(context); }
    JSONArray stored() throws Exception {
        synchronized (AiProviderStore.class) {
            String text = secrets.protectedValue("ai_providers");
            return text.isEmpty() ? new JSONArray() : new JSONArray(text);
        }
    }
    JSONArray publicList() throws Exception {
        synchronized (AiProviderStore.class) {
            JSONArray result = new JSONArray(), stored = stored();
            for (int i = 0; i < stored.length(); i++) {
                JSONObject item = stored.getJSONObject(i);
                result.put(new JSONObject().put("id", item.getString("id")).put("name", item.getString("name"))
                    .put("protocol", item.getString("protocol")).put("baseUrl", item.getString("baseUrl")).put("model", item.getString("model"))
                    .put("enabled", item.optBoolean("enabled")).put("priority", i + 1).put("configured", !item.optString("key").isEmpty()));
            }
            return result;
        }
    }
    JSONArray save(JSONArray input) throws Exception {
        synchronized (AiProviderStore.class) {
            if (input == null || input.length() > 10) throw new IllegalArgumentException("At most 10 API services are allowed");
            Map<String, JSONObject> previous = new HashMap<>(); JSONArray old = stored();
            for (int i = 0; i < old.length(); i++) previous.put(old.getJSONObject(i).getString("id"), old.getJSONObject(i));
            JSONArray result = new JSONArray(); Set<String> ids = new HashSet<>();
            for (int i = 0; i < input.length(); i++) {
                JSONObject item = input.getJSONObject(i); String id = item.optString("id", "");
                if (!id.matches("[\\w-]{1,80}") || !ids.add(id)) throw new IllegalArgumentException("Invalid or duplicate API ID");
                String protocol = item.optString("protocol"), model = item.optString("model").trim();
                if (!java.util.Arrays.asList("openai", "responses", "anthropic", "gemini").contains(protocol)) throw new IllegalArgumentException("Unsupported API protocol");
                if (model.isEmpty() || model.length() > 120 || !model.matches("[\\w./:@-]+")) throw new IllegalArgumentException("Enter the actual model ID supplied by the service");
                String url = AiWire.normalizeUrl(item.optString("baseUrl")), key = item.optString("apiKey", "").trim();
                if (key.length() > 4096 || key.contains("\r") || key.contains("\n")) throw new IllegalArgumentException("Invalid API key");
                JSONObject before = previous.get(id);
                if (key.isEmpty() && before != null && !before.optString("key").isEmpty() && !url.equals(before.optString("baseUrl")))
                    throw new IllegalArgumentException("Re-enter the API key after changing its URL");
                if (key.isEmpty() && before != null) key = before.optString("key");
                String name = item.optString("name", "API").trim(); if (name.isEmpty()) name = "API";
                result.put(new JSONObject().put("id", id).put("name", name.substring(0, Math.min(60, name.length())))
                    .put("protocol", protocol).put("baseUrl", url).put("model", model).put("priority", i + 1)
                    .put("enabled", item.optBoolean("enabled")).put("key", key));
            }
            secrets.saveProtected("ai_providers", result.toString()); return publicList();
        }
    }
}
