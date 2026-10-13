package com.aurora.musicnext;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;
import static org.junit.Assert.*;

/** Redirect/header policy fixtures, not proof of a real provider's behavior. */
public class AudioHttpTest {
    private static final String ROOT = "https://origin.example/music/";
    private Request request() { return new Request.Builder().url(ROOT + "song.m4a")
        .header("Authorization", "Basic fixture").header("Cookie", "fixture=1").header("Proxy-Authorization", "fixture")
        .header("Range", "bytes=4-").header("If-Range", "\"fixture-v1\"").header("Accept-Encoding", "identity").build(); }
    private static Response response(Request request, int code, String location, String type) {
        Response.Builder builder = new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
            .body(ResponseBody.create("abcd", MediaType.parse(type))).header("Content-Type", type);
        if (location != null) builder.header("Location", location);
        return builder.build();
    }
    @Test public void anonymousAudioOmitsAnEmptyAuthorizationHeader() throws Exception {
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            Request outgoing = chain.request();
            // Public audio may reject a present but invalid/empty credential instead of falling back to anonymous access.
            return response(outgoing, outgoing.header("Authorization") == null ? 200 : 401, null, "audio/mp4");
        }).build();
        Request anonymous = request().newBuilder().header("Authorization", "").build();
        try (Response result = new AudioHttp(client, ROOT).execute(anonymous, call -> {})) { assertEquals(200, result.code()); }
    }
    @Test public void sameDirectoryRedirectPreservesAuthAndRangeForAllSupportedStatuses() throws Exception {
        for (int code : new int[]{301, 302, 303, 307, 308}) {
            List<Request> seen = new ArrayList<>();
            OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
                Request request = chain.request(); seen.add(request);
                return response(request, seen.size() == 1 ? code : 206, seen.size() == 1 ? "next.m4a?version=2" : null, "video/mp4");
            }).build();
            try (Response result = new AudioHttp(client, ROOT).execute(request(), call -> {})) { assertEquals(206, result.code()); }
            assertEquals(2, seen.size()); assertEquals("Basic fixture", seen.get(1).header("Authorization"));
            assertEquals("bytes=4-", seen.get(1).header("Range")); assertEquals("\"fixture-v1\"", seen.get(1).header("If-Range"));
            assertEquals("identity", seen.get(1).header("Accept-Encoding"));
        }
    }
    @Test public void crossOriginSignedUrlStripsCredentialsAndNeverRestoresThemOnBounceBack() throws Exception {
        List<Request> seen = new ArrayList<>();
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            Request value = chain.request(); seen.add(value);
            return seen.size() == 1 ? response(value, 302, "https://cdn.example/music.m4a?signature=fixture", "text/html")
                : seen.size() == 2 ? response(value, 307, ROOT + "returned.m4a", "text/html") : response(value, 200, null, "audio/mp4");
        }).build();
        try (Response result = new AudioHttp(client, ROOT).execute(request(), call -> {})) { AudioHttp.requireAudioBody(result); }
        assertEquals(3, seen.size());
        for (Request value : seen.subList(1, seen.size())) {
            assertNull(value.header("Authorization")); assertNull(value.header("Cookie")); assertNull(value.header("Proxy-Authorization"));
            assertEquals("bytes=4-", value.header("Range")); assertEquals("\"fixture-v1\"", value.header("If-Range"));
        }
    }
    @Test public void sameOriginOutsideConfiguredDirectoryAlsoLosesCredentials() throws Exception {
        List<Request> seen = new ArrayList<>();
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            seen.add(chain.request()); return response(chain.request(), seen.size() == 1 ? 302 : 200,
                seen.size() == 1 ? "/download/signed.m4a" : null, "application/octet-stream");
        }).build();
        try (Response ignored = new AudioHttp(client, ROOT).execute(request(), call -> {})) { assertNull(seen.get(1).header("Authorization")); }
    }
    @Test public void unsafeOrInvalidRedirectIsRejectedBeforeAnotherRequest() throws Exception {
        for (String location : new String[]{null, "http://cdn.example/song", "https://127.0.0.1/song", "https://10.0.0.1/song",
            "https://[::1]/song", "https://cdn.example/song#fragment",
            HttpUrl.get("https://cdn.example/song").newBuilder().username("fixture").password("fixture").build().toString()}) {
            AtomicInteger calls = new AtomicInteger();
            OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> { calls.incrementAndGet(); return response(chain.request(), 302, location, "text/html"); }).build();
            AudioHttp.Failure error = assertThrows(AudioHttp.Failure.class, () -> new AudioHttp(client, ROOT).execute(request(), call -> {}));
            assertTrue(error.code.equals("AUDIO_REDIRECT_UNSAFE") || error.code.equals("AUDIO_REDIRECT_INVALID"));
            assertEquals(1, calls.get()); assertFalse(error.getMessage().contains("fixture"));
        }
    }
    @Test public void loopAndExcessiveHopsAreBounded() throws Exception {
        for (boolean loop : new boolean[]{true, false}) {
            AtomicInteger calls = new AtomicInteger();
            OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> response(chain.request(), 302,
                loop ? ROOT + "song.m4a" : ROOT + "hop-" + calls.incrementAndGet(), "text/html")).build();
            AudioHttp.Failure error = assertThrows(AudioHttp.Failure.class, () -> new AudioHttp(client, ROOT).execute(request(), call -> {}));
            assertEquals("AUDIO_REDIRECT_LOOP", error.code); assertTrue(calls.get() <= 6);
        }
    }
    @Test public void initialUrlCannotBypassScopeEvenIfUntrustedClientWouldFollowRedirects() {
        AtomicInteger calls = new AtomicInteger();
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> { calls.incrementAndGet(); return response(chain.request(), 200, null, "audio/mp4"); }).build();
        Request outside = request().newBuilder().url("https://cdn.example/song.m4a").build();
        assertEquals("AUDIO_SCOPE", assertThrows(AudioHttp.Failure.class, () -> new AudioHttp(client, ROOT).execute(outside, call -> {})).code);
        assertEquals(0, calls.get());
    }
    @Test public void redirectedDnsRejectsPrivateAndMixedResolutionBeforeAnySocket() throws Exception {
        InetAddress publicIp = InetAddress.getByName("8.8.8.8"), privateIp = InetAddress.getByName("127.0.0.1");
        for (List<InetAddress> addresses : Arrays.asList(Collections.singletonList(privateIp), Arrays.asList(publicIp, privateIp))) {
            AtomicInteger lookups = new AtomicInteger();
            OkHttpClient client = new OkHttpClient.Builder().dns(host -> { lookups.incrementAndGet(); return addresses; })
                .retryOnConnectionFailure(false).addInterceptor(chain -> chain.request().url().host().equals("origin.example")
                    ? response(chain.request(), 302, "https://cdn.example/song.m4a", "text/html") : chain.proceed(chain.request())).build();
            assertThrows(UnknownHostException.class, () -> new AudioHttp(client, ROOT).execute(request(), call -> {}));
            assertEquals(1, lookups.get());
        }
    }
    @Test public void cancellationIsCheckedBeforeEveryRedirectHop() {
        AtomicInteger calls = new AtomicInteger(), starts = new AtomicInteger();
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> { calls.incrementAndGet(); return response(chain.request(), 302, "next.m4a", "text/html"); }).build();
        assertThrows(AudioHttp.Failure.class, () -> new AudioHttp(client, ROOT).execute(request(), call -> {
            if (starts.incrementAndGet() == 2) { call.cancel(); throw new AudioHttp.Failure("AUDIO_CANCELLED"); }
        }));
        assertEquals(2, starts.get()); assertEquals(1, calls.get());
    }
    @Test public void htmlJsonXmlAndEncodedBodiesDoNotMasqueradeAsAudio() throws Exception {
        for (String type : new String[]{"text/html; charset=utf-8", "application/problem+json", "text/xml", "application/xhtml+xml"}) {
            try (Response response = response(request(), 200, null, type)) {
                assertEquals("AUDIO_NOT_AUDIO", assertThrows(AudioHttp.Failure.class, () -> AudioHttp.requireAudioBody(response)).code);
            }
        }
        for (String type : new String[]{"audio/mp4", "video/mp4", "application/octet-stream"}) {
            try (Response response = response(request(), 200, null, type)) { AudioHttp.requireAudioBody(response); }
        }
        try (Response response = response(request(), 200, null, "audio/mp4").newBuilder().header("Content-Encoding", "gzip").build()) {
            assertEquals("AUDIO_ENCODING", assertThrows(AudioHttp.Failure.class, () -> AudioHttp.requireAudioBody(response)).code);
        }
    }
}
