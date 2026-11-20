package id.my.milkglass.extractor

import id.my.milkglass.net.IsolatedHttpClients
import id.my.milkglass.net.PublicIpGuard
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class ExtractedDirectMedia(
    val hoster: String,
    val directUrl: String,
    val referer: String?,
    val supportsRange: Boolean,
    val isArchive: Boolean = false,
    val isHls: Boolean = false,
    val contentType: String? = null,
    val extraHeaders: Map<String, String> = emptyMap()
)

object HosterFilter {
    private val blacklistedTokens = listOf(
        "mega.nz", "mega.co.nz", "megaup", "otakufiles", "zippyshare",
        "mirrored.to", "acefile.co", "(rar)", ".rar", "(zip)", "terabox", "shrinkearn"
    )

    private val preferredOrder = listOf(
        "pixeldrain", "pdrain", "gofile", "buzzheavier", "filedon", "odfiles",
        "gdrive", "drive.google", "qiwi", "mediafire", "kuramadrive",
        "icefile.web.id", "odstream", "ondesu", "desustream", "blogger", "streamruby",
        "vidhide", "vidhidepre", "yourupload", "berkasdrive", "mitedrive", "short.ink", "filemoon", "mixdrop", "streamtape", "dood", "krakenfiles", "kfiles"
    )

    fun isBlacklisted(hosterLabel: String, url: String): Boolean {
        val combined = "${hosterLabel.lowercase()} ${url.lowercase()}"
        return blacklistedTokens.any { combined.contains(it) }
    }

    fun rank(hosterLabel: String, url: String): Int {
        val combined = "${hosterLabel.lowercase()} ${url.lowercase()}"
        val idx = preferredOrder.indexOfFirst { combined.contains(it) }
        return if (idx >= 0) idx else preferredOrder.size + 1
    }

    fun isKnownPlayableHost(url: String): Boolean {
        val low = url.lowercase()
        return preferredOrder.any { low.contains(it) } ||
            low.contains("wibufile.com") ||
            low.contains("uqni.net") ||
            low.contains("drive.usercontent.google.com") ||
            low.contains("docs.google.com") ||
            low.contains("workers.dev") ||
            low.contains("r2.dev") ||
            low.contains("storage.googleapis.com") ||
            low.contains("googlevideo.com") ||
            low.contains("rubyvidhub.com") ||
            low.contains("streamhide.") ||
            low.contains("sunrong.my.id") ||
            low.contains(".mkv") ||
            low.contains(".mp4") ||
            low.contains(".m3u8")
    }
}

object HosterUrlHelpers {
    private val gdriveFileIdPathRegex = Regex(
        """/(?:file|document)/d/([A-Za-z0-9_-]+)""",
        RegexOption.IGNORE_CASE
    )
    private val gdriveIdQueryRegex = Regex(
        """[?&]id=([A-Za-z0-9_-]+)""",
        RegexOption.IGNORE_CASE
    )
    private val gdriveResourceKeyRegex = Regex(
        """[?&]resourcekey=([^&#]+)""",
        RegexOption.IGNORE_CASE
    )

    private val oneFichierDomains = listOf(
        "1fichier.com", "alterupload.com", "cjoint.net", "desfichiers.net",
        "desfichiers.com", "dfichiers.com", "megadl.fr", "mesfichiers.org",
        "piecejointe.net", "pjointe.com", "tenvoi.com", "dl4free.com"
    )
    private val oneFichierHostsPattern = oneFichierDomains.joinToString("|") { Regex.escape(it) }
    private val oneFichierQueryRegex = Regex(
        """^https?://(?:www\.)?($oneFichierHostsPattern)/\?([a-z0-9]{5,20})""",
        RegexOption.IGNORE_CASE
    )
    private val oneFichierSubdomainRegex = Regex(
        """^https?://([a-z0-9]{5,20})\.($oneFichierHostsPattern)/?(?:[?#].*)?$""",
        RegexOption.IGNORE_CASE
    )
    private val linkboxTokenRegex = Regex(
        """(?:/f/|/file/|\?id=)(\w+)""",
        RegexOption.IGNORE_CASE
    )
    private val yourUploadIdRegex = Regex(
        """yourupload\.com/(?:watch|embed)/([0-9A-Za-z]+)""",
        RegexOption.IGNORE_CASE
    )

    private val sendNowDomains = listOf(
        "send.now", "send.cm", "sendit.cloud", "tusfiles.com", "tusfiles.net", "userscloud.com"
    )

    fun isSendNowUrl(lowerUrl: String): Boolean {
        return sendNowDomains.any { lowerUrl.contains(it) }
    }

    fun isOneFichierUrl(lowerUrl: String): Boolean {
        return oneFichierDomains.any { lowerUrl.contains(it) }
    }

    fun normalizeOneFichierUrl(url: String): String? {
        val trimmed = url.trim()
        oneFichierQueryRegex.find(trimmed)?.let { m ->
            return "https://${m.groupValues[1].lowercase()}/?${m.groupValues[2].lowercase()}"
        }
        oneFichierSubdomainRegex.find(trimmed)?.let { m ->
            val sub = m.groupValues[1].lowercase()
            if (sub != "www") {
                return "https://${m.groupValues[2].lowercase()}/?$sub"
            }
        }
        return null
    }

    fun parseGoogleDriveFileId(url: String): Pair<String, String?>? {
        val trimmed = url.trim()
        val id = gdriveFileIdPathRegex.find(trimmed)?.groupValues?.getOrNull(1)
            ?: gdriveIdQueryRegex.find(trimmed)?.groupValues?.getOrNull(1)
            ?: return null
        val resourceKey = gdriveResourceKeyRegex.find(trimmed)?.groupValues?.getOrNull(1)
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        return id to resourceKey
    }

    fun parseLinkboxToken(url: String): String? {
        return linkboxTokenRegex.find(url.trim())?.groupValues?.getOrNull(1)
    }

    fun parseYourUploadEmbedUrl(url: String): String? {
        val id = yourUploadIdRegex.find(url.trim())?.groupValues?.getOrNull(1) ?: return null
        return "https://www.yourupload.com/embed/$id"
    }
}

