package com.milkglass.app.core.app

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Merekam penyebab aplikasi berhenti, lalu menampilkannya saat aplikasi dibuka lagi
 * agar bisa di-screenshot. Dua sumber: exception Java yang tak tertangkap, dan
 * alasan keluar proses dari sistem (ANR, kehabisan memori, crash native; Android 11+).
 */
object CrashReporter {
    private const val FILE = "last_crash.txt"
    private const val PREFS = "crash_reporter"
    private val headerSecretRegex = Regex("(?i)(authorization|cookie|set-cookie|x-api-key|x-milkglass-sig|access_token|refresh_token|bearer)\\s*[:=]\\s*[^\\s,;\"]+")
    private val querySecretRegex = Regex("(?i)([?&](?:token|sig|signature|key|auth|jwt|session|email)=)[^&\\s\"'<>]+")
    private val jwtRegex = Regex("eyJ[A-Za-z0-9_-]+\\.eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
    private val emailRegex = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

    internal fun sanitize(raw: String): String = raw
        .replace(headerSecretRegex, "$1=[REDACTED]")
        .replace(querySecretRegex, "$1[REDACTED]")
        .replace(jwtRegex, "[REDACTED_JWT]")
        .replace(emailRegex, "[REDACTED_EMAIL]")

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                File(app.filesDir, FILE).writeText(sanitize("thread: ${thread.name}\n$sw").take(5000))
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun pendingReport(ctx: Context): String? {
        val exit = exitReason(ctx)
        val crash = takeFile(File(ctx.filesDir, FILE))
        val stall = takeFile(File(ctx.filesDir, StallWatchdog.FILE))
        return listOfNotNull(crash, stall, exit).joinToString("\n---\n").ifBlank { null }
    }

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
