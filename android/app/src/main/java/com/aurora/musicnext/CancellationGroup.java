package com.aurora.musicnext;

import java.util.HashSet;
import java.util.Set;

/** Lifetime cancellation with removable bindings: no retained sockets from previous tracks. */
final class CancellationGroup {
    private final Set<Registration> active = new HashSet<>();
    private boolean cancelled;
    Registration bind(Runnable callback) {
        Registration registration = new Registration(callback);
        boolean cancel;
        synchronized (this) { cancel = cancelled; if (!cancel) active.add(registration); }
        if (cancel) callback.run();
        return registration;
    }
    void cancel() {
        Registration[] registrations;
        synchronized (this) {
            if (cancelled) return;
            cancelled = true; registrations = active.toArray(new Registration[0]); active.clear();
        }
        // Never run a socket callback under the registry's lock, or close a cache on this thread.
        for (Registration registration : registrations) registration.callback.run();
    }
    final class Registration implements AutoCloseable {
        private final Runnable callback;
        private Registration(Runnable callback) { this.callback = callback; }
        @Override public void close() { synchronized (CancellationGroup.this) { active.remove(this); } }
    }
}
