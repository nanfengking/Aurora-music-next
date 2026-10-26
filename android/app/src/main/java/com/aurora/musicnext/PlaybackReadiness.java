package com.aurora.musicnext;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

/** Service-local lifecycle, not the owner of shared preference initialization or an audio player. */
final class PlaybackReadiness {
    interface Command { ListenableFuture<?> run() throws Exception; }
    private final Object lock = new Object();
    private final Thread owner = Thread.currentThread();
    private final Executor main;
    private final Runnable apply;
    private final SettableFuture<Void> application = SettableFuture.create();
    private final Set<SettableFuture<?>> pending = new HashSet<>();
    private final Map<SettableFuture<?>, Runnable> connections = new LinkedHashMap<>();
    private final ArrayDeque<PendingCommand> cold = new ArrayDeque<>();
    private boolean hydrated;
    private boolean applying;
    private boolean applied;
    private Throwable failure;

    PlaybackReadiness(ListenableFuture<?> readiness, Executor main, Runnable apply) {
        this.main = Objects.requireNonNull(main); this.apply = Objects.requireNonNull(apply);
        // A stalled process-level read must not retain a destroyed service through this listener.
        WeakReference<PlaybackReadiness> weak = new WeakReference<>(this);
        Objects.requireNonNull(readiness).addListener(() -> {
            PlaybackReadiness gate = weak.get();
            if (gate != null) gate.hydrationComplete(readiness);
        }, MoreExecutors.directExecutor());
    }

    /** Worker-completed connection permission: never waits for the application's main looper. */
    <T> ListenableFuture<T> connection(T accepted) {
        SettableFuture<T> result = owned();
        synchronized (lock) {
            if (!result.isDone()) {
                if (hydrated) result.set(accepted);
                else connections.put(result, () -> result.set(accepted));
            }
        }
        return result;
    }

    ListenableFuture<Void> whenApplied() { return Futures.nonCancellationPropagating(application); }

    boolean isApplied() { synchronized (lock) { return applied && failure == null; } }

    /** Main only, including state reads. A reentrant raw-player listener observes the inert shell. */
    boolean ensureAppliedIfHydrated() {
        requireOwner();
        synchronized (lock) {
            if (failure != null || !hydrated || applying || applied) return applied && failure == null;
            applying = true;
        }
        try { apply.run(); }
        catch (Throwable error) { close(error); }
        List<PendingCommand> queued;
        synchronized (lock) {
            applying = false;
            if (failure != null) return false;
            applied = true; queued = new ArrayList<>(cold); cold.clear();
        }
        // Preserve cold arrival order, not completion order: pause/stop must interrupt an async fade.
        for (PendingCommand command : queued) execute(command);
        synchronized (lock) {
            if (failure != null) return false;
            application.set(null);
            return applied && failure == null;
        }
    }

    /** Main only. Returns the actual handler future, scoped to this service's lifetime. */
    ListenableFuture<?> command(Command work) {
        requireOwner(); Objects.requireNonNull(work);
        SettableFuture<Object> result = owned(); PendingCommand command = new PendingCommand(work, result);
        boolean executeNow;
        synchronized (lock) {
            if (result.isDone()) return result;
            executeNow = applied && !applying;
            if (!executeNow) cold.add(command);
        }
        if (executeNow) execute(command);
        else ensureAppliedIfHydrated();
        return result;
    }

    /** Own a resumption/result wait without cancelling its underlying I/O or shared future. */
    <T> ListenableFuture<T> scoped(ListenableFuture<T> work) {
        Objects.requireNonNull(work);
        SettableFuture<T> result = owned();
        if (!result.isDone()) forward(work, result);
        return result;
    }

    /** May run on the hydration worker. Close before removing callbacks or releasing the player. */
    void close(Throwable reason) {
        Objects.requireNonNull(reason); List<SettableFuture<?>> waiting;
        synchronized (lock) {
            if (failure != null) return;
            failure = reason; applied = false;
            waiting = new ArrayList<>(pending); pending.clear(); connections.clear(); cold.clear();
        }
        application.setException(reason);
        for (SettableFuture<?> future : waiting) future.setException(reason);
    }

    private void hydrationComplete(ListenableFuture<?> source) {
        try {
            Futures.getDone(source);
            synchronized (lock) {
                if (failure != null) return;
                hydrated = true;
                List<Runnable> accepted = new ArrayList<>(connections.values()); connections.clear();
                for (Runnable connection : accepted) {
                    if (failure != null) break;
                    connection.run();
                }
            }
            main.execute(() -> {
                try { ensureAppliedIfHydrated(); }
                catch (Throwable error) { close(error); }
            });
        } catch (Throwable error) { close(unwrap(error)); }
    }

    private void execute(PendingCommand command) {
        synchronized (lock) { if (failure != null || command.result.isDone()) return; }
        try { forward(Objects.requireNonNull(command.work.run(), "Missing command completion"), command.result); }
        catch (Throwable error) { command.result.setException(error); }
    }

    private <T> SettableFuture<T> owned() {
        SettableFuture<T> result = SettableFuture.create();
        synchronized (lock) {
            if (failure != null) result.setException(failure);
            else pending.add(result);
        }
        result.addListener(() -> {
            synchronized (lock) {
                pending.remove(result); connections.remove(result);
                cold.removeIf(command -> command.result == result);
            }
        }, MoreExecutors.directExecutor());
        return result;
    }

    private <T> void forward(ListenableFuture<? extends T> source, SettableFuture<T> result) {
        WeakReference<PlaybackReadiness> weak = new WeakReference<>(this);
        source.addListener(() -> {
            PlaybackReadiness gate = weak.get();
            if (gate == null) return;
            synchronized (gate.lock) {
                if (gate.failure != null || result.isDone()) return;
                try { result.set(Futures.getDone(source)); }
                catch (Throwable error) { result.setException(unwrap(error)); }
            }
        }, MoreExecutors.directExecutor());
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Playback command requires owner thread");
    }
    private static Throwable unwrap(Throwable error) {
        return error instanceof ExecutionException && error.getCause() != null ? error.getCause() : error;
    }
    private static final class PendingCommand {
        final Command work;
        final SettableFuture<Object> result;
        PendingCommand(Command work, SettableFuture<Object> result) { this.work = work; this.result = result; }
    }
}
