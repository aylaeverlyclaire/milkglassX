package com.milkglass.app

import android.content.Context
import android.graphics.Color as AndroidColor
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import com.milkglass.app.core.app.AppController
import com.milkglass.app.core.data.AppData
import com.milkglass.app.core.design.Milk
import com.milkglass.app.core.design.MilkTheme
import com.milkglass.app.core.design.ThemeMode
import com.milkglass.app.core.poster.PosterBitmaps
import com.milkglass.app.core.poster.PosterStore
import com.milkglass.app.core.remote.AniListExtrasHttpSource
import com.milkglass.app.core.remote.AniSkipSource
import com.milkglass.app.core.remote.AniZipMetaSource
import com.milkglass.app.core.remote.CatalogRepository
import com.milkglass.app.core.remote.FileCatalogCache
import com.milkglass.app.core.remote.FileSnapshotStore
import com.milkglass.app.core.remote.HttpUrlTransport
import com.milkglass.app.core.storage.StateStore
import com.milkglass.app.core.app.CrashReporter
import com.milkglass.app.ui.shell.AppRoot
import id.my.milkglass.EngineRuntime
import java.io.File

class MainActivity : ComponentActivity() {
    private var controller: AppController? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    private class Loaded(val data: AppData, val posters: PosterStore, val controller: AppController)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReporter.install(applicationContext)
        com.milkglass.app.core.app.StallWatchdog.start(applicationContext)
        CrashReporter.pendingReport(this)?.let { CrashReporter.show(this, it) }

        val gridOverlay = BuildConfig.DEBUG && intent.getBooleanExtra("grid", false)

        val loaded = mutableStateOf<Loaded?>(null)
        val appCtx = applicationContext
        val density = resources.displayMetrics.density
        // AppData (parse JSON aset) dan EngineRuntime (OkHttp, keystore, cache) saling bebas: jalankan paralel.
        val dataTask = java.util.concurrent.FutureTask { AppData.load(appCtx) }
        Thread(dataTask, "milkglass-appdata").start()
        Thread {
            val runtime = EngineRuntime.get(appCtx)
            runtime.prewarmCloudflareEdge()
            val sharedHttp = runtime.httpTextClient
            val availabilityIndex = runtime.availabilityIndex

            val warmupThread = Thread {
                runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
                runCatching { runtime.sourceConfig.refreshIfNeeded() }
                val count = runCatching { availabilityIndex.refreshIfNeeded() }.getOrDefault(0)
                if (count > 0 || availabilityIndex.isReady) {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) controller?.notifyAvailabilityUpdated()
                    }
                }
            }.apply { start() }

            val data = dataTask.get()
            val posters = PosterStore(emptyMap())
            runCatching { PosterBitmaps.prewarm(posters, density) }

            val repo = CatalogRepository(
                HttpUrlTransport(httpClient = sharedHttp),
                FileCatalogCache(File(cacheDir, "catalog_home.json")),
                snapshots = FileSnapshotStore(File(filesDir, "catalog")),
            )
            val c = AppController(
                data, posters, StateStore(File(filesDir, StateStore.FILE_NAME)),
                catalogRepo = repo,
                initialCatalog = runCatching { repo.cachedSnapshot() }.getOrNull(),
                metaSource = AniZipMetaSource(httpClient = sharedHttp),
                skipSource = AniSkipSource(httpClient = sharedHttp),
                extrasSource = AniListExtrasHttpSource(httpClient = sharedHttp),
                onSignOut = { runtime.sessionStore.clearSession() },
                availabilityChecker = { anime ->
                    availabilityIndex.isAvailable(listOf(anime.romaji, anime.title))
                },
            )
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                controller = c
                watchConnectivity(c)
                runtime.onRequireReLogin = { runOnUiThread { if (!isFinishing && !isDestroyed) c.signOut() } }
                loaded.value = Loaded(data, posters, c)
                if (!warmupThread.isAlive && availabilityIndex.isReady) {
                    c.notifyAvailabilityUpdated()
                }
            }
        }.start()
        setContent {
            val l = loaded.value
            val mode = if (l != null) ThemeMode.fromKey(l.controller.persisted.settings.theme) else ThemeMode.System
            MilkTheme(mode) {
                val dark = Milk.isDark
                DisposableEffect(dark) {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT) { dark },
                        navigationBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT) { dark },
                    )
                    onDispose { }
                }
                if (l == null) {
                    androidx.compose.foundation.layout.Box(
                        androidx.compose.ui.Modifier.fillMaxSize().background(Milk.colors.surface),
                    )
                } else {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                        AppRoot(l.controller)
                        if (gridOverlay) com.milkglass.app.core.design.MilkGridOverlay()
                    }
                }
            }
        }
    }

    private fun watchConnectivity(controller: AppController) {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        fun isOnline(): Boolean = cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        controller.online = isOnline()
        val appCtx = applicationContext
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                EngineRuntime.get(appCtx).onNetworkRestored {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) controller.notifyAvailabilityUpdated()
                    }
                }
                runOnUiThread { controller.onConnectivityChanged(true) }
            }
            override fun onLost(network: Network) { runOnUiThread { controller.onConnectivityChanged(false) } }
        }
        netCallback = cb
        cm.registerDefaultNetworkCallback(cb)
    }

    override fun onDestroy() {
        netCallback?.let { cb ->
            runCatching { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb) }
        }
        netCallback = null
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        controller?.saveNow()
    }
}
