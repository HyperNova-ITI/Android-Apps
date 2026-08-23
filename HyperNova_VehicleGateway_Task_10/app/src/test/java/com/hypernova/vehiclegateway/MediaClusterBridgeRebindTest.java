package com.hypernova.vehiclegateway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Focused plain-JVM tests for Worker C's MediaClusterBridge binding state
 * machine.
 *
 * They drive the real ServiceConnection callbacks through overridden
 * framework hooks and prove that a disconnection releases the stale binding
 * and permits exactly one controlled retry without duplicate scheduling,
 * and that stop() cancels the retry and stays idempotent. No android.*
 * calls execute on these paths (hooks replace bind/unbind/schedule/log).
 */
public final class MediaClusterBridgeRebindTest {

    @Test
    public void disconnectReleasesStaleBindingAndPermitsSingleRetry() {
        TestBridge bridge = new TestBridge();

        // Duplicate start requests must not issue extra binds.
        bridge.start();
        bridge.start();

        assertEquals(1, bridge.bindAttempts.get());
        assertEquals(0, bridge.unbindCalls.get());

        bridge.serviceConnection.onServiceDisconnected(null);

        assertEquals(
                "stale binding must be released on disconnect",
                1,
                bridge.unbindCalls.get());
        assertEquals(
                "exactly one retry may be pending",
                1,
                bridge.scheduledCount.get());

        /*
         * A burst of loss notifications before the retry fires must be
         * collapsed into the single already-scheduled retry; releasing an
         * already-released binding must not unbind again.
         */
        bridge.serviceConnection.onServiceDisconnected(null);
        bridge.serviceConnection.onBindingDied(null);

        assertEquals(1, bridge.scheduledCount.get());
        assertEquals(1, bridge.unbindCalls.get());

        // The released binding permits a real rebind attempt.
        assertTrue(bridge.runPendingRetry());
        assertEquals(2, bridge.bindAttempts.get());

        // No duplicate retries remain queued.
        assertFalse(bridge.runPendingRetry());
        assertEquals(2, bridge.bindAttempts.get());
    }

    @Test
    public void rejectedBindSchedulesSingleControlledRetry() {
        TestBridge bridge = new TestBridge();
        bridge.nextBindResults.add(Boolean.FALSE);

        bridge.start();

        assertEquals(1, bridge.bindAttempts.get());
        assertEquals(
                "a rejected bind holds no binding to release",
                0,
                bridge.unbindCalls.get());
        assertEquals(1, bridge.scheduledCount.get());
        assertEquals(1, bridge.warnings.size());

        assertTrue(bridge.runPendingRetry());

        assertEquals("controlled retry must rebind", 2, bridge.bindAttempts.get());
        assertFalse(bridge.runPendingRetry());
    }

    @Test
    public void stopCancelsPendingRetryAndStaysIdempotent() {
        TestBridge bridge = new TestBridge();

        bridge.start();
        bridge.serviceConnection.onServiceDisconnected(null);

        assertEquals(1, bridge.unbindCalls.get());
        assertEquals(1, bridge.scheduledCount.get());

        bridge.stop();

        assertEquals(
                "stop must cancel scheduled work",
                1,
                bridge.cancellations.get());
        assertFalse(bridge.runPendingRetry());

        int bindsBeforeStop = bridge.bindAttempts.get();

        /*
         * Late notifications after stop() must neither reschedule nor
         * touch the already-released binding.
         */
        bridge.serviceConnection.onBindingDied(null);
        bridge.serviceConnection.onNullBinding(null);

        assertEquals(1, bridge.scheduledCount.get());
        assertEquals(1, bridge.unbindCalls.get());
        assertEquals(bindsBeforeStop, bridge.bindAttempts.get());

        // Second stop is a no-op.
        bridge.stop();

        assertEquals(1, bridge.cancellations.get());
        assertEquals(1, bridge.unbindCalls.get());
    }

    /**
     * Bridge with framework interactions replaced by recorded fakes so all
     * production state-machine code runs against pure JVM objects.
     */
    private static final class TestBridge extends MediaClusterBridge {

        final AtomicInteger bindAttempts = new AtomicInteger();
        final AtomicInteger unbindCalls = new AtomicInteger();
        final AtomicInteger scheduledCount = new AtomicInteger();
        final AtomicInteger cancellations = new AtomicInteger();

        /** Queued outcomes for successive performBind calls; default true. */
        final List<Boolean> nextBindResults = new ArrayList<>();

        final List<String> warnings = new ArrayList<>();

        private volatile Runnable lastScheduled;

        TestBridge() {
            super(null, null);
        }

        boolean runPendingRetry() {
            Runnable retry = lastScheduled;
            lastScheduled = null;

            if (retry == null) {
                return false;
            }

            retry.run();
            return true;
        }

        @Override
        boolean performBind() {
            bindAttempts.incrementAndGet();

            return nextBindResults.isEmpty()
                    ? Boolean.TRUE
                    : nextBindResults.remove(0);
        }

        @Override
        void performUnbind() {
            unbindCalls.incrementAndGet();
        }

        @Override
        void scheduleRetry(Runnable retry) {
            scheduledCount.incrementAndGet();
            lastScheduled = retry;
        }

        @Override
        void cancelScheduledWork() {
            cancellations.incrementAndGet();
            lastScheduled = null;
        }

        @Override
        void logInfo(String message) {
            // Recorded nowhere; silence keeps tests free of android.util.Log.
        }

        @Override
        void logWarning(String message) {
            warnings.add(message);
        }

        @Override
        void logError(String message) {
            // Not asserted here.
        }
    }
}
