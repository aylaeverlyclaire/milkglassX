package id.my.milkglass

import android.content.Context
import id.my.milkglass.net.DiskCachedJsonStore
import id.my.milkglass.auth.AuthBearerInterceptor
import id.my.milkglass.auth.SessionTokenStore
import id.my.milkglass.auth.SynchronizedTokenAuthenticator
import id.my.milkglass.download.EndpointHealthScorer
import id.my.milkglass.net.IsolatedHttpClients
import id.my.milkglass.net.RemoteClearance
import id.my.milkglass.resolver.StreamResolver
import id.my.milkglass.source.DeadCandidateCache
import id.my.milkglass.source.MkvCascadeResolver
import id.my.milkglass.source.RawCandidateStore
import id.my.milkglass.source.RemoteRulesStore
import id.my.milkglass.source.RawMkvCandidate
import id.my.milkglass.source.SourceCircuitBreaker
import id.my.milkglass.source.SourceAvailabilityIndex
import id.my.milkglass.source.SeriesEpisodeMapCache
import id.my.milkglass.source.SourcePriority
import id.my.milkglass.source.SourceSlugCache
import id.my.milkglass.source.StreamingFirstGuards
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import id.my.milkglass.player.PlayerManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore as CoroutineSemaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object MilkglassEndpoints {
    const val DEFAULT_BACKEND_BASE_URL = "https://milkglass-backend.milkyliquid.workers.dev"

    private val trustedExactHosts = setOf(
        "milkglass-backend.milkyliquid.workers.dev",
        "milkglass.my.id"
    )

    fun isTrustedBackendHost(host: String): Boolean {
        val h = host.trim().lowercase()
        return h in trustedExactHosts || h.endsWith(".milkglass.my.id")
    }
}

data class HttpTextResponse(
    val code: Int,
    val body: String,
    val etag: String? = null
)

data class LiveEpisodeSnapshot(
    val maxEpisode: Int,
    val hasEpisodeZero: Boolean
)

interface EngineHttpTextClient {
    @Throws(IOException::class)
    fun get(url: String, headers: Map<String, String> = emptyMap()): HttpTextResponse

    @Throws(IOException::class)
    fun postJson(url: String, jsonBody: String, headers: Map<String, String> = emptyMap()): HttpTextResponse
}

class SourceConfigStore(
    context: Context?,
    private val clients: IsolatedHttpClients,
    private val backendBaseUrl: String = MilkglassEndpoints.DEFAULT_BACKEND_BASE_URL
) {
    private val diskCache = DiskCachedJsonStore(
        context = context,
        prefsName = "milkglass_source_config_v2",
        ttlMs = CONFIG_TTL_MS,
        maxDiskAgeMs = CONFIG_TTL_MS * 4L
    )

    private val urls = ConcurrentHashMap<SourcePriority, String>()

    @Volatile
    private var dynamicOrder: List<SourcePriority> = SourcePriority.entries

    init {
        SourcePriority.entries.forEach { urls[it] = it.defaultBaseUrl }
        diskCache.loadCached()?.let { applyRawJson(it) }
    }

    fun baseUrlFor(source: SourcePriority): String = urls[source] ?: source.defaultBaseUrl

    fun orderedSources(): List<SourcePriority> = dynamicOrder

    fun snapshot(): Map<SourcePriority, String> = urls.toMap()

    private fun applyRawJson(raw: String): Boolean {
        val parsed = parseSourcesJson(raw)
        if (parsed.isEmpty()) return false
        parsed.forEach { (k, v) -> urls[k] = v }
        val serverOrder = parsed.keys.toList()
        val missing = SourcePriority.entries.filterNot { it in serverOrder }
        dynamicOrder = serverOrder + missing
        return true
    }

    fun refreshIfNeeded(force: Boolean = false): Boolean =
        diskCache.refreshIfNeeded(
            client = clients.mediaAndApiClient,
            url = "${backendBaseUrl.trimEnd('/')}/v1/catalog/sources",
            force = force,
            onBody = ::applyRawJson
        )

    companion object {
        private const val CONFIG_TTL_MS = 6L * 60L * 60L * 1000L

        fun parseSourcesJson(json: String): Map<SourcePriority, String> {
            val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
            val arr = root.optJSONArray("sources") ?: return emptyMap()
            val ranked = mutableListOf<Triple<Int, SourcePriority, String>>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val id = item.optString("id").trim().lowercase()
                val baseUrl = item.optString("base_url").trim().trimEnd('/')
                if (!RemoteRulesStore.isSafeSourceBaseUrl(baseUrl)) continue
                val src = SourcePriority.entries.firstOrNull { it.id == id } ?: continue
                val rank = item.optInt("rank", i + 1)
                ranked.add(Triple(rank, src, baseUrl))
            }
            val out = linkedMapOf<SourcePriority, String>()
            ranked.sortedBy { it.first }.forEach { (_, src, url) -> out[src] = url }
            return out
        }
    }
}

