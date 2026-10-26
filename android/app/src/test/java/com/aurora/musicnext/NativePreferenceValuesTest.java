package com.aurora.musicnext;

import static org.junit.Assert.*;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;

/** Real immutable preference code: coercion/reset/counter mutations must fail these tests. */
public final class NativePreferenceValuesTest {
    @Test public void defaultsPreserveAndroidPlaybackAndNetworkConsent() throws Exception {
        JSONObject value = NativePreferenceValues.defaults().json();
        assertEquals(18, value.length());
        assertEquals("off", value.get("repeatMode")); assertEquals(false, value.get("shuffle"));
        assertEquals(1, value.getDouble("speed"), 0); assertEquals(1, value.getDouble("volume"), 0);
        assertEquals(false, value.get("normalizeVolume")); assertEquals(false, value.get("prefetch"));
        assertEquals(0, value.getInt("crossfadeSeconds")); assertEquals(true, value.get("rememberPlayback"));
        assertEquals("system", value.get("mode")); assertEquals("#b94b5c", value.get("accent"));
        assertEquals(true, value.get("dynamic")); assertEquals(true, value.get("motion"));
        assertEquals(true, value.get("lowPowerVisuals")); assertEquals(false, value.get("autoLookup"));
        assertEquals(18, value.getInt("lyricsSize")); assertEquals(true, value.get("showBackdrop"));
        assertEquals("", value.get("language")); assertEquals(true, value.get("wifiOnly"));
    }

    @Test public void partialPatchMergesWithoutResettingOtherPreferences() throws Exception {
        NativePreferenceValues original = NativePreferenceValues.defaults().patch(new JSONObject()
            .put("accent", "#123AbC").put("mode", "dark").put("language", "fr").put("volume", .3));
        NativePreferenceValues next = original.patch(new JSONObject().put("motion", false));
        assertEquals("#123AbC", next.get("accent")); assertEquals("dark", next.get("mode"));
        assertEquals("fr", next.get("language")); assertEquals(.3, (Double) next.get("volume"), 0);
        assertEquals(false, next.get("motion")); assertEquals(true, original.get("motion"));
    }

    @Test public void jsonAndInputMutationCannotChangeSnapshots() throws Exception {
        JSONObject patch = new JSONObject().put("language", "en");
        NativePreferenceValues value = NativePreferenceValues.defaults().patch(patch);
        patch.put("language", "fr"); value.json().put("language", "zh-CN");
        assertEquals("en", value.get("language"));
        JSONObject persisted = value.json();
        NativePreferenceValues restored = NativePreferenceValues.fromJson(persisted);
        persisted.put("wifiOnly", false);
        assertEquals(true, restored.get("wifiOnly"));
    }

    @Test public void actualBooleansRequiredRatherThanJsonCoercion() throws Exception {
        for (String key : new String[]{"shuffle", "normalizeVolume", "prefetch", "rememberPlayback", "dynamic",
            "motion", "lowPowerVisuals", "autoLookup", "showBackdrop", "wifiOnly"}) {
            invalid(key, "false"); invalid(key, 0); invalid(key, JSONObject.NULL);
            assertEquals(false, NativePreferenceValues.defaults().patch(new JSONObject().put(key, false)).get(key));
        }
    }