class LinkUnshortener(
    private val clients: IsolatedHttpClients
) {
    private val resolvedCache = ConcurrentHashMap<String, Pair<Long, String>>()
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<String>>()
    private val sourceNoRedirectClient by lazy {
        clients.sourceClient.newBuilder()
            .connectTimeout(3_000, TimeUnit.MILLISECONDS)
            .readTimeout(4_000, TimeUnit.MILLISECONDS)
            .callTimeout(5_000, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
    private val metaRefreshRegex = Regex(
        """<meta[^>]+http-equiv=["']refresh["'][^>]+content=["'][^"']*url=([^"'\s>]+)["']""",
        RegexOption.IGNORE_CASE
    )
    private val jsRedirectRegex = Regex(
        """window\.location(?:\.href)?\s*=\s*["'](https?://[^"']+)["']""",
        RegexOption.IGNORE_CASE
    )

    fun unshorten(initialUrl: String, referer: String? = null, maxHops: Int = 10): String {
        val trimmed = initialUrl.trim()
        val now = System.currentTimeMillis()
        resolvedCache[trimmed]?.let { (exp, target) ->
            if (exp > now) return target
            resolvedCache.remove(trimmed)
        }
        val mine = CompletableFuture<String>()
        val existing = inFlight.putIfAbsent(trimmed, mine)
        if (existing != null) {
            return runCatching { existing.get(10L, TimeUnit.SECONDS) }.getOrDefault(trimmed)
        }
        return try {
            val out = unshortenUncached(trimmed, referer, maxHops)
            if (resolvedCache.size > 256) resolvedCache.clear()
            resolvedCache[trimmed] = (System.currentTimeMillis() + 10L * 60_000L) to out
            mine.complete(out)
            out
        } catch (e: Exception) {
            mine.complete(trimmed)
            trimmed
        } finally {
            inFlight.remove(trimmed, mine)
        }
    }

    private fun unshortenUncached(initialUrl: String, referer: String?, maxHops: Int): String {
        var current = initialUrl.trim()
        val visited = mutableSetOf<String>()

        for (hop in 0 until maxHops) {
            if (!current.startsWith("http://") && !current.startsWith("https://")) break
            if (!visited.add(current)) break

            val parsed = current.toHttpUrlOrNull() ?: break
            val b64Target = decodeBase64ParamTarget(parsed)
            if (b64Target != null && b64Target != current) {
                current = b64Target
                continue
            }
            PublicIpGuard.verifyPublicHost(clients.dns, parsed.host)

            val low = current.lowercase()
            if (isTerminalHoster(low)) return current

            val reqBuilder = Request.Builder().url(current)
            if (!referer.isNullOrBlank()) reqBuilder.header("Referer", referer)

            val client = if (low.contains("otakudesu") || low.contains("samehadaku") ||
                low.contains("oploverz") || low.contains("kusonime")
            ) {
                sourceNoRedirectClient
            } else {
                clients.noRedirectClient
            }

            val nextUrl = runCatching {
                client.newCall(reqBuilder.build()).execute().use { resp ->
                    if (resp.code in 300..399) {
                        val loc = resp.header("Location")
                        if (!loc.isNullOrBlank()) {
                            return@use URI(current).resolve(loc.trim()).toString()
                        }
                    }
                    val contentType = resp.header("Content-Type").orEmpty().lowercase()
                    if (contentType.contains("text/html") && resp.isSuccessful) {
                        val html = resp.body?.string().orEmpty()
                        val meta = metaRefreshRegex.find(html)?.groupValues?.getOrNull(1)
                        if (!meta.isNullOrBlank()) {
                            return@use URI(current).resolve(meta.trim()).toString()
                        }
                        if (low.contains("desustream") || low.contains("safelink") || low.contains("desudrive") || low.contains("mirrored.to") || low.contains("multiup.")) {
                            val js = jsRedirectRegex.find(html)?.groupValues?.getOrNull(1)
                            if (!js.isNullOrBlank()) return@use js.trim()
                            val doc = Jsoup.parse(html, current)
                            val anchor = doc.select("a[href]").firstOrNull { a ->
                                val h = a.absUrl("href").lowercase()
                                isTerminalHoster(h)
                            }?.absUrl("href")
                            if (!anchor.isNullOrBlank()) return@use anchor
                        }
                    }
                    null
                }
            }.getOrNull()

            if (nextUrl.isNullOrBlank() || nextUrl == current) break
            current = nextUrl
        }
        return current
    }

    private fun decodeBase64ParamTarget(url: okhttp3.HttpUrl): String? {
        for (key in listOf("url", "link", "target", "go", "u", "dest")) {
            val raw = url.queryParameter(key)?.trim()?.takeIf { it.length >= 12 } ?: continue
            if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
            val decoded = runCatching {
                String(Base64.getDecoder().decode(raw.replace(' ', '+').replace('-', '+').replace('_', '/')), Charsets.UTF_8).trim()
            }.getOrNull()
            if (decoded != null && (decoded.startsWith("http://") || decoded.startsWith("https://"))) {
                return decoded
            }
        }
        return null
    }

    private fun isTerminalHoster(lowerUrl: String): Boolean {
        return lowerUrl.contains("pixeldrain.com") ||
            lowerUrl.contains("pixeldrain.lu") ||
            lowerUrl.contains("krakenfiles.com") ||
            lowerUrl.contains("filedon.co") ||
            lowerUrl.contains("gofile.io") ||
            lowerUrl.contains("wibufile.com") ||
            lowerUrl.contains("mediafire.com") ||
            lowerUrl.contains("buzzheavier.com") ||
            lowerUrl.contains("qiwi.gg") ||
            HosterUrlHelpers.isSendNowUrl(lowerUrl) ||
            lowerUrl.contains("racaty") ||
            lowerUrl.contains("drive.google.com") ||
            lowerUrl.contains("drive.usercontent.google.com") ||
            lowerUrl.contains("docs.google.com") ||
            HosterUrlHelpers.isOneFichierUrl(lowerUrl) ||
            lowerUrl.contains("archive.org") ||
            lowerUrl.contains("yourupload.com") ||
            lowerUrl.contains("lbx.to") ||
            lowerUrl.contains("linkbox.to") ||
            lowerUrl.contains("kuramadrive.com") ||
            lowerUrl.contains("gdflix.") ||
            lowerUrl.contains("hubdrive.") ||
            lowerUrl.contains("workers.dev") ||
            lowerUrl.contains("r2.dev") ||
            lowerUrl.contains("dood.") ||
            lowerUrl.contains("odstream.xyz") ||
            lowerUrl.contains("ondesu.cc") ||
            lowerUrl.contains("ondesuh.cc") ||
            lowerUrl.contains("desustream.") ||
            lowerUrl.contains("blogger.com") ||
            lowerUrl.contains("googlevideo.com") ||
            lowerUrl.contains("ok.ru") ||
            lowerUrl.contains("odnoklassniki.ru") ||
            lowerUrl.contains("dailymotion.com") ||
            lowerUrl.contains("dai.ly") ||
            lowerUrl.contains("rumble.com") ||
            lowerUrl.contains("rubyvidhub.com") ||
            lowerUrl.contains("streamruby.") ||
            lowerUrl.contains("odvidhide.com") ||
            lowerUrl.contains("vidhide") ||
            lowerUrl.contains("streamhide.") ||
            lowerUrl.contains("filemoon.") ||
            lowerUrl.contains("sunrong.my.id") ||
            lowerUrl.contains("mixdrop.") ||
            lowerUrl.contains("streamtape.") ||
            lowerUrl.endsWith(".mkv") ||
            lowerUrl.contains(".mkv?") ||
            lowerUrl.endsWith(".mp4") ||
            lowerUrl.contains(".mp4?") ||
            lowerUrl.endsWith(".m3u8") ||
            lowerUrl.contains(".m3u8?")
    }
}

class MkvHosterExtractorRegistry(
    private val clients: IsolatedHttpClients
) {
    private val pixeldrainIdRegex = Regex(
        """pixeldrain\.(?:com|lu)/(?:u|d|l|api/file|api/filesystem)/([a-zA-Z0-9_-]+)""",
        RegexOption.IGNORE_CASE
    )
    private val filedoSlugRegex = Regex(
        """filedon\.co/(?:view|embed|d|f)/([a-zA-Z0-9_-]+)""",
        RegexOption.IGNORE_CASE
    )
    private val gofileIdRegex = Regex("""gofile\.io/(?:d|w)/([a-zA-Z0-9]+)""", RegexOption.IGNORE_CASE)
    private val krakenIdRegex = Regex(
        """krakenfiles\.com/(?:view|download|embed-video)/([a-zA-Z0-9]+)""",
        RegexOption.IGNORE_CASE
    )

    fun extract(resolvedUrl: String, referer: String? = null): ExtractedDirectMedia? {
        return extractInternal(resolvedUrl, referer, depth = 0)
    }

    private fun extractInternal(resolvedUrl: String, referer: String?, depth: Int): ExtractedDirectMedia? {
        if (depth > 2) return null
        val low = resolvedUrl.lowercase()
        val parsed = resolvedUrl.toHttpUrlOrNull() ?: return null
        PublicIpGuard.verifyPublicHost(clients.dns, parsed.host)

        if ((low.contains(".m3u8")) && !low.contains(".html")) {
            return ExtractedDirectMedia(
                hoster = "DirectHLS",
                directUrl = resolvedUrl,
                referer = referer,
                supportsRange = false,
                isHls = true,
                contentType = "application/vnd.apple.mpegurl"
            )
        }

        val isLandingPageHost = low.contains("mediafire.com") || low.contains("krakenfiles.com") ||
            low.contains("filedon.co") || low.contains("gofile.io") || low.contains("buzzheavier.com") ||
            low.contains("1fichier.com") || low.contains("racaty") || low.contains("send.now")
        if (!isLandingPageHost && (low.contains(".mkv") || low.contains("wibufile.com") || low.contains("uqni.net") ||
                low.contains(".workers.dev") || low.contains(".r2.dev") || low.contains("storage.googleapis.com")) &&
            !low.contains(".html")
        ) {
            return ExtractedDirectMedia(
                hoster = "DirectMKV",
                directUrl = resolvedUrl,
                referer = referer,
                supportsRange = true
            )
        }

        if (!isLandingPageHost && (low.contains(".mp4") || low.contains(".m3u8") ||
                low.contains("googlevideo.com/videoplayback") ||
                low.contains("blogger.googleusercontent.com")) &&
            !low.contains(".html")
        ) {
            val isHls = low.contains(".m3u8")
            val directReferer = if (low.contains("googlevideo.com/")) "https://youtube.googleapis.com/" else referer
            return ExtractedDirectMedia(
                hoster = if (isHls) "DirectHLS" else "DirectMP4",
                directUrl = resolvedUrl,
                referer = directReferer,
                supportsRange = !isHls,
                isHls = isHls
            )
        }

        return when {
            low.contains("pixeldrain.com") || low.contains("pixeldrain.lu") -> extractPixeldrain(resolvedUrl)
            low.contains("filedon.co") -> extractFiledon(resolvedUrl)
            low.contains("krakenfiles.com") -> extractKrakenfiles(resolvedUrl)
            low.contains("gofile.io") -> extractGoFile(resolvedUrl)
            low.contains("mediafire.com") -> extractMediaFire(resolvedUrl)
            low.contains("buzzheavier.com") -> extractBuzzheavier(resolvedUrl)
            low.contains("qiwi.gg") -> extractQiwi(resolvedUrl)
            low.contains("drive.google.com") || low.contains("drive.usercontent.google.com") ||
                low.contains("docs.google.com") -> extractGoogleDrive(resolvedUrl)
            low.contains("kuramadrive.com") -> extractKuramadrive(resolvedUrl)
            low.contains("dood.") || low.contains("doodstream.") || low.contains("ds2play.") -> extractDood(resolvedUrl)
            low.contains("odstream.xyz") || low.contains("desustream.") || low.contains("icefile.web.id") -> extractOdstreamOrDesustream(resolvedUrl, referer)
            low.contains("ondesu.cc") || low.contains("ondesuh.cc") -> extractOndesu(resolvedUrl, referer, depth)
            low.contains("blogger.com") -> extractBlogger(resolvedUrl, referer)
            low.contains("rubyvidhub.com") || low.contains("streamruby.") -> extractStreamRuby(resolvedUrl, referer)
            low.contains("odvidhide.com") || low.contains("vidhide") || low.contains("streamhide.") ||
                low.contains("filemoon.") || low.contains("sunrong.my.id") -> extractVidhideOrFilemoon(resolvedUrl, referer)
            low.contains("mixdrop.") -> extractMixdrop(resolvedUrl)
            low.contains("streamtape.") -> extractStreamtape(resolvedUrl)
            else -> extractGenericEmbedPlayer(resolvedUrl, referer, depth)
        }
    }

    private fun extractPixeldrain(url: String): ExtractedDirectMedia? {
        val id = pixeldrainIdRegex.find(url)?.groupValues?.getOrNull(1) ?: return null
        val low = url.lowercase()
        val directUrl = when {
            low.contains("/api/file/") -> url
            low.contains("/d/") || low.contains("/filesystem/") -> {
                val statUrl = "https://pixeldrain.com/api/filesystem/$id?stat"
                val statJson = fetchText(statUrl, url) ?: return null
                val root = runCatching { JSONObject(statJson) }.getOrNull() ?: return null
                val pathArr = root.optJSONArray("path")
                val baseIdx = root.optInt("base_index", 0)
                val pathStr = pathArr?.optJSONObject(baseIdx)?.optString("path")
                    ?.takeIf { it.isNotBlank() } ?: "/$id"
                "https://pixeldrain.com/api/filesystem$pathStr?attach"
            }
            low.contains("/l/") || low.contains("/list/") -> {
                val listJson = fetchText("https://pixeldrain.com/api/list/$id", url) ?: return null
                val files = runCatching { JSONObject(listJson).optJSONArray("files") }.getOrNull()
                    ?: return null
                var chosenId: String? = null
                for (i in 0 until files.length()) {
                    val f = files.optJSONObject(i) ?: continue
                    val name = f.optString("name").lowercase()
                    if (name.endsWith(".mkv") || name.endsWith(".mp4")) {
                        chosenId = f.optString("id")
                        break
                    }
                }
                val finalId = chosenId ?: files.optJSONObject(0)?.optString("id") ?: return null
                "https://pixeldrain.com/api/file/$finalId"
            }
            else -> "https://pixeldrain.com/api/file/$id"
        }
        return ExtractedDirectMedia(
            hoster = "Pixeldrain",
            directUrl = directUrl,
            referer = url,
            supportsRange = true
        )
    }

    private fun extractFiledon(url: String): ExtractedDirectMedia? {
        val slug = filedoSlugRegex.find(url)?.groupValues?.getOrNull(1)
            ?: url.trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() }
            ?: return null
        val candidates = listOf("https://filedon.co/embed/$slug", url).distinct()
        for (target in candidates) {
            val html = fetchText(target, "https://filedon.co/") ?: continue
            val doc = Jsoup.parse(html, target)
            val rawDataPage = doc.selectFirst("[data-page]")?.attr("data-page") ?: continue
            val props = runCatching { JSONObject(rawDataPage).optJSONObject("props") }.getOrNull() ?: continue
            val directUrl = listOf("url", "download_url", "stream_url", "file_url", "direct_url")
                .firstNotNullOfOrNull { k -> props.optString(k).takeIf { it.startsWith("http") } }
                ?: props.optJSONObject("media")?.optString("hls_url")?.takeIf { it.startsWith("http") }
                ?: continue
            return ExtractedDirectMedia(
                hoster = "ODFiles",
                directUrl = directUrl,
                referer = "https://filedon.co/",
                supportsRange = !directUrl.contains(".m3u8", ignoreCase = true),
                isHls = directUrl.contains(".m3u8", ignoreCase = true)
            )
        }
        // /get-url returns HTTP 405 on live filedon.co; avoid 3.9s wasted fallback.
        return null
    }

    private fun extractKrakenfiles(url: String): ExtractedDirectMedia? {
        val id = krakenIdRegex.find(url)?.groupValues?.getOrNull(1) ?: return null
        val pageUrl = if (url.contains("/view/") || url.contains("/embed-video/")) url else "https://krakenfiles.com/embed-video/$id"
        val req = Request.Builder()
            .url(pageUrl)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()

        clients.mediaAndApiClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val html = resp.body?.string().orEmpty()
            if (html.contains("File not found", ignoreCase = true)) return null

            val doc = Jsoup.parse(html, pageUrl)
            val sourceSrc = doc.selectFirst("source[src*=krakencloud], source[type=video/mp4], source[src]")
                ?.attr("src")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            if (sourceSrc != null) {
                val fixed = if (sourceSrc.startsWith("//")) "https:$sourceSrc" else sourceSrc
                return ExtractedDirectMedia(
                    hoster = "KrakenFiles",
                    directUrl = fixed,
                    referer = pageUrl,
                    supportsRange = true
                )
            }

            val dlForm = doc.selectFirst("#dl-form") ?: return null
            val action = dlForm.attr("action").ifBlank { "/download/$id" }
            val token = dlForm.selectFirst("input[name=token]")?.attr("value").orEmpty()
            if (token.isBlank()) return null

            val postUrl = if (action.startsWith("http")) action else "https://krakenfiles.com$action"
            val cookieHeader = resp.headers("Set-Cookie")
                .map { it.substringBefore(';').trim() }
                .filter { it.isNotEmpty() }
                .joinToString("; ")

            val formBuilder = FormBody.Builder()
            var hasToken = false
            dlForm.select("input[name]").forEach { inp ->
                val n = inp.attr("name").trim()
                val v = inp.attr("value")
                if (n.isNotEmpty()) {
                    formBuilder.add(n, v)
                    if (n == "token") hasToken = true
                }
            }
            if (!hasToken) formBuilder.add("token", token)

            val postReq = Request.Builder()
                .url(postUrl)
                .header("Referer", pageUrl)
                .header("Origin", "https://krakenfiles.com")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Hash", id)
                .apply { if (cookieHeader.isNotEmpty()) header("Cookie", cookieHeader) }
                .post(formBuilder.build())
                .build()

            clients.mediaAndApiClient.newCall(postReq).execute().use { postResp ->
                if (!postResp.isSuccessful) return null
                val jsonText = postResp.body?.string().orEmpty()
                val dlUrl = runCatching { JSONObject(jsonText).optString("url") }.getOrNull()
                    ?.takeIf { it.startsWith("http") } ?: return null
                return ExtractedDirectMedia(
                    hoster = "KrakenFiles",
                    directUrl = dlUrl,
                    referer = pageUrl,
                    supportsRange = true
                )
            }
        }
    }

    private fun extractBuzzheavier(url: String): ExtractedDirectMedia? {
        val clean = url.trimEnd('/')
        val id = Regex("""buzzheavier\.com/(?:f/)?([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
            .find(clean)?.groupValues?.getOrNull(1)
        val targets = buildList {
            add("$clean/download")
            if (id != null) add("https://buzzheavier.com/f/$id/download")
        }.distinct()
        var direct: String? = null
        for (target in targets) {
            val req = Request.Builder()
                .url(target)
                .header("HX-Current-URL", url)
                .header("HX-Request", "true")
                .header("Referer", url)
                .build()
            direct = runCatching {
                clients.noRedirectClient.newCall(req).execute().use { resp ->
                    resp.header("hx-redirect") ?: resp.header("Location")
                }
            }.getOrNull()?.takeIf { it.startsWith("http") }
            if (direct != null) break
        }
        if (direct == null) return null
        return ExtractedDirectMedia(
            hoster = "Buzzheavier",
            directUrl = direct,
            referer = "https://buzzheavier.com/",
            supportsRange = true
        )
    }

    private fun extractQiwi(url: String): ExtractedDirectMedia? {
        val html = fetchText(url, "https://qiwi.gg/") ?: return null
        val doc = Jsoup.parse(html, url)
        val src = doc.selectFirst("video source[src]")?.attr("src")?.trim()
            ?.takeIf { it.startsWith("http") } ?: return null
        return ExtractedDirectMedia(
            hoster = "Qiwi",
            directUrl = src,
            referer = "https://qiwi.gg/",
            supportsRange = true
        )
    }

    private fun extractMediaFire(url: String): ExtractedDirectMedia? {
        val html = fetchText(url, "https://www.mediafire.com/") ?: return null
        if (html.contains("error.php?errno=", ignoreCase = true)) return null
        val doc = Jsoup.parse(html, url)
        val dlBtn = doc.selectFirst("#downloadButton, a[aria-label^=Download file]")
        val scrambled = dlBtn?.attr("data-scrambled-url")?.trim()?.takeIf { it.isNotEmpty() }
            ?: Regex("""data-scrambled-url\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1)
        val unscrambled = scrambled?.let { raw ->
            runCatching { String(Base64.getDecoder().decode(raw), Charsets.UTF_8).trim() }.getOrNull()
        }?.takeIf { it.startsWith("http") }

        val direct = unscrambled
            ?: dlBtn?.attr("href")?.takeIf { it.startsWith("http") }
            ?: Regex("""kNO\s*=\s*["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1)
            ?: Regex("""href=["'](https?://download\d*\.mediafire\.com/[^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1)
            ?: return null
        return ExtractedDirectMedia(
            hoster = "MediaFire",
            directUrl = direct,
            referer = url,
            supportsRange = true
        )
    }

    private fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    @Volatile private var cachedGoFileToken: Pair<Long, String>? = null

    private fun getOrCreateGoFileToken(): String? {
        val now = System.currentTimeMillis()
        cachedGoFileToken?.let { (exp, tok) -> if (exp > now && tok.isNotBlank()) return tok }
        val accReq = Request.Builder()
            .url("https://api.gofile.io/accounts")
            .post(FormBody.Builder().build())
            .build()
        val fresh = runCatching {
            clients.mediaAndApiClient.newCall(accReq).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                JSONObject(resp.body?.string().orEmpty()).optJSONObject("data")?.optString("token")
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        cachedGoFileToken = (now + 12L * 3600_000L) to fresh
        return fresh
    }

    private fun extractGoFile(url: String): ExtractedDirectMedia? {
        val contentId = gofileIdRegex.find(url)?.groupValues?.getOrNull(1) ?: return null
        val token = getOrCreateGoFileToken() ?: return null

        val block = (System.currentTimeMillis() / 1000L) / 14400L
        val ua = clients.webViewUserAgent
        val variants = listOf(
            Triple("en-US", "12af056dacea0b", "https://api.gofile.io/contents/$contentId?wt=4fd6sg89d7s6&cache=true"),
            Triple("", "9844d94d963d30", "https://api.gofile.io/contents/$contentId?contentId=$contentId")
        )

        var link: String? = null
        for ((lang, salt, endpoint) in variants) {
            val wtHash = sha256Hex("$ua::$lang::$token::$block::$salt")
            val listReq = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $token")
                .header("X-Website-Token", wtHash)
                .header("Referer", "https://gofile.io/")
                .apply { if (lang.isNotEmpty()) header("X-BL", lang) }
                .build()
            link = runCatching {
                clients.mediaAndApiClient.newCall(listReq).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val root = JSONObject(resp.body?.string().orEmpty())
                    if (root.optString("status") != "ok") return@use null
                    val children = root.optJSONObject("data")?.optJSONObject("children") ?: return@use null
                    val keys = children.keys()
                    var fallbackLink: String? = null
                    while (keys.hasNext()) {
                        val item = children.optJSONObject(keys.next()) ?: continue
                        val dl = item.optString("link").ifBlank { item.optString("directLink") }
                            .takeIf { it.startsWith("http") } ?: continue
                        val name = item.optString("name").lowercase()
                        if (name.endsWith(".mkv") || name.endsWith(".mp4")) return@use dl
                        if (fallbackLink == null) fallbackLink = dl
                    }
                    fallbackLink
                }
            }.getOrNull()
            if (link != null) break
        }
        if (link == null) return null

        return ExtractedDirectMedia(
            hoster = "GoFile",
            directUrl = link,
            referer = "https://gofile.io/",
            supportsRange = true,
            extraHeaders = mapOf("Cookie" to "accountToken=$token")
        )
    }

    private fun extractGoogleDrive(url: String): ExtractedDirectMedia? {
        val (id, resourceKey) = HosterUrlHelpers.parseGoogleDriveFileId(url) ?: return null
        val rkParam = resourceKey?.let { "&resourcekey=${URLEncoder.encode(it, "UTF-8")}" }.orEmpty()
        val quickUrl = "https://drive.google.com/uc?id=${URLEncoder.encode(id, "UTF-8")}$rkParam&authuser=0&export=download"

        val quickReq = Request.Builder()
            .url(quickUrl)
            .header("X-Drive-First-Party", "DriveViewer")
            .header("Accept-Language", "en-gb, en;q=0.9")
            .post(FormBody.Builder().build())
            .build()

        var downloadUrl: String? = null
        runCatching {
            clients.mediaAndApiClient.newCall(quickReq).execute().use { resp ->
                if (!resp.isSuccessful) return@use
                val body = resp.body?.string().orEmpty()
                val jsonStart = body.indexOf('{')
                if (jsonStart >= 0) {
                    val json = JSONObject(body.substring(jsonStart))
                    if (json.optString("scanResult").equals("ERROR", ignoreCase = true)) {
                        return null
                    }
                    downloadUrl = json.optString("downloadUrl").takeIf { it.startsWith("http") }
                }
            }
        }

        var currentUrl = downloadUrl ?: "https://drive.google.com/uc?id=${URLEncoder.encode(id, "UTF-8")}$rkParam&export=download"
        var cookieHeader = ""

        for (step in 0 until 2) {
            val req = Request.Builder()
                .url(currentUrl)
                .header("Accept-Language", "en-gb, en;q=0.9")
                .apply { if (cookieHeader.isNotEmpty()) header("Cookie", cookieHeader) }
                .build()

            val next = runCatching {
                clients.mediaAndApiClient.newCall(req).execute().use { resp ->
                    val newCookies = resp.headers("Set-Cookie")
                        .map { it.substringBefore(';').trim() }
                        .filter { it.isNotEmpty() }
                    if (newCookies.isNotEmpty()) {
                        cookieHeader = newCookies.joinToString("; ")
                    }
                    val ct = resp.header("Content-Type").orEmpty().lowercase()
                    val finalUrl = resp.request.url.toString()
                    if (!resp.isSuccessful) return@use "STOP"
                    if (!ct.contains("text/html")) {
                        return ExtractedDirectMedia(
                            hoster = "GoogleDrive",
                            directUrl = finalUrl,
                            referer = "https://drive.google.com/",
                            supportsRange = true,
                            extraHeaders = if (cookieHeader.isNotEmpty()) mapOf("Cookie" to cookieHeader) else emptyMap()
                        )
                    }
                    val html = resp.body?.string().orEmpty()
                    if (html.contains("Too many users have viewed or downloaded", ignoreCase = true) ||
                        html.contains("Quota", ignoreCase = true) && html.contains("Google Drive", ignoreCase = true)
                    ) {
                        return@use "STOP"
                    }
                    parseGoogleDriveConfirmUrl(html, finalUrl) ?: "STOP"
                }
            }.getOrNull() ?: break

            if (next == "STOP") break
            currentUrl = next
        }
        return null
    }

    private fun parseGoogleDriveConfirmUrl(html: String, pageUrl: String): String? {
        val doc = Jsoup.parse(html, pageUrl)
        val ucLink = doc.selectFirst("a#uc-download-link[href]")?.absUrl("href")
            ?.takeIf { it.startsWith("http") }
        if (ucLink != null) return ucLink

        val confirmAnchor = doc.select("a[href]").firstOrNull { a ->
            val href = a.absUrl("href")
            href.contains("export=") && href.contains("confirm=")
        }?.absUrl("href")?.takeIf { it.startsWith("http") }
        if (confirmAnchor != null) return confirmAnchor

        val form = doc.selectFirst("form#download-form, form#downloadForm") ?: return null
        val action = form.absUrl("action").ifBlank { pageUrl }.substringBefore('?')
        val query = form.select("input[name]").mapNotNull { inp ->
            val name = inp.attr("name").trim()
            if (name.isEmpty()) return@mapNotNull null
            val value = inp.attr("value")
            "${URLEncoder.encode(name, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }.joinToString("&")
        return if (query.isNotEmpty()) "$action?$query" else action
    }

    private fun extractKuramadrive(url: String): ExtractedDirectMedia? {
        val req = Request.Builder().url(url).build()
        clients.mediaAndApiClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val html = resp.body?.string().orEmpty()
            val doc = Jsoup.parse(html, url)
            val token = doc.selectFirst("meta[name=csrf-token]")?.attr("content")?.trim().orEmpty()
            val route = doc.selectFirst("input#routeCheckAvl")?.attr("value")?.trim().orEmpty()
            if (route.isEmpty()) return null
            val routeUrl = runCatching { URI(url).resolve(route).toString() }.getOrNull() ?: return null
            val cookieHeader = resp.headers("Set-Cookie")
                .map { it.substringBefore(';').trim() }
                .filter { it.isNotEmpty() }
                .joinToString("; ")

            val ajaxReq = Request.Builder()
                .url(routeUrl)
                .header("Referer", url)
                .header("X-Requested-With", "XMLHttpRequest")
                .apply {
                    if (token.isNotEmpty()) header("X-CSRF-TOKEN", token)
                    if (cookieHeader.isNotEmpty()) header("Cookie", cookieHeader)
                }
                .build()

            clients.mediaAndApiClient.newCall(ajaxReq).execute().use { ajaxResp ->
                if (!ajaxResp.isSuccessful) return null
                val dlUrl = runCatching { JSONObject(ajaxResp.body?.string().orEmpty()).optString("url") }
                    .getOrNull()?.takeIf { it.startsWith("http") } ?: return null
                return ExtractedDirectMedia(
                    hoster = "Kuramadrive",
                    directUrl = dlUrl,
                    referer = "https://kuramadrive.com/",
                    supportsRange = true
                )
            }
        }
    }

    private fun extractDood(url: String): ExtractedDirectMedia? {
        val req = Request.Builder().url(url).build()
        val (finalUrl, html) = runCatching {
            clients.mediaAndApiClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.request.url.toString() to resp.body?.string().orEmpty()
            }
        }.getOrNull() ?: return null

        val b2Href = Jsoup.parse(html, finalUrl).selectFirst(".download-content a[href]")?.absUrl("href")
            ?.takeIf { it.startsWith("http") } ?: return null
        val r2Html = fetchText(b2Href, finalUrl) ?: return null
        val dlUrl = Regex("""window\.open\(\s*'(https?://[^']+)'""")
            .find(r2Html)?.groupValues?.getOrNull(1) ?: return null
        return ExtractedDirectMedia(
            hoster = "DoodStream",
            directUrl = dlUrl,
            referer = finalUrl,
            supportsRange = true
        )
    }

    private fun extractOdstreamOrDesustream(url: String, referer: String?): ExtractedDirectMedia? {
        val origin = runCatching {
            val u = URI(url)
            "${u.scheme}://${u.host}/"
        }.getOrDefault(referer ?: "https://odstream.xyz/")
        val html = fetchText(url, referer ?: origin) ?: return null
        val src = JsUnpacker.unpack(html) ?: html
        val patterns = listOf(
            Regex("""["'](https?://[^"']+\.m3u8[^"']{0,300}?)["']""", RegexOption.IGNORE_CASE),
            Regex("""file\s*:\s*["'](https?://[^"']+\.(?:mp4|m3u8)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""<source[^>]+src=["'](https?://[^"']+\.(?:mp4|m3u8)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""["'](https?://[^"']{15,}\.mp4[^"']{0,200}?)["']""", RegexOption.IGNORE_CASE)
        )
        val videoUrl = patterns.firstNotNullOfOrNull { it.find(src)?.groupValues?.getOrNull(1) }
            ?.takeIf { it.startsWith("http") } ?: return null
        val isHls = videoUrl.contains(".m3u8", ignoreCase = true)
        return ExtractedDirectMedia(
            hoster = if (url.contains("desustream", ignoreCase = true)) "Desustream" else "Odstream",
            directUrl = videoUrl,
            referer = origin,
            supportsRange = !isHls,
            isHls = isHls
        )
    }

    private fun extractOndesu(url: String, referer: String?, depth: Int): ExtractedDirectMedia? {
        val html = fetchText(url, referer ?: "https://ondesu.cc/") ?: return null
        val doc = Jsoup.parse(html, url)
        val iframeSrc = doc.select("iframe[src]").firstNotNullOfOrNull { el ->
            el.absUrl("src").takeIf { it.contains("blogger.com", ignoreCase = true) || it.contains("googlevideo.com", ignoreCase = true) }
        } ?: return null
        return extractInternal(iframeSrc, url, depth + 1)
    }

    private fun decodeUnicodeEscapes(input: String): String {
        var out = input
        val rx = Regex("""\\u([0-9a-fA-F]{4})""")
        repeat(2) {
            out = rx.replace(out) { m -> m.groupValues[1].toInt(16).toChar().toString() }
        }
        return out
            .replace("\\/", "/")
            .replace("\\=", "=")
            .replace("\\&", "&")
            .replace("\\\"", "\"")
    }

    private fun extractBlogger(url: String, referer: String?): ExtractedDirectMedia? {
        val fixed = if (url.startsWith("//")) "https:$url" else url
        val html = fetchText(fixed, referer ?: "https://www.blogger.com/") ?: return null
        val decoded = decodeUnicodeEscapes(html)
        val playUrl = Regex(""""play_url"\s*:\s*"([^"]+)"""").find(decoded)?.groupValues?.getOrNull(1)
            ?: Regex(""""iurl"\s*:\s*"([^"]+)"""").find(decoded)?.groupValues?.getOrNull(1)
            ?: Regex("""https://[^\s"']+""")
                .findAll(decoded)
                .map { it.value.replace("\\", "") }
                .firstOrNull {
                    it.contains("googlevideo.com/videoplayback") ||
                        it.contains("blogger.googleusercontent.com")
                }
            ?: return null
        val directRef = if (playUrl.contains("googlevideo.com/", ignoreCase = true)) {
            "https://youtube.googleapis.com/"
        } else {
            fixed
        }
        return ExtractedDirectMedia(
            hoster = "Blogger",
            directUrl = playUrl,
            referer = directRef,
            supportsRange = !playUrl.contains(".m3u8", ignoreCase = true),
            isHls = playUrl.contains(".m3u8", ignoreCase = true)
        )
    }

    private fun extractStreamRuby(url: String, referer: String?): ExtractedDirectMedia? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        val origin = "https://${parsed.host}"
        val id = Regex("""embed-([a-zA-Z0-9]+)\.html""").find(url)?.groupValues?.getOrNull(1) ?: return null
        val postReq = Request.Builder()
            .url("$origin/dl")
            .header("Referer", referer ?: url)
            .post(
                FormBody.Builder()
                    .add("op", "embed")
                    .add("file_code", id)
                    .add("auto", "1")
                    .add("referer", "")
                    .build()
            )
            .build()
        val body = runCatching {
            clients.mediaAndApiClient.newCall(postReq).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()
            }
        }.getOrNull() ?: return null
        val unpacked = JsUnpacker.unpack(body) ?: body
        val m3u8 = Regex("""file\s*:\s*["']([^"']*m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(unpacked)?.groupValues?.getOrNull(1)?.takeIf { it.startsWith("http") } ?: return null
        return ExtractedDirectMedia(
            hoster = "StreamRuby",
            directUrl = m3u8,
            referer = "$origin/",
            supportsRange = false,
            isHls = true
        )
    }

    private fun extractVidhideOrFilemoon(url: String, referer: String?): ExtractedDirectMedia? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        val origin = "https://${parsed.host}/"
        val html = fetchText(url, referer ?: origin) ?: return null
        val src = JsUnpacker.unpack(html) ?: html
        val stream = Regex("""file\s*:\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(src)?.groupValues?.getOrNull(1)
            ?: Regex("""<source[^>]+src=["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE)
                .find(src)?.groupValues?.getOrNull(1)
            ?: return null
        val isHls = stream.contains(".m3u8", ignoreCase = true)
        return ExtractedDirectMedia(
            hoster = "VidHide",
            directUrl = stream,
            referer = origin,
            supportsRange = !isHls,
            isHls = isHls
        )
    }

    private fun extractMixdrop(url: String): ExtractedDirectMedia? {
        val embedUrl = url.replace("/f/", "/e/")
        val html = fetchText(embedUrl, "https://mixdrop.co/") ?: return null
        val src = JsUnpacker.unpack(html) ?: html
        val raw = Regex("""MDCore\.wurl\s*=\s*["']([^"']+)["']""")
            .find(src)?.groupValues?.getOrNull(1)?.trim() ?: return null
        val direct = if (raw.startsWith("//")) "https:$raw" else raw
        if (!direct.startsWith("http")) return null
        return ExtractedDirectMedia(
            hoster = "Mixdrop",
            directUrl = direct,
            referer = embedUrl,
            supportsRange = true
        )
    }

    /**
     * Ekstraktor generik berbasis tanda tangan embed player (tahan ganti domain):
     * Mendeteksi <video><source>, array JSON Plyr/JWPlayer, tautan media langsung, dan iframe bertingkat.
     */
    private fun extractGenericEmbedPlayer(url: String, referer: String?, depth: Int): ExtractedDirectMedia? {
        if (depth >= 2) return null
        val html = fetchText(url, referer ?: url) ?: return null
        val doc = Jsoup.parse(html, url)

        val sourceEls = doc.select("video source[src], video[src]")
        val domCandidates = sourceEls.mapNotNull { el ->
            val src = el.absUrl("src").trim().ifBlank { el.attr("src").trim() }
            if (!src.startsWith("http")) return@mapNotNull null
            val resScore = Regex("(\\d{3,4})").find(el.attr("size") + " " + el.attr("label") + " " + src)
                ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 720
            resScore to src
        }.sortedByDescending { it.first }
        domCandidates.firstOrNull()?.second?.let { best ->
            val isHls = best.contains(".m3u8", ignoreCase = true)
            return ExtractedDirectMedia(
                hoster = "EmbedHTML5",
                directUrl = best,
                referer = url,
                supportsRange = !isHls,
                isHls = isHls
            )
        }

        val plyrRegex = Regex("""["'](?:src|file)["']\s*:\s*["'](https?:\\?/\\?/[^"']+\.(?:mp4|m3u8|mkv)[^"']*)["']""", RegexOption.IGNORE_CASE)
        val plyrMatches = plyrRegex.findAll(html).map { m ->
            m.groupValues[1].replace("\\/", "/")
        }.distinct().toList()
        if (plyrMatches.isNotEmpty()) {
            val best = plyrMatches.firstOrNull { it.contains("1080") }
                ?: plyrMatches.firstOrNull { it.contains("720") }
                ?: plyrMatches.first()
            val isHls = best.contains(".m3u8", ignoreCase = true)
            return ExtractedDirectMedia(
                hoster = "EmbedPlyr",
                directUrl = best,
                referer = url,
                supportsRange = !isHls,
                isHls = isHls
            )
        }

        val directMediaRegex = Regex("""https?://[^\s"'<>]+(?:\.m3u8|\.mp4|\.mkv|googlevideo\.com/videoplayback)[^\s"'<>]*""", RegexOption.IGNORE_CASE)
        val rawDirect = directMediaRegex.find(html)?.value?.replace("\\/", "/")?.replace("&amp;", "&")
        if (!rawDirect.isNullOrBlank()) {
            val isHls = rawDirect.contains(".m3u8", ignoreCase = true)
            return ExtractedDirectMedia(
                hoster = "EmbedDirect",
                directUrl = rawDirect,
                referer = if (rawDirect.contains("googlevideo.com/")) "https://youtube.googleapis.com/" else url,
                supportsRange = !isHls,
                isHls = isHls
            )
        }

        val nestedIframe = doc.select("iframe[src]")
            .mapNotNull { it.absUrl("src").trim().takeIf { s -> s.startsWith("http") && s != url } }
            .firstOrNull { !HosterFilter.isBlacklisted("", it) }
        if (!nestedIframe.isNullOrBlank()) {
            return extractInternal(nestedIframe, url, depth + 1)
        }
        return null
    }

    private fun extractStreamtape(url: String): ExtractedDirectMedia? {
        val targetUrl = url.replace("/e/", "/v/")
        val html = fetchText(targetUrl, "https://streamtape.com/") ?: return null
        val doc = Jsoup.parse(html, targetUrl)
        val roboText = doc.selectFirst("#norobotlink")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val token = Regex("""getElementById\('ideoooolink'\)\.innerHTML.*token=([^'&\s]+)""")
            .find(html)?.groupValues?.getOrNull(1) ?: return null
        val base = if (roboText.startsWith("//")) "https:$roboText" else "https:/$roboText"
        val sep = if (base.contains("?")) "&" else "?"
        val direct = "${base.substringBefore("&token=")}${sep}token=$token&dl=1"
        return ExtractedDirectMedia(
            hoster = "Streamtape",
            directUrl = direct,
            referer = targetUrl,
            supportsRange = true
        )
    }

    private val verifiedUrlCache = ConcurrentHashMap<String, Pair<Long, Boolean>>()
    private val verifiedUrlTtlMs = 5L * 60L * 1000L
    private val fastProbeClient by lazy {
        clients.mediaAndApiClient.newBuilder()
            .connectTimeout(2_500, TimeUnit.MILLISECONDS)
            .readTimeout(3_000, TimeUnit.MILLISECONDS)
            .callTimeout(4_000, TimeUnit.MILLISECONDS)
            .build()
    }

    fun verifyPlayableMedia(media: ExtractedDirectMedia, bypassCache: Boolean = false): Boolean {
        val parsed = media.directUrl.toHttpUrlOrNull() ?: return false
        if (runCatching { PublicIpGuard.verifyPublicHost(clients.dns, parsed.host); true }.getOrDefault(false).not()) {
            return false
        }
        val now = System.currentTimeMillis()
        val cacheKey = if (media.extraHeaders.isEmpty()) media.directUrl else "${media.directUrl}#${media.extraHeaders.hashCode()}"
        if (!bypassCache) verifiedUrlCache[cacheKey]?.let { (exp, ok) ->
            if (exp > now) return ok
            verifiedUrlCache.remove(cacheKey)
        }
        val probeReq = Request.Builder()
            .url(media.directUrl)
            .header("Range", if (media.isHls) "bytes=0-511" else "bytes=0-15")
            .apply {
                if (!media.referer.isNullOrBlank()) header("Referer", media.referer)
                media.extraHeaders.forEach { (k, v) -> header(k, v) }
            }
            .build()
        val result = runCatching {
            fastProbeClient.newCall(probeReq).execute().use { resp ->
                if (resp.code != 200 && resp.code != 206) return@use false
                val ct = resp.header("Content-Type").orEmpty().lowercase()
                if (ct.contains("text/html") || ct.contains("application/json")) {
                    return@use false
                }
                val headBytes = runCatching {
                    val maxPeek = if (media.isHls) 512 else 16
                    val buf = ByteArray(maxPeek)
                    val stream = resp.body?.byteStream()
                    var total = 0
                    while (stream != null && total < maxPeek) {
                        val n = stream.read(buf, total, maxPeek - total)
                        if (n <= 0) break
                        total += n
                    }
                    if (total > 0) String(buf, 0, total, Charsets.UTF_8) else ""
                }.getOrDefault("")
                if (media.isHls) {
                    return@use headBytes.contains("#EXTM3U")
                }
                if (ct.contains("text/plain")) return@use false
                val trimmed = headBytes.trimStart().lowercase()
                if (trimmed.startsWith("<!doc") || trimmed.startsWith("<html") ||
                    trimmed.startsWith("<?xml") || trimmed.startsWith("{\"err")
                ) {
                    return@use false
                }
                true
            }
        }.getOrDefault(false)
        if (result) {
            if (verifiedUrlCache.size > 128) verifiedUrlCache.clear()
            verifiedUrlCache[cacheKey] = (now + verifiedUrlTtlMs) to true
        }
        return result
    }

    private fun fetchText(url: String, referer: String?): String? {
        val req = Request.Builder().url(url).apply {
            if (!referer.isNullOrBlank()) header("Referer", referer)
        }.build()
        return runCatching {
            clients.mediaAndApiClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()
            }
        }.getOrNull()
    }
}


object JsUnpacker {
    private val wordRegex = Regex("""\b([0-9A-Za-z]+)\b""")

    private fun readQuoted(src: String, startIdx: Int): Pair<String, Int>? {
        var i = startIdx
        val n = src.length
        while (i < n && src[i].isWhitespace()) i++
        if (i >= n || (src[i] != '\'' && src[i] != '"')) return null
        val quote = src[i++]
        val sb = StringBuilder()
        while (i < n) {
            val ch = src[i++]
            if (ch == '\\' && i < n) {
                val next = src[i++]
                when (next) {
                    quote -> sb.append(quote)
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    else -> {
                        sb.append('\\')
                        sb.append(next)
                    }
                }
            } else if (ch == quote) {
                return sb.toString() to i
            } else {
                sb.append(ch)
            }
        }
        return null
    }

    private fun skipCommaAndSpaces(src: String, startIdx: Int): Int {
        var i = startIdx
        val n = src.length
        while (i < n && (src[i].isWhitespace() || src[i] == ',')) i++
        return i
    }

    private fun readIntToken(src: String, startIdx: Int): Pair<Int, Int>? {
        var i = startIdx
        val n = src.length
        while (i < n && src[i].isWhitespace()) i++
        val start = i
        while (i < n && src[i].isDigit()) i++
        if (i == start) return null
        val v = src.substring(start, i).toIntOrNull() ?: return null
        return v to i
    }

    fun unpack(source: String): String? {
        val markerIdx = source.indexOf("eval(function(p,a,c,k,e")
        val searchFrom = if (markerIdx >= 0) markerIdx else 0
        val braceIdx = source.indexOf("}(", searchFrom)
        if (braceIdx < 0) return null

        val (payload, afterPayload) = readQuoted(source, braceIdx + 2) ?: return null
        val radixPos = skipCommaAndSpaces(source, afterPayload)
        val (radix, afterRadix) = readIntToken(source, radixPos) ?: return null
        if (radix < 2 || radix > 36) return null
        val countPos = skipCommaAndSpaces(source, afterRadix)
        val (_, afterCount) = readIntToken(source, countPos) ?: return null
        val keysPos = skipCommaAndSpaces(source, afterCount)
        val (keysRaw, _) = readQuoted(source, keysPos) ?: return null
        val keys = keysRaw.split("|")

        return wordRegex.replace(payload) { m ->
            val word = m.groupValues[1]
            val idx = word.toLongOrNull(radix)?.toInt()
            if (idx != null && idx in keys.indices && keys[idx].isNotEmpty()) keys[idx] else word
        }
    }

}
