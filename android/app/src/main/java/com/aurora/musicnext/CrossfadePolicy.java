package com.aurora.musicnext;

/** Small, deterministic transition math. Linear coefficients never sum above unity. */
final class CrossfadePolicy {
    static final long PREPARE_TIMEOUT_MS = 8000;
    static final long FRAME_MS = 50;
    static int seconds(double value) {
        if (!Double.isFinite(value) || value != Math.floor(value) || value < 0 || value > 3)
            throw new IllegalArgumentException("Invalid crossfade duration");
        return (int) value;
    }
    static long remaining(long duration, long position, float speed) {
        if (duration <= 0 || position < 0 || position >= duration || !Float.isFinite(speed) || speed <= 0) return 0;
        return (long) Math.min(Long.MAX_VALUE, (duration - position) / (double) speed);
    }
    static boolean automatic(int seconds, boolean playing, boolean loading, boolean failed, boolean blocked,
        boolean powerSave, boolean repeatOne, boolean live, long duration, float speed) {
        return seconds > 0 && playing && !loading && !failed && !blocked && !powerSave && !repeatOne && !live
            && Float.isFinite(speed) && speed > 0 && duration > seconds * 2000.0 * speed;
    }
    static long delay(long remaining, int seconds) { return Math.max(0, remaining - (seconds * 1000L + 4000)); }
    static long overlap(long remaining, int seconds) { return Math.max(0, Math.min(seconds * 1000L, remaining)); }
    static float progress(long elapsed, long duration) {
        return duration <= 0 ? 1 : (float) Math.max(0, Math.min(1, elapsed / (double) duration));
    }
    static float volume(float user, float attenuation, float weight, float duck) {
        return unit(user) * unit(attenuation) * unit(weight) * unit(duck);
    }
    static boolean finished(boolean ended, long duration, long position) { return ended || duration > 200 && position >= duration - 200; }
    private static float unit(float value) { return Float.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0; }

    /** Transient focus gain may resume only an intent that the user has not cancelled. */
    static final class FocusIntent {
        boolean ready, waiting;
        void request(boolean value) { ready = value; if (!value) waiting = false; }
        void command(int command) {
            if (command < 0) { ready = false; waiting = false; }
            else if (ready) waiting = command == 0;
        }
        boolean allowed() { return ready && !waiting; }
    }
}
