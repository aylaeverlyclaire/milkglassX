package id.my.milkglass.source

import android.content.Context
import id.my.milkglass.net.DiskCachedJsonStore
import id.my.milkglass.MilkglassEndpoints
import id.my.milkglass.net.IsolatedHttpClients
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class SourceRuleEntry(
    val baseUrl: String?,
    val backApiUrl: String? = null,
    val searchPath: String? = null,
    val selectors: Map<String, String> = emptyMap()
)

class RemoteRulesStore(
    context: Context?,
    private val clients: IsolatedHttpClients,
    private val rulesUrl: String = "${MilkglassEndpoints.DEFAULT_BACKEND_BASE_URL}/v1/catalog/rules"
) {
    private val diskCache = DiskCachedJsonStore(
        context = context,
        prefsName = "milkglass_remote_rules_v1",
        ttlMs = RULES_TTL_MS,
        maxDiskAgeMs = MAX_DISK_AGE_MS,
        maxPayloadBytes = MAX_MANIFEST_BYTES
    )

    private val rules = ConcurrentHashMap<SourcePriority, SourceRuleEntry>()

    init {
        diskCache.loadCached()?.let { raw ->
            val parsed = parseManifest(raw)
            if (parsed.isNotEmpty()) { rules.putAll(parsed); globalRules.putAll(parsed) }
        }
    }

    fun baseUrlFor(source: SourcePriority): String? = rules[source]?.baseUrl

    fun backApiUrlFor(source: SourcePriority): String? = rules[source]?.backApiUrl

    fun searchPathFor(source: SourcePriority, default: String): String =
        rules[source]?.searchPath ?: default

    fun selectorFor(source: SourcePriority, key: String, default: String): String =
        rules[source]?.selectors?.get(key) ?: default

    fun refreshIfNeeded(force: Boolean = false): Boolean =
        diskCache.refreshIfNeeded(clients.apiClient, rulesUrl, force) { raw ->
            val parsed = parseManifest(raw)
            if (parsed.isEmpty()) return@refreshIfNeeded false
            rules.clear()
            rules.putAll(parsed)
            globalRules.clear()
            globalRules.putAll(parsed)
            true
        }

    companion object {
        private val globalRules = ConcurrentHashMap<SourcePriority, SourceRuleEntry>()

        fun globalSelectorFor(source: SourcePriority, key: String, default: String): String =
            globalRules[source]?.selectors?.get(key)?.takeIf { it.isNotBlank() } ?: default

        private const val RULES_TTL_MS = 6L * 60L * 60L * 1000L
        private const val MAX_DISK_AGE_MS = 7L * 24L * 60L * 60L * 1000L
        private const val MAX_MANIFEST_BYTES = 32 * 1024

        private val EMPTY_DOC by lazy { Jsoup.parse("") }
        private val INT_OR_HEX_HOST = Regex("^(?:\\d+|0x[0-9a-f]+)$", RegexOption.IGNORE_CASE)
        private val DOTTED_NUMERIC_HOST = Regex("^[0-9a-fx]+(?:\\.[0-9a-fx]+){1,3}$", RegexOption.IGNORE_CASE)

        fun isSafeSourceBaseUrl(rawUrl: String): Boolean {
            val trimmed = rawUrl.trim().trimEnd('/')
            if (!trimmed.startsWith("https://") || trimmed.length > 256) return false
            val u = runCatching { URI(trimmed) }.getOrNull() ?: return false
            if (!u.userInfo.isNullOrBlank()) return false
            if (u.port != -1 && u.port != 443) return false
            if (!u.query.isNullOrBlank() || !u.fragment.isNullOrBlank()) return false
            val host = u.host?.trim()?.lowercase(Locale.ROOT) ?: return false
            if (host.isBlank() || host.endsWith('.') || !host.contains('.')) return false
            if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") ||
                host.endsWith(".internal") || host.endsWith(".home.arpa") ||
                host == "metadata.google.internal"
            ) return false
            if (host.contains(':') || host.startsWith('[') || host.endsWith(']')) return false
            if (INT_OR_HEX_HOST.matches(host) || DOTTED_NUMERIC_HOST.matches(host)) return false
            return true
        }

        fun isSafeSearchPath(path: String): Boolean {
            val p = path.trim()
            if (p.length !in 2..128 || !p.startsWith("/")) return false
            if (p.startsWith("//") || p.contains("@") || p.contains('\n') || p.contains('\r')) return false
            return p.contains("{q}")
        }

        private val SAFE_ACTION_TOKEN = Regex("^[a-zA-Z0-9_-]{1,64}$")

        fun isSafeRuleValue(key: String, value: String): Boolean {
            val v = value.trim()
            if (key.endsWith("_action")) return SAFE_ACTION_TOKEN.matches(v)
            return isSafeCssSelector(v)
        }

        fun isSafeCssSelector(sel: String): Boolean {
            val s = sel.trim()
            if (s.length !in 1..240) return false
            if (s.any { it.code < 32 }) return false
            return runCatching { EMPTY_DOC.select(s); true }.getOrDefault(false)
        }

        fun parseManifest(rawJson: String): Map<SourcePriority, SourceRuleEntry> {
            val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return emptyMap()
            if (root.optInt("schema_version", 0) != 1) return emptyMap()
            val sourcesObj = root.optJSONObject("sources") ?: return emptyMap()
            val out = linkedMapOf<SourcePriority, SourceRuleEntry>()

            for (src in SourcePriority.entries) {
                val item = sourcesObj.optJSONObject(src.id) ?: continue
                val baseUrl = item.optString("base_url").trim().trimEnd('/')
                    .takeIf { isSafeSourceBaseUrl(it) }
                val backApiUrl = item.optString("back_api_url").trim().trimEnd('/')
                    .takeIf { isSafeSourceBaseUrl(it) }
                val searchPath = item.optString("search_path").trim()
                    .takeIf { isSafeSearchPath(it) }

                val selMap = linkedMapOf<String, String>()
                val selObj = item.optJSONObject("selectors")
                if (selObj != null) {
                    val keys = selObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next().trim()
                        val v = selObj.optString(k).trim()
                        if (k.length in 1..40 && isSafeRuleValue(k, v)) {
                            selMap[k] = v
                        }
                    }
                }
                if (baseUrl != null || backApiUrl != null || searchPath != null || selMap.isNotEmpty()) {
                    out[src] = SourceRuleEntry(baseUrl, backApiUrl, searchPath, selMap)
                }
            }
            return out
        }
    }
}
