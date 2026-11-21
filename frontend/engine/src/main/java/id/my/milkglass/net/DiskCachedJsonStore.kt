package id.my.milkglass.net

import android.content.Context
import android.content.SharedPreferences
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Helper cache JSON berbasis HTTP ETag (`If-None-Match` / `304 Not Modified`) + `SharedPreferences`.
 * Dipakai bersama oleh `SourceConfigStore` dan `RemoteRulesStore` untuk menghilangkan duplikasi
 * boilerplate cache disk, TTL, dan sinkronisasi ETag.
 */
class DiskCachedJsonStore(
    context: Context?,
    prefsName: String,
    private val ttlMs: Long,
    private val maxDiskAgeMs: Long,
    private val maxPayloadBytes: Int = 64 * 1024
) {
    private val prefs: SharedPreferences? = context?.applicationContext
        ?.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    @Volatile
    var lastSyncedAtMs: Long = 0L
        private set

    @Volatile
    private var lastEtag: String? = null

    fun loadCached(): String? {
        val p = prefs ?: return null
        val ts = p.getLong(KEY_TS, 0L)
        val raw = p.getString(KEY_JSON, null) ?: return null
        if (ts <= 0L || System.currentTimeMillis() - ts > maxDiskAgeMs) return null
        lastSyncedAtMs = ts
        lastEtag = p.getString(KEY_ETAG, null)
        return raw
    }

    fun refreshIfNeeded(
        client: OkHttpClient,
        url: String,
        force: Boolean = false,
        onBody: (String) -> Boolean
    ): Boolean {
        val now = System.currentTimeMillis()
        if (!force && lastSyncedAtMs > 0L && (now - lastSyncedAtMs) < ttlMs) {
            return false
        }
        val reqBuilder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
        lastEtag?.takeIf { it.isNotBlank() }?.let { reqBuilder.header("If-None-Match", it) }

        return runCatching {
            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.code == 304) {
                    lastSyncedAtMs = now
                    prefs?.edit()?.putLong(KEY_TS, now)?.apply()
                    return@use true
                }
                if (!resp.isSuccessful) return@use false
                val raw = resp.body?.string().orEmpty()
                if (raw.isBlank() || raw.length > maxPayloadBytes) return@use false
                if (!onBody(raw)) return@use false

                val newEtag = resp.header("ETag")?.trim()?.takeIf { it.isNotBlank() }
                if (newEtag != null) lastEtag = newEtag
                lastSyncedAtMs = now
                saveToDisk(raw, newEtag, now)
                true
            }
        }.getOrDefault(false)
    }

    private fun saveToDisk(rawJson: String, etag: String?, ts: Long) {
        val p = prefs ?: return
        runCatching {
            p.edit()
                .putLong(KEY_TS, ts)
                .putString(KEY_JSON, rawJson)
                .apply { if (etag != null) putString(KEY_ETAG, etag) }
                .apply()
        }
    }

    companion object {
        private const val KEY_TS = "ts"
        private const val KEY_JSON = "json"
        private const val KEY_ETAG = "etag"
    }
}
