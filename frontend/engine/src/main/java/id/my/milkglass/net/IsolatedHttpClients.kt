package id.my.milkglass.net

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import okhttp3.Authenticator
import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CloudflareChallengeException(
    val host: String,
    val targetUrl: String,
    message: String = "Verifikasi Cloudflare diperlukan untuk $host"
) : IOException(message)

data class RemoteClearance(
    val cookies: Map<String, String>,
    val userAgent: String
)

object WebViewUserAgentProvider {
    private const val FALLBACK_UA =
        "Mozilla/5.0 (Linux; Android 13; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    @Volatile
    private var cachedUa: String? = null

    private val fetchStarted = AtomicBoolean(false)

    fun current(): String = cachedUa ?: FALLBACK_UA

    fun resolve(context: Context?): String {
        if (context != null && cachedUa == null && fetchStarted.compareAndSet(false, true)) {
            val appCtx = context.applicationContext
            Thread({
                runCatching { WebSettings.getDefaultUserAgent(appCtx) }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { cachedUa = it }
            }, "milkglass-ua-resolve").apply {
                isDaemon = true
                start()
            }
        }
        return cachedUa ?: FALLBACK_UA
    }
}

class WebViewCookieBridge(
    private val unreplayablePrefixes: Set<String> = setOf("cf_chl_")
) : CookieJar {

    private val memoryOverrides = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()

    private fun managerOrNull(): CookieManager? =
        runCatching { CookieManager.getInstance() }.getOrNull()

    private fun isUnreplayable(name: String): Boolean {
        val low = name.lowercase()
        return unreplayablePrefixes.any { low.startsWith(it) }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val merged = linkedMapOf<String, Cookie>()
        val raw = managerOrNull()?.getCookie(url.toString()).orEmpty()
        if (raw.isNotBlank()) {
            raw.split(';')
                .mapNotNull { Cookie.parse(url, it.trim()) }
                .filterNot { isUnreplayable(it.name) }
                .forEach { merged[it.name] = it }
        }
        memoryOverrides[url.host.lowercase()]?.forEach { (name, value) ->
            if (!isUnreplayable(name)) {
                Cookie.parse(url, "$name=$value")?.let { merged[it.name] = it }
            }
        }
        return merged.values.toList()
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val manager = managerOrNull() ?: return
        val kept = cookies.filterNot { isUnreplayable(it.name) }
        if (kept.isEmpty()) return
        val urlStr = url.toString()
        kept.forEach { manager.setCookie(urlStr, it.toString()) }
        manager.flush()
    }

    fun injectCookies(url: HttpUrl, cookies: Map<String, String>) {
        if (cookies.isEmpty()) return
        val hostMap = memoryOverrides.computeIfAbsent(url.host.lowercase()) { ConcurrentHashMap() }
        val manager = managerOrNull()
        val urlStr = url.toString()
        cookies.forEach { (name, value) ->
            if (name.isNotBlank() && !isUnreplayable(name)) {
                hostMap[name] = value
                manager?.setCookie(urlStr, "$name=$value; Path=/; Secure")
            }
        }
        manager?.flush()
    }

    fun hasClearance(url: String): Boolean {
        val raw = managerOrNull()?.getCookie(url).orEmpty()
        if (CloudflareChallengeDetector.hasClearanceCookie(raw)) return true
        val host = runCatching { url.toHttpUrl().host.lowercase() }.getOrNull() ?: return false
        return memoryOverrides[host]?.containsKey("cf_clearance") == true
    }

    fun clearHostCookies(url: HttpUrl) {
        memoryOverrides.remove(url.host.lowercase())
        val manager = managerOrNull() ?: return
        val raw = manager.getCookie(url.toString()) ?: return
        raw.split(';').forEach { entry ->
            val name = entry.substringBefore('=').trim()
            if (name.isNotEmpty() && (name == "cf_clearance" || isUnreplayable(name))) {
                manager.setCookie(url.toString(), "$name=; Max-Age=0")
            }
        }
        manager.flush()
    }
}

