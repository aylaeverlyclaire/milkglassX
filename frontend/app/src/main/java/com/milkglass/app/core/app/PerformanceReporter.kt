package com.milkglass.app.core.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import com.milkglass.app.BuildConfig
import id.my.milkglass.EngineRuntime
import id.my.milkglass.MilkglassEndpoints
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicBoolean

data class DailyPerfSnapshot(
    val startupMs: Long,
    val avgTtffMs: Long,
    val maxTtffMs: Long,
    val playAttempts: Int,
    val playFailures: Int,
    val crashCount: Int,
    val memoryPeakMb: Int
)

object PerformanceReporter {
    private const val PREFS_NAME = "milkglass_perf_telemetry_v1"
    private const val KEY_LAST_REPORT_MS = "last_report_ms"
    private const val KEY_STABILIZED_VERSION = "stabilized_version_code"
    private const val KEY_CONSECUTIVE_STABLE_DAYS = "consecutive_stable_days"
    private const val KEY_STARTUP_MS = "startup_ms"
    private const val KEY_TTFF_SUM_MS = "ttff_sum_ms"
    private const val KEY_TTFF_COUNT = "ttff_count"
    private const val KEY_TTFF_MAX_MS = "ttff_max_ms"
    private const val KEY_PLAY_ATTEMPTS = "play_attempts"
    private const val KEY_PLAY_FAILURES = "play_failures"
    private const val KEY_CRASH_COUNT = "crash_count"
    private const val KEY_MEMORY_PEAK_MB = "memory_peak_mb"

    internal const val REPORT_INTERVAL_MS = 24L * 60L * 60L * 1000L
    internal const val REQUIRED_STABLE_DAYS = 3
    internal const val STABLE_STARTUP_MAX_MS = 1_500L
    internal const val STABLE_AVG_TTFF_MAX_MS = 1_200L

    private val installed = AtomicBoolean(false)
    private val firstResumeRecorded = AtomicBoolean(false)
    private val flushInFlight = AtomicBoolean(false)

    @Volatile
    private var appStartElapsedMs: Long = 0L

    fun install(application: Application) {
        if (!installed.compareAndSet(false, true)) return
        appStartElapsedMs = SystemClock.elapsedRealtime()
        val appContext = application.applicationContext

        // Reset stabilization automatically when app updates to a new versionCode
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stabilizedVersion = prefs.getInt(KEY_STABILIZED_VERSION, -1)
        if (stabilizedVersion != -1 && stabilizedVersion != BuildConfig.VERSION_CODE) {
            prefs.edit()
                .remove(KEY_STABILIZED_VERSION)
                .putInt(KEY_CONSECUTIVE_STABLE_DAYS, 0)
                .apply()
        }

        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                CrashReporter.recordCurrentScreen(activity.javaClass.simpleName)
                if (firstResumeRecorded.compareAndSet(false, true)) {
                    val startupMs = (SystemClock.elapsedRealtime() - appStartElapsedMs).coerceIn(0L, 120_000L)
                    recordStartupSample(appContext, startupMs)
                }
                sampleMemory(appContext)
                maybeFlushDailyAsync(appContext)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    fun recordPlaybackResult(context: Context, ttffMs: Long, failed: Boolean) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val attempts = prefs.getInt(KEY_PLAY_ATTEMPTS, 0) + 1
        val failures = prefs.getInt(KEY_PLAY_FAILURES, 0) + if (failed) 1 else 0
        val editor = prefs.edit()
            .putInt(KEY_PLAY_ATTEMPTS, attempts)
            .putInt(KEY_PLAY_FAILURES, failures)
        if (!failed && ttffMs > 0L) {
            val sum = prefs.getLong(KEY_TTFF_SUM_MS, 0L) + ttffMs
            val count = prefs.getInt(KEY_TTFF_COUNT, 0) + 1
            val max = maxOf(prefs.getLong(KEY_TTFF_MAX_MS, 0L), ttffMs)
            editor
                .putLong(KEY_TTFF_SUM_MS, sum)
                .putInt(KEY_TTFF_COUNT, count)
                .putLong(KEY_TTFF_MAX_MS, max)
        }
        // Re-enable daily reporting if a playback failure or slow regression occurs
        if (failed || ttffMs > STABLE_AVG_TTFF_MAX_MS) {
            editor
                .remove(KEY_STABILIZED_VERSION)
                .putInt(KEY_CONSECUTIVE_STABLE_DAYS, 0)
        }
        editor.apply()
    }