    @Test public void rejectsNumericStringsAndOutOfRangeOrFractionalValues() throws Exception {
        for (String key : new String[]{"speed", "volume", "crossfadeSeconds", "lyricsSize"}) invalid(key, "1");
        invalid("speed", .499); invalid("speed", 2.001); invalid("volume", -.01); invalid("volume", 1.01);
        invalid("crossfadeSeconds", -1); invalid("crossfadeSeconds", 4); invalid("crossfadeSeconds", 1.5);
        invalid("lyricsSize", 19); invalid("lyricsSize", 24.5);
        NativePreferenceValues boundary = NativePreferenceValues.defaults().patch(new JSONObject()
            .put("speed", .5).put("volume", 0).put("crossfadeSeconds", 3.0).put("lyricsSize", 36));
        assertEquals(.5, (Double) boundary.get("speed"), 0); assertEquals(0, (Double) boundary.get("volume"), 0);
        assertEquals(3, boundary.get("crossfadeSeconds")); assertEquals(36, boundary.get("lyricsSize"));
        assertEquals(2, (Double) boundary.patch(new JSONObject().put("speed", 2).put("volume", 1)).get("speed"), 0);
        for (int size : new int[]{18, 24, 30, 36})
            assertEquals(size, boundary.patch(new JSONObject().put("lyricsSize", size)).get("lyricsSize"));
    }

    @Test public void rejectsUnknownInternalTransientAndNullFields() throws Exception {
        for (String key : new String[]{"password", "apiKey", "url", "revision", "restoreRevision", "version", "sleepMinutes", "unknown"})
            invalid(key, 1);
        invalid("accent", JSONObject.NULL); invalid("language", new JSONObject());
        assertThrows(IllegalArgumentException.class, () -> NativePreferenceValues.defaults().patch(null));
        assertThrows(IllegalArgumentException.class, () -> NativePreferenceValues.defaults().get("password"));
    }

    @Test public void validatesEnumsAccentAndSupportedLanguages() throws Exception {
        invalid("repeatMode", "sequential"); invalid("mode", "auto"); invalid("accent", "red");
        invalid("accent", "#123"); invalid("accent", "#12345678"); invalid("language", "de"); invalid("language", "en-US");
        for (String language : new String[]{"", "zh-CN", "zh-TW", "en", "fr"})
            assertEquals(language, NativePreferenceValues.defaults().patch(new JSONObject().put("language", language)).get("language"));
        for (String repeat : new String[]{"off", "one", "all"})
            assertEquals(repeat, NativePreferenceValues.defaults().patch(new JSONObject().put("repeatMode", repeat)).get("repeatMode"));
        for (String mode : new String[]{"system", "light", "dark"})
            assertEquals(mode, NativePreferenceValues.defaults().patch(new JSONObject().put("mode", mode)).get("mode"));
    }

    @Test public void persistedRecordRequiresEveryWhitelistedKeyExactly() throws Exception {
        JSONObject complete = NativePreferenceValues.defaults().json();
        complete.remove("wifiOnly");
        assertThrows(IllegalArgumentException.class, () -> NativePreferenceValues.fromJson(complete));
        JSONObject extra = NativePreferenceValues.defaults().json().put("password", "not-a-real-secret");
        assertThrows(IllegalArgumentException.class, () -> NativePreferenceValues.fromJson(extra));
        assertThrows(IllegalArgumentException.class, () -> NativePreferenceValues.fromJson(null));
        assertEquals(true, NativePreferenceValues.fromJson(NativePreferenceValues.defaults().json()).get("wifiOnly"));
    }

    @Test public void legacyMigrationUsesOnlyWhitelistedProfileKeysAndToleratesInvalidFields() throws Exception {
        Map<String, Object> playback = new HashMap<>();
        playback.put("volume", .4f); playback.put("speed", Float.NaN); playback.put("repeatMode", "all");
        playback.put("shuffle", "true"); playback.put("restoreRevision", 9L); playback.put("language", "fr");
        Map<String, Object> appearance = new HashMap<>();
        appearance.put("language", "zh-TW"); appearance.put("accent", "#445566"); appearance.put("lyricsSize", 24);
        appearance.put("prefetch", true); appearance.put("autoLookup", "true"); appearance.put("motion", false);
        Map<String, Object> connection = new HashMap<>();
        connection.put("wifiOnly", false); connection.put("volume", .1); connection.put("password", "fixture-only");
        NativePreferenceRecord record = NativePreferenceRecord.initial(playback, appearance, connection);
        assertEquals(0, record.revision); assertEquals(9, record.restoreRevision);
        assertEquals(.4, (Double) record.values.get("volume"), .00001);
        assertEquals(1, (Double) record.values.get("speed"), 0); assertEquals("all", record.values.get("repeatMode"));
        assertEquals(false, record.values.get("shuffle")); assertEquals(false, record.values.get("prefetch"));
        assertEquals(false, record.values.get("autoLookup")); assertEquals(false, record.values.get("wifiOnly"));
        assertEquals("zh-TW", record.values.get("language")); assertEquals("#445566", record.values.get("accent"));
        assertEquals(24, record.values.get("lyricsSize")); assertEquals(false, record.values.get("motion"));
        assertFalse(record.values.json().has("password"));
        appearance.put("language", "en"); assertEquals("zh-TW", record.values.get("language"));
    }

