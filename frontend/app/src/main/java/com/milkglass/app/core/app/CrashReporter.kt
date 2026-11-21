package com.milkglass.app.core.app

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.os.Build
import com.milkglass.app.BuildConfig
import id.my.milkglass.EngineRuntime
import id.my.milkglass.MilkglassEndpoints
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Merekam penyebab aplikasi berhenti (exception tak tertangkap, stall, & exit reason Android 11+),
 * menampilkannya saat aplikasi dibuka lagi, serta mengirim laporan crash ter-sanitasi secara
 * otomatis ke backend Cloudflare (`POST /v1/crash-report`).
 */
object CrashReporter {
    private const val FILE = "last_crash.txt"
    private const val PENDING_JSON_FILE = "pending_crash_report.json"
    private const val PREFS = "crash_reporter"
    private const val MAX_STACK_TRACE_CHARS = 11_000
    private const val MAX_PENDING_AGE_MS = 7L * 24L * 60L * 60L * 1000L

    private val installed = AtomicBoolean(false)

    @Volatile
    private var currentScreen: String = ""

    private val headerSecretRegex = Regex("(?i)(authorization|cookie|set-cookie|x-api-key|x-milkglass-sig|access_token|refresh_token|bearer)\\s*[:=]\\s*[^\\s,;\"]+")
    private val querySecretRegex = Regex("(?i)([?&](?:token|sig|signature|key|auth|jwt|session|email)=)[^&\\s\"'<>]+")
    private val jwtRegex = Regex("eyJ[A-Za-z0-9_-]+\\.eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
    private val emailRegex = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

    internal fun sanitize(raw: String): String = raw
        .replace(headerSecretRegex, "$1=[REDACTED]")
        .replace(querySecretRegex, "$1[REDACTED]")
        .replace(jwtRegex, "[REDACTED_JWT]")
        .replace(emailRegex, "[REDACTED_EMAIL]")

    fun recordCurrentScreen(screen: String) {
        currentScreen = screen.trim().take(120)
    }

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        if (!installed.compareAndSet(false, true)) return

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                PerformanceReporter.onCrashDetected(app)
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                File(app.filesDir, FILE).writeText(sanitize("thread: ${thread.name}\n$sw").take(5000))

                val payload = buildCrashPayloadJson(
                    throwable = error,
                    threadName = thread.name.orEmpty(),
                    appVersion = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                    deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                    androidVersion = "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
                    screen = currentScreen,
                    isFatal = true
                )
                pendingJsonFile(app).writeText(payload)
            }
            previous?.uncaughtException(thread, error)
        }

        flushPendingAsync(app)
    }

    fun flushPendingAsync(context: Context) {
        val appContext = context.applicationContext
        val file = pendingJsonFile(appContext)
        if (!file.exists()) return

        Thread(
            {
                runCatching {
                    val ageMs = System.currentTimeMillis() - file.lastModified()
                    if (ageMs > MAX_PENDING_AGE_MS) {
                        file.delete()
                        return@runCatching
                    }
                    val payload = file.readText().trim()
                    if (payload.isEmpty()) {
                        file.delete()
                        return@runCatching
                    }
                    val endpoint = "${MilkglassEndpoints.DEFAULT_BACKEND_BASE_URL.trimEnd('/')}/v1/crash-report"
                    val resp = EngineRuntime.get(appContext).httpTextClient.postJson(
                        url = endpoint,
                        jsonBody = payload
                    )
                    if (resp.code in 200..299 || resp.code == 400 || resp.code == 422) {
                        file.delete()
                    }
                }
            },
            "milkglass-crash-flush"
        ).apply {
            isDaemon = true
            start()
        }
    }

    fun pendingReport(ctx: Context): String? {
        val exit = exitReason(ctx)
        val crash = takeFile(File(ctx.filesDir, FILE))
        val stall = takeFile(File(ctx.filesDir, StallWatchdog.FILE))
        return listOfNotNull(crash, stall, exit).joinToString("\n---\n").ifBlank { null }
    }

    private fun pendingJsonFile(context: Context): File = File(context.cacheDir, PENDING_JSON_FILE)

    private fun takeFile(f: File): String? {
        if (!f.exists()) return null
        val text = runCatching { f.readText() }.getOrNull()
        runCatching { f.delete() }
        return text?.takeIf { it.isNotBlank() }
    }

    fun show(activity: Activity, report: String) {
        runCatching {
            AlertDialog.Builder(activity)
                .setTitle("Milkglass sempat berhenti")
                .setMessage("Mohon screenshot layar ini dan kirim ke pengembang.\n\n" + report.take(3400))
                .setPositiveButton("OK", null)
                .show()
        }
    }

    internal fun formatStackTrace(throwable: Throwable): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val full = sanitize(sw.toString().trim())
        return if (full.length <= MAX_STACK_TRACE_CHARS) {
            full
        } else {
            full.take(MAX_STACK_TRACE_CHARS) + "\n... (truncated)"
        }
    }

    internal fun buildCrashPayloadJson(
        throwable: Throwable,
        threadName: String,
        appVersion: String,
        versionCode: Int,
        deviceModel: String,
        androidVersion: String,
        screen: String,
        isFatal: Boolean
    ): String {
        val rootCause = generateSequence(throwable) { it.cause }.last()
        val exceptionType = (rootCause.javaClass.name ?: throwable.javaClass.name ?: "Throwable").take(200)
        val exceptionMessage = sanitize((rootCause.message ?: throwable.message ?: "").trim()).take(1000)
        val stackTrace = formatStackTrace(throwable)

        return buildJsonObject {
            put("exception_type", exceptionType)
            put("exception_message", exceptionMessage)
            put("stack_trace", stackTrace)
            put("thread_name", threadName.trim().take(120))
            put("app_version", appVersion.trim().take(64))
            put("version_code", versionCode.coerceAtLeast(0))
            put("device_model", deviceModel.trim().take(120))
            put("android_version", androidVersion.trim().take(64))
            put("screen", screen.trim().take(120))
            put("is_fatal", isFatal)
        }.toString()
    }

    private fun exitReason(ctx: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 1).firstOrNull() ?: return null
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (info.timestamp <= prefs.getLong("seen_exit_ts", 0L)) return null
            prefs.edit().putLong("seen_exit_ts", info.timestamp).apply()
            val name = when (info.reason) {
                2 -> "SIGNALED (dimatikan sinyal)"
                3 -> "LOW_MEMORY (kehabisan memori)"
                4 -> "CRASH (exception Java)"
                5 -> "CRASH_NATIVE (crash native)"
                6 -> "ANR (tidak merespons)"
                9 -> "EXCESSIVE_RESOURCE_USAGE"
                else -> return null
            }
            "alasan keluar: $name\n${info.description.orEmpty()}"
        }.getOrNull()
    }
}
