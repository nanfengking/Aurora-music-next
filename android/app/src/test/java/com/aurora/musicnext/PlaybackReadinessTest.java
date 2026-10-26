package com.aurora.musicnext;

import static org.junit.Assert.*;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Real gate/futures/executors; no Android or Media3 mock can make these lifecycle assertions pass. */
public final class PlaybackReadinessTest {
    private static final Executor DIRECT = MoreExecutors.directExecutor();

    @Test public void constructorAndColdCommandsNeverApplyDefaultsBeforeHydration() throws Exception {
        SettableFuture<Void> source = SettableFuture.create(); QueuedMain main = new QueuedMain();
        AtomicInteger applied = new AtomicInteger(), played = new AtomicInteger();
        PlaybackReadiness gate = new PlaybackReadiness(source, main, applied::incrementAndGet);
        ListenableFuture<?> command = gate.command(() -> { played.incrementAndGet(); return Futures.immediateVoidFuture(); });
        main.drain();
        assertFalse(gate.isApplied()); assertFalse(gate.ensureAppliedIfHydrated());
        assertFalse(command.isDone()); assertEquals(0, applied.get()); assertEquals(0, played.get());
        source.set(null); main.drain();
        command.get(1, TimeUnit.SECONDS);
        assertEquals(1, applied.get()); assertEquals(1, played.get());
    }

    @Test public void connectionCompletesOnWorkerWhileMainQueueIsBlocked() throws Exception {
        SettableFuture<Void> source = SettableFuture.create(); QueuedMain main = new QueuedMain();
        AtomicInteger applied = new AtomicInteger();
        PlaybackReadiness gate = new PlaybackReadiness(source, main, applied::incrementAndGet);
        ListenableFuture<String> connection = gate.connection("accepted with original permissions");
        AtomicReference<Thread> completionThread = new AtomicReference<>();
        connection.addListener(() -> completionThread.set(Thread.currentThread()), DIRECT);
        Thread worker = background(() -> source.set(null));
        assertEquals("accepted with original permissions", connection.get(1, TimeUnit.SECONDS));
        assertSame(worker, completionThread.get()); assertEquals(0, applied.get());
        assertFalse(gate.whenApplied().isDone()); assertFalse(gate.isApplied());
        main.drain(); assertTrue(gate.isApplied()); assertEquals(1, applied.get());
    }

    @Test public void coldCommandsApplyOnceAndReplayInArrivalOrder() throws Exception {
        SettableFuture<Void> source = SettableFuture.create(); QueuedMain main = new QueuedMain();
        List<String> events = new ArrayList<>();
        PlaybackReadiness gate = new PlaybackReadiness(source, main, () -> events.add("settings"));
        ListenableFuture<?> items = gate.command(() -> { events.add("items"); return Futures.immediateVoidFuture(); });
        ListenableFuture<?> play = gate.command(() -> { events.add("play"); return Futures.immediateVoidFuture(); });
        background(() -> source.set(null)); main.drain();
        assertEquals(Arrays.asList("settings", "items", "play"), events);
        assertTrue(items.isDone()); assertTrue(play.isDone()); gate.whenApplied().get(1, TimeUnit.SECONDS);
        assertTrue(gate.ensureAppliedIfHydrated()); main.drain(); assertEquals(3, events.size());
    }

    @Test public void stateReadCanApplyBeforeThePostedInitializerWithoutDoubleApplication() throws Exception {
        SettableFuture<Void> source = SettableFuture.create(); QueuedMain main = new QueuedMain();
        AtomicInteger applied = new AtomicInteger();
        PlaybackReadiness gate = new PlaybackReadiness(source, main, applied::incrementAndGet);
        background(() -> source.set(null));
        assertTrue(gate.ensureAppliedIfHydrated()); assertEquals(1, applied.get());
        main.drain(); assertEquals(1, applied.get());
    }

    @Test public void reentrantCommandsDuringApplicationWaitUntilSettingsAreComplete() throws Exception {
        SettableFuture<Void> source = SettableFuture.create(); QueuedMain main = new QueuedMain();
        List<String> events = new ArrayList<>(); AtomicReference<PlaybackReadiness> owner = new AtomicReference<>();
        PlaybackReadiness gate = new PlaybackReadiness(source, main, () -> {
            events.add("apply-start");
            assertFalse(owner.get().ensureAppliedIfHydrated());
            owner.get().command(() -> { events.add("reentrant"); return Futures.immediateVoidFuture(); });
            events.add("apply-end");
        });
        owner.set(gate); source.set(null); main.drain();
        assertEquals(Arrays.asList("apply-start", "apply-end", "reentrant"), events);
    }

    @Test public void cancellingConnectionDoesNotCancelHydrationOrAnotherController() throws Exception {
        SettableFuture<Void> source = SettableFuture.create(); QueuedMain main = new QueuedMain();
        PlaybackReadiness gate = new PlaybackReadiness(source, main, () -> {});
        ListenableFuture<String> cancelled = gate.connection("first"), accepted = gate.connection("second");
        cancelled.cancel(true); assertFalse(source.isCancelled());
        source.set(null); assertEquals("second", accepted.get(1, TimeUnit.SECONDS));
        main.drain(); assertTrue(gate.isApplied());
    }

