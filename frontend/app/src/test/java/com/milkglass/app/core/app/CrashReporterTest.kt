package com.milkglass.app.core.app

import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReporterTest {

    @Test
    fun buildCrashPayloadJson_usesRootCauseAndIncludesMetadata() {
        val root = IllegalStateException("MediaCodec decoder failed")
        val wrapped = RuntimeException("Player crashed", root)

        val json = CrashReporter.buildCrashPayloadJson(
            throwable = wrapped,
            threadName = "main",
            appVersion = "0.8.0-samehadaku-otakudesu",
            versionCode = 42,
            deviceModel = "Google Pixel 9",
            androidVersion = "15 (SDK 35)",
            screen = "PlayerScreen",
            isFatal = true
        )

        assertTrue(json.contains("IllegalStateException"))
        assertTrue(json.contains("MediaCodec decoder failed"))
        assertTrue(json.contains("0.8.0-samehadaku-otakudesu"))
        assertTrue(json.contains("PlayerScreen"))
        assertTrue(json.contains("CrashReporterTest"))
    }

    @Test
    fun formatStackTrace_truncatesVeryLongTraces() {
        val longMsg = "X".repeat(15_000)
        val formatted = CrashReporter.formatStackTrace(RuntimeException(longMsg))
        assertTrue(formatted.endsWith("... (truncated)"))
        assertTrue(formatted.length < 11_100)
    }
}
