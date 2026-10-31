package com.ose.multitimer;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Verifies the clock-delta calculation used to estimate device sleep in diagnostics. */
public final class DiagnosticSnapshotTest {
    /** elapsedRealtime includes sleep, so subtracting uptime estimates the sleeping portion. */
    @Test
    public void estimatesSleepAsElapsedGapMinusUptimeGap() {
        assertEquals(79_641L, DiagnosticSnapshot.estimateDeepSleepMillis(82_192L, 2_551L));
    }

    /** Clock anomalies or unavailable observations must not become a negative sleep claim. */
    @Test
    public void clampsNegativeDifferenceAndMarksMissingClocksUnknown() {
        assertEquals(0L, DiagnosticSnapshot.estimateDeepSleepMillis(10L, 20L));
        assertEquals(-1L, DiagnosticSnapshot.estimateDeepSleepMillis(-1L, 20L));
        assertEquals(-1L, DiagnosticSnapshot.estimateDeepSleepMillis(20L, -1L));
    }
}
