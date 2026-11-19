package id.my.milkglass.extractor

import id.my.milkglass.net.IsolatedHttpClients
import id.my.milkglass.net.PublicIpGuard
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

data class ExtractedDirectMedia(
    val hoster: String,
    val directUrl: String,
    val referer: String?,
    val supportsRange: Boolean,
    val isArchive: Boolean = false
)

object HosterFilter {
    private val blacklistedTokens = listOf(
        "mega.nz", "mega.co.nz", "megaup", "otakufiles", "zippyshare", "racaty"
    )

    private val preferredOrder = listOf(
        "pixeldrain", "pdrain", "filedon", "odfiles", "buzzheavier", "qiwi", "mediafire", "gofile", "krakenfiles", "kfiles"
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
}

class LinkUnshortener(
    private val clients: IsolatedHttpClients
) {
    private val metaRefreshRegex = Regex(
        """<meta[^>]+http-equiv=["']refresh["'][^>]+content=["'][^"']*url=([^"'\s>]+)["']""",
        RegexOption.IGNORE_CASE
    )
    private val jsRedirectRegex = Regex(
        """window\.location(?:\.href)?\s*=\s*["'](https?://[^"']+)["']""",
        RegexOption.IGNORE_CASE
    )

    private val sourceNoRedirectClient = clients.sourceClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun unshorten(initialUrl: String, referer: String? = null, maxHops: Int = 10): String {
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
                        if (low.contains("desustream") || low.contains("safelink") || low.contains("desudrive")) {
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
                String(Base64.getDecoder().decode(raw.replace('-', '+').replace('_', '/')), Charsets.UTF_8).trim()
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
            lowerUrl.endsWith(".mkv") ||
            lowerUrl.contains(".mkv?")
    }
}

class MkvHosterExtractorRegistry(
    private val clients: IsolatedHttpClients
) {
    private val verifiedUrlCache = ConcurrentHashMap<String, Long>()
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
        val low = resolvedUrl.lowercase()
        val parsed = resolvedUrl.toHttpUrlOrNull() ?: return null
        PublicIpGuard.verifyPublicHost(clients.dns, parsed.host)

        if ((low.contains(".mkv") || low.contains("wibufile.com") || low.contains("uqni.net")) && !low.contains(".html")) {
            return ExtractedDirectMedia(
                hoster = "DirectMKV",
                directUrl = resolvedUrl,
                referer = referer,
                supportsRange = true
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
            else -> null
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
                    if (name.endsWith(".mkv")) {
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
                supportsRange = !directUrl.contains(".m3u8", ignoreCase = true)
            )
        }
        val origHtml = fetchText(url, "https://filedon.co/") ?: return null
        val csrf = Jsoup.parse(origHtml, url).selectFirst("meta[name=csrf-token]")?.attr("content")?.trim()
        if (!csrf.isNullOrBlank()) {
            val postReq = Request.Builder()
                .url("https://filedon.co/get-url")
                .header("Referer", url)
                .post(FormBody.Builder().add("_token", csrf).add("slug", slug).build())
                .build()
            val apiUrl = runCatching {
                clients.mediaAndApiClient.newCall(postReq).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    JSONObject(resp.body?.string().orEmpty()).optJSONObject("data")?.optString("url")
                }
            }.getOrNull()?.takeIf { it.startsWith("http") }
            if (apiUrl != null) {
                return ExtractedDirectMedia(
                    hoster = "ODFiles",
                    directUrl = apiUrl,
                    referer = "https://filedon.co/",
                    supportsRange = !apiUrl.contains(".m3u8", ignoreCase = true)
                )
            }
        }
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
        val direct = doc.selectFirst("#downloadButton")?.attr("href")?.takeIf { it.startsWith("http") }
            ?: Regex("""href=["'](https?://download\d*\.mediafire\.com/[^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1)
            ?: doc.selectFirst("a[aria-label^=Download file]")?.attr("href")?.takeIf { it.startsWith("http") }
            ?: return null
        return ExtractedDirectMedia(
            hoster = "MediaFire",
            directUrl = direct,
            referer = url,
            supportsRange = true
        )
    }

    private fun extractGoFile(url: String): ExtractedDirectMedia? {
        val contentId = gofileIdRegex.find(url)?.groupValues?.getOrNull(1) ?: return null
        val accReq = Request.Builder()
            .url("https://api.gofile.io/accounts")
            .post(FormBody.Builder().build())
            .build()
        val token = runCatching {
            clients.mediaAndApiClient.newCall(accReq).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                JSONObject(resp.body?.string().orEmpty()).optJSONObject("data")?.optString("token")
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null

        var link: String? = null
        for (wt in listOf("4fd6sg89d7s6", "1234567890abcdef")) {
            val listReq = Request.Builder()
                .url("https://api.gofile.io/contents/$contentId?wt=$wt&cache=true")
                .header("Authorization", "Bearer $token")
                .header("Referer", "https://gofile.io/")
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
                        if (name.endsWith(".mkv")) return@use dl
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
            supportsRange = true
        )
    }

    fun verifyPlayableMedia(media: ExtractedDirectMedia): Boolean {
        val now = System.currentTimeMillis()
        verifiedUrlCache[media.directUrl]?.let { expiresAt ->
            if (expiresAt > now) return true
            verifiedUrlCache.remove(media.directUrl)
        }
        val parsed = media.directUrl.toHttpUrlOrNull() ?: return false
        if (runCatching { PublicIpGuard.verifyPublicHost(clients.dns, parsed.host); true }.getOrDefault(false).not()) {
            return false
        }
        val probeReq = Request.Builder()
            .url(media.directUrl)
            .header("Range", "bytes=0-1")
            .apply { if (!media.referer.isNullOrBlank()) header("Referer", media.referer) }
            .build()
        val ok = runCatching {
            clients.mediaAndApiClient
                .newCall(probeReq)
                .execute()
                .use { resp ->
                    if (resp.code != 200 && resp.code != 206) return@use false
                    val ct = resp.header("Content-Type").orEmpty().lowercase()
                    if (ct.contains("text/html") || ct.contains("application/json") || ct.contains("text/plain")) {
                        return@use false
                    }
                    true
                }
        }.getOrDefault(false)
        if (ok) {
            if (verifiedUrlCache.size > 128) verifiedUrlCache.clear()
            verifiedUrlCache[media.directUrl] = now + VERIFIED_URL_TTL_MS
        }
        return ok
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

    companion object {
        private const val VERIFIED_URL_TTL_MS = 5L * 60L * 1000L
    }
}

object JsUnpacker {
    private val packedRegex = Regex(
        """\u007D\s*\(\s*'((?:[^'\\]|\\.)*)',\s*(\d+),\s*(\d+),\s*'((?:[^'\\]|\\.)*)'\s*\.split"""
    )

    fun unpack(source: String): String? {
        val match = packedRegex.find(source) ?: return null
        val payload = match.groupValues[1].replace("\\'", "'")
        val radix = match.groupValues[2].toIntOrNull() ?: return null
        val keys = match.groupValues[4].split("|")
        return Regex("""\b(\w+)\b""").replace(payload) { m ->
            val word = m.groupValues[1]
            val idx = word.toLongOrNull(radix)?.toInt()
            if (idx != null && idx in keys.indices && keys[idx].isNotEmpty()) keys[idx] else word
        }
    }
}
