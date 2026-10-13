package com.aurora.musicnext;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Android-independent transfer core, exercised against a real local HTTP fixture in JVM tests. */
final class ResumableDownload {
    interface Listener {
        boolean stopped();
        void progress(long received, long total) throws IOException;
        boolean complete(File partial, long received) throws IOException;
    }
    static final class Stopped extends IOException { Stopped() { super("Download stopped"); } }
    static final class Checkpoint extends IOException { Checkpoint() { super("Download checkpoint saved"); } }
    static final class HttpFailure extends IOException {
        final boolean retryable;
        HttpFailure(int code) { super("Download HTTP " + code); retryable = code == 408 || code == 429 || code >= 500; }
    }
    static final class StorageFull extends IOException { StorageFull() { super("Not enough device storage"); } }
    private static final Pattern RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");
    private static final Pattern UNSATISFIED = Pattern.compile("bytes \\*/(\\d+)");
    private static final long SAFETY = 64L * 1024 * 1024;
    private final OkHttpClient client;
    private final AudioHttp transport;
    private final File partial, metadata;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile Call activeCall;

    ResumableDownload(OkHttpClient client, File partial) {
        this.client = client; transport = null; this.partial = partial; metadata = new File(partial.getPath() + ".meta");
    }
    ResumableDownload(AudioHttp transport, File partial) {
        this.transport = transport; client = null; this.partial = partial; metadata = new File(partial.getPath() + ".meta");
    }
    void cancel() { cancelled.set(true); Call call = activeCall; if (call != null) call.cancel(); }
    void run(String address, String authorization, String scope, Listener listener) throws IOException {
        try (Lease ignored = Lease.acquire(partial, 30000)) {
            check(listener);
            String identity = digest(scope);
            Properties saved = readMetadata(metadata);
            String validator = strongTag(saved.getProperty("etag"));
            long total = parse(saved.getProperty("total"));
            long offset = partial.isFile() ? partial.length() : 0;
            if (!identity.equals(saved.getProperty("scope")) || validator.isEmpty() || total >= 0 && offset > total) offset = 0;
            long started = System.nanoTime(), reported = 0, checkedBytes = -1024 * 1024;
            int restarts = 0;
            while (true) {
                check(listener);
                Request.Builder request = new Request.Builder().url(address).header("Authorization", authorization).header("Accept-Encoding", "identity");
                if (offset > 0) request.header("Range", "bytes=" + offset + "-").header("If-Range", validator);
                try (Response response = execute(request.build(), listener)) {
                    check(listener);
                    if (response.code() == 416 && offset > 0) {
                        Matcher size = UNSATISFIED.matcher(response.header("Content-Range", ""));
                        if (size.matches() && parse(size.group(1)) == offset && total == offset
                            && validator.equals(strongTag(response.header("ETag")))) {
                            listener.progress(offset, total); finish(listener, offset); return;
                        }
                        if (++restarts > 1) throw new IOException("Invalid download range");
                        offset = 0; validator = ""; continue;
                    }
                    if (response.code() != 200 && response.code() != 206) throw new HttpFailure(response.code());
                    AudioHttp.requireAudioBody(response);
                    String tag = strongTag(response.header("ETag"));
                    long bodyLength = response.body().contentLength();
                    long end = -1;
                    if (response.code() == 206) {
                        Matcher range = RANGE.matcher(response.header("Content-Range", ""));
                        boolean valid = range.matches();
                        long begin = valid ? parse(range.group(1)) : -1;
                        end = valid ? parse(range.group(2)) : -1;
                        long size = valid ? parse(range.group(3)) : -1;
                        valid = valid && begin == offset && end >= begin && size > end
                            && (bodyLength < 0 || bodyLength == end - begin + 1)
                            && (offset == 0 || !tag.isEmpty() && tag.equals(validator) && (total < 0 || total == size));
                        if (!valid) {
                            if (++restarts > 1) throw new IOException("Invalid download range or version");
                            offset = 0; validator = ""; continue;
                        }
                        total = size;
                    } else {
                        // If-Range failed or the server ignored Range: replace, never append.
                        offset = 0; total = bodyLength;
                    }
                    validator = tag;
                    long received = offset;
                    try (InputStream input = response.body().byteStream(); FileOutputStream output = new FileOutputStream(partial, offset > 0)) {
                        // Truncate an incompatible old partial before advertising a new validator.
                        saveMetadata(metadata, identity, validator, total);
                        byte[] buffer = new byte[64 * 1024]; int count;
                        listener.progress(received, total);
                        while ((count = input.read(buffer)) != -1) {
                            check(listener);
                            if (received - checkedBytes >= 1024 * 1024) {
                                if (partial.getParentFile().getUsableSpace() < SAFETY) throw new StorageFull();
                                checkedBytes = received;
                            }
                            if (total >= 0 && count > total - received) throw new IOException("Download exceeds expected length");
                            output.write(buffer, 0, count); received += count;
                            long now = System.nanoTime();
                            if (now - reported >= TimeUnit.SECONDS.toNanos(1)) { listener.progress(received, total); reported = now; }
                            if (!validator.isEmpty() && now - started >= TimeUnit.MINUTES.toNanos(8)) { output.getFD().sync(); throw new Checkpoint(); }
                        }
                        output.getFD().sync();
                    } finally {
                        // Also checkpoint the final bytes of a broken connection or paused job.
                        listener.progress(partial.length(), total);
                    }
                    if (received == 0 || end >= 0 && received != end + 1 || total >= 0 && received != total)
                        throw new IOException("Incomplete download response");
                    check(listener); finish(listener, received); return;
                } finally { activeCall = null; }
            }
        }
    }
    private Response execute(Request request, Listener listener) throws IOException {
        AudioHttp.CallObserver observer = call -> { activeCall = call; check(listener); };
        if (transport != null) return transport.execute(request, observer);
        Call call = client.newCall(request); observer.starting(call); return call.execute();
    }
    private void finish(Listener listener, long bytes) throws IOException {
        check(listener);
        if (!listener.complete(partial, bytes)) throw new Stopped();
        if (metadata.exists() && !metadata.delete()) throw new IOException("Cannot remove download metadata");
    }
    private void check(Listener listener) throws Stopped {
        if (cancelled.get() || listener.stopped() || Thread.currentThread().isInterrupted()) throw new Stopped();
    }
    static long clear(File partial) throws IOException {
        return clear(partial, null);
    }
    static long clear(File partial, File unpublished) throws IOException {
        try (Lease ignored = Lease.acquire(partial, 5000)) {
            long bytes = partial.isFile() ? partial.length() : 0;
            File metadata = new File(partial.getPath() + ".meta");
            // Recover a process death between rename and SQLite commit. Never pass a published offline file here.
            if (unpublished != null && metadata.isFile() && unpublished.isFile()) {
                bytes += unpublished.length();
                if (!unpublished.delete()) throw new IOException("Cannot clear unpublished download");
            }
            if (partial.exists() && !partial.delete() || metadata.exists() && !metadata.delete()) throw new IOException("Cannot clear partial download");
            return bytes;
        }
    }
    static void recover(File partial, File unpublished) throws IOException {
        try (Lease ignored = Lease.acquire(partial, 5000)) {
            if (!partial.exists() && new File(partial.getPath() + ".meta").isFile() && unpublished.isFile()
                && !unpublished.renameTo(partial)) throw new IOException("Cannot recover download checkpoint");
        }
    }
    static File partial(File directory, String id) {
        if (!id.matches("[a-f0-9]{24}")) throw new IllegalArgumentException("Invalid download ID");
        return new File(directory, id + ".part");
    }
    private static long parse(String value) { try { return Long.parseLong(value); } catch (Exception ignored) { return -1; } }
    static long remaining(File partial, String scope, long expected) throws IOException {
        Properties saved = readMetadata(new File(partial.getPath() + ".meta"));
        long size = parse(saved.getProperty("total"));
        boolean compatible = digest(scope).equals(saved.getProperty("scope")) && !strongTag(saved.getProperty("etag")).isEmpty();
        long total = compatible && size >= 0 ? size : Math.max(0, expected);
        return Math.max(0, total - (compatible && partial.isFile() ? partial.length() : 0));
    }
    static String strongTag(String value) {
        if (value == null || value.length() > 512 || !value.matches("\"[\\x21\\x23-\\x7e]*\"")) return "";
        return value;
    }
    private static String digest(String value) throws IOException {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder text = new StringBuilder(); for (byte b : hash) text.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return text.toString();
        } catch (Exception error) { throw new IOException("Cannot identify download scope", error); }
    }
    private static Properties readMetadata(File file) {
        Properties properties = new Properties();
        if (!file.isFile() || file.length() > 8192) return properties;
        try (FileInputStream input = new FileInputStream(file)) { properties.load(input); } catch (Exception ignored) { properties.clear(); }
        return properties;
    }
    private static void saveMetadata(File file, String identity, String tag, long total) throws IOException {
        Properties properties = new Properties(); properties.setProperty("scope", identity);
        properties.setProperty("etag", tag); properties.setProperty("total", Long.toString(total));
        try (FileOutputStream output = new FileOutputStream(file)) { properties.store(output, null); output.getFD().sync(); }
    }
    /** Ref-counted per-file leases: no lock files, unbounded retained locks or overlapping writers. */
    private static final class Lease implements AutoCloseable {
        private static final Map<String, Slot> SLOTS = new HashMap<>();
        private static final class Slot { final ReentrantLock lock = new ReentrantLock(); int references; }
        private final String key; private final Slot slot;
        private Lease(String key, Slot slot) { this.key = key; this.slot = slot; }
        static Lease acquire(File file, long timeout) throws IOException {
            String key = file.getCanonicalPath(); Slot slot;
            synchronized (SLOTS) { slot = SLOTS.get(key); if (slot == null) { slot = new Slot(); SLOTS.put(key, slot); } slot.references++; }
            try {
                if (!slot.lock.tryLock(timeout, TimeUnit.MILLISECONDS)) throw new IOException("Download is still stopping; retry shortly");
                return new Lease(key, slot);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt(); release(key, slot); throw new Stopped();
            } catch (IOException error) { release(key, slot); throw error; }
        }
        private static void release(String key, Slot slot) { synchronized (SLOTS) { if (--slot.references == 0) SLOTS.remove(key); } }
        @Override public void close() { slot.lock.unlock(); release(key, slot); }
    }
}
