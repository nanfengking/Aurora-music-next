package com.aurora.musicnext;

import java.util.Arrays;
import java.util.regex.Pattern;
import okhttp3.HttpUrl;
import okhttp3.Request;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Small explicit JSON contract, compatible with desktop resource endpoints. No HTML scraping. */
final class AssetWire {
    private static final Pattern TIMED = Pattern.compile("\\[\\d{1,3}:\\d{2}");
    static String kind(String value) {
        if (!Arrays.asList("lyrics", "cover", "background").contains(value)) throw new IllegalArgumentException("Invalid resource kind");
        return value;
    }
    static String field(JSONObject object, String... names) {
        for (String name : names) { Object value = object.opt(name); if (value instanceof String && !((String) value).trim().isEmpty()) return (String) value; }
        return "";
    }
    static JSONObject query(JSONObject input) throws Exception {
        JSONObject result = new JSONObject();
        for (String key : new String[]{"title", "artist", "album"}) {
            Object raw = input.opt(key); if (raw != null && raw != JSONObject.NULL && !(raw instanceof String)) throw new IllegalArgumentException("Invalid search metadata");
            String value = raw instanceof String ? ((String) raw).trim() : "";
            if (value.length() > 200) throw new IllegalArgumentException("Search metadata is too long");
            result.put(key, value);
        }
        if (result.getString("title").isEmpty() && result.getString("artist").isEmpty() && result.getString("album").isEmpty())
            throw new IllegalArgumentException("Enter search metadata");
        return result;
    }
    static Request request(JSONObject provider, JSONObject query, int level) throws Exception {
        String source = provider.optString("builtin"), kind = kind(provider.getString("kind"));
        HttpUrl.Builder url = HttpUrl.get(provider.getString("baseUrl")).newBuilder();
        String title = query.optString("title"), artist = level < 2 ? query.optString("artist") : "", album = level == 0 ? query.optString("album") : "";
        if (source.equals("lrclib")) {
            url.addQueryParameter("track_name", title);
            if (!artist.isEmpty()) url.addQueryParameter("artist_name", artist);
            if (!album.isEmpty()) url.addQueryParameter("album_name", album);
        } else if (source.equals("itunes")) url.addQueryParameter("term", title + " " + artist + " " + album)
            .addQueryParameter("media", "music").addQueryParameter("entity", "song").addQueryParameter("limit", "30");
        else url.addQueryParameter("title", title).addQueryParameter("artist", artist).addQueryParameter("album", album).addQueryParameter("kind", kind);
        Request.Builder request = new Request.Builder().url(url.build()).header("User-Agent", "AuroraMusicNext/Android (metadata)");
        String key = provider.optString("key"); if (!key.isEmpty() && source.isEmpty()) request.header("Authorization", "Bearer " + key);
        return request.build();
    }
    static JSONArray rows(String kind, String body, boolean itunes) throws Exception {
        JSONTokener parser = new JSONTokener(body); Object parsed = parser.nextValue(); JSONArray raw;
        if (parser.nextClean() != 0) throw new IllegalArgumentException("Trailing resource response data");
        if (parsed instanceof JSONArray) raw = (JSONArray) parsed;
        else if (parsed instanceof JSONObject) {
            JSONObject object = (JSONObject) parsed; raw = object.optJSONArray("results");
            if (raw == null) raw = object.optJSONArray(kind.equals("background") ? "artists" : "album");
        } else throw new IllegalArgumentException("Resource response is not JSON");
        if (raw == null) throw new IllegalArgumentException("Resource response requires a results array");
        JSONArray result = new JSONArray();
        for (int i = 0; i < Math.min(30, raw.length()); i++) {
            JSONObject row = raw.optJSONObject(i); if (row == null) continue;
            String title = field(row, "trackName", "title", "strTrack", "strAlbum").trim();
            String artist = field(row, "artistName", "artist", "strArtist").trim(), album = field(row, "albumName", "album", "collectionName", "strAlbum").trim();
            String text = row.optBoolean("instrumental") ? "" : field(row, "syncedLyrics", "plainLyrics", "lyrics", "text");
            String image = field(row, "imageUrl", "strAlbumThumb", "artworkUrl100", "strArtistFanart", "strArtistFanart2");
            if (kind.equals("lyrics") ? text.isEmpty() || text.length() > 100_000 : image.isEmpty()) continue;
            if (!kind.equals("lyrics")) {
                try {
                    HttpUrl url = PublicAssetUrl.image(image);
                    if (itunes && !url.host().endsWith(".mzstatic.com")) continue;
                    image = itunes ? url.newBuilder().encodedPath(url.encodedPath().replace("100x100bb", "600x600bb")).build().toString() : url.toString();
                } catch (IllegalArgumentException unsafe) { continue; }
            }
            double duration = row.optDouble("duration", row.optDouble("trackTimeMillis", 0) / 1000);
            if (!Double.isFinite(duration) || duration < 0 || duration > 86400) duration = 0;
            result.put(new JSONObject().put("title", title.substring(0, Math.min(200, title.length())))
                .put("artist", artist.substring(0, Math.min(200, artist.length()))).put("album", album.substring(0, Math.min(200, album.length())))
                .put("duration", duration).put("text", kind.equals("lyrics") ? text : "").put("synced", TIMED.matcher(text).find()).put("imageUrl", image));
        }
        return result;
    }
    static boolean matches(JSONObject query, JSONObject row, String kind, int level) {
        String title = query.optString("title"), artist = query.optString("artist"), album = query.optString("album");
        if (kind.equals("lyrics")) return AssetMatch.lyrics(title, artist, album, row.optString("title"), row.optString("artist"), row.optString("album"), level);
        return AssetMatch.albumCover(artist, album, row.optString("artist"), row.optString("album"))
            || AssetMatch.cover(title, artist, album, query.optDouble("duration"), row.optString("title"), row.optString("artist"), row.optString("album"), row.optDouble("duration"));
    }
}
