package com.aurora.musicnext;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Playlist membership lives in SQLite, not an unbounded WebView store. */
final class PlaylistStore {
    private final MusicStore store;
    PlaylistStore(MusicStore store) { this.store = store; }

    JSONArray list() throws Exception {
        JSONArray result = new JSONArray();
        try (Cursor rows = store.getReadableDatabase().rawQuery("SELECT playlists.*,(SELECT COUNT(*) FROM playlist_songs WHERE playlist_id=playlists.id) AS count FROM playlists ORDER BY updated_at DESC LIMIT 200", null)) {
            while (rows.moveToNext()) {
                JSONObject row = new JSONObject();
                row.put("id", rows.getString(rows.getColumnIndexOrThrow("id"))); row.put("name", rows.getString(rows.getColumnIndexOrThrow("name")));
                row.put("description", rows.getString(rows.getColumnIndexOrThrow("description")));
                String rules = rows.getString(rows.getColumnIndexOrThrow("rules")); row.put("smartRules", rules == null ? JSONObject.NULL : rules);
                row.put("count", rows.getInt(rows.getColumnIndexOrThrow("count"))); result.put(row);
            }
        }
        return result;
    }
    String save(String id, String name, String description, JSONObject rules, JSONArray ids) throws Exception {
        if (name.trim().isEmpty() || name.length() > 100 || description.length() > 500) throw new IllegalArgumentException("Invalid playlist name/description");
        if (rules != null) validateRules(rules);
        SQLiteDatabase db = store.getWritableDatabase();
        if (id.isEmpty()) id = UUID.randomUUID().toString();
        else require(id);
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues(); values.put("id", id); values.put("name", name.trim()); values.put("description", description);
            values.put("rules", rules == null ? null : rules.toString()); values.put("updated_at", System.currentTimeMillis());
            if (db.update("playlists", values, "id=?", new String[]{id}) == 0) {
                try (Cursor count = db.rawQuery("SELECT COUNT(*) FROM playlists", null)) { if (count.moveToFirst() && count.getInt(0) >= 200) throw new IllegalStateException("At most 200 playlists"); }
                values.put("created_at", System.currentTimeMillis()); db.insertOrThrow("playlists", null, values);
            }
            if (ids != null) {
                if (ids.length() > 5000) throw new IllegalArgumentException("At most 5000 tracks per playlist");
                db.delete("playlist_songs", "playlist_id=?", new String[]{id});
                for (int i = 0; i < ids.length(); i++) db.execSQL("INSERT OR IGNORE INTO playlist_songs SELECT ?,id,? FROM songs WHERE id=?", new Object[]{id, i, ids.getString(i)});
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        return id;
    }
    void add(String id, String songId) throws Exception {
        if (!require(id).isNull("rules")) throw new IllegalArgumentException("Smart playlists are managed by rules");
        store.getWritableDatabase().execSQL("INSERT OR IGNORE INTO playlist_songs SELECT ?,id,COALESCE((SELECT MAX(position)+1 FROM playlist_songs WHERE playlist_id=?),0) FROM songs WHERE id=? AND (SELECT COUNT(*) FROM playlist_songs WHERE playlist_id=?)<5000", new Object[]{id,id,songId,id});
        touch(id);
    }
    void remove(String id, String songId) throws Exception { require(id); store.getWritableDatabase().delete("playlist_songs", "playlist_id=? AND song_id=?", new String[]{id,songId}); touch(id); }
    void delete(String id) throws Exception {
        require(id); SQLiteDatabase db = store.getWritableDatabase(); db.beginTransaction();
        try { db.delete("playlist_songs", "playlist_id=?", new String[]{id}); db.delete("playlists", "id=?", new String[]{id}); db.setTransactionSuccessful(); }
        finally { db.endTransaction(); }
    }
    private void touch(String id) { ContentValues values = new ContentValues(); values.put("updated_at", System.currentTimeMillis()); store.getWritableDatabase().update("playlists", values, "id=?", new String[]{id}); }
    private JSONObject require(String id) throws Exception {
        try (Cursor row = store.getReadableDatabase().rawQuery("SELECT rules FROM playlists WHERE id=?", new String[]{id})) {
            if (!row.moveToFirst()) throw new IllegalArgumentException("Playlist not found");
            JSONObject result = new JSONObject(); result.put("rules", row.isNull(0) ? JSONObject.NULL : row.getString(0)); return result;
        }
    }
    static void validateRules(JSONObject rules) throws Exception {
        if (rules.optInt("version") != 1 || !(rules.opt("favoritesOnly") instanceof Boolean)
            || !(rules.opt("artist") instanceof String) || !(rules.opt("genre") instanceof String)
            || rules.optString("artist").length() > 100 || rules.optString("genre").length() > 100
            || !java.util.Arrays.asList("recent", "most-played", "newest", "title").contains(rules.optString("sort"))) throw new IllegalArgumentException("Invalid playlist rules");
        for (int[] limit : new int[][]{{0,365},{1,5000},{0,1440}}) {
            String key = limit[1] == 365 ? "recentDays" : limit[1] == 5000 ? "limit" : "durationMinutes";
            Object number = rules.opt(key);
            if (!(number instanceof Number) || ((Number) number).doubleValue() != ((Number) number).intValue()
                || ((Number) number).intValue() < limit[0] || ((Number) number).intValue() > limit[1]) throw new IllegalArgumentException("Invalid playlist rules");
        }
    }
    JSONArray ids(String id, boolean offlineOnly) throws Exception {
        JSONObject playlist = require(id);
        String sql; List<String> args = new ArrayList<>();
        JSONObject rules = playlist.isNull("rules") ? null : new JSONObject(playlist.getString("rules"));
        int limit = 5000; double maximum = 0;
        if (rules == null) {
            sql = "SELECT songs.id,duration FROM playlist_songs JOIN songs ON songs.id=playlist_songs.song_id WHERE playlist_id=?";
            args.add(id); if (offlineOnly) sql += " AND download_complete=1"; sql += " ORDER BY position";
        } else {
            validateRules(rules); limit = rules.getInt("limit"); maximum = rules.getInt("durationMinutes") * 60;
            sql = "SELECT id,duration FROM songs WHERE 1=1";
            if (rules.getBoolean("favoritesOnly")) sql += " AND favorite=1";
            if (offlineOnly) sql += " AND download_complete=1";
            for (String field : new String[]{"artist", "genre"}) if (!rules.getString(field).trim().isEmpty()) {
                sql += " AND " + field + " LIKE ? ESCAPE '\\'";
                args.add("%" + rules.getString(field).trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
            }
            if (rules.getInt("recentDays") > 0) { sql += " AND id IN (SELECT song_id FROM history WHERE timestamp>=?)"; args.add(Long.toString(System.currentTimeMillis() - rules.getInt("recentDays") * 86400000L)); }
            String sort = rules.getString("sort");
            sql += " ORDER BY " + (sort.equals("recent") ? "COALESCE((SELECT MAX(timestamp) FROM history WHERE song_id=songs.id),0) DESC"
                : sort.equals("most-played") ? "play_count DESC" : sort.equals("newest") ? "created_at DESC" : "title COLLATE NOCASE") + ",id";
        }
        JSONArray result = new JSONArray(); double seconds = 0;
        try (Cursor rows = store.getReadableDatabase().rawQuery(sql, args.toArray(new String[0]))) {
            while (rows.moveToNext() && result.length() < limit) {
                double duration = rows.getDouble(1);
                if (maximum > 0 && (duration <= 0 || seconds + duration > maximum)) continue;
                result.put(rows.getString(0)); seconds += duration;
            }
        }
        return result;
    }
    JSONObject songs(String id, boolean offlineOnly, int offset, int limit) throws Exception {
        JSONArray ids = ids(id, offlineOnly), songs = new JSONArray();
        for (int i = offset; i < Math.min(ids.length(), offset + limit); i++) {
            JSONObject song = store.song(ids.getString(i));
            if (song != null) { song.put("customLyrics", JSONObject.NULL); songs.put(song); }
        }
        JSONObject result = new JSONObject(); result.put("songs", songs); result.put("total", ids.length()); result.put("offset", offset); result.put("hasMore", offset + songs.length() < ids.length()); return result;
    }
}
