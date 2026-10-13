package com.aurora.musicnext;

import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import okhttp3.Authenticator;
import okhttp3.Call;
import okhttp3.ConnectionPool;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Streaming GET transport. Signed download URLs live only in this call, never in storage. */
final class AudioHttp {
    interface CallObserver { void starting(Call call) throws IOException; }
    static final class Failure extends IOException {
        final String code;
        Failure(String code) { super(code); this.code = code; }
    }
    private static final int MAX_HOPS = 5;
    // Never reuse an authenticated/private-server socket for an untrusted redirected resource.
    private static final ConnectionPool PUBLIC_POOL = new ConnectionPool();
    private final OkHttpClient scoped, external;
    private final String root;

    AudioHttp(OkHttpClient client, String root) {
        this.root = root;
        scoped = client.newBuilder().followRedirects(false).followSslRedirects(false)
            .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
            .cookieJar(CookieJar.NO_COOKIES).build();
        external = scoped.newBuilder().connectionPool(PUBLIC_POOL)
            .dns(PublicAssetUrl.dns(client.dns())).build();
    }

    Response execute(Request original, CallObserver observer) throws IOException {
        if (!original.method().equals("GET") || original.url().toString().length() > 8192
            || !inside(original.url())) throw new Failure("AUDIO_SCOPE");
        // Empty credentials are not an authentication attempt. Some public audio servers reject the header with 401.
        Request request = "".equals(original.header("Authorization"))
            ? original.newBuilder().removeHeader("Authorization").build() : original;
        Set<String> visited = new HashSet<>();
        boolean detached = false;
        for (int hop = 0; ; hop++) {
            if (!visited.add(request.url().toString())) throw new Failure("AUDIO_REDIRECT_LOOP");
            if (detached) {
                try { PublicAssetUrl.audio(request.url().toString()); }
                catch (IllegalArgumentException error) { throw new Failure("AUDIO_REDIRECT_UNSAFE"); }
                request = request.newBuilder().removeHeader("Authorization").removeHeader("Cookie")
                    .removeHeader("Proxy-Authorization").build();
            }
            Call call = (detached ? external : scoped).newCall(request);
            observer.starting(call); // Register/check cancellation before every hop, including header waits.
            Response response = call.execute();
            if (!redirect(response.code())) return response;
            HttpUrl next;
            try {
                if (hop == MAX_HOPS) throw new Failure("AUDIO_REDIRECT_LOOP");
                String location = response.header("Location");
                if (location == null || location.length() > 8192) throw new Failure("AUDIO_REDIRECT_INVALID");
                next = request.url().resolve(location);
                if (next == null) throw new Failure("AUDIO_REDIRECT_INVALID");
                if (!next.isHttps() || !next.username().isEmpty() || !next.password().isEmpty()
                    || next.fragment() != null || next.toString().length() > 8192)
                    throw new Failure("AUDIO_REDIRECT_UNSAFE");
            } finally { response.close(); }
            // Once outside the configured directory, never restore credentials, even on a bounce back.
            detached |= !inside(next);
            request = request.newBuilder().url(next).build(); // Preserve Range/If-Range/identity.
        }
    }

    static void requireAudioBody(Response response) throws Failure {
        if (response.body() == null) throw new Failure("AUDIO_EMPTY");
        if (!response.header("Content-Encoding", "identity").equalsIgnoreCase("identity"))
            throw new Failure("AUDIO_ENCODING");
        String type = response.header("Content-Type", "").split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (type.equals("text/html") || type.equals("application/xhtml+xml") || type.equals("application/json")
            || type.equals("application/xml") || type.equals("text/xml") || type.endsWith("+json") || type.endsWith("+xml"))
            throw new Failure("AUDIO_NOT_AUDIO");
        // M4A is often video/mp4, application/octet-stream or untyped. Let Media3 sniff these.
    }
    private boolean inside(HttpUrl url) {
        try { ServerScope.requireInside(root, url.toString()); return true; }
        catch (IllegalArgumentException error) { return false; }
    }
    private static boolean redirect(int code) { return code == 301 || code == 302 || code == 303 || code == 307 || code == 308; }
}
