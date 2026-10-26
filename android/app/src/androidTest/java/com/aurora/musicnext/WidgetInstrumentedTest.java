package com.aurora.musicnext;

import android.content.Context;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class WidgetInstrumentedTest {
    @Test public void backdropPreferencePreservesLegacyEditsAndRejectsInvalidSizeAtomically() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String profile = "appearance-fixture-" + java.util.UUID.randomUUID();
        try {
            AppearanceSettings settings = new AppearanceSettings(context, profile);
            settings.repository.ready().get(6, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(settings.status().getBoolean("showBackdrop"));
            settings.save(new org.json.JSONObject().put("showBackdrop", false)).get(6, java.util.concurrent.TimeUnit.SECONDS);
            assertFalse(new AppearanceSettings(context, profile).status().getBoolean("showBackdrop"));
            settings.save(new org.json.JSONObject().put("mode", "dark").put("lyricsSize", 36)).get(6, java.util.concurrent.TimeUnit.SECONDS);
            assertFalse(settings.status().getBoolean("showBackdrop"));
            try { settings.save(new org.json.JSONObject().put("showBackdrop", true).put("lyricsSize", 900)).get(6, java.util.concurrent.TimeUnit.SECONDS); fail("Expected invalid size"); }
            catch (java.util.concurrent.ExecutionException expected) { assertTrue(expected.getCause() instanceof IllegalArgumentException); }
            assertFalse(settings.status().getBoolean("showBackdrop"));
            assertEquals(36, settings.status().getInt("lyricsSize"));
        } finally {
            context.deleteDatabase(profile);
            for (String name : new String[]{"playback-options", "appearance", "connection"}) context.deleteSharedPreferences(name + "-" + profile);
        }
    }
    @Test public void bothLayoutsUseSupportedRemoteViews() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (int layout : new int[]{R.layout.widget_music, R.layout.widget_lyrics}) {
                RemoteViews views = new RemoteViews(context.getPackageName(), layout);
                views.setTextViewText(R.id.widget_title, "A fixture song");
                assertNotNull(views.apply(context, new FrameLayout(context)).findViewById(R.id.widget_toggle));
            }
        });
    }
}
