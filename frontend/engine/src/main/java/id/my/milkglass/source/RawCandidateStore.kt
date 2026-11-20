package id.my.milkglass.source

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Cache disk untuk kandidat mentah (tautan hoster dari halaman episode), supaya buka ulang aplikasi /
 * kembali ke episode lama tidak mengulang pencarian judul + scrape halaman (langkah paling lambat dan paling
 * rentan Cloudflare). Hanya tautan mentah yang disimpan; URL langsung hoster sengaja TIDAK dipersist karena
 * bertoken/kedaluwarsa. Kandidat basi tetap aman: ekstraksi gagal -> alur live berjalan seperti biasa.
 */
class RawCandidateStore(context: Context?) {
    private val file: File? = context?.applicationContext?.filesDir?.let { File(it, "raw_candidates_v1.json") }
    private val entries = ConcurrentHashMap<String, Entry>()
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "milkglass-rawstore").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    @Volatile private var loaded = false

    private class Entry(val savedAtMs: Long, val list: List<RawMkvCandidate>)

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching {
                val f = file
                if (f != null && f.exists() && f.length() in 2..MAX_FILE_BYTES) {
                    val root = JSONObject(f.readText())
                    root.keys().forEach { k ->
                        val o = root.optJSONObject(k) ?: return@forEach
                        val list = decodeList(o.optJSONArray("l"))
                        if (list.isNotEmpty()) entries[k] = Entry(o.optLong("t"), list)
                    }
                }
            }
            loaded = true
        }
    }

    fun has(key: String, maxAgeMs: Long): Boolean = get(key, maxAgeMs) != null

    fun get(key: String, maxAgeMs: Long, nowMs: Long = System.currentTimeMillis()): List<RawMkvCandidate>? {
        ensureLoaded()
        val e = entries[key] ?: return null
        if (nowMs - e.savedAtMs > maxAgeMs || nowMs < e.savedAtMs) {
            entries.remove(key, e)
            return null
        }
        return e.list
    }

    fun put(key: String, list: List<RawMkvCandidate>, nowMs: Long = System.currentTimeMillis()) {
        if (list.isEmpty() || file == null) return
        ensureLoaded()
        val prev = entries[key]
        // Hindari tulis disk berulang untuk data yang sama dalam 10 menit.
        if (prev != null && nowMs - prev.savedAtMs < 10L * 60_000L && prev.list == list) return
        entries[key] = Entry(nowMs, list)
        if (entries.size > MAX_ENTRIES) {
            entries.entries.sortedBy { it.value.savedAtMs }.take(entries.size - MAX_ENTRIES).forEach { entries.remove(it.key) }
        }
        persistAsync()
    }

    fun invalidate(key: String) {
        ensureLoaded()
        if (entries.remove(key) != null) {
            persistAsync()
        }
    }

    fun clear() {
        synchronized(this) {
            loaded = true
            entries.clear()
        }
        persistAsync()
    }

    private fun persistAsync() {
        val f = file ?: return
        writer.execute {
            runCatching {
                val root = JSONObject()
                entries.forEach { (k, e) ->
                    root.put(k, JSONObject().put("t", e.savedAtMs).put("l", encodeList(e.list)))
                }
                var payload = root.toString()
                if (payload.length > MAX_FILE_BYTES) {
                    val trimmed = JSONObject()
                    entries.entries.sortedByDescending { it.value.savedAtMs }.take(MAX_ENTRIES / 2).forEach { (k, e) ->
                        trimmed.put(k, JSONObject().put("t", e.savedAtMs).put("l", encodeList(e.list)))
                    }
                    payload = trimmed.toString()
                }
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(payload)
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            }
        }
    }

    companion object {
        private const val MAX_ENTRIES = 40
        private const val MAX_FILE_BYTES = 512L * 1024L

        internal fun encodeList(list: List<RawMkvCandidate>): JSONArray {
            val arr = JSONArray()
            list.forEach { c ->
                arr.put(
                    JSONObject()
                        .put("s", c.source.id).put("q", c.quality.label).put("h", c.hosterLabel)
                        .put("u", c.rawUrl).put("r", c.pageReferer).put("z", c.sizeLabel ?: JSONObject.NULL)
                        .put("b", c.isBatchArchive).put("c", c.container.name)
                )
            }
            return arr
        }

        internal fun decodeList(arr: JSONArray?): List<RawMkvCandidate> {
            if (arr == null) return emptyList()
            val out = ArrayList<RawMkvCandidate>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val src = SourcePriority.entries.firstOrNull { it.id == o.optString("s") } ?: continue
                val q = MkvQuality.fromText(o.optString("q")) ?: continue
                val url = o.optString("u").takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: continue
                val cont = runCatching { MediaContainer.valueOf(o.optString("c")) }.getOrDefault(MediaContainer.MKV)
                out.add(
                    RawMkvCandidate(
                        source = src, quality = q, hosterLabel = o.optString("h").ifBlank { "Hoster" },
                        rawUrl = url, pageReferer = o.optString("r"),
                        sizeLabel = if (o.isNull("z")) null else o.optString("z").ifBlank { null },
                        isBatchArchive = o.optBoolean("b", false), container = cont
                    )
                )
            }
            return out
        }
    }
}
