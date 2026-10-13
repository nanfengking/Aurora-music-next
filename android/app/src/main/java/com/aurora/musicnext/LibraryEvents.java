package com.aurora.musicnext;

import android.os.Handler;
import android.os.Looper;
import java.lang.ref.WeakReference;

/** Coalesce worker notifications; an absent/background WebView does no database polling. */
final class LibraryEvents {
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static WeakReference<Runnable> listener = new WeakReference<>(null);
    private static final Runnable pulse = () -> { Runnable value = listener.get(); if (value != null) value.run(); };
    static void listen(Runnable value) { listener = new WeakReference<>(value); }
    static void changed() { handler.removeCallbacks(pulse); handler.postDelayed(pulse, 200); }
}
