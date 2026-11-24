package id.my.milkglass.resolver

import id.my.milkglass.source.SeriesEpisodeMapCache

import android.content.Context
import id.my.milkglass.MilkglassEndpoints
import id.my.milkglass.download.EndpointHealthScorer
import id.my.milkglass.download.MkvDownloadManager
import id.my.milkglass.net.IsolatedHttpClients
import id.my.milkglass.net.PublicIpGuard
import id.my.milkglass.source.CascadeResult
import id.my.milkglass.source.MediaContainer
import id.my.milkglass.source.MkvCascadeResolver
import id.my.milkglass.source.MkvQuality
import id.my.milkglass.source.OtakudesuSourceProvider
import id.my.milkglass.source.RawMkvCandidate
import id.my.milkglass.source.ResolutionConsolidator
import id.my.milkglass.source.ResolvedMkvMedia
import id.my.milkglass.source.YlnimeSourceProvider
import id.my.milkglass.source.SourcePriority
import id.my.milkglass.source.SourceSlugCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class LocalStreamSession(
    val localHttpUrl: String,
    val expectedSizeBytes: Long,
    val availableResolutions: List<String> = listOf("1080p", "720p", "480p"),
    val activeResolution: String = "1080p",
    val resolvedSourceId: String = SourcePriority.YLNIME.id,
    val resolvedHoster: String = "Pixeldrain",
    val isHls: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
    val candidates: List<ResolvedMkvMedia> = emptyList(),
    val cacheHit: Boolean = false,
    val closeSession: () -> Unit
)

