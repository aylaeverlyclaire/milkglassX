package id.my.milkglass.download

import android.content.Context
import android.content.SharedPreferences
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import id.my.milkglass.EngineRuntime
import id.my.milkglass.net.CloudflareChallengeException
import id.my.milkglass.net.PublicIpGuard
import id.my.milkglass.source.CascadeResult
import id.my.milkglass.source.MediaContainer
import id.my.milkglass.source.MkvQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

enum class DownloadDrainOutcome {
    SUCCESS,
    RETRY_NETWORK,
    BLOCKED_CHALLENGE,
    FAILED_PERMANENT
}

data class MkvDownloadStatus(
    val isRunning: Boolean = false,
    val isSucceeded: Boolean = false,
    val isFailed: Boolean = false,
    val progressPercent: Int = 0,
    val outcome: String? = null
)

data class MkvDownloadRequest(
    val animeSlug: String,
    val animeTitle: String,
    val episodeNumber: Int,
    val preferredQuality: MkvQuality = MkvQuality.P1080,
    val allowBatchFallback: Boolean = true
)

object RetryBackoff {
    fun nextDelayMs(
        attempt: Int,
        baseMs: Long = 800L,
        maxMs: Long = 15_000L,
        jitterRatio: Double = 0.25,
        randomUnit: Double = Random.nextDouble()
    ): Long {
        val shift = attempt.coerceIn(0, 10)
        val exp = min(maxMs, baseMs shl shift)
        val jitterSpan = (exp * jitterRatio).toLong().coerceAtLeast(1L)
        val delta = ((randomUnit.coerceIn(0.0, 1.0) * 2.0 - 1.0) * jitterSpan).toLong()
        return (exp + delta).coerceIn(baseMs / 2, maxMs)
    }
}

object EndpointHealthScorer {
    private val scores = ConcurrentHashMap<String, Int>()
    private val sourceScores = ConcurrentHashMap<String, Int>()

    @Volatile
    private var prefs: SharedPreferences? = null

    fun attachContext(context: Context?) {
        if (context == null || prefs != null) return
        val p = context.applicationContext.getSharedPreferences("milkglass_endpoint_health", Context.MODE_PRIVATE)
        prefs = p
        runCatching {
            val hObj = JSONObject(p.getString("hosters", "{}") ?: "{}")
            hObj.keys().forEach { k ->
                scores.putIfAbsent(k, hObj.optInt(k, 0).coerceIn(-20, 20))
            }
            val sObj = JSONObject(p.getString("sources", "{}") ?: "{}")
            sObj.keys().forEach { k ->
                sourceScores.putIfAbsent(k, sObj.optInt(k, 0).coerceIn(-20, 20))
            }
        }
    }

    private fun persistAsync() {
        val p = prefs ?: return
        runCatching {
            val hObj = JSONObject()
            scores.forEach { (k, v) -> hObj.put(k, v) }
            val sObj = JSONObject()
            sourceScores.forEach { (k, v) -> sObj.put(k, v) }
            p.edit()
                .putString("hosters", hObj.toString())
                .putString("sources", sObj.toString())
                .apply()
        }
    }

    fun score(hoster: String): Int = scores[hoster.lowercase()] ?: 0

    fun recordSuccess(hoster: String) {
        val key = hoster.lowercase()
        scores.compute(key) { _, cur -> ((cur ?: 0) + 2).coerceAtMost(20) }
        persistAsync()
    }

    fun recordFailure(hoster: String) {
        val key = hoster.lowercase()
        scores.compute(key) { _, cur -> ((cur ?: 0) - 3).coerceAtLeast(-20) }
        persistAsync()
    }

    fun sourceScore(sourceId: String): Int = sourceScores[sourceId.lowercase()] ?: 0

    fun recordSourceSuccess(sourceId: String, latencyMs: Long = 0L) {
        val key = sourceId.lowercase()
        val bonus = if (latencyMs in 1L..1500L) 3 else 2
        sourceScores.compute(key) { _, cur -> ((cur ?: 0) + bonus).coerceAtMost(20) }
        persistAsync()
    }