object CloudflareChallengeCoordinator {
    private const val TIMEOUT_SECONDS = 90L

    private class PendingVerification {
        val latch = CountDownLatch(1)
        @Volatile
        var succeeded: Boolean = false
    }

    private val lock = Any()
    private val active = mutableMapOf<String, PendingVerification>()

    @Volatile
    var uiLauncher: ((host: String, url: String) -> Unit)? = null

    fun verify(host: String, url: String): Boolean {
        val launcher = uiLauncher ?: return false
        val key = host.lowercase()
        var shouldLaunch = false
        val pending = synchronized(lock) {
            active[key] ?: PendingVerification().also {
                active[key] = it
                shouldLaunch = true
            }
        }
        if (shouldLaunch) {
            runCatching { launcher(key, url) }.onFailure {
                complete(key, false)
                return false
            }
        }
        val finished = runCatching { pending.latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            .getOrDefault(false)
        if (!finished) {
            synchronized(lock) {
                if (active[key] === pending) {
                    active.remove(key)
                    pending.succeeded = false
                    pending.latch.countDown()
                }
            }
            return false
        }
        return pending.succeeded
    }

    fun complete(host: String, succeeded: Boolean) {
        val key = host.lowercase()
        val pending = synchronized(lock) { active.remove(key) } ?: return
        pending.succeeded = succeeded
        pending.latch.countDown()
    }
}

class CloudflareInterceptor(
    private val cookieBridge: WebViewCookieBridge,
    private val remoteSolver: ((host: String, url: String) -> RemoteClearance?)? = null,
    private val silentLocalSolver: ((host: String, url: String) -> Boolean)? = null,
    private val onUserAgentOverride: ((host: String, userAgent: String) -> Unit)? = null
) : Interceptor {

    private val inFlightRemote = ConcurrentHashMap<String, CompletableFuture<RemoteClearance?>>()
    private val inFlightSilent = ConcurrentHashMap<String, CompletableFuture<Boolean>>()

    private fun resolveSilentSingleFlight(host: String, urlStr: String): Boolean {
        val solver = silentLocalSolver ?: return false
        val key = host.lowercase()
        val mine = CompletableFuture<Boolean>()
        val existing = inFlightSilent.putIfAbsent(key, mine)
        if (existing != null) {
            return runCatching { existing.get(15, TimeUnit.SECONDS) }.getOrDefault(false)
        }
        return try {
            val ok = runCatching { solver.invoke(host, urlStr) }.getOrDefault(false)
            mine.complete(ok)
            ok
        } finally {
            inFlightSilent.remove(key, mine)
        }
    }

    private fun resolveRemoteSingleFlight(host: String, urlStr: String): RemoteClearance? {
        val solver = remoteSolver ?: return null
        val key = host.lowercase()
        val mine = CompletableFuture<RemoteClearance?>()
        val existing = inFlightRemote.putIfAbsent(key, mine)
        if (existing != null) {
            return runCatching { existing.get(35, TimeUnit.SECONDS) }.getOrNull()
        }
        return try {
            val res = runCatching { solver.invoke(host, urlStr) }.getOrNull()
            mine.complete(res)
            res
        } finally {
            inFlightRemote.remove(key, mine)
        }
    }

    private fun isCloudflareChallenge(response: Response): Boolean = CloudflareChallengeDetector.isChallengeResponse(response)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (!isCloudflareChallenge(response)) return response

        val host = request.url.host
        val urlStr = request.url.toString()
        response.close()
        cookieBridge.clearHostCookies(request.url)

        val remote = resolveRemoteSingleFlight(host, urlStr)
        if (remote != null && remote.cookies.isNotEmpty()) {
            cookieBridge.injectCookies(request.url, remote.cookies)
            val retryReq = if (remote.userAgent.isNotBlank()) {
                onUserAgentOverride?.invoke(host.lowercase(), remote.userAgent)
                request.newBuilder().header("User-Agent", remote.userAgent).build()
            } else {
                request
            }
            val retryResp = chain.proceed(retryReq)
            if (!isCloudflareChallenge(retryResp)) {
                return retryResp
            }
            retryResp.close()
            cookieBridge.clearHostCookies(request.url)
        }

        if (resolveSilentSingleFlight(host, urlStr)) {
            val silentRetry = chain.proceed(request)
            if (!isCloudflareChallenge(silentRetry)) {
                return silentRetry
            }
            silentRetry.close()
        }

        val verified = try {
            CloudflareChallengeCoordinator.verify(host, urlStr)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Gagal menjalankan verifikasi Cloudflare untuk $host", e)
        }
        if (!verified) {
            throw CloudflareChallengeException(host, urlStr)
        }
        return chain.proceed(request)
    }
}

