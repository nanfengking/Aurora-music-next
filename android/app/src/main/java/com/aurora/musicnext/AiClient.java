package com.aurora.musicnext;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native-only credential handling; sequential bounded failover, no redirects or automatic retries. */
final class AiClient {
    private final AiProviderStore providers;
    private final AtomicLong cancellation = new AtomicLong();
    private volatile Call activeCall;
    private final OkHttpClient client = new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(8, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build();
    AiClient(Context context) { providers = new AiProviderStore(context); }
    void cancel() { cancellation.incrementAndGet(); Call call = activeCall; if (call != null) call.cancel(); }
    JSONObject request(String stage, String system, String user, String providerId, long timeoutMillis) throws Exception {
        if (!java.util.Arrays.asList("plan", "select", "test").contains(stage) || system.length() > 8000 || user.length() > 200_000)
            throw new IllegalArgumentException("Invalid AI request");
        Set<String> allowed = new HashSet<>();
        if (stage.equals("select")) {
            JSONArray catalog = new JSONObject(user).getJSONArray("catalog");
            if (catalog.length() > 240) throw new IllegalArgumentException("Candidate catalog is too large");
            for (int i = 0; i < catalog.length(); i++) allowed.add(catalog.getJSONObject(i).getString("id"));
        }
        long ticket = cancellation.get(), deadline = android.os.SystemClock.elapsedRealtime() + Math.max(1, Math.min(90_000, timeoutMillis));
        JSONArray stored = providers.stored(), attempts = new JSONArray();
        for (int i = 0; i < stored.length(); i++) {
            if (ticket != cancellation.get()) throw new IOException("AI request cancelled");
            JSONObject provider = stored.getJSONObject(i);
            if (!providerId.isEmpty() && !providerId.equals(provider.optString("id"))) continue;
            if ((providerId.isEmpty() && !provider.optBoolean("enabled")) || provider.optString("key").isEmpty()) continue;
            long remaining = deadline - android.os.SystemClock.elapsedRealtime(); if (remaining <= 0) break;
            String reason = "Timeout, network failure or invalid response";
            try {
                OkHttpClient scoped = client.newBuilder().callTimeout(Math.min(30_000, remaining), TimeUnit.MILLISECONDS).build();
                Call call = scoped.newCall(AiWire.request(provider, system, user)); activeCall = call;
                if (ticket != cancellation.get()) { call.cancel(); throw new IOException("AI request cancelled"); }
                try (Response response = call.execute()) {
                    if (!response.isSuccessful()) { reason = "HTTP " + response.code(); throw new IOException(reason); }
                    JSONObject value = AiWire.parse(provider.getString("protocol"), new JSONObject(read(response)));
                    AiWire.validate(value, stage, allowed);
                    if (ticket != cancellation.get()) throw new IOException("AI request cancelled");
                    return new JSONObject().put("value", value).put("providerName", provider.getString("name")).put("attempts", attempts);
                }
            } catch (Exception ignored) {
                // Do not surface URLs, response bodies, request headers or exceptions containing a key.
                if (ticket != cancellation.get()) throw new IOException("AI request cancelled");
                attempts.put(provider.optString("name") + ": " + reason);
            } finally { activeCall = null; }
        }
        throw new IOException("No valid API response. " + attempts);
    }
    private static String read(Response response) throws IOException {
        final int limit = 2_000_000;
        if (response.body() == null || response.body().contentLength() > limit) throw new IOException("Invalid response size");
        try (InputStream input = response.body().byteStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() > limit - count) throw new IOException("Response too large");
                bytes.write(buffer, 0, count);
            }
            return bytes.toString(StandardCharsets.UTF_8.name());
        }
    }
}
