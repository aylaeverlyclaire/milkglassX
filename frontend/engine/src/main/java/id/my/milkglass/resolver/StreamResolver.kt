package id.my.milkglass.resolver

import android.content.Context
import id.my.milkglass.MilkglassEndpoints
import id.my.milkglass.download.EndpointHealthScorer
import id.my.milkglass.download.MkvDownloadManager
import id.my.milkglass.net.IsolatedHttpClients
import id.my.milkglass.extractor.MkvHosterExtractorRegistry
import id.my.milkglass.net.PublicIpGuard
import id.my.milkglass.source.CascadeResult
import id.my.milkglass.source.KusonimeSourceProvider
import id.my.milkglass.source.MediaContainer
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
import java.net.URLEncoder
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class LocalStreamSession(
    val localHttpUrl: String,
    val expectedSizeBytes: Long,
    val availableResolutions: List<String> = listOf("1080p", "720p", "480p"),
    val activeResolution: String = "1080p",
    val resolvedSourceId: String = "otakudesu",
    val resolvedHoster: String = "Pixeldrain",
    val isHls: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
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
    private val hasAuthSession: () -> Boolean = { false },
    private val serverSignatureVerifier: (String, String?) -> Boolean = { _, _ -> true },
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
    private val inFlightResolutions = ConcurrentHashMap<String, CompletableFuture<CachedResolvedCandidates?>>()
    private val ipLockedExtractor = MkvHosterExtractorRegistry(clients)

    @Volatile
    private var serverResolverDisabledUntilMs: Long = 0L

    private fun resolveViaServerSplitPlane(
        searchTitle: String,
        episodeNum: Int,
        preferredQuality: MkvQuality,
        preferCompatibleContainer: Boolean
    ): List<ResolvedMkvMedia> {
        if (!hasAuthSession()) return emptyList()
        val now = System.currentTimeMillis()
        if (now < serverResolverDisabledUntilMs) return emptyList()
        val url = "${backendBaseUrl.trimEnd('/')}/v1/play/resolve" +
            "?title=${URLEncoder.encode(searchTitle, "UTF-8")}" +
            "&ep=$episodeNum&q=${preferredQuality.label}"
        val req = Request.Builder().url(url).header("Accept", "application/json").build()
        val body = runCatching {
            clients.mediaAndApiClient.newCall(req).execute().use { resp ->
                if (resp.code == 503 || resp.code == 404) {
                    serverResolverDisabledUntilMs = System.currentTimeMillis() + 5L * 60L * 1000L
                    return@use null
                }
                if (!resp.isSuccessful) return@use null
                val rawBody = resp.body?.string() ?: return@use null
                if (!serverSignatureVerifier(rawBody, resp.header("X-Milkglass-Server-Sig"))) {
                    return@use null
                }
                rawBody
            }
        }.getOrNull() ?: return emptyList()

        val data = runCatching { JSONObject(body).optJSONObject("data") }.getOrNull() ?: return emptyList()
        val resolved = mutableListOf<ResolvedMkvMedia>()

        val directs = data.optJSONArray("direct_candidates")
        if (directs != null) {
            for (i in 0 until directs.length()) {
                val obj = directs.optJSONObject(i) ?: continue
                val directUrl = obj.optString("direct_url").trim()
                val host = directUrl.toHttpUrlOrNull()?.host ?: continue
                if (runCatching { PublicIpGuard.verifyPublicHost(clients.dns, host); true }.getOrDefault(false).not()) continue
                val src = SourcePriority.entries.firstOrNull { it.id == obj.optString("source") } ?: SourcePriority.OTAKUDESU
                val qual = MkvQuality.fromText(obj.optString("quality")) ?: preferredQuality
                val cont = runCatching { MediaContainer.valueOf(obj.optString("container").uppercase()) }.getOrDefault(MediaContainer.UNKNOWN)
                resolved.add(
                    ResolvedMkvMedia(
                        source = src,
                        quality = qual,
                        hoster = obj.optString("hoster").ifBlank { host },
                        directUrl = directUrl,
                        referer = obj.optString("referer").takeIf { it.isNotBlank() },
                        supportsRange = obj.optBoolean("supports_range", true),
                        container = cont,
                        isHls = obj.optBoolean("is_hls", false)
                    )
                )
            }
        }

        val needClientExtract = resolved.isEmpty() || preferCompatibleContainer
        val clientArr = if (needClientExtract) data.optJSONArray("client_extract_candidates") else null
        if (clientArr != null) {
            for (i in 0 until minOf(clientArr.length(), 4)) {
                val obj = clientArr.optJSONObject(i) ?: continue
                val rawUrl = obj.optString("raw_url").trim()
                if (!rawUrl.startsWith("http")) continue
                val referer = obj.optString("referer").takeIf { it.isNotBlank() }
                val extracted = runCatching { ipLockedExtractor.extract(rawUrl, referer) }.getOrNull() ?: continue
                if (!extracted.isArchive && !ipLockedExtractor.verifyPlayableMedia(extracted)) continue
                val host = extracted.directUrl.toHttpUrlOrNull()?.host ?: continue
                if (runCatching { PublicIpGuard.verifyPublicHost(clients.dns, host); true }.getOrDefault(false).not()) continue
                val src = SourcePriority.entries.firstOrNull { it.id == obj.optString("source") } ?: SourcePriority.OTAKUDESU
                val qual = MkvQuality.fromText(obj.optString("quality")) ?: preferredQuality
                val cont = when {
                    extracted.isHls -> MediaContainer.HLS
                    extracted.directUrl.contains(".mp4", ignoreCase = true) -> MediaContainer.MP4
                    extracted.directUrl.contains(".mkv", ignoreCase = true) -> MediaContainer.MKV
                    else -> MediaContainer.UNKNOWN
                }
                resolved.add(
                    ResolvedMkvMedia(
                        source = src,
                        quality = qual,
                        hoster = extracted.hoster,
                        directUrl = extracted.directUrl,
                        referer = extracted.referer,
                        supportsRange = extracted.supportsRange,
                        container = cont,
                        isHls = extracted.isHls
                    )
                )
                if (resolved.size >= 3) break
            }
        }

        if (resolved.isEmpty()) return emptyList()
        return if (preferCompatibleContainer) {
            resolved.sortedBy {
                when (it.container) {
                    MediaContainer.MP4 -> 0
                    MediaContainer.HLS -> 1
                    MediaContainer.UNKNOWN -> 2
                    MediaContainer.MKV -> 3
                }
            }
        } else {
            resolved
        }
    }

    fun invalidateCache(slug: String) {
        val prefix = "${slug.lowercase()}#"
        resolvedCache.keys.removeIf { it.startsWith(prefix) }
    }

    fun cachedCandidatesFor(slug: String, quality: MkvQuality): List<ResolvedMkvMedia> {
        val nowMs = System.currentTimeMillis()
        val stdKey = "${slug.lowercase()}#${quality.label}#std"
        resolvedCache[stdKey]?.takeIf { nowMs - it.cachedAtMs <= RESOLVED_CACHE_TTL_MS }?.let { return it.candidates }
        val compatKey = "${slug.lowercase()}#${quality.label}#compat"
        resolvedCache[compatKey]?.takeIf { nowMs - it.cachedAtMs <= RESOLVED_CACHE_TTL_MS }?.let { return it.candidates }
        return emptyList()
    }

    fun prefetchStream(
        slug: String,
        preferredRes: String = "1080p",
        title: String = "",
        romajiTitle: String = ""
    ): Boolean {
        val preferredQuality = MkvQuality.fromText(preferredRes) ?: MkvQuality.P1080
        val sessionCacheKey = "${slug.lowercase()}#${preferredQuality.label}#std"
        val nowMs = System.currentTimeMillis()
        if (resolvedCache[sessionCacheKey]?.let { nowMs - it.cachedAtMs <= RESOLVED_CACHE_TTL_MS } == true) {
            return true
        }
        val episodeNum = extractEpisodeNumber(slug)
        val searchTitle = romajiTitle.ifBlank { title }.ifBlank {
            slug.replace(Regex("-ep-\\d+$", RegexOption.IGNORE_CASE), "").replace('-', ' ')
        }
        val mine = CompletableFuture<CachedResolvedCandidates?>()
        val existing = inFlightResolutions.putIfAbsent(sessionCacheKey, mine)
        if (existing != null) {
            return runCatching { existing.get(25, TimeUnit.SECONDS) != null }.getOrDefault(false)
        }
        return try {
            val outcome = cascadeResolver.resolve(
                animeTitle = searchTitle,
                episodeNumber = episodeNum,
                preferredQuality = preferredQuality,
                allowBatchFallback = false,
                preferCompatibleContainer = false
            )
            if (outcome !is CascadeResult.Success) {
                mine.complete(null)
                return false
            }
            val allCandidates = (listOf(outcome.selected) + outcome.alternatives).filter { cand ->
                val h = cand.directUrl.toHttpUrlOrNull()?.host ?: return@filter false
                runCatching { PublicIpGuard.verifyPublicHost(clients.dns, h); true }.getOrDefault(false)
            }.distinctBy { it.directUrl }
            if (allCandidates.isEmpty()) {
                mine.complete(null)
                return false
            }
            val avail = allCandidates.map { it.quality.label }.distinct().ifEmpty { listOf(outcome.selected.quality.label) }
            val entry = CachedResolvedCandidates(
                cachedAtMs = System.currentTimeMillis(),
                candidates = allCandidates,
                availableResolutions = avail,
                activeResolution = outcome.selected.quality.label,
                resolvedSourceId = outcome.selected.source.id,
                resolvedHoster = outcome.selected.hoster
            )
            if (resolvedCache.size > 32) resolvedCache.clear()
            resolvedCache[sessionCacheKey] = entry
            mine.complete(entry)
            true
        } catch (_: Throwable) {
            mine.complete(null)
            false
        } finally {
            inFlightResolutions.remove(sessionCacheKey, mine)
        }
    }

    suspend fun resolveAndStartStream(
        slug: String,
        preferredRes: String = "1080p",
        title: String = "",
        romajiTitle: String = "",
        anilistId: Int? = null,
        preferCompatibleContainer: Boolean = false,
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
    ): LocalStreamSession = withContext(Dispatchers.IO) {
        val preferredQuality = MkvQuality.fromText(preferredRes) ?: MkvQuality.P1080
        val episodeNum = extractEpisodeNumber(slug)
        val searchTitle = romajiTitle.ifBlank { title }.ifBlank {
            slug.replace(Regex("-ep-\\d+$", RegexOption.IGNORE_CASE), "").replace('-', ' ')
        }

        if (context != null && !preferCompatibleContainer) {
            val dlManager = MkvDownloadManager(context)
            val localFound = dlManager.findAnyDownloadedFile(slug.substringBefore("-ep-"), episodeNum, preferredQuality)
            if (localFound != null) {
                val (localFile, foundQuality) = localFound
                return@withContext LocalStreamSession(
                    localHttpUrl = "file://${localFile.absolutePath}",
                    expectedSizeBytes = localFile.length(),
                    availableResolutions = listOf(foundQuality.label),
                    activeResolution = foundQuality.label,
                    resolvedSourceId = "offline",
                    resolvedHoster = "LocalStorage",
                    closeSession = {}
                )
            }
        }

        val sessionCacheKey = "${slug.lowercase()}#${preferredQuality.label}#${if (preferCompatibleContainer) "compat" else "std"}"
        val nowMs = System.currentTimeMillis()
        resolvedCache[sessionCacheKey]?.takeIf { nowMs - it.cachedAtMs <= RESOLVED_CACHE_TTL_MS }?.let { cached ->
            return@withContext startSessionForCandidates(
                candidates = cached.candidates,
                availableResolutions = cached.availableResolutions,
                activeResolution = cached.activeResolution,
                resolvedSourceId = cached.resolvedSourceId,
                resolvedHoster = cached.resolvedHoster,
                scope = scope
            )
        }

        inFlightResolutions[sessionCacheKey]?.let { inflight ->
            val joined = runCatching { inflight.get(25, TimeUnit.SECONDS) }.getOrNull()
            if (joined != null) {
                return@withContext startSessionForCandidates(
                    candidates = joined.candidates,
                    availableResolutions = joined.availableResolutions,
                    activeResolution = joined.activeResolution,
                    resolvedSourceId = joined.resolvedSourceId,
                    resolvedHoster = joined.resolvedHoster,
                    scope = scope
                )
            }
        }

        val serverCandidates = resolveViaServerSplitPlane(
            searchTitle = searchTitle,
            episodeNum = episodeNum,
            preferredQuality = preferredQuality,
            preferCompatibleContainer = preferCompatibleContainer
        )
        if (serverCandidates.isNotEmpty()) {
            val chosen = serverCandidates.first()
            val availableRes = serverCandidates.map { it.quality.label }.distinct()
                .ifEmpty { listOf("1080p", "720p", "480p") }
            if (resolvedCache.size > 32) resolvedCache.clear()
            resolvedCache[sessionCacheKey] = CachedResolvedCandidates(
                cachedAtMs = System.currentTimeMillis(),
                candidates = serverCandidates,
                availableResolutions = availableRes,
                activeResolution = chosen.quality.label,
                resolvedSourceId = chosen.source.id,
                resolvedHoster = chosen.hoster
            )
            return@withContext startSessionForCandidates(
                candidates = serverCandidates,
                availableResolutions = availableRes,
                activeResolution = chosen.quality.label,
                resolvedSourceId = chosen.source.id,
                resolvedHoster = chosen.hoster,
                scope = scope
            )
        }

        val primaryOutcome = cascadeResolver.resolve(
            animeTitle = searchTitle,
            episodeNumber = episodeNum,
            preferredQuality = preferredQuality,
            allowBatchFallback = false,
            preferCompatibleContainer = preferCompatibleContainer
        )
        val altTitle = if (searchTitle == romajiTitle) title else romajiTitle
        val outcome = if (primaryOutcome is CascadeResult.Failure && altTitle.isNotBlank() && !altTitle.equals(searchTitle, ignoreCase = true)) {
            cascadeResolver.resolve(
                animeTitle = altTitle,
                episodeNumber = episodeNum,
                preferredQuality = preferredQuality,
                allowBatchFallback = false,
                preferCompatibleContainer = preferCompatibleContainer
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

                startSessionForCandidates(
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

    private fun startSessionForCandidates(
        candidates: List<ResolvedMkvMedia>,
        availableResolutions: List<String>,
        activeResolution: String,
        resolvedSourceId: String,
        resolvedHoster: String,
        scope: CoroutineScope
    ): LocalStreamSession {
        val primary = candidates.first()
        if (primary.isHls || primary.container == MediaContainer.HLS) {
            val hdrs = buildMap {
                put("User-Agent", clients.webViewUserAgent)
                if (!primary.referer.isNullOrBlank()) put("Referer", primary.referer)
            }
            return LocalStreamSession(
                localHttpUrl = primary.directUrl,
                expectedSizeBytes = 0L,
                availableResolutions = availableResolutions,
                activeResolution = activeResolution,
                resolvedSourceId = resolvedSourceId,
                resolvedHoster = resolvedHoster,
                isHls = true,
                headers = hdrs,
                closeSession = {}
            )
        }
        return startHttpRangeProxySession(
            candidates = candidates,
            availableResolutions = availableResolutions,
            activeResolution = activeResolution,
            resolvedSourceId = resolvedSourceId,
            resolvedHoster = resolvedHoster,
            scope = scope
        )
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
        val expectedPath = "/$sessionToken/stream"
        val localUrl = "http://127.0.0.1:${server.localPort}$expectedPath"
        val stickyIdx = AtomicInteger(0)

        val acceptJob = scope.launch(Dispatchers.IO) {
            try {
                while (isActive && !server.isClosed) {
                    val clientSocket = runCatching { server.accept() }.getOrNull() ?: break
                    launch(Dispatchers.IO) {
                        runCatching {
                            handleLocalProxyRequest(clientSocket, expectedPath, candidates, stickyIdx)
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
        candidates: List<ResolvedMkvMedia>,
        stickyIdx: AtomicInteger = AtomicInteger(0)
    ) {
        clientSocket.use { socket ->
            runCatching {
                socket.tcpNoDelay = true
                socket.sendBufferSize = 131_072
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

                val startIdx = stickyIdx.get().coerceIn(0, (candidates.size - 1).coerceAtLeast(0))
                val orderedIndices = (startIdx until candidates.size) + (0 until startIdx)
                for ((attemptPos, idx) in orderedIndices.withIndex()) {
                    val candidate = candidates[idx]
                    val isLast = attemptPos == orderedIndices.lastIndex
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
                            val shouldFailover = !isLast && (resp.code == 403 || resp.code == 404 || resp.code == 410 || resp.code == 429 || resp.code >= 500)
                            if (shouldFailover) {
                                EndpointHealthScorer.recordFailure(candidate.hoster)
                                return@use Pair(false, false)
                            }
                            if (resp.isSuccessful || resp.code == 206) {
                                stickyIdx.set(idx)
                                EndpointHealthScorer.recordSuccess(candidate.hoster)
                            }

                            val contentLength = resp.header("Content-Length")
                            val canKeepAlive = !clientWantsClose && (contentLength != null || method == "HEAD")
                            val statusText = when (resp.code) {
                                206 -> "Partial Content"
                                200 -> "OK"
                                else -> resp.message.ifBlank { "Error" }
                            }
                            val fallbackCt = if (candidate.container == MediaContainer.MP4) "video/mp4" else "application/octet-stream"
                            val sb = StringBuilder()
                            sb.append("HTTP/1.1 ${resp.code} $statusText\r\n")
                            sb.append("Content-Type: ${resp.header("Content-Type") ?: fallbackCt}\r\n")
                            sb.append("Accept-Ranges: bytes\r\n")
                            contentLength?.let { sb.append("Content-Length: $it\r\n") }
                            resp.header("Content-Range")?.let { sb.append("Content-Range: $it\r\n") }
                            sb.append("Connection: ${if (canKeepAlive) "keep-alive" else "close"}\r\n\r\n")
                            out.write(sb.toString().toByteArray())

                            if (method != "HEAD") {
                                resp.body?.byteStream()?.let { stream ->
                                    BufferedInputStream(stream, 131072).use { bis ->
                                        val buf = ByteArray(131072)
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
