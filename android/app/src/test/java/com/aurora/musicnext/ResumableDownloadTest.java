package com.aurora.musicnext;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Real loopback HTTP; no remote service, WebDAV account or user music is touched. */
public class ResumableDownloadTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();
    private final OkHttpClient client = new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).readTimeout(3, TimeUnit.SECONDS).build();
    private File partial() { return ResumableDownload.partial(files.getRoot(), "0123456789abcdef01234567"); }
    private static final class Sink implements ResumableDownload.Listener {
        boolean accept = true, stopped; long bytes, total; int complete;
        public boolean stopped() { return stopped; }
        public void progress(long value, long expected) { bytes = value; total = expected; }
        public boolean complete(File file, long bytes) { complete++; return accept; }
    }
    private static final class Reply {
        final String status, headers, body; boolean hold;
        Reply(int status, long length, String headers, String body) {
            this.status = Integer.toString(status); this.headers = "Content-Length: " + length + "\r\n" + headers;
            this.body = body;
        }
    }
    private static final class Server implements AutoCloseable {
        final ServerSocket server;
        final BlockingQueue<Reply> replies = new LinkedBlockingQueue<>();
        final BlockingQueue<Map<String, String>> requests = new LinkedBlockingQueue<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final Thread thread; volatile Socket socket; volatile boolean closed;
        Server() throws IOException {
            server = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"));
            thread = new Thread(() -> {
                try {
                    while (!closed) {
                        try (Socket active = server.accept()) {
                            socket = active; active.setSoTimeout(5000);
                            BufferedReader input = new BufferedReader(new InputStreamReader(active.getInputStream(), StandardCharsets.US_ASCII));
                            Map<String, String> headers = new HashMap<>(); String line = input.readLine(); headers.put("request", line);
                            while ((line = input.readLine()) != null && !line.isEmpty()) {
                                int separator = line.indexOf(':');
                                if (separator >= 0) headers.put(line.substring(0, separator).toLowerCase(java.util.Locale.ROOT), line.substring(separator + 1).trim());
                            }
                            Reply reply = replies.poll(5, TimeUnit.SECONDS);
                            if (reply == null) throw new IOException("Unexpected fixture request");
                            active.getOutputStream().write(("HTTP/1.1 " + reply.status + " Fixture\r\nConnection: close\r\n" + reply.headers + "\r\n" + reply.body).getBytes(StandardCharsets.US_ASCII));
                            active.getOutputStream().flush(); requests.add(headers);
                            if (reply.hold) active.getInputStream().read(); // Cancellation must interrupt a blocked response body.
                        } finally { socket = null; }
                    }
                } catch (Throwable failure) { if (!closed) error.set(failure); }
            }, "aurora-http-fixture");
            thread.setDaemon(true); thread.start();
        }
        String url() { return "http://127.0.0.1:" + server.getLocalPort() + "/music.m4a"; }
        void add(int status, long length, String headers, String body) { replies.add(new Reply(status, length, headers, body)); }
        Map<String, String> request() throws InterruptedException { Map<String, String> result = requests.poll(5, TimeUnit.SECONDS); assertNotNull(result); return result; }
        public void close() throws Exception { closed = true; server.close(); Socket active = socket; if (active != null) active.close(); thread.join(2000); assertFalse(thread.isAlive()); if (error.get() != null) throw new AssertionError(error.get()); }
    }
    private void run(Server server, Sink sink) throws IOException { new ResumableDownload(client, partial()).run(server.url(), "Basic fixture", "connection:fixture", sink); }
    private void broken(Server server, String tag) throws Exception {
        server.add(200, 10, "ETag: " + tag + "\r\n", "abcd");
        assertThrows(IOException.class, () -> run(server, new Sink())); server.request();
        assertEquals(4, partial().length());
    }
    private String body() throws IOException { return new String(Files.readAllBytes(partial().toPath()), StandardCharsets.US_ASCII); }

    @Test public void interruptedConnectionResumesWithValidatedRangeAndStrongEtag() throws Exception {
        try (Server server = new Server()) {
            broken(server, "\"v1\"");
            assertEquals(6, ResumableDownload.remaining(partial(), "connection:fixture", 10));
            server.add(206, 6, "ETag: \"v1\"\r\nContent-Range: bytes 4-9/10\r\n", "efghij");
            Sink sink = new Sink(); run(server, sink); Map<String, String> request = server.request();
            assertEquals("bytes=4-", request.get("range")); assertEquals("\"v1\"", request.get("if-range"));
            assertEquals("identity", request.get("accept-encoding")); assertEquals("abcdefghij", body());
            assertEquals(1, sink.complete); assertEquals(10, sink.bytes); assertFalse(new File(partial() + ".meta").exists());
        }
    }
    @Test public void serverIgnoringRangeReplacesRatherThanAppends() throws Exception {
        try (Server server = new Server()) {
            broken(server, "\"v1\""); server.add(200, 6, "ETag: \"v2\"\r\n", "NEWNEW"); run(server, new Sink());
            assertEquals("bytes=4-", server.request().get("range")); assertEquals("NEWNEW", body());
        }
    }
    @Test public void weakEtagRestartsWithoutRange() throws Exception {
        try (Server server = new Server()) {
            broken(server, "W/\"v1\""); assertEquals(10, ResumableDownload.remaining(partial(), "connection:fixture", 10));
            server.add(200, 3, "ETag: \"v2\"\r\n", "new"); run(server, new Sink());
            assertNull(server.request().get("range")); assertEquals("new", body());
        }
    }
    @Test public void changedConnectionScopeCannotResumeOldAccountBytes() throws Exception {
        try (Server server = new Server()) {
            broken(server, "\"v1\""); server.add(200, 3, "ETag: \"v1\"\r\n", "new");
            new ResumableDownload(client, partial()).run(server.url(), "Basic other-fixture", "different-connection", new Sink());
            assertNull(server.request().get("range")); assertEquals("new", body());
        }
    }
    @Test public void wrongContentRangeRestartsSafely() throws Exception {
        try (Server server = new Server()) {
            broken(server, "\"v1\""); server.add(206, 6, "ETag: \"v1\"\r\nContent-Range: bytes 3-8/10\r\n", "WRONG!");
            server.add(200, 3, "ETag: \"v2\"\r\n", "new"); run(server, new Sink());
            assertNotNull(server.request().get("range")); assertNull(server.request().get("range")); assertEquals("new", body());
        }
    }
    @Test public void changed206EtagIsNeverAppended() throws Exception {
        try (Server server = new Server()) {
            broken(server, "\"v1\""); server.add(206, 6, "ETag: \"v2\"\r\nContent-Range: bytes 4-9/10\r\n", "WRONG!");
            server.add(200, 3, "ETag: \"v2\"\r\n", "new"); run(server, new Sink());
            server.request(); server.request(); assertEquals("new", body());
        }
    }
    @Test public void completedCheckpointAccepts416OnlyWithExactLengthAndValidator() throws Exception {
        try (Server server = new Server()) {
            server.add(200, 4, "ETag: \"v1\"\r\n", "done"); Sink rejected = new Sink(); rejected.accept = false;
            assertThrows(ResumableDownload.Stopped.class, () -> run(server, rejected)); server.request();
            server.add(416, 0, "ETag: \"v1\"\r\nContent-Range: bytes */4\r\n", "");
            Sink sink = new Sink(); run(server, sink); assertEquals("bytes=4-", server.request().get("range")); assertEquals("done", body()); assertEquals(1, sink.complete);
        }
    }
    @Test public void unmatched416RestartsAndDoesNotPublishCheckpoint() throws Exception {
        try (Server server = new Server()) {
            broken(server, "\"v1\""); server.add(416, 0, "ETag: \"v2\"\r\nContent-Range: bytes */4\r\n", "");
            server.add(200, 3, "ETag: \"v2\"\r\n", "new"); run(server, new Sink());
            server.request(); assertNull(server.request().get("range")); assertEquals("new", body());
        }
    }
    @Test public void authorizationErrorIsTerminalAndRedirectDoesNotForwardKey() throws Exception {
        try (Server server = new Server()) {
            server.add(403, 0, "", ""); ResumableDownload.HttpFailure error = assertThrows(ResumableDownload.HttpFailure.class, () -> run(server, new Sink()));
            assertFalse(error.retryable); server.request();
            server.add(302, 0, "Location: " + server.url() + "?redirect\r\n", "");
            error = assertThrows(ResumableDownload.HttpFailure.class, () -> run(server, new Sink())); assertFalse(error.retryable); server.request();
            assertTrue(server.requests.isEmpty());
        }
    }
    @Test public void invalidMetadataRestartsAndPrivatePathsRejectTraversal() throws Exception {
        try (Server server = new Server()) {
            broken(server, "\"v1\""); Files.write(new File(partial() + ".meta").toPath(), "scope=\\uBAD!".getBytes(StandardCharsets.US_ASCII));
            server.add(200, 3, "ETag: \"v2\"\r\n", "new"); run(server, new Sink()); assertNull(server.request().get("range")); assertEquals("new", body());
            assertThrows(IllegalArgumentException.class, () -> ResumableDownload.partial(files.getRoot(), "../other"));
        }
    }
    @Test public void cancelInterruptsBlockedSocketAndClearWaitsForWriter() throws Exception {
        try (Server server = new Server()) {
            Reply reply = new Reply(200, 10, "ETag: \"v1\"\r\n", "abcd"); reply.hold = true; server.replies.add(reply);
            ResumableDownload task = new ResumableDownload(client, partial()); Sink sink = new Sink(); CountDownLatch finished = new CountDownLatch(1);
            AtomicReference<Throwable> result = new AtomicReference<>();
            Thread download = new Thread(() -> { try { task.run(server.url(), "Basic fixture", "fixture", sink); } catch (Throwable error) { result.set(error); } finally { finished.countDown(); } });
            download.start(); server.request(); task.cancel();
            ResumableDownload.clear(partial()); assertTrue(finished.await(3, TimeUnit.SECONDS)); download.join(1000);
            assertTrue(result.get() instanceof IOException); assertEquals(0, sink.complete); assertFalse(partial().exists()); assertFalse(new File(partial() + ".meta").exists());
        }
    }
    @Test public void interruptedPublicationRecoversOrClearsOnlyUnpublishedFiles() throws Exception {
        try (Server server = new Server()) {
            server.add(200, 4, "ETag: \"v1\"\r\n", "done"); Sink rejected = new Sink(); rejected.accept = false;
            assertThrows(ResumableDownload.Stopped.class, () -> run(server, rejected)); server.request();
            File unpublished = new File(files.getRoot(), "0123456789abcdef01234567.m4a"); assertTrue(partial().renameTo(unpublished));
            ResumableDownload.recover(partial(), unpublished); assertEquals("done", body()); assertFalse(unpublished.exists());
            assertTrue(partial().renameTo(unpublished)); assertEquals(4, ResumableDownload.clear(partial(), unpublished)); assertFalse(unpublished.exists());
            Files.write(unpublished.toPath(), new byte[]{1,2,3}); ResumableDownload.clear(partial()); assertTrue(unpublished.exists());
        }
    }
}