data class PlaybackTelemetryReport(
    val title: String,
    val ep: Int,
    val animeId: Int? = null,
    val status: String,
    val resolveMs: Long = 0L,
    val ttffMs: Long = 0L,
    val cacheHit: Boolean = false,
    val source: String = "",
    val hoster: String = "",
    val quality: String = "",
    val errorCode: String = "",
    val errorMessage: String = "",
    val providerDiagnostics: String = "",
    val appVersion: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("title", title.trim().ifBlank { "unknown" }.take(160))
        put("ep", ep.coerceIn(0, 2000))
        if (animeId != null && animeId > 0) put("anime_id", animeId)
        put("status", status)
        put("resolve_ms", resolveMs.coerceIn(0L, 120_000L))
        put("ttff_ms", ttffMs.coerceIn(0L, 120_000L))
        put("cache_hit", cacheHit)
        put("source", source.trim().take(40))
        put("hoster", hoster.trim().take(60))
        put("quality", quality.trim().take(16))
        put("error_code", errorCode.trim().take(64))
        put("error_message", errorMessage.trim().take(400))
        put("provider_diagnostics", providerDiagnostics.trim().take(1200))
        put("app_version", appVersion.trim().take(32))
    }

    companion object {
        const val TARGET_FAST_TTFF_MS = 1_000L

        fun classifyStatus(failed: Boolean, ttffMs: Long): String = when {
            failed -> "failed"
            ttffMs > TARGET_FAST_TTFF_MS -> "slow"
            else -> "ok"
        }
    }
}

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
            YlnimeSourceProvider(
                clients,
                sourceBaseUrls[SourcePriority.YLNIME] ?: SourcePriority.YLNIME.defaultBaseUrl,
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

    @Volatile
    private var serverResolverDisabledUntilMs: Long = 0L

    private val serverHintClient by lazy {
        clients.mediaAndApiClient.newBuilder()
            .callTimeout(SERVER_HINT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }
    private val hintExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "milkglass-hint").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val consolidator = ResolutionConsolidator(clients, slugCache, sourceBaseUrls)
    private val hintReportedAt = ConcurrentHashMap<String, Long>()

    fun reportDeadDirectUrl(title: String, ep: Int, directUrl: String, httpCode: Int) {
        if (!directUrl.startsWith("https://")) return
        if (title.trim().length < 2 || ep < 0) return
        val statusStr = when (httpCode) {
            403 -> "403"
            404 -> "404"
            410 -> "410"
            408, 429, 504 -> "timeout"
            else -> if (httpCode >= 500) "expired" else return
        }
        val now = System.currentTimeMillis()
        if (now < serverResolverDisabledUntilMs) return
        hintExecutor.execute {
            runCatching {
                val body = JSONObject()
                    .put("title", title.trim().take(160))
                    .put("ep", ep)
                    .put("kind", "direct")
                    .put("url", directUrl.take(2048))
                    .put("http_status", statusStr)
                    .toString()
                val req = Request.Builder()
                    .url("${backendBaseUrl.trimEnd('/')}/v1/play/report")
                    .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                serverHintClient.newCall(req).execute().close()
            }
        }
    }

    fun getCachedCandidates(
        slug: String,
        preferredRes: String = "1080p",
        preferCompatibleContainer: Boolean = false
    ): List<ResolvedMkvMedia>? {
        val preferredQuality = MkvQuality.fromText(preferredRes) ?: MkvQuality.P1080
        val key = "${slug.lowercase()}#${preferredQuality.label}#${if (preferCompatibleContainer) "compat" else "std"}"
        val cached = resolvedCache[key] ?: return null
        if (System.currentTimeMillis() - cached.cachedAtMs > RESOLVED_CACHE_TTL_MS) return null
        return cached.candidates.takeIf { it.isNotEmpty() }
    }

    fun reportHint(cacheKey: String, candidates: List<RawMkvCandidate>) {
        if (candidates.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now < serverResolverDisabledUntilMs) return
        val title = cacheKey.substringBeforeLast('#', "").trim()
        val ep = cacheKey.substringAfterLast('#', "").toIntOrNull() ?: return
        if (title.length < 2 || ep < 0) return
        val reportSignature = "${cacheKey}#${candidates.map { "${it.source.id}:${it.quality.label}" }.distinct().sorted().joinToString(",")}"
        val last = hintReportedAt[reportSignature]
        if (last != null && now - last < 30L * 60L * 1000L) return
        if (hintReportedAt.size > 96) hintReportedAt.clear()
        hintReportedAt[reportSignature] = now
        hintExecutor.execute {
            runCatching {
                val arr = JSONArray()
                candidates.filter { !it.isBatchArchive }.take(12).forEach { c ->
                    arr.put(
                        JSONObject().put("source", c.source.id).put("quality", c.quality.label)
                            .put("hoster", c.hosterLabel.take(40)).put("raw_url", c.rawUrl)
                            .put("referer", c.pageReferer)
                            .put("container", c.container.name)
                    )
                }
                if (arr.length() == 0) return@runCatching
                val body = JSONObject().put("title", title).put("ep", ep).put("candidates", arr).toString()
                val req = Request.Builder()
                    .url("${backendBaseUrl.trimEnd('/')}/v1/play/hint")
                    .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                serverHintClient.newCall(req).execute().use { resp ->
                    if (resp.code == 404 || resp.code == 503) {
                        serverResolverDisabledUntilMs = System.currentTimeMillis() + 6L * 60L * 60L * 1000L
                    }
                }
            }
        }
    }

    fun reportPlaybackTelemetry(report: PlaybackTelemetryReport) {
        hintExecutor.execute {
            runCatching {
                val body = report.toJson().toString()
                val req = Request.Builder()
                    .url("${backendBaseUrl.trimEnd('/')}/v1/play/playback-report")
                    .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                serverHintClient.newCall(req).execute().close()
            }
        }
    }

    private val directReportedAt = ConcurrentHashMap<String, Long>()

    fun reportDirect(title: String, ep: Int, list: List<ResolvedMkvMedia>) {
        if (list.isEmpty() || ep < 0 || title.trim().length < 2) return
        val now = System.currentTimeMillis()
        if (now < serverResolverDisabledUntilMs) return
        val key = "${title.lowercase().trim()}#$ep#${list.first().quality.label}"
        val last = directReportedAt[key]
        if (last != null && now - last < 5L * 60L * 1000L) return
        if (directReportedAt.size > 64) directReportedAt.clear()
        directReportedAt[key] = now
        val usable = list.filter { !it.isBatchArchive && it.directUrl.startsWith("https://") }.take(4)
        if (usable.isEmpty()) return
        hintExecutor.execute {
            runCatching {
                val arr = JSONArray()
                usable.forEach { m ->
                    arr.put(
                        JSONObject().put("source", m.source.id).put("quality", m.quality.label)
                            .put("hoster", m.hoster.take(40)).put("direct_url", m.directUrl)
                            .put("referer", m.referer ?: "").put("supports_range", m.supportsRange)
                            .put("container", m.container.name).put("is_hls", m.isHls)
                    )
                }
                val body = JSONObject().put("title", title.trim()).put("ep", ep).put("candidates", arr).toString()
                val req = Request.Builder()
                    .url("${backendBaseUrl.trimEnd('/')}/v1/play/direct")
                    .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                serverHintClient.newCall(req).execute().use { resp ->
                    if (resp.code == 404 || resp.code == 503) {
                        serverResolverDisabledUntilMs = System.currentTimeMillis() + 6L * 60L * 60L * 1000L
                    }
                }
            }
        }
    }

    private fun <T> CompletableFuture<T>.awaitOrNull(ms: Long): T? =
        try {
            get(ms, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            throw e
        } catch (_: Exception) {
            null
        }

    private sealed interface RaceOutcome {
        class Candidates(val list: List<ResolvedMkvMedia>, val availableQualities: List<MkvQuality> = emptyList(), val cacheHit: Boolean = false) : RaceOutcome
        class Failed(val reason: FailureReason, val message: String) : RaceOutcome
    }

    private sealed interface RaceMsg {
        class FromServer(val list: List<ResolvedMkvMedia>, val availableQualities: List<MkvQuality> = emptyList()) : RaceMsg
        class FromJoin(val entry: CachedResolvedCandidates?) : RaceMsg
        class FromCascade(val result: CascadeResult?) : RaceMsg
    }

    private val raceSeq = AtomicInteger(1)
    private val raceExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "milkglass-race-${raceSeq.getAndIncrement()}").apply { isDaemon = true }
    }

    @Volatile
    private var serverMissStreak: Int = 0

    @Volatile
    private var serverQuietUntilMs: Long = 0L

    private fun shouldAskServer(): Boolean {
        val now = System.currentTimeMillis()
        return now >= serverResolverDisabledUntilMs && now >= serverQuietUntilMs
    }

    private fun noteServerOutcome(hit: Boolean) {
        if (hit) {
            serverMissStreak = 0
            return
        }
        serverMissStreak += 1
        if (serverMissStreak >= SERVER_MISS_STREAK_LIMIT) {
            serverMissStreak = 0
            serverQuietUntilMs = System.currentTimeMillis() + SERVER_QUIET_MS
        }
    }

    private fun publicOnly(list: List<ResolvedMkvMedia>): List<ResolvedMkvMedia> =
        list.filter { cand ->
            val h = cand.directUrl.toHttpUrlOrNull()?.host ?: return@filter false
            runCatching { PublicIpGuard.verifyPublicHost(clients.dns, h); true }.getOrDefault(false)
        }.distinctBy { it.directUrl }

    private fun cacheEntryFor(
        list: List<ResolvedMkvMedia>,
        knownQualities: List<MkvQuality> = emptyList()
    ): CachedResolvedCandidates {
        val chosen = list.first()
        val avail = (knownQualities + list.map { it.quality })
            .distinct()
            .sortedByDescending { it.height }
            .map { it.label }
            .ifEmpty { listOf(chosen.quality.label) }
        return CachedResolvedCandidates(
            cachedAtMs = System.currentTimeMillis(),
            candidates = list,
            availableResolutions = avail,
            activeResolution = chosen.quality.label,
            resolvedSourceId = chosen.source.id,
            resolvedHoster = chosen.hoster
        )
    }

    private fun startFromEntry(e: CachedResolvedCandidates, scope: CoroutineScope, cacheHit: Boolean = false): LocalStreamSession =
        startSessionForCandidates(
            candidates = e.candidates,
            availableResolutions = e.availableResolutions,
            activeResolution = e.activeResolution,
            resolvedSourceId = e.resolvedSourceId,
            resolvedHoster = e.resolvedHoster,
            scope = scope,
            cacheHit = cacheHit
        )

    private fun raceResolve(
        searchTitle: String,
        altTitle: String,
        episodeNum: Int,
        preferredQuality: MkvQuality,
        preferCompatibleContainer: Boolean,
        joined: CompletableFuture<CachedResolvedCandidates?>?,
        anilistId: Int? = null
    ): RaceOutcome {
        val queue = LinkedBlockingQueue<RaceMsg>()
        val futures = ArrayList<Future<*>>(3)
        var pending = 0
        try {
            if (shouldAskServer()) {
                pending++
                futures.add(
                    raceExecutor.submit(Runnable {
                        var msg = RaceMsg.FromServer(emptyList())
                        try {
                            val (list, availQ, hadAnyServerEntry) = resolveViaServerSplitPlane(searchTitle, episodeNum, preferredQuality, preferCompatibleContainer, anilistId)
                            noteServerOutcome(list.isNotEmpty() || hadAnyServerEntry)
                            msg = RaceMsg.FromServer(list, availQ)
                        } catch (_: InterruptedException) {
                        } catch (_: Throwable) {
                        }
                        queue.offer(msg)
                    })
                )
            }
            if (joined != null) {
                pending++
                futures.add(
                    raceExecutor.submit(Runnable {
                        var entry: CachedResolvedCandidates? = null
                        try {
                            entry = joined.awaitOrNull(JOIN_INFLIGHT_MAX_MS)
                        } catch (_: InterruptedException) {
                        }
                        queue.offer(RaceMsg.FromJoin(entry))
                    })
                )
            }
            pending++
            futures.add(
                raceExecutor.submit(Runnable {
                    var result: CascadeResult? = null
                    try {
                        var r = cascadeResolver.resolve(
                            animeTitle = searchTitle,
                            episodeNumber = episodeNum,
                            preferredQuality = preferredQuality,
                            allowBatchFallback = false,
                            preferCompatibleContainer = preferCompatibleContainer
                        )
                        if (r is CascadeResult.Failure && altTitle.isNotBlank() &&
                            !altTitle.equals(searchTitle, ignoreCase = true) && !Thread.currentThread().isInterrupted
                        ) {
                            r = cascadeResolver.resolve(
                                animeTitle = altTitle,
                                episodeNumber = episodeNum,
                                preferredQuality = preferredQuality,
                                allowBatchFallback = false,
                                preferCompatibleContainer = preferCompatibleContainer
                            )
                        }
                        result = r
                    } catch (_: Throwable) {
                    }
                    queue.offer(RaceMsg.FromCascade(result))
                })
            )

            var cascadeFailure: CascadeResult.Failure? = null
            var hosterRejected = false
            while (pending > 0) {
                when (val msg = queue.take()) {
                    is RaceMsg.FromServer -> {
                        pending--
                        if (msg.list.isNotEmpty()) return RaceOutcome.Candidates(msg.list.distinctBy { it.directUrl }, msg.availableQualities, cacheHit = true)
                    }
                    is RaceMsg.FromJoin -> {
                        pending--
                        val e = msg.entry
                        if (e != null && e.candidates.isNotEmpty()) {
                            val ok = publicOnly(e.candidates)
                            val knownQ = e.availableResolutions.mapNotNull { MkvQuality.fromText(it) }
                            if (ok.isNotEmpty()) return RaceOutcome.Candidates(ok, knownQ, cacheHit = true)
                        }
                    }
                    is RaceMsg.FromCascade -> {
                        pending--
                        val r = msg.result
                        if (r is CascadeResult.Success) {
                            val ok = publicOnly(listOf(r.selected) + r.alternatives)
                            if (ok.isNotEmpty()) {
                                reportDirect(searchTitle, episodeNum, ok)
                                return RaceOutcome.Candidates(ok, r.availableQualities)
                            }
                            hosterRejected = true
                        } else if (r is CascadeResult.Failure) {
                            cascadeFailure = r
                        }
                    }
                }
            }
            val cf = cascadeFailure
            return when {
                hosterRejected -> RaceOutcome.Failed(FailureReason.NOT_FOUND, "Semua URL hoster ditolak oleh validasi IP publik")
                cf != null -> RaceOutcome.Failed(
                    if (cf.diagnostics.cloudflareBlockedHosts.isNotEmpty()) FailureReason.HTTP_403 else FailureReason.NOT_FOUND,
                    cf.reason
                )
                else -> RaceOutcome.Failed(FailureReason.NOT_FOUND, "Sumber tidak menghasilkan tautan")
            }
        } finally {
            futures.forEach { if (!it.isDone) it.cancel(true) }
        }
    }

    private fun resolveViaServerSplitPlane(
        searchTitle: String,
        episodeNum: Int,
        preferredQuality: MkvQuality,
        preferCompatibleContainer: Boolean,
        anilistId: Int? = null
    ): Triple<List<ResolvedMkvMedia>, List<MkvQuality>, Boolean> {
        val empty = Triple(emptyList<ResolvedMkvMedia>(), emptyList<MkvQuality>(), false)
        val now = System.currentTimeMillis()
        if (now < serverResolverDisabledUntilMs) return empty
        val animeIdParam = if (anilistId != null && anilistId > 0) "&anime_id=$anilistId" else ""
        val url = "${backendBaseUrl.trimEnd('/')}/v1/play/resolve" +
            "?title=${URLEncoder.encode(searchTitle, "UTF-8")}" +
            "&ep=$episodeNum&q=${preferredQuality.label}$animeIdParam"
        val req = Request.Builder().url(url).header("Accept", "application/json").build()
        val body = try {
            serverHintClient.newCall(req).execute().use { resp ->
                val backoffMs = when {
                    resp.code == 404 -> 6L * 60L * 60L * 1000L
                    resp.code == 503 -> 2L * 60L * 1000L
                    resp.code == 401 || resp.code == 403 || resp.code == 429 -> 60_000L
                    !resp.isSuccessful -> 30_000L
                    else -> 0L
                }
                if (backoffMs > 0L) {
                    serverResolverDisabledUntilMs = System.currentTimeMillis() + backoffMs
                    return@use null
                }
                val rawBody = resp.body?.string() ?: return@use null
                val serverSig = resp.header("X-Milkglass-Server-Sig")
                if ((hasAuthSession() || !serverSig.isNullOrBlank()) && !serverSignatureVerifier(rawBody, serverSig)) {
                    return@use null
                }
                rawBody
            }
        } catch (e: InterruptedException) {
            throw e
        } catch (e: java.io.InterruptedIOException) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            serverResolverDisabledUntilMs = System.currentTimeMillis() + 20_000L
            null
        } catch (e: Exception) {
            if (Thread.currentThread().isInterrupted || e.message.equals("Canceled", ignoreCase = true)) {
                throw InterruptedException("Cancelled")
            }
            serverResolverDisabledUntilMs = System.currentTimeMillis() + 20_000L
            null
        } ?: return empty

        val data = runCatching { JSONObject(body).optJSONObject("data") }.getOrNull() ?: return empty
        data.optJSONObject("hoster_scores")?.let { scoresObj ->
            val map = linkedMapOf<String, Double>()
            val keys = scoresObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = scoresObj.optDouble(k, Double.NaN)
                if (k.isNotBlank() && v.isFinite()) map[k] = v
            }
            if (map.isNotEmpty()) EndpointHealthScorer.applyServerHosterScores(map)
        }
        val resolved = mutableListOf<ResolvedMkvMedia>()
        val fallbackDirect = mutableListOf<ResolvedMkvMedia>()
        val serverQualities = linkedSetOf<MkvQuality>()
        var hadAnyServerEntry = false

        val directs = data.optJSONArray("direct_candidates")
        if (directs != null) {
            for (i in 0 until directs.length()) {
                val obj = directs.optJSONObject(i) ?: continue
                val directUrl = obj.optString("direct_url").trim()
                if (id.my.milkglass.source.DeadCandidateCache.isDead(directUrl)) continue
                val host = directUrl.toHttpUrlOrNull()?.host ?: continue
                val qual = MkvQuality.fromText(obj.optString("quality")) ?: continue
                serverQualities.add(qual)
                hadAnyServerEntry = true
                if (runCatching { PublicIpGuard.verifyPublicHost(clients.dns, host); true }.getOrDefault(false).not()) continue
                val src = SourcePriority.entries.firstOrNull { it.id == obj.optString("source") } ?: SourcePriority.OTAKUDESU
                val cont = runCatching { MediaContainer.valueOf(obj.optString("container").uppercase()) }.getOrDefault(MediaContainer.UNKNOWN)
                val item = ResolvedMkvMedia(
                    source = src,
                    quality = qual,
                    hoster = obj.optString("hoster").ifBlank { host },
                    directUrl = directUrl,
                    referer = obj.optString("referer").takeIf { it.isNotBlank() },
                    supportsRange = obj.optBoolean("supports_range", true),
                    container = cont,
                    isHls = obj.optBoolean("is_hls", false),
                    rawUrl = directUrl
                )
                if (qual == preferredQuality) resolved.add(item) else fallbackDirect.add(item)
            }
        }
        if (resolved.isEmpty() && fallbackDirect.isNotEmpty()) {
            resolved.addAll(fallbackDirect.sortedByDescending { it.quality.height })
        } else if (resolved.isNotEmpty() && fallbackDirect.isNotEmpty()) {
            // Sertakan direct candidate resolusi lain sebagai cadangan failover instan di posisi akhir
            resolved.addAll(fallbackDirect.sortedByDescending { it.quality.height }.take(2))
        }

        if (resolved.isNotEmpty()) {
            // Zero-Probe Direct Handoff: urutkan CDN binary terpercaya di depan dan serahkan langsung ke FailoverHttpDataSource tanpa 1x RTT probe ganda.
            val trusted = resolved.filter { id.my.milkglass.extractor.EmbedMediaSniffer.isTrustedBinaryStreamEndpoint(it.directUrl) }
            val rest = resolved.filterNot { id.my.milkglass.extractor.EmbedMediaSniffer.isTrustedBinaryStreamEndpoint(it.directUrl) }
            resolved.clear()
            resolved.addAll(trusted)
            resolved.addAll(rest)
        }

        val clientArrAll = data.optJSONArray("client_extract_candidates")
        val allServerRaws = ArrayList<RawMkvCandidate>()
        if (clientArrAll != null) {
            for (i in 0 until minOf(clientArrAll.length(), 16)) {
                val obj = clientArrAll.optJSONObject(i) ?: continue
                val rawUrl = obj.optString("raw_url").trim()
                if (!rawUrl.startsWith("https://")) continue
                val qual = MkvQuality.fromText(obj.optString("quality")) ?: continue
                serverQualities.add(qual)
                hadAnyServerEntry = true
                if (id.my.milkglass.source.DeadCandidateCache.isDead(rawUrl)) continue
                val hoster = obj.optString("hoster").ifBlank { "Hoster" }
                val isEmbed = obj.optBoolean("is_stream_embed", isEmbedCandidateUrl(rawUrl, hoster))
                allServerRaws.add(
                    RawMkvCandidate(
                        source = SourcePriority.entries.firstOrNull { it.id == obj.optString("source") } ?: SourcePriority.OTAKUDESU,
                        quality = qual,
                        hosterLabel = hoster,
                        rawUrl = rawUrl,
                        pageReferer = obj.optString("referer").trim(),
                        container = MediaContainer.detect("", hoster, rawUrl),
                        isStreamEmbed = isEmbed
                    )
                )
            }
        }
        if (allServerRaws.isNotEmpty()) {
            cascadeResolver.mergeExternalCandidates(searchTitle, episodeNum, allServerRaws)
        }

        val needClientExtract =
            resolved.isEmpty() ||
                (preferCompatibleContainer && resolved.none { it.container == MediaContainer.MP4 || it.container == MediaContainer.HLS })
        if (needClientExtract && allServerRaws.isNotEmpty()) {
            val exactRaws = allServerRaws.filter { it.quality == preferredQuality }
            val fallbackRaws = allServerRaws.filter { it.quality != preferredQuality }
            val raws = exactRaws + fallbackRaws
            val extractedFromServerRaws = mutableListOf<ResolvedMkvMedia>()
            for (media in cascadeResolver.resolveFromRaw(raws, preferredQuality, preferCompatibleContainer)) {
                val host = media.directUrl.toHttpUrlOrNull()?.host ?: continue
                if (runCatching { PublicIpGuard.verifyPublicHost(clients.dns, host); true }.getOrDefault(false).not()) continue
                extractedFromServerRaws.add(media)
                if (extractedFromServerRaws.size >= 3) break
            }
            if (extractedFromServerRaws.isNotEmpty()) {
                val combined = (extractedFromServerRaws + resolved).distinctBy { it.directUrl }
                resolved.clear()
                resolved.addAll(combined.sortedBy { if (it.quality == preferredQuality) 0 else 1 })
            }
        }

        val knownQualities = (serverQualities + cascadeResolver.cachedQualitiesFor(searchTitle, episodeNum))
            .distinct()
            .sortedByDescending { it.height }
            .ifEmpty { MkvQuality.ALL_DESC }
        if (resolved.isEmpty()) return Triple(emptyList(), knownQualities, hadAnyServerEntry)
        val ordered = if (preferCompatibleContainer) {
            resolved.sortedWith(
                compareBy<ResolvedMkvMedia> { if (it.quality == preferredQuality) 0 else 1 }
                    .thenBy {
                        when (it.container) {
                            MediaContainer.MP4 -> 0
                            MediaContainer.HLS -> 1
                            MediaContainer.UNKNOWN -> 2
                            MediaContainer.MKV -> 3
                        }
                    }
            )
        } else {
            resolved.sortedBy { if (it.quality == preferredQuality) 0 else 1 }
        }
        return Triple(ordered, knownQualities, hadAnyServerEntry)
    }

    data class BatchPrefetchItem(
        val animeId: Int?,
        val title: String,
        val romajiTitle: String,
        val episode: Int
    )

    fun prefetchBatchFromServer(
        items: List<BatchPrefetchItem>,
        preferredRes: String = "1080p",
        preferCompatibleContainer: Boolean = false
    ): Int {
        if (items.isEmpty()) return 0
        val now = System.currentTimeMillis()
        if (now < serverResolverDisabledUntilMs) return 0
        val preferredQuality = MkvQuality.fromText(preferredRes) ?: MkvQuality.P1080
        val modeTag = if (preferCompatibleContainer) "compat" else "std"

        val pending = items.filter { item ->
            val searchTitle = item.romajiTitle.ifBlank { item.title }.trim()
            if (searchTitle.length < 2 || item.episode < 0) return@filter false
            val slug = buildEpisodeSlug(searchTitle, item.episode)
            val cacheKey = "${slug.lowercase()}#${preferredQuality.label}#$modeTag"
            val existing = resolvedCache[cacheKey]
            existing == null || now - existing.cachedAtMs > RESOLVED_CACHE_TTL_MS / 2
        }.take(12)
        if (pending.isEmpty()) return 0

        val reqItems = org.json.JSONArray()
        for (it in pending) {
            val searchTitle = it.romajiTitle.ifBlank { it.title }.trim()
            val obj = JSONObject()
                .put("title", searchTitle)
                .put("ep", it.episode)
                .put("q", preferredQuality.label)
            if (it.animeId != null && it.animeId > 0) obj.put("anime_id", it.animeId)
            reqItems.put(obj)
        }
        val payload = JSONObject().put("items", reqItems).toString()
        val url = "${backendBaseUrl.trimEnd('/')}/v1/play/resolve-batch"
        val body = runCatching {
            val req = Request.Builder()
                .url(url)
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("Accept", "application/json")
                .build()
            serverHintClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()
            }
        }.getOrNull() ?: return 0

        val dataObj = runCatching { JSONObject(body).optJSONObject("data") }.getOrNull() ?: return 0
        var populated = 0
        for (item in pending) {
            val searchTitle = item.romajiTitle.ifBlank { item.title }.trim()
            val normKey = searchTitle
                .lowercase(java.util.Locale.ROOT)
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()
                .replace(Regex("\\s+"), " ")
                .take(120) + "#" + item.episode
            val entryObj = dataObj.optJSONObject(normKey) ?: continue
            val directs = entryObj.optJSONArray("direct_candidates")
            val resolved = mutableListOf<ResolvedMkvMedia>()
            val serverQualities = linkedSetOf<MkvQuality>()
            if (directs != null) {
                for (i in 0 until directs.length()) {
                    val obj = directs.optJSONObject(i) ?: continue
                    val directUrl = obj.optString("direct_url").trim()
                    if (directUrl.isBlank() || id.my.milkglass.source.DeadCandidateCache.isDead(directUrl)) continue
                    val host = directUrl.toHttpUrlOrNull()?.host ?: continue
                    val qual = MkvQuality.fromText(obj.optString("quality")) ?: continue
                    serverQualities.add(qual)
                    if (runCatching { PublicIpGuard.verifyPublicHost(clients.dns, host); true }.getOrDefault(false).not()) continue
                    val src = SourcePriority.entries.firstOrNull { it.id == obj.optString("source") } ?: SourcePriority.YLNIME
                    val cont = runCatching { MediaContainer.valueOf(obj.optString("container").uppercase()) }.getOrDefault(MediaContainer.MP4)
                    resolved.add(
                        ResolvedMkvMedia(
                            source = src,
                            quality = qual,
                            hoster = obj.optString("hoster").ifBlank { host },
                            directUrl = directUrl,
                            referer = obj.optString("referer").takeIf { it.isNotBlank() },
                            supportsRange = obj.optBoolean("supports_range", true),
                            container = cont,
                            isHls = obj.optBoolean("is_hls", false),
                            rawUrl = directUrl
                        )
                    )
                }
            }
            val clientArr = entryObj.optJSONArray("client_extract_candidates")
            val raws = ArrayList<RawMkvCandidate>()
            if (clientArr != null) {
                for (i in 0 until minOf(clientArr.length(), 12)) {
                    val obj = clientArr.optJSONObject(i) ?: continue
                    val rawUrl = obj.optString("raw_url").trim()
                    if (!rawUrl.startsWith("https://") || id.my.milkglass.source.DeadCandidateCache.isDead(rawUrl)) continue
                    val qual = MkvQuality.fromText(obj.optString("quality")) ?: continue
                    serverQualities.add(qual)
                    val hoster = obj.optString("hoster").ifBlank { "Hoster" }
                    raws.add(
                        RawMkvCandidate(
                            source = SourcePriority.entries.firstOrNull { it.id == obj.optString("source") } ?: SourcePriority.YLNIME,
                            quality = qual,
                            hosterLabel = hoster,
                            rawUrl = rawUrl,
                            pageReferer = obj.optString("referer").trim(),
                            container = MediaContainer.detect("", hoster, rawUrl),
                            isStreamEmbed = obj.optBoolean("is_stream_embed", isEmbedCandidateUrl(rawUrl, hoster))
                        )
                    )
                }
            }
            if (raws.isNotEmpty()) {
                cascadeResolver.mergeExternalCandidates(searchTitle, item.episode, raws)
            }
            if (resolved.isNotEmpty()) {
                val ordered = resolved.sortedBy { if (it.quality == preferredQuality) 0 else 1 }
                val slug = buildEpisodeSlug(searchTitle, item.episode)
                val cacheKey = "${slug.lowercase()}#${preferredQuality.label}#$modeTag"
                resolvedCache[cacheKey] = cacheEntryFor(ordered, serverQualities.toList())
                populated++
            }
        }
        return populated
    }

    fun invalidateCache(slug: String) {
        val prefix = "${slug.lowercase()}#"
        resolvedCache.keys.removeIf { it.startsWith(prefix) }
    }

    fun resolveConsolidatedMp4Candidates(title: String, episode: Int, quality: MkvQuality): List<ResolvedMkvMedia> {
        val raw = consolidator.getConsolidatedCandidates(title, episode)
        if (raw.isEmpty()) return emptyList()
        val mp4Raw = raw.filter { !it.isBatchArchive && (it.container == MediaContainer.MP4 || it.rawUrl.contains(".mp4", ignoreCase = true)) }
        if (mp4Raw.isEmpty()) return emptyList()
        return cascadeResolver.resolveFromRaw(mp4Raw, quality, preferCompatibleContainer = true, preferStreamingEmbeds = false)
    }

    fun cachedCandidatesFor(slug: String, quality: MkvQuality): List<ResolvedMkvMedia> {
        val nowMs = System.currentTimeMillis()
        val stdKey = "${slug.lowercase()}#${quality.label}#std"
        resolvedCache[stdKey]?.takeIf { nowMs - it.cachedAtMs <= RESOLVED_CACHE_TTL_MS }?.let { return it.candidates }
        val compatKey = "${slug.lowercase()}#${quality.label}#compat"
        resolvedCache[compatKey]?.takeIf { nowMs - it.cachedAtMs <= RESOLVED_CACHE_TTL_MS }?.let { return it.candidates }
        return emptyList()
    }

    fun getCachedSession(
        slug: String,
        preferredRes: String = "1080p",
        preferCompatibleContainer: Boolean = false
    ): LocalStreamSession? {
        val preferredQuality = MkvQuality.fromText(preferredRes) ?: MkvQuality.P1080
        val sessionCacheKey = "${slug.lowercase()}#${preferredQuality.label}#${if (preferCompatibleContainer) "compat" else "std"}"
        val nowMs = System.currentTimeMillis()
        val entry = resolvedCache[sessionCacheKey]?.takeIf { nowMs - it.cachedAtMs <= RESOLVED_CACHE_TTL_MS } ?: return null
        return startSessionForCandidates(
            candidates = entry.candidates,
            availableResolutions = entry.availableResolutions,
            activeResolution = entry.activeResolution,
            resolvedSourceId = entry.resolvedSourceId,
            resolvedHoster = entry.resolvedHoster,
            scope = CoroutineScope(Dispatchers.IO)
        )
    }

    fun prefetchStream(
        slug: String,
        preferredRes: String = "1080p",
        title: String = "",
        romajiTitle: String = "",
        maxAgeMs: Long = RESOLVED_CACHE_TTL_MS,
        anilistId: Int? = null
    ): Boolean {
        val preferredQuality = MkvQuality.fromText(preferredRes) ?: MkvQuality.P1080
        val sessionCacheKey = "${slug.lowercase()}#${preferredQuality.label}#std"
        val nowMs = System.currentTimeMillis()
        val maxAge = maxAgeMs.coerceIn(0L, RESOLVED_CACHE_TTL_MS)
        if (resolvedCache[sessionCacheKey]?.let { nowMs - it.cachedAtMs <= maxAge } == true) {
            return true
        }
        val episodeNum = extractEpisodeNumber(slug)
        val searchTitle = romajiTitle.ifBlank { title }.ifBlank {
            slug.replace(Regex("-ep-\\d+$", RegexOption.IGNORE_CASE), "").replace('-', ' ')
        }
        val mine = CompletableFuture<CachedResolvedCandidates?>()
        val existing = inFlightResolutions.putIfAbsent(sessionCacheKey, mine)
        if (existing != null) {
            return existing.awaitOrNull(25_000L) != null
        }
        return try {
            val outcome = raceResolve(
                searchTitle = searchTitle,
                altTitle = if (searchTitle == romajiTitle) title else romajiTitle,
                episodeNum = episodeNum,
                preferredQuality = preferredQuality,
                preferCompatibleContainer = false,
                joined = null,
                anilistId = anilistId
            )
            if (outcome !is RaceOutcome.Candidates || outcome.list.isEmpty()) {
                mine.complete(null)
                return false
            }
            val entry = cacheEntryFor(outcome.list, outcome.availableQualities)
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
    ): LocalStreamSession = runInterruptible(Dispatchers.IO) {
        resolveAndStartStreamBlocking(slug, preferredRes, title, romajiTitle, anilistId, preferCompatibleContainer, scope)
    }

    private fun resolveAndStartStreamBlocking(
        slug: String,
        preferredRes: String,
        title: String,
        romajiTitle: String,
        anilistId: Int?,
        preferCompatibleContainer: Boolean,
        scope: CoroutineScope
    ): LocalStreamSession {
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
                return LocalStreamSession(
                    localHttpUrl = "file://${localFile.absolutePath}",
                    expectedSizeBytes = localFile.length(),
                    availableResolutions = listOf(foundQuality.label),
                    activeResolution = foundQuality.label,
                    resolvedSourceId = "offline",
                    resolvedHoster = "LocalStorage",
                    cacheHit = true,
                    closeSession = {}
                )
            }
        }

        val sessionCacheKey = "${slug.lowercase()}#${preferredQuality.label}#${if (preferCompatibleContainer) "compat" else "std"}"
        val nowMs = System.currentTimeMillis()
        resolvedCache[sessionCacheKey]?.takeIf { nowMs - it.cachedAtMs <= RESOLVED_CACHE_TTL_MS }?.let { cached ->
            val aliveCandidates = cached.candidates.filterNot {
                id.my.milkglass.source.DeadCandidateCache.isDead(it.directUrl) ||
                    id.my.milkglass.source.DeadCandidateCache.isDead(it.rawUrl)
            }
            if (aliveCandidates.isNotEmpty()) {
                return startFromEntry(cached.copy(candidates = aliveCandidates), scope, cacheHit = true)
            }
            resolvedCache.remove(sessionCacheKey)
        }

        val inflight = inFlightResolutions[sessionCacheKey]
        if (inflight != null) {
            val joinedEntry = inflight.awaitOrNull(JOIN_INFLIGHT_MAX_MS)
            if (joinedEntry != null) return startFromEntry(joinedEntry, scope, cacheHit = true)
        }

        val consolidatedRaw = consolidator.getConsolidatedCandidates(searchTitle, episodeNum)
        if (consolidatedRaw.any { it.quality == preferredQuality }) {
            val fromConsolidated = cascadeResolver.resolveFromRaw(
                raw = consolidatedRaw,
                preferredQuality = preferredQuality,
                preferCompatibleContainer = preferCompatibleContainer
            )
            if (fromConsolidated.isNotEmpty()) {
                val mergedQuals = (consolidatedRaw.map { it.quality } + fromConsolidated.map { it.quality } + cascadeResolver.cachedQualitiesFor(searchTitle, episodeNum))
                    .distinct()
                    .sortedByDescending { it.height }
                val entry = cacheEntryFor(fromConsolidated, mergedQuals)
                if (resolvedCache.size > 32) resolvedCache.clear()
                resolvedCache[sessionCacheKey] = entry
                reportDirect(searchTitle, episodeNum, fromConsolidated)
                return startFromEntry(entry, scope)
            }
        }

        val mine = CompletableFuture<CachedResolvedCandidates?>()
        val existing = inFlightResolutions.putIfAbsent(sessionCacheKey, mine)
        if (existing != null) {
            val joinedEntry = existing.awaitOrNull(JOIN_INFLIGHT_MAX_MS)
            if (joinedEntry != null) return startFromEntry(joinedEntry, scope, cacheHit = true)
        }

        val altTitle = if (searchTitle == romajiTitle) title else romajiTitle
        return try {
            val outcome = raceResolve(
                searchTitle = searchTitle,
                altTitle = altTitle,
                episodeNum = episodeNum,
                preferredQuality = preferredQuality,
                preferCompatibleContainer = preferCompatibleContainer,
                joined = null,
                anilistId = anilistId
            )
            when (outcome) {
                is RaceOutcome.Failed -> {
                    mine.complete(null)
                    throw StreamResolutionException(outcome.reason, outcome.message)
                }
                is RaceOutcome.Candidates -> {
                    val entry = cacheEntryFor(outcome.list, outcome.availableQualities)
                    if (resolvedCache.size > 32) resolvedCache.clear()
                    resolvedCache[sessionCacheKey] = entry
                    mine.complete(entry)
                    startFromEntry(entry, scope, cacheHit = outcome.cacheHit)
                }
            }
        } catch (t: Throwable) {
            mine.complete(null)
            throw t
        } finally {
            inFlightResolutions.remove(sessionCacheKey, mine)
        }
    }

    private fun startSessionForCandidates(
        candidates: List<ResolvedMkvMedia>,
        availableResolutions: List<String>,
        activeResolution: String,
        resolvedSourceId: String,
        resolvedHoster: String,
        @Suppress("UNUSED_PARAMETER") scope: CoroutineScope,
        cacheHit: Boolean = false
    ): LocalStreamSession {
        val primary = candidates.first()
        val isHls = primary.isHls || primary.container == MediaContainer.HLS
        val hdrs = buildMap {
            put("User-Agent", clients.webViewUserAgent)
            if (!primary.referer.isNullOrBlank()) put("Referer", primary.referer)
            putAll(primary.extraHeaders)
        }
        return LocalStreamSession(
            localHttpUrl = primary.directUrl,
            expectedSizeBytes = 0L,
            availableResolutions = availableResolutions,
            activeResolution = activeResolution,
            resolvedSourceId = resolvedSourceId,
            resolvedHoster = resolvedHoster,
            isHls = isHls,
            headers = hdrs,
            candidates = candidates,
            cacheHit = cacheHit,
            closeSession = {}
        )
    }

    fun enrichAvailableResolutionsAsync(
        slug: String,
        title: String,
        romajiTitle: String,
        currentResolutions: List<String>,
        scope: CoroutineScope,
        onResolutionsUpdated: (List<String>) -> Unit
    ): Job = scope.launch(Dispatchers.IO) {
        val episodeNum = extractEpisodeNumber(slug)
        val searchTitle = romajiTitle.ifBlank { title }.ifBlank {
            slug.replace(Regex("-ep-\\d+$", RegexOption.IGNORE_CASE), "").replace('-', ' ')
        }
        if (searchTitle.isBlank() || episodeNum < 0) return@launch
        val existingQualities = (currentResolutions.mapNotNull { MkvQuality.fromText(it) } +
            cascadeResolver.cachedQualitiesFor(searchTitle, episodeNum)).toSet()

        var waitedMs = 0L
        while (id.my.milkglass.player.PlayerManager.isVideoBuffering.value && waitedMs < 1_500L) {
            delay(150L)
            waitedMs += 150L
        }

        val existingSourceId = resolvedCache.entries
            .firstOrNull { it.key.startsWith("${slug.lowercase()}#") }
            ?.value?.resolvedSourceId
        runCatching {
            consolidator.consolidateInBackground(
                animeTitle = searchTitle,
                episodeNumber = episodeNum,
                existingQualities = existingQualities,
                excludeSourceId = existingSourceId,
                onMergedHints = { key, hints ->
                    cascadeResolver.mergeExternalCandidates(searchTitle, episodeNum, hints)
                    reportHint(key, hints)
                },
                onQualitiesUpdated = { mergedQualities ->
                    val allQuals = (mergedQualities + cascadeResolver.cachedQualitiesFor(searchTitle, episodeNum))
                        .distinct()
                        .sortedByDescending { it.height }
                    val labels = allQuals.map { it.label }
                    if (labels.isNotEmpty()) {
                        val slugPrefix = "${slug.lowercase()}#"
                        for ((k, entry) in resolvedCache.entries) {
                            if (k.startsWith(slugPrefix)) {
                                resolvedCache[k] = entry.copy(availableResolutions = labels)
                            }
                        }
                        onResolutionsUpdated(labels)
                    }
                }
            )
        }
    }

    fun extractEpisodeNumber(slug: String): Int = SeriesEpisodeMapCache.extractEpisodeNumberFromSlug(slug)

    companion object {
        const val DEFAULT_BACKEND_BASE_URL = MilkglassEndpoints.DEFAULT_BACKEND_BASE_URL
        private const val RESOLVED_CACHE_TTL_MS = 10L * 60L * 1000L
        private const val JOIN_INFLIGHT_MAX_MS = 10_000L
        private const val SERVER_MISS_STREAK_LIMIT = 3
        private const val SERVER_QUIET_MS = 10L * 60L * 1000L
        private const val SERVER_HINT_TIMEOUT_MS = 3_500L

        fun isEmbedCandidateUrl(rawUrl: String, hosterLabel: String = ""): Boolean {
            val lowUrl = rawUrl.lowercase()
            val lowHost = hosterLabel.lowercase()
            if (lowUrl.contains("pixeldrain.") || lowUrl.contains(".animeverse.id/") || lowUrl.contains(".animekita.org/")) {
                return false
            }
            return lowHost == "ylnimeplayer" ||
                lowHost.contains("embed") ||
                lowUrl.contains("desustream.") ||
                lowUrl.contains("odstream.") ||
                lowUrl.contains("ondesu.") ||
                lowUrl.contains("ondesuh.") ||
                lowUrl.contains("blogger.com/video") ||
                lowUrl.contains("otakuwatch") ||
                lowUrl.contains("icefile.web.id") ||
                lowUrl.contains("filemoon.") ||
                lowUrl.contains("vidhide") ||
                lowUrl.contains("streamruby.") ||
                lowUrl.contains("rubyvidhub.") ||
                lowUrl.contains("filedon.co/embed/") ||
                (lowUrl.contains("ylnime.com/index.php") && !lowUrl.contains(".mp4") && !lowUrl.contains(".m3u8"))
        }

        fun buildEpisodeSlug(romajiOrTitle: String, episode: Int): String {
            val clean = romajiOrTitle.lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .ifBlank { "anime" }
            return "$clean-ep-$episode"
        }
    }
}
