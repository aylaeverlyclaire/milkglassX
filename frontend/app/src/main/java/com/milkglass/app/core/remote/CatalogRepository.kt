package com.milkglass.app.core.remote

import androidx.compose.runtime.Immutable

import id.my.milkglass.EngineHttpTextClient
import id.my.milkglass.MilkglassEndpoints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

const val DEFAULT_CATALOG_BASE_URL = MilkglassEndpoints.DEFAULT_BACKEND_BASE_URL

const val CATALOG_STALE_AFTER_SEC = 18L * 3600L

@Immutable
data class CatalogResponse(
    val body: String?,
    val etag: String? = null,
    val notModified: Boolean = false,
)

interface CatalogTransport {
    suspend fun get(pathAndQuery: String): String

    suspend fun getConditional(pathAndQuery: String, ifNoneMatch: String?): CatalogResponse =
        CatalogResponse(body = get(pathAndQuery), etag = null, notModified = false)
}

class CatalogHttpException(val code: Int) : Exception("HTTP $code")

class CatalogUnavailable(message: String) : Exception(message)

class HttpUrlTransport(
    private val baseUrl: String = DEFAULT_CATALOG_BASE_URL,
    private val timeoutMs: Int = 15_000,
    private val httpClient: EngineHttpTextClient? = null,
) : CatalogTransport {
    private val etagCache = ConcurrentHashMap<String, Pair<String, String>>()

    override suspend fun get(pathAndQuery: String): String = withContext(Dispatchers.IO) {
        val targetUrl = baseUrl.trimEnd('/') + pathAndQuery
        val cached = etagCache[pathAndQuery]
        if (httpClient != null) {
            val reqHeaders = if (cached != null) mapOf("If-None-Match" to cached.first) else emptyMap()
            val resp = httpClient.get(targetUrl, reqHeaders)
            if (resp.code == 304 && cached != null) return@withContext cached.second
            if (resp.code !in 200..299) throw CatalogHttpException(resp.code)
            resp.etag?.takeIf { it.isNotBlank() }?.let { tag ->
                if (etagCache.size >= 32) etagCache.clear()
                etagCache[pathAndQuery] = tag to resp.body
            }
            return@withContext resp.body
        }
        val conn = URL(targetUrl).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            if (cached != null) conn.setRequestProperty("If-None-Match", cached.first)
            val code = conn.responseCode
            if (code == 304 && cached != null) return@withContext cached.second
            if (code !in 200..299) throw CatalogHttpException(code)
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            conn.getHeaderField("ETag")?.takeIf { it.isNotBlank() }?.let { tag ->
                if (etagCache.size >= 32) etagCache.clear()
                etagCache[pathAndQuery] = tag to body
            }
            body
        } finally {
            conn.disconnect()
        }
    }
}

interface CatalogCache {
    fun read(): String?
    fun write(json: String)
}

class FileCatalogCache(private val file: File) : CatalogCache {
    override fun read(): String? = try {
        if (file.isFile) file.readText(Charsets.UTF_8) else null
    } catch (e: Exception) {
        null
    }

