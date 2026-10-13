package com.aurora.musicnext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import org.junit.Test;
import static org.junit.Assert.*;

/** In-memory interceptor responses: tests byte/cancel guards, never a real service or network. */
public class AssetHttpTest {
    private Request request() { return new Request.Builder().url("https://example.com/fixture").build(); }
    @Test public void boundedBodiesRejectBothDeclaredAndUnknownLengths() throws Exception {
        for (boolean known : new boolean[]{true, false}) {
            AssetHttp http = new AssetHttp(new OkHttpClient.Builder().addInterceptor(chain -> new Response.Builder().request(chain.request())
                .protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(new ResponseBody() {
                    private final Buffer data = new Buffer().write(new byte[65]);
                    public okhttp3.MediaType contentType() { return null; }
                    public long contentLength() { return known ? 65 : -1; }
                    public BufferedSource source() { return data; }
                }).build()).build());
            assertThrows(IOException.class, () -> http.get(http.begin(), request(), 64));
        }
    }
    @Test public void cancelledOperationsMakeNoRequestAndNewOperationsCanRun() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AssetHttp http = new AssetHttp(new OkHttpClient.Builder().addInterceptor(chain -> {
            calls.incrementAndGet(); return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("fixture").body(ResponseBody.create("fixture", null)).build();
        }).build());
        AssetHttp.Operation old = http.begin(); http.cancel();
        assertThrows(IOException.class, () -> http.get(old, request(), 64)); assertEquals(0, calls.get());
        assertEquals("fixture", new String(http.get(http.begin(), request(), 64), StandardCharsets.UTF_8)); assertEquals(1, calls.get());
    }
}
