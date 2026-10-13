package com.aurora.musicnext;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONArray;
import org.json.JSONObject;
import static org.junit.Assert.*;

/** Isolated fixture DB: these tests must never erase or modify the user's library. */
@RunWith(AndroidJUnit4.class)
public class MusicStoreInstrumentedTest {
    private static final String DATABASE = "aurora-instrumented-fixture.db";
    private MusicStore store;
    private Context context;
    @Before public void before() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.getSharedPreferences("playback-options-" + DATABASE, Context.MODE_PRIVATE).edit().clear().commit();
        context.deleteDatabase(DATABASE); store = new MusicStore(context, DATABASE);
        SQLiteDatabase db = store.getWritableDatabase(); db.beginTransaction();
        try {
            for (int i = 0; i < 720; i++) store.upsert("https://example.com/music/" + i + ".m4a", "Artist - Track " + i + ".m4a", 5000, "");
            db.execSQL("UPDATE songs SET duration=180,genre='Pop'"); db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    @After public void after() {
        if (store != null) store.close(); context.deleteDatabase(DATABASE);
        context.getSharedPreferences("playback-options-" + DATABASE, Context.MODE_PRIVATE).edit().clear().commit();
    }
    private String id(int i) { return MusicStore.idFor("https://example.com/music/" + i + ".m4a"); }
    @Test public void chineseFilenameFallbackPopulatesTheArtistWithoutSplittingAmbiguousTitles() throws Exception {
        String[][] cases = {
            {"周杰伦-星晴.m4a", "星晴", "周杰伦"},
            {" 周杰伦 - 夜曲.MP3", "夜曲", "周杰伦"},
            {"Artist  -  Song.flac", "Song", "Artist"},
            {"AC-DC.mp3", "AC-DC", ""},
            {"晴天.mp3", "晴天", ""},
            {"周杰伦-现场-晴天.m4a", "周杰伦-现场-晴天", ""}
        };
        for (int i = 0; i < cases.length; i++) {
            String address = "https://example.com/filename/" + i + ".m4a";
            store.upsert(address, cases[i][0], 5000, "");
            JSONObject song = store.song(MusicStore.idFor(address));
            assertEquals(cases[i][0], cases[i][1], song.getString("title"));
            assertEquals(cases[i][0], cases[i][2], song.getString("artist"));
            assertEquals("", song.getString("album"));
        }
        assertEquals(1, store.collections("artists", "周杰伦", false, 0, 60).getInt("total"));
    }
    @Test public void rescanningOnlyRepairsTheUnchangedFilenameFallbackAndPreservesUserData() throws Exception {
        String address = "https://example.com/filename/existing.m4a", songId = MusicStore.idFor(address);
        store.upsert(address, "周杰伦-星晴.m4a", 5000, "");
        // State produced by preview.9 before this filename convention was recognized.
        store.getWritableDatabase().execSQL("UPDATE songs SET title='周杰伦-星晴',artist='',album='自选专辑',favorite=1,lyrics='[00:01]手动歌词' WHERE id=?", new Object[]{songId});
        assertFalse(store.upsert(address, "周杰伦-星晴.m4a", 6000, "new"));
        JSONObject repaired = store.song(songId);
        assertEquals("星晴", repaired.getString("title")); assertEquals("周杰伦", repaired.getString("artist"));
        assertEquals("自选专辑", repaired.getString("album")); assertEquals(1, repaired.getInt("isFavorite"));
        assertEquals("[00:01]手动歌词", repaired.getString("customLyrics")); assertEquals(6000, repaired.getLong("remoteSize"));
        store.metadata(songId, new androidx.media3.common.MediaMetadata.Builder().setTitle("标签歌名").setArtist("标签艺术家").build(), 180000, null, null);
        store.upsert(address, "周杰伦-星晴.m4a", 7000, "newer");
        assertEquals("标签歌名", store.song(songId).getString("title")); assertEquals("标签艺术家", store.song(songId).getString("artist"));
        store.getWritableDatabase().execSQL("UPDATE songs SET title='手动歌名',artist='' WHERE id=?", new Object[]{songId});
        store.upsert(address, "周杰伦-星晴.m4a", 7000, "newest");
        assertEquals("手动歌名", store.song(songId).getString("title")); assertEquals("", store.song(songId).getString("artist"));
    }
    @Test public void rescanningCannotOverwriteTagsWrittenAfterItsFallbackRead() throws Exception {
        String address = "https://example.com/filename/racing.m4a", songId = MusicStore.idFor(address);
        store.upsert(address, "周杰伦-星晴.m4a", 5000, "");
        SQLiteDatabase db = store.getWritableDatabase();
        db.execSQL("UPDATE songs SET title='周杰伦-星晴',artist='' WHERE id=?", new Object[]{songId});
        // Write tags at the real SQLite boundary after upsert has read the old
        // fallback, but before its source-size update completes.
        db.execSQL("CREATE TEMP TRIGGER fixture_concurrent_tags BEFORE UPDATE OF size ON songs WHEN OLD.url='https://example.com/filename/racing.m4a' BEGIN UPDATE songs SET title='刚读取的标签歌名',artist='标签艺术家' WHERE id=OLD.id; END");
        try {
            store.upsert(address, "周杰伦-星晴.m4a", 6000, "new");
            assertEquals("刚读取的标签歌名", store.song(songId).getString("title"));
            assertEquals("标签艺术家", store.song(songId).getString("artist"));
            assertEquals(6000, store.song(songId).getLong("remoteSize"));
        } finally { db.execSQL("DROP TRIGGER fixture_concurrent_tags"); }
    }
    @Test public void replayGainMetadataIsNullableAndKeptOutOfCatalogPages() throws Exception {
        String song = id(719); assertTrue(store.song(song).isNull("replayGainDb")); assertTrue(store.song(song).isNull("replayPeak"));
        androidx.media3.common.MediaMetadata metadata = new androidx.media3.common.MediaMetadata.Builder().build();
        androidx.media3.common.Format format = new androidx.media3.common.Format.Builder().setSampleMimeType("audio/flac")
            .setMetadata(new androidx.media3.common.Metadata(new androidx.media3.extractor.metadata.vorbis.VorbisComment("REPLAYGAIN_TRACK_GAIN", "-6.25 dB"),
                new androidx.media3.extractor.metadata.vorbis.VorbisComment("REPLAYGAIN_TRACK_PEAK", "1.2345"))).build();
        store.metadata(song, metadata, 180000, format, null);
        assertEquals(-6.25, store.song(song).getDouble("replayGainDb"), 0); assertEquals(1.2345, store.song(song).getDouble("replayPeak"), 0);
        assertFalse(store.songs("Track 719", false, 0, 60).getJSONObject(0).has("replayGainDb"));
        store.metadata(song, metadata, 180000, null, null); assertEquals(-6.25, store.song(song).getDouble("replayGainDb"), 0);
        store.metadata(song, metadata, 180000, new androidx.media3.common.Format.Builder().setSampleMimeType("audio/flac").build(), null);
        assertTrue(store.song(song).isNull("replayGainDb")); assertTrue(store.song(song).isNull("replayPeak"));
    }
    @Test public void replayGainPreferenceDefaultsOffAndValidationIsAtomic() throws Exception {
        String profile = "playback-options-" + DATABASE;
        PlaybackSettings options = new PlaybackSettings(context, profile);
        assertFalse(options.normalizeVolume()); assertFalse(options.status().getBoolean("normalizeVolume"));
        options.save(new JSONObject().put("normalizeVolume", true).put("volume", .7));
        assertTrue(new PlaybackSettings(context, profile).normalizeVolume()); assertEquals(.7f, options.volume(), 0);
        long revision = options.revision();
        assertThrows(IllegalArgumentException.class, () -> options.save(new JSONObject().put("normalizeVolume", false).put("volume", 2)));
        assertThrows(org.json.JSONException.class, () -> options.save(new JSONObject().put("normalizeVolume", 42).put("volume", .3)));
        assertTrue(options.normalizeVolume()); assertEquals(.7f, options.volume(), 0); assertEquals(revision, options.revision());
        options.save(new JSONObject().put("normalizeVolume", false)); assertFalse(options.normalizeVolume()); assertEquals(.7f, options.volume(), 0);
    }
    @Test public void crossfadeDurationDefaultsOffAndValidationIsAtomic() throws Exception {
        String profile = "playback-options-" + DATABASE;
        PlaybackSettings options = new PlaybackSettings(context, profile);
        assertEquals(0, options.crossfadeSeconds()); assertEquals(0, options.status().getInt("crossfadeSeconds"));
        options.save(new JSONObject().put("crossfadeSeconds", 3).put("volume", .6));
        assertEquals(3, new PlaybackSettings(context, profile).crossfadeSeconds());
        assertThrows(IllegalArgumentException.class, () -> options.save(new JSONObject().put("crossfadeSeconds", 2.5).put("volume", .2)));
        assertEquals(3, options.crossfadeSeconds()); assertEquals(.6f, options.volume(), 0);
        assertThrows(IllegalArgumentException.class, () -> options.save(new JSONObject().put("crossfadeSeconds", 1).put("volume", 2)));
        assertEquals(3, options.crossfadeSeconds()); options.save(new JSONObject().put("crossfadeSeconds", 0)); assertEquals(0, options.crossfadeSeconds());
    }
    @Test public void prefetchDefaultsOffAndSharesTheQueueFileStamp() throws Exception {
        PlaybackSettings options = new PlaybackSettings(context, "playback-options-" + DATABASE);
        assertFalse(options.prefetch()); options.save(new JSONObject().put("prefetch", true)); assertTrue(options.prefetch());
        assertThrows(IllegalArgumentException.class, () -> options.save(new JSONObject().put("prefetch", false).put("volume", 2)));
        assertTrue(options.prefetch()); store.replaceQueue(null, "", false, id(719));
        MusicStore.PrefetchSource source = store.prefetchSource(id(719)); assertNotNull(source); assertEquals(5000, source.size);
        androidx.media3.common.MediaItem item = store.queueWindow(store.queueIndex(id(719)), 1).get(0);
        assertEquals(source.address, item.localConfiguration.uri.toString()); assertEquals(source.stamp, item.localConfiguration.customCacheKey);
        androidx.media3.common.MediaItem transported = androidx.media3.common.MediaItem.fromBundle(item.toBundleIncludeLocalConfiguration());
        assertEquals(source.stamp, transported.localConfiguration.customCacheKey);
        store.downloadState(id(719), "pending", ""); assertNull(store.prefetchSource(id(719)));
        store.downloadState(id(719), "paused", ""); assertNotNull(store.prefetchSource(id(719)));
        store.getWritableDatabase().execSQL("UPDATE songs SET size=67108865 WHERE id=?", new Object[]{id(719)}); assertNull(store.prefetchSource(id(719)));
    }
    @Test public void manualResourcesSurviveAutomaticAndAudioTagUpdates() throws Exception {
        String song = id(719); store.applyAsset(song, "cover", "fixture-chosen.jpg"); store.applyAsset(song, "background", "fixture-background.jpg");
        store.applyAsset(song, "lyrics", "manual lyric");
        store.metadata(song, new androidx.media3.common.MediaMetadata.Builder().setTitle("Updated title").build(), 180000, null, "fixture-embedded.jpg");
        store.assets(song, "automatic lyric", "fixture-automatic.jpg");
        try (android.database.Cursor row = store.getReadableDatabase().rawQuery("SELECT title,cover_path,cover_manual,background_path,lyrics FROM songs WHERE id=?", new String[]{song})) {
            assertTrue(row.moveToFirst()); assertEquals("Updated title", row.getString(0)); assertEquals("fixture-chosen.jpg", row.getString(1));
            assertEquals(1, row.getInt(2)); assertEquals("fixture-background.jpg", row.getString(3)); assertEquals("manual lyric", row.getString(4));
        }
        store.resetAsset(song, "cover", "fixture-embedded.jpg"); store.resetAsset(song, "background", null);
        try (android.database.Cursor row = store.getReadableDatabase().rawQuery("SELECT cover_path,cover_manual,background_path,lyrics FROM songs WHERE id=?", new String[]{song})) {
            assertTrue(row.moveToFirst()); assertEquals("fixture-embedded.jpg", row.getString(0)); assertEquals(0, row.getInt(1));
            assertTrue(row.isNull(2)); assertEquals("manual lyric", row.getString(3));
        }
        store.invalidateAssets(); assertTrue(store.needsAssets(song)); assertEquals(720, store.totalSongs());
    }
    @Test public void completeLibraryPaginationAndEscapedSearch() throws Exception {
        assertEquals(720, store.totalSongs()); assertEquals(60, store.songs("", false, 0, 60).length());
        assertEquals(1, store.songs("Track 719", false, 0, 60).length());
        assertEquals(0, store.songs("%", false, 0, 60).length());
        store.lyrics(id(719), "[00:00]Fixture lyric");
        assertTrue(store.songs("Track 719", false, 0, 60).getJSONObject(0).isNull("customLyrics"));
        assertEquals("[00:00]Fixture lyric", store.song(id(719)).getString("customLyrics"));
        assertTrue(store.overview().length() <= 600);
    }
    @Test public void databaseQueueCoversSongsBeyondRenderedPage() throws Exception {
        store.replaceQueue(null, "", false, id(719));
        assertTrue(store.queueIndex(id(719)) > 0);
        assertEquals(40, store.queueWindow(1, 40).size());
        assertEquals(id(719), store.savedPlayback()[0]);
        store.savePlayback(id(719), 12345); assertEquals("12345", store.savedPlayback()[1]);
    }
    @Test public void forgettingPlaybackPreservesHistoryAndQueueAndRejectsStaleCheckpoints() throws Exception {
        PlaybackSettings options = new PlaybackSettings(context, "playback-options-" + DATABASE);
        store.replaceQueue(null, "", false, id(719)); store.event(id(719), "start", 0);
        long old = options.revision(); store.savePlayback(id(719), 12345, old);
        options.save(new JSONObject().put("rememberPlayback", false));
        long disabled = options.revision(); assertTrue(disabled > old);
        store.forgetPlayback(disabled); store.savePlayback(id(719), 54321, old);
        assertNull(store.savedPlayback()); assertEquals(720, store.queueSize());
        try (android.database.Cursor row = store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM playback_state", null)) {
            assertTrue(row.moveToFirst()); assertEquals(0, row.getInt(0));
        }
        assertEquals(1, store.song(id(719)).getInt("playCount"));
        options.save(new JSONObject().put("rememberPlayback", true));
        assertNull(store.savedPlayback()); store.savePlayback(id(719), 67890, options.revision());
        store.forgetPlayback(disabled); store.savePlayback(id(719), 54321, old);
        assertEquals("67890", store.savedPlayback()[1]);
    }
    @Test public void manualAndSmartPlaylistsUseFullDatabase() throws Exception {
        PlaylistStore playlists = new PlaylistStore(store);
        String manual = playlists.save("", "Test list", "", null, new JSONArray().put(id(719)));
        playlists.add(manual, id(718)); playlists.add(manual, id(718));
        assertEquals(2, playlists.ids(manual, false).length());
        playlists.remove(manual, id(719)); assertEquals(id(718), playlists.ids(manual, false).getString(0));
        store.favorite(id(719), true);
        JSONObject rules = new JSONObject().put("version",1).put("favoritesOnly",true).put("artist","Artist")
            .put("genre","pop").put("recentDays",0).put("limit",50).put("durationMinutes",10).put("sort","most-played");
        String smart = playlists.save("", "Smart list", "", rules, null);
        assertEquals(id(719), playlists.ids(smart, false).getString(0));
        assertEquals(1, playlists.songs(smart, false, 0, 60).getInt("total"));
        rules.put("genre", "%"); playlists.save(smart,"Smart list","",rules,null);
        assertEquals(0, playlists.ids(smart, false).length());
        playlists.delete(manual); assertEquals(1, playlists.list().length());
    }
    @Test public void pendingStorageReservationIncludesWorkManagerDownloads() {
        store.downloadState(id(1), "pending", ""); store.downloadState(id(2), "running", "");
        assertEquals(10000, store.pendingBytes(id(3))); assertEquals(5000, store.pendingBytes(id(1)));
    }
    @Test public void checkpointsReserveOnlyRemainingBytesAndArePaged() throws Exception {
        store.scheduleDownload(id(1), "fixture-token"); store.downloadProgress(id(1), "fixture-token", 2000, 5000);
        assertEquals(3000, store.pendingBytes(id(2))); assertEquals(2000, store.partialBytes());
        assertEquals(2000, store.song(id(1)).getLong("downloadBytes"));
        assertEquals(1, store.downloadTasks(0, 60).getInt("active"));
        for (int i = 2; i < 72; i++) store.scheduleDownload(id(i), "fixture-token");
        JSONObject page = store.downloadTasks(0, 60); assertEquals(71, page.getInt("total")); assertEquals(60, page.getJSONArray("items").length()); assertTrue(page.getBoolean("hasMore"));
        assertEquals(11, store.downloadTasks(60, 60).getJSONArray("items").length());
        JSONObject task = page.getJSONArray("items").getJSONObject(0);
        assertFalse(task.has("remotePath")); assertFalse(task.has("downloadToken")); assertFalse(task.has("customLyrics"));
    }
    @Test public void pauseAndGenerationGuardRejectLateWorkerStateAndCompletion() throws Exception {
        store.scheduleDownload(id(1), "old"); store.pauseDownload(id(1), false);
        store.downloadState(id(1), "old", "running", ""); assertFalse(store.downloadActive(id(1), "old"));
        assertEquals("paused", store.song(id(1)).getString("downloadState"));
        store.scheduleDownload(id(1), "new"); store.downloadProgress(id(1), "old", 4000, 5000);
        assertEquals(0, store.song(id(1)).getLong("downloadBytes"));
        assertFalse(store.commitDownload(id(1), "old", new java.io.File("unused-fixture-partial"), new java.io.File("unused-fixture-target")));
        assertTrue(store.downloadActive(id(1), "new")); store.pauseDownload(id(1), true);
        store.downloadState(id(1), "new", "ready", ""); store.clearDownloadProgress(id(1));
        assertEquals(0, store.downloadTasks(0, 60).getInt("total")); assertEquals("cancelled", store.song(id(1)).getString("downloadState"));
    }
    @Test public void retriesResetOnlyOnNetForwardProgressNotRedownloadingTheSameBytes() {
        store.scheduleDownload(id(1), "token"); store.downloadProgress(id(1), "token", 2000, 5000);
        assertEquals(1, store.downloadFailure(id(1), "token"));
        store.downloadProgress(id(1), "token", 0, 5000, 2000); store.downloadProgress(id(1), "token", 2000, 5000, 2000);
        assertEquals(2, store.downloadFailure(id(1), "token"));
        store.downloadProgress(id(1), "token", 2500, 5000, 2000); assertEquals(1, store.downloadFailure(id(1), "token"));
    }
    @Test public void catalogCursorCoversAllRowsAndExcludesNewInsertions() throws Exception {
        JSONObject page = store.catalogPage(0, 0, false);
        long ceiling = page.getLong("ceiling"); int count = page.getJSONArray("songs").length();
        assertFalse(page.getJSONArray("songs").getJSONObject(0).has("customLyrics"));
        store.upsert("https://example.com/music/late.m4a", "Late.m4a", 5000, "");
        while (page.getBoolean("hasMore")) {
            long previous = page.getLong("cursor"); page = store.catalogPage(previous, ceiling, false);
            assertTrue(page.getLong("cursor") > previous); count += page.getJSONArray("songs").length();
        }
        assertEquals(720, count); assertEquals(720, page.getInt("total"));
    }
    @Test public void collectionAndFavoriteFiltersAlsoConstrainTheFullQueue() throws Exception {
        store.getWritableDatabase().execSQL("UPDATE songs SET artist='Late Artist',album='Album_100%' WHERE id=?", new Object[]{id(719)});
        JSONObject albums = store.collections("albums", "%", false, 0, 60);
        assertEquals(1, albums.getInt("total")); assertEquals("Album_100%", albums.getJSONArray("items").getJSONObject(0).getString("name"));
        LibrarySelection selection = LibrarySelection.from(new JSONObject().put("artist", "late artist").put("album", "Album_100%"), false);
        assertEquals(1, store.matchingSongs(selection)); assertEquals(id(719), store.songs(selection, 0, 60).getJSONObject(0).getString("id"));
        store.replaceQueue(null, selection, id(719)); assertEquals(1, store.queueWindow(1, 40).size());
        store.favorite(id(719), true);
        LibrarySelection favorites = LibrarySelection.from(new JSONObject().put("favoritesOnly", true), false);
        assertEquals(1, store.matchingSongs(favorites)); store.replaceQueue(null, favorites, id(719)); assertEquals(1, store.queueWindow(1, 40).size());
    }
    @Test public void fullQueueShuffleIsUniqueAndCanRestorePlaylistOrder() throws Exception {
        store.replaceQueue(null, "", false, id(719));
        String first = store.queueWindow(1, 1).get(0).mediaId;
        long selected = store.queueIndex(id(719));
        store.orderQueue(true, id(719)); assertEquals(720, store.queueSize());
        assertEquals(1, store.queueIndex(id(719)));
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (long cursor = 1; cursor <= 720; cursor += 40)
            for (androidx.media3.common.MediaItem item : store.queueWindow(cursor, 40)) assertTrue(ids.add(item.mediaId));
        assertEquals(720, ids.size()); assertTrue(ids.contains(id(719)));
        store.orderQueue(false); assertEquals(first, store.queueWindow(1, 1).get(0).mediaId);
        assertEquals(selected, store.queueIndex(id(719)));
        java.util.List<androidx.media3.common.MediaItem> tail = store.queueWindow(715, 20, true);
        assertEquals(20, tail.size()); assertEquals(first, tail.get(6).mediaId);
        assertEquals(6, store.queueWindow(715, 20, false).size());
        store.replaceQueue(new JSONArray().put(id(719)), "", false, id(719));
        assertEquals(1, store.queueWindow(2, 40, true).size());
    }
    @Test public void recentTimeAndGenreAliasesRemainQueryableAcrossTheWholeLibrary() throws Exception {
        long before = System.currentTimeMillis(); store.event(id(719), "start", 0);
        assertTrue(store.song(id(719)).getLong("lastPlayedAt") >= before);
        LibrarySelection pop = LibrarySelection.from(new JSONObject().put("genreTerms", new JSONArray().put("流行").put("Pop")), false);
        assertEquals(720, store.matchingSongs(pop));
        LibrarySelection literal = LibrarySelection.from(new JSONObject().put("genreTerms", new JSONArray().put("%")), false);
        assertEquals(0, store.matchingSongs(literal));
    }
    @Test public void upgradePreservesOldLibraryAndBackfillsRecentTimeAndQueueOrder() throws Exception {
        String migration = "aurora-migration-fixture.db";
        context.deleteDatabase(migration);
        context.getSharedPreferences("playback-options-" + migration, Context.MODE_PRIVATE).edit().clear().commit();
        try {
            try (SQLiteDatabase db = context.openOrCreateDatabase(migration, Context.MODE_PRIVATE, null)) {
                db.execSQL("CREATE TABLE songs(id TEXT PRIMARY KEY,play_count INTEGER,favorite INTEGER,download_state TEXT NOT NULL DEFAULT '')");
                db.execSQL("CREATE TABLE history(song_id TEXT,action TEXT,timestamp INTEGER)");
                db.execSQL("CREATE TABLE playback_queue(position INTEGER PRIMARY KEY,song_id TEXT UNIQUE)");
                db.execSQL("CREATE TABLE playback_state(id INTEGER PRIMARY KEY,song_id TEXT,position_ms INTEGER)");
                db.execSQL("INSERT INTO playback_state VALUES(1,'fixture',4567)");
                db.execSQL("INSERT INTO songs(id,play_count,favorite) VALUES('fixture',8,1)");
                db.execSQL("INSERT INTO history VALUES('fixture','start',12345)");
                db.execSQL("INSERT INTO playback_queue VALUES(1,'fixture')"); db.setVersion(4);
            }
            try (MusicStore upgraded = new MusicStore(context, migration);
                android.database.Cursor row = upgraded.getReadableDatabase().rawQuery("SELECT favorite,play_count,last_played_at FROM songs WHERE id='fixture'", null)) {
                assertTrue(row.moveToFirst()); assertEquals(1, row.getInt(0)); assertEquals(8, row.getInt(1)); assertEquals(12345, row.getLong(2));
                assertEquals(10, upgraded.getReadableDatabase().getVersion()); assertEquals("", upgraded.downloadToken("fixture"));
                try (android.database.Cursor gain = upgraded.getReadableDatabase().rawQuery("SELECT replay_gain_db,replay_peak FROM songs WHERE id='fixture'", null)) {
                    assertTrue(gain.moveToFirst()); assertTrue(gain.isNull(0)); assertTrue(gain.isNull(1));
                }
                assertEquals("4567", upgraded.savedPlayback()[1]);
                upgraded.orderQueue(true); upgraded.orderQueue(false); assertEquals(1, upgraded.queueIndex("fixture"));
            }
        } finally {
            context.deleteDatabase(migration);
            context.getSharedPreferences("playback-options-" + migration, Context.MODE_PRIVATE).edit().clear().commit();
        }
    }
}
