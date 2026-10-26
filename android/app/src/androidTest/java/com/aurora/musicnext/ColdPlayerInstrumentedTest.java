package com.aurora.musicnext;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.ByteArrayDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.session.MediaController;
import androidx.media3.session.MediaSession;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real Media3 session/player with generated silent bytes and a private settings DB; no Activity. */
@RunWith(AndroidJUnit4.class)
@OptIn(markerClass = UnstableApi.class)
public final class ColdPlayerInstrumentedTest {
    private Harness host;
    @Before public void prepare() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String database = "aurora-cold-player-" + java.util.UUID.randomUUID();
        PlaybackSettings settings = new PlaybackSettings(context, database);
        settings.repository.ready().get(6, TimeUnit.SECONDS);
        settings.save(new JSONObject().put("volume", .2)).get(6, TimeUnit.SECONDS);
        main(() -> host = new Harness(context, database, settings));
    }
    @After public void close() {
        if (host == null) return;
        main(() -> {
            host.gate.close(new IllegalStateException("fixture destroyed"));
            if (host.controller != null) host.controller.release();
            if (host.connection != null) MediaController.releaseFuture(host.connection);
            if (host.session != null) host.session.release();
            host.fade.close(); host.player.release();
        });
        host.context.deleteDatabase(host.database);
        for (String profile : new String[]{"playback-options", "appearance", "connection"}) host.context.deleteSharedPreferences(profile + "-" + host.database);
    }
    @Test public void coldCommandsAndOptimisticPlaceholdersRemainInert() throws Exception {
        main(() -> {
            host.facade.setMediaItems(items()); host.facade.prepare(); host.facade.play();
            assertEquals(0, host.facade.getMediaItemCount()); assertEquals(0, host.player.getMediaItemCount());
            assertFalse(host.facade.getPlayWhenReady()); assertFalse(host.player.getPlayWhenReady());
            assertEquals(0f, host.facade.getVolume(), 0); assertEquals(0f, host.player.getVolume(), 0);
        });
        host.ready.set(null);
        await(() -> host.facade.getMediaItemCount() == 2 && host.player.isPlaying());
        main(() -> { assertEquals(.2f, host.player.getVolume(), 0); assertEquals(1, host.applied); });
    }
    @Test public void delayedVolumeDoesNotPublishAnUncommittedPlaceholder() throws Exception {
        host.ready.set(null); await(() -> host.gate.isApplied());
        main(() -> {
            host.volume = SettableFuture.create(); host.facade.setVolume(.8f);
            assertEquals(.2f, host.facade.getVolume(), 0); assertEquals(.2f, host.player.getVolume(), 0);
        });
        main(() -> { host.player.setVolume(.8f); host.volume.set(null); });
        await(() -> host.facade.getVolume() == .8f);
    }
    @Test public void failedVolumeFutureIsNotImmediateSuccess() throws Exception {
        host.ready.set(null); await(() -> host.gate.isApplied());
        AtomicReference<ListenableFuture<?>> result = new AtomicReference<>();
        main(() -> { host.volume = SettableFuture.create(); result.set(host.facade.handleSetVolume(.8f, C.VOLUME_OPERATION_TYPE_SET_VOLUME)); });
        assertFalse(result.get().isDone()); IllegalStateException failed = new IllegalStateException("fixture save failed");
        host.volume.setException(failed);
        try { result.get().get(3, TimeUnit.SECONDS); fail("Volume must expose save failure"); }
        catch (ExecutionException error) { assertSame(failed, error.getCause()); }
        main(() -> assertEquals(.2f, host.player.getVolume(), 0));
    }
    @Test public void closeRejectsColdPlayEvenIfHydrationFinishesLater() throws Exception {
        AtomicReference<ListenableFuture<?>> result = new AtomicReference<>();
        main(() -> {
            result.set(host.facade.handleSetPlayWhenReady(true));
            host.gate.close(new IllegalStateException("fixture closed"));
        });
        try { result.get().get(3, TimeUnit.SECONDS); fail("Closed play must fail"); } catch (ExecutionException expected) {}
        host.ready.set(null); main(() -> {
            assertFalse(host.player.getPlayWhenReady()); assertFalse(host.facade.getPlayWhenReady());
            assertEquals(0, host.facade.getMediaItemCount()); assertEquals(0f, host.facade.getVolume(), 0);
        });
    }
    @Test public void realControllerConnectsAndPlaysWithoutAnActivityAfterHydration() throws Exception {
        main(() -> {
            host.session = new MediaSession.Builder(host.context, host.facade).setCallback(new MediaSession.Callback() {
                @Override public ListenableFuture<MediaSession.ConnectionResult> onConnectAsync(MediaSession session, MediaSession.ControllerInfo info) {
                    return host.gate.connection(new MediaSession.ConnectionResult.AcceptedResultBuilder(session, info).build());
                }
            }).build();
            host.connection = new MediaController.Builder(host.context, host.session.getToken()).buildAsync();
        });
        assertFalse(host.connection.isDone()); host.ready.set(null);
        host.controller = host.connection.get(6, TimeUnit.SECONDS);
        main(() -> { host.controller.setMediaItems(items()); host.controller.prepare(); host.controller.play(); });
        await(() -> host.player.isPlaying() && host.player.getCurrentPosition() > 0);
    }
    private static List<MediaItem> items() {
        return Arrays.asList(new MediaItem.Builder().setMediaId("a").setUri("https://audio.example.test/a.wav").setMimeType(MimeTypes.AUDIO_WAV).build(),
            new MediaItem.Builder().setMediaId("b").setUri("https://audio.example.test/b.wav").setMimeType(MimeTypes.AUDIO_WAV).build());
    }
    private static void main(Runnable work) { InstrumentationRegistry.getInstrumentation().runOnMainSync(work); }
    private static void await(BooleanSupplier check) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 6000;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            AtomicReference<Boolean> result = new AtomicReference<>(); main(() -> result.set(check.getAsBoolean()));
            if (result.get()) return; Thread.sleep(20);
        }
        fail("Native cold player fixture timed out");
    }
    private static byte[] wave() {
        int length = 8000 * 2 * 20; ByteBuffer out = ByteBuffer.allocate(length + 44).order(ByteOrder.LITTLE_ENDIAN);
        out.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(length + 36).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        out.putInt(16).putShort((short) 1).putShort((short) 1).putInt(8000).putInt(16000).putShort((short) 2).putShort((short) 16);
        out.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(length); return out.array();
    }
    private static final class Harness implements PlaybackCrossfade.Host {
        final Context context; final String database; final PlaybackSettings settings;
        final ExoPlayer player; final PlaybackCrossfade fade; final CrossfadePlayer facade;
        final SettableFuture<Void> ready = SettableFuture.create(); final PlaybackReadiness gate;
        SettableFuture<Void> volume; int applied; MediaSession session; ListenableFuture<MediaController> connection; MediaController controller;
        Harness(Context context, String database, PlaybackSettings settings) {
            this.context = context; this.database = database; this.settings = settings;
            player = new ExoPlayer.Builder(context).setMediaSourceFactory(new DefaultMediaSourceFactory((DataSource.Factory) () -> new ByteArrayDataSource(wave()))).build();
            player.setVolume(0); player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).build(), false);
            Handler handler = new Handler(Looper.getMainLooper());
            fade = new PlaybackCrossfade(context, handler, new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).build(), this);
            gate = new PlaybackReadiness(ready, handler::post, () -> { player.setVolume(.2f); fade.configure(); applied++; });
            facade = new CrossfadePlayer(player, fade, value -> volume == null ? Futures.immediateVoidFuture() : volume, gate); fade.facade = facade;
        }
        public ExoPlayer current() { return player; } public ExoPlayer create() { throw new AssertionError("Crossfade remains disabled"); }
        public void release(ExoPlayer old) { old.release(); } public PlaybackSettings settings() { return settings; }
        public boolean blocked() { return false; } public ReplayGain tags() { return ReplayGain.NONE; } public float volume() { return settings.volume(); }
        public void handoff(ExoPlayer next, boolean automatic) { throw new AssertionError("No second player"); }
        public void install(List<MediaItem> items, int index, long position, boolean ready) { player.setMediaItems(items, index, position); player.prepare(); player.setPlayWhenReady(ready); }
        public void changed() {} public void outgoingFinished(ExoPlayer old) {}
    }
}
