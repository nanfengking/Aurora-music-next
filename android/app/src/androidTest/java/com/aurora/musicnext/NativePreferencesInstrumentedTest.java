package com.aurora.musicnext;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;
import android.os.StrictMode;
import androidx.core.content.ContextCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Actual coordinator + Android SQL; only callback timing is controlled, never the DB outcome. */
@RunWith(AndroidJUnit4.class)
public final class NativePreferencesInstrumentedTest {
    private static final String NAME = "preferences.db";
    private static final Executor DIRECT = Runnable::run;
    private final List<Fixture> fixtures = new ArrayList<>();
    @After public void after() { for (Fixture fixture : fixtures) fixture.close(); }

    @Test public void coldConstructionAndCachedGettersDoNotReadDiskOnMain() throws Exception {
        Fixture f = fixture();
        AtomicReference<NativePreferences> handle = new AtomicReference<>();
        AtomicReference<ListenableFuture<NativePreferenceRecord>> ready = new AtomicReference<>();
        List<android.os.strictmode.Violation> violations = new java.util.concurrent.CopyOnWriteArrayList<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            StrictMode.ThreadPolicy old = StrictMode.getThreadPolicy();
            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder(old).detectDiskReads().detectDiskWrites()
                .penaltyListener(DIRECT, violations::add).build());
            try {
                handle.set(NativePreferences.forDatabase(f.context, NAME));
                assertThrows(IllegalStateException.class, () -> handle.get().requireReady());
                ready.set(handle.get().ready());
            } finally { StrictMode.setThreadPolicy(old); }
        });
        assertEquals(true, await(ready.get()).values.get("wifiOnly"));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertSame(handle.get(), NativePreferences.forDatabase(f.context, NAME));
            assertEquals(1, (Double) handle.get().requireReady().values.get("volume"), 0);
        });
        assertTrue(violations.toString(), violations.isEmpty());
        assertTrue(f.pathReads.get() > 0);
    }

    @Test public void delayedHydrationDoesNotExposeDefaultsOrAcknowledgeEarly() throws Exception {
        Fixture f = fixture(); SQLiteDatabase db = f.database();
        db.beginTransaction();
        NativePreferences p = f.preferences(); ListenableFuture<NativePreferenceRecord> ready = p.ready();
        try {
            assertTrue(f.pathSeen.await(3, TimeUnit.SECONDS));
            assertFalse(ready.isDone()); assertThrows(IllegalStateException.class, p::requireReady);
        } finally { db.endTransaction(); }
        assertEquals(0, await(ready).revision); assertEquals(true, p.requireReady().values.get("rememberPlayback"));
    }

    @Test public void cancellingOneReadinessWaiterCannotCancelSharedHydration() throws Exception {
        Fixture f = fixture(); SQLiteDatabase db = f.database(); db.beginTransaction();
        NativePreferences p = f.preferences(); ListenableFuture<NativePreferenceRecord> first = p.ready();
        ListenableFuture<NativePreferenceRecord> second = p.ready();
        try { assertTrue(first.cancel(false)); assertFalse(second.isCancelled()); }
        finally { db.endTransaction(); }
        assertEquals(true, await(second).values.get("wifiOnly"));
        assertFalse(p.ready().isCancelled());
    }

    @Test public void failedHydrationCanRetryWithoutResettingAnInitializedRecord() throws Exception {
        Fixture f = fixture(); SQLiteDatabase db = f.database();
        db.execSQL("CREATE TRIGGER refuse_init BEFORE UPDATE ON native_preferences BEGIN SELECT RAISE(ABORT,'fixture'); END");
        NativePreferences p = f.preferences(); failed(p.ready());
        assertThrows(IllegalStateException.class, p::requireReady);
        db.execSQL("DROP TRIGGER refuse_init"); assertEquals(0, await(p.ready()).revision);
        assertEquals(.25, (Double) await(p.update(new JSONObject().put("volume", .25))).values.get("volume"), 0);
    }

    @Test public void initializedDatabaseDoesNotReadLegacyProfilesAgain() throws Exception {
        Fixture f = fixture(); NativePreferenceStorage.initialize(f.database(), Map.of(), Map.of("language", "fr"), Map.of());
        f.forbidLegacy = true;
        NativePreferenceRecord loaded = await(f.preferences().ready());
        assertEquals("fr", loaded.values.get("language")); assertEquals(0, f.profileReads.get());
    }

    @Test public void corruptInitializedRecordFailsReadinessWithoutLegacyFallback() throws Exception {
        Fixture f = fixture();
        NativePreferenceStorage.initialize(f.database(), Map.of(), Map.of("language", "fr"), Map.of());
        f.database().execSQL("UPDATE native_preferences SET version=2"); f.forbidLegacy = true;
        NativePreferences p = f.preferences(); assertTrue(failed(p.ready()) instanceof IllegalStateException);
        assertThrows(IllegalStateException.class, p::requireReady); assertEquals(0, f.profileReads.get());
        // Fixture-only repair; retry must read preserved data, not silently reset to defaults.
        f.database().execSQL("UPDATE native_preferences SET version=1");
        assertEquals("fr", await(p.ready()).values.get("language"));
    }

    @Test public void migrationReadsOnlyNamedFieldsAndNeverCopiesOrChangesCredentials() throws Exception {
        Fixture f = fixture();
        assertTrue(f.profile("playback-options").edit().putFloat("volume", .4f).putLong("restoreRevision", 7)
            .putString("prefetch", "true").putString("password", "fixture-playback-only").commit());
        assertTrue(f.profile("appearance").edit().putString("language", "zh-TW").putInt("lyricsSize", 24).commit());
        assertTrue(f.profile("connection").edit().putBoolean("wifiOnly", false).putString("password", "fixture-only")
            .putString("apiKey", "fixture-not-a-key").commit());
        NativePreferenceRecord loaded = await(f.preferences().ready());
        assertEquals(.4, (Double) loaded.values.get("volume"), .00001); assertEquals(7, loaded.restoreRevision);
        assertEquals(false, loaded.values.get("prefetch")); assertEquals(false, loaded.values.get("wifiOnly"));
        assertEquals("zh-TW", loaded.values.get("language")); assertEquals(24, loaded.values.get("lyricsSize"));
        assertEquals("fixture-only", f.profile("connection").getString("password", ""));
        assertEquals("fixture-not-a-key", f.profile("connection").getString("apiKey", ""));
        assertFalse(loaded.values.json().has("password")); assertFalse(loaded.values.json().has("apiKey"));
        f.forbidLegacy = true;
        await(f.preferences().update(new JSONObject().put("language", "en")));
        assertEquals("en", f.preferences().requireReady().values.get("language"));
    }

    @Test public void fullPathsIsolateEqualNamesAndAliasesShareCommittedSnapshots() throws Exception {
        Fixture a = fixture(), b = fixture();
        NativePreferences one = a.preferences(), two = b.preferences();
        await(one.ready()); await(two.ready());
        await(one.update(new JSONObject().put("volume", .25)));
        assertEquals(1, (Double) two.requireReady().values.get("volume"), 0);
        Context alias = new ContextWrapper(a.alias()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getDatabasePath(String name) {
                File path = super.getDatabasePath(name);
                return new File(path.getParentFile(), "./" + path.getName());
            }
        };
        NativePreferences same = NativePreferences.forDatabase(alias, NAME);
        await(same.ready()); await(same.update(new JSONObject().put("language", "fr")));
        assertEquals("fr", one.requireReady().values.get("language"));
        assertEquals(.25, (Double) same.requireReady().values.get("volume"), 0);
        assertEquals("", two.requireReady().values.get("language"));
    }

    @Test public void concurrentPartialUpdatesMergeWithTheCommittedPredecessor() throws Exception {
        Fixture f = fixture(); NativePreferences p = f.preferences(); await(p.ready());
        List<ListenableFuture<NativePreferenceRecord>> changes = new ArrayList<>();
        changes.add(p.update(new JSONObject().put("volume", .3)));
        changes.add(p.update(new JSONObject().put("language", "fr")));
        changes.add(p.update(new JSONObject().put("motion", false)));
        for (ListenableFuture<NativePreferenceRecord> change : changes) await(change);
        assertEquals(.3, (Double) p.requireReady().values.get("volume"), 0);
        assertEquals("fr", p.requireReady().values.get("language")); assertEquals(false, p.requireReady().values.get("motion"));
        assertEquals(3, p.requireReady().revision); assertEquals(3, NativePreferenceStorage.read(f.database()).revision);
    }

    @Test public void delayedEffectsBlockLaterWritesButNotWorkerCompletedReadiness() throws Exception {
        Fixture f = fixture(); NativePreferences p = f.preferences(); await(p.ready());
        SettableFuture<Void> effects = SettableFuture.create(); CountDownLatch applied = new CountDownLatch(1);
        ListenableFuture<NativePreferenceRecord> first = p.update(new JSONObject().put("volume", .25),
            ContextCompat.getMainExecutor(f.context), (before, after) -> {
                assertEquals(0, before.revision); assertEquals(1, after.revision); applied.countDown(); return effects;
            });
        try {
            assertTrue(applied.await(4, TimeUnit.SECONDS));
            ListenableFuture<NativePreferenceRecord> second = p.update(new JSONObject().put("language", "fr"));
            assertFalse(first.isDone()); assertFalse(second.isDone());
            Context alias = f.alias();
            // A new handle's worker-side hydration runs after the second enqueue:
            // a progress barrier that catches premature writes without a timing sleep.
            assertEquals(.25, (Double) await(NativePreferences.forDatabase(alias, NAME).ready()).values.get("volume"), 0);
            assertEquals(.25, (Double) await(p.ready()).values.get("volume"), 0);
            assertEquals("", NativePreferenceStorage.read(f.database()).values.get("language"));
            effects.set(null); await(first); assertEquals(2, await(second).revision);
            assertEquals("fr", p.requireReady().values.get("language"));
        } finally { effects.set(null); }
    }

    @Test public void preflightFailureDoesNotCommitOrReplayAndQueueRecovers() throws Exception {
        Fixture f = fixture(); NativePreferences p = f.preferences(); await(p.ready());
        ListenableFuture<NativePreferenceRecord> rejected = p.update(new JSONObject().put("volume", .2), DIRECT,
            new NativePreferences.Effects() {
                @Override public void validate(NativePreferenceRecord before) { throw new IllegalStateException("fixture preflight"); }
                @Override public ListenableFuture<?> apply(NativePreferenceRecord before, NativePreferenceRecord after) { throw new AssertionError("Must not apply"); }
            });
        assertTrue(failed(rejected) instanceof IllegalStateException);
        assertEquals(0, p.requireReady().revision); assertEquals(1, (Double) NativePreferenceStorage.read(f.database()).values.get("volume"), 0);
        assertEquals(.6, (Double) await(p.update(new JSONObject().put("volume", .6))).values.get("volume"), 0);
        assertEquals(.6, (Double) p.requireReady().values.get("volume"), 0);
    }

    @Test public void effectFailureReportsCommittedStateAndDoesNotBlockLaterOperations() throws Exception {
        Fixture f = fixture(); NativePreferences p = f.preferences(); await(p.ready());
        Throwable failure = failed(p.update(new JSONObject().put("volume", .25), DIRECT,
            (before, after) -> Futures.immediateFailedFuture(new IllegalStateException("fixture refresh"))));
        assertTrue(failure instanceof NativePreferences.CommittedEffectException);
        assertEquals(1, ((NativePreferences.CommittedEffectException) failure).record.revision);
        assertEquals(.25, (Double) p.requireReady().values.get("volume"), 0);
        assertEquals(.25, (Double) NativePreferenceStorage.read(f.database()).values.get("volume"), 0);
        assertEquals("fr", await(p.update(new JSONObject().put("language", "fr"))).values.get("language"));
    }

    @Test public void sqlFailureDoesNotPublishAndSuccessfulNewValueSupersedesFailure() throws Exception {
        Fixture f = fixture(); NativePreferences p = f.preferences(); await(p.ready());
        f.database().execSQL("CREATE TRIGGER refuse_change BEFORE UPDATE ON native_preferences BEGIN SELECT RAISE(ABORT,'fixture'); END");
        failed(p.update(new JSONObject().put("volume", .2)));
        assertEquals(0, p.requireReady().revision); assertEquals(1, (Double) p.requireReady().values.get("volume"), 0);
        f.database().execSQL("DROP TRIGGER refuse_change");
        await(p.update(new JSONObject().put("volume", .6))); await(p.update(new JSONObject().put("language", "en")));
        assertEquals(.6, (Double) p.requireReady().values.get("volume"), 0);
        assertEquals(.6, (Double) NativePreferenceStorage.read(f.database()).values.get("volume"), 0);
    }

    @Test public void queuedPatchIsValidatedAndCopiedBeforeCallerMutation() throws Exception {
        Fixture f = fixture(); NativePreferences p = f.preferences(); await(p.ready());
        SettableFuture<Void> gate = SettableFuture.create(); CountDownLatch entered = new CountDownLatch(1);
        ListenableFuture<NativePreferenceRecord> first = p.update(new JSONObject(), DIRECT, (before, after) -> { entered.countDown(); return gate; });
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            JSONObject patch = new JSONObject().put("volume", .25);
            ListenableFuture<NativePreferenceRecord> next = p.update(patch);
            patch.put("volume", .9); patch.put("password", "fixture-only");
            assertTrue(failed(p.update(new JSONObject().put("wifiOnly", "false"))) instanceof IllegalArgumentException);
            gate.set(null); await(first); assertEquals(.25, (Double) await(next).values.get("volume"), 0);
            assertEquals(true, p.requireReady().values.get("wifiOnly"));
        } finally { gate.set(null); }
    }

    @Test public void cancellingAWaiterDoesNotDiscardAnAcceptedSettingWrite() throws Exception {
        Fixture f = fixture(); NativePreferences p = f.preferences(); await(p.ready());
        SettableFuture<Void> gate = SettableFuture.create(); CountDownLatch entered = new CountDownLatch(1);
        ListenableFuture<NativePreferenceRecord> first = p.update(new JSONObject(), DIRECT, (before, after) -> { entered.countDown(); return gate; });
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            ListenableFuture<NativePreferenceRecord> accepted = p.update(new JSONObject().put("volume", .3)); accepted.cancel(false);
            ListenableFuture<NativePreferenceRecord> last = p.update(new JSONObject().put("language", "fr"));
            gate.set(null); await(first); await(last);
            assertEquals(.3, (Double) p.requireReady().values.get("volume"), 0);
        } finally { gate.set(null); }
    }

    @Test public void rejectedPreflightExecutorDoesNotCommitOrStallTheQueue() throws Exception {
        Fixture f = fixture(); NativePreferences p = f.preferences(); await(p.ready());
        Executor rejected = runnable -> { throw new java.util.concurrent.RejectedExecutionException("fixture executor"); };
        failed(p.update(new JSONObject().put("volume", .2), rejected, (before, after) -> Futures.immediateVoidFuture()));
        assertEquals(0, p.requireReady().revision);
        assertEquals(.7, (Double) await(p.update(new JSONObject().put("volume", .7))).values.get("volume"), 0);
    }

    @Test public void throwingOrRejectedPostCommitEffectsKeepTheirCommittedSnapshots() throws Exception {
        Fixture f = fixture(); NativePreferences p = f.preferences(); await(p.ready());
        assertTrue(failed(p.update(new JSONObject().put("volume", .2), DIRECT,
            (before, after) -> { throw new IllegalStateException("fixture effect throw"); })) instanceof NativePreferences.CommittedEffectException);
        AtomicInteger dispatches = new AtomicInteger();
        Executor secondRejected = runnable -> {
            if (dispatches.incrementAndGet() == 1) runnable.run();
            else throw new java.util.concurrent.RejectedExecutionException("fixture post-commit executor");
        };
        assertTrue(failed(p.update(new JSONObject().put("language", "fr"), secondRejected,
            (before, after) -> Futures.immediateVoidFuture())) instanceof NativePreferences.CommittedEffectException);
        await(p.update(new JSONObject().put("motion", false)));
        assertEquals(.2, (Double) p.requireReady().values.get("volume"), 0);
        assertEquals("fr", p.requireReady().values.get("language")); assertEquals(false, p.requireReady().values.get("motion"));
    }

    @Test public void idleWorkerReleasesItsThreadAndRestartsForALaterSave() throws Exception {
        Fixture f = fixture(); NativePreferences p = f.preferences(); await(p.ready());
        AtomicReference<Thread> worker = new AtomicReference<>();
        await(p.update(new JSONObject().put("volume", .4), DIRECT, (before, after) -> {
            worker.set(Thread.currentThread()); return Futures.immediateVoidFuture();
        }));
        assertNotNull(worker.get()); assertNotSame(Thread.currentThread(), worker.get());
        long deadline = android.os.SystemClock.elapsedRealtime() + 40_000;
        while (worker.get().isAlive() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100);
        assertFalse("Idle preference worker must release its native stack", worker.get().isAlive());
        assertEquals("fr", await(p.update(new JSONObject().put("language", "fr"))).values.get("language"));
        assertEquals(.4, (Double) p.requireReady().values.get("volume"), 0);
    }

    private Fixture fixture() { Fixture f = new Fixture(); fixtures.add(f); return f; }
    private static NativePreferenceRecord await(ListenableFuture<NativePreferenceRecord> future) throws Exception { return future.get(6, TimeUnit.SECONDS); }
    private static Throwable failed(ListenableFuture<?> future) throws Exception {
        try { future.get(6, TimeUnit.SECONDS); fail("Expected operation failure"); return null; }
        catch (ExecutionException error) { return error.getCause(); }
    }

    /** Tests own contexts/profiles/DBs; no repository reset/cleanup API is added to production. */
    private static final class Fixture implements AutoCloseable {
        final Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final String prefix = "aurora-preference-coordinator-" + java.util.UUID.randomUUID() + "-";
        final AtomicInteger pathReads = new AtomicInteger(), profileReads = new AtomicInteger();
        final CountDownLatch pathSeen = new CountDownLatch(1);
        volatile boolean forbidLegacy;
        final Context context = alias();
        MusicStore helper;
        Context alias() {
            return new ContextWrapper(base) {
                @Override public Context getApplicationContext() { return this; }
                @Override public File getDatabasePath(String name) {
                    assertNotEquals("Main-thread database path resolution", Looper.getMainLooper(), Looper.myLooper());
                    pathReads.incrementAndGet(); pathSeen.countDown(); return base.getDatabasePath(prefix + name);
                }
                @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory) {
                    return base.openOrCreateDatabase(prefix + name, mode, factory);
                }
                @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler handler) {
                    return base.openOrCreateDatabase(prefix + name, mode, factory, handler);
                }
                @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                    assertNotEquals("Main-thread legacy read", Looper.getMainLooper(), Looper.myLooper());
                    assertFalse("Legacy reread after canonical initialization", forbidLegacy);
                    profileReads.incrementAndGet(); return guarded(base.getSharedPreferences(prefix + name, mode), name);
                }
            };
        }
        NativePreferences preferences() { return NativePreferences.forDatabase(context, NAME); }
        SQLiteDatabase database() { if (helper == null) helper = new MusicStore(context, NAME); return helper.getWritableDatabase(); }
        SharedPreferences profile(String profile) { return base.getSharedPreferences(prefix + profile + "-" + NAME, Context.MODE_PRIVATE); }
        @Override public void close() {
            if (helper != null) helper.close(); base.deleteDatabase(prefix + NAME);
            for (String profile : new String[]{"playback-options", "appearance", "connection"}) base.deleteSharedPreferences(prefix + profile + "-" + NAME);
        }
        private SharedPreferences guarded(SharedPreferences source, String profile) {
            return new SharedPreferences() {
                private void check(String key) {
                    if (profile.startsWith("connection")) assertEquals("wifiOnly", key);
                    assertFalse("Credential field requested", key.equals("password") || key.equals("apiKey") || key.equals("url"));
                }
                public Map<String, ?> getAll() { throw new AssertionError("Must not enumerate credentials"); }
                public String getString(String key, String fallback) { check(key); return source.getString(key, fallback); }
                public Set<String> getStringSet(String key, Set<String> fallback) { throw new AssertionError("No set fields"); }
                public int getInt(String key, int fallback) { check(key); return source.getInt(key, fallback); }
                public long getLong(String key, long fallback) { check(key); return source.getLong(key, fallback); }
                public float getFloat(String key, float fallback) { check(key); return source.getFloat(key, fallback); }
                public boolean getBoolean(String key, boolean fallback) { check(key); return source.getBoolean(key, fallback); }
                public boolean contains(String key) { check(key); return source.contains(key); }
                public Editor edit() { throw new AssertionError("Migration must not write legacy stores"); }
                public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { throw new AssertionError("No legacy observers"); }
                public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { throw new AssertionError("No legacy observers"); }
            };
        }
    }
}
