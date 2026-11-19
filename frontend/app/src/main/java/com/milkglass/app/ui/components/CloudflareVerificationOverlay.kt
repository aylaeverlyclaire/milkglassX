package com.milkglass.app.ui.components

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import id.my.milkglass.net.CloudflareChallengeCoordinator
import kotlinx.coroutines.delay

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CloudflareVerificationOverlay(
    host: String,
    targetUrl: String,
    onDismiss: () -> Unit
) {
    var verified by remember(host, targetUrl) { mutableStateOf(false) }

    LaunchedEffect(host, targetUrl) {
        val cookieManager = CookieManager.getInstance()
        fun extractClearance(raw: String): String? =
            raw.split(';').map { it.trim() }.firstOrNull { it.startsWith("cf_clearance=") }
                ?.substringAfter("=")?.takeIf { it.isNotBlank() }

        val initialClearance = extractClearance(cookieManager.getCookie(targetUrl).orEmpty())
        while (!verified) {
            delay(500L)
            val currentClearance = extractClearance(cookieManager.getCookie(targetUrl).orEmpty())
            if (currentClearance != null && currentClearance != initialClearance) {
                cookieManager.flush()
                verified = true
                CloudflareChallengeCoordinator.complete(host, true)
                onDismiss()
            }
        }
    }

    DisposableEffect(host) {
        onDispose {
            runCatching { CookieManager.getInstance().flush() }
            if (!verified) {
                CloudflareChallengeCoordinator.complete(host, false)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1015))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(
                text = "Verifikasi Cloudflare ($host)",
                style = TextStyle(color = Color.White, fontSize = 15.sp)
            )
            BasicText(
                text = "Tutup",
                style = TextStyle(color = Color(0xFF8FB8FF), fontSize = 14.sp),
                modifier = Modifier.clickable {
                    CloudflareChallengeCoordinator.complete(host, false)
                    onDismiss()
                }
            )
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val reqHost = request?.url?.host?.lowercase().orEmpty()
                            val rootHost = host.lowercase().removePrefix("www.")
                            return reqHost.isNotEmpty() && !reqHost.endsWith(rootHost)
                        }
                    }
                    loadUrl(targetUrl)
                }
            }
        )
    }
}
