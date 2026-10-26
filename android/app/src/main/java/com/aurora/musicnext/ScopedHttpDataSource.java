package com.aurora.musicnext;

import android.content.Context;
import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSpec;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

@UnstableApi
final class ScopedHttpDataSource extends BaseDataSource {
    static final OkHttpClient HTTP = new OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build();
    private final SecretStore secrets;
    private final SecretStore.Connection boundConnection;
    private long prefetchTotal = -1;
    private volatile boolean cancelled;
    private final AtomicInteger generation = new AtomicInteger();
    private final AtomicReference<Call> activeCall = new AtomicReference<>();
    private Response response;
    private InputStream stream;
    private Uri uri;
    private long remaining;
    private boolean opened;
    private CancellationGroup cancellations;
    private CancellationGroup.Registration registration;

    ScopedHttpDataSource(Context context) { super(true); secrets = new SecretStore(context); boundConnection = null; }
    ScopedHttpDataSource(SecretStore.Connection connection) { super(true); secrets = null; boundConnection = connection; }
    ScopedHttpDataSource(SecretStore.Connection connection, long prefetchTotal) { this(connection); this.prefetchTotal = prefetchTotal; }
    ScopedHttpDataSource(Context context, CancellationGroup group) { this(context); cancellations = group; }
    ScopedHttpDataSource(SecretStore.Connection connection, CancellationGroup group) { this(connection); cancellations = group; }
    /** Cancels the socket without concurrently closing a cache/sink that belongs to its I/O thread. */
    void cancel() {
        cancelled = true; generation.incrementAndGet();
        Call call = activeCall.getAndSet(null); if (call != null) call.cancel();
    }

    @Override public long open(DataSpec spec) throws IOException {
        if (cancellations != null) registration = cancellations.bind(this::cancel);
        transferInitializing(spec);
        int epoch = generation.incrementAndGet();
        try {
            if (cancelled) throw new AudioHttp.Failure("AUDIO_CANCELLED");
            String address = spec.uri.toString();
            SecretStore.Connection connection = boundConnection == null ? secrets.connection() : boundConnection;
            Request.Builder request = new Request.Builder().url(address)
                .header("Authorization", connection.authorizationFor(address))
                .header("Accept-Encoding", "identity");
            if (spec.position > 0 || spec.length != C.LENGTH_UNSET)
                request.header("Range", AudioRange.header(spec.position, spec.length));
            Response candidate = new AudioHttp(HTTP, connection.root).execute(request.build(), call -> {
                activeCall.set(call);
                if (cancelled || generation.get() != epoch) { call.cancel(); throw new AudioHttp.Failure("AUDIO_CANCELLED"); }
            });
            synchronized (this) {
                if (generation.get() != epoch) { candidate.close(); throw new AudioHttp.Failure("AUDIO_CANCELLED"); }
                response = candidate;
            }
            if (candidate.code() != 200 && candidate.code() != 206) throw new AudioHttp.Failure("AUDIO_HTTP_" + candidate.code());
            AudioHttp.requireAudioBody(candidate);
            remaining = AudioRange.remaining(candidate.code(), candidate.header("Content-Range", ""),
                candidate.body().contentLength(), spec.position, spec.length);
            if (prefetchTotal >= 0 && AudioRange.total(candidate.code(), candidate.header("Content-Range", ""), candidate.body().contentLength()) != prefetchTotal)
                throw new AudioHttp.Failure("AUDIO_CHANGED");
            InputStream input = candidate.body().byteStream();
            long skipped = candidate.code() == 200 ? spec.position : 0;
            long toSkip = skipped;
            byte[] buffer = new byte[8192];
            while (toSkip > 0) {
                if (generation.get() != epoch) throw new AudioHttp.Failure("AUDIO_CANCELLED");
                int count = input.read(buffer, 0, (int) Math.min(buffer.length, toSkip));
                if (count == -1) throw new AudioHttp.Failure("AUDIO_INCOMPLETE");
                toSkip -= count;
            }
            synchronized (this) {
                if (generation.get() != epoch) throw new AudioHttp.Failure("AUDIO_CANCELLED");
                // CacheDataSource must not persist a temporary signed CDN URL as a redirect/cache identity.
                stream = input; uri = spec.uri; opened = true; transferStarted(spec);
                return remaining;
            }
        } catch (Exception error) {
            close();
            if (error instanceof IOException) throw (IOException) error;
            throw new AudioHttp.Failure("AUDIO_SCOPE");
        }
    }

    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (remaining == 0) return C.RESULT_END_OF_INPUT;
        InputStream input = stream;
        if (input == null) throw new AudioHttp.Failure("AUDIO_CANCELLED");
        int count = input.read(buffer, offset, remaining < 0 ? length : (int) Math.min(length, remaining));
        if (count == -1) {
            if (remaining > 0) throw new AudioHttp.Failure("AUDIO_INCOMPLETE");
            return C.RESULT_END_OF_INPUT;
        }
        if (remaining > 0) remaining -= count;
        bytesTransferred(count);
        return count;
    }
    @Override public Uri getUri() { return uri; }
    @Override public Map<String, List<String>> getResponseHeaders() {
        return response == null ? Collections.emptyMap() : response.headers().toMultimap();
    }
    @Override public synchronized void close() {
        if (registration != null) { registration.close(); registration = null; }
        generation.incrementAndGet();
        Call call = activeCall.getAndSet(null);
        if (call != null) call.cancel();
        if (response != null) response.close();
        response = null; stream = null; uri = null;
        if (opened) { opened = false; transferEnded(); }
    }
}
