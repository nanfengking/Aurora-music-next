package com.aurora.musicnext;

import android.app.DownloadManager;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.google.common.util.concurrent.ListenableFuture;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import androidx.work.Constraints;
import androidx.work.BackoffPolicy;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

@CapacitorPlugin(name = "AuroraMusic")
public final class AuroraMusicPlugin extends Plugin {
    private static final Object DOWNLOAD_COMMANDS = new Object();
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final ExecutorService aiIo = Executors.newSingleThreadExecutor();
    private final ExecutorService assetIo = Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean assetBusy = new java.util.concurrent.atomic.AtomicBoolean();
    private AssetLookup assets;
    private volatile boolean destroyed;
    private final java.util.concurrent.atomic.AtomicBoolean aiBusy = new java.util.concurrent.atomic.AtomicBoolean();
    private AiClient ai;
    private MusicStore store;
    private SecretStore secrets;
    private WebDavClient webdav;
    private DownloadManager downloads;
    private ListenableFuture<MediaController> controllerFuture;
    private volatile boolean foreground = true;
    private volatile boolean libraryDirty;
    private final Runnable libraryObserver = this::changed;
    private final Runnable playbackObserver = () -> {
        if (!foreground) return;
        try { notifyListeners("playbackChanged", JSObject.fromJSONObject(PlaybackService.snapshot())); }
        catch (Exception ignored) { }
    };

    @Override public void load() {
        store = new MusicStore(getContext()); secrets = new SecretStore(getContext());
        webdav = new WebDavClient(getContext());
        ai = new AiClient(getContext());
        assets = new AssetLookup(getContext().getApplicationContext());
        assetIo.execute(assets::clearPreviews);
        downloads = (DownloadManager) getContext().getSystemService(Context.DOWNLOAD_SERVICE);
        PlaybackService.setObserver(playbackObserver);
        LibraryEvents.listen(libraryObserver);
    }
    @Override protected void handleOnPause() {
        foreground = false;
        if (ai != null) ai.cancel();
        if (assets != null) { assets.cancel(); assetIo.execute(assets::clearPreviews); }
        JSObject state = new JSObject(); state.put("active", false); notifyListeners("appStateChanged", state);
        if (controllerFuture != null) { MediaController.releaseFuture(controllerFuture); controllerFuture = null; }
        super.handleOnPause();
    }
    @Override protected void handleOnResume() {
        foreground = true; playbackObserver.run(); PlaybackService.widgetsChanged();
        JSObject state = new JSObject(); state.put("active", true); notifyListeners("appStateChanged", state);
        applySystemTheme(); super.handleOnResume();
        if (libraryDirty) { libraryDirty = false; changed(); }
    }
    @Override protected void handleOnNewIntent(Intent intent) {
        if ("com.aurora.musicnext.OPEN_PLAYER".equals(intent.getAction()) || intent.getBooleanExtra("openPlayer", false))
            notifyListeners("openPlayer", new JSObject(), true);
        super.handleOnNewIntent(intent);
    }
    private void applySystemTheme() {
        getActivity().runOnUiThread(() -> {
            AppearanceSettings theme = new AppearanceSettings(getContext());
            getActivity().getWindow().getDecorView().setBackgroundColor(theme.color("background"));
            getBridge().getWebView().setBackgroundColor(theme.color("background"));
            androidx.core.view.WindowInsetsControllerCompat bars = new androidx.core.view.WindowInsetsControllerCompat(
                getActivity().getWindow(), getActivity().getWindow().getDecorView());
            bars.setAppearanceLightStatusBars(!theme.dark()); bars.setAppearanceLightNavigationBars(!theme.dark());
        });
    }
    @Override protected void handleOnDestroy() {
        foreground = false; destroyed = true; PlaybackService.setObserver(null); LibraryEvents.listen(null);
        if (ai != null) ai.cancel(); aiIo.shutdown();
        if (assets != null) assets.cancel();
        ContextCompat.getMainExecutor(getContext()).execute(() -> {
            if (controllerFuture != null) MediaController.releaseFuture(controllerFuture);
        });
        io.shutdown();
        // The database outlives both ordinary I/O and the final cancelled resource operation.
        assetIo.execute(() -> {
            if (assets != null) assets.clearPreviews();
            try { while (!io.awaitTermination(1, TimeUnit.SECONDS)) { /* Background drain, never on the UI thread. */ } }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            if (store != null) store.close();
        });
        assetIo.shutdown(); super.handleOnDestroy();
    }
    private interface Work { JSObject run() throws Exception; }
    private void background(PluginCall call, Work work) {
        io.execute(() -> {
            try { call.resolve(work.run()); }
            catch (Exception error) { call.reject(error.getMessage() == null ? error.toString() : error.getMessage()); }
        });
    }
    private void resourceWork(PluginCall call, Work work) {
        if (destroyed || !foreground || !assetBusy.compareAndSet(false, true)) { call.reject("ASSET_BUSY"); return; }
        assetIo.execute(() -> {
            try {
                if (destroyed || !foreground) throw new java.io.IOException("Resource operation cancelled");
                JSObject result = work.run(); assetBusy.set(false); call.resolve(result);
            } catch (Exception error) {
                assetBusy.set(false);
                String message = error.getMessage();
                // Do not return HTTP bodies, secret-bearing URLs or JSON parsing diagnostics to JavaScript.
                String code = "ASSET_FAILED";
                if (message != null && message.contains("cancelled")) code = "ASSET_CANCELLED";
                else if (message != null && message.contains("expired")) code = "ASSET_EXPIRED";
                else if (message != null && message.contains("storage is full")) code = "ASSET_STORAGE_FULL";
                else if (error instanceof IllegalArgumentException) code = "ASSET_CONFIG_INVALID";
                call.reject(code);
            }
        });
    }
    private JSObject assetChanged(String id, JSONObject song) {
        getActivity().runOnUiThread(() -> PlaybackService.assetsChanged(id)); changed();
        JSObject result = new JSObject(); result.put("song", song); return result;
    }
    private void changed() {
        if (!foreground) { libraryDirty = true; return; }
        notifyListeners("libraryChanged", new JSObject());
    }

