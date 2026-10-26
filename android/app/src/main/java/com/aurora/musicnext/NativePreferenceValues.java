package com.aurora.musicnext;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/** Immutable, credential-free Android preferences. No Android, disk or service dependency. */
final class NativePreferenceValues {
    private static final Map<String, Object> DEFAULTS;
    static {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("repeatMode", "off"); values.put("shuffle", false); values.put("speed", 1.0);
        values.put("volume", 1.0); values.put("normalizeVolume", false); values.put("prefetch", false);
        values.put("crossfadeSeconds", 0); values.put("rememberPlayback", true);
        values.put("mode", "system"); values.put("accent", "#b94b5c"); values.put("dynamic", true);
        values.put("motion", true); values.put("lowPowerVisuals", true); values.put("autoLookup", false);
        values.put("lyricsSize", 18); values.put("showBackdrop", true); values.put("language", "");
        values.put("wifiOnly", true);
        DEFAULTS = Collections.unmodifiableMap(values);
    }
    private final Map<String, Object> values;

    private NativePreferenceValues(Map<String, Object> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    static NativePreferenceValues defaults() { return new NativePreferenceValues(DEFAULTS); }

    static NativePreferenceValues fromJson(JSONObject json) {
        if (json == null || json.length() != DEFAULTS.size()) throw invalid();
        return defaults().patch(json);
    }

    NativePreferenceValues patch(JSONObject patch) {
        if (patch == null) throw invalid();
        Map<String, Object> merged = new LinkedHashMap<>(values);
        java.util.Iterator<String> keys = patch.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            merged.put(key, validate(key, patch.opt(key)));
        }
        return new NativePreferenceValues(merged);
    }

    Object get(String key) {
        if (!values.containsKey(key)) throw invalid();
        return values.get(key);
    }

    JSONObject json() { return new JSONObject(values); }

    boolean sameValues(NativePreferenceValues other) { return values.equals(other.values); }

    /** Legacy reads are tolerant per field; durable records and new patches are never tolerant. */
    static NativePreferenceValues legacy(Map<String, ?> playback, Map<String, ?> appearance, Map<String, ?> connection) {
        Map<String, Object> migrated = new LinkedHashMap<>(DEFAULTS);
        copyLegacy(migrated, playback, new String[]{"repeatMode", "shuffle", "speed", "volume", "normalizeVolume",
            "prefetch", "crossfadeSeconds", "rememberPlayback"});
        copyLegacy(migrated, appearance, new String[]{"mode", "accent", "dynamic", "motion", "lowPowerVisuals",
            "autoLookup", "lyricsSize", "showBackdrop", "language"});
        copyLegacy(migrated, connection, new String[]{"wifiOnly"});
        return new NativePreferenceValues(migrated);
    }

    private static void copyLegacy(Map<String, Object> target, Map<String, ?> source, String[] keys) {
        if (source == null) throw invalid();
        for (String key : keys) {
            if (!source.containsKey(key)) continue;
            try { target.put(key, validate(key, source.get(key))); }
            catch (IllegalArgumentException ignored) { /* Keep this field's established safe default. */ }
        }
    }

    private static Object validate(String key, Object value) {
        if (!DEFAULTS.containsKey(key) || value == null || value == JSONObject.NULL) throw invalid();
        Object fallback = DEFAULTS.get(key);
        if (fallback instanceof Boolean) {
            if (!(value instanceof Boolean)) throw invalid();
            return value;
        }
        if (fallback instanceof Number) {
            if (!(value instanceof Number)) throw invalid();
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number)) throw invalid();
            switch (key) {
                case "speed": if (number >= .5 && number <= 2) return number; break;
                case "volume": if (number >= 0 && number <= 1) return number; break;
                case "crossfadeSeconds": if (number >= 0 && number <= 3 && number == Math.floor(number)) return (int) number; break;
                case "lyricsSize": if (number == 18 || number == 24 || number == 30 || number == 36) return (int) number; break;
                default: break;
            }
            throw invalid();
        }
        if (!(value instanceof String)) throw invalid();
        String text = (String) value;
        switch (key) {
            case "repeatMode": if (Arrays.asList("off", "one", "all").contains(text)) return text; break;
            case "mode": if (Arrays.asList("system", "light", "dark").contains(text)) return text; break;
            case "accent": if (text.matches("#[0-9a-fA-F]{6}")) return text; break;
            case "language": if (Arrays.asList("", "zh-CN", "zh-TW", "en", "fr").contains(text)) return text; break;
            default: break;
        }
        throw invalid();
    }

    private static IllegalArgumentException invalid() {
        // Do not include supplied values: even rejected unknown fields may contain credentials.
        return new IllegalArgumentException("Invalid native preferences");
    }
}
