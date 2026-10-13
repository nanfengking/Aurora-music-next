package com.aurora.musicnext;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;
import androidx.core.content.ContextCompat;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.File;

/** Native widgets never instantiate a WebView and only refresh on relevant changes. */
public class MusicWidgetProvider extends AppWidgetProvider {
    private static final String ACTION_CONTROL = "com.aurora.musicnext.WIDGET_CONTROL";
    private static String cachedCover = "";
    private static Bitmap artwork;
    protected boolean lyrics() { return false; }

    static boolean hasWidgets(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        return manager.getAppWidgetIds(new ComponentName(context, MusicWidgetProvider.class)).length > 0
            || manager.getAppWidgetIds(new ComponentName(context, LyricsWidgetProvider.class)).length > 0;
    }
    static void refresh(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        WidgetSnapshot value = WidgetSnapshot.load(context);
        update(context, manager, manager.getAppWidgetIds(new ComponentName(context, MusicWidgetProvider.class)), value, false);
        update(context, manager, manager.getAppWidgetIds(new ComponentName(context, LyricsWidgetProvider.class)), value, true);
    }

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        update(context, manager, ids, WidgetSnapshot.load(context), lyrics());
        PlaybackService.widgetsChanged();
    }
    @Override public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle options) {
        update(context, manager, new int[]{id}, WidgetSnapshot.load(context), lyrics());
    }
    @Override public void onDisabled(Context context) {
        PlaybackService.widgetsChanged();
        if (!hasWidgets(context)) { artwork = null; cachedCover = ""; }
    }

    @Override public void onReceive(Context context, Intent intent) {
        if (!ACTION_CONTROL.equals(intent.getAction())) { super.onReceive(context, intent); return; }
        String action = intent.getStringExtra("control");
        if (!"toggle".equals(action) && !"next".equals(action) && !"previous".equals(action)) return;
        PendingResult pending = goAsync();
        // A manifest receiver's restricted context cannot bind a MediaController service.
        Context application = context.getApplicationContext();
        ListenableFuture<MediaController> future = new MediaController.Builder(application,
            new SessionToken(application, new ComponentName(application, PlaybackService.class))).buildAsync();
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable finish = () -> {
            if (finished.compareAndSet(false, true)) { MediaController.releaseFuture(future); pending.finish(); }
        };
        Runnable timeout = finish;
        handler.postDelayed(timeout, 8000);
        future.addListener(() -> {
            if (finished.get()) return;
            handler.removeCallbacks(timeout);
            try {
                MediaController player = future.get();
                if ("toggle".equals(action)) {
                    if (player.getPlayerError() == null && player.getPlayWhenReady()) player.pause();
                    else {
                        if (player.getMediaItemCount() > 0 && player.getPlaybackState() == Player.STATE_IDLE) player.prepare();
                        if (player.getPlaybackState() == Player.STATE_ENDED) player.seekToDefaultPosition();
                        player.play();
                    }
                } else if ("next".equals(action) && player.hasNextMediaItem()) player.seekToNextMediaItem();
                else if ("previous".equals(action)) player.seekToPreviousMediaItem();
            } catch (Exception ignored) {
                // An empty or disconnected queue remains idle; never start unrelated audio.
            } finally { finish.run(); }
        }, ContextCompat.getMainExecutor(application));
    }

    private static PendingIntent control(Context context, int id, String action) {
        Intent intent = new Intent(context, MusicWidgetProvider.class).setAction(ACTION_CONTROL)
            .setData(android.net.Uri.parse("aurora-widget://" + id + "/" + action)).putExtra("control", action);
        return PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private static void update(Context context, AppWidgetManager manager, int[] ids, WidgetSnapshot value, boolean lyrics) {
        if (ids.length == 0) return;
        context = AppearanceSettings.localized(context);
        AppearanceSettings theme = new AppearanceSettings(context);
        Bitmap image = cover(context, value.cover);
        for (int id : ids) {
            int width = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250);
            RemoteViews views = new RemoteViews(context.getPackageName(), lyrics ? R.layout.widget_lyrics : R.layout.widget_music);
            views.setInt(android.R.id.background, "setBackgroundColor", theme.color("surface"));
            views.setTextViewText(R.id.widget_title, value.title.isEmpty() ? context.getString(R.string.widget_empty) : value.title);
            views.setTextViewText(R.id.widget_artist, value.artist.isEmpty() ? "AURORA MUSIC" : value.artist);
            views.setTextViewText(R.id.widget_toggle, value.playing ? "Ⅱ" : "▶");
            for (int text : new int[]{R.id.widget_title, R.id.widget_toggle, R.id.widget_previous, R.id.widget_next})
                views.setTextColor(text, theme.color("text"));
            views.setTextColor(R.id.widget_artist, theme.color("muted"));
            views.setInt(R.id.widget_accent, "setBackgroundColor", theme.color("accent"));
            views.setViewVisibility(R.id.widget_cover, image == null || (!lyrics && width < 280) ? View.GONE : View.VISIBLE);
            if (image != null) views.setImageViewBitmap(R.id.widget_cover, image);
            if (lyrics) {
                views.setTextViewText(R.id.widget_lyric, value.lyric.isEmpty() ? context.getString(R.string.widget_no_lyrics) : value.lyric);
                views.setTextViewText(R.id.widget_next_lyric, value.nextLyric);
                views.setTextColor(R.id.widget_lyric, theme.color("text"));
                views.setTextColor(R.id.widget_next_lyric, theme.color("muted"));
            }
            if (!lyrics) {
                views.setTextViewTextSize(R.id.widget_title, android.util.TypedValue.COMPLEX_UNIT_SP, width < 250 ? 16 : 18);
                views.setInt(R.id.widget_title, "setMaxLines", width < 250 ? 1 : 2);
            }
            if (lyrics) views.setTextViewTextSize(R.id.widget_lyric, android.util.TypedValue.COMPLEX_UNIT_SP, width < 250 ? 19 : 25);
            views.setOnClickPendingIntent(R.id.widget_toggle, control(context, id, "toggle"));
            views.setOnClickPendingIntent(R.id.widget_next, control(context, id, "next"));
            views.setOnClickPendingIntent(R.id.widget_previous, control(context, id, "previous"));
            Intent open = new Intent(context, MainActivity.class).setAction("com.aurora.musicnext.OPEN_PLAYER")
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            views.setOnClickPendingIntent(android.R.id.background, PendingIntent.getActivity(context, id, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            manager.updateAppWidget(id, views);
        }
    }

    private static synchronized Bitmap cover(Context context, String path) {
        String revision = path + ":" + new File(path).lastModified();
        if (revision.equals(cachedCover)) return artwork;
        cachedCover = revision; artwork = null;
        if (path.isEmpty()) return null;
        try {
            File file = new File(path).getCanonicalFile();
            if (!file.getPath().startsWith(context.getFilesDir().getCanonicalPath() + File.separator) || !file.isFile()) return null;
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 192) options.inSampleSize *= 2;
            artwork = BitmapFactory.decodeFile(path, options);
        } catch (java.io.IOException ignored) { }
        return artwork;
    }
}
