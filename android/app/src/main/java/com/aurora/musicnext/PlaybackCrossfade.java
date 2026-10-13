package com.aurora.musicnext;

import android.content.Context;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Main-thread, two-stream handoff. Only a near-boundary/manual request creates a second decoder. */
@OptIn(markerClass = UnstableApi.class)
final class PlaybackCrossfade implements AutoCloseable {
    interface Host {
        ExoPlayer current();
        ExoPlayer create();
        void release(ExoPlayer player);
        PlaybackSettings settings();
        boolean blocked();
        ReplayGain tags();
        float volume();
        void handoff(ExoPlayer next, boolean automatic);
        void install(List<MediaItem> items, int index, long position, boolean ready);
        void changed();
        void outgoingFinished(ExoPlayer old);
    }
    private final Context context;
    private final Handler handler;
    private final Host host;
    private final AudioAttributes attributes;
    private final Runnable boundary = this::update;
    private final Runnable ramp = this::advance;
    private Request request;
    private ExoPlayer outgoing;
    private ReplayGain outgoingTags = ReplayGain.NONE;
    private long started, duration;
    private String attempted;
    private boolean closed, changing;
    private int configuredSeconds;
    PlaybackFocus focus;
    CrossfadePlayer facade;
    PlaybackCrossfade(Context context, Handler handler, AudioAttributes attributes, Host host) {
        this.context = context.getApplicationContext(); this.handler = handler; this.attributes = attributes; this.host = host;
    }
    boolean busy() { return request != null || outgoing != null; }
    boolean manualPending() { return request != null && !request.automatic; }
    boolean readyIntent() { return focus == null ? host.current().getPlayWhenReady() : focus.intent.ready; }
    float duck() { return focus == null ? 1 : focus.multiplier; }
    void configure() {
        int seconds = host.settings().crossfadeSeconds();
        if (seconds != configuredSeconds) {
            interrupt(true, readyIntent()); attempted = null;
            if (seconds > 0 && focus == null) {
                boolean ready = host.current().getPlayWhenReady();
                host.current().setAudioAttributes(attributes, false);
                focus = new PlaybackFocus(context, handler, attributes, this::focusChanged);
                focus.intent.request(ready);
                if (ready) host.current().pause();
                focus.request(ready, host.current().getPlaybackState());
            } else if (seconds == 0 && focus != null) {
                boolean ready = focus.intent.ready;
                focus.close(); focus = null;
                host.current().setAudioAttributes(attributes, true); host.current().setPlayWhenReady(ready);
            }
            configuredSeconds = seconds;
        }
        if (request != null) request.prepared.setPlaybackParameters(host.current().getPlaybackParameters());
        if (outgoing != null) outgoing.setPlaybackParameters(host.current().getPlaybackParameters());
        applyVolume(); update();
    }
    void playIntent(boolean ready) {
        if (!ready) interrupt(true, false);
        if (focus == null) host.current().setPlayWhenReady(ready);
        else { focus.request(ready, host.current().getPlaybackState()); focusChanged(); }
    }
    private void focusChanged() {
        if (closed || changing || focus == null) return;
        if (!focus.intent.allowed()) interrupt(true, false);
        host.current().setPlayWhenReady(focus.intent.allowed());
        applyVolume(); if (facade != null) facade.focusChanged(); host.changed(); update();
    }
    void noisy() { playIntent(false); }
    void seekInterrupted() { interrupt(false, false); attempted = null; }
    List<MediaItem> items() { return manualPending() ? request.items : copy(host.current()); }
    private static List<MediaItem> copy(Player player) {
        List<MediaItem> items = new ArrayList<>();
        for (int i = 0; i < player.getMediaItemCount(); i++) items.add(player.getMediaItemAt(i));
        return items;
    }
    private boolean savingPower() {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return power != null && power.isPowerSaveMode();
    }
    ListenableFuture<?> manual(List<MediaItem> items, int index, long position) {
        ExoPlayer player = host.current();
        if (closed || host.settings().crossfadeSeconds() == 0 || host.blocked() || savingPower() || !player.isPlaying()
            || index < 0 || index >= items.size() || items.size() > 80 || position != 0 && position != C.TIME_UNSET
            || player.getCurrentMediaItem() == null || player.getCurrentMediaItem().mediaId.equals(items.get(index).mediaId)) return null;
        interrupt(false, false);
        return prepare(items, index, position, false);
    }
    private ListenableFuture<?> prepare(List<MediaItem> items, int index, long position, boolean automatic) {
        try {
            ExoPlayer prepared = host.create();
            request = new Request(prepared, new ArrayList<>(items), index, position, automatic);
            Request pending = request;
            host.changed(); prepared.setVolume(0); prepared.setPlaybackParameters(host.current().getPlaybackParameters());
            prepared.setMediaItems(pending.items, index, position); prepared.addListener(pending.listener);
            handler.postDelayed(pending.timeout, CrossfadePolicy.PREPARE_TIMEOUT_MS);
            prepared.prepare();
            return pending.completion;
        } catch (RuntimeException unavailable) {
            cancelRequest(false, false); return null;
        }
    }
    void update() {
        if (closed || changing) return;
        handler.removeCallbacks(boundary);
        ExoPlayer player = host.current();
        if (focus != null) focus.sync(player.getPlaybackState());
        if (outgoing != null) {
            if (!player.isPlaying() || player.getPlayerError() != null || savingPower() || host.settings().crossfadeSeconds() == 0) finishOutgoing();
            return;
        }
        if (request != null && !request.automatic) {
            if (!player.isPlaying() || savingPower() || host.settings().crossfadeSeconds() == 0) cancelRequest(true, readyIntent());
            else if (request.prepared.getPlaybackState() == Player.STATE_READY) commit();
            return;
        }
        boolean allowed = CrossfadePolicy.automatic(host.settings().crossfadeSeconds(), player.isPlaying(), player.isLoading(),
            player.getPlayerError() != null, host.blocked(), savingPower(), host.settings().repeat().equals("one"),
            player.isCurrentMediaItemLive() || player.isPlayingAd(), player.getDuration(), player.getPlaybackParameters().speed);
        int next = player.getNextMediaItemIndex();
        String current = player.getCurrentMediaItem() == null ? "" : player.getCurrentMediaItem().mediaId;
        MediaItem item = next < 0 ? null : player.getMediaItemAt(next);
        String key = allowed && item != null && item.localConfiguration != null
            ? current + "|" + item.mediaId + "|" + item.localConfiguration.uri + "|" + item.localConfiguration.customCacheKey : null;
        if (!allowed || key == null || current.equals(item.mediaId)) { cancelRequest(false, false); return; }
        if (request != null && (!Objects.equals(key, request.key) || !copy(player).equals(request.items))) cancelRequest(false, false);
        long remaining = CrossfadePolicy.remaining(player.getDuration(), player.getCurrentPosition(), player.getPlaybackParameters().speed);
        if (request != null) {
            if (request.prepared.getPlaybackState() == Player.STATE_READY && remaining <= host.settings().crossfadeSeconds() * 1000L) commit();
            else handler.postDelayed(boundary, Math.max(100, remaining - host.settings().crossfadeSeconds() * 1000L));
        } else if (!Objects.equals(attempted, key)) {
            long delay = CrossfadePolicy.delay(remaining, host.settings().crossfadeSeconds());
            if (delay > 0) handler.postDelayed(boundary, delay);
            else {
                attempted = key;
                if (prepare(copy(player), next, 0, true) != null && request != null) request.key = key;
            }
        }
    }
    private void commit() {
        Request pending = request;
        if (pending == null || changing || !host.current().isPlaying()) return;
        long remaining = CrossfadePolicy.remaining(host.current().getDuration(), host.current().getCurrentPosition(), host.current().getPlaybackParameters().speed);
        long overlap = CrossfadePolicy.overlap(remaining, host.settings().crossfadeSeconds());
        if (overlap < 100) { cancelRequest(!pending.automatic, readyIntent()); return; }
        changing = true;
        request = null; handler.removeCallbacks(pending.timeout); pending.prepared.removeListener(pending.listener);
        outgoing = host.current(); outgoingTags = host.tags(); duration = overlap; started = SystemClock.elapsedRealtime();
        try {
            host.handoff(pending.prepared, pending.automatic);
            if (facade != null) facade.use(pending.prepared);
            pending.prepared.setPlayWhenReady(focus == null || focus.intent.allowed());
            applyVolume(); handler.postDelayed(ramp, CrossfadePolicy.FRAME_MS);
        } catch (RuntimeException unavailable) {
            if (host.current() != pending.prepared) { host.release(pending.prepared); outgoing = null; host.install(pending.items, pending.index, pending.position, readyIntent()); }
            else { if (facade != null) facade.use(pending.prepared); finishOutgoing(); }
        } finally {
            changing = false; pending.completion.set(null); host.changed(); update();
        }
    }
    void applyVolume() {
        float weight = outgoing == null ? 1 : CrossfadePolicy.progress(SystemClock.elapsedRealtime() - started, duration);
        float current = CrossfadePolicy.volume(host.volume(), 1, weight, duck());
        if (host.current().getVolume() != current) host.current().setVolume(current);
        if (outgoing != null) outgoing.setVolume(CrossfadePolicy.volume(host.settings().volume(), outgoingTags.factor(host.settings().normalizeVolume()), 1 - weight, duck()));
    }
    private void advance() {
        if (closed || outgoing == null) return;
        if (!host.current().isPlaying() || savingPower() || SystemClock.elapsedRealtime() - started >= duration) { finishOutgoing(); update(); return; }
        applyVolume(); handler.postDelayed(ramp, CrossfadePolicy.FRAME_MS);
    }
    private void finishOutgoing() {
        handler.removeCallbacks(ramp);
        ExoPlayer old = outgoing; outgoing = null; outgoingTags = ReplayGain.NONE;
        if (old != null) { host.outgoingFinished(old); old.setVolume(0); host.release(old); }
        if (!closed) { applyVolume(); host.changed(); }
    }
    /** A pause/settings change preserves an explicit selection, but a new seek supersedes it. */
    void interrupt(boolean preserveSelection, boolean ready) {
        handler.removeCallbacks(boundary); finishOutgoing(); cancelRequest(preserveSelection, ready);
    }
    private void cancelRequest(boolean preserveSelection, boolean ready) {
        Request old = request; request = null;
        if (old == null) return;
        handler.removeCallbacks(old.timeout); old.prepared.removeListener(old.listener); host.release(old.prepared);
        try { if (preserveSelection && !old.automatic) host.install(old.items, old.index, old.position, ready); }
        finally { old.completion.set(null); if (!closed) host.changed(); }
    }
    @Override public void close() {
        if (closed) return;
        closed = true; interrupt(false, false); if (focus != null) focus.close();
    }
    private final class Request {
        final ExoPlayer prepared;
        final List<MediaItem> items;
        final int index;
        final long position;
        final boolean automatic;
        final SettableFuture<Void> completion = SettableFuture.create();
        String key;
        final Runnable timeout = this::expired;
        private void expired() { if (request == this) { cancelRequest(!automatic, readyIntent()); update(); } }
        final Player.Listener listener = new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) { if (request == Request.this && state == Player.STATE_READY) update(); }
            @Override public void onPlayerError(androidx.media3.common.PlaybackException error) { if (request == Request.this) { cancelRequest(!automatic, readyIntent()); update(); } }
        };
        Request(ExoPlayer prepared, List<MediaItem> items, int index, long position, boolean automatic) {
            this.prepared = prepared; this.items = items; this.index = index; this.position = position; this.automatic = automatic;
        }
    }
}
