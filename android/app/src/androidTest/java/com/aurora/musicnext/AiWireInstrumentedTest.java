package com.aurora.musicnext;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.Collections;
import okhttp3.Request;
import okio.Buffer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Native protocol checks use fixture keys, without contacting any provider. */
@RunWith(AndroidJUnit4.class)
public class AiWireInstrumentedTest {
    private JSONObject provider(String protocol) throws Exception {
        return new JSONObject().put("protocol", protocol).put("baseUrl", "https://example.com/v1").put("model", "fixture-model").put("key", "fixture");
    }
    @Test public void responsesUsesStatelessInputAndCorrectEndpoint() throws Exception {
        Request request = AiWire.request(provider("responses"), "system", "user"); Buffer buffer = new Buffer(); request.body().writeTo(buffer);
        JSONObject body = new JSONObject(buffer.readUtf8());
        assertEquals("https://example.com/v1/responses", request.url().toString()); assertFalse(body.getBoolean("store"));
        assertEquals("system", body.getString("instructions")); assertEquals("user", body.getString("input")); assertFalse(body.has("messages"));
    }
    @Test public void rawOutputIgnoresReasoningAndRejectsIncompleteResponses() throws Exception {
        JSONObject payload = new JSONObject().put("status", "completed").put("output", new JSONArray()
            .put(new JSONObject().put("type", "reasoning"))
            .put(new JSONObject().put("type", "message").put("role", "assistant").put("content", new JSONArray()
                .put(new JSONObject().put("type", "output_text").put("text", "{\"ok\":true}")))));
        assertTrue(AiWire.parse("responses", payload).getBoolean("ok"));
        try { AiWire.parse("responses", payload.put("status", "incomplete")); fail("Incomplete response must fail"); }
        catch (IllegalArgumentException expected) { }
    }
    @Test public void untrustedIdsAndUnsafeUrlsAreRejected() throws Exception {
        try { AiWire.validate(new JSONObject().put("songIds", new JSONArray().put("invented")), "select", Collections.singleton("real")); fail("Foreign ID must fail"); }
        catch (IllegalArgumentException expected) { }
        String credentialUrl = okhttp3.HttpUrl.get("https://example.com/v1").newBuilder().username("fixture-user").password("fixture-pass").build().toString();
        for (String url : new String[]{"http://example.com/v1", credentialUrl, "https://example.com/v1?key=fixture"}) {
            try { AiWire.normalizeUrl(url); fail("Unsafe URL must fail"); } catch (IllegalArgumentException expected) { }
        }
        assertEquals("http://127.0.0.1:8080/v1", AiWire.normalizeUrl("http://127.0.0.1:8080/v1"));
    }
}
