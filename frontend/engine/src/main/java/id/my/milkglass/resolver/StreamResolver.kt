package id.my.milkglass.resolver

import android.content.Context
import id.my.milkglass.MilkglassEndpoints
import id.my.milkglass.download.EndpointHealthScorer
import id.my.milkglass.download.MkvDownloadManager
import id.my.milkglass.net.IsolatedHttpClients
import id.my.milkglass.net.PublicIpGuard
import id.my.milkglass.source.CascadeResult
import id.my.milkglass.source.KusonimeSourceProvider
import id.my.milkglass.source.MediaContainer
import id.my.milkglass.source.MkvCascadeResolver
import id.my.milkglass.source.MkvQuality
import id.my.milkglass.source.OploverzSourceProvider
import id.my.milkglass.source.OtakudesuSourceProvider
import id.my.milkglass.source.RawMkvCandidate
import id.my.milkglass.source.ResolvedMkvMedia
import id.my.milkglass.source.SamehadakuSourceProvider
import id.my.milkglass.source.SourcePriority
import id.my.milkglass.source.SourceSlugCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
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
    val resolvedSourceId: String = "otakudesu",
    val resolvedHoster: String = "Pixeldrain",
    val isHls: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
    val candidates: List<ResolvedMkvMedia> = emptyList(),
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
    private val hintReportedAt = ConcurrentHashMap<String, Long>()
    /**
     * Laporkan URL direct yang mati (403/404/410) secara asinkron agar Edge/D1 segera mengeviksi.
     */
    fun reportDeadDirectUrl(title: String, ep: Int, directUrl: String, httpCode: Int) {
        if (!hasAuthSession() || !directUrl.startsWith("https://")) return
        if (title.trim().length < 2 || ep <= 0) return
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

    /**
     * Mengembalikan daftar kandidat ter-cache (segar) untuk kebutuhan pre-buffering CacheWriter.
     */
    fun getCachedCandidates(
        slug: String,
        preferredRes: String = "1080p",
        preferCompatibleContainer: Boolean = false
    ): List<ResolvedMkvMedia>? {
        val normRes = if (preferredRes == "Auto") "1080p" else preferredRes
        val key = "${slug.lowercase()}|${normRes.lowercase()}|$preferCompatibleContainer"
        val cached = resolvedCache[key] ?: return null
        if (System.currentTimeMillis() - cached.cachedAtMs > RESOLVED_CACHE_TTL_MS) return null
        return cached.candidates.takeIf { it.isNotEmpty() }
    }

    /**
     * Laporkan tautan hoster mentah yang terbukti bisa diekstrak ke Cloudflare (tabel hint bersama), supaya
     * pengguna berikutnya melewati pencarian judul + scrape halaman. Fire-and-forget, throttle 30 menit per kunci.
     * Server hanya menyimpan petunjuk; klien SELALU mengekstrak + memverifikasi sendiri sebelum memutar.
     */
    fun reportHint(cacheKey: String, candidates: List<RawMkvCandidate>) {
        if (candidates.isEmpty() || !hasAuthSession()) return
        val now = System.currentTimeMillis()
        if (now < serverResolverDisabledUntilMs) return
        val title = cacheKey.substringBeforeLast('#', "").trim()
        val ep = cacheKey.substringAfterLast('#', "").toIntOrNull() ?: return
        if (title.length < 2 || ep <= 0) return
        val last = hintReportedAt[cacheKey]
        if (last != null && now - last < 30L * 60L * 1000L) return
        if (hintReportedAt.size > 64) hintReportedAt.clear()
        hintReportedAt[cacheKey] = now
        hintExecutor.execute {
            runCatching {
                val arr = JSONArray()
                candidates.filter { !it.isBatchArchive }.take(6).forEach { c ->
                    arr.put(
                        JSONObject().put("source", c.source.id).put("quality", c.quality.label)
                            .put("hoster", c.hosterLabel.take(40)).put("raw_url", c.rawUrl)
                            .put("referer", c.pageReferer)
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

    private val directReportedAt = ConcurrentHashMap<String, Long>()

    /**
     * Laporkan URL direct yang SUDAH terverifikasi ke Cloudflare (tabel play_direct), fire-and-forget.
     * Throttle 5 menit per judul+episode (server juga melewati tulis bila masih segar).
     */
    fun reportDirect(title: String, ep: Int, list: List<ResolvedMkvMedia>) {
        if (list.isEmpty() || ep <= 0 || title.trim().length < 2 || !hasAuthSession()) return
        val now = System.currentTimeMillis()
        if (now < serverResolverDisabledUntilMs) return
        val key = "${title.lowercase().trim()}#$ep"
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

    /** Menunggu future tanpa menelan interupsi (pembatalan harus merambat), timeout/kegagalan -> null. */
    private fun <T> CompletableFuture<T>.awaitOrNull(ms: Long): T? =
        try {
            get(ms, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            throw e
        } catch (_: Exception) {
            null
        }

    // ---------------------------------------------------------------------------------------------
    // Balapan server (Cloudflare hint) vs cascade lokal.
    // Dulu: server dulu (sampai 3,5 dtk), BARU cascade. Server hampir selalu miss untuk episode yang belum pernah
    // diputar siapa pun, jadi jalur dingin selalu membayar waktu mati itu. Sekarang keduanya jalan bersamaan:
    // yang pertama memberi kandidat valid menang, yang kalah diinterupsi (dan pembatalan tidak dihitung sebagai
    // kegagalan sumber). Prefetch yang sedang berjalan untuk episode yang sama ikut jadi peserta ketiga.
    // ---------------------------------------------------------------------------------------------
    private sealed interface RaceOutcome {
        class Candidates(val list: List<ResolvedMkvMedia>, val availableQualities: List<MkvQuality> = emptyList()) : RaceOutcome
        class Failed(val reason: FailureReason, val message: String) : RaceOutcome
    }

    private sealed interface RaceMsg {
        class FromServer(val list: List<ResolvedMkvMedia>) : RaceMsg
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
        if (!hasAuthSession()) return false
        val now = System.currentTimeMillis()
        return now >= serverResolverDisabledUntilMs && now >= serverQuietUntilMs
    }

    /** Server yang terus miss (tak ada hint) tidak perlu ditanya tiap kali: istirahat 10 menit setelah 3 miss beruntun. */
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
        val avail = (knownQualities.map { it.label } + list.map { it.quality.label })
            .distinct()
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

    private fun startFromEntry(e: CachedResolvedCandidates, scope: CoroutineScope): LocalStreamSession =
        startSessionForCandidates(
            candidates = e.candidates,
            availableResolutions = e.availableResolutions,
            activeResolution = e.activeResolution,
            resolvedSourceId = e.resolvedSourceId,
            resolvedHoster = e.resolvedHoster,
            scope = scope
        )

    private fun raceResolve(
        searchTitle: String,
        altTitle: String,
        episodeNum: Int,
        preferredQuality: MkvQuality,
        preferCompatibleContainer: Boolean,
        joined: CompletableFuture<CachedResolvedCandidates?>?
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
                            val list = resolveViaServerSplitPlane(searchTitle, episodeNum, preferredQuality, preferCompatibleContainer)
                            noteServerOutcome(list.isNotEmpty())
                            msg = RaceMsg.FromServer(list)
                        } catch (_: InterruptedException) {
                            // kalah balapan / dibatalkan: bukan kegagalan server
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
                // take() melempar InterruptedException bila pemanggil dibatalkan (ganti episode): finally membatalkan semua peserta.
                when (val msg = queue.take()) {
                    is RaceMsg.FromServer -> {
                        pending--
                        if (msg.list.isNotEmpty()) return RaceOutcome.Candidates(msg.list.distinctBy { it.directUrl })
                    }
                    is RaceMsg.FromJoin -> {
                        pending--
                        val e = msg.entry
                        if (e != null && e.candidates.isNotEmpty()) {
                            val ok = publicOnly(e.candidates)
                            val knownQ = e.availableResolutions.mapNotNull { MkvQuality.fromText(it) }
                            if (ok.isNotEmpty()) return RaceOutcome.Candidates(ok, knownQ)
                        }
                    }
                    is RaceMsg.FromCascade -> {
                        pending--
                        val r = msg.result
                        if (r is CascadeResult.Success) {
                            val ok = publicOnly(listOf(r.selected) + r.alternatives)
                            if (ok.isNotEmpty()) {
                                // Simpan otomatis di Cloudflare supaya pengguna lain tak perlu scraping + ekstraksi.
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
        preferCompatibleContainer: Boolean
    ): List<ResolvedMkvMedia> {
        if (!hasAuthSession()) return emptyList()
        val now = System.currentTimeMillis()
        if (now < serverResolverDisabledUntilMs) return emptyList()
        val url = "${backendBaseUrl.trimEnd('/')}/v1/play/resolve" +
            "?title=${URLEncoder.encode(searchTitle, "UTF-8")}" +
            "&ep=$episodeNum&q=${preferredQuality.label}"
        val req = Request.Builder().url(url).header("Accept", "application/json").build()
        // Server hanyalah jalan pintas: dibatasi ketat supaya tidak pernah menahan alur lokal.
        val body = try {
            serverHintClient.newCall(req).execute().use { resp ->
                val backoffMs = when {
                    resp.code == 404 -> 6L * 60L * 60L * 1000L   // endpoint belum ada di backend: jangan tanya lagi berjam-jam
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
                if (!serverSignatureVerifier(rawBody, resp.header("X-Milkglass-Server-Sig"))) {
                    return@use null
                }
                rawBody
            }
        } catch (e: InterruptedException) {
            throw e
        } catch (e: java.io.InterruptedIOException) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            serverResolverDisabledUntilMs = System.currentTimeMillis() + 20_000L  // timeout: istirahat singkat
            null
        } catch (e: Exception) {
            if (Thread.currentThread().isInterrupted || e.message.equals("Canceled", ignoreCase = true)) {
                throw InterruptedException("Cancelled")
            }
            serverResolverDisabledUntilMs = System.currentTimeMillis() + 20_000L
            null
        } ?: return emptyList()

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

        // URL direct dari server wajib lolos probe sebelum dipakai (bisa kedaluwarsa / terikat IP penyetor).
        if (resolved.isNotEmpty()) {
            val verified = cascadeResolver.verifyDirect(resolved)
            resolved.clear()
            resolved.addAll(verified)
        }

        val needClientExtract = resolved.isEmpty() || (preferCompatibleContainer && resolved.none { it.container == MediaContainer.MP4 || it.container == MediaContainer.HLS })
        val clientArr = if (needClientExtract) data.optJSONArray("client_extract_candidates") else null
        if (clientArr != null) {
            // Hint dari Cloudflare diekstrak PARALEL (bukan satu per satu) lewat jalur cascade yang sama.
            val raws = ArrayList<RawMkvCandidate>()
            for (i in 0 until minOf(clientArr.length(), 8)) {
                val obj = clientArr.optJSONObject(i) ?: continue
                val rawUrl = obj.optString("raw_url").trim()
                if (!rawUrl.startsWith("https://")) continue
                val hoster = obj.optString("hoster").ifBlank { "Hoster" }
                raws.add(
                    RawMkvCandidate(
                        source = SourcePriority.entries.firstOrNull { it.id == obj.optString("source") } ?: SourcePriority.OTAKUDESU,
                        quality = MkvQuality.fromText(obj.optString("quality")) ?: preferredQuality,
                        hosterLabel = hoster,
                        rawUrl = rawUrl,
                        pageReferer = obj.optString("referer").trim(),
                        container = MediaContainer.detect("", hoster, rawUrl)
                    )
                )
            }
            for (media in cascadeResolver.resolveFromRaw(raws, preferredQuality, preferCompatibleContainer)) {
                val host = media.directUrl.toHttpUrlOrNull()?.host ?: continue
                if (runCatching { PublicIpGuard.verifyPublicHost(clients.dns, host); true }.getOrDefault(false).not()) continue
                resolved.add(media)
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
        maxAgeMs: Long = RESOLVED_CACHE_TTL_MS
    ): Boolean {
        val preferredQuality = MkvQuality.fromText(preferredRes) ?: MkvQuality.P1080
        val sessionCacheKey = "${slug.lowercase()}#${preferredQuality.label}#std"
        val nowMs = System.currentTimeMillis()
        // maxAgeMs < TTL = "segarkan bila lebih tua dari ini". Entri lama tetap dipakai kalau penyegaran gagal.
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
                joined = null
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

    /**
     * Pembatalan coroutine (mis. pengguna ganti episode) kini benar-benar menginterupsi thread yang memblok,
     * sehingga resolve episode lama tidak lagi menghabiskan pool provider/ekstraksi dan mengantre episode baru.
     */
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
        @Suppress("UNUSED_PARAMETER") anilistId: Int?,
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
                    closeSession = {}
                )
            }
        }

        val sessionCacheKey = "${slug.lowercase()}#${preferredQuality.label}#${if (preferCompatibleContainer) "compat" else "std"}"
        val nowMs = System.currentTimeMillis()
        resolvedCache[sessionCacheKey]?.takeIf { nowMs - it.cachedAtMs <= RESOLVED_CACHE_TTL_MS }?.let { cached ->
            return startFromEntry(cached, scope)
        }

        // Prefetch yang sedang jalan: tunggu sebentar saja. Bila belum selesai, resolve sendiri dimulai SEKARANG dan
        // prefetch tetap ikut berlomba (dulu: menunggu sampai 12 dtk lalu mengulang dari nol).
        val inflight = inFlightResolutions[sessionCacheKey]
        if (inflight != null) {
            val joinedEntry = inflight.awaitOrNull(JOIN_FAST_MS)
            if (joinedEntry != null) return startFromEntry(joinedEntry, scope)
        }

        val altTitle = if (searchTitle == romajiTitle) title else romajiTitle
        val outcome = raceResolve(
            searchTitle = searchTitle,
            altTitle = altTitle,
            episodeNum = episodeNum,
            preferredQuality = preferredQuality,
            preferCompatibleContainer = preferCompatibleContainer,
            joined = inflight
        )
        return when (outcome) {
            is RaceOutcome.Failed -> throw StreamResolutionException(outcome.reason, outcome.message)
            is RaceOutcome.Candidates -> {
                val entry = cacheEntryFor(outcome.list, outcome.availableQualities)
                if (resolvedCache.size > 32) resolvedCache.clear()
                resolvedCache[sessionCacheKey] = entry
                startFromEntry(entry, scope)
            }
        }
    }

    private fun startSessionForCandidates(
        candidates: List<ResolvedMkvMedia>,
        availableResolutions: List<String>,
        activeResolution: String,
        resolvedSourceId: String,
        resolvedHoster: String,
        @Suppress("UNUSED_PARAMETER") scope: CoroutineScope
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
            closeSession = {}
        )
    }

    fun extractEpisodeNumber(slug: String): Int {
        return Regex("-ep-(\\d+)$", RegexOption.IGNORE_CASE)
            .find(slug)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
    }

    companion object {
        const val DEFAULT_BACKEND_BASE_URL = MilkglassEndpoints.DEFAULT_BACKEND_BASE_URL
        private const val RESOLVED_CACHE_TTL_MS = 10L * 60L * 1000L
        private const val JOIN_INFLIGHT_MAX_MS = 12_000L
        private const val JOIN_FAST_MS = 1_500L
        private const val SERVER_MISS_STREAK_LIMIT = 3
        private const val SERVER_QUIET_MS = 10L * 60L * 1000L
        private const val SERVER_HINT_TIMEOUT_MS = 3_500L

        fun buildEpisodeSlug(romajiOrTitle: String, episode: Int): String {
            val clean = romajiOrTitle.lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .ifBlank { "anime" }
            return "$clean-ep-$episode"
        }
    }
}