class EngineRuntime private constructor(private val appContext: Context) {
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightPrefetches = ConcurrentHashMap.newKeySet<String>()
    private val liveEpisodeProbeCache = ConcurrentHashMap<String, Pair<Long, LiveEpisodeSnapshot>>()
    private val prefetchGate = CoroutineSemaphore(1)
    @Volatile private var latestPrefetchKey: String = ""

    @Volatile
    private var remoteCfDisabledUntilMs: Long = 0L

    @Volatile
    private var lastForcedConfigRefreshMs: Long = 0L

    @Volatile
    var onRequireReLogin: (() -> Unit)? = null

    init {
        EndpointHealthScorer.attachContext(appContext)
    }

    val sessionStore: SessionTokenStore = SessionTokenStore(appContext)

    val clients: IsolatedHttpClients = IsolatedHttpClients(
        context = appContext,
        remoteClearanceSolver = { _, targetUrl -> fetchRemoteClearance(targetUrl) },
        backendAuthInterceptor = AuthBearerInterceptor(sessionStore),
        backendAuthenticatorFactory = { refreshBase ->
            SynchronizedTokenAuthenticator(
                tokenStore = sessionStore,
                refreshHttpClient = refreshBase,
                apiBaseUrl = MilkglassEndpoints.DEFAULT_BACKEND_BASE_URL,
                onRequireReLogin = { onRequireReLogin?.invoke() }
            )
        }
    )

    val slugCache: SourceSlugCache by lazy { SourceSlugCache(appContext) }
    val sourceConfig: SourceConfigStore = SourceConfigStore(appContext, clients)
    val remoteRules: RemoteRulesStore by lazy { RemoteRulesStore(appContext, clients) }

    private fun resolveSourceBaseUrl(source: SourcePriority): String =
        remoteRules.baseUrlFor(source) ?: sourceConfig.baseUrlFor(source)

    val availabilityIndex: SourceAvailabilityIndex by lazy {
        SourceAvailabilityIndex(
            context = appContext,
            clients = clients,
            slugCache = slugCache,
            baseUrlProvider = { resolveSourceBaseUrl(it) }
        )
    }

    val rawCandidateStore: RawCandidateStore by lazy { RawCandidateStore(appContext) }

    /** Diisi setelah streamResolver dibuat (cascade dibuat lebih dulu). */
    @Volatile private var hintSink: ((String, List<RawMkvCandidate>) -> Unit)? = null

