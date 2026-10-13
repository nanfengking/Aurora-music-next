package com.aurora.musicnext;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.PowerManager;
import androidx.annotation.OptIn;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One low-priority, cancellable next-track transfer; no service, work or timer while idle. */
@OptIn(markerClass = UnstableApi.class)
final class PlaybackPrefetch implements AutoCloseable {
    private final Context context;
    private final Handler handler;
    private final Runnable changed;
    private final ConnectivityManager connectivity;
    private final PrefetchPolicy.Gate gate = new PrefetchPolicy.Gate();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(0, 1, 10, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1));
    private Job job;
    private boolean closed, networkRegistered, powerRegistered, watchAttempted;
    private String heldTrack;
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) { notifyChanged(); }
        @Override public void onLost(Network network) { notifyChanged(); }
        @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) { notifyChanged(); }
    };
    private final BroadcastReceiver powerReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) { notifyChanged(); }
    };
    PlaybackPrefetch(Context context, Handler handler, Runnable changed) {
        this.context = context.getApplicationContext(); this.handler = handler; this.changed = changed;
        connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }
    private void startWatching() {
        if (watchAttempted) return;
        watchAttempted = true;
        try { if (connectivity != null) { connectivity.registerDefaultNetworkCallback(networkCallback); networkRegistered = true; } }
        catch (RuntimeException unavailable) { /* Optional prefetch must not break playback. */ }
        try {
            ContextCompat.registerReceiver(this.context, powerReceiver, new IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED);
            powerRegistered = true;
        } catch (RuntimeException unavailable) { /* Recheck the current power policy on player events. */ }
    }
    private void notifyChanged() { handler.post(() -> { if (!closed) changed.run(); }); }
    /** Main thread only. Queue order comes from the native player's complete SQL-backed window. */
    void update(Player player, PlaybackSettings options, boolean changingQueue) {
        if (closed) return;
        if (!options.prefetch() || !player.isPlaying()) { cancel(); gate.reset(); stopWatching(); return; }
        startWatching();
        String current = player.getCurrentMediaItem() == null ? null : player.getCurrentMediaItem().mediaId;
        if (!Objects.equals(current, heldTrack)) heldTrack = null;
        MediaItem next = null; String key = null;
        try {
            Network network = connectivity == null ? null : connectivity.getActiveNetwork();
            NetworkCapabilities capabilities = network == null ? null : connectivity.getNetworkCapabilities(network);
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            SecretStore secrets = new SecretStore(context);
            boolean allowed = networkRegistered && PrefetchPolicy.allowed(options.prefetch(), player.isPlaying(), player.isLoading(),
                player.getPlaybackState() == Player.STATE_BUFFERING, player.getPlayerError() != null,
                power != null && power.isPowerSaveMode(), capabilities != null,
                secrets.wifiOnly(), capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED), options.repeat().equals("one"));
            int index = player.getNextMediaItemIndex();
            if (allowed && !changingQueue && heldTrack == null && current != null && index >= 0) {
                MediaItem candidate = player.getMediaItemAt(index);
                if (!current.equals(candidate.mediaId) && candidate.localConfiguration != null
                    && "https".equalsIgnoreCase(candidate.localConfiguration.uri.getScheme())) {
                    next = candidate;
                    key = current + "|" + next.mediaId + "|" + next.localConfiguration.customCacheKey + "|" + network.getNetworkHandle() + "|" + secrets.wifiOnly();
                }
            }
        } catch (RuntimeException unavailable) { /* Cancel optional work on an unknown policy. */ }
        if (!gate.change(key)) return;
        cancel();
        if (key == null || next == null || gate.finished(key)) return;
        job = new Job(next, key); handler.postDelayed(job.start, PrefetchPolicy.START_DELAY_MS);
    }
    void reset() { cancel(); gate.reset(); heldTrack = null; }
    void hold(String currentTrack) { cancel(); gate.reset(); heldTrack = currentTrack; }
    private void cancel() {
        if (job == null) return;
        handler.removeCallbacks(job.start); handler.removeCallbacks(job.timeout); job.cancellation.cancel();
        if (job.future != null) job.future.cancel(true);
        executor.purge(); job = null;
    }
    @Override public void close() {
        closed = true; cancel(); executor.shutdownNow();
        stopWatching();
    }
    private void stopWatching() {
        if (networkRegistered) try { connectivity.unregisterNetworkCallback(networkCallback); } catch (RuntimeException ignored) {}
        if (powerRegistered) try { context.unregisterReceiver(powerReceiver); } catch (RuntimeException ignored) {}
        networkRegistered = false; powerRegistered = false; watchAttempted = false;
    }
    private final class Job {
        final MediaItem item;
        final String key;
        final PrefetchCancellation cancellation = new PrefetchCancellation();
        Future<?> future;
        final Runnable timeout = () -> { if (job == this) cancel(); };
        final Runnable start = () -> {
            if (closed || job != this) return;
            try { future = executor.submit(this::run); handler.postDelayed(timeout, PrefetchPolicy.DEADLINE_MS); }
            catch (RuntimeException unavailable) { cancel(); }
        };
        Job(MediaItem item, String key) { this.item = item; this.key = key; }
        void run() {
            try (MusicStore store = new MusicStore(context)) {
                cancellation.check();
                MusicStore.PrefetchSource source = store.prefetchSource(item.mediaId);
                if (source == null || item.localConfiguration == null || !source.address.equals(item.localConfiguration.uri.toString())
                    || !source.stamp.equals(item.localConfiguration.customCacheKey)) return;
                SecretStore.Connection connection = new SecretStore(context).connection();
                connection.authorizationFor(source.address); cancellation.check();
                StreamCache.prefetch(context, connection, source.address, source.stamp, source.size, cancellation);
            } catch (Exception unavailable) { /* No retry loop or raw URL/credential diagnostics for optional work. */ }
            finally {
                cancellation.unbind();
                handler.post(() -> { if (job == this) { handler.removeCallbacks(timeout); if (!cancellation.cancelled()) gate.complete(key); } });
            }
        }
    }
}
