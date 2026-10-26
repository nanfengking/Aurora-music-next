package com.aurora.musicnext;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.ForwardingSimpleBasePlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.MediaMetadata;
import android.os.Handler;
import androidx.media3.common.util.UnstableApi;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.List;

/** Stable MediaSession player: UI, widget, notification and headset commands share the same path. */
@OptIn(markerClass = UnstableApi.class)
final class CrossfadePlayer extends ForwardingSimpleBasePlayer {
    interface Volume { ListenableFuture<?> save(float value); }
    private static final State INERT = new State.Builder().setAvailableCommands(new Player.Commands.Builder().addAllCommands().build())
        .setVolume(0f).setUnmuteVolume(0f).build();
    private final PlaybackCrossfade fade;
    private final Volume volume;
    private final PlaybackReadiness gate;
    private final Handler handler;
    private int pendingVolume;
    CrossfadePlayer(Player player, PlaybackCrossfade fade, Volume volume, PlaybackReadiness gate) {
        super(player); this.fade = fade; this.volume = volume; this.gate = gate; handler = new Handler(player.getApplicationLooper());
    }
    void use(Player player) { setPlayer(player); }
    void focusChanged() { invalidateState(); }
    @Override protected State getState() {
        if (gate == null || !gate.ensureAppliedIfHydrated()) return INERT;
        State state = super.getState();
        if (fade.focus == null) return state;
        return state.buildUpon().setPlayWhenReady(fade.focus.intent.ready, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
            .setPlaybackSuppressionReason(fade.focus.intent.waiting ? Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS : Player.PLAYBACK_SUPPRESSION_REASON_NONE).build();
    }
    @Override protected State getPlaceholderState(State suggested) {
        if (!gate.ensureAppliedIfHydrated()) return INERT;
        return pendingVolume == 0 ? suggested : suggested.buildUpon().setVolume(getPlayer().getVolume()).build();
    }
    @Override protected ListenableFuture<?> handleSetMediaItems(List<MediaItem> items, int index, long position) {
        List<MediaItem> copied = new java.util.ArrayList<>(items);
        return gate.command(() -> {
            ListenableFuture<?> transition = fade.manual(copied, index == C.INDEX_UNSET ? 0 : index, position);
            if (transition != null) return transition;
            fade.seekInterrupted(); return super.handleSetMediaItems(copied, index, position);
        });
    }
    @Override protected ListenableFuture<?> handleSeek(int index, long position, int command) {
        return gate.command(() -> {
            ListenableFuture<?> transition = fade.manual(fade.items(), index, position);
            if (transition != null) return transition;
            fade.seekInterrupted(); return super.handleSeek(index, position, command);
        });
    }
    @Override protected ListenableFuture<?> handleSetPlayWhenReady(boolean ready) { return gate.command(() -> { fade.playIntent(ready); return Futures.immediateVoidFuture(); }); }
    @Override protected ListenableFuture<?> handlePrepare() { return gate.command(() -> fade.manualPending() ? Futures.immediateVoidFuture() : super.handlePrepare()); }
    @Override protected ListenableFuture<?> handleStop() { return gate.command(() -> { fade.playIntent(false); fade.seekInterrupted(); return super.handleStop(); }); }
    @Override protected ListenableFuture<?> handleRelease() { gate.close(new IllegalStateException("Playback released")); return super.handleRelease(); }
    @Override protected ListenableFuture<?> handleSetVolume(float value, int operation) {
        return gate.command(() -> {
            ListenableFuture<?> saved = volume.save(value); pendingVolume++;
            saved.addListener(() -> { pendingVolume--; if (gate.isApplied()) invalidateState(); }, handler::post);
            return saved;
        });
    }
    @Override protected ListenableFuture<?> handleRemoveMediaItems(int from, int to) { return gate.command(() -> { fade.seekInterrupted(); return super.handleRemoveMediaItems(from, to); }); }
    @Override protected ListenableFuture<?> handleAddMediaItems(int index, List<MediaItem> items) {
        List<MediaItem> copied = new java.util.ArrayList<>(items);
        return gate.command(() -> { fade.seekInterrupted(); return super.handleAddMediaItems(index, copied); });
    }
    @Override protected ListenableFuture<?> handleMoveMediaItems(int from, int to, int index) { return gate.command(() -> { fade.seekInterrupted(); return super.handleMoveMediaItems(from, to, index); }); }
    @Override protected ListenableFuture<?> handleReplaceMediaItems(int from, int to, List<MediaItem> items) {
        List<MediaItem> copied = new java.util.ArrayList<>(items);
        return gate.command(() -> { fade.seekInterrupted(); return super.handleReplaceMediaItems(from, to, copied); });
    }
    @Override protected ListenableFuture<?> handleSetRepeatMode(int mode) { return gate.command(() -> super.handleSetRepeatMode(mode)); }
    @Override protected ListenableFuture<?> handleSetShuffleModeEnabled(boolean enabled) { return gate.command(() -> super.handleSetShuffleModeEnabled(enabled)); }
    @Override protected ListenableFuture<?> handleSetPlaybackParameters(PlaybackParameters parameters) { return gate.command(() -> super.handleSetPlaybackParameters(parameters)); }
    @Override protected ListenableFuture<?> handleSetTrackSelectionParameters(TrackSelectionParameters parameters) { return gate.command(() -> super.handleSetTrackSelectionParameters(parameters)); }
    @Override protected ListenableFuture<?> handleSetPlaylistMetadata(MediaMetadata metadata) { return gate.command(() -> super.handleSetPlaylistMetadata(metadata)); }
    @Override protected ListenableFuture<?> handleSetDeviceVolume(int value, int flags) { return gate.command(() -> super.handleSetDeviceVolume(value, flags)); }
    @Override protected ListenableFuture<?> handleIncreaseDeviceVolume(int flags) { return gate.command(() -> super.handleIncreaseDeviceVolume(flags)); }
    @Override protected ListenableFuture<?> handleDecreaseDeviceVolume(int flags) { return gate.command(() -> super.handleDecreaseDeviceVolume(flags)); }
    @Override protected ListenableFuture<?> handleSetDeviceMuted(boolean muted, int flags) { return gate.command(() -> super.handleSetDeviceMuted(muted, flags)); }
    @Override protected ListenableFuture<?> handleSetAudioAttributes(AudioAttributes attributes, boolean focus) { return gate.command(() -> super.handleSetAudioAttributes(attributes, focus)); }
    @Override protected ListenableFuture<?> handleSetVideoOutput(Object output) { return gate.command(() -> super.handleSetVideoOutput(output)); }
    @Override protected ListenableFuture<?> handleClearVideoOutput(Object output) { return gate.command(() -> super.handleClearVideoOutput(output)); }
}