    @Test public void cancelledColdCommandIsNeverReplayed() throws Exception {
        SettableFuture<Void> source = SettableFuture.create(); QueuedMain main = new QueuedMain();
        AtomicInteger commands = new AtomicInteger();
        PlaybackReadiness gate = new PlaybackReadiness(source, main, () -> {});
        ListenableFuture<?> cancelled = gate.command(() -> { commands.incrementAndGet(); return Futures.immediateVoidFuture(); });
        cancelled.cancel(true); source.set(null); main.drain();
        assertEquals(0, commands.get()); assertFalse(source.isCancelled());
    }

    @Test public void closeSettlesAllOwnedWaitsWithoutCancellingSharedWork() throws Exception {
        SettableFuture<Void> source = SettableFuture.create(), delegate = SettableFuture.create(); QueuedMain main = new QueuedMain();
        PlaybackReadiness gate = new PlaybackReadiness(source, main, () -> fail("closed gate must not apply"));
        ListenableFuture<String> connection = gate.connection("accepted");
        ListenableFuture<?> command = gate.command(() -> { fail("closed gate must not play"); return Futures.immediateVoidFuture(); });
        ListenableFuture<Void> scoped = gate.scoped(delegate), application = gate.whenApplied();
        IllegalStateException closed = new IllegalStateException("service destroyed"); gate.close(closed);
        assertFailed(connection, closed); assertFailed(command, closed); assertFailed(scoped, closed); assertFailed(application, closed);
        assertFalse(source.isCancelled()); assertFalse(delegate.isCancelled());
        source.set(null); delegate.set(null); main.drain(); assertFalse(gate.isApplied());
    }

    @Test public void postedInitializationCannotRunAfterDestruction() throws Exception {
        SettableFuture<Void> source = SettableFuture.create(); QueuedMain main = new QueuedMain();
        AtomicInteger applied = new AtomicInteger();
        PlaybackReadiness gate = new PlaybackReadiness(source, main, applied::incrementAndGet);
        source.set(null); gate.close(new IllegalStateException("destroyed before main")); main.drain();
        assertEquals(0, applied.get()); assertFalse(gate.ensureAppliedIfHydrated());
    }

    @Test public void failedHydrationRejectsConnectionsCommandsAndApplication() throws Exception {
        SettableFuture<Void> source = SettableFuture.create(); QueuedMain main = new QueuedMain();
        PlaybackReadiness gate = new PlaybackReadiness(source, main, () -> fail("failed hydration must not use defaults"));
        ListenableFuture<String> connection = gate.connection("accepted");
        ListenableFuture<?> command = gate.command(Futures::immediateVoidFuture);
        IllegalStateException failure = new IllegalStateException("corrupt preferences"); background(() -> source.setException(failure));
        assertFailed(connection, failure); assertFailed(command, failure); assertFailed(gate.whenApplied(), failure);
        assertFailed(gate.connection("late"), failure); main.drain();
    }

    @Test public void applicationFailureRejectsQueuedAndFutureCommands() throws Exception {
        SettableFuture<Void> source = SettableFuture.create(); QueuedMain main = new QueuedMain();
        IllegalStateException failure = new IllegalStateException("player configuration failed");
        PlaybackReadiness gate = new PlaybackReadiness(source, main, () -> { throw failure; });
        ListenableFuture<?> queued = gate.command(() -> { fail("must not play on partial settings"); return Futures.immediateVoidFuture(); });
        source.set(null); main.drain();
        assertFailed(queued, failure); assertFailed(gate.whenApplied(), failure);
        assertFailed(gate.command(Futures::immediateVoidFuture), failure); assertFalse(gate.isApplied());
    }

    @Test public void commandFailureDoesNotPreventAnotherCommand() throws Exception {
        QueuedMain main = new QueuedMain(); PlaybackReadiness gate = new PlaybackReadiness(Futures.immediateVoidFuture(), main, () -> {});
        IllegalArgumentException failure = new IllegalArgumentException("invalid seek");
        ListenableFuture<?> bad = gate.command(() -> { throw failure; });
        AtomicInteger paused = new AtomicInteger();
        ListenableFuture<?> pause = gate.command(() -> { paused.incrementAndGet(); return Futures.immediateVoidFuture(); });
        assertFailed(bad, failure); pause.get(1, TimeUnit.SECONDS); assertEquals(1, paused.get()); assertTrue(gate.isApplied());
    }

    @Test public void asyncFadeDoesNotBlockPauseAndReturnsActualCompletion() throws Exception {
        QueuedMain main = new QueuedMain(); SettableFuture<String> fading = SettableFuture.create();
        PlaybackReadiness gate = new PlaybackReadiness(Futures.immediateVoidFuture(), main, () -> {});
        ListenableFuture<?> fade = gate.command(() -> fading); AtomicInteger paused = new AtomicInteger();
        ListenableFuture<?> pause = gate.command(() -> { paused.incrementAndGet(); return Futures.immediateVoidFuture(); });
        pause.get(1, TimeUnit.SECONDS); assertEquals(1, paused.get()); assertFalse(fade.isDone());
        fading.set("actual fade result"); assertEquals("actual fade result", fade.get(1, TimeUnit.SECONDS));
    }

