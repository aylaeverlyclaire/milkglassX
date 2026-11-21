package id.my.milkglass.net

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Handler Cloudflare lokal berbasis offscreen Android WebView (tanpa server berbayar).
 *
 * Saat OkHttp terkena challenge Cloudflare (403/503), handler ini mencoba memuat halaman
 * secara diam-diam di background WebView dengan User-Agent & CookieManager yang sama,
 * lalu men-flush cookie `cf_clearance` dan/atau mengembalikan HTML halaman asli.
 * Jika tantangan membutuhkan interaksi manusia (mis. Turnstile checkbox), handler akan
 * menyerah cepat agar alur jatuh ke overlay verifikasi interaktif (`CloudflareChallengeCoordinator`).
 */
object SilentWebViewCfHandler {
    private const val DEFAULT_TIMEOUT_MS = 10_000L
    private const val POLL_INTERVAL_MS = 450L
    private const val MIN_VALID_HTML_LEN = 600
    private const val FAILED_HOST_COOLDOWN_MS = 45_000L
    private const val GATE_WAIT_SECONDS = 4L

    private val webViewGate = Semaphore(1)
    private val failedHostCooldownUntil = ConcurrentHashMap<String, Long>()

    data class SilentFetchResult(
        val html: String?,
        val hasClearanceCookie: Boolean
    )

    fun isChallengeHtml(html: String?): Boolean {
        if (html.isNullOrBlank()) return true
        val lower = html.lowercase()
        return lower.contains("cf-browser-verification") ||
            lower.contains("cf_chl_opt") ||
            lower.contains("challenges.cloudflare.com") ||
            lower.contains("/cdn-cgi/challenge-platform/") ||
            lower.contains("checking your browser") ||
            lower.contains("attention required! | cloudflare") ||
            lower.contains("your request was blocked") ||
            lower.contains("verify you are human") ||
            (lower.contains("just a moment") && (lower.contains("cloudflare") || lower.contains("enable javascript")))
    }

