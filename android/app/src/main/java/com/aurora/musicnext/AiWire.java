package com.aurora.musicnext;

import java.util.HashSet;
import java.util.Set;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.json.JSONArray;
import org.json.JSONObject;

/** Four explicit protocols. Never guesses a model or silently changes the chosen protocol. */
final class AiWire {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    static String normalizeUrl(String address) {
        HttpUrl url = HttpUrl.parse(address.trim());
        if (url == null || !url.username().isEmpty() || !url.password().isEmpty() || url.query() != null || url.fragment() != null)
            throw new IllegalArgumentException("API URL cannot contain credentials, query parameters or fragments");
        boolean local = url.host().equals("localhost") || url.host().equals("127.0.0.1") || url.host().equals("::1");
        if (!url.isHttps() && !local) throw new IllegalArgumentException("API requires HTTPS; HTTP is allowed only on localhost");
        return url.toString().replaceAll("/$", "");
    }
    static Request request(JSONObject provider, String system, String user) throws Exception {
        String protocol = provider.getString("protocol"), base = provider.getString("baseUrl");
        String model = provider.getString("model"), key = provider.getString("key");
        JSONObject body = new JSONObject().put("model", model);
        Request.Builder request = new Request.Builder().header("Content-Type", "application/json")
            .header("User-Agent", "AuroraMusicNext/Android (playlist)");
        String endpoint;
        switch (protocol) {
            case "responses":
                endpoint = base.endsWith("/responses") ? base : base + "/responses";
                request.header("Authorization", "Bearer " + key);
                body.put("instructions", system).put("input", user).put("store", false).put("stream", false).put("max_output_tokens", 4096);
                break;
            case "anthropic":
                endpoint = base.endsWith("/messages") ? base : (base.endsWith("/v1") ? base : base + "/v1") + "/messages";
                request.header("x-api-key", key).header("anthropic-version", "2023-06-01");
                body.put("max_tokens", 4096).put("system", system).put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", user)));
                break;
            case "gemini":
                String geminiModel = model.replaceFirst("^models/", "");
                HttpUrl.Builder url = HttpUrl.get(base.matches(".*/v1(beta)?$") ? base : base + "/v1beta").newBuilder();
                url.addPathSegment("models").addPathSegment(geminiModel + ":generateContent");
                endpoint = url.build().toString(); request.header("x-goog-api-key", key);
                body.remove("model");
                body.put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", system))))
                    .put("contents", new JSONArray().put(new JSONObject().put("role", "user").put("parts", new JSONArray().put(new JSONObject().put("text", user)))))
                    .put("generationConfig", new JSONObject().put("responseMimeType", "application/json"));
                break;
            case "openai":
                endpoint = base.endsWith("/chat/completions") ? base : base + "/chat/completions";
                request.header("Authorization", "Bearer " + key);
                body.put("messages", new JSONArray().put(new JSONObject().put("role", "system").put("content", system))
                    .put(new JSONObject().put("role", "user").put("content", user)));
                break;
            default: throw new IllegalArgumentException("Unsupported API protocol");
        }
        return request.url(endpoint).post(RequestBody.create(body.toString(), JSON)).build();
    }
    static JSONObject parse(String protocol, JSONObject payload) throws Exception {
        StringBuilder text = new StringBuilder();
        if (protocol.equals("responses")) {
            if (!payload.isNull("error") || (payload.has("status") && !payload.optString("status").equals("completed")))
                throw new IllegalArgumentException("Response is incomplete");
            JSONArray output = payload.optJSONArray("output");
            if (output == null) text.append(payload.optString("output_text", ""));
            else for (int i = 0; i < output.length(); i++) {
                JSONObject item = output.optJSONObject(i);
                if (item == null || !item.optString("type").equals("message") || !item.optString("role").equals("assistant")) continue;
                appendText(text, item.optJSONArray("content"), "output_text");
            }
        } else if (protocol.equals("anthropic")) appendText(text, payload.optJSONArray("content"), "text");
        else if (protocol.equals("gemini")) {
            JSONObject candidate = payload.getJSONArray("candidates").getJSONObject(0).getJSONObject("content");
            appendText(text, candidate.optJSONArray("parts"), "gemini");
        } else text.append(payload.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"));
        int first = text.indexOf("{"), last = text.lastIndexOf("}");
        if (first < 0 || last < first) throw new IllegalArgumentException("No JSON object in response");
        return new JSONObject(text.substring(first, last + 1));
    }
    private static void appendText(StringBuilder text, JSONArray parts, String type) {
        if (parts == null) return;
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i);
            if (part != null && (type.equals("gemini") ? !part.optBoolean("thought") : part.optString("type").equals(type)))
                text.append(part.isNull("text") ? "" : part.optString("text", ""));
        }
    }
    static void validate(JSONObject value, String stage, Set<String> allowed) throws Exception {
        if (stage.equals("plan")) {
            if (!java.util.Arrays.asList("any", "run", "focus", "sleep", "relax").contains(value.optString("scene")) || value.optJSONArray("keywords") == null)
                throw new IllegalArgumentException("Invalid scene plan");
        } else if (stage.equals("select")) {
            JSONArray ids = value.optJSONArray("songIds");
            Set<String> accepted = new HashSet<>();
            if (ids != null) for (int i = 0; i < ids.length() && accepted.size() < 80; i++) {
                Object id = ids.opt(i); if (id instanceof String && allowed.contains(id)) accepted.add((String) id);
            }
            if (accepted.isEmpty()) throw new IllegalArgumentException("No valid library song selected");
        } else if (!stage.equals("test") || !value.optBoolean("ok")) throw new IllegalArgumentException("Invalid API test response");
    }
}
