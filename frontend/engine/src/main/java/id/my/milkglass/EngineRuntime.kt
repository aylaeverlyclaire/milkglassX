package id.my.milkglass

import android.content.Context
import android.content.SharedPreferences
import id.my.milkglass.auth.AuthBearerInterceptor
import id.my.milkglass.auth.SessionTokenStore
import id.my.milkglass.auth.SynchronizedTokenAuthenticator
import id.my.milkglass.download.EndpointHealthScorer
import id.my.milkglass.net.IsolatedHttpClients
import id.my.milkglass.net.RemoteClearance
import id.my.milkglass.resolver.StreamResolver
import id.my.milkglass.source.DeadCandidateCache
import id.my.milkglass.source.MkvCascadeResolver
import id.my.milkglass.source.SourceCircuitBreaker
import id.my.milkglass.source.SourceAvailabilityIndex
import id.my.milkglass.source.SourcePriority
import id.my.milkglass.source.SourceSlugCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    private val prefs: SharedPreferences? = context?.applicationContext
        ?.getSharedPreferences("milkglass_source_config", Context.MODE_PRIVATE)

    private val urls = ConcurrentHashMap<SourcePriority, String>()

    @Volatile
    private var lastSyncedAtMs: Long = 0L

    @Volatile
    private var lastEtag: String? = null

    init {
        SourcePriority.entries.forEach { urls[it] = it.defaultBaseUrl }
        loadFromDisk()
    }

    fun baseUrlFor(source: SourcePriority): String = urls[source] ?: source.defaultBaseUrl

    fun snapshot(): Map<SourcePriority, String> = urls.toMap()

    fun refreshIfNeeded(force: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        if (!force && lastSyncedAtMs > 0L && (now - lastSyncedAtMs) < CONFIG_TTL_MS) {
            return false
        }
        val reqBuilder = Request.Builder()
            .url("${backendBaseUrl.trimEnd('/')}/v1/catalog/sources")
            .header("Accept", "application/json")
        lastEtag?.takeIf { it.isNotBlank() }?.let { reqBuilder.header("If-None-Match", it) }
        return runCatching {
            clients.mediaAndApiClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.code == 304) {
                    lastSyncedAtMs = now
                    return@use true
                }
                if (!resp.isSuccessful) return@use false
                val raw = resp.body?.string().orEmpty()
                val parsed = parseSourcesJson(raw)
                if (parsed.isEmpty()) return@use false
                parsed.forEach { (k, v) -> urls[k] = v }
                resp.header("ETag")?.takeIf { it.isNotBlank() }?.let { lastEtag = it }
                lastSyncedAtMs = now
                saveToDisk(parsed, now)
                true
            }
        }.getOrDefault(false)
    }

    private fun saveToDisk(map: Map<SourcePriority, String>, ts: Long) {
        val p = prefs ?: return
        runCatching {
            val obj = JSONObject()
            map.forEach { (k, v) -> obj.put(k.id, v) }
            p.edit()
                .putLong("ts", ts)
                .putString("urls", obj.toString())
                .apply()
        }
    }

    private fun loadFromDisk() {
        val p = prefs ?: return
        val ts = p.getLong("ts", 0L)
        val raw = p.getString("urls", null) ?: return
        if (System.currentTimeMillis() - ts > CONFIG_TTL_MS * 4L) return
        runCatching {
            val obj = JSONObject(raw)
            SourcePriority.entries.forEach { src ->
                val v = obj.optString(src.id).trim().trimEnd('/')
                if (v.startsWith("https://")) {
                    urls[src] = v
                }
            }
            lastSyncedAtMs = ts
        }
    }

    companion object {
        private const val CONFIG_TTL_MS = 6L * 60L * 60L * 1000L

        private fun isSafeSourceBaseUrl(rawUrl: String): Boolean {
            if (!rawUrl.startsWith("https://") || rawUrl.length > 256) return false
            val parsed = runCatching { java.net.URI(rawUrl) }.getOrNull() ?: return false
            if (!parsed.userInfo.isNullOrBlank()) return false
            val host = parsed.host?.trim()?.lowercase() ?: return false
            if (host.isBlank() || host == "localhost" || host.endsWith(".local") || host.contains(':')) return false
            if (Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$").matches(host)) return false
            return host.contains('.')
        }

        fun parseSourcesJson(json: String): Map<SourcePriority, String> {
            val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
            val arr = root.optJSONArray("sources") ?: return emptyMap()
            val out = linkedMapOf<SourcePriority, String>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val id = item.optString("id").trim().lowercase()
                val baseUrl = item.optString("base_url").trim().trimEnd('/')
                if (!isSafeSourceBaseUrl(baseUrl)) continue
                val src = SourcePriority.entries.firstOrNull { it.id == id } ?: continue
                out[src] = baseUrl
            }
            return out
        }
    }
}

