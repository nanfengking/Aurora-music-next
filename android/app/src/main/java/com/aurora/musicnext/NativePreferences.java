package com.aurora.musicnext;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Process authority: worker-side durable writes; cached-only reads; effects are part of ordering. */
final class NativePreferences {
    interface Effects {
        default void validate(NativePreferenceRecord before) throws Exception {}
        ListenableFuture<?> apply(NativePreferenceRecord before, NativePreferenceRecord after) throws Exception;
    }

    static final class CommittedEffectException extends Exception {
        final NativePreferenceRecord record;
        CommittedEffectException(NativePreferenceRecord record, Throwable cause) {
            super("Preferences saved, but refresh failed", cause); this.record = record;
        }
    }

    private static final Object SUBMISSIONS = new Object();
    private static final Executor DIRECT = MoreExecutors.directExecutor();
    // No thread is created until ready/update is requested; the worker releases its stack when idle.
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(), work -> {
            Thread thread = new Thread(work, "AuroraPreferences"); thread.setDaemon(true); return thread;
        });
    private static final Map<Context, Map<String, NativePreferences>> HANDLES = new WeakHashMap<>();
    // Worker-confined. Authorities retain only tiny immutable state, not Contexts or open databases.
    private static final Map<String, Authority> AUTHORITIES = new HashMap<>();
    private final WeakReference<Context> context;
    private final String databaseName;
    private volatile Authority authority;
    private SettableFuture<NativePreferenceRecord> initializing; // SUBMISSIONS lock; worker completes it.

    private NativePreferences(Context context, String databaseName) {
        this.context = new WeakReference<>(context); this.databaseName = databaseName;
    }

    static NativePreferences forApplication(Context context) {
        return forDatabase(context.getApplicationContext(), "aurora-mobile.db");
    }

    static JSONObject only(JSONObject patch, String... allowed) {
        if (patch == null) throw new IllegalArgumentException("Invalid preference patch");
        NativePreferenceValues.defaults().patch(patch);
        JSONObject copied = new JSONObject();
        java.util.Iterator<String> keys = patch.keys();
        try {
            while (keys.hasNext()) {
                String key = keys.next();
                if (!java.util.Arrays.asList(allowed).contains(key)) throw new IllegalArgumentException("Invalid preference field");
                copied.put(key, patch.get(key));
            }
        } catch (org.json.JSONException invalid) { throw new IllegalArgumentException("Invalid preference patch"); }
        return copied;
    }

    static NativePreferences forDatabase(Context context, String databaseName) {
        if (context == null || databaseName == null || databaseName.isEmpty() || databaseName.length() > 160
            || databaseName.equals(".") || databaseName.equals("..") || databaseName.contains("/") || databaseName.contains("\\"))
            throw new IllegalArgumentException("Invalid preference database");
        synchronized (SUBMISSIONS) {
            Map<String, NativePreferences> handles = HANDLES.get(context);
            if (handles == null) { handles = new HashMap<>(); HANDLES.put(context, handles); }
            NativePreferences result = handles.get(databaseName);
            if (result == null) { result = new NativePreferences(context, databaseName); handles.put(databaseName, result); }
            return result;
        }
    }

    ListenableFuture<NativePreferenceRecord> ready() {
        synchronized (SUBMISSIONS) { return Futures.nonCancellationPropagating(beginReady()); }
    }

    NativePreferenceRecord requireReady() {
        Authority current = authority;
        if (current == null) throw new IllegalStateException("Native preferences not ready");
        return current.snapshot;
    }

    ListenableFuture<NativePreferenceRecord> update(JSONObject patch) {
        return update(patch, DIRECT, (before, after) -> Futures.immediateVoidFuture());
    }

    ListenableFuture<NativePreferenceRecord> update(JSONObject patch, Executor executor, Effects effects) {
        final JSONObject copied;
        try {
            if (executor == null || effects == null) throw new IllegalArgumentException("Invalid preference effect");
            NativePreferenceValues checked = NativePreferenceValues.defaults().patch(patch);
            copied = new JSONObject();
            java.util.Iterator<String> keys = patch.keys();
            while (keys.hasNext()) { String key = keys.next(); copied.put(key, checked.get(key)); }
        } catch (Exception invalid) { return Futures.immediateFailedFuture(invalid); }
        SettableFuture<NativePreferenceRecord> completion = SettableFuture.create();
        synchronized (SUBMISSIONS) {
            ListenableFuture<NativePreferenceRecord> gate = beginReady();
            Context owner = context.get();
            WORKER.execute(() -> {
                try {
                    Futures.getDone(gate);
                    if (owner == null || authority == null) throw new IllegalStateException("Preference context unavailable");
                    Change change = new Change(owner, databaseName, copied, executor, effects, completion);
                    authority.queue.add(change); startNext(authority);
                } catch (Throwable failure) { completion.setException(unwrap(failure)); }
            });
        }
        // Cancelling a caller's wait cannot undo/cancel an already accepted durable operation.
        return Futures.nonCancellationPropagating(completion);
    }

    /** Must hold SUBMISSIONS. A fresh call after failed hydration is an explicit retry, not a reset. */
    private ListenableFuture<NativePreferenceRecord> beginReady() {
        if (authority != null) return Futures.immediateFuture(authority.snapshot);
        if (initializing != null && !initializing.isDone()) return initializing;
        SettableFuture<NativePreferenceRecord> gate = SettableFuture.create(); initializing = gate;
        Context owner = context.get();
        WORKER.execute(() -> {
            try {
                if (owner == null) throw new IllegalStateException("Preference context unavailable");
                String path = path(owner, databaseName);
                Authority shared = AUTHORITIES.get(path);
                if (shared == null) {
                    NativePreferenceRecord record;
                    try (MusicStore store = new MusicStore(owner, databaseName)) {
                        record = NativePreferenceStorage.initialize(store.getWritableDatabase(),
                            () -> NativePreferenceMigration.read(owner, databaseName));
                    }
                    shared = new Authority(path, record); AUTHORITIES.put(path, shared);
                }
                authority = shared; gate.set(shared.snapshot);
            } catch (Throwable failure) { gate.setException(unwrap(failure)); }
        });
        return gate;
    }

    /** All queue state and SQL lifecycle methods below run only on WORKER, never while effects run. */
    private static void startNext(Authority authority) {
        if (authority.busy || authority.queue.isEmpty()) return;
        authority.busy = true;
        Change change = authority.queue.remove();
        try {
            checkPath(authority, change);
            change.store = new MusicStore(change.context, change.databaseName);
            change.before = NativePreferenceStorage.read(change.store.getWritableDatabase());
            change.after = change.before.patch(change.patch);
            change.executor.execute(() -> {
                try {
                    change.effects.validate(change.before);
                    WORKER.execute(() -> commit(authority, change));
                } catch (Throwable failure) { WORKER.execute(() -> finish(authority, change, failure)); }
            });
        } catch (Throwable failure) { finish(authority, change, failure); }
    }

    private static void commit(Authority authority, Change change) {
        if (change.finished) return;
        try {
            checkPath(authority, change);
            SQLiteDatabase db = change.store.getWritableDatabase();
            db.beginTransaction();
            try {
                NativePreferenceStorage.writeInTransaction(db, change.before, change.after);
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
            // Publish only after endTransaction (including COMMIT) returns, before any UI effect.
            authority.snapshot = change.after; change.committed = true;
            close(change);
            change.executor.execute(() -> {
                try {
                    ListenableFuture<?> effect = change.effects.apply(change.before, change.after);
                    if (effect == null) throw new IllegalStateException("Preference effect did not return completion");
                    effect.addListener(() -> WORKER.execute(() -> {
                        Throwable failure = null;
                        try { Futures.getDone(effect); } catch (Throwable error) { failure = unwrap(error); }
                        finish(authority, change, failure);
                    }), DIRECT);
                } catch (Throwable failure) { WORKER.execute(() -> finish(authority, change, failure)); }
            });
        } catch (Throwable failure) { finish(authority, change, failure); }
    }

    private static void finish(Authority authority, Change change, Throwable failure) {
        if (change.finished) return;
        change.finished = true;
        try { close(change); }
        catch (Throwable closeFailure) { if (failure == null) failure = closeFailure; else failure.addSuppressed(closeFailure); }
        if (failure == null) change.completion.set(change.after);
        else change.completion.setException(change.committed ? new CommittedEffectException(change.after, failure) : unwrap(failure));
        authority.busy = false;
        // Queue another work item rather than recursing through a long batch of failed changes.
        WORKER.execute(() -> startNext(authority));
    }

    private static void close(Change change) {
        MusicStore store = change.store; change.store = null;
        if (store != null) store.close();
    }

    private static String path(Context context, String name) throws java.io.IOException {
        return context.getDatabasePath(name).getCanonicalPath();
    }

    private static void checkPath(Authority authority, Change change) throws java.io.IOException {
        if (!authority.path.equals(path(change.context, change.databaseName)))
            throw new IllegalStateException("Preference database identity changed");
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof java.util.concurrent.ExecutionException && failure.getCause() != null ? failure.getCause() : failure;
    }

    private static final class Authority {
        final String path;
        final ArrayDeque<Change> queue = new ArrayDeque<>();
        volatile NativePreferenceRecord snapshot;
        boolean busy;
        Authority(String path, NativePreferenceRecord snapshot) { this.path = path; this.snapshot = snapshot; }
    }

    private static final class Change {
        final Context context;
        final String databaseName;
        final JSONObject patch;
        final Executor executor;
        final Effects effects;
        final SettableFuture<NativePreferenceRecord> completion;
        MusicStore store;
        NativePreferenceRecord before, after;
        boolean committed, finished;
        Change(Context context, String databaseName, JSONObject patch, Executor executor, Effects effects,
            SettableFuture<NativePreferenceRecord> completion) {
            this.context = context; this.databaseName = databaseName; this.patch = patch;
            this.executor = executor; this.effects = effects; this.completion = completion;
        }
    }
}