    val cascadeResolver: MkvCascadeResolver by lazy {
        MkvCascadeResolver(
            clients = clients,
            slugCache = slugCache,
            baseUrlProvider = { resolveSourceBaseUrl(it) },
            sourceOrderProvider = { sourceConfig.orderedSources() },
            indexLookup = { source, title -> availabilityIndex.lookupBestUrl(source, title) },
            remoteHtmlFetcher = { url -> fetchRemoteHtml(url) },
            onAllSourcesFailed = { triggerSourceConfigRecovery() },
            rawStore = rawCandidateStore,
            onEpisodeResolved = { key, list -> hintSink?.invoke(key, list) },
            embedSniffer = { url, referer ->
                id.my.milkglass.extractor.EmbedMediaSniffer.sniff(
                    context = appContext,
                    embedUrl = url,
                    pageReferer = referer,
                    userAgent = clients.webViewUserAgent
                )
            }
        )
    }

    val streamResolver: StreamResolver by lazy {
        StreamResolver(
            context = appContext,
            backendBaseUrl = MilkglassEndpoints.DEFAULT_BACKEND_BASE_URL,
            clients = clients,
            slugCache = slugCache,
            hasAuthSession = { sessionStore.hasActiveSession() },
            serverSignatureVerifier = { body, sig -> sessionStore.verifyServerSignature(body, sig) },
            cascadeResolver = cascadeResolver
        ).also { resolver -> hintSink = resolver::reportHint }
    }

    val httpTextClient: EngineHttpTextClient = object : EngineHttpTextClient {
        private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

        override fun get(url: String, headers: Map<String, String>): HttpTextResponse {
            val builder = Request.Builder().url(url).header("Accept", "application/json")
            headers.forEach { (k, v) -> builder.header(k, v) }
            clients.mediaAndApiClient.newCall(builder.build()).execute().use { resp ->
                return HttpTextResponse(resp.code, resp.body?.string().orEmpty(), resp.header("ETag"))
            }
        }

        override fun postJson(
            url: String,
            jsonBody: String,
            headers: Map<String, String>
        ): HttpTextResponse {
            val builder = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .post(jsonBody.toRequestBody(jsonMediaType))
            headers.forEach { (k, v) -> builder.header(k, v) }
            clients.mediaAndApiClient.newCall(builder.build()).execute().use { resp ->
                return HttpTextResponse(resp.code, resp.body?.string().orEmpty(), resp.header("ETag"))
            }
        }
    }

    private fun triggerSourceConfigRecovery() {
        val now = System.currentTimeMillis()
        if (now - lastForcedConfigRefreshMs < 2L * 60L * 1000L) return
        lastForcedConfigRefreshMs = now
        backgroundScope.launch {
            runCatching { sourceConfig.refreshIfNeeded(force = true) }
            runCatching { remoteRules.refreshIfNeeded(force = true) }
        }
    }

    private fun fetchRemoteClearance(targetUrl: String): RemoteClearance? {
        val now = System.currentTimeMillis()
        if (now < remoteCfDisabledUntilMs) return null
        if (!sessionStore.hasActiveSession()) return null

        val endpoint = "${MilkglassEndpoints.DEFAULT_BACKEND_BASE_URL}/v1/cf/cookies?url=${URLEncoder.encode(targetUrl, "UTF-8")}"
        val req = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .build()

        return runCatching {
            clients.cfSolverClient.newCall(req).execute().use { resp ->
                if (resp.code == 503 || resp.code == 429) {
                    remoteCfDisabledUntilMs = System.currentTimeMillis() + 5L * 60L * 1000L
                    return@use null
                }
                if (!resp.isSuccessful) return@use null
                val raw = resp.body?.string().orEmpty()
                if (!sessionStore.verifyServerSignature(raw, resp.header("X-Milkglass-Server-Sig"))) {
                    return@use null
                }
                parseRemoteClearanceJson(raw)
            }
        }.getOrNull()
    }

