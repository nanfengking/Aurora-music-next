package com.aurora.musicnext;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class CancellationGroupTest {
    @Test public void cancelledBeforeBindStopsNewSocket() { CancellationGroup group = new CancellationGroup(); group.cancel(); AtomicInteger calls = new AtomicInteger(); group.bind(calls::incrementAndGet).close(); assertEquals(1, calls.get()); }
    @Test public void activeCallbacksAreCancelledOnlyOnce() { CancellationGroup group = new CancellationGroup(); AtomicInteger calls = new AtomicInteger(); group.bind(calls::incrementAndGet); group.bind(calls::incrementAndGet); group.cancel(); group.cancel(); assertEquals(2, calls.get()); }
    @Test public void closedRequestsAreNotRetained() { CancellationGroup group = new CancellationGroup(); AtomicInteger calls = new AtomicInteger(); for (int i = 0; i < 10000; i++) group.bind(calls::incrementAndGet).close(); group.cancel(); assertEquals(0, calls.get()); }
    @Test public void onePlayerCannotCancelTheOther() { CancellationGroup outgoing = new CancellationGroup(), incoming = new CancellationGroup(); AtomicInteger old = new AtomicInteger(), next = new AtomicInteger(); outgoing.bind(old::incrementAndGet); incoming.bind(next::incrementAndGet); outgoing.cancel(); assertEquals(1, old.get()); assertEquals(0, next.get()); }
    @Test public void reentrantRegistrationCannotDeadlock() { CancellationGroup group = new CancellationGroup(); AtomicInteger calls = new AtomicInteger(); group.bind(() -> group.bind(calls::incrementAndGet)); group.cancel(); assertEquals(1, calls.get()); }
    @Test public void concurrentBindAndCancelNeverMissesActiveSocket() throws Exception {
        for (int i = 0; i < 100; i++) {
            CancellationGroup group = new CancellationGroup(); AtomicInteger calls = new AtomicInteger();
            Thread bind = new Thread(() -> group.bind(calls::incrementAndGet)), cancel = new Thread(group::cancel);
            bind.start(); cancel.start(); bind.join(); cancel.join(); assertEquals(1, calls.get());
        }
    }
}