    fun recordSourceFailure(sourceId: String) {
        val key = sourceId.lowercase()
        sourceScores.compute(key) { _, cur -> ((cur ?: 0) - 3).coerceAtLeast(-20) }
        persistAsync()
    }

    fun reset() {
        scores.clear()
        sourceScores.clear()
        prefs?.edit()?.clear()?.apply()
    }
}

object MkvDownloadConcurrency {
    const val MAX_CONCURRENT_DOWNLOADS = 2
    val semaphore = Semaphore(MAX_CONCURRENT_DOWNLOADS)
}

class MkvDownloadManager(
    private val context: Context
) {
    private val workManager: WorkManager? = runCatching { WorkManager.getInstance(context.applicationContext) }.getOrNull()

    fun enqueueEpisodeDownload(req: MkvDownloadRequest): String {
        val uniqueName = "mkv_dl_${req.animeSlug}_ep_${req.episodeNumber}_${req.preferredQuality.label}"
        val input = Data.Builder()
            .putString(MkvDownloadWorker.KEY_SLUG, req.animeSlug)
            .putString(MkvDownloadWorker.KEY_TITLE, req.animeTitle)
            .putInt(MkvDownloadWorker.KEY_EPISODE, req.episodeNumber)
            .putString(MkvDownloadWorker.KEY_QUALITY, req.preferredQuality.name)
            .putBoolean(MkvDownloadWorker.KEY_ALLOW_BATCH, req.allowBatchFallback)
            .build()

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<MkvDownloadWorker>()
            .setInputData(input)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("milkglass_mkv_download")
            .build()

        val wm = workManager ?: throw IllegalStateException("WorkManager belum terinisialisasi")
        wm.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, workRequest)
        return uniqueName
    }

    fun observeDownload(uniqueName: String): Flow<MkvDownloadStatus> {
        val wm = workManager ?: return kotlinx.coroutines.flow.flowOf(MkvDownloadStatus())
        return wm.getWorkInfosForUniqueWorkFlow(uniqueName).map { list ->
            val info = list.firstOrNull() ?: return@map MkvDownloadStatus()
            MkvDownloadStatus(
                isRunning = info.state == WorkInfo.State.ENQUEUED || info.state == WorkInfo.State.RUNNING,
                isSucceeded = info.state == WorkInfo.State.SUCCEEDED,
                isFailed = info.state == WorkInfo.State.FAILED || info.state == WorkInfo.State.CANCELLED,
                progressPercent = info.progress.getInt(MkvDownloadWorker.KEY_PROGRESS, 0),
                outcome = info.outputData.getString(MkvDownloadWorker.KEY_OUTCOME)
            )
        }
    }

    fun cancelDownload(uniqueName: String) {
        workManager?.cancelUniqueWork(uniqueName)
    }

    fun getDownloadedFile(animeSlug: String, episodeNumber: Int, quality: MkvQuality): File {
        val dir = File(context.getExternalFilesDir("downloads") ?: context.filesDir, animeSlug)
        val mkv = File(dir, "${animeSlug}_ep${episodeNumber}_${quality.label}.mkv")
        if (mkv.exists() && mkv.length() > 1024L) return mkv
        val mp4 = File(dir, "${animeSlug}_ep${episodeNumber}_${quality.label}.mp4")
        if (mp4.exists() && mp4.length() > 1024L) return mp4
        return mkv
    }

    fun findAnyDownloadedFile(
        animeSlug: String,
        episodeNumber: Int,
        preferredQuality: MkvQuality
    ): Pair<File, MkvQuality>? {
        val dir = File(context.getExternalFilesDir("downloads") ?: context.filesDir, animeSlug)
        if (!dir.exists()) return null
        val order = listOf(preferredQuality) + MkvQuality.ALL_DESC.filterNot { it == preferredQuality }
        for (q in order) {
            for (ext in listOf("mkv", "mp4")) {
                val f = File(dir, "${animeSlug}_ep${episodeNumber}_${q.label}.$ext")
                if (f.exists() && f.length() > 1024L) {
                    return f to q
                }
            }
        }
        return null
    }
}

class MkvDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_SLUG = "anime_slug"
        const val KEY_TITLE = "anime_title"
        const val KEY_EPISODE = "episode_number"
        const val KEY_QUALITY = "preferred_quality"
        const val KEY_ALLOW_BATCH = "allow_batch"
        const val KEY_OUTPUT_PATH = "output_path"
        const val KEY_OUTCOME = "drain_outcome"
        const val KEY_SOURCE = "resolved_source"
        const val KEY_HOSTER = "resolved_hoster"
        const val KEY_PROGRESS = "progress_percent"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val slug = inputData.getString(KEY_SLUG).orEmpty()
        val title = inputData.getString(KEY_TITLE).orEmpty()
        val episode = inputData.getInt(KEY_EPISODE, 1)
        val quality = runCatching {
            MkvQuality.valueOf(inputData.getString(KEY_QUALITY) ?: MkvQuality.P1080.name)
        }.getOrDefault(MkvQuality.P1080)
        val allowBatch = inputData.getBoolean(KEY_ALLOW_BATCH, true)

        if (slug.isBlank() || title.isBlank()) {
            return@withContext Result.failure(
                Data.Builder().putString(KEY_OUTCOME, DownloadDrainOutcome.FAILED_PERMANENT.name).build()
            )
        }

        MkvDownloadConcurrency.semaphore.withPermit {
            val runtime = EngineRuntime.get(applicationContext)
            val clients = runtime.clients
            val cascade = runtime.cascadeResolver

            val episodeSlug = "${slug}-ep-${episode}"
            val cachedCandidates = runtime.streamResolver.cachedCandidatesFor(episodeSlug, quality)
                .filterNot { it.isHls || it.container == MediaContainer.HLS }

            val candidates = if (cachedCandidates.isNotEmpty()) {
                cachedCandidates.sortedByDescending { EndpointHealthScorer.score(it.hoster) }
            } else {
                when (val res = cascade.resolve(title, episode, quality, allowBatch)) {
                    is CascadeResult.Success -> (listOf(res.selected) + res.alternatives)
                        .filterNot { it.isHls || it.container == MediaContainer.HLS }
                        .sortedByDescending { EndpointHealthScorer.score(it.hoster) }
                    is CascadeResult.Failure -> {
                        val outcome = if (res.diagnostics.cloudflareBlockedHosts.isNotEmpty()) {
                            DownloadDrainOutcome.BLOCKED_CHALLENGE
                        } else {
                            DownloadDrainOutcome.FAILED_PERMANENT
                        }
                        return@withPermit Result.failure(
                            Data.Builder()
                                .putString(KEY_OUTCOME, outcome.name)
                                .build()
                        )
                    }
                }
            }

            if (candidates.isEmpty()) {
                return@withPermit Result.failure(
                    Data.Builder().putString(KEY_OUTCOME, DownloadDrainOutcome.FAILED_PERMANENT.name).build()
                )
            }

            var lastRetryable = false
            for (resolved in candidates) {
                val host = resolved.directUrl.toHttpUrlOrNull()?.host ?: continue
                if (runCatching { PublicIpGuard.verifyPublicHost(clients.dns, host); true }.getOrDefault(false).not()) {
                    continue
                }

                val ext = when {
                    resolved.isBatchArchive -> "zip"
                    resolved.container == MediaContainer.MP4 || resolved.directUrl.contains(".mp4", ignoreCase = true) -> "mp4"
                    else -> "mkv"
                }
                val dir = File(applicationContext.getExternalFilesDir("downloads") ?: applicationContext.filesDir, slug)
                if (!dir.exists()) dir.mkdirs()
                val targetFile = File(dir, "${slug}_ep${episode}_${resolved.quality.label}.$ext")
                val partFile = File(dir, "${targetFile.name}.part")
                val existingBytes = if (partFile.exists()) partFile.length() else 0L

                try {
                    val reqBuilder = Request.Builder().url(resolved.directUrl)
                    if (!resolved.referer.isNullOrBlank()) {
                        reqBuilder.header("Referer", resolved.referer)
                    }
                    if (existingBytes > 0L && resolved.supportsRange) {
                        reqBuilder.header("Range", "bytes=$existingBytes-")
                    }

                    clients.mediaAndApiClient.newCall(reqBuilder.build()).execute().use { resp ->
                        if (resp.code == 416 && existingBytes > 0L) {
                            partFile.delete()
                            lastRetryable = true
                            return@use
                        }
                        if (resp.code == 403 && resp.header("cf-mitigated") == "challenge") {
                            return@withPermit Result.failure(
                                Data.Builder()
                                    .putString(KEY_OUTCOME, DownloadDrainOutcome.BLOCKED_CHALLENGE.name)
                                    .build()
                            )
                        }
                        if (!resp.isSuccessful && resp.code != 206) {
                            if (resp.code in 500..599 || resp.code == 429) lastRetryable = true
                            return@use
                        }

                        val append = resp.code == 206 && existingBytes > 0L
                        val body = resp.body ?: throw IOException("Response body kosong")
                        val rangeTotal = resp.header("Content-Range")
                            ?.substringAfter("/", "")
                            ?.toLongOrNull()
                        val contentLength = body.contentLength()
                        val totalBytes = rangeTotal ?: if (contentLength > 0) {
                            if (append) existingBytes + contentLength else contentLength
                        } else -1L

                        var downloaded = if (append) existingBytes else 0L
                        var lastPct = -1

                        body.byteStream().use { input ->
                            FileOutputStream(partFile, append).use { output ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    if (isStopped) throw IOException("Unduhan dihentikan oleh sistem/user")
                                    val read = input.read(buf)
                                    if (read == -1) break
                                    output.write(buf, 0, read)
                                    downloaded += read
                                    if (totalBytes > 0) {
                                        val pct = ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                                        if (pct != lastPct && (pct % 2 == 0 || pct == 100)) {
                                            lastPct = pct
                                            setProgressAsync(
                                                Data.Builder().putInt(KEY_PROGRESS, pct).build()
                                            )
                                        }
                                    }
                                }
                                output.flush()
                            }
                        }
                    }

                    if (partFile.exists() && partFile.length() > 1024L) {
                        if (targetFile.exists()) targetFile.delete()
                        if (!partFile.renameTo(targetFile)) {
                            throw IOException("Gagal memindahkan file unduhan final")
                        }
                        EndpointHealthScorer.recordSuccess(resolved.hoster)
                        EndpointHealthScorer.recordSourceSuccess(resolved.source.id)
                        return@withPermit Result.success(
                            Data.Builder()
                                .putString(KEY_OUTCOME, DownloadDrainOutcome.SUCCESS.name)
                                .putString(KEY_OUTPUT_PATH, targetFile.absolutePath)
                                .putString(KEY_SOURCE, resolved.source.id)
                                .putString(KEY_HOSTER, resolved.hoster)
                                .build()
                        )
                    }
                } catch (_: CloudflareChallengeException) {
                    return@withPermit Result.failure(
                        Data.Builder()
                            .putString(KEY_OUTCOME, DownloadDrainOutcome.BLOCKED_CHALLENGE.name)
                            .build()
                    )
                } catch (_: IOException) {
                    EndpointHealthScorer.recordFailure(resolved.hoster)
                    lastRetryable = true
                    delay(RetryBackoff.nextDelayMs(runAttemptCount))
                }
            }

            if (lastRetryable) {
                Result.retry()
            } else {
                Result.failure(
                    Data.Builder()
                        .putString(KEY_OUTCOME, DownloadDrainOutcome.FAILED_PERMANENT.name)
                        .build()
                )
            }
        }
    }
}
