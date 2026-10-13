package com.aurora.musicnext;

import android.content.Context;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import okhttp3.Request;
import org.json.JSONArray;
import org.json.JSONObject;

/** Serialized foreground resource work. Only native-issued, song-bound preview tokens can be applied. */
final class AssetLookup {
    private final Context context;
    private final AssetHttp http = new AssetHttp();
    private final AssetProviderStore providers;
    private final Map<String, Candidate> candidates = new LinkedHashMap<>();
    private static final class Candidate {
        final String songId, kind, revision;
        final JSONObject row, provider;
        final long expires = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(10);
        String preview;
        Candidate(String songId, String kind, String revision, JSONObject row, JSONObject provider) {
            this.songId = songId; this.kind = kind; this.revision = revision; this.row = row; this.provider = provider;
        }
    }
    AssetLookup(Context context) { this.context = context; providers = new AssetProviderStore(context); }
    void cancel() { http.cancel(); }
    void clearPreviews() {
        // An old Activity's cleanup must not delete previews issued by its replacement.
        for (Candidate candidate : candidates.values()) ArtworkStore.removePreview(context, candidate.preview);
        candidates.clear();
    }
    static JSONArray sources(JSONObject config, String kind) throws Exception {
        JSONArray result = new JSONArray(), list = config.getJSONArray("providers");
        for (int i = 0; i < list.length(); i++) {
            JSONObject source = list.getJSONObject(i);
            if (source.optBoolean("enabled") && source.optString("kind").equals(kind)) result.put(source);
        }
        if (kind.equals("lyrics")) result.put(new JSONObject().put("name", "LRCLIB").put("kind", kind)
            .put("builtin", "lrclib").put("baseUrl", "https://lrclib.net/api/search"));
        if (kind.equals("cover") && config.optBoolean("itunesEnabled")) result.put(new JSONObject().put("name", "iTunes").put("kind", kind)
            .put("builtin", "itunes").put("baseUrl", "https://itunes.apple.com/search"));
        return result;
    }
    private JSONArray rows(AssetHttp.Operation operation, JSONObject provider, JSONObject query, int level) throws Exception {
        String body = new String(http.get(operation, AssetWire.request(provider, query, level), 2 * 1024 * 1024), StandardCharsets.UTF_8);
        return AssetWire.rows(provider.getString("kind"), body, provider.optString("builtin").equals("itunes"));
    }
    static Request imageRequest(JSONObject provider, String image) {
        Request.Builder request = new Request.Builder().url(PublicAssetUrl.image(image)).header("User-Agent", "AuroraMusicNext/Android (artwork)");
        // A CDN must never receive the endpoint key; redirects are disabled in AssetHttp.
        if (provider.optString("builtin").isEmpty() && !provider.optString("key").isEmpty()
            && PublicAssetUrl.sameOrigin(PublicAssetUrl.image(provider.optString("baseUrl")), PublicAssetUrl.image(image))) request.header("Authorization", "Bearer " + provider.optString("key"));
        return request.build();
    }
    private void current(AssetHttp.Operation operation, String revision) throws Exception {
        operation.check();
        if (!revision.equals(providers.stored().optString("revision"))) throw new IOException("Resource configuration changed");
    }
    JSONObject lookup(MusicStore store, String id, boolean force) throws Exception {
        JSONObject song = requiredSong(store, id);
        if (!force && !store.needsAssets(id)) return song;
        JSONObject query = new JSONObject().put("title", song.optString("title").trim()).put("artist", song.optString("artist").trim())
            .put("album", song.optString("album").trim()).put("duration", song.optDouble("duration"));
        if (query.optString("artist").equals("未知艺术家")) query.put("artist", "");
        if (query.optString("title").isEmpty()) return song;
        JSONObject config = providers.stored(); String revision = config.optString("revision"); AssetHttp.Operation operation = http.begin();
        String text = null, cover = null;
        if (song.optString("customLyrics").isEmpty() || song.isNull("customLyrics")) {
            JSONArray services = sources(config, "lyrics");
            for (int level = 0; level < 3 && text == null; level++) {
                if ((level == 0 && (query.optString("album").isEmpty() || query.optString("artist").isEmpty()))
                    || (level == 1 && query.optString("artist").isEmpty())) continue;
                for (int s = 0; s < services.length() && text == null; s++) {
                    operation.check();
                    try {
                        JSONArray rows = rows(operation, services.getJSONObject(s), query, level); double best = -Double.MAX_VALUE;
                        for (int i = 0; i < rows.length(); i++) {
                            JSONObject row = rows.getJSONObject(i); if (!AssetWire.matches(query, row, "lyrics", level)) continue;
                            double score = (row.optBoolean("synced") ? 10 : 0) + (AssetMatch.same(query.optString("artist"), row.optString("artist")) ? 4 : 0)
                                + (AssetMatch.same(query.optString("album"), row.optString("album")) ? 2 : 0);
                            if (query.optDouble("duration") > 0 && row.optDouble("duration") > 0)
                                score -= Math.min(10, Math.abs(query.optDouble("duration") - row.optDouble("duration")) / 10);
                            if (score > best) { best = score; text = row.getString("text"); }
                        }
                    } catch (Exception ignored) { operation.check(); }
                }
            }
        }
        if (song.isNull("coverPath") && !query.optString("artist").isEmpty()) {
            JSONArray services = sources(config, "cover");
            for (int s = 0; s < services.length() && cover == null; s++) {
                operation.check(); JSONObject provider = services.getJSONObject(s);
                try {
                    JSONArray rows = rows(operation, provider, query, 0);
                    for (int i = 0; i < rows.length(); i++) {
                        JSONObject row = rows.getJSONObject(i); if (!AssetWire.matches(query, row, "cover", 0)) continue;
                        byte[] image = http.get(operation, imageRequest(provider, row.getString("imageUrl")), 8 * 1024 * 1024);
                        current(operation, revision); cover = ArtworkStore.saveRemote(context, id, image); break;
                    }
                } catch (Exception ignored) { operation.check(); }
            }
        }
        current(operation, revision); store.assets(id, text, cover); return store.song(id);
    }
    JSONArray search(MusicStore store, String id, String kind, JSONObject input) throws Exception {
        requiredSong(store, id); AssetWire.kind(kind); JSONObject query = AssetWire.query(input);
        clearPreviews(); JSONObject config = providers.stored(); String revision = config.optString("revision");
        AssetHttp.Operation operation = http.begin(); JSONArray result = new JSONArray(), services = sources(config, kind);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int s = 0; s < services.length() && result.length() < 8; s++) {
            operation.check(); JSONObject provider = services.getJSONObject(s);
            try {
                JSONArray rows = rows(operation, provider, query, 0);
                for (int i = 0; i < rows.length() && result.length() < 8; i++) {
                    JSONObject row = rows.getJSONObject(i); String identity = kind.equals("lyrics") ? row.getString("text") : row.getString("imageUrl");
                    if (!seen.add(identity)) continue;
                    current(operation, revision); String token = UUID.randomUUID().toString().replace("-", "").substring(0, 24);
                    Candidate candidate = new Candidate(id, kind, revision, row, provider); candidates.put(token, candidate); result.put(dto(token, candidate));
                }
            } catch (Exception ignored) { operation.check(); }
        }
        current(operation, revision); return result;
    }
    private JSONObject dto(String token, Candidate candidate) throws Exception {
        JSONObject row = candidate.row;
        JSONObject result = new JSONObject().put("id", token).put("title", row.getString("title")).put("artist", row.getString("artist"))
            .put("album", row.getString("album")).put("source", candidate.provider.getString("name"))
            .put("sourceUrl", PublicAssetUrl.origin(PublicAssetUrl.image(candidate.provider.getString("baseUrl")))).put("synced", row.optBoolean("synced"));
        if (candidate.kind.equals("lyrics")) result.put("text", row.getString("text"));
        if (candidate.preview != null) result.put("imagePath", candidate.preview);
        return result;
    }
    private Candidate candidate(String token, String id, String kind) throws Exception {
        Candidate candidate = candidates.get(token);
        if (candidate == null || !candidate.songId.equals(id) || !candidate.kind.equals(AssetWire.kind(kind)) || System.nanoTime() >= candidate.expires
            || !candidate.revision.equals(providers.stored().optString("revision"))) throw new IOException("Resource preview expired. Search again");
        return candidate;
    }
    JSONObject preview(String token, String id, String kind) throws Exception {
        Candidate candidate = candidate(token, id, kind); AssetHttp.Operation operation = http.begin();
        if (!kind.equals("lyrics") && candidate.preview == null) {
            byte[] bytes = http.get(operation, imageRequest(candidate.provider, candidate.row.getString("imageUrl")), 8 * 1024 * 1024);
            current(operation, candidate.revision); candidate.preview = ArtworkStore.preview(context, token, bytes);
        }
        current(operation, candidate.revision); return dto(token, candidate);
    }
    JSONObject apply(MusicStore store, String token, String id, String kind) throws Exception {
        Candidate candidate = candidate(token, id, kind); JSONObject song = requiredSong(store, id); AssetHttp.Operation operation = http.begin();
        if (kind.equals("lyrics")) { current(operation, candidate.revision); store.applyAsset(id, kind, candidate.row.getString("text")); }
        else {
            if (candidate.preview == null) throw new IOException("Preview the image before applying it");
            String old = song.isNull(kind.equals("cover") ? "coverPath" : "customArtistImage") ? null : song.optString(kind.equals("cover") ? "coverPath" : "customArtistImage");
            String path = ArtworkStore.choose(context, candidate.preview, old);
            try { current(operation, candidate.revision); store.applyAsset(id, kind, path); }
            catch (Exception error) { ArtworkStore.removeChoice(context, path); throw error; }
            ArtworkStore.removeChoice(context, old);
        }
        clearPreviews(); return store.song(id);
    }
    JSONObject reset(MusicStore store, String id, String kind) throws Exception {
        JSONObject song = requiredSong(store, id); String old = song.optString(kind.equals("cover") ? "coverPath" : "customArtistImage", "");
        store.resetAsset(id, kind, ArtworkStore.embedded(context, id)); ArtworkStore.removeChoice(context, old); clearPreviews(); return store.song(id);
    }
    private static JSONObject requiredSong(MusicStore store, String id) throws Exception {
        JSONObject song = store.song(id); if (song == null) throw new IllegalArgumentException("Song not found"); return song;
    }
}