    private fun fetchRemoteHtml(targetUrl: String): String? {
        val now = System.currentTimeMillis()
        val remoteHtml = if (now >= remoteCfDisabledUntilMs && sessionStore.hasActiveSession()) {
            val endpoint = "${MilkglassEndpoints.DEFAULT_BACKEND_BASE_URL}/v1/cf/html?url=${URLEncoder.encode(targetUrl, "UTF-8")}"
            val req = Request.Builder()
                .url(endpoint)
                .header("Accept", "application/json")
                .build()

            runCatching {
                clients.cfSolverClient.newCall(req).execute().use { resp ->
                    if (resp.code == 503 || resp.code == 429) {
                        remoteCfDisabledUntilMs = System.currentTimeMillis() + 5L * 60L * 1000L
                        return@use null
                    }
                    if (!resp.isSuccessful) return@use null
                    val raw = resp.body?.string().orEmpty()
                    if (!sessionStore.verifyServerSignature(raw, resp.header("X-Milkglass-Server-Sig"))) {
                        return@use null
                    }
                    val root = JSONObject(raw)
                    if (root.optString("status") != "ok") return@use null
                    root.optJSONObject("data")?.optString("html")?.takeIf { it.length >= 64 }
                }
            }.getOrNull()
        } else {
            null
        }
        if (remoteHtml != null) return remoteHtml
        return id.my.milkglass.net.SilentWebViewCfHandler.fetchHtmlSilently(
            context = appContext,
            url = targetUrl,
            userAgent = clients.webViewUserAgent,
            timeoutMs = 3_500L
        )
    }

    fun prewarmCloudflareEdge() {
        backgroundScope.launch {
            runCatching {
                val req = Request.Builder()
                    .url("${MilkglassEndpoints.DEFAULT_BACKEND_BASE_URL}/v1/health")
                    .head()
                    .build()
                clients.baseMediaAndApiClient.newCall(req).execute().use { }
            }
        }
    }

    fun onNetworkRestored(onIndexUpdated: (() -> Unit)? = null) {
        SourceCircuitBreaker.resetAll()
        DeadCandidateCache.clear()
        remoteCfDisabledUntilMs = 0L
        id.my.milkglass.net.SilentWebViewCfHandler.clearCooldowns()
        id.my.milkglass.extractor.EmbedMediaSniffer.clearCooldowns()
        prewarmCloudflareEdge()
        backgroundScope.launch {
            runCatching { sourceConfig.refreshIfNeeded() }
            runCatching { remoteRules.refreshIfNeeded() }
            if (!availabilityIndex.isReady) {
                val count = runCatching { availabilityIndex.refreshIfNeeded() }.getOrDefault(0)
                if (count > 0) onIndexUpdated?.invoke()
            }
        }
    }

    /** Menunggu giliran prefetch secara non-blocking, langsung menyerah bila ada permintaan lebih baru. */
    private suspend fun acquirePrefetchGate(key: String): Boolean {
        val acquired = withTimeoutOrNull(30_000L) {
            prefetchGate.acquire()
            true
        } ?: false
        if (!acquired) return false
        if (latestPrefetchKey != key) {
            prefetchGate.release()
            return false
        }
        return true
    }

    /**
     * Tanamkan URL series langsung dari katalog D1 (`source_urls`) ke `slugCache`
     * sehingga pemutaran dan prefetch langsung melewati langkah pencarian judul (`/?s=...`).
     */
    fun seedSourceUrls(title: String, romajiTitle: String, sourceUrls: Map<String, String>) {
        if (sourceUrls.isEmpty()) return
        val titles = listOf(title, romajiTitle).map { it.trim() }.filter { it.length >= 2 }.distinct()
        if (titles.isEmpty()) return
        for ((rawKey, url) in sourceUrls) {
            if (!url.startsWith("https://")) continue
            val priority = SourcePriority.entries.firstOrNull { it.id.equals(rawKey.trim(), ignoreCase = true) }
                ?: continue
            for (t in titles) {
                slugCache.put(priority, t, url)
            }
        }
    }

