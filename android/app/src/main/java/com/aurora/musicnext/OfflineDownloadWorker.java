package com.aurora.musicnext;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONObject;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class OfflineDownloadWorker extends Worker {
    private static final ConcurrentHashMap<String, ResumableDownload> ACTIVE = new ConcurrentHashMap<>();
    private volatile ResumableDownload transfer;
    public OfflineDownloadWorker(@NonNull Context context, @NonNull WorkerParameters parameters) { super(context, parameters); }

    static void cancel(String id) { ResumableDownload task = ACTIVE.get(id); if (task != null) task.cancel(); }
    @Override public void onStopped() { ResumableDownload task = transfer; if (task != null) task.cancel(); super.onStopped(); }
    static File target(File directory, String id, String format) {
        ResumableDownload.partial(directory, id);
        if (!format.matches("[a-z0-9]{1,12}")) throw new IllegalArgumentException("Invalid audio format");
        return new File(directory, id + "." + format);
    }

    @NonNull @Override public Result doWork() {
        String id = getInputData().getString("songId");
        if (id == null || !id.matches("[a-f0-9]{24}")) return Result.failure();
        try (MusicStore store = new MusicStore(getApplicationContext())) {
            String token = getInputData().getString("token");
            JSONObject song;
            try { song = store.song(id); } catch (org.json.JSONException error) { return Result.failure(); }
            if (song == null) return Result.failure();
            File directory = new File(getApplicationContext().getFilesDir(), "offline");
            File partial = ResumableDownload.partial(directory, id);
            if (song.optBoolean("offline")) {
                try { ResumableDownload.clear(partial); } catch (IOException ignored) { }
                return Result.success();
            }
            // Migrate old WorkManager jobs without reactivating a user-paused task.
            if (token == null) {
                if (!song.optString("downloadState").matches("pending|running")) return Result.success();
                token = store.downloadToken(id);
                if (token.isEmpty()) { token = UUID.randomUUID().toString(); store.scheduleDownload(id, token); }
            }
            final String generation = token;
            final long priorBytes = song.optLong("downloadBytes");
            if (!store.downloadActive(id, generation)) return Result.success();
            SecretStore.Connection connection;
            String address, authorization;
            try {
                connection = new SecretStore(getApplicationContext()).connection();
                address = song.getString("remotePath"); authorization = connection.authorizationFor(address);
            } catch (Exception error) {
                store.downloadState(id, generation, "failed", "Reconnect to the original WebDAV server before retrying");
                LibraryEvents.changed(); return Result.failure();
            }
            transfer = new ResumableDownload(new AudioHttp(ScopedHttpDataSource.HTTP, connection.root), partial);
            if (ACTIVE.putIfAbsent(id, transfer) != null) return Result.retry();
            try {
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create offline directory");
                File target = target(directory, id, song.getString("format"));
                ResumableDownload.recover(partial, target);
                store.downloadState(id, generation, "running", ""); LibraryEvents.changed();
                transfer.run(address, authorization, connection.revision + ":" + id, new ResumableDownload.Listener() {
                    public boolean stopped() { return isStopped(); }
                    public void progress(long bytes, long total) throws IOException {
                        store.downloadProgress(id, generation, bytes, total, priorBytes);
                        if (!store.downloadActive(id, generation)) throw new ResumableDownload.Stopped();
                    }
                    public boolean complete(File file, long bytes) throws IOException {
                        return !isStopped() && store.commitDownload(id, generation, file, target);
                    }
                });
                return Result.success();
            } catch (Exception error) {
                if (!store.downloadActive(id, generation)) return Result.success();
                if (isStopped() || error instanceof ResumableDownload.Stopped || error instanceof ResumableDownload.Checkpoint) {
                    store.downloadState(id, generation, "pending", ""); return Result.retry();
                }
                boolean retryable = error instanceof IOException && !(error instanceof ResumableDownload.StorageFull)
                    && !(error instanceof AudioHttp.Failure)
                    && (!(error instanceof ResumableDownload.HttpFailure) || ((ResumableDownload.HttpFailure) error).retryable);
                boolean retry = retryable && store.downloadFailure(id, generation) < 5;
                // Arbitrary exception text can contain a private URL: only expose bounded generic diagnostics.
                String safe = error instanceof ResumableDownload.HttpFailure || error instanceof ResumableDownload.StorageFull || error instanceof AudioHttp.Failure
                    ? error.getMessage() : retry ? "Connection interrupted; waiting to retry" : "Download failed; check connection and storage, then retry";
                store.downloadState(id, generation, retry ? "pending" : "failed", safe);
                return retry ? Result.retry() : Result.failure();
            } finally {
                ACTIVE.remove(id, transfer); transfer = null; LibraryEvents.changed();
            }
        }
    }
}