    override fun write(json: String) {
        synchronized(this) {
            try {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, file.name + ".tmp")
                FileOutputStream(tmp).use { fos ->
                    fos.write(json.toByteArray(Charsets.UTF_8))
                    fos.fd.sync()
                }
                try {
                    Files.move(
                        tmp.toPath(),
                        file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (e: AtomicMoveNotSupportedException) {
                    Files.move(
                        tmp.toPath(),
                        file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            } catch (e: Exception) {
            }
        }
    }
}

enum class CatalogSource { NETWORK, CACHE }

@Immutable
data class CatalogHome(
    val ongoing: List<CatalogEntry>,
    val popular: List<CatalogEntry>,
    val completed: List<CatalogEntry>,
    val extras: List<CatalogEntry>,
    val syncedAtSec: Long?,
    val source: CatalogSource,
    val stale: Boolean,
)

class CatalogRepository(
    private val transport: CatalogTransport,
    private val cache: CatalogCache,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val snapshots: SnapshotStore? = null,
) {
    private var stateMemo: SnapshotState? = null
    private var stateRead = false

    private fun state(store: SnapshotStore): SnapshotState? {
        if (!stateRead) {
            stateMemo = store.readState()?.takeIf { it.schema == SNAPSHOT_SCHEMA }
            stateRead = true
        }
        return stateMemo
    }

    private suspend fun stateIo(store: SnapshotStore): SnapshotState? {
        if (!stateRead) {
            stateMemo = store.readStateIo()?.takeIf { it.schema == SNAPSHOT_SCHEMA }
            stateRead = true
        }
        return stateMemo
    }

    private fun saveState(store: SnapshotStore, s: SnapshotState) {
        stateMemo = s
        stateRead = true
        store.writeState(s)
    }

    private suspend fun saveStateIo(store: SnapshotStore, s: SnapshotState) {
        stateMemo = s
        stateRead = true
        store.writeStateIo(s)
    }

    fun cachedSnapshot(): CachedSnapshot? {
        val store = snapshots ?: return null
        val st = state(store) ?: return null
        val resp = store.readBody()?.let { decodeSnapshot(it) } ?: return null
        return CachedSnapshot(RemoteMapper.toEntries(resp.data), st.complete)
    }

    suspend fun refreshSnapshot(force: Boolean = false): RefreshOutcome {
        val store = snapshots ?: return RefreshOutcome.Unsupported
        val now = nowMs()
        val st = stateIo(store)?.takeIf { store.readBodyIo() != null }

        if (!force && st != null && now >= st.fetchedAtMs && now < st.validUntilMs) return RefreshOutcome.Skipped

        val statusFetch = fetch("/v1/catalog/status")
        if (statusFetch.body == null) {
            return if (statusFetch.httpCode == 404 || statusFetch.httpCode == 405) RefreshOutcome.Unsupported else failed(store, st, now)
        }
        val status = try {
            RemoteJson.decodeFromString(StatusResponse.serializer(), statusFetch.body)
        } catch (e: Exception) {
            return failed(store, st, now)
        }
        if (status.status != "ok") return failed(store, st, now)
        val data = status.data ?: return RefreshOutcome.Unsupported
        val version = data.version ?: return RefreshOutcome.Unsupported
        val recheckMs = (data.recheckAfterSec ?: RECHECK_DEFAULT_SEC).coerceIn(RECHECK_MIN_SEC, RECHECK_MAX_SEC) * 1000L

        if (st != null && st.version == version) {
            saveStateIo(store, st.copy(fetchedAtMs = now, validUntilMs = now + recheckMs, failures = 0))
            return RefreshOutcome.Unchanged
        }

        val snapFetch = fetch("/v1/catalog/snapshot?v=" + enc(version))
        if (snapFetch.body == null) {
            return if (snapFetch.httpCode == 404 || snapFetch.httpCode == 405) RefreshOutcome.Unsupported else failed(store, st, now)
        }
        val resp = decodeSnapshot(snapFetch.body) ?: return failed(store, st, now)
        val complete = resp.meta.count in 1..resp.data.size

        store.writeBodyIo(snapFetch.body)
        saveStateIo(
            store,
            SnapshotState(
                version = resp.meta.version ?: version,
                fetchedAtMs = now,
                validUntilMs = now + recheckMs,
                complete = complete,
            ),
        )
        return RefreshOutcome.Updated(RemoteMapper.toEntries(resp.data), complete)
    }

    private suspend fun failed(store: SnapshotStore, st: SnapshotState?, now: Long): RefreshOutcome {
        if (st != null) {
            val n = st.failures + 1
            val backoff = minOf(BACKOFF_MAX_MS, BACKOFF_BASE_MS shl (n - 1).coerceAtMost(10))
            saveStateIo(store, st.copy(validUntilMs = now + backoff, failures = n))
        }
        return RefreshOutcome.Failed
    }

    private fun decodeSnapshot(json: String): SnapshotResponse? = try {
        RemoteJson.decodeFromString(SnapshotResponse.serializer(), json).takeIf { it.status == "ok" && it.data.isNotEmpty() }
    } catch (e: Exception) {
        null
    }

    private class Fetched(val body: String?, val httpCode: Int?)

    private suspend fun fetch(path: String): Fetched = try {
        Fetched(transport.get(path), null)
    } catch (e: CancellationException) {
        throw e
    } catch (e: CatalogHttpException) {
        Fetched(null, e.code)
    } catch (e: Exception) {
        Fetched(null, null)
    }

    suspend fun home(): Result<CatalogHome> {
        val net = fetchOrNull("/v1/catalog/home")
        val fromNet = net?.let { parseHome(it) }
        if (net != null && fromNet != null) {
            cache.write(net)
            return Result.success(build(fromNet, CatalogSource.NETWORK))
        }
        val fromCache = cache.read()?.let { parseHome(it) }
        return if (fromCache != null) {
            Result.success(build(fromCache, CatalogSource.CACHE))
        } else {
            Result.failure(CatalogUnavailable("Katalog tidak terjangkau dan belum ada cache."))
        }
    }

    suspend fun browse(q: String? = null, genre: String? = null, status: Int? = null, page: Int = 1): Result<List<CatalogEntry>> {
        val params = buildList {
            q?.trim()?.takeIf { it.isNotEmpty() }?.let { add("q=${enc(it)}") }
            genre?.trim()?.takeIf { it.isNotEmpty() }?.let { add("genre=${enc(it)}") }
            status?.let { add("status=$it") }
            add("page=$page")
        }
        val body = fetchOrNull("/v1/catalog/browse?" + params.joinToString("&"))
            ?: return Result.failure(CatalogUnavailable("Katalog tidak terjangkau."))
        return try {
            val resp = RemoteJson.decodeFromString(BrowseResponse.serializer(), body)
            if (resp.status != "ok") Result.failure(CatalogUnavailable(resp.message ?: "Respons katalog tidak valid."))
            else Result.success(RemoteMapper.toEntries(resp.data))
        } catch (e: Exception) {
            Result.failure(CatalogUnavailable("Respons katalog tidak valid."))
        }
    }

    suspend fun searchUniversal(q: String, ep: Int = 1): List<CatalogEntry> {
        val clean = q.trim()
        if (clean.length < 2) return emptyList()
        val body = fetchOrNull("/v1/catalog/search?q=${enc(clean)}&ep=$ep") ?: return emptyList()
        return try {
            val root = RemoteJson.parseToJsonElement(body) as? JsonObject ?: return emptyList()
            val out = ArrayList<CatalogEntry>()
            (root["data"] as? JsonArray)?.forEach { el ->
                runCatching {
                    val r = RemoteJson.decodeFromJsonElement(RemoteAnime.serializer(), el)
                    if (!RemoteMapper.isExcludedAnime(r)) {
                        out += RemoteMapper.toEntry(r)
                    }
                }
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun detail(id: Int): Result<CatalogEntry> {
        val body = fetchOrNull("/v1/catalog/anime/$id")
            ?: return Result.failure(CatalogUnavailable("Katalog tidak terjangkau."))
        return try {
            val resp = RemoteJson.decodeFromString(DetailResponse.serializer(), body)
            val data = resp.data
            if (resp.status != "ok" || data == null || RemoteMapper.isExcludedAnime(data)) {
                Result.failure(CatalogUnavailable(resp.message ?: "Judul tidak ditemukan."))
            } else {
                Result.success(RemoteMapper.toEntry(data))
            }
        } catch (e: Exception) {
            Result.failure(CatalogUnavailable("Respons katalog tidak valid."))
        }
    }

    private suspend fun fetchOrNull(path: String): String? = try {
        transport.get(path)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun parseHome(json: String): HomeResponse? = try {
        val resp = RemoteJson.decodeFromString(HomeResponse.serializer(), json)
        if (resp.status == "ok" && resp.data != null && resp.data.popular.isNotEmpty()) resp else null
    } catch (e: Exception) {
        null
    }

    private fun build(resp: HomeResponse, source: CatalogSource): CatalogHome {
        val data = checkNotNull(resp.data)
        val syncedAt = resp.meta.syncedAt
        val ageSec = syncedAt?.let { nowMs() / 1000 - it }
        return CatalogHome(
            ongoing = RemoteMapper.toEntries(data.ongoing),
            popular = RemoteMapper.toEntries(data.popular),
            completed = RemoteMapper.toEntries(data.tamat),
            extras = RemoteMapper.toEntries(data.extras),
            syncedAtSec = syncedAt,
            source = source,
            stale = ageSec == null || ageSec > CATALOG_STALE_AFTER_SEC,
        )
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