    /**
     * Memeriksa jumlah episode terbaru secara live & paralel dari halaman series Otakudesu & YLnime
     * (TTL 2 menit) sekaligus mengisi `SeriesEpisodeMapCache` agar episode yang baru rilis
     * langsung muncul di DetailScreen/PlayerScreen dan siap diputar tanpa menunggu cron katalog.
     */
    suspend fun probeLiveEpisodes(
        title: String,
        romajiTitle: String,
        sourceUrls: Map<String, String>
    ): LiveEpisodeSnapshot? = withContext(Dispatchers.IO) {
        val searchTitle = romajiTitle.ifBlank { title }.trim()
        val cacheKey = searchTitle.lowercase(Locale.ROOT)
        val now = System.currentTimeMillis()
        liveEpisodeProbeCache[cacheKey]?.let { (ts, snap) ->
            if (now - ts < LIVE_PROBE_TTL_MS) return@withContext snap
        }

        val targets = linkedMapOf<SourcePriority, String>()
        for ((rawKey, url) in sourceUrls) {
            if (!url.startsWith("https://")) continue
            val p = SourcePriority.entries.firstOrNull { it.id.equals(rawKey.trim(), ignoreCase = true) } ?: continue
            targets[p] = url
        }
        val titles = listOf(title, romajiTitle).map { it.trim() }.filter { it.length >= 2 }.distinct()
        for (p in sourceConfig.orderedSources()) {
            if (p in targets) continue
            val cached = titles.firstNotNullOfOrNull { slugCache.get(p, it) }
                ?: titles.firstNotNullOfOrNull { availabilityIndex.lookupBestUrl(p, it) }
            if (!cached.isNullOrBlank() && cached.startsWith("https://")) {
                targets[p] = cached
            }
        }
        if (targets.isEmpty()) return@withContext null

        val maps = withTimeoutOrNull(4_500L) {
            coroutineScope {
                targets.entries.map { (priority, seriesUrl) ->
                    async {
                        runCatching {
                            val html = fetchSeriesHtmlForProbe(priority, seriesUrl) ?: return@runCatching emptyMap<Int, String>()
                            val parsed = parseLiveEpisodeMap(priority, html, seriesUrl)
                            if (parsed.isNotEmpty()) {
                                SeriesEpisodeMapCache.putAll(priority, seriesUrl, parsed)
                            }
                            parsed
                        }.getOrDefault(emptyMap<Int, String>())
                    }
                }.awaitAll()
            }
        }.orEmpty()

        var maxEp = 0
        var hasEp0 = false
        for (m in maps) {
            if (0 in m) hasEp0 = true
            val hi = m.keys.maxOrNull() ?: 0
            if (hi > maxEp) maxEp = hi
        }
        if (maxEp <= 0 && !hasEp0) return@withContext null
        val snap = LiveEpisodeSnapshot(maxEpisode = maxEp, hasEpisodeZero = hasEp0)
        liveEpisodeProbeCache[cacheKey] = System.currentTimeMillis() to snap
        snap
    }

