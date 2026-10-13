package com.aurora.musicnext;

import java.util.Objects;

/** Phone-sized cache budget; explicit offline downloads do not have this prefetch limit. */
final class PrefetchPolicy {
    static final long MAX_TRACK_BYTES = 64L * 1024 * 1024;
    static final long DISK_RESERVE_BYTES = 32L * 1024 * 1024;
    static final long PLAYBACK_HEADROOM_BYTES = 8L * 1024 * 1024;
    static final long START_DELAY_MS = 5000;
    static final long DEADLINE_MS = 90000;
    private PrefetchPolicy() {}
    static boolean sizeAllowed(long size) { return size > 0 && size <= MAX_TRACK_BYTES; }
    static boolean allowed(boolean enabled, boolean playing, boolean loading, boolean buffering, boolean failed,
        boolean powerSave, boolean networkAvailable, boolean wifiOnly, boolean unmetered, boolean repeatOne) {
        return enabled && playing && !loading && !buffering && !failed && !powerSave && networkAvailable
            && (!wifiOnly || unmetered) && !repeatOne;
    }
    static boolean fits(long size, long protectedBytes, long cacheLimit, long freeBytes) {
        return sizeAllowed(size) && protectedBytes >= 0 && cacheLimit >= PLAYBACK_HEADROOM_BYTES
            && protectedBytes <= cacheLimit - PLAYBACK_HEADROOM_BYTES
            && size <= cacheLimit - PLAYBACK_HEADROOM_BYTES - protectedBytes
            && freeBytes >= DISK_RESERVE_BYTES && size <= freeBytes - DISK_RESERVE_BYTES;
    }
    /** Repeated state events cannot create an automatic retry loop. An eligibility/key change can retry. */
    static final class Gate {
        private String selected;
        private String finished;
        boolean change(String key) {
            if (Objects.equals(key, selected)) return false;
            selected = key; return true;
        }
        boolean finished(String key) { return key != null && Objects.equals(key, finished); }
        void complete(String key) { finished = key; }
        void reset() { selected = null; finished = null; }
    }
}
