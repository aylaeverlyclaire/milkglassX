package id.my.milkglass.resolver

import android.content.Context
import id.my.milkglass.MilkglassEndpoints
import id.my.milkglass.download.EndpointHealthScorer
import id.my.milkglass.download.MkvDownloadManager
import id.my.milkglass.net.IsolatedHttpClients
import id.my.milkglass.net.PublicIpGuard
import id.my.milkglass.source.CascadeResult
import id.my.milkglass.source.KusonimeSourceProvider
import id.my.milkglass.source.MkvCascadeResolver
import id.my.milkglass.source.MkvQuality
import id.my.milkglass.source.OploverzSourceProvider
import id.my.milkglass.source.OtakudesuSourceProvider
import id.my.milkglass.source.ResolvedMkvMedia
import id.my.milkglass.source.SamehadakuSourceProvider
import id.my.milkglass.source.SourcePriority
import id.my.milkglass.source.SourceSlugCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class LocalStreamSession(
    val localHttpUrl: String,
    val expectedSizeBytes: Long,
    val availableResolutions: List<String> = listOf("1080p", "720p", "480p"),
    val activeResolution: String = "1080p",
    val resolvedSourceId: String = "otakudesu",
    val resolvedHoster: String = "Pixeldrain",
    val closeSession: () -> Unit
)

