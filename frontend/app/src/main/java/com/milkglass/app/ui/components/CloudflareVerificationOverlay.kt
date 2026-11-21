package com.milkglass.app.ui.components

import android.annotation.SuppressLint
import android.os.SystemClock
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import id.my.milkglass.net.CloudflareChallengeCoordinator
import id.my.milkglass.net.CloudflareChallengeDetector
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

private const val CF_POLL_INTERVAL_MS = 500L
private const val CF_POLL_TIMEOUT_MS = 90_000L

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CloudflareVerificationOverlay(
    host: String,
    targetUrl: String,
    onDismiss: () -> Unit
) {
    val completed = remember(host, targetUrl) { AtomicBoolean(false) }

    fun completeOnce(success: Boolean) {
        if (completed.compareAndSet(false, true)) {
            CloudflareChallengeCoordinator.complete(host, success)
        }
    }

    LaunchedEffect(host, targetUrl) {
        val cookieManager = CookieManager.getInstance()
        val initialClearance = CloudflareChallengeDetector.extractClearanceCookie(cookieManager.getCookie(targetUrl))
        val deadlineMs = SystemClock.elapsedRealtime() + CF_POLL_TIMEOUT_MS
        while (!completed.get() && SystemClock.elapsedRealtime() < deadlineMs) {
            delay(CF_POLL_INTERVAL_MS)
            val currentClearance = CloudflareChallengeDetector.extractClearanceCookie(cookieManager.getCookie(targetUrl))
            if (currentClearance != null && currentClearance != initialClearance) {
                cookieManager.flush()
                completeOnce(true)
                onDismiss()
                return@LaunchedEffect
            }
        }
        if (!completed.get()) {
            completeOnce(false)
            onDismiss()
        }
    }

    DisposableEffect(host, targetUrl) {
        onDispose {
            runCatching { CookieManager.getInstance().flush() }
            completeOnce(false)
        }
    }

    androidx.activity.compose.BackHandler {
        completeOnce(false)
        onDismiss()
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1015))
            .padding(top = 28.dp)
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
                    completeOnce(false)
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
                            val allowed = reqHost == rootHost || reqHost.endsWith(".$rootHost")
                            return reqHost.isNotEmpty() && !allowed
                        }
                    }
                    loadUrl(targetUrl)
                }
            },
            onRelease = { wv ->
                wv.stopLoading()
                wv.removeAllViews()
                wv.destroy()
            }
        )
    }
}
