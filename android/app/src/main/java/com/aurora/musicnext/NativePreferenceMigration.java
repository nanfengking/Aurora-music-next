package com.aurora.musicnext;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.LinkedHashMap;
import java.util.Map;

/** Worker-only, one-time whitelist reader. Never enumerate a connection/provider store. */
final class NativePreferenceMigration {
    private NativePreferenceMigration() {}

    static NativePreferenceRecord read(Context context, String databaseName) {
        SharedPreferences playback = context.getSharedPreferences(profile("playback-options", databaseName), Context.MODE_PRIVATE);
        SharedPreferences appearance = context.getSharedPreferences(profile("appearance", databaseName), Context.MODE_PRIVATE);
        SharedPreferences connection = context.getSharedPreferences(profile("connection", databaseName), Context.MODE_PRIVATE);
        Map<String, Object> playing = new LinkedHashMap<>(), visual = new LinkedHashMap<>(), network = new LinkedHashMap<>();
        copy(playback, playing, "repeatMode", "string");
        for (String key : new String[]{"shuffle", "normalizeVolume", "prefetch", "rememberPlayback"}) copy(playback, playing, key, "boolean");
        for (String key : new String[]{"speed", "volume"}) copy(playback, playing, key, "float");
        copy(playback, playing, "crossfadeSeconds", "int"); copy(playback, playing, "restoreRevision", "long");
        for (String key : new String[]{"mode", "accent", "language"}) copy(appearance, visual, key, "string");
        for (String key : new String[]{"dynamic", "motion", "lowPowerVisuals", "autoLookup", "showBackdrop"}) copy(appearance, visual, key, "boolean");
        copy(appearance, visual, "lyricsSize", "int");
        copy(connection, network, "wifiOnly", "boolean");
        return NativePreferenceRecord.initial(playing, visual, network);
    }

    private static String profile(String base, String databaseName) {
        return databaseName.equals("aurora-mobile.db") ? base : base + "-" + databaseName;
    }

    private static void copy(SharedPreferences source, Map<String, Object> target, String key, String type) {
        if (!source.contains(key)) return;
        try {
            switch (type) {
                case "string": target.put(key, source.getString(key, null)); break;
                case "boolean": target.put(key, source.getBoolean(key, false)); break;
                case "float": target.put(key, source.getFloat(key, 0)); break;
                case "int": target.put(key, source.getInt(key, 0)); break;
                case "long": target.put(key, source.getLong(key, 0)); break;
                default: throw new IllegalArgumentException("Unknown legacy field type");
            }
        } catch (ClassCastException invalidLegacyType) {
            // Legacy corruption is tolerated only here, per field, using established safe defaults.
        }
    }
}
