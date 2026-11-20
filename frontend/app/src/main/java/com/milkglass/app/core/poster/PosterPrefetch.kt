package com.milkglass.app.core.poster

import android.content.Context
import android.net.ConnectivityManager
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Size
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import id.my.milkglass.player.PlaybackBandwidthGate
import id.my.milkglass.player.PlayerManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

private const val WARM_W = 264
private const val WARM_H = 352

suspend fun prefetchPosters(context: Context, urls: List<String>, parallelism: Int = 6) {
    if (urls.isEmpty()) return
    if (PlayerManager.isVideoBuffering.value) {
        withTimeoutOrNull(4_000L) { PlayerManager.isVideoBuffering.first { !it } }
    }
    val loader = context.imageLoader

    val gate = Semaphore(parallelism.coerceIn(1, 8))
    coroutineScope {
        urls.distinct().map { url ->
            async {
                gate.withPermit {
                    if (!PlaybackBandwidthGate.awaitClear(maxWaitMs = 8_000L)) return@withPermit
                    runCatching {
                        loader.execute(
                            ImageRequest.Builder(context)
                                .data(url)
                                .size(Size(WARM_W, WARM_H))
                                .diskCachePolicy(CachePolicy.ENABLED)
                                .networkCachePolicy(CachePolicy.ENABLED)
                                .build(),
                        )
                    }
                }
            }
        }.awaitAll()
    }
}

fun isMeteredNetwork(context: Context): Boolean = runCatching {
    (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
}.getOrDefault(true)
