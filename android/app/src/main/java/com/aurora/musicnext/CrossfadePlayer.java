package com.aurora.musicnext;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.ForwardingSimpleBasePlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.List;

/** Stable MediaSession player: UI, widget, notification and headset commands share the same path. */
@OptIn(markerClass = UnstableApi.class)
final class CrossfadePlayer extends ForwardingSimpleBasePlayer {
    interface Volume { void save(float value); }
    private final PlaybackCrossfade fade;
    private final Volume volume;
    CrossfadePlayer(Player player, PlaybackCrossfade fade, Volume volume) { super(player); this.fade = fade; this.volume = volume; }
    void use(Player player) { setPlayer(player); }
    void focusChanged() { invalidateState(); }
    @Override protected State getState() {
        State state = super.getState();
        if (fade.focus == null) return state;
        return state.buildUpon().setPlayWhenReady(fade.focus.intent.ready, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
            .setPlaybackSuppressionReason(fade.focus.intent.waiting ? Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS : Player.PLAYBACK_SUPPRESSION_REASON_NONE).build();
    }
    @Override protected ListenableFuture<?> handleSetMediaItems(List<MediaItem> items, int index, long position) {
        ListenableFuture<?> transition = fade.manual(items, index == C.INDEX_UNSET ? 0 : index, position);
        if (transition != null) return transition;
        fade.seekInterrupted(); return super.handleSetMediaItems(items, index, position);
    }
    @Override protected ListenableFuture<?> handleSeek(int index, long position, int command) {
        ListenableFuture<?> transition = fade.manual(fade.items(), index, position);
        if (transition != null) return transition;
        fade.seekInterrupted(); return super.handleSeek(index, position, command);
    }
    @Override protected ListenableFuture<?> handleSetPlayWhenReady(boolean ready) { fade.playIntent(ready); return Futures.immediateVoidFuture(); }
    @Override protected ListenableFuture<?> handlePrepare() { return fade.manualPending() ? Futures.immediateVoidFuture() : super.handlePrepare(); }
    @Override protected ListenableFuture<?> handleStop() { fade.playIntent(false); fade.seekInterrupted(); return super.handleStop(); }
    @Override protected ListenableFuture<?> handleSetVolume(float value, int operation) { volume.save(value); return Futures.immediateVoidFuture(); }
    @Override protected ListenableFuture<?> handleRemoveMediaItems(int from, int to) { fade.seekInterrupted(); return super.handleRemoveMediaItems(from, to); }
    @Override protected ListenableFuture<?> handleAddMediaItems(int index, List<MediaItem> items) { fade.seekInterrupted(); return super.handleAddMediaItems(index, items); }
    @Override protected ListenableFuture<?> handleMoveMediaItems(int from, int to, int index) { fade.seekInterrupted(); return super.handleMoveMediaItems(from, to, index); }
    @Override protected ListenableFuture<?> handleReplaceMediaItems(int from, int to, List<MediaItem> items) { fade.seekInterrupted(); return super.handleReplaceMediaItems(from, to, items); }
}