    fun onCrashDetected(context: Context) {
        runCatching {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val crashes = prefs.getInt(KEY_CRASH_COUNT, 0) + 1
            prefs.edit()
                .putInt(KEY_CRASH_COUNT, crashes)
                .remove(KEY_STABILIZED_VERSION)
                .putInt(KEY_CONSECUTIVE_STABLE_DAYS, 0)
                .commit()
        }
    }

    private fun recordStartupSample(context: Context, startupMs: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prev = prefs.getLong(KEY_STARTUP_MS, 0L)
        val next = if (prev == 0L) startupMs else (prev + startupMs) / 2L
        prefs.edit().putLong(KEY_STARTUP_MS, next).apply()
    }

    private fun sampleMemory(context: Context) {
        val rt = Runtime.getRuntime()
        val usedMb = ((rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L)).toInt().coerceAtLeast(0)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val peak = prefs.getInt(KEY_MEMORY_PEAK_MB, 0)
        if (usedMb > peak) {
            prefs.edit().putInt(KEY_MEMORY_PEAK_MB, usedMb).apply()
        }
    }

    fun maybeFlushDailyAsync(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stabilizedVersion = prefs.getInt(KEY_STABILIZED_VERSION, -1)
        val lastReportMs = prefs.getLong(KEY_LAST_REPORT_MS, 0L)

        if (!shouldSendDailyReport(
                stabilizedVersionCode = stabilizedVersion,
                currentVersionCode = BuildConfig.VERSION_CODE,
                lastReportMs = lastReportMs,
                nowMs = nowMs
            )
        ) {
            return
        }

        // First install: initialize window start so the first report covers a full 24h cycle
        if (lastReportMs == 0L) {
            prefs.edit().putLong(KEY_LAST_REPORT_MS, nowMs).apply()
            return
        }

        if (!flushInFlight.compareAndSet(false, true)) return

        Thread(
            {
                try {
                    val ttffCount = prefs.getInt(KEY_TTFF_COUNT, 0)
                    val avgTtffMs = if (ttffCount > 0) prefs.getLong(KEY_TTFF_SUM_MS, 0L) / ttffCount else 0L
                    val snapshot = DailyPerfSnapshot(
                        startupMs = prefs.getLong(KEY_STARTUP_MS, 0L),
                        avgTtffMs = avgTtffMs,
                        maxTtffMs = prefs.getLong(KEY_TTFF_MAX_MS, 0L),
                        playAttempts = prefs.getInt(KEY_PLAY_ATTEMPTS, 0),
                        playFailures = prefs.getInt(KEY_PLAY_FAILURES, 0),
                        crashCount = prefs.getInt(KEY_CRASH_COUNT, 0),
                        memoryPeakMb = prefs.getInt(KEY_MEMORY_PEAK_MB, 0)
                    )
                    val stableToday = isSnapshotStable(snapshot)
                    val prevStableDays = prefs.getInt(KEY_CONSECUTIVE_STABLE_DAYS, 0)
                    val nextStableDays = nextConsecutiveStableDays(prevStableDays, stableToday)

                    val payload = buildPerfPayloadJson(
                        snapshot = snapshot,
                        isStable = stableToday,
                        consecutiveStableDays = nextStableDays,
                        appVersion = BuildConfig.VERSION_NAME,
                        versionCode = BuildConfig.VERSION_CODE,
                        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "Android" },
                        androidVersion = "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
                    )
                    val endpoint = "${MilkglassEndpoints.DEFAULT_BACKEND_BASE_URL.trimEnd('/')}/v1/perf-report"
                    val resp = EngineRuntime.get(appContext).httpTextClient.postJson(
                        url = endpoint,
                        jsonBody = payload
                    )
                    if (resp.code in 200..299) {
                        val editor = prefs.edit()
                            .putLong(KEY_LAST_REPORT_MS, nowMs)
                            .putInt(KEY_CONSECUTIVE_STABLE_DAYS, nextStableDays)
                            .putLong(KEY_STARTUP_MS, 0L)
                            .putLong(KEY_TTFF_SUM_MS, 0L)
                            .putInt(KEY_TTFF_COUNT, 0)
                            .putLong(KEY_TTFF_MAX_MS, 0L)
                            .putInt(KEY_PLAY_ATTEMPTS, 0)
                            .putInt(KEY_PLAY_FAILURES, 0)
                            .putInt(KEY_CRASH_COUNT, 0)
                            .putInt(KEY_MEMORY_PEAK_MB, 0)
                        if (nextStableDays >= REQUIRED_STABLE_DAYS) {
                            editor.putInt(KEY_STABILIZED_VERSION, BuildConfig.VERSION_CODE)
                        }
                        editor.apply()
                    }
                } catch (_: Throwable) {
                    // Retry on next launch
                } finally {
                    flushInFlight.set(false)
                }
            },
            "milkglass-perf-flush"
        ).apply {
            isDaemon = true
            start()
        }
    }

    internal fun isSnapshotStable(snapshot: DailyPerfSnapshot): Boolean {
        if (snapshot.crashCount > 0) return false
        if (snapshot.playFailures > 0) return false
        if (snapshot.startupMs > STABLE_STARTUP_MAX_MS) return false
        if (snapshot.playAttempts > 0 && snapshot.avgTtffMs > STABLE_AVG_TTFF_MAX_MS) return false
        return true
    }

    internal fun nextConsecutiveStableDays(previousDays: Int, stableToday: Boolean): Int =
        if (stableToday) previousDays + 1 else 0

    internal fun shouldSendDailyReport(
        stabilizedVersionCode: Int,
        currentVersionCode: Int,
        lastReportMs: Long,
        nowMs: Long
    ): Boolean {
        if (stabilizedVersionCode == currentVersionCode) return false
        if (lastReportMs == 0L) return true
        return (nowMs - lastReportMs) >= REPORT_INTERVAL_MS
    }

    internal fun buildPerfPayloadJson(
        snapshot: DailyPerfSnapshot,
        isStable: Boolean,
        consecutiveStableDays: Int,
        appVersion: String,
        versionCode: Int,
        deviceModel: String,
        androidVersion: String
    ): String = buildJsonObject {
        put("app_version", appVersion.trim().take(64))
        put("version_code", versionCode.coerceAtLeast(0))
        put("device_model", deviceModel.trim().ifBlank { "Android" }.take(120))
        put("android_version", androidVersion.trim().take(64))
        put("startup_ms", snapshot.startupMs.coerceAtLeast(0L))
        put("avg_ttff_ms", snapshot.avgTtffMs.coerceAtLeast(0L))
        put("max_ttff_ms", snapshot.maxTtffMs.coerceAtLeast(0L))
        put("play_attempts", snapshot.playAttempts.coerceAtLeast(0))
        put("play_failures", snapshot.playFailures.coerceAtLeast(0))
        put("crash_count", snapshot.crashCount.coerceAtLeast(0))
        put("memory_peak_mb", snapshot.memoryPeakMb.coerceAtLeast(0))
        put("is_stable", isStable)
        put("consecutive_stable_days", consecutiveStableDays.coerceAtLeast(0))
    }.toString()
}