class CloudflareDohDns(
    private val dohEndpoint: String = "https://1.1.1.1/dns-query"
) : Dns {
    private val cache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<List<InetAddress>>>()
    private val dohClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(4, 5, TimeUnit.MINUTES))
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(2500, TimeUnit.MILLISECONDS)
        .build()

    @Volatile
    private var dohBrokenUntilMs: Long = 0L

    override fun lookup(hostname: String): List<InetAddress> {
        val host = hostname.lowercase()
        if (host == "127.0.0.1" || host == "localhost" || host == "1.1.1.1") {
            return Dns.SYSTEM.lookup(hostname)
        }
        if (host == "1.1.1.1" || host == "1.0.0.1" || host == "8.8.8.8" || host == "8.8.4.4") {
            return Dns.SYSTEM.lookup(hostname)
        }
        val now = System.currentTimeMillis()
        cache[host]?.let { (expiresAt, addrs) ->
            if (expiresAt > now && addrs.isNotEmpty()) return addrs
        }

        val mine = CompletableFuture<List<InetAddress>>()
        val existing = inFlight.putIfAbsent(host, mine)
        if (existing != null) {
            val shared = runCatching { existing.get(4, TimeUnit.SECONDS) }.getOrDefault(emptyList())
            if (shared.isNotEmpty()) return shared
            return Dns.SYSTEM.lookup(hostname)
        }

        return try {
            val dohAllowed = System.currentTimeMillis() >= dohBrokenUntilMs
            val resolved = if (dohAllowed) {
                try {
                    resolveViaDoh(host)
                } catch (_: Exception) {
                    dohBrokenUntilMs = System.currentTimeMillis() + 120_000L
                    emptyList()
                }
            } else {
                emptyList()
            }
            if (resolved.isNotEmpty()) {
                cache[host] = (System.currentTimeMillis() + 300_000L) to resolved
                mine.complete(resolved)
                resolved
            } else {
                val fallback = runCatching { Dns.SYSTEM.lookup(hostname) }.getOrDefault(emptyList())
                mine.complete(fallback)
                if (fallback.isNotEmpty()) {
                    cache[host] = (System.currentTimeMillis() + 120_000L) to fallback
                    fallback
                } else {
                    throw UnknownHostException(hostname)
                }
            }
        } catch (e: UnknownHostException) {
            mine.complete(emptyList())
            throw e
        } finally {
            inFlight.remove(host, mine)
        }
    }

    private fun resolveViaDoh(hostname: String): List<InetAddress> {
        val encoded = java.net.URLEncoder.encode(hostname, "UTF-8")
        val endpoints = listOf(
            "https://1.1.1.1/dns-query?name=$encoded&type=A",
            "https://1.0.0.1/dns-query?name=$encoded&type=A",
            "https://8.8.8.8/resolve?name=$encoded&type=A",
            "https://8.8.4.4/resolve?name=$encoded&type=A"
        )
        var lastError: Exception? = null
        for (url in endpoints) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "application/dns-json")
                    .build()
                dohClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        lastError = IOException("DoH HTTP ${resp.code}")
                        return@use
                    }
                    val body = resp.body?.string().orEmpty()
                    val answers = JSONObject(body).optJSONArray("Answer") ?: return@use
                    val ips = mutableListOf<InetAddress>()
                    for (i in 0 until answers.length()) {
                        val obj = answers.optJSONObject(i) ?: continue
                        if (obj.optInt("type") == 1) {
                            val ip = obj.optString("data").trim()
                            if (ip.isNotEmpty()) {
                                runCatching { ips.add(InetAddress.getByName(ip)) }
                            }
                        }
                    }
                    if (ips.isNotEmpty()) return ips
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (lastError != null) throw lastError
        return emptyList()
    }
}