    private fun fetchSeriesHtmlForProbe(priority: SourcePriority, seriesUrl: String): String? {
        val referer = "${resolveSourceBaseUrl(priority).trimEnd('/')}/"
        val req = Request.Builder().url(seriesUrl).header("Referer", referer).build()
        val direct = runCatching {
            clients.sourceClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                if (body.length < 128 || StreamingFirstGuards.isWafBlockedHtml(body)) null else body
            }
        }.getOrNull()
        if (direct != null) return direct
        return runCatching { fetchRemoteHtml(seriesUrl) }.getOrNull()
    }

    fun prefetchEpisode(
        title: String,
        romajiTitle: String,
        episode: Int,
        preferredRes: String = "1080p",
        maxAgeMs: Long = Long.MAX_VALUE,
        prebuffer: Boolean = false,
        prebufferHeadBytes: Long = PlayerManager.PREBUFFER_HEAD_BYTES
    ) {
        if (episode < 0) return
        val searchTitle = romajiTitle.ifBlank { title }.trim()
        if (searchTitle.length < 2) return
        val key = "${searchTitle.lowercase(Locale.ROOT)}#$episode#${preferredRes.lowercase(Locale.ROOT)}#$prebuffer"
        latestPrefetchKey = key
        if (!inFlightPrefetches.add(key)) return
        backgroundScope.launch {
            var held = false
            try {
                held = acquirePrefetchGate(key)
                if (!held || latestPrefetchKey != key) return@launch
                val slug = StreamResolver.buildEpisodeSlug(searchTitle, episode)
                val ok = streamResolver.prefetchStream(
                    slug = slug,
                    preferredRes = preferredRes,
                    title = title,
                    romajiTitle = romajiTitle,
                    maxAgeMs = maxAgeMs
                )
                if (ok && prebuffer && latestPrefetchKey == key) {
                    val cachedSession = streamResolver.getCachedSession(slug, preferredRes)
                    if (cachedSession != null) {
                        PlayerManager.prebufferEpisode(appContext, cachedSession, slug, headBytes = prebufferHeadBytes)
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
            } finally {
                if (held) prefetchGate.release()
                inFlightPrefetches.remove(key)
            }
        }
    }

    companion object {
        private const val LIVE_PROBE_TTL_MS = 2L * 60L * 1000L

        @Volatile
        private var instance: EngineRuntime? = null

        fun parseLiveEpisodeMap(source: SourcePriority, html: String, baseUrl: String): Map<Int, String> {
            val doc = Jsoup.parse(html, baseUrl)
            val parsedMap = linkedMapOf<Int, String>()
            if (source == SourcePriority.OTAKUDESU) {
                val epBoxes = doc.select("div.episodelist")
                val orderedEpLinks = if (epBoxes.size > 1) {
                    epBoxes[1].select("ul li a[href]") + epBoxes[0].select("ul li a[href]")
                } else {
                    doc.select(".episodelist ul li a[href]")
                }
                val nonBatch = linkedSetOf<String>()
                orderedEpLinks.forEach { a ->
                    val href = a.absUrl("href").trim()
                    if (href.contains("/episode/") && !href.contains("/batch/", ignoreCase = true)) {
                        nonBatch.add(href)
                        SeriesEpisodeMapCache.extractEpisodeNumber(a.text(), href)?.let { num ->
                            parsedMap.putIfAbsent(num, href)
                        }
                    }
                }
                if (parsedMap.isEmpty() && nonBatch.size == 1) {
                    parsedMap[1] = nonBatch.first()
                }
            } else {
                val epLinks = doc.select("a[href*='episode=']")
                val distinctUrls = linkedSetOf<String>()
                epLinks.forEach { a ->
                    val href = a.absUrl("href").trim()
                    if (href.startsWith("http")) {
                        distinctUrls.add(href)
                        SeriesEpisodeMapCache.extractEpisodeNumber(a.text(), href)?.let { num ->
                            parsedMap.putIfAbsent(num, href)
                        }
                    }
                }
                if (parsedMap.isEmpty() && distinctUrls.size == 1) {
                    parsedMap[1] = distinctUrls.first()
                }
            }
            return parsedMap
        }

        fun get(context: Context): EngineRuntime {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: EngineRuntime(context.applicationContext).also { instance = it }
            }
        }

        fun parseRemoteClearanceJson(json: String): RemoteClearance? {
            val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
            if (root.optString("status") != "ok") return null
            val data = root.optJSONObject("data") ?: return null
            val cookiesObj = data.optJSONObject("cookies") ?: return null
            val ua = data.optString("user_agent").trim()
            val map = linkedMapOf<String, String>()
            val keys = cookiesObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = cookiesObj.optString(k)
                if (k.isNotBlank() && v.isNotBlank()) map[k] = v
            }
            if (map.isEmpty()) return null
            return RemoteClearance(cookies = map, userAgent = ua)
        }
    }
}
