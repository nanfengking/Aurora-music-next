package com.aurora.musicnext;

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
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real native players with generated silent WAV bytes. No server, user DB or production preferences. */
@RunWith(AndroidJUnit4.class)
@OptIn(markerClass = UnstableApi.class)
public class CrossfadeInstrumentedTest {
    private Harness host;
    private final List<MediaItem> items = Arrays.asList(item("a"), item("b"), item("c"));
    private static MediaItem item(String id) { return new MediaItem.Builder().setMediaId(id).setUri("https://audio.example.test/" + id + ".wav").setMimeType(MimeTypes.AUDIO_WAV).build(); }
    private void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private void await(BooleanSupplier check) throws Exception {
        long end = android.os.SystemClock.elapsedRealtime() + 5000;
        while (android.os.SystemClock.elapsedRealtime() < end) {
            AtomicReference<Boolean> value = new AtomicReference<>(); main(() -> value.set(check.getAsBoolean()));
            if (value.get()) return;
            Thread.sleep(20);
        }
        fail("Native transition fixture timed out");
    }
    @Before public void prepare() throws Exception {
        main(() -> {
            host = new Harness(); host.player.setMediaItems(items); host.player.prepare(); host.player.play();
        });
        await(() -> host.player.isPlaying());
    }
    @After public void close() {
        if (host != null) main(() -> { host.fade.close(); host.release(host.player); host.context.deleteSharedPreferences(host.profile); });
    }
    @Test public void disabledDoesNotCreateSecondPlayer() {
        main(() -> { host.setSeconds(0); assertNull(host.fade.manual(items, 1, 0)); assertEquals(1, host.live.size()); assertFalse(host.fade.busy()); });
    }
    @Test public void readyManualHandoffRetainsOnlyOneOutgoingItem() throws Exception {
        AtomicReference<ListenableFuture<?>> completion = new AtomicReference<>();
        main(() -> completion.set(host.fade.manual(items, 1, 0)));
        assertNotNull(completion.get()); await(() -> completion.get().isDone());
        main(() -> {
            assertEquals("b", host.player.getCurrentMediaItem().mediaId); assertEquals(2, host.live.size());
            assertEquals(1, host.retiring.getMediaItemCount()); assertEquals(Player.REPEAT_MODE_OFF, host.retiring.getRepeatMode());
            assertEquals("b", host.facade.getCurrentMediaItem().mediaId);
            host.fade.seekInterrupted(); assertEquals(1, host.live.size()); assertTrue(host.player.isPlaying());
        });
    }
    @Test public void pausePreservesRequestedTrackButCancelsBlockedPreparation() throws Exception {
        AtomicReference<ListenableFuture<?>> completion = new AtomicReference<>();
        main(() -> { host.holdNext = true; completion.set(host.fade.manual(items, 1, 0)); });
        await(() -> host.blockedOpen);
        main(() -> { host.fade.playIntent(false); assertTrue(completion.get().isDone()); assertEquals("b", host.player.getCurrentMediaItem().mediaId); assertFalse(host.player.getPlayWhenReady()); assertEquals(1, host.live.size()); });
    }
    @Test public void rapidSelectionsCancelOldPreparationWithoutThirdLivePlayer() throws Exception {
        AtomicReference<ListenableFuture<?>> first = new AtomicReference<>(), latest = new AtomicReference<>();
        main(() -> { host.holdNext = true; first.set(host.fade.manual(items, 1, 0)); });
        await(() -> host.blockedOpen);
        main(() -> { host.holdNext = false; latest.set(host.fade.manual(items, 2, 0)); assertTrue(first.get().isDone()); });
        await(() -> latest.get().isDone());
        main(() -> { assertEquals("c", host.player.getCurrentMediaItem().mediaId); assertTrue(host.maximumLive <= 2); });
    }
    @Test public void sessionFacadeNextUsesTheSameTransitionPath() throws Exception {
        main(() -> host.facade.seekToNextMediaItem());
        await(() -> "b".equals(host.player.getCurrentMediaItem().mediaId));
        main(() -> { assertEquals(2, host.live.size()); assertEquals("b", host.facade.getCurrentMediaItem().mediaId); host.facade.seekToNextMediaItem(); });
        await(() -> "c".equals(host.player.getCurrentMediaItem().mediaId));
        main(() -> { assertTrue(host.maximumLive <= 2); host.facade.pause(); assertFalse(host.player.getPlayWhenReady()); assertEquals(1, host.live.size()); });
    }
    private static byte[] wave() {
        int length = 8000 * 2 * 12;
        ByteBuffer out = ByteBuffer.allocate(44 + length).order(ByteOrder.LITTLE_ENDIAN);
        out.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + length).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        out.putInt(16).putShort((short) 1).putShort((short) 1).putInt(8000).putInt(16000).putShort((short) 2).putShort((short) 16);
        out.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(length); return out.array();
    }
    private final class Harness implements PlaybackCrossfade.Host {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final String profile = "crossfade-fixture-" + UUID.randomUUID();
        final PlaybackSettings settings = new PlaybackSettings(context, profile);
        final Map<ExoPlayer, CancellationGroup> live = new IdentityHashMap<>();
        ExoPlayer player, retiring;
        PlaybackCrossfade fade;
        CrossfadePlayer facade;
        boolean holdNext;
        volatile boolean blockedOpen;
        int maximumLive;
        Harness() {
            setSeconds(3); player = create();
            fade = new PlaybackCrossfade(context, new Handler(Looper.getMainLooper()), new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).build(), this);
            facade = new CrossfadePlayer(player, fade, ignored -> {}); fade.facade = facade;
            // OS focus is intentionally not requested in these transport/lifetime fixtures.
        }
        void setSeconds(int value) { try { settings.save(new org.json.JSONObject().put("crossfadeSeconds", value).put("volume", 0)); } catch (Exception error) { throw new AssertionError(error); } }
        public ExoPlayer current() { return player; }
        public ExoPlayer create() {
            CancellationGroup cancellations = new CancellationGroup(); boolean hold = holdNext;
            ExoPlayer value = new ExoPlayer.Builder(context).setMediaSourceFactory(new DefaultMediaSourceFactory((DataSource.Factory) () -> {
                ByteArrayDataSource source = new ByteArrayDataSource(wave());
                return new DataSource() {
                    CancellationGroup.Registration registration;
                    final CountDownLatch gate = new CountDownLatch(hold ? 1 : 0);
                    volatile boolean cancelled;
                    public long open(DataSpec spec) throws IOException {
                        registration = cancellations.bind(() -> { cancelled = true; gate.countDown(); });
                        if (hold) blockedOpen = true;
                        try { if (!gate.await(3, TimeUnit.SECONDS) || cancelled) throw new IOException("AUDIO_CANCELLED"); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("AUDIO_CANCELLED"); }
                        return source.open(spec);
                    }
                    public int read(byte[] buffer, int offset, int length) throws IOException { return source.read(buffer, offset, length); }
                    public android.net.Uri getUri() { return source.getUri(); }
                    public void addTransferListener(TransferListener listener) { source.addTransferListener(listener); }
                    public void close() { if (registration != null) registration.close(); source.close(); }
                };
            })).build();
            value.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).build(), false); value.setVolume(0);
            live.put(value, cancellations); maximumLive = Math.max(maximumLive, live.size()); return value;
        }
        public void release(ExoPlayer old) { CancellationGroup cancellations = live.remove(old); if (cancellations != null) { cancellations.cancel(); old.release(); } }
        public PlaybackSettings settings() { return settings; }
        public boolean blocked() { return false; }
        public ReplayGain tags() { return ReplayGain.NONE; }
        public float volume() { return 0; }
        public void handoff(ExoPlayer next, boolean automatic) {
            retiring = player; int index = retiring.getCurrentMediaItemIndex();
            retiring.removeMediaItems(index + 1, retiring.getMediaItemCount()); if (index > 0) retiring.removeMediaItems(0, index);
            retiring.setRepeatMode(Player.REPEAT_MODE_OFF); player = next;
        }
        public void install(List<MediaItem> songs, int index, long position, boolean ready) { player.setPlayWhenReady(false); player.setMediaItems(songs, index, position); player.prepare(); player.setPlayWhenReady(ready); }
        public void changed() {}
        public void outgoingFinished(ExoPlayer old) {}
    }
}