    fun unescapeJsString(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        val trimmed = raw.trim()
        if (trimmed.length < 2 || trimmed.first() != '"' || trimmed.last() != '"') return trimmed
        val sb = StringBuilder(trimmed.length)
        var i = 1
        val end = trimmed.length - 1
        while (i < end) {
            val ch = trimmed[i]
            if (ch == '\\' && i + 1 < end) {
                when (val next = trimmed[i + 1]) {
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    '/' -> { sb.append('/'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    'f' -> { sb.append('\u000C'); i += 2 }
                    'u' -> {
                        if (i + 5 < end) {
                            val code = trimmed.substring(i + 2, i + 6).toIntOrNull(16)
                            if (code != null) {
                                sb.append(code.toChar())
                                i += 6
                            } else {
                                sb.append(ch)
                                i += 1
                            }
                        } else {
                            sb.append(ch)
                            i += 1
                        }
                    }
                    else -> { sb.append(next); i += 2 }
                }
            } else {
                sb.append(ch)
                i += 1
            }
        }
        return sb.toString()
    }

    fun clearCooldowns() {
        failedHostCooldownUntil.clear()
    }

    fun solveClearanceSilently(
        context: Context?,
        url: String,
        userAgent: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): Boolean {
        val res = executeSilent(
            context = context,
            url = url,
            userAgent = userAgent,
            requireHtml = false,
            timeoutMs = timeoutMs
        )
        return res != null && (res.hasClearanceCookie || !res.html.isNullOrBlank())
    }

    fun fetchHtmlSilently(
        context: Context?,
        url: String,
        userAgent: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): String? {
        val res = executeSilent(
            context = context,
            url = url,
            userAgent = userAgent,
            requireHtml = true,
            timeoutMs = timeoutMs
        )
        return res?.html?.takeIf { it.length >= MIN_VALID_HTML_LEN && !isChallengeHtml(it) }
    }

    private fun extractHost(url: String): String =
        runCatching { URI(url).host?.lowercase().orEmpty() }.getOrDefault("")

    @SuppressLint("SetJavaScriptEnabled")
    private fun executeSilent(
        context: Context?,
        url: String,
        userAgent: String,
        requireHtml: Boolean,
        timeoutMs: Long
    ): SilentFetchResult? {
        val appContext = context?.applicationContext ?: return null
        val mainLooper = runCatching { Looper.getMainLooper() }.getOrNull() ?: return null
        // Jangan pernah memblokir Main Thread.
        if (Looper.myLooper() == mainLooper) return null

        val host = extractHost(url)
        val now = System.currentTimeMillis()
        if (host.isNotBlank()) {
            val blockedUntil = failedHostCooldownUntil[host] ?: 0L
            if (now < blockedUntil) return null
        }

        val acquired = runCatching { webViewGate.tryAcquire(GATE_WAIT_SECONDS, TimeUnit.SECONDS) }
            .getOrDefault(false)
        if (!acquired) return null

        val handler = Handler(mainLooper)
        val latch = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        val pageLoaded = AtomicBoolean(false)
        val resultRef = AtomicReference<SilentFetchResult?>(null)
        val webViewRef = AtomicReference<WebView?>(null)
        val deadlineMs = System.currentTimeMillis() + timeoutMs

        fun finishWith(res: SilentFetchResult?) {
            if (finished.compareAndSet(false, true)) {
                resultRef.set(res)
                latch.countDown()
            }
        }

        val poller = object : Runnable {
            override fun run() {
                if (finished.get()) return
                val wv = webViewRef.get() ?: run {
                    finishWith(null)
                    return
                }
                if (System.currentTimeMillis() >= deadlineMs) {
                    finishWith(null)
                    return
                }
                val cookieStr = runCatching { CookieManager.getInstance().getCookie(url).orEmpty() }
                    .getOrDefault("")
                val hasClearance = cookieStr.contains("cf_clearance=")

                runCatching {
                    wv.evaluateJavascript(
                        "(function(){return document.documentElement?document.documentElement.outerHTML:'';})()"
                    ) { raw ->
                        if (finished.get()) return@evaluateJavascript
                        val html = unescapeJsString(raw)
                        val validHtml = !html.isNullOrBlank() &&
                            html.length >= MIN_VALID_HTML_LEN &&
                            !isChallengeHtml(html)

                        if (validHtml) {
                            runCatching { CookieManager.getInstance().flush() }
                            finishWith(SilentFetchResult(html = html, hasClearanceCookie = hasClearance))
                            return@evaluateJavascript
                        }
                        if (!requireHtml && hasClearance && pageLoaded.get() && !isChallengeHtml(html)) {
                            runCatching { CookieManager.getInstance().flush() }
                            finishWith(SilentFetchResult(html = html, hasClearanceCookie = true))
                            return@evaluateJavascript
                        }
                        if (System.currentTimeMillis() < deadlineMs && !finished.get()) {
                            handler.postDelayed(this, POLL_INTERVAL_MS)
                        } else {
                            finishWith(null)
                        }
                    }
                }.onFailure {
                    finishWith(null)
                }
            }
        }

        return try {
            handler.post {
                if (finished.get()) return@post
                runCatching {
                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    val wv = WebView(appContext)
                    webViewRef.set(wv)
                    cm.setAcceptThirdPartyCookies(wv, true)
                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        loadsImagesAutomatically = false
                        blockNetworkImage = true
                        mediaPlaybackRequiresUserGesture = true
                        cacheMode = WebSettings.LOAD_DEFAULT
                        if (userAgent.isNotBlank()) {
                            userAgentString = userAgent
                        }
                    }
                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                            pageLoaded.set(true)
                        }

                        override fun onReceivedSslError(
                            view: WebView?,
                            sslHandler: SslErrorHandler?,
                            error: SslError?
                        ) {
                            sslHandler?.cancel()
                            finishWith(null)
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            if (request?.isForMainFrame == true) {
                                finishWith(null)
                            }
                        }
                    }
                    wv.loadUrl(url)
                    handler.postDelayed(poller, POLL_INTERVAL_MS)
                }.onFailure {
                    finishWith(null)
                }
            }

            runCatching { latch.await(timeoutMs + 1_500L, TimeUnit.MILLISECONDS) }
            val result = resultRef.get()
            if (host.isNotBlank()) {
                if (result == null) {
                    failedHostCooldownUntil[host] = System.currentTimeMillis() + FAILED_HOST_COOLDOWN_MS
                } else {
                    failedHostCooldownUntil.remove(host)
                }
            }
            result
        } finally {
            finished.set(true)
            val cleanupLatch = CountDownLatch(1)
            val posted = handler.post {
                runCatching { handler.removeCallbacksAndMessages(null) }
                val wv = webViewRef.getAndSet(null)
                if (wv != null) {
                    runCatching { wv.stopLoading() }
                    runCatching { wv.webViewClient = WebViewClient() }
                    runCatching { wv.loadUrl("about:blank") }
                    runCatching { wv.clearHistory() }
                    runCatching { wv.removeAllViews() }
                    runCatching { wv.destroy() }
                }
                cleanupLatch.countDown()
            }
            if (posted) {
                runCatching { cleanupLatch.await(1_000L, TimeUnit.MILLISECONDS) }
            }
            webViewGate.release()
        }
    }
}