class EngineRuntime private constructor(appContext: Context) {
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightPrefetches = ConcurrentHashMap.newKeySet<String>()
    private val prefetchGate = java.util.concurrent.Semaphore(1)
    @Volatile private var latestPrefetchKey: String = ""

    @Volatile
    private var remoteCfDisabledUntilMs: Long = 0L

    @Volatile
    private var lastForcedConfigRefreshMs: Long = 0L

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
                apiBaseUrl = MilkglassEndpoints.DEFAULT_BACKEND_BASE_URL
            )
        }
    )

    val slugCache: SourceSlugCache = SourceSlugCache(appContext)
    val sourceConfig: SourceConfigStore = SourceConfigStore(appContext, clients)

    val availabilityIndex: SourceAvailabilityIndex = SourceAvailabilityIndex(
        context = appContext,
        clients = clients,
        slugCache = slugCache,
        baseUrlProvider = { sourceConfig.baseUrlFor(it) }
    )

    val cascadeResolver: MkvCascadeResolver = MkvCascadeResolver(
        clients = clients,
        slugCache = slugCache,
        baseUrlProvider = { sourceConfig.baseUrlFor(it) },
        indexLookup = { source, title -> availabilityIndex.lookupBestUrl(source, title) },
        remoteHtmlFetcher = { url -> fetchRemoteHtml(url) },
        onAllSourcesFailed = { triggerSourceConfigRecovery() }
    )

    val streamResolver: StreamResolver = StreamResolver(
        context = appContext,
        backendBaseUrl = MilkglassEndpoints.DEFAULT_BACKEND_BASE_URL,
        clients = clients,
        slugCache = slugCache,
        hasAuthSession = { sessionStore.hasActiveSession() },
        serverSignatureVerifier = { body, sig -> sessionStore.verifyServerSignature(body, sig) },
        cascadeResolver = cascadeResolver
    )

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
            clients.mediaAndApiClient.newCall(req).execute().use { resp ->
                if (resp.code == 503) {
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
        if (now < remoteCfDisabledUntilMs) return null
        if (!sessionStore.hasActiveSession()) return null

        val endpoint = "${MilkglassEndpoints.DEFAULT_BACKEND_BASE_URL}/v1/cf/html?url=${URLEncoder.encode(targetUrl, "UTF-8")}"
        val req = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .build()

        return runCatching {
            clients.mediaAndApiClient.newCall(req).execute().use { resp ->
                if (resp.code == 503) {
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
    }

    fun onNetworkRestored(onIndexUpdated: (() -> Unit)? = null) {
        SourceCircuitBreaker.resetAll()
        DeadCandidateCache.clear()
        remoteCfDisabledUntilMs = 0L
        backgroundScope.launch {
            runCatching { sourceConfig.refreshIfNeeded() }
            if (!availabilityIndex.isReady) {
                val count = runCatching { availabilityIndex.refreshIfNeeded() }.getOrDefault(0)
                if (count > 0) onIndexUpdated?.invoke()
            }
        }
    }

    /** Menunggu giliran prefetch, tapi langsung menyerah begitu ada permintaan yang lebih baru. */
    private fun acquirePrefetchGate(key: String): Boolean {
        val deadline = System.currentTimeMillis() + 30_000L
        while (System.currentTimeMillis() < deadline) {
            if (latestPrefetchKey != key) return false
            if (prefetchGate.tryAcquire(250, java.util.concurrent.TimeUnit.MILLISECONDS)) return true
        }
        return false
    }

    fun prefetchEpisode(
        title: String,
        romajiTitle: String,
        episode: Int,
        preferredRes: String = "1080p",
        maxAgeMs: Long = Long.MAX_VALUE
    ) {
        if (episode <= 0) return
        val searchTitle = romajiTitle.ifBlank { title }.trim()
        if (searchTitle.length < 2) return
        val key = "${searchTitle.lowercase(Locale.ROOT)}#$episode"
        if (!inFlightPrefetches.add(key)) return
        latestPrefetchKey = key
        backgroundScope.launch {
            var held = false
            try {
                held = acquirePrefetchGate(key)
                if (!held || latestPrefetchKey != key) return@launch
                val slug = StreamResolver.buildEpisodeSlug(searchTitle, episode)
                streamResolver.prefetchStream(
                    slug = slug,
                    preferredRes = preferredRes,
                    title = title,
                    romajiTitle = romajiTitle,
                    maxAgeMs = maxAgeMs
                )
            } catch (_: Throwable) {
            } finally {
                if (held) prefetchGate.release()
                inFlightPrefetches.remove(key)
            }
        }
    }

    companion object {
        @Volatile
        private var instance: EngineRuntime? = null

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
