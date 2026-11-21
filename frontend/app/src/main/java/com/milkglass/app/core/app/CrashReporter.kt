package com.milkglass.app.core.app

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

object CrashReporter {
    private const val PENDING_FILE_NAME = "pending_crash_report.json"
    private const val MAX_STACK_TRACE_CHARS = 11_000
    private const val MAX_PENDING_AGE_MS = 7L * 24L * 60L * 60L * 1000L

    private val installed = AtomicBoolean(false)

    @Volatile
    private var currentScreen: String = ""

    fun recordCurrentScreen(screen: String) {
        currentScreen = screen.trim().take(120)
    }

    fun install(context: Context) {
        val appContext = context.applicationContext
        if (!installed.compareAndSet(false, true)) return

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val payload = buildCrashPayloadJson(
                    throwable = throwable,
                    threadName = thread.name.orEmpty(),
                    appVersion = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                    deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                    androidVersion = "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
                    screen = currentScreen,
                    isFatal = true
                )
                pendingFile(appContext).writeText(payload)
            }
            previousHandler?.uncaughtException(thread, throwable)
        }

        flushPendingAsync(appContext)
    }

    fun flushPendingAsync(context: Context) {
        val appContext = context.applicationContext
        val file = pendingFile(appContext)
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

    private fun pendingFile(context: Context): File = File(context.cacheDir, PENDING_FILE_NAME)

    internal fun formatStackTrace(throwable: Throwable): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val full = sw.toString().trim()
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
        val exceptionMessage = (rootCause.message ?: throwable.message ?: "").trim().take(1000)
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
}
