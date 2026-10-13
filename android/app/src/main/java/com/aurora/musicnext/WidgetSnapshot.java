package com.aurora.musicnext;

import android.content.Context;
import android.content.SharedPreferences;

final class WidgetSnapshot {
    String songId = "", title = "", artist = "", cover = "", lyric = "", nextLyric = "";
    boolean playing;
    WidgetSnapshot() {}
    WidgetSnapshot(String id, String title, String artist, String cover, String lyric, String next, boolean playing) {
        songId = id == null ? "" : id; this.title = title; this.artist = artist; this.cover = cover;
        this.lyric = lyric; nextLyric = next; this.playing = playing;
    }
    void save(Context context) {
        context.getSharedPreferences("widget_snapshot", Context.MODE_PRIVATE).edit()
            .putString("songId", songId).putString("title", title).putString("artist", artist)
            .putString("cover", cover).putString("lyric", lyric).putString("nextLyric", nextLyric)
            .putBoolean("playing", playing).apply();
    }
    static WidgetSnapshot load(Context context) {
        SharedPreferences saved = context.getSharedPreferences("widget_snapshot", Context.MODE_PRIVATE);
        WidgetSnapshot value = new WidgetSnapshot();
        value.songId = saved.getString("songId", ""); value.title = saved.getString("title", "");
        value.artist = saved.getString("artist", ""); value.cover = saved.getString("cover", "");
        value.lyric = saved.getString("lyric", ""); value.nextLyric = saved.getString("nextLyric", "");
        value.playing = saved.getBoolean("playing", false); return value;
    }
}
