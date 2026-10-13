package com.aurora.musicnext;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONException;
import org.json.JSONObject;

/** Small, credential-free native settings; no WebView is needed while playing. */
final class PlaybackSettings {
    private final SharedPreferences preferences;
    PlaybackSettings(Context context) { preferences = context.getSharedPreferences("playback-options", Context.MODE_PRIVATE); }
    String repeat() { return preferences.getString("repeatMode", "off"); }
    boolean shuffle() { return preferences.getBoolean("shuffle", false); }
    float speed() { return preferences.getFloat("speed", 1); }
    float volume() { return preferences.getFloat("volume", 1); }
    boolean normalizeVolume() { return preferences.getBoolean("normalizeVolume", false); }
    boolean prefetch() { return preferences.getBoolean("prefetch", false); }
    int crossfadeSeconds() { return preferences.getInt("crossfadeSeconds", 0); }
    boolean remember() { return preferences.getBoolean("rememberPlayback", true); }
    long revision() { return preferences.getLong("restoreRevision", 0); }
    PlaybackSettings(Context context, String profile) { preferences = context.getSharedPreferences(profile, Context.MODE_PRIVATE); }
    static String validRepeat(String value) {
        if (!java.util.Arrays.asList("off", "one", "all").contains(value)) throw new IllegalArgumentException("Invalid repeat mode");
        return value;
    }
    static float validSpeed(double value) { return bounded(value, .5, 2, "speed"); }
    static float validVolume(double value) { return bounded(value, 0, 1, "volume"); }
    static int validSleep(double value) {
        if (!Double.isFinite(value) || value < 0 || value > 180 || value != Math.floor(value))
            throw new IllegalArgumentException("Invalid sleep minutes");
        return (int) value;
    }
    private static float bounded(double value, double minimum, double maximum, String name) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) throw new IllegalArgumentException("Invalid " + name);
        return (float) value;
    }
    void save(JSONObject patch) throws JSONException {
        // Validate the whole patch before committing any part of it.
        String repeat = validRepeat(patch.has("repeatMode") ? patch.getString("repeatMode") : repeat());
        boolean shuffle = patch.has("shuffle") ? patch.getBoolean("shuffle") : shuffle();
        float speed = validSpeed(patch.has("speed") ? patch.getDouble("speed") : speed());
        float volume = validVolume(patch.has("volume") ? patch.getDouble("volume") : volume());
        boolean normalize = patch.has("normalizeVolume") ? patch.getBoolean("normalizeVolume") : normalizeVolume();
        boolean prefetch = patch.has("prefetch") ? patch.getBoolean("prefetch") : prefetch();
        int crossfade = CrossfadePolicy.seconds(patch.has("crossfadeSeconds") ? patch.getDouble("crossfadeSeconds") : crossfadeSeconds());
        boolean remember = patch.has("rememberPlayback") ? patch.getBoolean("rememberPlayback") : remember();
        long revision = revision();
        if (remember != remember()) { if (revision == Long.MAX_VALUE) throw new IllegalStateException("Too many preference updates"); revision++; }
        if (patch.has("sleepMinutes")) validSleep(patch.getDouble("sleepMinutes"));
        preferences.edit().putString("repeatMode", repeat).putBoolean("shuffle", shuffle)
            .putFloat("speed", speed).putFloat("volume", volume).putBoolean("normalizeVolume", normalize)
            .putBoolean("prefetch", prefetch)
            .putInt("crossfadeSeconds", crossfade)
            .putBoolean("rememberPlayback", remember).putLong("restoreRevision", revision).apply();
    }
    JSONObject status() throws JSONException {
        return new JSONObject().put("repeatMode", repeat()).put("shuffle", shuffle()).put("speed", speed()).put("volume", volume())
            .put("normalizeVolume", normalizeVolume()).put("prefetch", prefetch()).put("crossfadeSeconds", crossfadeSeconds()).put("rememberPlayback", remember());
    }
}
