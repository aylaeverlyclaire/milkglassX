package id.my.milkglass.extractor

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Headless Android WebView media URL sniffer untuk mengekstrak direct stream (.m3u8 / .mp4 / Blogger / GoogleVideo)
 * dari player embed berbasis JavaScript/AJAX yang gagal diekstrak lewat OkHttp/Regex polos.
 */
object EmbedMediaSniffer {
    private const val DEFAULT_TIMEOUT_MS = 6_500L
    private const val FAILED_HOST_COOLDOWN_MS = 30_000L
    private const val GATE_WAIT_SECONDS = 3L

    private val webViewGate = Semaphore(1)
    private val failedUrlCooldownUntil = ConcurrentHashMap<String, Long>()

    private val adAndTrackerTokens = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "googletagmanager.com",
        "google-analytics.com", "popads.net", "popcash.net", "exoclick.com", "juicyads.com",
        "propellerads.com", "adsterra.com", "highcpmgate.com", "effectivegatecpm.com",
        "histats.com", "disqus.com", "facebook.net", "facebook.com/tr", "qq288", "qq188", "qq1x2os", "velvetcairns", "redirectthisclick",
        "bet365", "1xbet", "slot", "casino", "judi", "togel", "gacor", "mgid.com",
        "taboola.com", "outbrain.com", "mc.yandex.ru", "onclickperformance", "acscdn.com"
    )

    private val blockedAssetExtensions = listOf(
        ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico",
        ".woff", ".woff2", ".ttf", ".otf", ".eot", ".css"
    )

    private val nonPlayablePathExtensions = listOf(
        ".php", ".html", ".htm", ".js", ".mjs", ".json", ".aspx", ".jsp", ".xml", ".txt"
    )

    private val nonMediaHints = listOf(
        "blank.mp4", "dummy.mp4", "intro.mp4", "ad.mp4", "ads.mp4", "preroll",
        "favicon", "pixel.gif", "beacon", "analytics", "telemetry"
    )

    fun clearCooldowns() {
        failedUrlCooldownUntil.clear()
    }

    internal fun isBlockedAdOrAssetUrl(url: String): Boolean {
        val low = url.lowercase(Locale.ROOT)
        if (adAndTrackerTokens.any { low.contains(it) }) return true
        val pathOnly = low.substringBefore('?').substringBefore('#')
        return blockedAssetExtensions.any { pathOnly.endsWith(it) }
    }

    internal fun isPlayableMediaUrl(url: String): Boolean {
        if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) {
            return false
        }
        val low = url.lowercase(Locale.ROOT)
        if (adAndTrackerTokens.any { low.contains(it) }) return false
        if (nonMediaHints.any { low.contains(it) }) return false
        val pathOnly = low.substringBefore('?').substringBefore('#')
        // Tolak halaman HTML/PHP/JS meski query string-nya menyebut .mp4 atau type=video
        if (nonPlayablePathExtensions.any { pathOnly.endsWith(it) }) {
            return false
        }
        if (pathOnly.endsWith(".m3u8") || pathOnly.endsWith(".mp4") || pathOnly.endsWith(".mkv")) {
            return true
        }
        if (low.contains("googlevideo.com/videoplayback") ||
            low.contains("blogger.googleusercontent.com") ||
            low.contains("video-downloads.googleusercontent.com") ||
            low.contains("drive.usercontent.google.com") ||
            low.contains("odcloud.net/") ||
            low.contains("pixeldrain.com/api/file/") ||
            low.contains("pixeldrain.lu/api/file/") ||
            low.contains("r2.cloudflarestorage.com/") ||
            low.contains(".r2.dev/") ||
            low.contains(".animeverse.id/") ||
            low.contains(".animekita.org/")
        ) {
            return true
        }
        return false
    }

    /**
     * Endpoint stream biner CDN yang hanya dipanggil oleh elemen <video> di dalam WebView
     * dan bukan halaman HTML/PHP. Lewati TCP probe kedua agar tidak memicu throttling token.
     */
    internal fun isTrustedBinaryStreamEndpoint(url: String): Boolean {
        if (!isPlayableMediaUrl(url)) return false
        val low = url.lowercase(Locale.ROOT)
        return low.contains("googlevideo.com/videoplayback") ||
            low.contains("blogger.googleusercontent.com") ||
            low.contains("video-downloads.googleusercontent.com") ||
            low.contains("cdn.odcloud.net/") ||
            low.contains("pixeldrain.com/api/file/") ||
            low.contains("pixeldrain.lu/api/file/") ||
            low.contains("r2.cloudflarestorage.com/") ||
            low.contains(".r2.dev/") ||
            low.contains(".animeverse.id/") ||
            low.contains(".animekita.org/")
    }

    /**
     * Validasi URL yang dibaca langsung dari elemen DOM `<video>.currentSrc` / `<source>.src`.
     * Mendukung stream proxy `.php` atau CDN tanpa ekstensi file (mis. server Animeverse YLnime),
     * sementara tetap menolak iklan, aset statis, dan halaman HTML/JS.
     */
    internal fun isDomVideoElementSourceUrl(url: String): Boolean {
        if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) {
            return false
        }
        val low = url.lowercase(Locale.ROOT)
        if (adAndTrackerTokens.any { low.contains(it) }) return false
        if (nonMediaHints.any { low.contains(it) }) return false
        val pathOnly = low.substringBefore('?').substringBefore('#')
        if (blockedAssetExtensions.any { pathOnly.endsWith(it) }) return false
        if (pathOnly.endsWith(".html") || pathOnly.endsWith(".htm") || pathOnly.endsWith(".js") ||
            pathOnly.endsWith(".mjs") || pathOnly.endsWith(".json") || pathOnly.endsWith(".xml")
        ) return false
        return true
    }

    internal fun isRangeBackedMediaRequest(url: String, headers: Map<String, String>): Boolean {
        val hasRange = headers.entries.any { (k, v) ->
            k.equals("Range", ignoreCase = true) && v.lowercase(Locale.ROOT).startsWith("bytes=")
        }
        if (!hasRange) return false
        return isDomVideoElementSourceUrl(url)
    }

    internal fun isHlsUrl(url: String): Boolean {
        val low = url.lowercase(Locale.ROOT)
        val pathOnly = low.substringBefore('?').substringBefore('#')
        return pathOnly.endsWith(".m3u8") || low.contains("format=hls")
    }

    internal fun extractOrigin(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return runCatching {
            val uri = URI(url)
            val scheme = uri.scheme?.takeIf { it == "http" || it == "https" } ?: return null
            val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
            if (uri.port > 0 && uri.port != 80 && uri.port != 443) "$scheme://$host:${uri.port}" else "$scheme://$host"
        }.getOrNull()
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun sniff(
        context: Context?,
        embedUrl: String,
        pageReferer: String?,
        userAgent: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): ExtractedDirectMedia? {
        val appContext = context?.applicationContext ?: return null
        if (!embedUrl.startsWith("https://", ignoreCase = true) && !embedUrl.startsWith("http://", ignoreCase = true)) {
            return null
        }
        val mainLooper = runCatching { Looper.getMainLooper() }.getOrNull() ?: return null
        if (Looper.myLooper() == mainLooper) return null

        val now = System.currentTimeMillis()
        val cooldownKey = embedUrl.substringBefore('#')
        if ((failedUrlCooldownUntil[cooldownKey] ?: 0L) > now) return null

        val acquired = runCatching { webViewGate.tryAcquire(GATE_WAIT_SECONDS, TimeUnit.SECONDS) }
            .getOrDefault(false)
        if (!acquired) return null

        val handler = Handler(mainLooper)
        val latch = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        val resultRef = AtomicReference<ExtractedDirectMedia?>(null)
        val webViewRef = AtomicReference<WebView?>(null)
        val embedHost = runCatching { URI(embedUrl).host?.lowercase(Locale.ROOT).orEmpty() }.getOrDefault("")
        val emptyBlockedResponse = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

        fun finishWith(media: ExtractedDirectMedia?) {
            if (finished.compareAndSet(false, true)) {
                resultRef.set(media)
                latch.countDown()
            }
        }

        val timeoutRunnable = Runnable { finishWith(null) }

        return try {
            handler.post {
                if (finished.get()) return@post
                runCatching {
                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    val wv = WebView(appContext)
                    webViewRef.set(wv)
                    wv.layout(0, 0, 640, 360)
                    wv.onResume()
                    cm.setAcceptThirdPartyCookies(wv, true)
                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        loadsImagesAutomatically = false
                        blockNetworkImage = true
                        mediaPlaybackRequiresUserGesture = false
                        setSupportMultipleWindows(false)
                        javaScriptCanOpenWindowsAutomatically = false
                        cacheMode = WebSettings.LOAD_DEFAULT
                        if (userAgent.isNotBlank()) {
                            userAgentString = userAgent
                        }
                    }

                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val target = request?.url?.toString().orEmpty()
                            if (target.isBlank()) return true
                            if (isBlockedAdOrAssetUrl(target)) return true
                            val targetHost = runCatching { URI(target).host?.lowercase(Locale.ROOT).orEmpty() }.getOrDefault("")
                            return embedHost.isNotBlank() && targetHost.isNotBlank() &&
                                targetHost != embedHost && !targetHost.endsWith(".$embedHost")
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            if (finished.get()) return emptyBlockedResponse
                            val reqUrl = request?.url?.toString()?.trim().orEmpty()
                            if (reqUrl.isBlank()) return null

                            val reqHeaders = request?.requestHeaders.orEmpty()
                            if (isPlayableMediaUrl(reqUrl) || isRangeBackedMediaRequest(reqUrl, reqHeaders)) {
                                val ref = reqHeaders["Referer"]
                                    ?: reqHeaders["referer"]
                                    ?: embedUrl
                                val origin = reqHeaders["Origin"]
                                    ?: reqHeaders["origin"]
                                    ?: extractOrigin(ref)
                                val cookie = runCatching {
                                    CookieManager.getInstance().getCookie(reqUrl)
                                        ?: CookieManager.getInstance().getCookie(embedUrl)
                                }.getOrNull()?.takeIf { it.isNotBlank() }

                                val extra = linkedMapOf<String, String>()
                                if (userAgent.isNotBlank()) extra["User-Agent"] = userAgent
                                if (!origin.isNullOrBlank()) extra["Origin"] = origin
                                if (!cookie.isNullOrBlank()) extra["Cookie"] = cookie

                                val hostLabel = runCatching { URI(reqUrl).host.orEmpty() }
                                    .getOrDefault("WebViewSniff")
                                    .ifBlank { "WebViewSniff" }
                                val hls = isHlsUrl(reqUrl)
                                finishWith(
                                    ExtractedDirectMedia(
                                        hoster = "Embed:$hostLabel",
                                        directUrl = reqUrl,
                                        referer = ref,
                                        supportsRange = !hls,
                                        isArchive = false,
                                        isHls = hls,
                                        extraHeaders = extra
                                    )
                                )
                                return emptyBlockedResponse
                            }

                            if (isBlockedAdOrAssetUrl(reqUrl)) {
                                return emptyBlockedResponse
                            }
                            return null
                        }

                        override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                            if (finished.get() || view == null) return
                            runCatching {
                                view.evaluateJavascript(
                                    """
                                    (function(){
                                      try {
                                        // 1. Tutup overlay iklan (mis. "Tutup Iklan" / ad banner) sebelum memicu play
                                        var closeEls = document.querySelectorAll('a, button, span, div');
                                        for (var i = 0; i < closeEls.length; i++) {
                                          var txt = (closeEls[i].innerText || '').trim().toLowerCase();
                                          if (txt === 'tutup iklan' || txt === 'close ad' || txt === 'close ads' || txt === 'skip ad') {
                                            closeEls[i].click();
                                          }
                                        }
                                        var overlays = document.querySelectorAll('[class*="iklan" i], [id*="iklan" i], [class*="ad-overlay" i], [class*="popup" i]');
                                        for (var j = 0; j < overlays.length; j++) {
                                          overlays[j].style.display = 'none';
                                        }
                                        // 2. Cek video source langsung atau picu play (termasuk klik kedua untuk player 2-tahap)
                                        var v = document.querySelector('video');
                                        if (v) {
                                          var s = v.currentSrc || v.src || (v.querySelector('source') && v.querySelector('source').src) || '';
                                          if (s) return s;
                                          if (typeof v.play === 'function') v.play().catch(function(){});
                                        }
                                        var triggerPlay = function() {
                                          var btn = document.querySelector('.jw-icon-display, .plyr__control--overlaid, .vjs-big-play-button, button[aria-label*="Play" i], .play-button, #play');
                                          if (btn) btn.click();
                                          var vv = document.querySelector('video');
                                          if (vv && typeof vv.play === 'function') vv.play().catch(function(){});
                                        };
                                        triggerPlay();
                                        setTimeout(triggerPlay, 350);
                                      } catch(e) {}
                                      return '';
                                    })();
                                    """.trimIndent()
                                ) { raw ->
                                    if (finished.get()) return@evaluateJavascript
                                    val cleaned = id.my.milkglass.net.SilentWebViewCfHandler.unescapeJsString(raw)?.trim().orEmpty()
                                    if (isPlayableMediaUrl(cleaned) || isDomVideoElementSourceUrl(cleaned)) {
                                        val cookie = runCatching {
                                            CookieManager.getInstance().getCookie(cleaned)
                                                ?: CookieManager.getInstance().getCookie(embedUrl)
                                        }.getOrNull()?.takeIf { it.isNotBlank() }
                                        val origin = extractOrigin(embedUrl)
                                        val extra = linkedMapOf<String, String>()
                                        if (userAgent.isNotBlank()) extra["User-Agent"] = userAgent
                                        if (!origin.isNullOrBlank()) extra["Origin"] = origin
                                        if (!cookie.isNullOrBlank()) extra["Cookie"] = cookie
                                        val hls = isHlsUrl(cleaned)
                                        finishWith(
                                            ExtractedDirectMedia(
                                                hoster = "Embed:DOM",
                                                directUrl = cleaned,
                                                referer = embedUrl,
                                                supportsRange = !hls,
                                                isArchive = false,
                                                isHls = hls,
                                                extraHeaders = extra
                                            )
                                        )
                                    }
                                }
                            }
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

                    val initialHeaders = if (!pageReferer.isNullOrBlank()) {
                        mapOf("Referer" to pageReferer)
                    } else {
                        emptyMap()
                    }
                    wv.loadUrl(embedUrl, initialHeaders)
                    handler.postDelayed(timeoutRunnable, timeoutMs)
                }.onFailure {
                    finishWith(null)
                }
            }

            runCatching { latch.await(timeoutMs + 1_000L, TimeUnit.MILLISECONDS) }
            val result = resultRef.get()
            if (result == null) {
                if (failedUrlCooldownUntil.size > 128) failedUrlCooldownUntil.clear()
                failedUrlCooldownUntil[cooldownKey] = System.currentTimeMillis() + FAILED_HOST_COOLDOWN_MS
            } else {
                failedUrlCooldownUntil.remove(cooldownKey)
            }
            result
        } finally {
            finished.set(true)
            val cleanupLatch = CountDownLatch(1)
            val posted = handler.post {
                runCatching { handler.removeCallbacksAndMessages(null) }
                val wv = webViewRef.getAndSet(null)
                if (wv != null) {
                    runCatching { wv.onPause() }
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
