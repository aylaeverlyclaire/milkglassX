package id.my.milkglass.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import id.my.milkglass.download.EndpointHealthScorer
import id.my.milkglass.source.DeadCandidateCache
import id.my.milkglass.source.ResolvedMkvMedia
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@UnstableApi
class FailoverHttpDataSource(
    private val primaryClient: OkHttpClient,
    private val failoverClient: OkHttpClient,
    private val candidates: List<ResolvedMkvMedia>,
    private val defaultUserAgent: String,
    private val stickyIndex: AtomicInteger,
    private val hasLockedStreamBytes: AtomicBoolean,
    private val onCandidateFailure: ((ResolvedMkvMedia, Int) -> Unit)? = null
) : BaseDataSource(true) {

    class Factory(
        client: OkHttpClient,
        private val candidates: List<ResolvedMkvMedia>,
        private val defaultUserAgent: String,
        private val onCandidateFailure: ((ResolvedMkvMedia, Int) -> Unit)? = null
    ) : DataSource.Factory {
        private val sharedStickyIndex = AtomicInteger(0)
        private val sharedLockedBytes = AtomicBoolean(false)

        /**
         * Menolak HTTP 200 OK pada permintaan Range: bytes=N- (N > 0) sebelum OkHttpDataSource
         * diam-diam mengunduh dan membuang puluhan MB untuk seek mid-stream.
         */
        private val rangeGuardInterceptor = Interceptor { chain ->
            val req = chain.request()
            val range = req.header("Range")
            val rangeStart = range
                ?.substringAfter("bytes=", "")
                ?.substringBefore("-")
                ?.trim()
                ?.toLongOrNull() ?: 0L
            val resp: Response = chain.proceed(req)
            if (rangeStart > 0L && resp.code == 200) {
                resp.close()
                throw IOException("Upstream mengabaikan Range (HTTP 200 untuk offset=$rangeStart)")
            }
            resp
        }

        private val primaryClient: OkHttpClient = client.newBuilder()
            .addNetworkInterceptor(rangeGuardInterceptor)
            .build()

        private val failoverClient: OkHttpClient = client.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .addNetworkInterceptor(rangeGuardInterceptor)
            .build()

        override fun createDataSource(): DataSource {
            return FailoverHttpDataSource(
                primaryClient = primaryClient,
                failoverClient = failoverClient,
                candidates = candidates,
                defaultUserAgent = defaultUserAgent,
                stickyIndex = sharedStickyIndex,
                hasLockedStreamBytes = sharedLockedBytes,
                onCandidateFailure = onCandidateFailure
            )
        }
    }

    private var activeSource: OkHttpDataSource? = null
    private var activeUri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        if (candidates.isEmpty()) {
            throw IOException("Tidak ada kandidat stream untuk FailoverHttpDataSource")
        }
        val startIdx = stickyIndex.get().coerceIn(0, candidates.lastIndex)
        // Kunci ke 1 kandidat hanya jika stream sudah benar-benar berjalan di offset > 0 dan sudah membaca frame/payload;
        // jika gagal pada probe awal (position == 0 atau moov/Cues tail probe sebelum stream terkunci), izinkan failover penuh.
        val orderedIndices = if (dataSpec.position > 0L && hasLockedStreamBytes.get()) {
            listOf(startIdx)
        } else {
            (0 until candidates.size).map { (startIdx + it) % candidates.size }
        }
        var lastError: IOException? = null

        for ((attemptPos, idx) in orderedIndices.withIndex()) {
            val cand = candidates[idx]
            val isLast = attemptPos == orderedIndices.lastIndex
            val normalizedHeaders = linkedMapOf<String, String>()
            var explicitUa: String? = null
            var hasReferer = false

            cand.extraHeaders.forEach { (rawKey, rawVal) ->
                val k = rawKey.trim()
                val v = rawVal.trim()
                if (k.isEmpty() || v.isEmpty()) return@forEach
                when {
                    k.equals("User-Agent", ignoreCase = true) -> explicitUa = v
                    k.equals("Referer", ignoreCase = true) -> {
                        normalizedHeaders["Referer"] = v
                        hasReferer = true
                    }
                    k.equals("Cookie", ignoreCase = true) -> normalizedHeaders["Cookie"] = v
                    else -> normalizedHeaders[k] = v
                }
            }
            if (!hasReferer && !cand.referer.isNullOrBlank()) {
                normalizedHeaders["Referer"] = cand.referer
            }

            val ua = explicitUa ?: defaultUserAgent
            val callClient = if (isLast) primaryClient else failoverClient
            val delegate = OkHttpDataSource.Factory(callClient)
                .setUserAgent(ua)
                .setDefaultRequestProperties(normalizedHeaders)
                .createDataSource() as OkHttpDataSource

            val targetUri = Uri.parse(cand.directUrl)
            val candSpec = dataSpec.buildUpon().setUri(targetUri).build()
            try {
                transferInitializing(dataSpec)
                val length = delegate.open(candSpec)
                stickyIndex.set(idx)
                activeSource = delegate
                activeUri = targetUri
                EndpointHealthScorer.recordSuccess(cand.hoster)
                transferStarted(dataSpec)
                return length
            } catch (e: HttpDataSource.InvalidResponseCodeException) {
                runCatching { delegate.close() }
                DeadCandidateCache.markDead(cand.directUrl)
                DeadCandidateCache.markDead(cand.rawUrl)
                EndpointHealthScorer.recordFailure(cand.hoster)
                onCandidateFailure?.invoke(cand, e.responseCode)
                lastError = e
            } catch (e: IOException) {
                runCatching { delegate.close() }
                if (Thread.currentThread().isInterrupted || e.message.equals("Canceled", ignoreCase = true)) {
                    throw e
                }
                DeadCandidateCache.markDead(cand.directUrl)
                DeadCandidateCache.markDead(cand.rawUrl)
                EndpointHealthScorer.recordFailure(cand.hoster)
                onCandidateFailure?.invoke(cand, 0)
                lastError = e
            }
        }
        throw lastError ?: IOException("Semua kandidat failover gagal dibuka")
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val src = activeSource ?: return C.RESULT_END_OF_INPUT
        val read = src.read(buffer, offset, length)
        if (read > 0) {
            hasLockedStreamBytes.set(true)
            bytesTransferred(read)
        }
        return read
    }

    override fun getUri(): Uri? = activeUri

    override fun getResponseHeaders(): Map<String, List<String>> {
        return activeSource?.responseHeaders ?: emptyMap()
    }

    override fun close() {
        val src = activeSource
        activeSource = null
        activeUri = null
        if (src != null) {
            try {
                src.close()
            } finally {
                transferEnded()
            }
        }
    }
}
