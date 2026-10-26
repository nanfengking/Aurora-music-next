package com.aurora.musicnext;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import android.net.Uri;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Format;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class MusicStore extends SQLiteOpenHelper {
    private final File offlineDirectory;
    MusicStore(Context context) {
        this(context, "aurora-mobile.db");
    }
    MusicStore(Context context, String databaseName) {
        super(context, databaseName, null, 11);
        offlineDirectory = new File(context.getFilesDir(), "offline");
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE songs(id TEXT PRIMARY KEY, title TEXT NOT NULL, artist TEXT NOT NULL DEFAULT '', album TEXT NOT NULL DEFAULT '', format TEXT NOT NULL, url TEXT NOT NULL UNIQUE, size INTEGER NOT NULL DEFAULT 0, modified TEXT NOT NULL DEFAULT '', favorite INTEGER NOT NULL DEFAULT 0, play_count INTEGER NOT NULL DEFAULT 0, local_path TEXT, download_id INTEGER NOT NULL DEFAULT 0, download_complete INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL)");
        db.execSQL("CREATE INDEX songs_title ON songs(title COLLATE NOCASE)");
        db.execSQL("CREATE TABLE history(id INTEGER PRIMARY KEY AUTOINCREMENT, song_id TEXT NOT NULL, action TEXT NOT NULL, timestamp INTEGER NOT NULL, progress REAL NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX history_time ON history(timestamp DESC)");
        upgradeToTwo(db);
        upgradeToThree(db);
        upgradeToFour(db);
        upgradeToFive(db);
        upgradeToSix(db);
        upgradeToSeven(db);
        upgradeToEight(db);
        upgradeToNine(db);
        upgradeToTen(db);
        upgradeToEleven(db);
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) upgradeToTwo(db);
        if (oldVersion < 3) upgradeToThree(db);
        if (oldVersion < 4) upgradeToFour(db);
        if (oldVersion < 5) upgradeToFive(db);
        if (oldVersion < 6) upgradeToSix(db);
        if (oldVersion < 7) upgradeToSeven(db);
        if (oldVersion < 8) upgradeToEight(db);
        if (oldVersion < 9) upgradeToNine(db);
        if (oldVersion < 10) upgradeToTen(db);
        if (oldVersion < 11) upgradeToEleven(db);
    }
    private static void upgradeToEleven(SQLiteDatabase db) {
        NativePreferenceStorage.createTable(db);
    }
    private static void upgradeToTen(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE songs ADD COLUMN replay_gain_db REAL");
        db.execSQL("ALTER TABLE songs ADD COLUMN replay_peak REAL");
    }
    private static void upgradeToNine(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE songs ADD COLUMN background_path TEXT");
        db.execSQL("ALTER TABLE songs ADD COLUMN cover_manual INTEGER NOT NULL DEFAULT 0");
    }
    private static void upgradeToEight(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE playback_state ADD COLUMN restore_revision INTEGER NOT NULL DEFAULT 0");
    }
    private static void upgradeToSeven(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE songs ADD COLUMN download_bytes INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE songs ADD COLUMN download_total INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE songs ADD COLUMN download_token TEXT NOT NULL DEFAULT ''");
        db.execSQL("ALTER TABLE songs ADD COLUMN download_failures INTEGER NOT NULL DEFAULT 0");
        db.execSQL("CREATE INDEX songs_download_state ON songs(download_state)");
    }

    private static void upgradeToSix(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE playback_queue ADD COLUMN original_position INTEGER NOT NULL DEFAULT 0");
        db.execSQL("UPDATE playback_queue SET original_position=position");
    }

    private static void upgradeToFive(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE songs ADD COLUMN last_played_at INTEGER NOT NULL DEFAULT 0");
        db.execSQL("UPDATE songs SET last_played_at=COALESCE((SELECT MAX(timestamp) FROM history WHERE song_id=songs.id AND action='start'),0)");
    }

    private static void upgradeToFour(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE playlists(id TEXT PRIMARY KEY,name TEXT NOT NULL,description TEXT NOT NULL DEFAULT '',rules TEXT,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE playlist_songs(playlist_id TEXT NOT NULL,song_id TEXT NOT NULL,position INTEGER NOT NULL,PRIMARY KEY(playlist_id,song_id))");
        db.execSQL("CREATE INDEX playlist_order ON playlist_songs(playlist_id,position)");
        db.execSQL("CREATE INDEX songs_favorites ON songs(favorite DESC,play_count DESC)");
        db.execSQL("CREATE INDEX history_song_time ON history(song_id,timestamp DESC)");
    }

    private static void upgradeToThree(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE songs ADD COLUMN duration REAL NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE songs ADD COLUMN cover_path TEXT");
        db.execSQL("ALTER TABLE songs ADD COLUMN lyrics TEXT");
        db.execSQL("ALTER TABLE songs ADD COLUMN genre TEXT NOT NULL DEFAULT ''");
        db.execSQL("ALTER TABLE songs ADD COLUMN year INTEGER");
        db.execSQL("ALTER TABLE songs ADD COLUMN codec TEXT");
        db.execSQL("ALTER TABLE songs ADD COLUMN bitrate INTEGER");
        db.execSQL("ALTER TABLE songs ADD COLUMN sample_rate INTEGER");
        db.execSQL("ALTER TABLE songs ADD COLUMN channels INTEGER");
        db.execSQL("ALTER TABLE songs ADD COLUMN assets_checked_at INTEGER NOT NULL DEFAULT 0");
    }

    void metadata(String id, MediaMetadata metadata, long durationMs, Format format, String cover) {
        ContentValues values = new ContentValues();
        if (metadata.title != null && !metadata.title.toString().trim().isEmpty()) values.put("title", metadata.title.toString());
        if (metadata.artist != null) values.put("artist", metadata.artist.toString());
        if (metadata.albumTitle != null) values.put("album", metadata.albumTitle.toString());
        if (metadata.genre != null) values.put("genre", metadata.genre.toString());
        if (metadata.releaseYear != null) values.put("year", metadata.releaseYear);
        if (durationMs > 0) values.put("duration", durationMs / 1000.0);
        if (format != null) {
            if (format.sampleMimeType != null) values.put("codec", format.sampleMimeType);
            if (format.averageBitrate > 0) values.put("bitrate", format.averageBitrate);
            if (format.sampleRate > 0) values.put("sample_rate", format.sampleRate);
            if (format.channelCount > 0) values.put("channels", format.channelCount);
            ReplayGain tags = ReplayGainTags.read(format.metadata);
            if (tags.gainDb == null) values.putNull("replay_gain_db"); else values.put("replay_gain_db", tags.gainDb);
            if (tags.peak == null) values.putNull("replay_peak"); else values.put("replay_peak", tags.peak);
        }
        if (values.size() > 0) getWritableDatabase().update("songs", values, "id=?", new String[]{id});
        if (cover != null) {
            ContentValues image = new ContentValues(); image.put("cover_path", cover);
            // Audio tag updates must not replace artwork explicitly chosen by the user.
            getWritableDatabase().update("songs", image, "id=? AND cover_manual=0", new String[]{id});
        }
    }

    void lyrics(String id, String text) {
        if (text.length() > 100_000) throw new IllegalArgumentException("Lyrics are too long");
        ContentValues values = new ContentValues(); values.put("lyrics", text);
        getWritableDatabase().update("songs", values, "id=?", new String[]{id});
    }
    void assets(String id, String lyrics, String cover) {
        SQLiteDatabase db = getWritableDatabase(); ContentValues values = new ContentValues();
        if (lyrics != null && !lyrics.isEmpty()) {
            values.put("lyrics", lyrics); db.update("songs", values, "id=? AND (lyrics IS NULL OR lyrics='')", new String[]{id}); values.clear();
        }
        if (cover != null) {
            try (Cursor row = db.rawQuery("SELECT cover_path FROM songs WHERE id=? AND cover_manual=0", new String[]{id})) {
                if (row.moveToFirst()) {
                    String previous = row.getString(0);
                    if (previous != null && !new File(previous).isFile()) {
                        ContentValues missing = new ContentValues(); missing.putNull("cover_path");
                        db.update("songs", missing, "id=? AND cover_manual=0 AND cover_path=?", new String[]{id, previous});
                    }
                }
            }
            values.put("cover_path", cover); db.update("songs", values, "id=? AND cover_manual=0 AND (cover_path IS NULL OR cover_path='')", new String[]{id}); values.clear();
        }
        values.put("assets_checked_at", System.currentTimeMillis());
        db.update("songs", values, "id=?", new String[]{id});
    }
    void applyAsset(String id, String kind, String value) {
        AssetWire.kind(kind); ContentValues values = new ContentValues();
        if (kind.equals("lyrics")) { if (value == null || value.length() > 100_000) throw new IllegalArgumentException("Invalid lyrics"); values.put("lyrics", value); }
        else { values.put(kind.equals("cover") ? "cover_path" : "background_path", value); if (kind.equals("cover")) values.put("cover_manual", 1); }
        if (getWritableDatabase().update("songs", values, "id=?", new String[]{id}) != 1) throw new IllegalArgumentException("Song not found");
    }
    void resetAsset(String id, String kind, String embeddedCover) {
        AssetWire.kind(kind); if (kind.equals("lyrics")) throw new IllegalArgumentException("Use the lyric editor");
        ContentValues values = new ContentValues(); values.put(kind.equals("cover") ? "cover_path" : "background_path", kind.equals("cover") ? embeddedCover : null);
        if (kind.equals("cover")) values.put("cover_manual", 0);
        if (getWritableDatabase().update("songs", values, "id=?", new String[]{id}) != 1) throw new IllegalArgumentException("Song not found");
    }
    void invalidateAssets() {
        getWritableDatabase().execSQL("UPDATE songs SET assets_checked_at=0 WHERE assets_checked_at<>0");
    }
    boolean needsAssets(String id) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT assets_checked_at FROM songs WHERE id=?", new String[]{id})) {
            return cursor.moveToFirst() && System.currentTimeMillis() - cursor.getLong(0) > 6 * 60 * 60 * 1000L;
        }
    }
    private static void upgradeToTwo(SQLiteDatabase db) {
        db.execSQL("CREATE INDEX songs_page ON songs(title COLLATE NOCASE,id)");
        db.execSQL("ALTER TABLE songs ADD COLUMN download_state TEXT NOT NULL DEFAULT ''");
        db.execSQL("ALTER TABLE songs ADD COLUMN download_error TEXT NOT NULL DEFAULT ''");
        db.execSQL("CREATE TABLE playback_queue(position INTEGER PRIMARY KEY,song_id TEXT NOT NULL UNIQUE)");
        db.execSQL("CREATE TABLE playback_state(id INTEGER PRIMARY KEY CHECK(id=1),song_id TEXT NOT NULL,position_ms INTEGER NOT NULL DEFAULT 0)");
    }

    static String idFor(String url) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < 12; i++) text.append(String.format(Locale.ROOT, "%02x", digest[i] & 0xff));
            return text.toString();
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    boolean upsert(String url, String name, long size, String modified) {
        String id = idFor(url);
        String filenameTitle = name.replaceFirst("(?i)\\.(mp3|m4a|mp4|flac|wav|ogg|opus|aac)$", "").trim();
        String title = filenameTitle;
        String artist = "";
        String[] parts = filenameTitle.split("\\s+-\\s+", 2);
        // Bare hyphens are ambiguous (AC-DC, live-version titles). Only infer the
        // common Chinese artist-title convention when the artist is Chinese text.
        if (parts.length == 1 && filenameTitle.matches("[\\u3400-\\u9fff·•\\s]{1,48}-[^-]*[\\u3400-\\u9fff][^-]*"))
            parts = filenameTitle.split("-", 2);
        if (parts.length == 2 && !parts[0].trim().isEmpty() && !parts[1].trim().isEmpty()) {
            artist = parts[0].trim(); title = parts[1].trim();
        }
        String format = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        boolean added, repairFallback;
        try (Cursor previous = getReadableDatabase().rawQuery("SELECT title,artist FROM songs WHERE id=?", new String[]{id})) {
            added = !previous.moveToFirst();
            // Rescans may improve an untouched filename fallback, never tags or
            // edited titles, favorites, playlists, downloads or chosen resources.
            repairFallback = !added && !artist.isEmpty() && previous.getString(1).isEmpty()
                && previous.getString(0).equals(filenameTitle);
        }
        ContentValues values = new ContentValues();
        values.put("id", id); values.put("title", title); values.put("artist", artist);
        values.put("format", format); values.put("url", url); values.put("size", size);
        values.put("modified", modified == null ? "" : modified);
        java.text.SimpleDateFormat timestamp = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        timestamp.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        values.put("created_at", timestamp.format(new java.util.Date()));
        if (added) getWritableDatabase().insertWithOnConflict("songs", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        else {
            values.remove("title"); values.remove("artist");
            values.remove("format"); values.remove("created_at");
            getWritableDatabase().update("songs", values, "id=?", new String[]{id});
            if (repairFallback) {
                ContentValues fallback = new ContentValues(); fallback.put("title", title); fallback.put("artist", artist);
                // Re-check at the write boundary: the player may have read real
                // tags after the earlier SELECT on another worker/connection.
                getWritableDatabase().update("songs", fallback, "id=? AND title=? AND artist=''", new String[]{id, filenameTitle});
            }
        }
        return added;
    }

    JSONObject song(String id) throws JSONException {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT * FROM songs WHERE id=?", new String[]{id})) {
            return cursor.moveToFirst() ? songJson(cursor, true) : null;
        }
    }
    JSONArray songs(String query, boolean offlineOnly, int offset, int limit) throws JSONException {
        return songs(new LibrarySelection(query, offlineOnly, null, null, false), offset, limit);
    }
    JSONArray songs(LibrarySelection selection, int offset, int limit) throws JSONException {
        JSONArray result = new JSONArray();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT * FROM songs WHERE " + selection.where
            + " ORDER BY title COLLATE NOCASE,id LIMIT " + limit + " OFFSET " + offset, selection.args)) {
            while (cursor.moveToNext()) result.put(songJson(cursor));
        }
        return result;
    }
    JSONObject catalogPage(long after, long ceiling, boolean offlineOnly) throws JSONException {
        if (ceiling <= 0) {
            try (Cursor row = getReadableDatabase().rawQuery("SELECT COALESCE(MAX(rowid),0) FROM songs", null)) {
                ceiling = row.moveToFirst() ? row.getLong(0) : 0;
            }
        }
        JSONArray result = new JSONArray(); long next = Math.max(0, after), total;
        String offline = offlineOnly ? " AND download_complete=1" : "";
        try (Cursor row = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM songs WHERE rowid<=?" + offline, new String[]{Long.toString(ceiling)})) {
            total = row.moveToFirst() ? row.getLong(0) : 0;
        }
        // No SELECT *, artwork decoding, lyric allocation or disk existence checks per catalog row.
        try (Cursor row = getReadableDatabase().rawQuery("SELECT rowid,id,title,artist,album,genre,duration,favorite,play_count,url,last_played_at FROM songs WHERE rowid>? AND rowid<=?"
            + offline + " ORDER BY rowid LIMIT 200", new String[]{Long.toString(next), Long.toString(ceiling)})) {
            while (row.moveToNext()) {
                next = row.getLong(0);
                result.put(new JSONObject().put("id", row.getString(1)).put("title", row.getString(2)).put("artist", row.getString(3))
                    .put("album", row.getString(4)).put("genre", row.getString(5)).put("duration", row.getDouble(6))
                    .put("isFavorite", row.getInt(7)).put("playCount", row.getInt(8)).put("remotePath", row.getString(9))
                    .put("lastPlayedAt", row.getLong(10) > 0 ? row.getLong(10) : JSONObject.NULL));
            }
        }
        return new JSONObject().put("songs", result).put("cursor", next).put("ceiling", ceiling).put("total", total)
            .put("hasMore", result.length() == 200 && next < ceiling);
    }
        long matchingSongs(String query, boolean offlineOnly) {
            return matchingSongs(new LibrarySelection(query, offlineOnly, null, null, false));
        }
        long matchingSongs(LibrarySelection selection) {
            try (Cursor cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM songs WHERE " + selection.where, selection.args)) {
                return cursor.moveToFirst() ? cursor.getLong(0) : 0;
            }
        }
        void replaceQueue(JSONArray ids, String query, boolean offlineOnly, String start) throws JSONException {
            replaceQueue(ids, new LibrarySelection(query, offlineOnly, null, null, false), start);
        }
        void replaceQueue(JSONArray ids, LibrarySelection selection, String start) throws JSONException {
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                NativePreferenceRecord preferences = NativePreferenceStorage.read(db);
                db.delete("playback_queue", null, null);
                if (ids == null) {
                    db.execSQL("INSERT INTO playback_queue(song_id) SELECT id FROM songs WHERE " + selection.where
                        + " ORDER BY title COLLATE NOCASE,id", selection.args);
                } else {
                    for (int i = 0; i < ids.length(); i++) {
                        db.execSQL("INSERT OR IGNORE INTO playback_queue(song_id) SELECT id FROM songs WHERE id=?"
                            + " AND " + selection.where, queueInsertArgs(ids.getString(i), selection.args));
                    }
                }
                if (queueIndex(start) < 0) throw new IllegalArgumentException("起始歌曲不可播放");
                db.execSQL("UPDATE playback_queue SET original_position=position");
                writePlayback(db, start, 0, preferences, preferences.restoreRevision);
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        }
    /** Reorder in SQLite, not a full-library Java/JavaScript array. Restore the original playlist order when disabled. */
    void orderQueue(boolean shuffle) { orderQueue(shuffle, null); }
    void orderQueue(boolean shuffle, String firstId) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            db.execSQL("CREATE TEMP TABLE IF NOT EXISTS queue_reorder(song_id TEXT,original_position INTEGER)");
            db.execSQL("DELETE FROM queue_reorder");
            String insert = "INSERT INTO queue_reorder SELECT song_id,original_position FROM playback_queue ORDER BY ";
            if (shuffle && firstId != null)
                db.execSQL(insert + "CASE WHEN song_id=? THEN 0 ELSE 1 END,RANDOM()", new Object[]{firstId});
            else db.execSQL(insert + (shuffle ? "RANDOM()" : "original_position"));
            db.execSQL("DELETE FROM playback_queue");
            db.execSQL("INSERT INTO playback_queue(song_id,original_position) SELECT song_id,original_position FROM queue_reorder ORDER BY rowid");
            db.execSQL("DELETE FROM queue_reorder"); db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    long queueSize() {
        try (Cursor row = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM playback_queue JOIN songs ON songs.id=playback_queue.song_id", null)) {
            return row.moveToFirst() ? row.getLong(0) : 0;
        }
    }
        private static Object[] queueInsertArgs(String id, String[] selection) {
            Object[] values = new Object[selection.length + 1]; values[0] = id;
            System.arraycopy(selection, 0, values, 1, selection.length); return values;
        }
    JSONObject collections(String kind, String query, boolean offlineOnly, int offset, int limit) throws JSONException {
        if (!kind.equals("albums") && !kind.equals("artists")) throw new IllegalArgumentException("Unknown collection kind");
        boolean albums = kind.equals("albums");
        String group = albums ? "album COLLATE NOCASE,artist COLLATE NOCASE" : "artist COLLATE NOCASE";
        String field = albums ? "album" : "artist";
        String where = "(" + field + " LIKE ? ESCAPE '\\')" + (offlineOnly ? " AND download_complete=1" : "");
        String[] args = new String[]{"%" + LibrarySelection.escape(query.trim()) + "%"};
        long total;
        try (Cursor row = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM (SELECT 1 FROM songs WHERE " + where + " GROUP BY " + group + ")", args)) {
            total = row.moveToFirst() ? row.getLong(0) : 0;
        }
        JSONArray result = new JSONArray();
        try (Cursor row = getReadableDatabase().rawQuery("SELECT " + field + ",artist,COUNT(*),MAX(cover_path) FROM songs WHERE " + where
            + " GROUP BY " + group + " ORDER BY " + field + " COLLATE NOCASE,artist COLLATE NOCASE LIMIT " + limit + " OFFSET " + offset, args)) {
            while (row.moveToNext()) {
                String cover = row.getString(3);
                result.put(new JSONObject().put("name", row.getString(0)).put("artist", row.getString(1)).put("count", row.getLong(2))
                    .put("coverPath", cover != null && new File(cover).isFile() ? cover : JSONObject.NULL));
            }
        }
        return new JSONObject().put("items", result).put("total", total).put("offset", offset).put("hasMore", offset + result.length() < total);
    }
        long queueIndex(String id) {
            try (Cursor cursor = getReadableDatabase().rawQuery("SELECT position FROM playback_queue WHERE song_id=?", new String[]{id})) {
                return cursor.moveToFirst() ? cursor.getLong(0) : -1;
            }
        }
        List<MediaItem> queueWindow(long first, int limit) throws JSONException {
            List<MediaItem> items = new ArrayList<>();
            try (Cursor cursor = getReadableDatabase().rawQuery("SELECT songs.id,url,title,artist,album,local_path,download_complete,cover_path,size,modified FROM playback_queue JOIN songs ON songs.id=playback_queue.song_id WHERE position>=? ORDER BY position LIMIT ?",
                new String[]{Long.toString(first), Integer.toString(limit)})) {
                while (cursor.moveToNext()) {
                    String local = cursor.getString(5), cover = cursor.getString(7);
                    boolean offline = cursor.getInt(6) == 1 && local != null && new File(local).isFile() && new File(local).length() > 0;
                    Uri uri = offline ? Uri.fromFile(new File(local)) : Uri.parse(cursor.getString(1));
                    items.add(new MediaItem.Builder().setMediaId(cursor.getString(0)).setUri(uri)
                        .setCustomCacheKey(offline ? null : CacheIdentity.source(cursor.getLong(8), cursor.getString(9)))
                        .setMediaMetadata(new MediaMetadata.Builder().setTitle(cursor.getString(2)).setArtist(cursor.getString(3))
                            .setAlbumTitle(cursor.getString(4)).setArtworkUri(cover == null || !new File(cover).isFile() ? null : Uri.fromFile(new File(cover))).build()).build());
                }
            }
            return items;
        }
    List<MediaItem> queueWindow(long first, int limit, boolean wrap) throws JSONException {
        List<MediaItem> items = queueWindow(first, limit);
        if (wrap && items.size() < limit) {
            int remaining = (int) Math.min(limit - items.size(), Math.max(0, queueSize() - items.size()));
            if (remaining > 0) items.addAll(queueWindow(1, remaining));
        }
        return items;
    }
    static final class PrefetchSource {
        final String address, stamp; final long size;
        PrefetchSource(String address, long size, String modified) { this.address = address; this.size = size; stamp = CacheIdentity.source(size, modified); }
    }
    PrefetchSource prefetchSource(String id) {
        // Do not load artwork, lyrics, history or the complete catalog for a one-track decision.
        try (Cursor row = getReadableDatabase().rawQuery("SELECT url,size,modified,local_path,download_complete,download_state FROM songs WHERE id=?", new String[]{id})) {
            if (!row.moveToFirst() || !PrefetchPolicy.sizeAllowed(row.getLong(1))) return null;
            String local = row.getString(3), state = row.getString(5);
            if (row.getInt(4) == 1 && local != null && new File(local).isFile() && new File(local).length() > 0
                || "pending".equals(state) || "running".equals(state)) return null;
            return new PrefetchSource(row.getString(0), row.getLong(1), row.getString(2));
        }
    }
        void savePlayback(String id, long position) {
            savePlaybackInTransaction(id, position, null);
        }
        void savePlayback(String id, long position, long revision) {
            savePlaybackInTransaction(id, position, revision);
        }
        private void savePlaybackInTransaction(String id, long position, Long revision) {
            SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
            try {
                NativePreferenceRecord preferences = NativePreferenceStorage.read(db);
                writePlayback(db, id, position, preferences, revision == null ? preferences.restoreRevision : revision);
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        }
        private static void writePlayback(SQLiteDatabase db, String id, long position, NativePreferenceRecord preferences, long revision) {
            if ((Boolean) preferences.values.get("rememberPlayback") && revision == preferences.restoreRevision)
                db.execSQL("INSERT OR REPLACE INTO playback_state(id,song_id,position_ms,restore_revision) VALUES(1,?,?,?)", new Object[]{id, Math.max(0, position), revision});
        }
        void forgetPlayback(long disabledRevision) {
            // A late clear must not erase a newer enabled session's recovery point.
            SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
            try {
                NativePreferenceRecord preferences = NativePreferenceStorage.read(db);
                db.delete("playback_state", "restore_revision<?", new String[]{Long.toString(Math.min(disabledRevision, preferences.restoreRevision))});
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        }
        String[] savedPlayback() {
            SQLiteDatabase db = getReadableDatabase(); db.beginTransaction();
            try {
                NativePreferenceRecord preferences = NativePreferenceStorage.read(db);
                String[] result = null;
                if ((Boolean) preferences.values.get("rememberPlayback"))
                    try (Cursor cursor = db.rawQuery("SELECT song_id,position_ms FROM playback_state WHERE id=1 AND restore_revision=?", new String[]{Long.toString(preferences.restoreRevision)})) {
                        if (cursor.moveToFirst()) result = new String[]{cursor.getString(0), cursor.getString(1)};
                    }
                db.setTransactionSuccessful(); return result;
            } finally { db.endTransaction(); }
        }
        void downloadState(String id, String state, String error) {
            ContentValues values = new ContentValues();
            values.put("download_state", state); values.put("download_error", error);
            getWritableDatabase().update("songs", values, "id=?", new String[]{id});
        }
    void scheduleDownload(String id, String token) {
        ContentValues value = new ContentValues(); value.put("download_token", token); value.put("download_state", "pending");
        value.put("download_error", ""); value.put("download_failures", 0); value.put("download_id", 0);
        getWritableDatabase().update("songs", value, "id=? AND download_complete=0", new String[]{id});
    }
    boolean downloadActive(String id, String token) {
        try (Cursor row = getReadableDatabase().rawQuery("SELECT 1 FROM songs WHERE id=? AND download_token=? AND download_complete=0 AND download_state IN ('pending','running')", new String[]{id, token})) {
            return row.moveToFirst();
        }
    }
    void downloadState(String id, String token, String state, String error) {
        ContentValues value = new ContentValues(); value.put("download_state", state); value.put("download_error", error);
        getWritableDatabase().update("songs", value, "id=? AND download_token=? AND download_complete=0 AND download_state IN ('pending','running')", new String[]{id, token});
    }
    String downloadToken(String id) {
        try (Cursor row = getReadableDatabase().rawQuery("SELECT download_token FROM songs WHERE id=?", new String[]{id})) { return row.moveToFirst() ? row.getString(0) : ""; }
    }
    void downloadProgress(String id, String token, long received, long total) {
        downloadProgress(id, token, received, total, 0);
    }
    void downloadProgress(String id, String token, long received, long total, long priorBytes) {
        getWritableDatabase().execSQL("UPDATE songs SET download_failures=CASE WHEN ?>MAX(download_bytes,?) THEN 0 ELSE download_failures END,download_bytes=?,download_total=? WHERE id=? AND download_token=? AND download_complete=0",
            new Object[]{Math.max(0, received), Math.max(0, priorBytes), Math.max(0, received), Math.max(0, total), id, token});
    }
    int downloadFailure(String id, String token) {
        getWritableDatabase().execSQL("UPDATE songs SET download_failures=download_failures+1 WHERE id=? AND download_token=? AND download_complete=0 AND download_state IN ('pending','running')", new Object[]{id, token});
        try (Cursor row = getReadableDatabase().rawQuery("SELECT download_failures FROM songs WHERE id=? AND download_token=?", new String[]{id, token})) { return row.moveToFirst() ? row.getInt(0) : 0; }
    }
    boolean commitDownload(String id, String token, File partial, File target) throws IOException {
        SQLiteDatabase db = getWritableDatabase(); boolean renamed = false;
        try {
          db.beginTransaction();
          try {
            if (!downloadActive(id, token)) return false;
            if (!partial.renameTo(target)) throw new IOException("Cannot save offline file");
            renamed = true; finishDownload(id, target.getAbsolutePath());
            ContentValues values = new ContentValues(); values.put("download_bytes", target.length()); values.put("download_total", target.length());
            db.update("songs", values, "id=?", new String[]{id}); db.setTransactionSuccessful();
          } finally { db.endTransaction(); }
          return true;
        } catch (Exception error) {
            if (renamed && !target.renameTo(partial)) throw new IOException("Offline publication could not be rolled back", error);
            throw error instanceof IOException ? (IOException) error : new IOException("Cannot publish offline file", error);
        }
    }
    void pauseDownload(String id, boolean discard) {
        ContentValues values = new ContentValues(); values.put("download_state", discard ? "cancelled" : "paused"); values.put("download_error", "");
        if (discard) values.put("download_token", "");
        getWritableDatabase().update("songs", values, "id=? AND download_complete=0", new String[]{id});
    }
    void clearDownloadProgress(String id) {
        ContentValues values = new ContentValues(); values.put("download_bytes", 0); values.put("download_total", 0);
        getWritableDatabase().update("songs", values, "id=? AND download_state='cancelled' AND download_complete=0", new String[]{id});
    }
    JSONObject downloadTasks(int offset, int limit) throws JSONException {
        JSONArray items = new JSONArray(); long total, active;
        String where = "download_complete=0 AND download_state<>'' AND (download_state<>'cancelled' OR download_bytes>0)";
        try (Cursor row = getReadableDatabase().rawQuery("SELECT COUNT(*),COALESCE(SUM(CASE WHEN download_state IN ('pending','running') THEN 1 ELSE 0 END),0) FROM songs WHERE " + where, null)) {
            row.moveToFirst(); total = row.getLong(0); active = row.getLong(1);
        }
        try (Cursor row = getReadableDatabase().rawQuery("SELECT id,title,artist,download_state,download_bytes,download_total,size,download_error FROM songs WHERE " + where
            + " ORDER BY CASE WHEN download_state IN ('pending','running') THEN 0 ELSE 1 END,title COLLATE NOCASE,id LIMIT ? OFFSET ?", new String[]{Integer.toString(limit), Integer.toString(offset)})) {
            while (row.moveToNext()) items.put(new JSONObject().put("songId", row.getString(0)).put("title", row.getString(1)).put("artist", row.getString(2))
                .put("state", row.getString(3)).put("receivedBytes", row.getLong(4)).put("totalBytes", row.getLong(5) > 0 ? row.getLong(5) : row.getLong(6)).put("error", row.getString(7)));
        }
        return new JSONObject().put("items", items).put("total", total).put("active", active).put("offset", offset).put("hasMore", offset + items.length() < total);
    }
    long partialBytes() {
        try (Cursor row = getReadableDatabase().rawQuery("SELECT COALESCE(SUM(download_bytes),0) FROM songs WHERE download_complete=0", null)) { return row.moveToFirst() ? Math.max(0, row.getLong(0)) : 0; }
    }
    long totalSongs() {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM songs", null)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0;
        }
    }

    JSONArray overview() throws JSONException {
        JSONArray result = new JSONArray();
        // A bounded pool from indexed favourites/recent plays and a distributed library sample.
        // The complete library remains searchable/pageable independently of this recommendation pool.
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT * FROM songs WHERE id IN "
            + "(SELECT id FROM songs ORDER BY favorite DESC,play_count DESC,id LIMIT 180) "
            + "OR id IN (SELECT song_id FROM history ORDER BY id DESC LIMIT 150) "
            + "OR rowid IN (SELECT MIN(rowid) FROM songs GROUP BY CAST(rowid / MAX(1,(SELECT COUNT(*)/250 FROM songs)) AS INTEGER)) "
            + "ORDER BY favorite DESC,play_count DESC,id LIMIT 600", null)) {
            while (cursor.moveToNext()) result.put(songJson(cursor));
        }
        return result;
    }
    JSONArray history() throws JSONException {
        JSONArray result = new JSONArray();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT id,song_id,action,timestamp,progress FROM history ORDER BY id DESC LIMIT 3000", null)) {
            while (cursor.moveToNext()) {
                JSONObject item = new JSONObject();
                item.put("id", cursor.getLong(0)); item.put("songId", cursor.getString(1));
                item.put("action", cursor.getString(2)); item.put("timestamp", cursor.getLong(3));
                item.put("progress", cursor.getDouble(4)); result.put(item);
            }
        }
        return result;
    }
    void event(String id, String action, double progress) {
        if (id == null || id.isEmpty()) return;
        ContentValues values = new ContentValues();
        values.put("song_id", id); values.put("action", action);
        values.put("timestamp", System.currentTimeMillis()); values.put("progress", Math.max(0, Math.min(1, progress)));
        getWritableDatabase().insert("history", null, values);
        if ("start".equals(action)) getWritableDatabase().execSQL("UPDATE songs SET play_count=play_count+1,last_played_at=? WHERE id=?", new Object[]{values.getAsLong("timestamp"), id});
        getWritableDatabase().execSQL("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY id DESC LIMIT 3000)");
        LibraryEvents.changed();
    }
    void favorite(String id, boolean value) {
        ContentValues values = new ContentValues(); values.put("favorite", value ? 1 : 0);
        getWritableDatabase().update("songs", values, "id=?", new String[]{id});
    }
    void download(String id, long downloadId, String localPath) {
        ContentValues values = new ContentValues(); values.put("download_id", downloadId); values.put("local_path", localPath); values.put("download_complete", 0);
        getWritableDatabase().update("songs", values, "id=?", new String[]{id});
    }
    void clearPendingDownload(String id) {
        ContentValues values = new ContentValues();
        values.put("download_id", 0); values.putNull("local_path"); values.put("download_complete", 0);
        getWritableDatabase().update("songs", values, "id=?", new String[]{id});
    }
    String[] pendingDownload(long downloadId) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT id,local_path FROM songs WHERE download_id=? AND download_complete=0", new String[]{Long.toString(downloadId)})) {
            return cursor.moveToFirst() ? new String[]{cursor.getString(0), cursor.getString(1)} : null;
        }
    }
    void finishDownload(String id, String localPath) {
        ContentValues values = new ContentValues(); values.put("download_complete", 1); values.put("local_path", localPath);
        getWritableDatabase().update("songs", values, "id=?", new String[]{id});
        downloadState(id, "ready", "");
    }
    long removeOffline(String id) throws IOException {
        String path;
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT local_path FROM songs WHERE id=? AND download_complete=1", new String[]{id})) {
            if (!cursor.moveToFirst()) return 0;
            path = cursor.getString(0);
        }
        long bytes = 0;
        if (path != null) {
            File file = new File(path);
            if (!offlineDirectory.getCanonicalFile().equals(file.getCanonicalFile().getParentFile())
                || !file.getName().startsWith(id + ".")) throw new SecurityException("离线文件路径异常，拒绝删除");
            if (file.exists()) {
                bytes = file.length();
                if (!file.delete()) throw new IOException("无法删除离线文件");
            }
        }
        ContentValues values = new ContentValues();
        values.putNull("local_path"); values.put("download_id", 0); values.put("download_complete", 0);
        values.put("download_state", ""); values.put("download_error", ""); values.put("download_bytes", 0); values.put("download_total", 0); values.put("download_token", "");
        getWritableDatabase().update("songs", values, "id=?", new String[]{id});
        return bytes;
    }
    long offlineBytes() {
        long total = 0;
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT local_path FROM songs WHERE download_complete=1", null)) {
            while (cursor.moveToNext()) {
                String path = cursor.getString(0);
                if (path != null) { File file = new File(path); if (file.isFile()) total += file.length(); }
            }
        }
        return total;
    }
    long pendingBytes(String exceptId) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT COALESCE(SUM(MAX(0,CASE WHEN download_total>0 THEN download_total ELSE size END-download_bytes)),0) FROM songs WHERE (download_id>0 OR download_state IN ('pending','running')) AND download_complete=0 AND id<>?", new String[]{exceptId})) {
            return cursor.moveToFirst() ? Math.max(0, cursor.getLong(0)) : 0;
        }
    }
    JSONObject songJson(Cursor row) throws JSONException { return songJson(row, false); }
    private JSONObject songJson(Cursor row, boolean detail) throws JSONException {
        String localPath = value(row, "local_path");
        long expected = number(row, "size");
        File file = localPath == null ? null : new File(localPath);
        boolean offline = number(row, "download_complete") != 0 && file != null && file.isFile() && file.length() > 0;
        String url = value(row, "url");
        JSONObject song = new JSONObject();
        song.put("id", value(row, "id")); song.put("title", value(row, "title"));
        song.put("artist", value(row, "artist")); song.put("album", value(row, "album"));
        song.put("year", row.isNull(row.getColumnIndexOrThrow("year")) ? JSONObject.NULL : number(row, "year"));
        song.put("genre", value(row, "genre")); song.put("duration", row.getDouble(row.getColumnIndexOrThrow("duration")));
        song.put("filePath", url); song.put("format", value(row, "format"));
        String cover = value(row, "cover_path");
        song.put("coverPath", cover != null && new File(cover).isFile() ? cover : JSONObject.NULL);
        String background = detail ? value(row, "background_path") : null;
        song.put("customArtistImage", background != null && new File(background).isFile() ? background : JSONObject.NULL);
        String lyricText = detail ? value(row, "lyrics") : null;
        song.put("customLyrics", !detail || lyricText == null ? JSONObject.NULL : lyricText); song.put("sourceType", "webdav");
        song.put("remotePath", url); song.put("remoteSize", expected);
        song.put("remoteModified", value(row, "modified"));
        song.put("localCachePath", offline ? localPath : JSONObject.NULL);
        song.put("cacheSize", offline ? file.length() : 0);
        song.put("isFavorite", number(row, "favorite")); song.put("playCount", number(row, "play_count"));
        song.put("lastPlayedAt", number(row, "last_played_at") > 0 ? number(row, "last_played_at") : JSONObject.NULL);
        song.put("createdAt", value(row, "created_at"));
        String downloadState = value(row, "download_state");
        long received = number(row, "download_bytes"), total = number(row, "download_total") > 0 ? number(row, "download_total") : expected;
        song.put("offline", offline); song.put("downloading", "pending".equals(downloadState) || "running".equals(downloadState));
        song.put("downloadState", downloadState); song.put("downloadBytes", received); song.put("downloadTotal", total);
        song.put("downloadProgress", total > 0 ? Math.min(100, 100.0 * received / total) : 0);
        song.put("downloadError", value(row, "download_error"));
        song.put("downloadId", number(row, "download_id"));
        String codec = value(row, "codec"); song.put("codec", codec == null ? JSONObject.NULL : codec);
        for (String[] column : new String[][]{{"bitrate", "bitrate"}, {"sampleRate", "sample_rate"}, {"channels", "channels"}})
            song.put(column[0], row.isNull(row.getColumnIndexOrThrow(column[1])) ? JSONObject.NULL : number(row, column[1]));
        if (detail) {
            for (String[] column : new String[][]{{"replayGainDb", "replay_gain_db"}, {"replayPeak", "replay_peak"}})
                song.put(column[0], row.isNull(row.getColumnIndexOrThrow(column[1])) ? JSONObject.NULL : row.getDouble(row.getColumnIndexOrThrow(column[1])));
        }
        return song;
    }
    private static String value(Cursor row, String column) { return row.getString(row.getColumnIndexOrThrow(column)); }
    private static long number(Cursor row, String column) { return row.getLong(row.getColumnIndexOrThrow(column)); }
}