object PublicIpGuard {
    private const val VERIFIED_TTL_MS = 5L * 60L * 1000L
    private val verifiedHosts = ConcurrentHashMap<String, Long>()

    private fun isPublicIpv4Bytes(b: ByteArray): Boolean {
        if (b.size != 4) return false
        val b0 = b[0].toInt() and 0xFF
        val b1 = b[1].toInt() and 0xFF
        if (b0 == 0 || b0 == 10 || b0 == 127) return false
        if (b0 == 100 && b1 in 64..127) return false
        if (b0 == 169 && b1 == 254) return false
        if (b0 == 172 && b1 in 16..31) return false
        if (b0 == 192 && b1 == 168) return false
        if (b0 >= 224) return false
        return true
    }

    fun isPublicIp(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress ||
            address.isLinkLocalAddress || address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) return false
        return when (address) {
            is Inet4Address -> isPublicIpv4Bytes(address.address)
            is Inet6Address -> {
                val b = address.address
                if (b.size != 16) return false
                // Tolak ULA fc00::/7
                if ((b[0].toInt() and 0xFE) == 0xFC) return false
                // NAT64 64:ff9b::/96
                val isNat64 = b[0] == 0x00.toByte() && b[1] == 0x64.toByte() &&
                    b[2] == 0xFF.toByte() && b[3] == 0x9B.toByte() &&
                    (4..11).all { b[it] == 0.toByte() }
                // IPv4-mapped ::ffff:0:0/96
                val isIpv4Mapped = (0..9).all { b[it] == 0.toByte() } &&
                    b[10] == 0xFF.toByte() && b[11] == 0xFF.toByte()
                if (isNat64 || isIpv4Mapped) {
                    isPublicIpv4Bytes(b.copyOfRange(12, 16))
                } else {
                    true
                }
            }
            else -> false
        }
    }

    fun isPublicIpv4(address: InetAddress): Boolean = isPublicIp(address)

    @Throws(IOException::class)
    fun verifyPublicHost(dns: Dns, host: String) {
        val key = host.trim().lowercase()
        val now = System.currentTimeMillis()
        verifiedHosts[key]?.let { exp ->
            if (exp > now) return
            verifiedHosts.remove(key, exp)
        }
        val addresses = try {
            dns.lookup(host)
        } catch (e: UnknownHostException) {
            throw IOException("DNS lookup gagal untuk $host", e)
        }
        val hasPublic = addresses.any { isPublicIp(it) }
        if (!hasPublic) {
            throw IOException("Host diblokir oleh anti-SSRF (bukan IP publik): $host")
        }
        if (verifiedHosts.size > 256) verifiedHosts.clear()
        verifiedHosts[key] = now + VERIFIED_TTL_MS
    }
}

class PublicIpGuardDns(
    private val delegate: Dns
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val resolved = delegate.lookup(hostname)
        val publicOnly = resolved.filter { PublicIpGuard.isPublicIp(it) }
        if (publicOnly.isEmpty()) {
            throw UnknownHostException("Host diblokir oleh anti-SSRF (bukan IP publik): $hostname")
        }
        return publicOnly
    }
}

class AntiSsrfNetworkInterceptor(
    private val dns: Dns
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val hop = chain.request()
        val hopHost = hop.url.host.lowercase()
        val connectedAddr = chain.connection()?.route()?.socketAddress?.address
        if (connectedAddr != null) {
            if (!PublicIpGuard.isPublicIp(connectedAddr)) {
                throw IOException("Redirect/koneksi diblokir oleh anti-SSRF (IP non-publik): $hopHost")
            }
        } else {
            PublicIpGuard.verifyPublicHost(dns, hopHost)
        }
        return chain.proceed(hop)
    }
}

