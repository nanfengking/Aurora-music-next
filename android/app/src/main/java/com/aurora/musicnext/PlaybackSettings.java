package com.aurora.musicnext;

import android.content.Context;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import org.json.JSONException;
import org.json.JSONObject;

/** Small, credential-free native settings; no WebView is needed while playing. */
final class PlaybackSettings {
    static final String[] KEYS = {"repeatMode", "shuffle", "speed", "volume", "normalizeVolume", "prefetch", "crossfadeSeconds", "rememberPlayback"};
    final NativePreferences repository;
    PlaybackSettings(Context context) { repository = NativePreferences.forApplication(context); }
    /** A database name, not a legacy SharedPreferences profile. */
    PlaybackSettings(Context context, String database) { repository = NativePreferences.forDatabase(context.getApplicationContext(), database); }
    private Object value(String key) { return repository.requireReady().values.get(key); }
    String repeat() { return (String) value("repeatMode"); }
    boolean shuffle() { return (Boolean) value("shuffle"); }
    float speed() { return ((Number) value("speed")).floatValue(); }
    float volume() { return ((Number) value("volume")).floatValue(); }
    boolean normalizeVolume() { return (Boolean) value("normalizeVolume"); }
    boolean prefetch() { return (Boolean) value("prefetch"); }
    int crossfadeSeconds() { return ((Number) value("crossfadeSeconds")).intValue(); }
    boolean remember() { return (Boolean) value("rememberPlayback"); }
    long revision() { return repository.requireReady().restoreRevision; }
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
    ListenableFuture<NativePreferenceRecord> save(JSONObject patch) {
        try { return repository.update(NativePreferences.only(patch, KEYS)); }
        catch (Exception invalid) { return Futures.immediateFailedFuture(invalid); }
    }
    JSONObject status() throws JSONException {
        return status(repository.requireReady());
    }
    static JSONObject status(NativePreferenceRecord record) throws JSONException {
        NativePreferenceValues values = record.values;
        JSONObject result = new JSONObject();
        for (String key : KEYS) result.put(key, values.get(key));
        return result;
    }
}
