package com.aurora.musicnext;

import java.io.InterruptedIOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class PrefetchPolicyTest {
    private static final long MIB = 1024 * 1024;
    @Test public void stablePlaybackCanPrefetchWhenExplicitlyEnabled() {
        assertTrue(PrefetchPolicy.allowed(true, true, false, false, false, false, true, true, true, false));
    }
    @Test public void defaultOffPausedLoadingBufferingAndErrorsDoNotPrefetch() {
        assertFalse(PrefetchPolicy.allowed(false, true, false, false, false, false, true, true, true, false));
        assertFalse(PrefetchPolicy.allowed(true, false, false, false, false, false, true, true, true, false));
        assertFalse(PrefetchPolicy.allowed(true, true, true, false, false, false, true, true, true, false));
        assertFalse(PrefetchPolicy.allowed(true, true, false, true, false, false, true, true, true, false));
        assertFalse(PrefetchPolicy.allowed(true, true, false, false, true, false, true, true, true, false));
    }
    @Test public void respectsBatteryNetworkAndRepeatOne() {
        assertFalse(PrefetchPolicy.allowed(true, true, false, false, false, true, true, true, true, false));
        assertFalse(PrefetchPolicy.allowed(true, true, false, false, false, false, false, true, true, false));
        assertFalse(PrefetchPolicy.allowed(true, true, false, false, false, false, true, true, true, true));
    }
    @Test public void meteredDataNeedsTheSharedWifiLimitTurnedOff() {
        assertFalse(PrefetchPolicy.allowed(true, true, false, false, false, false, true, true, false, false));
        assertTrue(PrefetchPolicy.allowed(true, true, false, false, false, false, true, false, false, false));
    }
    @Test public void unknownEmptyAndLargeSongsAreNotPartialPretendPrefetches() {
        for (long size : new long[]{-1, 0, 64 * MIB + 1, Long.MAX_VALUE}) assertFalse(PrefetchPolicy.sizeAllowed(size));
        assertTrue(PrefetchPolicy.sizeAllowed(1)); assertTrue(PrefetchPolicy.sizeAllowed(64 * MIB));
    }
    @Test public void preservesPlaybackHeadroomAndDiskReserve() {
        assertTrue(PrefetchPolicy.fits(64 * MIB, 56 * MIB, 128 * MIB, 96 * MIB));
        assertFalse(PrefetchPolicy.fits(64 * MIB, 56 * MIB + 1, 128 * MIB, 96 * MIB));
        assertFalse(PrefetchPolicy.fits(64 * MIB, 0, 128 * MIB, 96 * MIB - 1));
    }
    @Test public void invalidBudgetsAndOverflowFailClosed() {
        for (long protectedBytes : new long[]{-1, 128 * MIB, Long.MAX_VALUE}) assertFalse(PrefetchPolicy.fits(1, protectedBytes, 128 * MIB, Long.MAX_VALUE));
        assertFalse(PrefetchPolicy.fits(1, 0, 0, Long.MAX_VALUE)); assertFalse(PrefetchPolicy.fits(1, 0, 128 * MIB, -1));
    }
    @Test public void repeatedEventsDoNotRetryOrQueueDuplicateJobs() {
        PrefetchPolicy.Gate gate = new PrefetchPolicy.Gate(); assertFalse(gate.change(null)); assertTrue(gate.change("current-next-network"));
        for (int i = 0; i < 100; i++) assertFalse(gate.change("current-next-network"));
        assertTrue(gate.change(null)); assertFalse(gate.change(null)); assertTrue(gate.change("current-next-network"));
        assertTrue(gate.change("current-other-network")); gate.reset(); assertTrue(gate.change("current-other-network"));
    }
    @Test public void completedOrFailedAttemptsDoNotRepeatOnEveryBufferRefill() {
        PrefetchPolicy.Gate gate = new PrefetchPolicy.Gate(); gate.change("track-next-network"); gate.complete("track-next-network");
        gate.change(null); gate.change("track-next-network"); assertTrue(gate.finished("track-next-network"));
        assertFalse(gate.finished("track-next-new-network")); gate.reset(); assertFalse(gate.finished("track-next-network"));
    }
    @Test public void cancellationBeforeBindingPreventsLaterRequests() {
        PrefetchCancellation cancellation = new PrefetchCancellation(); AtomicInteger calls = new AtomicInteger();
        cancellation.cancel(); cancellation.bind(calls::incrementAndGet); assertEquals(1, calls.get());
        assertThrows(InterruptedIOException.class, cancellation::check);
    }
    @Test public void cancellationStopsBothSocketAndWriterExactlyOnce() throws Exception {
        PrefetchCancellation cancellation = new PrefetchCancellation(); AtomicInteger socket = new AtomicInteger(), writer = new AtomicInteger();
        cancellation.bind(socket::incrementAndGet); cancellation.bind(writer::incrementAndGet); cancellation.check();
        cancellation.cancel(); cancellation.cancel(); assertEquals(1, socket.get()); assertEquals(1, writer.get());
        assertThrows(InterruptedIOException.class, cancellation::check);
    }
    @Test public void completedWorkUnbindsAndInterruptionIsRespected() throws Exception {
        PrefetchCancellation cancellation = new PrefetchCancellation(); AtomicInteger calls = new AtomicInteger();
        cancellation.bind(calls::incrementAndGet); cancellation.unbind(); cancellation.cancel(); assertEquals(0, calls.get());
        PrefetchCancellation interrupted = new PrefetchCancellation(); Thread.currentThread().interrupt();
        try { assertThrows(InterruptedIOException.class, interrupted::check); } finally { Thread.interrupted(); }
        new PrefetchCancellation().check();
    }
}
