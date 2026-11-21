package com.milkglass.app.core.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceReporterTest {

    @Test
    fun isSnapshotStable_returnsTrueWhenWithinThresholds() {
        val healthy = DailyPerfSnapshot(
            startupMs = 650L,
            avgTtffMs = 820L,
            maxTtffMs = 1_100L,
            playAttempts = 5,
            playFailures = 0,
            crashCount = 0,
            memoryPeakMb = 140
        )
        assertTrue(PerformanceReporter.isSnapshotStable(healthy))
    }

    @Test
    fun isSnapshotStable_returnsFalseOnCrashFailureOrSlowness() {
        val base = DailyPerfSnapshot(600L, 700L, 900L, 4, 0, 0, 120)
        assertFalse(PerformanceReporter.isSnapshotStable(base.copy(crashCount = 1)))
        assertFalse(PerformanceReporter.isSnapshotStable(base.copy(playFailures = 1)))
        assertFalse(PerformanceReporter.isSnapshotStable(base.copy(startupMs = 2_200L)))
        assertFalse(PerformanceReporter.isSnapshotStable(base.copy(avgTtffMs = 1_800L)))
    }

    @Test
    fun shouldSendDailyReport_stopsOnceStabilizedOnSameVersionAndResumesOnNewVersion() {
        val now = 200_000_000L
        val overOneDayAgo = now - PerformanceReporter.REPORT_INTERVAL_MS - 1_000L

        // Active before stabilization
        assertTrue(
            PerformanceReporter.shouldSendDailyReport(
                stabilizedVersionCode = -1,
                currentVersionCode = 10,
                lastReportMs = overOneDayAgo,
                nowMs = now
            )
        )
        // Stopped once stabilized on version 10
        assertFalse(
            PerformanceReporter.shouldSendDailyReport(
                stabilizedVersionCode = 10,
                currentVersionCode = 10,
                lastReportMs = overOneDayAgo,
                nowMs = now
            )
        )
        // Automatically resumes when upgraded to version 11
        assertTrue(
            PerformanceReporter.shouldSendDailyReport(
                stabilizedVersionCode = 10,
                currentVersionCode = 11,
                lastReportMs = overOneDayAgo,
                nowMs = now
            )
        )
    }

    @Test
    fun nextConsecutiveStableDays_incrementsAndResetsOnUnstableDay() {
        assertEquals(1, PerformanceReporter.nextConsecutiveStableDays(0, true))
        assertEquals(3, PerformanceReporter.nextConsecutiveStableDays(2, true))
        assertEquals(0, PerformanceReporter.nextConsecutiveStableDays(2, false))
    }
}
