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
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import java.io.File;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/** Native widgets never instantiate a WebView and only refresh on relevant changes. */
public class MusicWidgetProvider extends AppWidgetProvider {
    private static final String ACTION_CONTROL = "com.aurora.musicnext.WIDGET_CONTROL";
    private static String cachedCover = "";
    private static Bitmap artwork;
    private static final Map<Context, Refresh> refreshes = new WeakHashMap<>();
    protected boolean lyrics() { return false; }

    static boolean hasWidgets(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        return manager.getAppWidgetIds(new ComponentName(context, MusicWidgetProvider.class)).length > 0
            || manager.getAppWidgetIds(new ComponentName(context, LyricsWidgetProvider.class)).length > 0;
    }
    static ListenableFuture<Void> refresh(Context context) {
        Context application = context.getApplicationContext();
        Refresh refresh;
        synchronized (refreshes) {
            refresh = refreshes.get(application);
            if (refresh == null) { refresh = new Refresh(application); refreshes.put(application, refresh); refresh.start(); }
        }
        return Futures.nonCancellationPropagating(refresh.result);
    }
    private static void paint(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        WidgetSnapshot value = WidgetSnapshot.load(context);
        update(context, manager, manager.getAppWidgetIds(new ComponentName(context, MusicWidgetProvider.class)), value, false);
        update(context, manager, manager.getAppWidgetIds(new ComponentName(context, LyricsWidgetProvider.class)), value, true);
    }

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        if (!ready(context)) { refresh(context); return; }
        update(context, manager, ids, WidgetSnapshot.load(context), lyrics());
        PlaybackService.widgetsChanged();
    }
    @Override public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle options) {
        if (!ready(context)) { refresh(context); return; }
        update(context, manager, new int[]{id}, WidgetSnapshot.load(context), lyrics());
    }
    @Override public void onDisabled(Context context) {
        PlaybackService.widgetsChanged();
        if (!hasWidgets(context)) { artwork = null; cachedCover = ""; }
    }

    @Override public void onReceive(Context context, Intent intent) {
        boolean control = ACTION_CONTROL.equals(intent.getAction());
        boolean update = AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(intent.getAction())
            || AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED.equals(intent.getAction())
            || AppWidgetManager.ACTION_APPWIDGET_RESTORED.equals(intent.getAction());
        if (!control && !update) { super.onReceive(context, intent); return; }
        String action = control ? intent.getStringExtra("control") : null;
        if (control && !"toggle".equals(action) && !"next".equals(action) && !"previous".equals(action)) return;
        // A manifest receiver's restricted context cannot bind a MediaController service.
        Context application = context.getApplicationContext();
        new Broadcast(application, goAsync(), intent, action).start();
    }

    private static boolean ready(Context context) {
        try { NativePreferences.forApplication(context).requireReady(); return true; }
        catch (IllegalStateException cold) { return false; }
    }
    private static final class Refresh {
        final Context context;
        final SettableFuture<Void> result = SettableFuture.create();
        final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable timeout = () -> finish(new java.util.concurrent.TimeoutException("Widget preferences timed out"));
        ListenableFuture<NativePreferenceRecord> hydration;
        Refresh(Context context) { this.context = context; }
        void start() {
            handler.postDelayed(timeout, 8000); hydration = NativePreferences.forApplication(context).ready();
            WeakReference<Refresh> weak = new WeakReference<>(this);
            hydration.addListener(() -> { Refresh current = weak.get(); if (current != null) current.handler.post(current::apply); }, MoreExecutors.directExecutor());
        }
        void apply() {
            if (result.isDone()) return;
            try { Futures.getDone(hydration); paint(context); finish(null); }
            catch (Exception error) { finish(error); }
        }
        void finish(Exception error) {
            if (result.isDone()) return;
            handler.removeCallbacks(timeout);
            synchronized (refreshes) { if (refreshes.get(context) == this) refreshes.remove(context); }
            if (error == null) result.set(null); else result.setException(error);
        }
    }

    /** One broadcast owns one deadline spanning hydration AND binding, never two fresh timers. */
    private final class Broadcast {
        final Context context; final PendingResult pending; final Intent intent; final String action;
        final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable timeout = this::finish;
        ListenableFuture<NativePreferenceRecord> hydration;
        ListenableFuture<MediaController> controller;
        boolean finished;
        Broadcast(Context context, PendingResult pending, Intent intent, String action) {
            this.context = context; this.pending = pending; this.intent = intent; this.action = action;
        }
        void start() {
            handler.postDelayed(timeout, 8000); hydration = NativePreferences.forApplication(context).ready();
            WeakReference<Broadcast> weak = new WeakReference<>(this);
            // The timeout keeps this owner alive; the shared initializer must not do so after expiry.
            hydration.addListener(() -> { Broadcast current = weak.get(); if (current != null) current.handler.post(current::hydrated); }, MoreExecutors.directExecutor());
        }
        void hydrated() {
            if (finished) return;
            try {
                Futures.getDone(hydration);
                if (action == null) { MusicWidgetProvider.super.onReceive(context, intent); finish(); return; }
                controller = new MediaController.Builder(context,
                    new SessionToken(context, new ComponentName(context, PlaybackService.class))).buildAsync();
                controller.addListener(this::connected, ContextCompat.getMainExecutor(context));
            } catch (Exception failure) { finish(); }
        }
        void connected() {
            if (finished) return;
            try {
                MediaController player = Futures.getDone(controller);
                if ("toggle".equals(action)) {
                    if (player.getPlayerError() == null && player.getPlayWhenReady()) player.pause();
                    else {
                        if (player.getMediaItemCount() > 0 && player.getPlaybackState() == Player.STATE_IDLE) player.prepare();
                        if (player.getPlaybackState() == Player.STATE_ENDED) player.seekToDefaultPosition();
                        player.play();
                    }
                } else if ("next".equals(action) && player.hasNextMediaItem()) player.seekToNextMediaItem();
                else if ("previous".equals(action)) player.seekToPreviousMediaItem();
            } catch (Exception ignored) { /* Empty/disconnected queues stay idle. */ }
            finally { finish(); }
        }
        void finish() {
            if (finished) return;
            finished = true; handler.removeCallbacks(timeout);
            try { if (controller != null) MediaController.releaseFuture(controller); }
            finally { if (pending != null) pending.finish(); }
        }
    }

    private static PendingIntent control(Context context, int id, String action) {
        Intent intent = new Intent(context, MusicWidgetProvider.class).setAction(ACTION_CONTROL)
            .setData(android.net.Uri.parse("aurora-widget://" + id + "/" + action)).putExtra("control", action);
        return PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private static void update(Context context, AppWidgetManager manager, int[] ids, WidgetSnapshot value, boolean lyrics) {
        if (ids.length == 0) return;
        AppearanceSettings theme = new AppearanceSettings(context);
        context = AppearanceSettings.localized(context);
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
