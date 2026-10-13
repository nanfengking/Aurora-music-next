package com.aurora.musicnext;

import java.util.Objects;
import java.util.regex.Pattern;

/** Tag-based attenuation only: never boost, guess loudness, or modify the source file. */
final class ReplayGain {
    static final ReplayGain NONE = new ReplayGain(null, null);
    private static final Pattern GAIN = Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:\\s*dB)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern PEAK = Pattern.compile("[+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)");
    final Double gainDb;
    final Double peak;

    ReplayGain(Double gainDb, Double peak) {
        this.gainDb = gainDb != null && Double.isFinite(gainDb) && gainDb >= -60 && gainDb <= 60 ? gainDb : null;
        this.peak = peak != null && Double.isFinite(peak) && peak > 0 && peak <= 64 ? peak : null;
    }

    static Double parseGain(String value) { return parse(value, true); }
    static Double parsePeak(String value) { return parse(value, false); }
    private static Double parse(String value, boolean gain) {
        if (value == null || value.length() > 256) return null;
        String text = value.trim();
        if (!(gain ? GAIN : PEAK).matcher(text).matches()) return null;
        if (gain && text.toLowerCase(java.util.Locale.ROOT).endsWith("db")) text = text.substring(0, text.length() - 2).trim();
        try {
            double number = Double.parseDouble(text);
            ReplayGain sanitized = gain ? new ReplayGain(number, null) : new ReplayGain(null, number);
            return gain ? sanitized.gainDb : sanitized.peak;
        } catch (NumberFormatException invalid) { return null; }
    }

    float factor(boolean enabled) {
        if (!enabled) return 1;
        double gain = gainDb == null ? 1 : Math.pow(10, gainDb / 20);
        return (float) Math.min(1, Math.min(gain, peak == null ? 1 : 1 / peak));
    }

    /** A late database reload cannot replace freshly parsed tags from the current track. */
    static final class State {
        private String songId;
        private ReplayGain tags = NONE;
        private boolean fromSource;
        void select(String id) { songId = id; tags = NONE; fromSource = false; }
        void cached(String id, ReplayGain value) {
            if (Objects.equals(id, songId) && !fromSource) tags = value;
        }
        void source(String id, ReplayGain value) {
            if (Objects.equals(id, songId)) { tags = value; fromSource = true; }
        }
        float volume(float userVolume, boolean enabled) { return userVolume * tags.factor(enabled); }
        ReplayGain tags() { return tags; }
    }
}
