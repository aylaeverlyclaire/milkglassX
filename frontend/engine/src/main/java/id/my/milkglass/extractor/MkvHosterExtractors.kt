package id.my.milkglass.extractor

import id.my.milkglass.net.IsolatedHttpClients
import id.my.milkglass.net.PublicIpGuard
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URI

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
        "pixeldrain", "pdrain", "filedon", "odfiles", "krakenfiles", "kfiles", "gofile"
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

    fun unshorten(initialUrl: String, referer: String? = null, maxHops: Int = 10): String {
        var current = initialUrl.trim()
        val visited = mutableSetOf<String>()

        for (hop in 0 until maxHops) {
            if (!current.startsWith("http://") && !current.startsWith("https://")) break
            if (!visited.add(current)) break

            val parsed = current.toHttpUrlOrNull() ?: break
            PublicIpGuard.verifyPublicHost(clients.dns, parsed.host)

            val low = current.lowercase()
            if (isTerminalHoster(low)) return current

            val reqBuilder = Request.Builder().url(current)
            if (!referer.isNullOrBlank()) reqBuilder.header("Referer", referer)

            val client = if (low.contains("otakudesu") || low.contains("samehadaku") ||
                low.contains("oploverz") || low.contains("kusonime")
            ) {
                clients.sourceClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
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

    private fun isTerminalHoster(lowerUrl: String): Boolean {
        return lowerUrl.contains("pixeldrain.com") ||
            lowerUrl.contains("krakenfiles.com") ||
            lowerUrl.contains("filedon.co") ||
            lowerUrl.endsWith(".mkv") ||
            lowerUrl.contains(".mkv?")
    }
}

class MkvHosterExtractorRegistry(
    private val clients: IsolatedHttpClients
) {
    private val pixeldrainIdRegex = Regex(
        """pixeldrain\.com/(?:u|d|l|api/file|api/filesystem)/([a-zA-Z0-9_-]+)""",
        RegexOption.IGNORE_CASE
    )
    private val filedoSlugRegex = Regex(
        """filedon\.co/(?:view|embed|d|f)/([a-zA-Z0-9_-]+)""",
        RegexOption.IGNORE_CASE
    )
    private val krakenIdRegex = Regex(
        """krakenfiles\.com/(?:view|download|embed-video)/([a-zA-Z0-9]+)""",
        RegexOption.IGNORE_CASE
    )

    fun extract(resolvedUrl: String, referer: String? = null): ExtractedDirectMedia? {
        val low = resolvedUrl.lowercase()
        val parsed = resolvedUrl.toHttpUrlOrNull() ?: return null
        PublicIpGuard.verifyPublicHost(clients.dns, parsed.host)

        if (low.contains(".mkv") && !low.contains(".html")) {
            return ExtractedDirectMedia(
                hoster = "DirectMKV",
                directUrl = resolvedUrl,
                referer = referer,
                supportsRange = true
            )
        }

        return when {
            low.contains("pixeldrain.com") -> extractPixeldrain(resolvedUrl)
            low.contains("filedon.co") -> extractFiledon(resolvedUrl)
            low.contains("krakenfiles.com") -> extractKrakenfiles(resolvedUrl)
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
        val embedUrl = "https://filedon.co/embed/$slug"
        val html = fetchText(embedUrl, url) ?: return null
        val doc = Jsoup.parse(html, embedUrl)
        val rawDataPage = doc.selectFirst("[data-page]")?.attr("data-page") ?: return null
        val props = runCatching { JSONObject(rawDataPage).optJSONObject("props") }.getOrNull()
            ?: return null
        val directUrl = props.optString("url").takeIf { it.startsWith("http") }
            ?: props.optJSONObject("media")?.optString("hls_url")?.takeIf { it.startsWith("http") }
            ?: return null
        return ExtractedDirectMedia(
            hoster = "ODFiles",
            directUrl = directUrl,
            referer = embedUrl,
            supportsRange = !directUrl.contains(".m3u8", ignoreCase = true)
        )
    }

    private fun extractKrakenfiles(url: String): ExtractedDirectMedia? {
        val id = krakenIdRegex.find(url)?.groupValues?.getOrNull(1) ?: return null
        val pageUrl = if (url.contains("/view/")) url else "https://krakenfiles.com/view/$id/file.html"
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

            val postReq = Request.Builder()
                .url(postUrl)
                .header("Referer", pageUrl)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Hash", id)
                .apply { if (cookieHeader.isNotEmpty()) header("Cookie", cookieHeader) }
                .post(FormBody.Builder().add("token", token).build())
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
