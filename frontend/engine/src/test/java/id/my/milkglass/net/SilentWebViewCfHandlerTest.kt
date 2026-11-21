package id.my.milkglass.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SilentWebViewCfHandlerTest {

    @Test
    fun isChallengeHtml_detectsCloudflareChallengeAndBlockPages() {
        val jsChallenge = "<html><head><title>Just a moment...</title></head><body>Cloudflare checking your browser</body></html>"
        val turnstile = "<html><body><script src='https://challenges.cloudflare.com/turnstile/v0/api.js'></script></body></html>"
        val blocked = "<html><body><h1>Your request was blocked.</h1></body></html>"

        assertTrue(SilentWebViewCfHandler.isChallengeHtml(null))
        assertTrue(SilentWebViewCfHandler.isChallengeHtml(""))
        assertTrue(SilentWebViewCfHandler.isChallengeHtml(jsChallenge))
        assertTrue(SilentWebViewCfHandler.isChallengeHtml(turnstile))
        assertTrue(SilentWebViewCfHandler.isChallengeHtml(blocked))
    }

    @Test
    fun isChallengeHtml_allowsRealAnimeProviderHtml() {
        val realPage = """
            <!doctype html>
            <html>
            <head><title>One Piece Episode 1122 Sub Indo - Samehadaku</title></head>
            <body>
              <div class="player-embed">
                <iframe src="https://vidhide.example/e/abc123" allowfullscreen></iframe>
              </div>
            </body>
            </html>
        """.trimIndent()

        assertFalse(SilentWebViewCfHandler.isChallengeHtml(realPage))
    }

    @Test
    fun unescapeJsString_decodesEvaluateJavascriptPayload() {
        val raw = "\"\\u003Chtml>\\u003Cbody>Hello \\\"Milkglass\\\"\\u003C/body>\\u003C/html>\""
        assertEquals("<html><body>Hello \"Milkglass\"</body></html>", SilentWebViewCfHandler.unescapeJsString(raw))
        assertNull(SilentWebViewCfHandler.unescapeJsString("null"))
        assertNull(SilentWebViewCfHandler.unescapeJsString(""))
    }

    @Test
    fun solveClearanceSilently_returnsFalseWhenContextNull() {
        assertFalse(SilentWebViewCfHandler.solveClearanceSilently(null, "https://v2.samehadaku.how/", "Mozilla/5.0"))
        assertNull(SilentWebViewCfHandler.fetchHtmlSilently(null, "https://v2.samehadaku.how/", "Mozilla/5.0"))
    }
}