    @PluginMethod public void connect(PluginCall call) {
        background(call, () -> {
            String url = WebDavClient.normalizeRoot(call.getString("url", ""));
            String username = call.getString("username", "");
            String password = call.getString("password", "");
            String authorization;
            if (password.isEmpty() && url.equals(secrets.url()) && username.equals(secrets.username()))
                authorization = secrets.authorization();
            else authorization = "Basic " + android.util.Base64.encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
            webdav.list("", url, authorization);
            if (!(password.isEmpty() && url.equals(secrets.url()) && username.equals(secrets.username())))
                secrets.save(url, username, password);
            PlaybackService.prefetchSettingsChanged();
            JSObject result = new JSObject(); result.put("url", url); return result;
        });
    }
    @PluginMethod public void connection(PluginCall call) {
        JSObject result = new JSObject(); result.put("url", secrets.url()); result.put("username", secrets.username()); call.resolve(result);
    }
    @PluginMethod public void browse(PluginCall call) {
        background(call, () -> { JSObject result = new JSObject(); result.put("entries", webdav.browse(call.getString("path", ""), store)); changed(); return result; });
    }
    @PluginMethod public void scan(PluginCall call) { background(call, () -> { JSObject result = JSObject.fromJSONObject(webdav.scan(store)); changed(); return result; }); }