class StreamResolver(
    private val context: Context? = null,
    private val backendBaseUrl: String = DEFAULT_BACKEND_BASE_URL,
    private val masterAppSecret: ByteArray = ByteArray(0),
    private val authTokenProvider: () -> String? = { null },
    private val sourceBaseUrls: Map<SourcePriority, String> = emptyMap(),
    private val clients: IsolatedHttpClients = IsolatedHttpClients(context),
    private val slugCache: SourceSlugCache = SourceSlugCache(context),
    private val cascadeResolver: MkvCascadeResolver = MkvCascadeResolver(
        clients = clients,
        slugCache = slugCache,
        providers = listOf(
            OtakudesuSourceProvider(
                clients,
                sourceBaseUrls[SourcePriority.OTAKUDESU] ?: SourcePriority.OTAKUDESU.defaultBaseUrl,
                slugCache = slugCache
            ),
            SamehadakuSourceProvider(
                clients,
                sourceBaseUrls[SourcePriority.SAMEHADAKU] ?: SourcePriority.SAMEHADAKU.defaultBaseUrl,
                slugCache = slugCache
            ),
            OploverzSourceProvider(
                clients,
                sourceBaseUrls[SourcePriority.OPLOVERZ] ?: SourcePriority.OPLOVERZ.defaultBaseUrl,
                slugCache = slugCache
            ),
            KusonimeSourceProvider(
                clients,
                sourceBaseUrls[SourcePriority.KUSONIME] ?: SourcePriority.KUSONIME.defaultBaseUrl,
                slugCache = slugCache
            )
        )
    )
) {
    private data class CachedResolvedCandidates(
        val cachedAtMs: Long,
        val candidates: List<ResolvedMkvMedia>,
        val availableResolutions: List<String>,
        val activeResolution: String,
        val resolvedSourceId: String,
        val resolvedHoster: String
    )

    private val resolvedCache = ConcurrentHashMap<String, CachedResolvedCandidates>()

    suspend fun resolveAndStartStream(
        slug: String,
        preferredRes: String = "1080p",
        title: String = "",
        romajiTitle: String = "",
        anilistId: Int? = null,
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
    ): LocalStreamSession = withContext(Dispatchers.IO) {
        val preferredQuality = MkvQuality.fromText(preferredRes) ?: MkvQuality.P1080
        val episodeNum = extractEpisodeNumber(slug)
        val searchTitle = romajiTitle.ifBlank { title }.ifBlank {
            slug.replace(Regex("-ep-\\d+$", RegexOption.IGNORE_CASE), "").replace('-', ' ')
        }

        if (context != null) {
            val dlManager = MkvDownloadManager(context)
            val localFile = dlManager.getDownloadedFile(slug.substringBefore("-ep-"), episodeNum, preferredQuality)
            if (localFile.exists() && localFile.length() > 1024L) {
                return@withContext LocalStreamSession(
                    localHttpUrl = "file://${localFile.absolutePath}",
                    expectedSizeBytes = localFile.length(),
                    availableResolutions = listOf(preferredQuality.label),
                    activeResolution = preferredQuality.label,
                    resolvedSourceId = "offline",
                    resolvedHoster = "LocalStorage",
                    closeSession = {}
                )
            }
        }

        val sessionCacheKey = "${slug.lowercase()}#${preferredQuality.label}"
        val nowMs = System.currentTimeMillis()
        resolvedCache[sessionCacheKey]?.takeIf { nowMs - it.cachedAtMs <= RESOLVED_CACHE_TTL_MS }?.let { cached ->
            return@withContext startHttpRangeProxySession(
                candidates = cached.candidates,
                availableResolutions = cached.availableResolutions,
                activeResolution = cached.activeResolution,
                resolvedSourceId = cached.resolvedSourceId,
                resolvedHoster = cached.resolvedHoster,
                scope = scope
            )
        }

        val primaryOutcome = cascadeResolver.resolve(
            animeTitle = searchTitle,
            episodeNumber = episodeNum,
            preferredQuality = preferredQuality,
            allowBatchFallback = false
        )
        val altTitle = if (searchTitle == romajiTitle) title else romajiTitle
        val outcome = if (primaryOutcome is CascadeResult.Failure && altTitle.isNotBlank() && !altTitle.equals(searchTitle, ignoreCase = true)) {
            cascadeResolver.resolve(
                animeTitle = altTitle,
                episodeNumber = episodeNum,
                preferredQuality = preferredQuality,
                allowBatchFallback = false
            )
        } else {
            primaryOutcome
        }

        when (outcome) {
            is CascadeResult.Failure -> {
                val reason = if (outcome.diagnostics.cloudflareBlockedHosts.isNotEmpty()) {
                    FailureReason.HTTP_403
                } else {
                    FailureReason.NOT_FOUND
                }
                throw StreamResolutionException(reason, outcome.reason)
            }
            is CascadeResult.Success -> {
                val chosen = outcome.selected
                val candidates = (listOf(chosen) + outcome.alternatives).filter { cand ->
                    val h = cand.directUrl.toHttpUrlOrNull()?.host ?: return@filter false
                    runCatching { PublicIpGuard.verifyPublicHost(clients.dns, h); true }.getOrDefault(false)
                }
                if (candidates.isEmpty()) {
                    throw StreamResolutionException(FailureReason.NOT_FOUND, "Semua URL hoster ditolak oleh validasi IP publik")
                }

                val availableRes = candidates.map { it.quality.label }.distinct()
                    .ifEmpty { listOf("1080p", "720p", "480p") }

                if (resolvedCache.size > 32) resolvedCache.clear()
                resolvedCache[sessionCacheKey] = CachedResolvedCandidates(
                    cachedAtMs = System.currentTimeMillis(),
                    candidates = candidates,
                    availableResolutions = availableRes,
                    activeResolution = chosen.quality.label,
                    resolvedSourceId = chosen.source.id,
                    resolvedHoster = chosen.hoster
                )

                startHttpRangeProxySession(
                    candidates = candidates,
                    availableResolutions = availableRes,
                    activeResolution = chosen.quality.label,
                    resolvedSourceId = chosen.source.id,
                    resolvedHoster = chosen.hoster,
                    scope = scope
                )
            }
        }
    }

    private fun startHttpRangeProxySession(
        candidates: List<ResolvedMkvMedia>,
        availableResolutions: List<String>,
        activeResolution: String,
        resolvedSourceId: String,
        resolvedHoster: String,
        scope: CoroutineScope
    ): LocalStreamSession {
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val sessionToken = UUID.randomUUID().toString().replace("-", "")
        val expectedPath = "/$sessionToken/stream.mkv"
        val localUrl = "http://127.0.0.1:${server.localPort}$expectedPath"

        val acceptJob = scope.launch(Dispatchers.IO) {
            try {
                while (isActive && !server.isClosed) {
                    val clientSocket = runCatching { server.accept() }.getOrNull() ?: break
                    launch(Dispatchers.IO) {
                        runCatching {
                            handleLocalProxyRequest(clientSocket, expectedPath, candidates)
                        }
                    }
                }
            } finally {
                runCatching { server.close() }
            }
        }

        return LocalStreamSession(
            localHttpUrl = localUrl,
            expectedSizeBytes = 0L,
            availableResolutions = availableResolutions,
            activeResolution = activeResolution,
            resolvedSourceId = resolvedSourceId,
            resolvedHoster = resolvedHoster,
            closeSession = {
                acceptJob.cancel()
                runCatching { server.close() }
            }
        )
    }

    private fun handleLocalProxyRequest(
        clientSocket: Socket,
        expectedPath: String,
        candidates: List<ResolvedMkvMedia>
    ) {
        clientSocket.use { socket ->
            runCatching {
                socket.tcpNoDelay = true
                socket.soTimeout = 30_000
            }
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val out: OutputStream = socket.getOutputStream()

            while (!socket.isClosed) {
                val requestLine = reader.readLine() ?: return
                if (requestLine.isBlank()) continue
                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0)?.uppercase() ?: "GET"
                val path = parts.getOrNull(1)?.substringBefore("?") ?: ""

                var rangeHeader: String? = null
                var clientWantsClose = false
                while (true) {
                    val line = reader.readLine() ?: return
                    if (line.isBlank()) break
                    if (line.startsWith("Range:", ignoreCase = true)) {
                        rangeHeader = line.substringAfter(":").trim()
                    } else if (line.startsWith("Connection:", ignoreCase = true) &&
                        line.contains("close", ignoreCase = true)
                    ) {
                        clientWantsClose = true
                    }
                }

                if (path != expectedPath) {
                    out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                    return
                }

                var handled = false
                var keepAlive = false

                for ((idx, candidate) in candidates.withIndex()) {
                    val isLast = idx == candidates.lastIndex
                    val reqBuilder = Request.Builder()
                        .url(candidate.directUrl)
                        .header("User-Agent", clients.webViewUserAgent)
                    if (!candidate.referer.isNullOrBlank()) {
                        reqBuilder.header("Referer", candidate.referer)
                    }
                    if (!rangeHeader.isNullOrBlank()) {
                        reqBuilder.header("Range", rangeHeader)
                    }
                    if (method == "HEAD") reqBuilder.head()

                    val outcome = runCatching {
                        clients.mediaAndApiClient.newCall(reqBuilder.build()).execute().use { resp ->
                            val shouldFailover = !isLast && (resp.code == 403 || resp.code == 429 || resp.code >= 500)
                            if (shouldFailover) {
                                EndpointHealthScorer.recordFailure(candidate.hoster)
                                return@use Pair(false, false)
                            }
                            if (resp.isSuccessful || resp.code == 206) {
                                EndpointHealthScorer.recordSuccess(candidate.hoster)
                            }

                            val contentLength = resp.header("Content-Length")
                            val canKeepAlive = !clientWantsClose && (contentLength != null || method == "HEAD")
                            val statusText = when (resp.code) {
                                206 -> "Partial Content"
                                200 -> "OK"
                                else -> resp.message.ifBlank { "Error" }
                            }
                            val sb = StringBuilder()
                            sb.append("HTTP/1.1 ${resp.code} $statusText\r\n")
                            sb.append("Content-Type: ${resp.header("Content-Type") ?: "video/x-matroska"}\r\n")
                            sb.append("Accept-Ranges: bytes\r\n")
                            contentLength?.let { sb.append("Content-Length: $it\r\n") }
                            resp.header("Content-Range")?.let { sb.append("Content-Range: $it\r\n") }
                            sb.append("Connection: ${if (canKeepAlive) "keep-alive" else "close"}\r\n\r\n")
                            out.write(sb.toString().toByteArray())

                            if (method != "HEAD") {
                                resp.body?.byteStream()?.let { stream ->
                                    BufferedInputStream(stream, 65536).use { bis ->
                                        val buf = ByteArray(65536)
                                        while (true) {
                                            val read = bis.read(buf)
                                            if (read == -1) break
                                            out.write(buf, 0, read)
                                        }
                                    }
                                }
                            }
                            out.flush()
                            Pair(true, canKeepAlive)
                        }
                    }.getOrDefault(Pair(false, false))

                    if (outcome.first) {
                        handled = true
                        keepAlive = outcome.second
                        break
                    }
                }

                if (!handled) {
                    runCatching {
                        out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        out.flush()
                    }
                    return
                }
                if (!keepAlive) return
            }
        }
    }

    private fun extractEpisodeNumber(slug: String): Int {
        return Regex("-ep-(\\d+)$", RegexOption.IGNORE_CASE)
            .find(slug)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
    }

    companion object {
        const val DEFAULT_BACKEND_BASE_URL = MilkglassEndpoints.DEFAULT_BACKEND_BASE_URL
        private const val RESOLVED_CACHE_TTL_MS = 10L * 60L * 1000L

        fun buildEpisodeSlug(romajiOrTitle: String, episode: Int): String {
            val clean = romajiOrTitle.lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .ifBlank { "anime" }
            return "$clean-ep-$episode"
        }
    }
}
