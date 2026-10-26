package com.aurora.musicnext;

import java.util.Map;
import org.json.JSONObject;

/** Versioned committed value, with separate ordering and playback recovery generations. */
final class NativePreferenceRecord {
    static final int VERSION = 1;
    final NativePreferenceValues values;
    final long revision;
    final long restoreRevision;

    private NativePreferenceRecord(NativePreferenceValues values, long revision, long restoreRevision) {
        this.values = values; this.revision = revision; this.restoreRevision = restoreRevision;
    }

    static NativePreferenceRecord initial(Map<String, ?> playback, Map<String, ?> appearance, Map<String, ?> connection) {
        NativePreferenceValues values = NativePreferenceValues.legacy(playback, appearance, connection);
        Object legacyRevision = playback.get("restoreRevision");
        long restore = 0;
        if (legacyRevision instanceof Long || legacyRevision instanceof Integer) {
            long candidate = ((Number) legacyRevision).longValue();
            if (candidate >= 0) restore = candidate;
        }
        return new NativePreferenceRecord(values, 0, restore);
    }

    static NativePreferenceRecord restore(int version, long revision, long restoreRevision, JSONObject values) {
        if (version != VERSION || revision < 0 || restoreRevision < 0)
            throw new IllegalArgumentException("Invalid native preference record");
        return new NativePreferenceRecord(NativePreferenceValues.fromJson(values), revision, restoreRevision);
    }

    NativePreferenceRecord patch(JSONObject patch) {
        NativePreferenceValues next = values.patch(patch);
        if (values.sameValues(next)) return this;
        boolean rememberChanged = !values.get("rememberPlayback").equals(next.get("rememberPlayback"));
        if (revision == Long.MAX_VALUE || rememberChanged && restoreRevision == Long.MAX_VALUE)
            throw new IllegalStateException("Too many native preference updates");
        return new NativePreferenceRecord(next, revision + 1, restoreRevision + (rememberChanged ? 1 : 0));
    }
}
