package com.aurora.musicnext;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Real loopback TLS with an ephemeral test certificate. No production trust overrides or accounts. */
public class AudioHttpsTest {
    @ClassRule public static TemporaryFolder certificate = new TemporaryFolder();
    @Rule public TemporaryFolder files = new TemporaryFolder();
    private static SSLContext tls;
    private static OkHttpClient client;

    @BeforeClass public static void createEphemeralCertificate() throws Exception {
        File keytool = new File(System.getProperty("java.home"), "bin/keytool.exe");
        if (!keytool.isFile()) keytool = new File(System.getProperty("java.home"), "bin/keytool");
        File store = new File(certificate.getRoot(), "fixture.p12"), output = new File(certificate.getRoot(), "keytool.txt");
        String password = UUID.randomUUID().toString();
        Process process = new ProcessBuilder(keytool.getPath(), "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048",
            "-storetype", "PKCS12", "-keystore", store.getPath(), "-storepass", password, "-keypass", password,
            "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-validity", "2", "-noprompt")
            .redirectErrorStream(true).redirectOutput(output).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("Fixture certificate creation timed out"); }
        assertEquals("Fixture certificate creation failed", 0, process.exitValue());
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (FileInputStream input = new FileInputStream(store)) { keys.load(input, password.toCharArray()); }
        KeyManagerFactory manager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); manager.init(keys, password.toCharArray());
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); trust.init(keys);
        tls = SSLContext.getInstance("TLS"); tls.init(manager.getKeyManagers(), trust.getTrustManagers(), null);
        client = new OkHttpClient.Builder().sslSocketFactory(tls.getSocketFactory(), (X509TrustManager) trust.getTrustManagers()[0])
            .dns(host -> Collections.singletonList(InetAddress.getByName("127.0.0.1"))).protocols(Collections.singletonList(Protocol.HTTP_1_1))
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).retryOnConnectionFailure(false).build();
    }
    private static final class Reply {
        final int code; final long length; final String headers, body; boolean waitForClose;
        Reply(int code, long length, String headers, String body) { this.code = code; this.length = length; this.headers = headers; this.body = body; }
    }
    private static final class Server implements AutoCloseable {
        final SSLServerSocket server;
        final BlockingQueue<Reply> replies = new LinkedBlockingQueue<>();
        final BlockingQueue<Map<String, String>> requests = new LinkedBlockingQueue<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final Thread worker; volatile Socket active; volatile boolean closed;
        Server() throws IOException {
            server = (SSLServerSocket) tls.getServerSocketFactory().createServerSocket(0, 5, InetAddress.getByName("127.0.0.1"));
            worker = new Thread(() -> {
                try {
                    while (!closed) {
                        try (Socket socket = server.accept()) {
                            active = socket; socket.setSoTimeout(5000);
                            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                            Map<String, String> headers = new HashMap<>(); String line = input.readLine(); headers.put("request", line);
                            while ((line = input.readLine()) != null && !line.isEmpty()) {
                                int colon = line.indexOf(':');
                                if (colon >= 0) headers.put(line.substring(0, colon).toLowerCase(java.util.Locale.ROOT), line.substring(colon + 1).trim());
                            }
                            Reply reply = replies.poll(5, TimeUnit.SECONDS); if (reply == null) throw new IOException("Unexpected fixture request");
                            requests.add(headers);
                            if (reply.waitForClose) { input.read(); continue; } // Exercise cancellation while awaiting headers.
                            socket.getOutputStream().write(("HTTP/1.1 " + reply.code + " Fixture\r\nConnection: close\r\nContent-Length: " + reply.length
                                + "\r\nContent-Type: audio/mp4\r\n" + reply.headers + "\r\n" + reply.body).getBytes(StandardCharsets.US_ASCII));
                            socket.getOutputStream().flush();
                        } finally { active = null; }
                    }
                } catch (Throwable failure) { if (!closed) error.set(failure); }
            }, "aurora-https-fixture"); worker.setDaemon(true); worker.start();
        }
        String root() { return "https://localhost:" + server.getLocalPort() + "/music/"; }
        String url() { return root() + "song.m4a"; }
        void add(int code, long length, String headers, String body) { replies.add(new Reply(code, length, headers, body)); }
        Map<String, String> request() throws InterruptedException { Map<String, String> result = requests.poll(5, TimeUnit.SECONDS); assertNotNull(result); return result; }
        public void close() throws Exception {
            closed = true; server.close(); Socket socket = active; if (socket != null) socket.close(); worker.join(2000);
            assertFalse(worker.isAlive()); if (error.get() != null) throw new AssertionError(error.get());
        }
    }
    private static final class Sink implements ResumableDownload.Listener {
        int completions;
        public boolean stopped() { return false; }
        public void progress(long bytes, long total) {}
        public boolean complete(File file, long bytes) { completions++; return true; }
    }
    private Request request(Server server) { return new Request.Builder().url(server.url()).header("Authorization", "Basic fixture")
        .header("Accept-Encoding", "identity").header("Range", "bytes=4-").build(); }

    @Test public void relativeHttpsRedirectStreamsValidatedRangeWithoutLoadingFullFile() throws Exception {
        try (Server server = new Server()) {
            server.add(302, 0, "Location: next.m4a?signature=fixture\r\n", "");
            server.add(206, 6, "Content-Range: bytes 4-9/10\r\n", "efghij");
            try (Response response = new AudioHttp(client, server.root()).execute(request(server), call -> {})) {
                assertEquals(206, response.code()); AudioHttp.requireAudioBody(response);
                assertEquals(6, AudioRange.remaining(response.code(), response.header("Content-Range"), response.body().contentLength(), 4, -1));
                assertEquals("efghij", response.body().string());
            }
            server.request(); Map<String, String> redirected = server.request();
            assertEquals("Basic fixture", redirected.get("authorization")); assertEquals("bytes=4-", redirected.get("range"));
        }
    }
    @Test public void redirectedOfflineDownloadResumesAcrossFreshLinksWithStrongEtag() throws Exception {
        File partial = ResumableDownload.partial(files.getRoot(), "0123456789abcdef01234567");
        try (Server server = new Server()) {
            AudioHttp transport = new AudioHttp(client, server.root());
            server.add(302, 0, "Location: next.m4a?signature=fixture-old\r\n", "");
            server.add(200, 10, "ETag: \"v1\"\r\n", "abcd");
            Sink sink = new Sink();
            assertThrows(IOException.class, () -> new ResumableDownload(transport, partial).run(server.url(), "Basic fixture", "fixture-scope", sink));
            assertEquals(4, partial.length()); assertEquals(0, sink.completions); server.request(); server.request();
            String metadata = new String(Files.readAllBytes(new File(partial + ".meta").toPath()), StandardCharsets.UTF_8);
            assertFalse(metadata.contains("signature")); assertFalse(metadata.contains("localhost")); assertFalse(metadata.contains("Basic"));
            server.add(307, 0, "Location: next.m4a?signature=fixture-new\r\n", "");
            server.add(206, 6, "ETag: \"v1\"\r\nContent-Range: bytes 4-9/10\r\n", "efghij");
            new ResumableDownload(transport, partial).run(server.url(), "Basic fixture", "fixture-scope", sink);
            server.request(); Map<String, String> redirected = server.request();
            assertEquals("bytes=4-", redirected.get("range")); assertEquals("\"v1\"", redirected.get("if-range"));
            assertEquals("abcdefghij", new String(Files.readAllBytes(partial.toPath()), StandardCharsets.US_ASCII));
            assertEquals(1, sink.completions); assertFalse(new File(partial + ".meta").exists());
        }
    }
    @Test public void cancellationInterruptsTheRedirectedCallWhileWaitingForHeaders() throws Exception {
        try (Server server = new Server()) {
            server.add(302, 0, "Location: next.m4a\r\n", "");
            Reply held = new Reply(200, 10, "", ""); held.waitForClose = true; server.replies.add(held);
            AtomicReference<Call> active = new AtomicReference<>(); AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch finished = new CountDownLatch(1);
            Thread reader = new Thread(() -> {
                try (Response ignored = new AudioHttp(client, server.root()).execute(request(server), active::set)) {}
                catch (Throwable error) { failure.set(error); } finally { finished.countDown(); }
            }); reader.start(); server.request(); server.request(); active.get().cancel();
            assertTrue(finished.await(2, TimeUnit.SECONDS)); reader.join(1000); assertTrue(failure.get() instanceof IOException);
        }
    }
    @Test public void redirectOutsidePrivateServerScopeIsBlockedWithoutForwardingAuth() throws Exception {
        try (Server server = new Server()) {
            server.add(302, 0, "Location: /download/song.m4a\r\n", "");
            assertEquals("AUDIO_REDIRECT_UNSAFE", assertThrows(AudioHttp.Failure.class,
                () -> new AudioHttp(client, server.root()).execute(request(server), call -> {})).code);
            server.request(); assertTrue(server.requests.isEmpty());
        }
    }
}