    @Test public void malformedLegacyValuesCannotLoosenDefaultNetworkConsent() {
        NativePreferenceRecord record = NativePreferenceRecord.initial(
            Map.of("prefetch", "true", "volume", Double.POSITIVE_INFINITY, "restoreRevision", -3L),
            Map.of("autoLookup", 1), Map.of("wifiOnly", "false"));
        assertEquals(false, record.values.get("prefetch")); assertEquals(false, record.values.get("autoLookup"));
        assertEquals(true, record.values.get("wifiOnly")); assertEquals(1, (Double) record.values.get("volume"), 0);
        assertEquals(0, record.restoreRevision);
    }

    @Test public void revisionsAdvanceOnlyForEffectiveChangesAndRememberToggles() throws Exception {
        NativePreferenceRecord original = NativePreferenceRecord.initial(Map.of(), Map.of(), Map.of());
        assertSame(original, original.patch(new JSONObject().put("volume", 1.0)));
        NativePreferenceRecord volume = original.patch(new JSONObject().put("volume", .5));
        assertEquals(1, volume.revision); assertEquals(0, volume.restoreRevision);
        NativePreferenceRecord off = volume.patch(new JSONObject().put("rememberPlayback", false));
        assertEquals(2, off.revision); assertEquals(1, off.restoreRevision);
        NativePreferenceRecord on = off.patch(new JSONObject().put("rememberPlayback", true));
        assertEquals(3, on.revision); assertEquals(2, on.restoreRevision);
        assertEquals(true, original.values.get("rememberPlayback"));
    }

    @Test public void persistedMetadataFailsClosedForFutureCorruptOrOverflow() throws Exception {
        JSONObject values = NativePreferenceValues.defaults().json();
        assertThrows(IllegalArgumentException.class, () -> NativePreferenceRecord.restore(2, 0, 0, values));
        assertThrows(IllegalArgumentException.class, () -> NativePreferenceRecord.restore(0, 0, 0, values));
        assertThrows(IllegalArgumentException.class, () -> NativePreferenceRecord.restore(1, -1, 0, values));
        assertThrows(IllegalArgumentException.class, () -> NativePreferenceRecord.restore(1, 0, -1, values));
        NativePreferenceRecord full = NativePreferenceRecord.restore(1, Long.MAX_VALUE, 0, values);
        assertThrows(IllegalStateException.class, () -> full.patch(new JSONObject().put("volume", .2)));
        assertSame(full, full.patch(new JSONObject()));
        NativePreferenceRecord restoreFull = NativePreferenceRecord.restore(1, 5, Long.MAX_VALUE, values);
        assertThrows(IllegalStateException.class, () -> restoreFull.patch(new JSONObject().put("rememberPlayback", false)));
        assertEquals(6, restoreFull.patch(new JSONObject().put("volume", .2)).revision);
    }

    private static void invalid(String key, Object value) throws Exception {
        JSONObject patch = new JSONObject().put(key, value);
        assertThrows("reject " + key + "=" + value, IllegalArgumentException.class,
            () -> NativePreferenceValues.defaults().patch(patch));
    }
}