class CrossOriginRefererInterceptor : Interceptor {
    private fun registrableDomain(host: String): String {
        val parts = host.lowercase().split('.').filter { it.isNotBlank() }
        return if (parts.size >= 2) "${parts[parts.size - 2]}.${parts[parts.size - 1]}" else host.lowercase()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val origUrl = chain.call().request().url
        val hop = chain.request()
        val hopUrl = hop.url
        val sameBaseDomain = registrableDomain(origUrl.host) == registrableDomain(hopUrl.host)
        val bothHttps = origUrl.isHttps && hopUrl.isHttps
        val cleaned = if (hop.header("Referer") != null && !sameBaseDomain && !bothHttps) {
            hop.newBuilder().removeHeader("Referer").build()
        } else {
            hop
        }
        return chain.proceed(cleaned)
    }
}

class IgnoreGzipInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        if (request.header("Accept-Encoding")?.equals("gzip", ignoreCase = true) == true) {
            request = request.newBuilder().removeHeader("Accept-Encoding").build()
        }
        return chain.proceed(request)
    }
}

class UncaughtExceptionInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        return try {
            chain.proceed(chain.request())
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(e)
        }
    }
}

class HostRateLimitInterceptor(
    private val maxPermitsPerWindow: Int = 4,
    private val windowMs: Long = 1000L,
    private val maxTrackedHosts: Int = 128
) : Interceptor {
    private val hostQueues = ConcurrentHashMap<String, ArrayDeque<Long>>()

    private fun pruneIfOversized(nowMs: Long) {
        if (hostQueues.size < maxTrackedHosts) return
        val cutoff = nowMs - windowMs
        val it = hostQueues.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            val q = entry.value
            val isStale = synchronized(q) {
                while (q.isNotEmpty() && q.first() <= cutoff) {
                    q.removeFirst()
                }
                q.isEmpty()
            }
            if (isStale) {
                it.remove()
            }
        }
        if (hostQueues.size >= maxTrackedHosts) {
            val excess = hostQueues.size - maxTrackedHosts + 1
            var removed = 0
            val fallbackIt = hostQueues.keys.iterator()
            while (fallbackIt.hasNext() && removed < excess) {
                fallbackIt.next()
                fallbackIt.remove()
                removed++
            }
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val call = chain.call()
        if (call.isCanceled()) throw IOException("Canceled")
        val host = chain.request().url.host.lowercase()
        if (!hostQueues.containsKey(host)) {
            pruneIfOversized(System.nanoTime() / 1_000_000L)
        }
        val queue = hostQueues.computeIfAbsent(host) { ArrayDeque() }

        while (true) {
            val sleepMs = synchronized(queue) {
                val now = System.nanoTime() / 1_000_000L
                val cutoff = now - windowMs
                while (queue.isNotEmpty() && queue.first() <= cutoff) {
                    queue.removeFirst()
                }
                if (queue.size < maxPermitsPerWindow) {
                    queue.addLast(now)
                    0L
                } else {
                    (queue.first() + windowMs - now).coerceAtLeast(10L)
                }
            }
            if (sleepMs == 0L) break
            if (call.isCanceled()) throw IOException("Canceled")
            try {
                Thread.sleep(sleepMs)
            } catch (e: InterruptedException) {
                throw IOException(e)
            }
        }
        return chain.proceed(chain.request())
    }
}

