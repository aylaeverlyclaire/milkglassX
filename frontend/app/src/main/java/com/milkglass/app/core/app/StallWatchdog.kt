package com.milkglass.app.core.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Pengintai thread utama. Tiap detik ia memposting ping ke thread utama; kalau tidak
 * dijawab dalam 2 detik, ia merekam tumpukan panggilan thread utama (siapa yang
 * memblokir), kondisi memori, dan thread latar yang sedang berjalan. Laporan disimpan
 * ke file dan ditampilkan saat aplikasi dibuka lagi (lihat CrashReporter).
 */
object StallWatchdog {
    const val FILE = "last_stall.txt"
    private const val FIRST_SAMPLE_MS = 2_000L
    private const val SECOND_SAMPLE_MS = 5_000L
    private const val MAX_FILE_CHARS = 3_500
    @Volatile private var started = false

    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        val looper = Looper.getMainLooper()
        val mainThread = looper.thread
        val handler = Handler(looper)
        val bootMs = SystemClock.uptimeMillis()
        Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            while (true) {
                val answered = AtomicLong(0L)
                val sentAt = SystemClock.uptimeMillis()
                handler.post { answered.set(SystemClock.uptimeMillis()) }
                if (!nap(FIRST_SAMPLE_MS)) return@Thread
                if (answered.get() == 0L) {
                    val first = mainStack(mainThread)
                    var waited = FIRST_SAMPLE_MS
                    while (answered.get() == 0L && waited < SECOND_SAMPLE_MS) {
                        if (!nap(250L)) return@Thread
                        waited += 250L
                    }
                    val second = if (answered.get() == 0L) mainStack(mainThread) else null
                    val duration = if (answered.get() != 0L) {
                        "pulih setelah ${answered.get() - sentAt} ms"
                    } else {
                        "belum pulih setelah $waited ms"
                    }
                    record(
                        app,
                        buildString {
                            append("[macet] ").append(duration)
                            append(", ${(sentAt - bootMs) / 1000}s sejak proses mulai\n")
                            append(environment(mainThread)).append('\n')
                            append("thread utama @2s:\n").append(first)
                            if (second != null && second != first) {
                                append("thread utama @5s:\n").append(second)
                            }
                            append('\n')
                        },
                    )
                    var total = waited
                    while (answered.get() == 0L && total < 60_000L) {
                        if (!nap(500L)) return@Thread
                        total += 500L
                    }
                }
                if (!nap(1_000L)) return@Thread
            }
        }, "milkglass-watchdog").apply { isDaemon = true; start() }
    }

    private fun nap(ms: Long): Boolean = try {
        Thread.sleep(ms)
        true
    } catch (_: InterruptedException) {
        false
    }

    private fun mainStack(main: Thread): String {
        val sb = StringBuilder()
        runCatching { main.stackTrace.take(14).forEach { sb.append("  ").append(it).append('\n') } }
        return sb.toString()
    }

    private fun environment(main: Thread): String = runCatching {
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / 1_048_576L
        val maxMb = rt.maxMemory() / 1_048_576L
        val all = Thread.getAllStackTraces()
        val running = all.entries.filter {
            it.key !== main && it.key.state == Thread.State.RUNNABLE && it.value.isNotEmpty()
        }
        val lines = running.take(6).joinToString("\n") { "  ${it.key.name}: ${it.value[0]}" }
        "heap ${usedMb}/${maxMb}MB, thread ${all.size}, runnable ${running.size}\n$lines"
    }.getOrDefault("(lingkungan tidak terbaca)")

    private fun record(app: Context, text: String) {
        runCatching {
            val f = File(app.filesDir, FILE)
            val old = if (f.exists()) f.readText() else ""
            if (old.length < MAX_FILE_CHARS) f.appendText(text)
        }
    }
}
