package id.my.milkglass.net

import okhttp3.Response

/**
 * Detektor tunggal untuk halaman tantangan, header mitigasi, dan cookie clearance Cloudflare.
 * Dipakai bersama oleh `CloudflareInterceptor`, `SilentWebViewCfHandler`, dan `CloudflareVerificationOverlay`
 * agar tidak ada duplikasi daftar marker di beberapa lapisan.
 */
object CloudflareChallengeDetector {

    fun extractClearanceCookie(rawCookie: String?): String? {
        if (rawCookie.isNullOrBlank()) return null
        return rawCookie.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("cf_clearance=") }
            ?.substringAfter("=")
            ?.takeIf { it.isNotBlank() }
    }

    fun hasClearanceCookie(rawCookie: String?): Boolean = extractClearanceCookie(rawCookie) != null

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

    fun isChallengeResponse(response: Response): Boolean {
        if (response.code != 403 && response.code != 503) return false
        val mitigated = response.header("cf-mitigated")?.trim()?.lowercase()
        if (mitigated == "challenge") return true
        val server = response.header("Server")?.lowercase().orEmpty()
        if (!server.contains("cloudflare")) return false
        val snippet = runCatching { response.peekBody(8192L).string() }.getOrDefault("")
        if (snippet.isBlank()) return false
        return isChallengeHtml(snippet)
    }
}
