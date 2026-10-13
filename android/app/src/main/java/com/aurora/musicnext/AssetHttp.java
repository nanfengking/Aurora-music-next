package com.aurora.musicnext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.Call;
import okhttp3.ConnectionPool;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** One foreground metadata operation, bounded even across providers and lyric fallback tiers. */
final class AssetHttp {
    private final AtomicLong generation = new AtomicLong();
    private volatile Call active;
    private final OkHttpClient client;
    AssetHttp() { this(new OkHttpClient.Builder().dns(PublicAssetUrl.dns(Dns.SYSTEM)).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectionPool(new ConnectionPool(2, 1, TimeUnit.MINUTES))
        .connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()); }
    AssetHttp(OkHttpClient client) { this.client = client; }
    final class Operation {
        private final long ticket = generation.get(), deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        void check() throws IOException { if (ticket != generation.get()) throw new IOException("Resource operation cancelled"); if (System.nanoTime() >= deadline) throw new IOException("Resource operation timed out"); }
        long remainingMillis() throws IOException { check(); return Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())); }
    }
    Operation begin() { return new Operation(); }
    void cancel() { generation.incrementAndGet(); Call call = active; if (call != null) call.cancel(); client.connectionPool().evictAll(); }
    byte[] get(Operation operation, Request request, int limit) throws IOException {
        PublicAssetUrl.image(request.url().toString());
        Call call = client.newBuilder().callTimeout(Math.min(12_000, operation.remainingMillis()), TimeUnit.MILLISECONDS).build().newCall(request);
        active = call;
        try {
            operation.check();
            try (Response response = call.execute()) {
                if (!response.isSuccessful() || response.body() == null) throw new IOException("Resource HTTP " + response.code());
                if (response.body().contentLength() > limit) throw new IOException("Resource response too large");
                try (InputStream input = response.body().byteStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = input.read(buffer)) != -1) {
                        operation.check(); if (bytes.size() > limit - count) throw new IOException("Resource response too large");
                        bytes.write(buffer, 0, count);
                    }
                    operation.check(); return bytes.toByteArray();
                }
            }
        } finally { if (active == call) active = null; }
    }
}
