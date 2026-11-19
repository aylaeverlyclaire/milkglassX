package id.my.milkglass.net

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
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
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    fun resolve(context: Context?): String {
        cachedUa?.let { return it }
        val ua = if (context != null) {
            runCatching { WebSettings.getDefaultUserAgent(context) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: FALLBACK_UA
        } else {
            FALLBACK_UA
        }
        cachedUa = ua
        return ua
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
        if (raw.contains("cf_clearance=")) return true
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
    private val onUserAgentOverride: ((host: String, userAgent: String) -> Unit)? = null
) : Interceptor {

    private fun isCloudflareChallenge(response: Response): Boolean {
        if (response.code != 403 && response.code != 503) return false
        val mitigated = response.header("cf-mitigated")?.lowercase()
        if (mitigated == "challenge") return true
        val server = response.header("Server")?.lowercase().orEmpty()
        return server.contains("cloudflare")
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (!isCloudflareChallenge(response)) return response

        val host = request.url.host
        val urlStr = request.url.toString()
        response.close()
        cookieBridge.clearHostCookies(request.url)

        // Fast path: try automated remote cf-handler (Edge-cached ~20ms or headless Brave pool)
        val remote = runCatching { remoteSolver?.invoke(host, urlStr) }.getOrNull()
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

        // Fallback path: interactive WebView verification overlay
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
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS)
        .build()

    override fun lookup(hostname: String): List<InetAddress> {
        val host = hostname.lowercase()
        if (host == "127.0.0.1" || host == "localhost" || host == "1.1.1.1") {
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
            val resolved = runCatching { resolveViaDoh(host) }.getOrDefault(emptyList())
            if (resolved.isNotEmpty()) {
                cache[host] = (System.currentTimeMillis() + 300_000L) to resolved
                mine.complete(resolved)
                resolved
            } else {
                val fallback = runCatching { Dns.SYSTEM.lookup(hostname) }.getOrDefault(emptyList())
                mine.complete(fallback)
                if (fallback.isNotEmpty()) fallback else throw UnknownHostException(hostname)
            }
        } catch (e: UnknownHostException) {
            mine.complete(emptyList())
            throw e
        } finally {
            inFlight.remove(host, mine)
        }
    }

    private fun resolveViaDoh(hostname: String): List<InetAddress> {
        val url = dohEndpoint.toHttpUrl().newBuilder()
            .addQueryParameter("name", hostname)
            .addQueryParameter("type", "A")
            .build()
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/dns-json")
            .build()
        dohClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            val answers = JSONObject(body).optJSONArray("Answer") ?: return emptyList()
            val ips = mutableListOf<InetAddress>()
            for (i in 0 until answers.length()) {
                val item = answers.optJSONObject(i) ?: continue
                if (item.optInt("type") == 1) {
                    val ip = item.optString("data").trim()
                    if (ip.isNotEmpty()) {
                        runCatching { InetAddress.getByName(ip) }.getOrNull()?.let { ips.add(it) }
                    }
                }
            }
            return ips
        }
    }
}

object PublicIpGuard {
    fun isPublicIpv4(address: InetAddress): Boolean {
        if (address !is Inet4Address) return false
        if (address.isAnyLocalAddress || address.isLoopbackAddress ||
            address.isLinkLocalAddress || address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) return false
        val b = address.address
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

    @Throws(IOException::class)
    fun verifyPublicHost(dns: Dns, host: String) {
        val addresses = try {
            dns.lookup(host)
        } catch (e: UnknownHostException) {
            throw IOException("DNS lookup gagal untuk $host", e)
        }
        val hasPublic = addresses.any { isPublicIpv4(it) }
        if (!hasPublic) {
            throw IOException("Host diblokir oleh anti-SSRF (bukan IPv4 publik): $host")
        }
    }
}

class CrossOriginRefererInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalHost = chain.call().request().url.host
        val hop = chain.request()
        val cleaned = if (hop.header("Referer") != null && hop.url.host != originalHost) {
            hop.newBuilder().removeHeader("Referer").build()
        } else {
            hop
        }
        return chain.proceed(cleaned)
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
    private val windowMs: Long = 1000L
) : Interceptor {
    private val hostQueues = ConcurrentHashMap<String, ArrayDeque<Long>>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val call = chain.call()
        if (call.isCanceled()) throw IOException("Canceled")
        val host = chain.request().url.host.lowercase()
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
    remoteClearanceSolver: ((host: String, url: String) -> RemoteClearance?)? = null
) {
    val webViewUserAgent: String = WebViewUserAgentProvider.resolve(context)

    private val sharedConnectionPool = ConnectionPool(12, 5, TimeUnit.MINUTES)
    private val hostUserAgents = ConcurrentHashMap<String, String>()

    val sourceClient: OkHttpClient = OkHttpClient.Builder()
        .dns(dns)
        .connectionPool(sharedConnectionPool)
        .addInterceptor(UncaughtExceptionInterceptor())
        .addInterceptor(HostRateLimitInterceptor(maxPermitsPerWindow = 4, windowMs = 1000L))
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
                onUserAgentOverride = { h, ua -> hostUserAgents[h] = ua }
            )
        )
        .addNetworkInterceptor(CrossOriginRefererInterceptor())
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(18, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    val mediaAndApiClient: OkHttpClient = OkHttpClient.Builder()
        .dns(dns)
        .connectionPool(sharedConnectionPool)
        .addInterceptor(UncaughtExceptionInterceptor())
        .addInterceptor { chain ->
            val orig = chain.request()
            val b = orig.newBuilder()
            if (orig.header("User-Agent") == null) b.header("User-Agent", webViewUserAgent)
            if (orig.header("Accept-Language") == null) b.header("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
            chain.proceed(b.build())
        }
        .addNetworkInterceptor(CrossOriginRefererInterceptor())
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    val noRedirectClient: OkHttpClient = mediaAndApiClient.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
}
