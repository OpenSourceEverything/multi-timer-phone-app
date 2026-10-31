package com.ose.multitimer;

import android.content.Context;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded background collector for noncritical Android-state snapshots.
 *
 * <p>The timing service must decide and dispatch cues on its Handler thread. Platform
 * queries and snapshot inserts can be slow, so this worker captures them after the tick
 * has queued its timing work. Expected-event and transition writes deliberately remain
 * in the separate durable event ledger; a snapshot is contextual evidence, not the
 * source of truth for whether a cue was expected.</p>
 *
 * <p>The queue holds at most 32 pending samples. When overloaded, it drops additional
 * context instead of accumulating unbounded work or making the timer wait. The next
 * successful sample records the drop count. Closing the worker drains accepted samples
 * without blocking the service's main thread.</p>
 */
final class DiagnosticSnapshotWriter implements AutoCloseable {
    private static final String TAG = "DiagnosticSnapshots";
    private static final int QUEUE_CAPACITY = 32;

    private final Context appContext;
    private final ThreadPoolExecutor executor;
    private final AtomicInteger droppedSamples = new AtomicInteger();
    /** Opened and closed only on the worker thread, separate from the event-ledger helper. */
    private DiagnosticStore store;
    private volatile boolean closed;

    DiagnosticSnapshotWriter(Context context) {
        appContext = context.getApplicationContext();
        executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY), task -> {
                    Thread thread = new Thread(() -> {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                        try {
                            task.run();
                        } finally {
                            // shutdown() lets the worker finish accepted samples before close.
                            if (store != null) {
                                store.close();
                            }
                        }
                    }, "multitimer-diagnostic-snapshots");
                    return thread;
                });
    }

    /**
     * Enqueues captured boundary facts while leaving slower Android queries to the worker.
     *
     * <p>The caller supplies the timestamp and lock/idle observations from the moment it
     * noticed a lifecycle event or tick gap. The worker adds a second timestamp for its own
     * later platform reads, so the report can distinguish event time from collection time.</p>
     */
    boolean enqueue(
            String kind,
            boolean foreground,
            long observedWallMillis,
            long observedElapsedMillis,
            long tickGapMillis,
            long uptimeGapMillis,
            Boolean wakeLockHeldAtObservation,
            Boolean deviceIdleAtObservation,
            String processSessionId,
            String serviceInstanceId) {
        if (closed) {
            return false;
        }
        try {
            executor.execute(() -> collect(kind, foreground, observedWallMillis,
                    observedElapsedMillis, tickGapMillis, uptimeGapMillis,
                    wakeLockHeldAtObservation, deviceIdleAtObservation,
                    processSessionId, serviceInstanceId));
            return true;
        } catch (RejectedExecutionException fullOrClosed) {
            if (!closed) {
                int dropped = droppedSamples.incrementAndGet();
                Log.w(TAG, "Snapshot backlog full; dropped sample count=" + dropped);
            }
            return false;
        }
    }

    /** Captures current facts, then persists them; failure never escapes into timer logic. */
    private void collect(
            String kind,
            boolean foreground,
            long observedWallMillis,
            long observedElapsedMillis,
            long tickGapMillis,
            long uptimeGapMillis,
            Boolean wakeLockHeldAtObservation,
            Boolean deviceIdleAtObservation,
            String processSessionId,
            String serviceInstanceId) {
        try {
            if (store == null) {
                store = new DiagnosticStore(appContext);
            }
            long wallMillis = System.currentTimeMillis();
            long elapsedMillis = SystemClock.elapsedRealtime();
            int dropped = droppedSamples.getAndSet(0);
            if (dropped > 0) {
                store.insertSnapshot("snapshot_queue_overflow", wallMillis, elapsedMillis,
                        "{\"droppedSamples\":" + dropped + "}");
            }
            String details = "process_exit_history".equals(kind)
                    ? DiagnosticSnapshot.captureProcessExitHistory(
                            appContext, observedWallMillis, observedElapsedMillis)
                    : DiagnosticSnapshot.capture(appContext, kind, foreground,
                            observedWallMillis, observedElapsedMillis,
                            wallMillis, elapsedMillis, tickGapMillis, uptimeGapMillis,
                            wakeLockHeldAtObservation, deviceIdleAtObservation,
                            processSessionId, serviceInstanceId);
            // The row is indexed at the original event time, not at the later worker-read time.
            store.insertSnapshot(kind, observedWallMillis, observedElapsedMillis, details);
        } catch (RuntimeException error) {
            // Context may be unavailable or SQLite may reject a noncritical sample.
            Log.e(TAG, "Unable to collect diagnostic snapshot " + kind, error);
        }
    }

    /** Stops accepting samples; the worker drains its bounded queue and closes SQLite. */
    @Override
    public void close() {
        closed = true;
        executor.shutdown();
    }
}
