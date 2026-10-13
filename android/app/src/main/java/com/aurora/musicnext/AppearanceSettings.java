package com.aurora.musicnext;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import androidx.core.graphics.ColorUtils;
import org.json.JSONException;
import org.json.JSONObject;

/** Visual preferences contain no credentials and are shared with native widgets. */
final class AppearanceSettings {
    private final Context context;
    private final SharedPreferences preferences;

    AppearanceSettings(Context context) {
        this(context, "appearance");
    }
    /** Isolated preference profiles let native fixtures leave user settings untouched. */
    AppearanceSettings(Context context, String profile) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(profile, Context.MODE_PRIVATE);
    }

    void save(JSONObject value) throws JSONException {
        String mode = value.optString("mode", "system");
        if (!mode.equals("system") && !mode.equals("light") && !mode.equals("dark"))
            throw new IllegalArgumentException("Invalid theme mode");
        String accent = value.optString("accent", "#b94b5c");
        if (!accent.matches("#[0-9a-fA-F]{6}")) throw new IllegalArgumentException("Invalid accent color");
        int lyricsSize = validLyricsSize(value.has("lyricsSize") ? value.getDouble("lyricsSize") : preferences.getInt("lyricsSize", 18));
        boolean showBackdrop = value.has("showBackdrop") ? value.getBoolean("showBackdrop") : preferences.getBoolean("showBackdrop", true);
        preferences.edit().putString("mode", mode).putString("accent", accent)
            .putBoolean("dynamic", value.optBoolean("dynamic", true))
            .putBoolean("motion", value.optBoolean("motion", true))
            .putBoolean("lowPowerVisuals", value.optBoolean("lowPowerVisuals", true))
            .putBoolean("autoLookup", value.optBoolean("autoLookup", false)).putInt("lyricsSize", lyricsSize)
            .putBoolean("showBackdrop", showBackdrop).apply();
    }
    static int validLyricsSize(double size) {
        if (!Double.isFinite(size) || !java.util.Arrays.asList(18.0, 24.0, 30.0, 36.0).contains(size)) throw new IllegalArgumentException("Invalid lyric size");
        return (int) size;
    }

    boolean autoLookup() { return preferences.getBoolean("autoLookup", false); }
    void language(String language) {
        if (!java.util.Arrays.asList("zh-CN", "zh-TW", "en", "fr").contains(language)) throw new IllegalArgumentException("Invalid language");
        preferences.edit().putString("language", language).apply();
    }
    static Context localized(Context context) {
        String language = context.getSharedPreferences("appearance", Context.MODE_PRIVATE).getString("language", "");
        if (language.isEmpty()) return context;
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        configuration.setLocale(java.util.Locale.forLanguageTag(language));
        return context.createConfigurationContext(configuration);
    }
    boolean dark() {
        String mode = preferences.getString("mode", "system");
        return mode.equals("dark") || (mode.equals("system") &&
            (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
    }

    int color(String role) {
        boolean dark = dark();
        boolean dynamic = preferences.getBoolean("dynamic", true) && Build.VERSION.SDK_INT >= 31;
        int accent = Color.parseColor(preferences.getString("accent", "#b94b5c"));
        switch (role) {
            case "background": return systemColor(dynamic, dark ? "system_neutral1_900" : "system_neutral1_10", dark ? 0xff17171c : 0xfff8f7f5);
            case "surface": return systemColor(dynamic, dark ? "system_neutral1_800" : "system_neutral1_0", dark ? 0xff24242b : Color.WHITE);
            case "text": return systemColor(dynamic, dark ? "system_neutral1_50" : "system_neutral1_900", dark ? 0xfff0edf0 : 0xff252329);
            case "muted": return systemColor(dynamic, dark ? "system_neutral2_200" : "system_neutral2_600", dark ? 0xffc1bac1 : 0xff746d75);
            case "border": return systemColor(dynamic, dark ? "system_neutral2_700" : "system_neutral2_200", dark ? 0xff49434b : 0xffded8df);
            case "accent": return systemColor(dynamic, dark ? "system_accent1_200" : "system_accent1_600", dark ? ColorUtils.blendARGB(accent, Color.WHITE, .38f) : accent);
            case "container": return systemColor(dynamic, dark ? "system_accent1_800" : "system_accent1_100", ColorUtils.blendARGB(accent, dark ? 0xff24242b : Color.WHITE, dark ? .72f : .88f));
            case "onAccent": return ColorUtils.calculateContrast(Color.WHITE, color("accent")) >= 4.5 ? Color.WHITE : 0xff151218;
            default: throw new IllegalArgumentException("Unknown palette role");
        }
    }

    private int systemColor(boolean enabled, String name, int fallback) {
        if (!enabled) return fallback;
        int resource = context.getResources().getIdentifier(name, "color", "android");
        return resource == 0 ? fallback : context.getColor(resource);
    }

    JSONObject status() throws JSONException {
        JSONObject value = new JSONObject();
        value.put("mode", preferences.getString("mode", "system"));
        value.put("accent", preferences.getString("accent", "#b94b5c"));
        value.put("dynamic", preferences.getBoolean("dynamic", true));
        value.put("dynamicAvailable", Build.VERSION.SDK_INT >= 31);
        value.put("motion", preferences.getBoolean("motion", true));
        value.put("lowPowerVisuals", preferences.getBoolean("lowPowerVisuals", true));
        value.put("autoLookup", autoLookup());
        value.put("lyricsSize", preferences.getInt("lyricsSize", 18));
        value.put("showBackdrop", preferences.getBoolean("showBackdrop", true));
        value.put("dark", dark());
        value.put("systemReducedMotion", Settings.Global.getFloat(context.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0);
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        value.put("powerSave", power != null && power.isPowerSaveMode());
        value.put("batteryOptimized", power != null && !power.isIgnoringBatteryOptimizations(context.getPackageName()));
        ActivityManager activity = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        value.put("backgroundRestricted", Build.VERSION.SDK_INT >= 28 && activity != null && activity.isBackgroundRestricted());
        JSONObject palette = new JSONObject();
        for (String role : new String[]{"background", "surface", "text", "muted", "border", "accent", "container", "onAccent"})
            palette.put(role, String.format(java.util.Locale.ROOT, "#%06x", color(role) & 0xffffff));
        value.put("palette", palette);
        return value;
    }
}
