package id.my.milkglass.net

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CloudflareChallengeException(
    val host: String,
    val targetUrl: String,
    message: String = "Verifikasi Cloudflare diperlukan untuk $host"
) : IOException(message)

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

    private fun managerOrNull(): CookieManager? =
        runCatching { CookieManager.getInstance() }.getOrNull()

    private fun isUnreplayable(name: String): Boolean {
        val low = name.lowercase()
        return unreplayablePrefixes.any { low.startsWith(it) }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val raw = managerOrNull()?.getCookie(url.toString()) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return raw.split(';')
            .mapNotNull { Cookie.parse(url, it.trim()) }
            .filterNot { isUnreplayable(it.name) }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val manager = managerOrNull() ?: return
        val kept = cookies.filterNot { isUnreplayable(it.name) }
        if (kept.isEmpty()) return
        val urlStr = url.toString()
        kept.forEach { manager.setCookie(urlStr, it.toString()) }
        manager.flush()
    }

    fun hasClearance(url: String): Boolean {
        val raw = managerOrNull()?.getCookie(url).orEmpty()
        return raw.contains("cf_clearance=")
    }

    fun clearHostCookies(url: HttpUrl) {
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
            complete(key, false)
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
    private val cookieBridge: WebViewCookieBridge
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

        val verified = CloudflareChallengeCoordinator.verify(host, urlStr)
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
    private val dohClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    override fun lookup(hostname: String): List<InetAddress> {
        val now = System.currentTimeMillis()
        cache[hostname]?.let { (expiresAt, addrs) ->
            if (expiresAt > now && addrs.isNotEmpty()) return addrs
        }
        val resolved = runCatching { resolveViaDoh(hostname) }.getOrDefault(emptyList())
        if (resolved.isNotEmpty()) {
            cache[hostname] = (now + 300_000L) to resolved
            return resolved
        }
        return Dns.SYSTEM.lookup(hostname)
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

class IsolatedHttpClients(
    context: Context?,
    val dns: Dns = CloudflareDohDns(),
    val cookieBridge: WebViewCookieBridge = WebViewCookieBridge()
) {
    val webViewUserAgent: String = WebViewUserAgentProvider.resolve(context)

    val sourceClient: OkHttpClient = OkHttpClient.Builder()
        .dns(dns)
        .cookieJar(cookieBridge)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", webViewUserAgent)
                .header("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
                .build()
            chain.proceed(req)
        }
        .addInterceptor(CloudflareInterceptor(cookieBridge))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    val mediaAndApiClient: OkHttpClient = OkHttpClient.Builder()
        .dns(dns)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", webViewUserAgent)
                .build()
            chain.proceed(req)
        }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    val noRedirectClient: OkHttpClient = mediaAndApiClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
}