    @PluginMethod public void library(PluginCall call) {
        background(call, () -> {
            LibrarySelection selection = LibrarySelection.from(call.getData(), false);
            int offset = Math.max(0, call.getInt("offset", 0));
            int limit = Math.max(1, Math.min(200, call.getInt("limit", 60)));
            JSONArray songs = store.songs(selection, offset, limit);
            Map<Long, long[]> states = downloadStates();
            for (int i = 0; i < songs.length(); i++) {
                JSONObject song = songs.getJSONObject(i);
                long downloadId = song.optLong("downloadId");
                long[] state = states.get(downloadId);
                if (downloadId > 0 && !song.optBoolean("offline") && (state == null || state[0] == DownloadManager.STATUS_FAILED)) {
                    store.clearPendingDownload(song.getString("id"));
                    if (state != null) downloads.remove(downloadId);
                    continue;
                }
                if (state == null) continue;
                if (state[0] == DownloadManager.STATUS_SUCCESSFUL && !song.optBoolean("offline")) {
                    OfflineFinalizeWorker.enqueue(getContext(), downloadId);
                    song.put("downloading", true); song.put("downloadProgress", 100);
                } else if (state[0] == DownloadManager.STATUS_PENDING || state[0] == DownloadManager.STATUS_RUNNING || state[0] == DownloadManager.STATUS_PAUSED) {
                    song.put("downloading", true); song.put("downloadProgress", state[2] > 0 ? (100.0 * state[1] / state[2]) : 0);
                }
            }
            long total = store.matchingSongs(selection);
            JSObject result = new JSObject(); result.put("songs", songs);
            if (Boolean.TRUE.equals(call.getBoolean("includeHistory"))) result.put("history", store.history());
            result.put("total", total); result.put("libraryTotal", store.totalSongs());
            result.put("offlineTotal", store.matchingSongs("", true)); result.put("offset", offset);
            result.put("hasMore", offset + songs.length() < total); return result;
        });
    }
    @PluginMethod public void song(PluginCall call) {
        background(call, () -> {
            JSObject result = new JSObject(); JSONObject song = store.song(call.getString("songId", ""));
            result.put("song", song == null ? JSONObject.NULL : song); return result;
        });
    }
    @PluginMethod public void collections(PluginCall call) {
        background(call, () -> JSObject.fromJSONObject(store.collections(call.getString("kind", ""), call.getString("query", ""),
            Boolean.TRUE.equals(call.getBoolean("offlineOnly")), Math.max(0, call.getInt("offset", 0)), Math.max(1, Math.min(200, call.getInt("limit", 60))))));
    }
    @PluginMethod public void overview(PluginCall call) {
        background(call, () -> {
            JSObject result = new JSObject(); result.put("songs", store.overview()); result.put("history", store.history());
            result.put("libraryTotal", store.totalSongs()); result.put("offlineTotal", store.matchingSongs("", true));
            return result;
        });
    }
    @PluginMethod public void catalogPage(PluginCall call) {
        background(call, () -> JSObject.fromJSONObject(store.catalogPage(Math.max(0, call.getLong("cursor", 0L)),
            Math.max(0, call.getLong("ceiling", 0L)), Boolean.TRUE.equals(call.getBoolean("offlineOnly")))));
    }
    @PluginMethod public void aiProviders(PluginCall call) {
        background(call, () -> { JSObject result = new JSObject(); result.put("providers", new AiProviderStore(getContext()).publicList()); return result; });
    }
    @PluginMethod public void saveAiProviders(PluginCall call) {
        background(call, () -> { JSObject result = new JSObject(); result.put("providers", new AiProviderStore(getContext()).save(call.getArray("providers"))); return result; });
    }
    private void aiRequest(PluginCall call, String stage, String system, String user, String providerId, long timeout) {
        if (!foreground || !aiBusy.compareAndSet(false, true)) { call.reject("AI is busy or app is in background"); return; }
        aiIo.execute(() -> {
            try {
                JSObject result = JSObject.fromJSONObject(ai.request(stage, system, user, providerId, timeout));
                aiBusy.set(false); call.resolve(result);
            } catch (Exception error) {
                aiBusy.set(false); call.reject(error.getMessage() == null ? "AI request failed" : error.getMessage());
            }
        });
    }
    @PluginMethod public void requestAi(PluginCall call) {
        String stage = call.getString("stage", "");
        if (!stage.equals("plan") && !stage.equals("select")) { call.reject("Invalid AI stage"); return; }
        aiRequest(call, stage, call.getString("system", ""), call.getString("user", ""), "", call.getLong("timeoutMillis", 30000L));
    }
    @PluginMethod public void testAiProvider(PluginCall call) {
        aiRequest(call, "test", "Return only the JSON object {\"ok\":true}.", "Connection test", call.getString("id", ""), 30000);
    }
    @PluginMethod public void cancelAi(PluginCall call) { ai.cancel(); call.resolve(); }
    @PluginMethod public void playlists(PluginCall call) {
        background(call, () -> { JSObject result = new JSObject(); result.put("playlists", new PlaylistStore(store).list()); return result; });
    }
    @PluginMethod public void savePlaylist(PluginCall call) {
        background(call, () -> {
            String id = new PlaylistStore(store).save(call.getString("id", ""), call.getString("name", ""), call.getString("description", ""), call.getObject("rules"), call.getArray("songIds"));
            changed(); JSObject result = new JSObject(); result.put("id", id); return result;
        });
    }
    @PluginMethod public void deletePlaylist(PluginCall call) {
        background(call, () -> { new PlaylistStore(store).delete(call.getString("id", "")); changed(); return new JSObject(); });
    }
    @PluginMethod public void playlistSong(PluginCall call) {
        background(call, () -> {
            PlaylistStore playlists = new PlaylistStore(store);
            if (Boolean.TRUE.equals(call.getBoolean("remove"))) playlists.remove(call.getString("id", ""), call.getString("songId", ""));
            else playlists.add(call.getString("id", ""), call.getString("songId", ""));
            changed(); return new JSObject();
        });
    }
    @PluginMethod public void playlistSongs(PluginCall call) {
        background(call, () -> {
            int offset = Math.max(0, call.getInt("offset", 0));
            int limit = Math.max(1, Math.min(200, call.getInt("limit", 60)));
            return JSObject.fromJSONObject(new PlaylistStore(store).songs(
                call.getString("id", ""), Boolean.TRUE.equals(call.getBoolean("offlineOnly")), offset, limit));
        });
    }
    private Map<Long, long[]> downloadStates() {
        Map<Long, long[]> states = new HashMap<>();
        try (Cursor cursor = downloads.query(new DownloadManager.Query())) {
            if (cursor != null) while (cursor.moveToNext()) {
                long id = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID));
                int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                long done = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                long total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                states.put(id, new long[]{status, done, total});
            }
        }
        return states;
    }
    private long[] downloadState(long id) {
        if (id <= 0) return null;
        try (Cursor cursor = downloads.query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            return new long[]{cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))};
        }
    }
    @PluginMethod public void favorite(PluginCall call) {
        background(call, () -> {
            store.favorite(call.getString("songId", ""), Boolean.TRUE.equals(call.getBoolean("favorite")));
            changed(); return new JSObject();
        });
    }
    @PluginMethod public void settings(PluginCall call) {
        background(call, () -> {
            JSObject result = JSObject.fromJSONObject(StreamCache.inspect(getContext())); result.put("wifiOnly", secrets.wifiOnly()); result.put("storageBytes", store.offlineBytes());
            result.put("artworkBytes", ArtworkStore.bytes(getContext()));
            result.put("partialBytes", store.partialBytes()); result.put("freeBytes", getContext().getFilesDir().getUsableSpace()); return result;
        });
    }
    @PluginMethod public void setWifiOnly(PluginCall call) {
        secrets.wifiOnly(Boolean.TRUE.equals(call.getBoolean("enabled"))); call.resolve();
        PlaybackService.prefetchSettingsChanged();
    }
    @PluginMethod public void clearStreamCache(PluginCall call) {
        PlaybackService.holdPrefetch();
        background(call, () -> JSObject.fromJSONObject(StreamCache.clear(getContext())));
    }
    @PluginMethod public void appearance(PluginCall call) {
        try { call.resolve(JSObject.fromJSONObject(new AppearanceSettings(getContext()).status())); }
        catch (Exception error) { call.reject(error.toString()); }
    }
    @PluginMethod public void setAppearance(PluginCall call) {
        try {
            new AppearanceSettings(getContext()).save(call.getData());
            applySystemTheme(); MusicWidgetProvider.refresh(getContext()); PlaybackService.widgetsChanged(); appearance(call);
        } catch (Exception error) { call.reject(error.toString()); }
    }
    @PluginMethod public void setLanguage(PluginCall call) {
        try {
            new AppearanceSettings(getContext()).language(call.getString("language", ""));
            MusicWidgetProvider.refresh(getContext()); call.resolve();
        } catch (Exception error) { call.reject(error.toString()); }
    }
    @PluginMethod public void openSystemSettings(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            try {
                Intent intent = "battery".equals(call.getString("section"))
                    ? new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getContext().getPackageName()));
                getActivity().startActivity(intent); call.resolve();
            } catch (Exception error) { call.reject("Please open Aurora's app settings manually"); }
        });
    }
    @PluginMethod public void pinWidget(PluginCall call) {
        JSObject result = new JSObject(); result.put("requested", false);
        if (Build.VERSION.SDK_INT < 26) { call.resolve(result); return; }
        AppWidgetManager manager = AppWidgetManager.getInstance(getContext());
        if (!manager.isRequestPinAppWidgetSupported()) { call.resolve(result); return; }
        Class<?> provider = "lyrics".equals(call.getString("kind")) ? LyricsWidgetProvider.class : MusicWidgetProvider.class;
        result.put("requested", manager.requestPinAppWidget(new ComponentName(getContext(), provider), null, null));
        call.resolve(result);
    }
    @PluginMethod public void launchState(PluginCall call) {
        Intent intent = getActivity().getIntent();
        boolean open = intent != null && (intent.getBooleanExtra("openPlayer", false)
            || "com.aurora.musicnext.OPEN_PLAYER".equals(intent.getAction()));
        if (intent != null) { intent.removeExtra("openPlayer"); if (open) intent.setAction(Intent.ACTION_MAIN); }
        JSObject result = new JSObject(); result.put("openPlayer", open); call.resolve(result);
    }
    @PluginMethod public void saveLyrics(PluginCall call) {
        background(call, () -> {
            String id = call.getString("songId", "");
            if (store.song(id) == null) throw new IllegalArgumentException("Song not found");
            store.lyrics(id, call.getString("text", ""));
            getActivity().runOnUiThread(() -> PlaybackService.assetsChanged(id));
            changed(); return new JSObject();
        });
    }
    @PluginMethod public void lookupAssets(PluginCall call) {
        resourceWork(call, () -> {
            String id = call.getString("songId", "");
            return assetChanged(id, assets.lookup(store, id, Boolean.TRUE.equals(call.getBoolean("force"))));
        });
    }
    @PluginMethod public void assetProviders(PluginCall call) {
        assetIo.execute(() -> {
            try { call.resolve(JSObject.fromJSONObject(new AssetProviderStore(getContext()).status())); }
            catch (Exception ignored) { call.reject("ASSET_FAILED"); }
        });
    }
    @PluginMethod public void saveAssetProviders(PluginCall call) {
        resourceWork(call, () -> {
            JSONObject result = new AssetProviderStore(getContext()).save(call.getArray("providers"), Boolean.TRUE.equals(call.getBoolean("itunesEnabled")));
            if (result.optBoolean("changed")) { assets.cancel(); assets.clearPreviews(); store.invalidateAssets(); }
            result.remove("changed"); return JSObject.fromJSONObject(result);
        });
    }
    @PluginMethod public void searchAssets(PluginCall call) {
        resourceWork(call, () -> {
            JSONObject query = call.getObject("query"); if (query == null) throw new IllegalArgumentException("Missing search metadata");
            JSObject result = new JSObject(); result.put("candidates", assets.search(store, call.getString("songId", ""), call.getString("kind", ""), query)); return result;
        });
    }
    @PluginMethod public void previewAsset(PluginCall call) {
        resourceWork(call, () -> {
            JSObject result = new JSObject(); result.put("candidate", assets.preview(call.getString("candidateId", ""), call.getString("songId", ""), call.getString("kind", ""))); return result;
        });
    }
    @PluginMethod public void applyAsset(PluginCall call) {
        resourceWork(call, () -> {
            String id = call.getString("songId", ""); return assetChanged(id, assets.apply(store, call.getString("candidateId", ""), id, call.getString("kind", "")));
        });
    }
    @PluginMethod public void clearAsset(PluginCall call) {
        resourceWork(call, () -> { String id = call.getString("songId", ""); return assetChanged(id, assets.reset(store, id, call.getString("kind", ""))); });
    }
    @PluginMethod public void cancelAssets(PluginCall call) {
        assets.cancel();
        if (destroyed) { call.resolve(); return; }
        assetIo.execute(() -> { assets.clearPreviews(); call.resolve(); });
    }
    @PluginMethod public void download(PluginCall call) {
        background(call, () -> { enqueue(call.getString("songId", "")); changed(); return new JSObject(); });
    }
    @PluginMethod public void downloadTasks(PluginCall call) {
        background(call, () -> JSObject.fromJSONObject(store.downloadTasks(Math.max(0, call.getInt("offset", 0)), Math.max(1, Math.min(60, call.getInt("limit", 60))))));
    }
    @PluginMethod public void pauseDownload(PluginCall call) { stopDownload(call, false); }
    @PluginMethod public void cancelDownload(PluginCall call) { stopDownload(call, true); }
    private void stopDownload(PluginCall call, boolean discard) {
        background(call, () -> {
            synchronized (DOWNLOAD_COMMANDS) {
                String id = call.getString("songId", ""); JSONObject song = store.song(id);
                if (song == null) throw new IllegalArgumentException("Song not found");
                File directory = new File(getContext().getFilesDir(), "offline"), partial = ResumableDownload.partial(directory, id);
                store.pauseDownload(id, discard); OfflineDownloadWorker.cancel(id);
                WorkManager.getInstance(getContext()).cancelUniqueWork("download-" + id).getResult().get();
                long oldId = song.optLong("downloadId");
                if (oldId > 0 && !song.optBoolean("offline")) { downloads.remove(oldId); store.clearPendingDownload(id); }
                long bytes = 0;
                if (discard) {
                    // A completion that won the DB transaction is kept; never delete playable music here.
                    boolean published = store.song(id).optBoolean("offline");
                    bytes = ResumableDownload.clear(partial, published ? null : OfflineDownloadWorker.target(directory, id, song.getString("format")));
                    store.clearDownloadProgress(id);
                }
                changed(); JSObject result = new JSObject(); result.put("freedBytes", bytes); return result;
            }
        });
    }
    @PluginMethod public void removeDownload(PluginCall call) {
        background(call, () -> {
            String id = call.getString("songId", "");
            JSONObject song = store.song(id);
            if (song == null) throw new IllegalArgumentException("歌曲不存在");
            long bytes = store.removeOffline(id);
            long oldId = song.optLong("downloadId");
            if (oldId > 0) try { downloads.remove(oldId); } catch (Exception ignored) {}
            changed();
            JSObject result = new JSObject(); result.put("freedBytes", bytes); return result;
        });
    }
    @PluginMethod public void downloadMany(PluginCall call) {
        background(call, () -> {
            JSArray ids = call.getArray("songIds");
            if (ids == null || ids.length() > 200) throw new IllegalArgumentException("一次最多下载 200 首");
            for (int i = 0; i < ids.length(); i++) enqueue(ids.getString(i));
            changed(); return new JSObject();
        });
    }
    private void enqueue(String id) throws Exception {
      synchronized (DOWNLOAD_COMMANDS) {
        JSONObject song = store.song(id);
        if (song == null) throw new IllegalArgumentException("歌曲不存在");
        if (song.optBoolean("offline")) return;
        if ("ready".equals(song.optString("downloadState"))) store.clearPendingDownload(id);
        for (androidx.work.WorkInfo work : WorkManager.getInstance(getContext()).getWorkInfosForUniqueWork("download-" + id).get())
            if (!work.getState().isFinished()) return;
        SecretStore.Connection connection = secrets.connection(); connection.authorizationFor(song.getString("remotePath"));
        long oldId = song.optLong("downloadId");
        long[] oldState = downloadState(oldId);
        if (oldState != null && (oldState[0] == DownloadManager.STATUS_PENDING || oldState[0] == DownloadManager.STATUS_RUNNING || oldState[0] == DownloadManager.STATUS_PAUSED)) return;
        if (oldId != 0) downloads.remove(oldId);
        File musicRoot = new File(getContext().getFilesDir(), "offline");
        if (!musicRoot.isDirectory() && !musicRoot.mkdirs()) throw new IllegalStateException("设备存储不可用");
        long expectedSize = ResumableDownload.remaining(ResumableDownload.partial(musicRoot, id), connection.revision + ":" + id, song.optLong("remoteSize"));
        long reserved = Math.max(0, store.pendingBytes(id));
        long safety = 64L * 1024 * 1024;
        // The worker renames .part within this directory; it never creates a second full copy.
        long required = expectedSize > Long.MAX_VALUE - safety - reserved
            ? Long.MAX_VALUE : expectedSize + reserved + safety;
        if (musicRoot.getUsableSpace() < required)
            throw new IllegalStateException("手机剩余空间不足，请先清理离线歌曲");
        String token = UUID.randomUUID().toString();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(OfflineDownloadWorker.class)
            .setInputData(new Data.Builder().putString("songId", id).putString("token", token).build())
            .setConstraints(new Constraints.Builder().setRequiredNetworkType(secrets.wifiOnly() ? NetworkType.UNMETERED : NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build();
        store.download(id, 0, null);
        store.scheduleDownload(id, token);
        try { WorkManager.getInstance(getContext()).enqueueUniqueWork("download-" + id, ExistingWorkPolicy.KEEP, request).getResult().get(); }
        catch (Exception error) { store.downloadState(id, token, "failed", "Cannot schedule download; retry"); throw error; }
      }
    }

    private interface Control { void apply(MediaController controller) throws Exception; }
    private void withController(PluginCall call, Control action) {
        ContextCompat.getMainExecutor(getContext()).execute(() -> {
            try {
                if (controllerFuture == null) {
                    SessionToken token = new SessionToken(getContext(), new ComponentName(getContext(), PlaybackService.class));
                    controllerFuture = new MediaController.Builder(getContext(), token).buildAsync();
                }
                ListenableFuture<MediaController> future = controllerFuture;
                future.addListener(() -> {
                    try { action.apply(future.get()); }
                    catch (Exception error) { call.reject(error.getMessage() == null ? error.toString() : error.getMessage()); }
                }, ContextCompat.getMainExecutor(getContext()));
            } catch (Exception error) { call.reject(error.toString()); }
        });
    }
    @PluginMethod public void play(PluginCall call) {
        io.execute(() -> {
            try {
                JSArray ids = call.getArray("songIds");
                String start = call.getString("startId", "");
                boolean offlineOnly = Boolean.TRUE.equals(call.getBoolean("offlineOnly"));
                JSObject libraryQuery = call.getObject("libraryQuery");
                String playlistId = call.getString("playlistId", "");
                if (!playlistId.isEmpty()) ids = new JSArray(new PlaylistStore(store).ids(playlistId, offlineOnly).toString());
                if ((ids == null && libraryQuery == null) || (ids != null && ids.length() > 5000))
                    throw new IllegalArgumentException("播放队列无效或过大");
                JSONObject selected = store.song(start);
                if (selected == null) throw new IllegalArgumentException("歌曲不存在");
                if (!selected.optBoolean("offline")) secrets.connection().authorizationFor(selected.getString("remotePath"));
                store.replaceQueue(ids, LibrarySelection.from(libraryQuery == null ? new JSONObject() : libraryQuery, offlineOnly), start);
                PlaybackSettings options = new PlaybackSettings(getContext());
                if (options.shuffle()) store.orderQueue(true, start);
                boolean smallLoop = options.repeat().equals("all") && store.queueSize() <= 40;
                List<MediaItem> queue = store.queueWindow(smallLoop ? 1 : Math.max(1, store.queueIndex(start) - 10), 40);
                int startIndex = -1;
                for (int i = 0; i < queue.size(); i++) if (queue.get(i).mediaId.equals(start)) startIndex = i;
                if (startIndex < 0) throw new IllegalArgumentException("起始歌曲不可播放");
                final int index = startIndex;
                withController(call, controller -> { controller.setMediaItems(queue, index, 0); controller.prepare(); controller.play(); call.resolve(); });
            } catch (Exception error) { call.reject(error.getMessage() == null ? error.toString() : error.getMessage()); }
        });
    }
    @PluginMethod public void toggle(PluginCall call) { withController(call, player -> {
        if (player.getPlayerError() == null && player.getPlayWhenReady()) player.pause();
        else {
            if (player.getMediaItemCount() > 0 && player.getPlaybackState() == androidx.media3.common.Player.STATE_IDLE) player.prepare();
            if (player.getPlaybackState() == androidx.media3.common.Player.STATE_ENDED) player.seekToDefaultPosition();
            player.play();
        }
        call.resolve();
    }); }
    @PluginMethod public void next(PluginCall call) { withController(call, player -> { if (player.hasNextMediaItem()) player.seekToNextMediaItem(); call.resolve(); }); }
    @PluginMethod public void previous(PluginCall call) { withController(call, player -> { player.seekToPreviousMediaItem(); call.resolve(); }); }
    @PluginMethod public void seek(PluginCall call) { withController(call, player -> { player.seekTo((long) (1000 * Math.max(0, call.getDouble("position", 0.0)))); call.resolve(); }); }
    @PluginMethod public void playbackOptions(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            try { call.resolve(JSObject.fromJSONObject(PlaybackService.options(getContext()))); }
            catch (Exception error) { call.reject(error.getMessage()); }
        });
    }
    @PluginMethod public void audioOutputs(PluginCall call) {
        background(call, () -> JSObject.fromJSONObject(AudioOutputInfo.status(getContext())));
    }
    @PluginMethod public void setPlaybackOptions(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            try {
                JSObject result = JSObject.fromJSONObject(PlaybackService.setOptions(getContext(), call.getData()));
                if (Boolean.FALSE.equals(call.getBoolean("rememberPlayback"))) {
                    long revision = new PlaybackSettings(getContext()).revision();
                    background(call, () -> { store.forgetPlayback(revision); return result; });
                } else call.resolve(result);
            }
            catch (Exception error) { call.reject(error.getMessage()); }
        });
    }
    @PluginMethod public void playback(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            try {
                JSObject result = JSObject.fromJSONObject(PlaybackService.snapshot());
                if (!result.isNull("songId")) { call.resolve(result); return; }
                background(call, () -> {
                    String[] saved = store.savedPlayback();
                    if (saved != null) {
                        result.put("songId", saved[0]); result.put("position", Long.parseLong(saved[1]) / 1000.0);
                        JSONObject song = store.song(saved[0]); if (song != null) result.put("duration", song.optDouble("duration"));
                    }
                    return result;
                });
            } catch (Exception error) { call.reject(error.toString()); }
        });
    }
}
