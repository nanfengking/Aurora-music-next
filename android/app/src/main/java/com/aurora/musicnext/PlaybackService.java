package com.aurora.musicnext;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.core.content.ContextCompat;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.io.File;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Audio runs natively; the WebView is neither a player nor a keep-alive timer. */
@OptIn(markerClass = UnstableApi.class)
public final class PlaybackService extends MediaSessionService {
    private static PlaybackService active;
    private static WeakReference<Runnable> observer = new WeakReference<>(null);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private MediaSession session;
    private ExoPlayer player;
    private MusicStore store;
    private String currentId;
    private JSONObject currentSong;
    private LyricTimeline lyrics = LyricTimeline.parse(null);
    private boolean updatingQueue;
    private volatile boolean destroyed;
    private int queueGeneration;
    private long metadataRevision;
    private String lastMetadata = "";
    private String lastWidget = "";
    private String completedId;
    private boolean sessionStarted;
    private final ListeningSession listening = new ListeningSession();
    private long lastKnownPosition;
    private boolean lastPlayIntent;
    private PlaybackSettings options;
    private PlaybackPrefetch prefetch;
    private PlaybackCrossfade crossfade;
    private CrossfadePlayer sessionPlayer;
    private Player.Listener playerListener;
    private final java.util.Map<ExoPlayer, CancellationGroup> transfers = new java.util.IdentityHashMap<>();
    private String fadingCompletion;
    private static final AudioAttributes AUDIO_ATTRIBUTES = new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build();
    private final ReplayGain.State replayGain = new ReplayGain.State();
    private long sleepDeadline;
    private boolean reorderingQueue;
    private int orderGeneration;
    private final Runnable sleepTick = this::checkSleep;
    private final Runnable checkpoint = () -> { savePosition(); scheduleCheckpoint(); };
    private final Runnable lyricTick = () -> updateWidget(false);
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { updateWidget(true); if (crossfade != null) crossfade.update(); }
    };

    static void setObserver(@Nullable Runnable listener) { observer = new WeakReference<>(listener); }
    static void widgetsChanged() {
        if (active != null) active.handler.post(() -> { if (active != null) active.updateWidget(true); });
    }
    static void assetsChanged(String id) {
        if (active != null && id.equals(active.currentId)) active.reloadSong(id);
    }
    static void prefetchSettingsChanged() {
        PlaybackService service = active;
        if (service != null) service.handler.post(() -> { if (!service.destroyed) { service.prefetch.reset(); service.updatePrefetch(); } });
    }
    static void holdPrefetch() {
        PlaybackService service = active;
        if (service != null) service.handler.post(() -> { if (!service.destroyed) service.prefetch.hold(service.currentId); });
    }
    /** These methods are main-thread only; inspecting/changing idle settings never starts a service. */
    static JSONObject options(Context context) throws org.json.JSONException {
        JSONObject value = new PlaybackSettings(context).status();
        value.put("sleepRemainingSeconds", active == null || active.sleepDeadline == 0 ? 0
            : Math.max(0, (active.sleepDeadline - SystemClock.elapsedRealtime() + 999) / 1000));
        return value;
    }
    static JSONObject setOptions(Context context, JSONObject patch) throws org.json.JSONException {
        PlaybackSettings settings = new PlaybackSettings(context);
        boolean shuffleChanged = patch.has("shuffle") && patch.getBoolean("shuffle") != settings.shuffle();
        boolean repeatChanged = patch.has("repeatMode") && !patch.getString("repeatMode").equals(settings.repeat());
        boolean rememberChanged = patch.has("rememberPlayback") && patch.getBoolean("rememberPlayback") != settings.remember();
        boolean prefetchChanged = patch.has("prefetch") && patch.getBoolean("prefetch") != settings.prefetch();
        int sleep = patch.has("sleepMinutes") ? PlaybackSettings.validSleep(patch.getDouble("sleepMinutes")) : -1;
        if (sleep > 0 && (active == null || active.player.getCurrentMediaItem() == null))
            throw new IllegalStateException("Start playback before setting a sleep timer");
        settings.save(patch);
        if (active != null) {
            PlaybackService service = active;
            if (rememberChanged) {
                if (settings.remember()) service.savePosition();
                else { long revision = settings.revision(); service.io.execute(() -> service.store.forgetPlayback(revision)); }
                service.scheduleCheckpoint();
            }
            service.player.setPlaybackSpeed(settings.speed()); service.applyVolume();
            service.crossfade.configure();
            if (shuffleChanged || repeatChanged) service.crossfade.interrupt(true, service.crossfade.readyIntent());
            if (prefetchChanged) service.prefetch.reset();
            if (sleep >= 0) {
                service.handler.removeCallbacks(service.sleepTick);
                service.sleepDeadline = sleep == 0 ? 0 : SystemClock.elapsedRealtime() + sleep * 60000L;
                if (sleep > 0) service.handler.postDelayed(service.sleepTick, sleep * 60000L);
            }
            if (shuffleChanged || repeatChanged) service.refreshQueue(shuffleChanged);
            else service.publish();
            service.updatePrefetch();
        }
        return options(context);
    }
    private void checkSleep() {
        if (destroyed || sleepDeadline == 0) return;
        long remaining = sleepDeadline - SystemClock.elapsedRealtime();
        if (remaining > 0) { handler.removeCallbacks(sleepTick); handler.postDelayed(sleepTick, remaining); return; }
        sleepDeadline = 0; crossfade.playIntent(false); publish();
    }
    /** Called on the main thread. Reading status must not start/bind a playback service. */
    static JSONObject snapshot() throws org.json.JSONException {
        JSONObject result = new JSONObject();
        ExoPlayer playing = active == null ? null : active.player;
        MediaItem item = playing == null ? null : playing.getCurrentMediaItem();
        result.put("songId", item == null ? JSONObject.NULL : item.mediaId);
        result.put("playing", playing != null && playing.isPlaying());
        result.put("playWhenReady", playing != null && active.crossfade.readyIntent());
        result.put("buffering", playing != null && playing.getPlaybackState() == Player.STATE_BUFFERING);
        result.put("position", playing == null ? 0 : Math.max(0, playing.getCurrentPosition()) / 1000.0);
        result.put("duration", playing == null ? 0 : Math.max(0, playing.getDuration()) / 1000.0);
        result.put("speed", playing == null ? 1 : playing.getPlaybackParameters().speed);
        result.put("revision", active == null ? 0 : active.metadataRevision);
        PlaybackException error = playing == null ? null : playing.getPlayerError();
        result.put("error", error == null ? JSONObject.NULL : PlaybackErrors.code(error, error.errorCode));
        result.put("errorCode", error == null ? JSONObject.NULL : error.errorCode);
        return result;
    }

    @Override public void onCreate() {
        super.onCreate();
        active = this;
        store = new MusicStore(this);
        options = new PlaybackSettings(this);
        player = createPlayer(options.crossfadeSeconds() == 0);
        prefetch = new PlaybackPrefetch(this, handler, this::updatePrefetch);
        crossfade = new PlaybackCrossfade(this, handler, AUDIO_ATTRIBUTES, new PlaybackCrossfade.Host() {
            public ExoPlayer current() { return player; }
            public ExoPlayer create() { return createPlayer(false); }
            public void release(ExoPlayer old) { releasePlayer(old); }
            public PlaybackSettings settings() { return options; }
            public boolean blocked() { return destroyed || updatingQueue || reorderingQueue; }
            public ReplayGain tags() { return replayGain.tags(); }
            public float volume() { return replayGain.volume(options.volume(), options.normalizeVolume()); }
            public void handoff(ExoPlayer next, boolean automatic) { handoffPlayer(next, automatic); }
            public void install(List<MediaItem> items, int index, long position, boolean ready) {
                player.setPlayWhenReady(false); player.setMediaItems(items, index, position); player.prepare();
                if (ready) crossfade.playIntent(true);
            }
            public void changed() { updatePrefetch(); if (!destroyed) { publish(); updateWidget(false); scheduleCheckpoint(); } }
            public void outgoingFinished(ExoPlayer old) { finishOutgoingHistory(old); }
        });
        sessionPlayer = new CrossfadePlayer(player, crossfade, volume -> {
            try { setOptions(PlaybackService.this, new JSONObject().put("volume", volume)); }
            catch (org.json.JSONException invalid) { throw new IllegalArgumentException("Invalid volume"); }
        });
        crossfade.facade = sessionPlayer;
        playerListener = new Player.Listener() {
            @Override public void onMediaItemTransition(@Nullable MediaItem item, int reason) { itemChanged(item, reason); }
            @Override public void onIsPlayingChanged(boolean playing) { stateChanged(); }
            @Override public void onIsLoadingChanged(boolean loading) { updatePrefetch(); crossfade.update(); }
            @Override public void onTimelineChanged(androidx.media3.common.Timeline timeline, int reason) { if (!updatingQueue) { updatePrefetch(); crossfade.update(); } }
            @Override public void onPlayWhenReadyChanged(boolean ready, int reason) {
                if (!ready && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) crossfade.noisy();
                stateChanged();
            }
            @Override public void onPositionDiscontinuity(Player.PositionInfo before, Player.PositionInfo after, int reason) {
                if (destroyed || updatingQueue) return;
                if (reason == Player.DISCONTINUITY_REASON_SEEK) crossfade.seekInterrupted();
                if (before.mediaItem != null && after.mediaItem != null
                    && !before.mediaItem.mediaId.equals(after.mediaItem.mediaId) && listening.finish(before.mediaItem.mediaId)) {
                    String oldId = before.mediaItem.mediaId;
                    double progress = currentSong == null || currentSong.optDouble("duration") <= 0 ? 0
                        : Math.min(1, before.positionMs / (1000 * currentSong.optDouble("duration")));
                    String action = reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION ? "complete" : "skip";
                    io.execute(() -> store.event(oldId, action, action.equals("complete") ? 1 : progress));
                }
                savePosition(); publish(); updateWidget(false); crossfade.update();
            }
            @Override public void onPlaybackStateChanged(int state) {
                if (destroyed) return;
                if (state == Player.STATE_READY) persistMetadata();
                if (state == Player.STATE_ENDED && currentId != null && !currentId.equals(completedId) && listening.finish(currentId)) {
                    String id = currentId; completedId = id;
                    io.execute(() -> store.event(id, "complete", 1));
                }
                if (state == Player.STATE_ENDED && options.repeat().equals("all")) extendQueue();
                stateChanged();
            }
            @Override public void onMediaMetadataChanged(MediaMetadata metadata) { persistMetadata(); }
            @Override public void onTracksChanged(androidx.media3.common.Tracks tracks) { persistMetadata(); }
            @Override public void onPlaybackParametersChanged(PlaybackParameters parameters) { crossfade.configure(); publish(); updateWidget(false); }
            @Override public void onPlayerError(PlaybackException error) {
                updatePrefetch(); crossfade.update();
                Log.w("AuroraPlayback", PlaybackErrors.code(error, error.errorCode) + " (Media3 " + error.errorCode + ")");
                fallbackOffline(); scheduleCheckpoint(); updateWidget(false); publish();
            }
        };
        player.addListener(playerListener);
        crossfade.configure();
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class)
            .putExtra("openPlayer", true), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        session = new MediaSession.Builder(this, sessionPlayer).setSessionActivity(open)
            .setCallback(new MediaSession.Callback() {
                @Override public ListenableFuture<MediaSession.MediaItemsWithStartPosition> onPlaybackResumption(
                    MediaSession session, MediaSession.ControllerInfo controller, boolean isForPlayback) {
                    SettableFuture<MediaSession.MediaItemsWithStartPosition> future = SettableFuture.create();
                    long revision = options.revision();
                    io.execute(() -> {
                        try {
                            MediaSession.MediaItemsWithStartPosition saved = savedQueue();
                            handler.post(() -> {
                                if (destroyed || !options.remember() || revision != options.revision())
                                    future.setException(new IllegalStateException("Resume preference changed"));
                                else future.set(saved);
                            });
                        }
                        catch (Exception error) { future.setException(error); }
                    });
                    return future;
                }
            }).build();
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF); filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        restoreQueue();
    }
    private ExoPlayer createPlayer(boolean focus) {
        CancellationGroup cancellations = new CancellationGroup();
        ExoPlayer created = new ExoPlayer.Builder(this)
            .setMediaSourceFactory(new DefaultMediaSourceFactory(StreamCache.factory(this, cancellations)))
            .setLoadControl(new DefaultLoadControl.Builder().setTargetBufferBytes(8 * 1024 * 1024)
                .setBufferDurationsMs(15000, 45000, 1500, 2500)
                .setPrioritizeTimeOverSizeThresholds(false).build())
            .build();
        created.setAudioAttributes(AUDIO_ATTRIBUTES, focus);
        // A music container may also carry video; never allocate a video decoder for it.
        created.setTrackSelectionParameters(created.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build());
        created.setHandleAudioBecomingNoisy(true); created.setWakeMode(C.WAKE_MODE_NETWORK);
        created.setPlaybackSpeed(options.speed()); created.setVolume(options.volume());
        created.setRepeatMode(options.repeat().equals("one") ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
        transfers.put(created, cancellations);
        return created;
    }
    private void releasePlayer(ExoPlayer old) {
        CancellationGroup cancellations = transfers.remove(old);
        if (cancellations != null) cancellations.cancel();
        old.release();
    }
    private void finishOutgoingHistory(ExoPlayer old) {
        MediaItem item = old.getCurrentMediaItem();
        if (item == null || !item.mediaId.equals(fadingCompletion)) return;
        String id = fadingCompletion; fadingCompletion = null;
        long duration = old.getDuration(), position = old.getCurrentPosition();
        boolean complete = CrossfadePolicy.finished(old.getPlaybackState() == Player.STATE_ENDED, duration, position);
        double progress = duration > 0 ? Math.max(0, Math.min(1, position / (double) duration)) : 0;
        io.execute(() -> store.event(id, complete ? "complete" : "skip", complete ? 1 : progress));
    }
    private void handoffPlayer(ExoPlayer next, boolean automatic) {
        ExoPlayer old = player;
        if (automatic && listening.finish(currentId)) fadingCompletion = currentId;
        savePosition(); old.removeListener(playerListener);
        int repeat = old.getRepeatMode(), index = old.getCurrentMediaItemIndex();
        old.removeMediaItems(index + 1, old.getMediaItemCount());
        if (index > 0) old.removeMediaItems(0, index);
        old.setRepeatMode(Player.REPEAT_MODE_OFF);
        player = next; next.setRepeatMode(repeat); next.addListener(playerListener);
        itemChanged(next.getCurrentMediaItem(), automatic ? Player.MEDIA_ITEM_TRANSITION_REASON_AUTO : Player.MEDIA_ITEM_TRANSITION_REASON_SEEK);
        persistMetadata(); stateChanged();
    }
    private void itemChanged(@Nullable MediaItem item, int reason) {
        if (destroyed) return;
        String id = item == null ? null : item.mediaId;
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT && listening.finish(currentId)) {
            String repeated = currentId;
            io.execute(() -> store.event(repeated, "complete", 1)); sessionStarted = false; completedId = null;
        }
        if (!java.util.Objects.equals(id, currentId)) {
            if (listening.finish(currentId)) {
                String oldId = currentId;
                boolean completed = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT;
                double progress = currentSong == null || currentSong.optDouble("duration") <= 0 ? 0
                    : Math.min(1, lastKnownPosition / (1000 * currentSong.optDouble("duration")));
                io.execute(() -> store.event(oldId, completed ? "complete" : "skip", completed ? 1 : progress));
            }
            currentId = id; currentSong = null; lyrics = LyricTimeline.parse(null);
            replayGain.select(id); applyVolume();
            lastMetadata = ""; completedId = null; sessionStarted = false; lastKnownPosition = 0;
            if (!updatingQueue) queueGeneration++;
            if (id != null) reloadSong(id);
        }
        if (!updatingQueue) { savePosition(); extendQueue(); }
        stateChanged();
    }
    private void stateChanged() {
        if (destroyed) return;
        checkSleep();
        boolean ready = crossfade.readyIntent();
        if (!updatingQueue && lastPlayIntent && !ready && sessionStarted && currentId != null) {
            String id = currentId;
            long duration = player.getDuration();
            double progress = duration > 0 ? Math.min(1, Math.max(0, player.getCurrentPosition()) / (double) duration) : 0;
            io.execute(() -> store.event(id, "pause", progress));
        }
        lastPlayIntent = ready;
        if (!updatingQueue && player.isPlaying() && currentId != null && !sessionStarted) {
            sessionStarted = true; String id = currentId;
            if (listening.start(id)) io.execute(() -> store.event(id, "start", 0));
        }
        savePosition(); scheduleCheckpoint(); publish(); updateWidget(false); updatePrefetch(); crossfade.update();
    }
    private void updatePrefetch() {
        if (!destroyed && prefetch != null) prefetch.update(player, options, updatingQueue || reorderingQueue || crossfade != null && crossfade.busy());
    }
    private void scheduleCheckpoint() {
        handler.removeCallbacks(checkpoint);
        if (!destroyed && player.getPlayerError() == null && player.getPlayWhenReady() && player.getPlaybackState() != Player.STATE_ENDED)
            handler.postDelayed(checkpoint, 30000);
    }
    private void publish() { Runnable listener = observer.get(); if (!destroyed && listener != null) listener.run(); }
    private void savePosition() {
        if (player == null || currentId == null || updatingQueue || destroyed) return;
        String id = currentId; long position = Math.max(0, player.getCurrentPosition());
        lastKnownPosition = position;
        if (options.remember()) { long revision = options.revision(); io.execute(() -> store.savePlayback(id, position, revision)); }
    }
    private MediaSession.MediaItemsWithStartPosition savedQueue() throws Exception {
        String[] saved = store.savedPlayback();
        if (saved == null) throw new IllegalStateException("No saved queue");
        long index = store.queueIndex(saved[0]);
        if (index < 1) throw new IllegalStateException("Saved song no longer exists");
        List<MediaItem> items = store.queueWindow(Math.max(1, index - 10), 40);
        for (int i = 0; i < items.size(); i++) if (items.get(i).mediaId.equals(saved[0]))
            return new MediaSession.MediaItemsWithStartPosition(items, i, Long.parseLong(saved[1]));
        throw new IllegalStateException("No playable saved song");
    }
    private void restoreQueue() {
        long revision = options.revision();
        io.execute(() -> {
            try {
                MediaSession.MediaItemsWithStartPosition saved = savedQueue();
                handler.post(() -> {
                    if (destroyed || !options.remember() || revision != options.revision() || player.getMediaItemCount() != 0 || queueGeneration != 0) return;
                    updatingQueue = true;
                    try { player.setMediaItems(saved.mediaItems, saved.startIndex, saved.startPositionMs); }
                    finally { updatingQueue = false; }
                    // Opening the app must not start audio unexpectedly.
                    extendQueue(); publish();
                });
            } catch (Exception error) { Log.d("AuroraPlayback", "No restorable queue", error); }
        });
    }
    private void extendQueue() {
        if (player.getMediaItemCount() == 0 || destroyed || updatingQueue || reorderingQueue || crossfade.manualPending()) return;
        int index = player.getCurrentMediaItemIndex();
        boolean before = index < 3, after = index >= player.getMediaItemCount() - 5;
        if (!before && !after && index <= 10 && !options.repeat().equals("all")) return;
        int generation = queueGeneration;
        String head = player.getMediaItemAt(0).mediaId;
        String tail = player.getMediaItemAt(player.getMediaItemCount() - 1).mediaId;
        io.execute(() -> {
            try {
                long first = store.queueIndex(head), last = store.queueIndex(tail);
                long count = store.queueSize();
                boolean repeatAll = options.repeat().equals("all");
                boolean smallLoop = repeatAll && count <= 40;
                List<MediaItem> full = smallLoop ? store.queueWindow(1, 40) : java.util.Collections.emptyList();
                List<MediaItem> prepend = !smallLoop && before && first > 1
                    ? store.queueWindow(Math.max(1, first - 10), (int) Math.min(10, first - 1))
                    : !smallLoop && before && first == 1 && repeatAll ? store.queueWindow(Math.max(1, count - 9), 10) : java.util.Collections.emptyList();
                List<MediaItem> append = !smallLoop && after && last > 0 ? store.queueWindow(last + 1, 20, repeatAll) : java.util.Collections.emptyList();
                handler.post(() -> {
                    if (destroyed || generation != queueGeneration || player.getMediaItemCount() == 0
                        || !head.equals(player.getMediaItemAt(0).mediaId)
                        || !tail.equals(player.getMediaItemAt(player.getMediaItemCount() - 1).mediaId)) return;
                    updatingQueue = true;
                    try {
                        player.setRepeatMode(options.repeat().equals("one") ? Player.REPEAT_MODE_ONE
                            : smallLoop ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF);
                        if (smallLoop) {
                            if (player.getMediaItemCount() != full.size() || !full.isEmpty() && !head.equals(full.get(0).mediaId)) installWindow(full);
                            return;
                        }
                        boolean ended = player.getPlaybackState() == Player.STATE_ENDED;
                        if (!prepend.isEmpty()) player.addMediaItems(0, prepend);
                        if (!append.isEmpty()) player.addMediaItems(append);
                        int keepBefore = player.getCurrentMediaItemIndex() - 10;
                        if (keepBefore > 0) player.removeMediaItems(0, keepBefore);
                        int keepEnd = player.getCurrentMediaItemIndex() + 31;
                        if (player.getMediaItemCount() > keepEnd) player.removeMediaItems(keepEnd, player.getMediaItemCount());
                        if (ended && !append.isEmpty() && player.getPlayWhenReady() && player.hasNextMediaItem()) {
                            player.seekToNextMediaItem(); player.prepare();
                        }
                    } finally { updatingQueue = false; updatePrefetch(); crossfade.update(); }
                });
            } catch (Exception error) { Log.e("AuroraPlayback", "Queue extension failed", error); }
        });
    }
    /** Retain the currently playing MediaItem: changing order must not seek, restart or fetch the song again. */
    private void installWindow(List<MediaItem> items) {
        String id = player.getCurrentMediaItem() == null ? "" : player.getCurrentMediaItem().mediaId;
        int target = -1;
        for (int i = 0; i < items.size(); i++) if (items.get(i).mediaId.equals(id)) { target = i; break; }
        if (target < 0) return;
        int current = player.getCurrentMediaItemIndex();
        player.removeMediaItems(current + 1, player.getMediaItemCount());
        if (current > 0) player.removeMediaItems(0, current);
        if (target > 0) player.addMediaItems(0, items.subList(0, target));
        if (target + 1 < items.size()) player.addMediaItems(items.subList(target + 1, items.size()));
    }
    private void refreshQueue(boolean shuffleChanged) {
        if (destroyed || currentId == null) return;
        reorderingQueue = true;
        int order = ++orderGeneration, generation = ++queueGeneration;
        String id = currentId;
        io.execute(() -> {
            try {
                if (shuffleChanged) store.orderQueue(options.shuffle(), id);
                long count = store.queueSize(), index = store.queueIndex(id);
                boolean smallLoop = options.repeat().equals("all") && count <= 40;
                List<MediaItem> items = store.queueWindow(smallLoop ? 1 : Math.max(1, index - 10), 40);
                handler.post(() -> {
                    if (destroyed || order != orderGeneration) return;
                    reorderingQueue = false;
                    if (generation != queueGeneration || !id.equals(currentId)) { refreshQueue(false); return; }
                    updatingQueue = true;
                    try {
                        player.setRepeatMode(options.repeat().equals("one") ? Player.REPEAT_MODE_ONE
                            : smallLoop ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF);
                        installWindow(items);
                    } finally { updatingQueue = false; updatePrefetch(); crossfade.update(); }
                    extendQueue(); publish();
                });
            } catch (Exception error) {
                Log.w("AuroraPlayback", "Queue order could not be updated", error);
                handler.post(() -> { if (order == orderGeneration) reorderingQueue = false; });
            }
        });
    }
    private void persistMetadata() {
        if (destroyed || currentId == null || player == null) return;
        MediaItem item = player.getCurrentMediaItem();
        if (item == null || !currentId.equals(item.mediaId)) return;
        String id = currentId;
        MediaMetadata metadata = player.getMediaMetadata();
        // Selected input format belongs to the current item; decoder output may still describe the previous item.
        Format format = selectedAudioFormat();
        ReplayGain tags = format == null ? ReplayGain.NONE : ReplayGainTags.read(format.metadata);
        if (format != null) { replayGain.source(id, tags); applyVolume(); }
        long duration = Math.max(0, player.getDuration());
        String signature = id + "|" + metadata.title + "|" + metadata.artist + "|" + metadata.albumTitle
            + "|" + duration + "|" + (format == null ? "" : format.sampleMimeType + ":" + format.sampleRate + ":" + format.channelCount)
            + "|" + (metadata.artworkData == null ? 0 : metadata.artworkData.length) + "|" + tags.gainDb + "|" + tags.peak;
        if (signature.equals(lastMetadata)) return;
        lastMetadata = signature;
        io.execute(() -> {
            try {
                String cover = metadata.artworkData == null ? null : ArtworkStore.save(this, id, metadata.artworkData);
                store.metadata(id, metadata, duration, format, cover);
                reloadSong(id);
            } catch (Exception error) { Log.w("AuroraPlayback", "Metadata could not be stored", error); }
        });
    }
    private void reloadSong(String id) {
        if (destroyed) return;
        io.execute(() -> {
            try {
                JSONObject song = store.song(id);
                LyricTimeline timeline = LyricTimeline.parse(song == null ? null : song.optString("customLyrics", ""));
                handler.post(() -> {
                    if (destroyed || !id.equals(currentId)) return;
                    replayGain.cached(id, song == null ? ReplayGain.NONE : new ReplayGain(song.optDouble("replayGainDb", Double.NaN), song.optDouble("replayPeak", Double.NaN)));
                    applyVolume(); currentSong = song; lyrics = timeline; metadataRevision++; publish(); updateWidget(true);
                });
            } catch (Exception error) { Log.w("AuroraPlayback", "Song metadata unavailable", error); }
        });
    }
    private void fallbackOffline() {
        if (destroyed || currentId == null) return;
        String id = currentId;
        io.execute(() -> {
            try {
                JSONObject song = store.song(id);
                handler.post(() -> {
                    if (destroyed || !id.equals(currentId) || song == null || !song.optBoolean("offline")) return;
                    MediaItem item = player.getCurrentMediaItem();
                    if (item == null || item.localConfiguration == null || "file".equals(item.localConfiguration.uri.getScheme())) return;
                    long position = player.getCurrentPosition();
                    player.replaceMediaItem(player.getCurrentMediaItemIndex(), item.buildUpon()
                        .setUri(Uri.fromFile(new File(song.optString("localCachePath")))).build());
                    player.seekTo(position); player.prepare();
                });
            } catch (Exception error) { Log.e("AuroraPlayback", "Offline fallback failed", error); }
        });
    }
    private Format selectedAudioFormat() {
        for (androidx.media3.common.Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != C.TRACK_TYPE_AUDIO) continue;
            for (int index = 0; index < group.length; index++) if (group.isTrackSelected(index)) return group.getTrackFormat(index);
        }
        return null;
    }
    private void applyVolume() {
        if (crossfade != null) crossfade.applyVolume();
        else { float volume = replayGain.volume(options.volume(), options.normalizeVolume()); if (player.getVolume() != volume) player.setVolume(volume); }
    }
    private void updateWidget(boolean force) {
        handler.removeCallbacks(lyricTick);
        if (destroyed || player == null) return;
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        boolean interactive = power != null && power.isInteractive();
        long position = Math.max(0, player.getCurrentPosition());
        String title = currentSong == null ? "" : currentSong.optString("title");
        String artist = currentSong == null ? "" : currentSong.optString("artist");
        String cover = currentSong == null ? "" : currentSong.optString("coverPath", "");
        WidgetSnapshot snapshot = new WidgetSnapshot(currentId, title, artist, cover,
            lyrics.current(position), lyrics.next(position), player.getPlayerError() == null && crossfade.readyIntent());
        String signature = currentId + "|" + title + "|" + artist + "|" + cover + "|" + snapshot.lyric + "|" + snapshot.playing + "|" + metadataRevision;
        if (force || !signature.equals(lastWidget)) {
            lastWidget = signature; snapshot.save(this);
            if (interactive && MusicWidgetProvider.hasWidgets(this)) MusicWidgetProvider.refresh(this);
        }
        if (interactive && player.isPlaying() && MusicWidgetProvider.hasWidgets(this)) {
            long delay = lyrics.delayUntilNext(position);
            if (delay >= 0) handler.postDelayed(lyricTick, Math.max(100, (long) (delay / player.getPlaybackParameters().speed)));
        }
    }
    @Nullable @Override public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) { return session; }
    @Override public void onTaskRemoved(Intent intent) { if (!isPlaybackOngoing()) stopSelf(); }
    @Override public void onDestroy() {
        savePosition(); destroyed = true;
        if (prefetch != null) prefetch.close();
        if (crossfade != null) crossfade.close();
        handler.removeCallbacksAndMessages(null);
        try { unregisterReceiver(screenReceiver); } catch (IllegalArgumentException ignored) {}
        if (session != null) session.release();
        if (player != null) releasePlayer(player);
        io.execute(() -> store.close()); io.shutdown();
        WidgetSnapshot old = WidgetSnapshot.load(this);
        new WidgetSnapshot(old.songId, old.title, old.artist, old.cover, old.lyric, old.nextLyric, false).save(this);
        MusicWidgetProvider.refresh(this);
        if (active == this) active = null;
        super.onDestroy();
    }
}
