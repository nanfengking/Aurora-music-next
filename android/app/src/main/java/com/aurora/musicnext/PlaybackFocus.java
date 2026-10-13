package com.aurora.musicnext;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.Player;
import androidx.media3.common.audio.AudioFocusManager;
import androidx.media3.common.util.UnstableApi;

/** One focus owner for both fade streams. Platform calls stay off the application thread. */
@OptIn(markerClass = UnstableApi.class)
final class PlaybackFocus implements AutoCloseable {
    interface Control { void changed(); }
    private final Context context;
    private final Handler main;
    private final AudioAttributes attributes;
    private final Control control;
    final CrossfadePolicy.FocusIntent intent = new CrossfadePolicy.FocusIntent();
    private HandlerThread thread;
    private Handler worker;
    private AudioFocusManager manager;
    private boolean closed;
    private long revision;
    private int lastState = -1;
    private boolean lastIntent;
    private boolean granted;
    private boolean pendingRequest, transientLoss;
    float multiplier = 1;
    PlaybackFocus(Context context, Handler main, AudioAttributes attributes, Control control) {
        this.context = context.getApplicationContext(); this.main = main; this.attributes = attributes; this.control = control;
    }
    void request(boolean ready, int state) {
        if (closed) return;
        intent.request(ready);
        pendingRequest = false; transientLoss = false;
        if (ready) { intent.waiting = false; if (!granted) lastState = -1; }
        if (!ready) control.changed();
        sync(state);
    }
    void sync(int state) {
        if (closed) return;
        if (state == Player.STATE_IDLE) {
            if (lastState == state && lastIntent == intent.ready) return;
            lastState = state; lastIntent = intent.ready; revision++;
            granted = false; pendingRequest = false; transientLoss = false; intent.waiting = intent.ready;
            if (worker != null) worker.post(() -> { if (manager != null) manager.updateAudioFocus(false, Player.STATE_IDLE); });
            return;
        }
        if (intent.waiting && intent.ready && (pendingRequest || transientLoss)) return;
        if (lastState == state && lastIntent == intent.ready) return;
        lastState = state; lastIntent = intent.ready;
        if (worker == null && !intent.ready) return;
        if (worker == null) {
            thread = new HandlerThread("AuroraAudioFocus", Process.THREAD_PRIORITY_BACKGROUND); thread.start();
            worker = new Handler(thread.getLooper());
            worker.post(() -> {
                manager = new AudioFocusManager(context, thread.getLooper(), new AudioFocusManager.PlayerControl() {
                    @Override public void setVolumeMultiplier(float value) { main.post(() -> { if (!closed) { multiplier = value; control.changed(); } }); }
                    @Override public void executePlayerCommand(int command) { main.post(() -> accept(command)); }
                });
                manager.setAudioAttributes(attributes);
            });
        }
        long version = ++revision;
        pendingRequest = true;
        boolean desired = intent.ready;
        worker.post(() -> {
            int command;
            try { command = manager.updateAudioFocus(desired, state); }
            catch (RuntimeException unavailable) { command = AudioFocusManager.PLAYER_COMMAND_DO_NOT_PLAY; }
            final int result = command;
            main.post(() -> { if (!closed && version == revision) accept(result); });
        });
        if (intent.ready && !granted) intent.waiting = true;
    }
    private void accept(int command) {
        if (closed || lastState == Player.STATE_IDLE) return;
        pendingRequest = false; transientLoss = command == AudioFocusManager.PLAYER_COMMAND_WAIT_FOR_CALLBACK;
        granted = command > 0;
        intent.command(command); control.changed();
    }
    @Override public void close() {
        if (closed) return;
        closed = true; revision++;
        if (worker != null) worker.post(() -> { if (manager != null) manager.release(); thread.quitSafely(); });
    }
}
