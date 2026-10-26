package com.aurora.musicnext;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.appwidget.AppWidgetManager;
import android.os.Handler;
import android.os.Looper;
import android.content.ServiceConnection;
import androidx.core.content.ContextCompat;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real private SQL hydration and production widget refresh, without a launcher or user data. */
@RunWith(AndroidJUnit4.class)
public final class WidgetReadinessInstrumentedTest {
    private final Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final String prefix = "aurora-widget-ready-" + java.util.UUID.randomUUID() + "-";
    private final CountDownLatch pathSeen = new CountDownLatch(1);
    private final AtomicInteger paints = new AtomicInteger();
    private final AtomicInteger binds = new AtomicInteger();
    private final AtomicInteger unbinds = new AtomicInteger();
    private final CountDownLatch binding = new CountDownLatch(1);
    private volatile boolean stallBinding;
    private volatile CountDownLatch diskGate;
    private final Context context = new ContextWrapper(base) {
        @Override public Context getApplicationContext() { return this; }
        @Override public File getDatabasePath(String name) {
            pathSeen.countDown(); CountDownLatch gate = diskGate;
            if (gate != null) {
                try { if (!gate.await(12, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture disk gate timed out"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            }
            return base.getDatabasePath(prefix + name);
        }
        @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory) { return base.openOrCreateDatabase(prefix + name, mode, factory); }
        @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler handler) { return base.openOrCreateDatabase(prefix + name, mode, factory, handler); }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) { return base.getSharedPreferences(prefix + name, mode); }
        @Override public Object getSystemService(String name) {
            if (Context.APPWIDGET_SERVICE.equals(name)) paints.incrementAndGet();
            return super.getSystemService(name);
        }
        @Override public boolean bindService(Intent service, ServiceConnection connection, int flags) {
            binds.incrementAndGet(); binding.countDown();
            if (stallBinding) return true;
            throw new AssertionError("Timed-out widget must never bind playback");
        }
        @Override public void unbindService(ServiceConnection connection) {
            if (stallBinding) { unbinds.incrementAndGet(); return; }
            super.unbindService(connection);
        }
    };
    private MusicStore store;
    private SQLiteDatabase database() { if (store == null) store = new MusicStore(context); return store.getWritableDatabase(); }
    @After public void close() {
        releaseDisk();
        if (store != null) store.close(); base.deleteDatabase(prefix + "aurora-mobile.db");
        for (String name : new String[]{"playback-options", "appearance", "connection", "widget_snapshot"}) base.deleteSharedPreferences(prefix + name);
    }

    @Test public void coldRefreshWaitsForHydrationAndCoalescesUntilPaint() throws Exception {
        SQLiteDatabase db = database(); db.beginTransaction();
        AtomicReference<ListenableFuture<Void>> first = new AtomicReference<>(), second = new AtomicReference<>();
        try {
            main(() -> { first.set(MusicWidgetProvider.refresh(context)); second.set(MusicWidgetProvider.refresh(context)); });
            assertTrue(pathSeen.await(3, TimeUnit.SECONDS));
            assertFalse(first.get().isDone()); assertFalse(second.get().isDone()); assertEquals(0, paints.get());
        } finally { db.endTransaction(); }
        first.get().get(6, TimeUnit.SECONDS); second.get().get(6, TimeUnit.SECONDS);
        assertEquals("Only one paint for concurrent deferred refreshes", 1, paints.get());
    }

    @Test public void cancellingOneRefreshDoesNotCancelOtherWaitersOrHydration() throws Exception {
        SQLiteDatabase db = database(); db.beginTransaction();
        AtomicReference<ListenableFuture<Void>> first = new AtomicReference<>(), second = new AtomicReference<>();
        try {
            main(() -> { first.set(MusicWidgetProvider.refresh(context)); second.set(MusicWidgetProvider.refresh(context)); });
            first.get().cancel(false); assertFalse(second.get().isCancelled()); assertFalse(second.get().isDone());
        } finally { db.endTransaction(); }
        second.get().get(6, TimeUnit.SECONDS);
        assertTrue((Boolean) NativePreferences.forApplication(context).requireReady().values.get("wifiOnly"));
        assertEquals(1, paints.get());
    }

    @Test public void failedHydrationDoesNotPaintDefaultContent() throws Exception {
        database().execSQL("CREATE TRIGGER refuse_widget_init BEFORE UPDATE ON native_preferences BEGIN SELECT RAISE(ABORT,'fixture'); END");
        AtomicReference<ListenableFuture<Void>> result = new AtomicReference<>(); main(() -> result.set(MusicWidgetProvider.refresh(context)));
        try { result.get().get(6, TimeUnit.SECONDS); fail("Failed initialization must not paint"); }
        catch (ExecutionException expected) { assertNotNull(expected.getCause()); }
        assertEquals(0, paints.get());
    }

    @Test public void realOrderedUpdateFinishesOnlyAfterHydrationAndUpdatesOnce() throws Exception {
        SQLiteDatabase db = database(); db.beginTransaction();
        try (BroadcastProbe probe = new BroadcastProbe(false)) {
            try {
                probe.send(); assertTrue(probe.received.await(3, TimeUnit.SECONDS));
                assertEquals(0, probe.updated.get()); assertEquals(0, probe.finished.get());
            } finally { db.endTransaction(); }
            assertTrue(probe.done.await(6, TimeUnit.SECONDS));
            assertEquals(1, probe.updated.get()); assertEquals(1, probe.finished.get());
        }
    }

    @Test public void failedOrderedUpdateFinishesWithoutReplacingWidgetContent() throws Exception {
        database().execSQL("CREATE TRIGGER refuse_broadcast_init BEFORE UPDATE ON native_preferences BEGIN SELECT RAISE(ABORT,'fixture'); END");
        try (BroadcastProbe probe = new BroadcastProbe(false)) {
            probe.send(); assertTrue(probe.done.await(6, TimeUnit.SECONDS));
            assertEquals(0, probe.updated.get()); assertEquals(1, probe.finished.get()); assertEquals(0, paints.get());
        }
    }

    @Test public void timedOutOrderedControlNeverBindsOrPlaysWhenHydrationArrivesLate() throws Exception {
        diskGate = new CountDownLatch(1);
        try (BroadcastProbe probe = new BroadcastProbe(true)) {
            try {
                long sent = android.os.SystemClock.elapsedRealtime();
                probe.send(); assertTrue(probe.received.await(3, TimeUnit.SECONDS));
                assertTrue("The original eight-second deadline must finish the real broadcast", probe.done.await(10, TimeUnit.SECONDS));
                assertTrue("An early database failure is not a timeout pass", android.os.SystemClock.elapsedRealtime() - sent >= 7500);
                assertEquals(0, binds.get()); assertEquals(1, probe.finished.get());
            } finally { releaseDisk(); }
            NativePreferences.forApplication(context).ready().get(6, TimeUnit.SECONDS);
            // Hydration completion queued its callback before this main-loop barrier.
            main(() -> {}); main(() -> {});
            assertEquals(0, binds.get()); assertEquals(1, probe.finished.get());
        }
    }

    @Test public void controllerBindingSharesTheOriginalDeadlineAndIsReleasedOnTimeout() throws Exception {
        diskGate = new CountDownLatch(1); stallBinding = true;
        ListenableFuture<NativePreferenceRecord> initialization = NativePreferences.forApplication(context).ready();
        try (BroadcastProbe probe = new BroadcastProbe(true)) {
            try {
                probe.send(); assertTrue(probe.received.await(3, TimeUnit.SECONDS));
                // Intentionally spend part of the one real broadcast deadline in SQL hydration.
                assertFalse(new CountDownLatch(1).await(3, TimeUnit.SECONDS));
            } finally { releaseDisk(); }
            initialization.get(6, TimeUnit.SECONDS);
            assertTrue(binding.await(2, TimeUnit.SECONDS));
            assertTrue("Binding must not get a new eight-second lifetime", probe.done.await(6, TimeUnit.SECONDS));
            main(() -> {}); main(() -> {});
            assertEquals(1, binds.get()); assertEquals(1, unbinds.get()); assertEquals(1, probe.finished.get());
        }
    }
    private void releaseDisk() {
        CountDownLatch gate = diskGate; diskGate = null; if (gate != null) gate.countDown();
    }

    /** Framework-managed ordered broadcasts provide real PendingResults; only routing uses a private action. */
    private final class BroadcastProbe implements AutoCloseable {
        final String route = "com.aurora.musicnext.WIDGET_TEST_" + java.util.UUID.randomUUID();
        final CountDownLatch received = new CountDownLatch(1), done = new CountDownLatch(1);
        final AtomicInteger updated = new AtomicInteger(), finished = new AtomicInteger();
        final MusicWidgetProvider receiver;
        BroadcastProbe(boolean control) {
            receiver = new MusicWidgetProvider() {
                @Override public void onReceive(Context unused, Intent routed) {
                    Intent request = control ? new Intent("com.aurora.musicnext.WIDGET_CONTROL").putExtra("control", "toggle")
                        : new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, new int[]{Integer.MAX_VALUE});
                    super.onReceive(context, request); received.countDown();
                }
                @Override public void onUpdate(Context supplied, AppWidgetManager manager, int[] ids) {
                    updated.incrementAndGet(); super.onUpdate(supplied, manager, ids);
                }
            };
            ContextCompat.registerReceiver(base, receiver, new IntentFilter(route), ContextCompat.RECEIVER_NOT_EXPORTED);
        }
        void send() {
            base.sendOrderedBroadcast(new Intent(route).setPackage(base.getPackageName()), null, new BroadcastReceiver() {
                @Override public void onReceive(Context ignored, Intent intent) { finished.incrementAndGet(); done.countDown(); }
            }, new Handler(Looper.getMainLooper()), 0, null, null);
        }
        @Override public void close() { base.unregisterReceiver(receiver); }
    }

    private static void main(Runnable work) { InstrumentationRegistry.getInstrumentation().runOnMainSync(work); }
}
