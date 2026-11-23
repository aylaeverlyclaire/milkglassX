package id.my.milkglass.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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

    @Test
    fun cloudflareInterceptor_prioritizesOnDeviceSilentSolverBeforeRemote() {
        val calls = mutableListOf<String>()
        var attempts = 0
        val interceptor = CloudflareInterceptor(
            cookieBridge = WebViewCookieBridge(),
            remoteSolver = { _, _ ->
                calls.add("remote")
                RemoteClearance(cookies = mapOf("cf_clearance" to "remote-tok"), userAgent = "UA")
            },
            silentLocalSolver = { _, _ ->
                calls.add("on_device")
                true
            }
        )
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .addInterceptor { chain ->
                attempts++
                val req = chain.request()
                if (attempts == 1) {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(403)
                        .message("Forbidden")
                        .header("Server", "cloudflare")
                        .header("cf-mitigated", "challenge")
                        .body("<html><title>Just a moment...</title></html>".toResponseBody("text/html".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("<html><body>Anime OK</body></html>".toResponseBody("text/html".toMediaType()))
                        .build()
                }
            }
            .build()

        client.newCall(Request.Builder().url("https://otakudesu.blog/").build()).execute().use { resp ->
            assertEquals(200, resp.code)
        }
        assertEquals(listOf("on_device"), calls)
    }
}
