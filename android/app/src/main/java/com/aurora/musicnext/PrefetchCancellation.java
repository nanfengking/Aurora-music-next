package com.aurora.musicnext;

import java.io.InterruptedIOException;

/** Registering after cancellation still cancels: no race can start a replacement HTTP request. */
final class PrefetchCancellation {
    private volatile boolean cancelled;
    private Runnable callback;
    synchronized void bind(Runnable action) {
        if (cancelled) action.run();
        else if (callback == null) callback = action;
        else { Runnable previous = callback; callback = () -> { previous.run(); action.run(); }; }
    }
    synchronized void cancel() {
        if (cancelled) return;
        cancelled = true;
        if (callback != null) callback.run();
        callback = null;
    }
    synchronized void unbind() { callback = null; }
    boolean cancelled() { return cancelled; }
    void check() throws InterruptedIOException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("PREFETCH_CANCELLED");
    }
}
