package com.aurora.musicnext;

import android.app.ActivityManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import androidx.core.graphics.ColorUtils;
import org.json.JSONException;
import org.json.JSONObject;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

/** Visual preferences contain no credentials and are shared with native widgets. */
final class AppearanceSettings {
    private final Context context;
    static final String[] KEYS = {"mode", "accent", "dynamic", "motion", "lowPowerVisuals", "autoLookup", "lyricsSize", "showBackdrop"};
    final NativePreferences repository;

    AppearanceSettings(Context context) {
        this.context = context.getApplicationContext(); repository = NativePreferences.forApplication(this.context);
    }
    /** Isolated preference profiles let native fixtures leave user settings untouched. */
    AppearanceSettings(Context context, String database) {
        this.context = context.getApplicationContext();
        repository = NativePreferences.forDatabase(this.context, database);
    }

    ListenableFuture<NativePreferenceRecord> save(JSONObject value) {
        try { return repository.update(NativePreferences.only(value, KEYS)); }
        catch (Exception invalid) { return Futures.immediateFailedFuture(invalid); }
    }
    static int validLyricsSize(double size) {
        if (!Double.isFinite(size) || !java.util.Arrays.asList(18.0, 24.0, 30.0, 36.0).contains(size)) throw new IllegalArgumentException("Invalid lyric size");
        return (int) size;
    }

    boolean autoLookup() { return (Boolean) repository.requireReady().values.get("autoLookup"); }
    ListenableFuture<NativePreferenceRecord> language(String language) {
        try { return repository.update(NativePreferences.only(new JSONObject().put("language", language), "language")); }
        catch (Exception invalid) { return Futures.immediateFailedFuture(invalid); }
    }
    static Context localized(Context context) {
        String language = (String) NativePreferences.forApplication(context).requireReady().values.get("language");
        if (language.isEmpty()) return context;
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        configuration.setLocale(java.util.Locale.forLanguageTag(language));
        return context.createConfigurationContext(configuration);
    }
    boolean dark() {
        return dark(repository.requireReady().values);
    }
    private boolean dark(NativePreferenceValues values) {
        String mode = (String) values.get("mode");
        return mode.equals("dark") || (mode.equals("system") &&
            (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
    }

    int color(String role) {
        return color(role, repository.requireReady().values);
    }
    private int color(String role, NativePreferenceValues values) {
        boolean dark = dark(values);
        boolean dynamic = (Boolean) values.get("dynamic") && Build.VERSION.SDK_INT >= 31;
        int accent = Color.parseColor((String) values.get("accent"));
        switch (role) {
            case "background": return systemColor(dynamic, dark ? "system_neutral1_900" : "system_neutral1_10", dark ? 0xff17171c : 0xfff8f7f5);
            case "surface": return systemColor(dynamic, dark ? "system_neutral1_800" : "system_neutral1_0", dark ? 0xff24242b : Color.WHITE);
            case "text": return systemColor(dynamic, dark ? "system_neutral1_50" : "system_neutral1_900", dark ? 0xfff0edf0 : 0xff252329);
            case "muted": return systemColor(dynamic, dark ? "system_neutral2_200" : "system_neutral2_600", dark ? 0xffc1bac1 : 0xff746d75);
            case "border": return systemColor(dynamic, dark ? "system_neutral2_700" : "system_neutral2_200", dark ? 0xff49434b : 0xffded8df);
            case "accent": return systemColor(dynamic, dark ? "system_accent1_200" : "system_accent1_600", dark ? ColorUtils.blendARGB(accent, Color.WHITE, .38f) : accent);
            case "container": return systemColor(dynamic, dark ? "system_accent1_800" : "system_accent1_100", ColorUtils.blendARGB(accent, dark ? 0xff24242b : Color.WHITE, dark ? .72f : .88f));
            case "onAccent": return ColorUtils.calculateContrast(Color.WHITE, color("accent", values)) >= 4.5 ? Color.WHITE : 0xff151218;
            default: throw new IllegalArgumentException("Unknown palette role");
        }
    }

    private int systemColor(boolean enabled, String name, int fallback) {
        if (!enabled) return fallback;
        int resource = context.getResources().getIdentifier(name, "color", "android");
        return resource == 0 ? fallback : context.getColor(resource);
    }

    JSONObject status() throws JSONException {
        NativePreferenceValues values = repository.requireReady().values;
        JSONObject value = new JSONObject();
        for (String key : KEYS) value.put(key, values.get(key));
        String language = (String) values.get("language");
        value.put("language", language); value.put("effectiveLanguage", effectiveLanguage(language));
        value.put("dynamicAvailable", Build.VERSION.SDK_INT >= 31);
        value.put("dark", dark(values));
        value.put("systemReducedMotion", Settings.Global.getFloat(context.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0);
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        value.put("powerSave", power != null && power.isPowerSaveMode());
        value.put("batteryOptimized", power != null && !power.isIgnoringBatteryOptimizations(context.getPackageName()));
        ActivityManager activity = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        value.put("backgroundRestricted", Build.VERSION.SDK_INT >= 28 && activity != null && activity.isBackgroundRestricted());
        JSONObject palette = new JSONObject();
        for (String role : new String[]{"background", "surface", "text", "muted", "border", "accent", "container", "onAccent"})
            palette.put(role, String.format(java.util.Locale.ROOT, "#%06x", color(role, values) & 0xffffff));
        value.put("palette", palette);
        return value;
    }
    private String effectiveLanguage(String language) {
        if (!language.isEmpty()) return language;
        java.util.Locale system = context.getResources().getConfiguration().getLocales().get(0);
        if (system.getLanguage().equals("zh")) return system.getScript().equals("Hant")
            || java.util.Arrays.asList("TW", "HK", "MO").contains(system.getCountry()) ? "zh-TW" : "zh-CN";
        return system.getLanguage().equals("fr") ? "fr" : "en";
    }
}