    @Test public void cancellingAcceptedCommandWaitDoesNotCancelItsDelegate() throws Exception {
        QueuedMain main = new QueuedMain(); SettableFuture<Void> saving = SettableFuture.create();
        PlaybackReadiness gate = new PlaybackReadiness(Futures.immediateVoidFuture(), main, () -> {});
        ListenableFuture<?> waiting = gate.command(() -> saving); waiting.cancel(true);
        assertFalse(saving.isCancelled()); saving.set(null);
        assertTrue(gate.isApplied());
    }

    @Test public void delegateFailureIsNotReportedAsImmediateSuccess() throws Exception {
        QueuedMain main = new QueuedMain(); SettableFuture<Void> saving = SettableFuture.create();
        PlaybackReadiness gate = new PlaybackReadiness(Futures.immediateVoidFuture(), main, () -> {});
        ListenableFuture<?> result = gate.command(() -> saving); assertFalse(result.isDone());
        IllegalStateException failedSave = new IllegalStateException("disk full"); saving.setException(failedSave);
        assertFailed(result, failedSave);
    }

    @Test public void rejectedMainDispatchFailsOwnedWorkWithoutCancellingHydration() throws Exception {
        SettableFuture<Void> source = SettableFuture.create(); RejectedExecutionException rejected = new RejectedExecutionException("main closed");
        PlaybackReadiness gate = new PlaybackReadiness(source, task -> { throw rejected; }, () -> fail("must not configure on worker"));
        ListenableFuture<?> command = gate.command(Futures::immediateVoidFuture);
        background(() -> source.set(null)); assertFailed(command, rejected); assertFailed(gate.whenApplied(), rejected);
        assertFalse(source.isCancelled()); assertFalse(gate.isApplied());
    }

    @Test public void lateDelegateCannotReviveAClosedSession() throws Exception {
        QueuedMain main = new QueuedMain(); SettableFuture<String> resumption = SettableFuture.create();
        PlaybackReadiness gate = new PlaybackReadiness(Futures.immediateVoidFuture(), main, () -> {});
        main.drain(); ListenableFuture<String> scoped = gate.scoped(resumption);
        IllegalStateException closed = new IllegalStateException("old service"); gate.close(closed); resumption.set("late saved queue");
        assertFailed(scoped, closed); assertFailed(gate.connection("late controller"), closed);
        assertFailed(gate.command(() -> { fail("no late play"); return Futures.immediateVoidFuture(); }), closed);
    }

    @Test public void reentrantCloseDuringApplicationNeverPublishesReady() throws Exception {
        SettableFuture<Void> source = SettableFuture.create(); QueuedMain main = new QueuedMain(); AtomicReference<PlaybackReadiness> owner = new AtomicReference<>();
        IllegalStateException closed = new IllegalStateException("destroyed during apply");
        PlaybackReadiness gate = new PlaybackReadiness(source, main, () -> owner.get().close(closed)); owner.set(gate);
        ListenableFuture<?> command = gate.command(() -> { fail("must not play"); return Futures.immediateVoidFuture(); });
        source.set(null); main.drain(); assertFalse(gate.isApplied());
        assertFailed(command, closed); assertFailed(gate.whenApplied(), closed);
    }

    @Test public void readyListenerClosingTheServiceCannotExposeAnAppliedState() throws Exception {
        SettableFuture<Void> source = SettableFuture.create(); QueuedMain main = new QueuedMain();
        PlaybackReadiness gate = new PlaybackReadiness(source, main, () -> {});
        gate.whenApplied().addListener(() -> gate.close(new IllegalStateException("closed by readiness callback")), DIRECT);
        source.set(null);
        assertFalse("A state read cannot expose a player closed by a synchronous ready listener", gate.ensureAppliedIfHydrated());
        assertFalse(gate.isApplied()); main.drain();
    }

    private static void assertFailed(ListenableFuture<?> future, Throwable expected) throws Exception {
        try { future.get(1, TimeUnit.SECONDS); fail("Expected " + expected.getMessage()); }
        catch (ExecutionException error) { assertSame(expected, error.getCause()); }
    }
    private static Thread background(Runnable work) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> { try { work.run(); } catch (Throwable error) { failure.set(error); } }, "AuroraReadinessTest");
        worker.start(); worker.join(2000); assertFalse("worker cannot depend on blocked main", worker.isAlive());
        if (failure.get() != null) throw new AssertionError(failure.get());
        return worker;
    }
    private static final class QueuedMain implements Executor {
        private final Thread owner = Thread.currentThread();
        private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
        @Override public void execute(Runnable work) { tasks.add(work); }
        void drain() {
            assertSame(owner, Thread.currentThread()); int count = 0;
            for (Runnable work; (work = tasks.poll()) != null; ) { assertTrue("unbounded main dispatch", ++count < 100); work.run(); }
        }
    }
}