class IsolatedHttpClients(
    context: Context?,
    val dns: Dns = CloudflareDohDns(),
    val cookieBridge: WebViewCookieBridge = WebViewCookieBridge(),
    remoteClearanceSolver: ((host: String, url: String) -> RemoteClearance?)? = null,
    silentLocalSolver: ((host: String, url: String) -> Boolean)? = null,
    backendAuthInterceptor: Interceptor? = null,
    backendAuthenticatorFactory: ((OkHttpClient) -> Authenticator)? = null
) {
    private val appContext: Context? = context?.applicationContext

    val userAgentProvider: () -> String = { WebViewUserAgentProvider.resolve(appContext) }

    val webViewUserAgent: String
        get() = userAgentProvider()

    init {
        WebViewUserAgentProvider.resolve(appContext)
    }

    private val sharedConnectionPool = ConnectionPool(16, 5, TimeUnit.MINUTES)
    private val sharedDispatcher = Dispatcher().apply {
        maxRequests = 64
        maxRequestsPerHost = 12
    }
    private val hostUserAgents = ConcurrentHashMap<String, String>()
    private val guardedMediaDns: Dns = PublicIpGuardDns(dns)

    val sourceClient: OkHttpClient = OkHttpClient.Builder()
        .dns(dns)
        .dispatcher(sharedDispatcher)
        .connectionPool(sharedConnectionPool)
        .addInterceptor(UncaughtExceptionInterceptor())
        .addInterceptor(HostRateLimitInterceptor(maxPermitsPerWindow = 8, windowMs = 1000L))
        .cookieJar(cookieBridge)
        .addInterceptor { chain ->
            val orig = chain.request()
            val ua = hostUserAgents[orig.url.host.lowercase()] ?: webViewUserAgent
            val req = orig.newBuilder()
                .header("User-Agent", ua)
                .header("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
                .build()
            chain.proceed(req)
        }
        .addInterceptor(
            CloudflareInterceptor(
                cookieBridge = cookieBridge,
                remoteSolver = remoteClearanceSolver,
                silentLocalSolver = silentLocalSolver ?: appContext?.let { ctx ->
                    { _, targetUrl ->
                        SilentWebViewCfHandler.solveClearanceSilently(ctx, targetUrl, webViewUserAgent)
                    }
                },
                onUserAgentOverride = { h, ua -> hostUserAgents[h] = ua }
            )
        )
        .addNetworkInterceptor(AntiSsrfNetworkInterceptor(guardedMediaDns))
        .addNetworkInterceptor(CrossOriginRefererInterceptor())
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    internal val baseMediaAndApiClient: OkHttpClient = OkHttpClient.Builder()
        .dns(guardedMediaDns)
        .dispatcher(sharedDispatcher)
        .connectionPool(sharedConnectionPool)
        .addInterceptor(UncaughtExceptionInterceptor())
        .addInterceptor { chain ->
            val orig = chain.request()
            val b = orig.newBuilder()
            if (orig.header("User-Agent") == null) b.header("User-Agent", webViewUserAgent)
            if (orig.header("Accept-Language") == null) b.header("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
            chain.proceed(b.build())
        }
        .addNetworkInterceptor(AntiSsrfNetworkInterceptor(dns))
        .addNetworkInterceptor(CrossOriginRefererInterceptor())
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    val mediaAndApiClient: OkHttpClient = baseMediaAndApiClient.newBuilder().apply {
        addInterceptor(IgnoreGzipInterceptor())
        if (backendAuthInterceptor != null) addInterceptor(backendAuthInterceptor)
        if (backendAuthenticatorFactory != null) authenticator(backendAuthenticatorFactory(baseMediaAndApiClient))
    }.build()

    /** Profil API Backend Milkglass (HTTP/2 keep-alive ping 25s + Bearer auth). */
    val apiClient: OkHttpClient = mediaAndApiClient.newBuilder()
        .pingInterval(25, TimeUnit.SECONDS)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    /** Profil Remote CF Solver (/v1/cf/cookies & /v1/cf/html): readTimeout 35s selaras single-flight. */
    val cfSolverClient: OkHttpClient = mediaAndApiClient.newBuilder()
        .pingInterval(25, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    /** Profil Streaming Video & Download MKV: tanpa callTimeout agar tidak memutus stream panjang. */
    val streamingClient: OkHttpClient = mediaAndApiClient.newBuilder()
        .pingInterval(25, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    val noRedirectClient: OkHttpClient = mediaAndApiClient.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
}
