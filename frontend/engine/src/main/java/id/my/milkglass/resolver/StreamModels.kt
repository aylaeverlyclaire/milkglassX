package id.my.milkglass.resolver

import java.io.IOException

data class StreamSourceDto(
    val id: Int,
    val episodeId: Int,
    val type: String,               
    val quality: Int,               
    val urlPayload: String,
    val releaseGroup: String = "default",
    val subKind: String = "hardsub-id",
    val durationSec: Int = 0,
    val fileSizeBytes: Long = 0L,
    val failCount: Int = 0,
    val cooldownUntil: Long = 0L
)

data class ResolvedStream(
    val source: StreamSourceDto,
    val directUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val resolvedAtElapsedMs: Long = monotonicMillis()
) {
    fun isFresh(ttlMs: Long = DEFAULT_RESOLVED_TTL_MS, nowMs: Long = monotonicMillis()): Boolean {
        val ageMs = nowMs - resolvedAtElapsedMs
        return ageMs in 0L until ttlMs
    }

    companion object {
        const val DEFAULT_RESOLVED_TTL_MS = 20L * 60L * 1000L 

        fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L
    }
}

enum class FailureReason(val wireName: String, val reportToServer: Boolean) {
    HTTP_403("http_403", true),
    EXPIRED("expired", true),
    NOT_FOUND("not_found", true),
    TIMEOUT("timeout", false),
    CODEC("codec", false),
    DNS("dns", false)
}

class StreamResolutionException(
    val reason: FailureReason,
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

