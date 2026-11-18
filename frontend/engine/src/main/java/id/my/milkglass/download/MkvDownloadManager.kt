package id.my.milkglass.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import id.my.milkglass.net.CloudflareChallengeException
import id.my.milkglass.net.IsolatedHttpClients
import id.my.milkglass.net.PublicIpGuard
import id.my.milkglass.source.CascadeResult
import id.my.milkglass.source.MkvCascadeResolver
import id.my.milkglass.source.MkvQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class DownloadDrainOutcome {
    SUCCESS,
    RETRY_NETWORK,
    BLOCKED_CHALLENGE,
    FAILED_PERMANENT
}

data class MkvDownloadRequest(
    val animeSlug: String,
    val animeTitle: String,
    val episodeNumber: Int,
    val preferredQuality: MkvQuality = MkvQuality.P1080,
    val allowBatchFallback: Boolean = true
)

object MkvDownloadConcurrency {
    const val MAX_CONCURRENT_DOWNLOADS = 2
    val semaphore = Semaphore(MAX_CONCURRENT_DOWNLOADS)
}

class MkvDownloadManager(
    private val context: Context
) {
    private val workManager: WorkManager = WorkManager.getInstance(context)

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

        workManager.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, workRequest)
        return uniqueName
    }

    fun cancelDownload(uniqueName: String) {
        workManager.cancelUniqueWork(uniqueName)
    }

    fun getDownloadedFile(animeSlug: String, episodeNumber: Int, quality: MkvQuality): File {
        val dir = File(context.getExternalFilesDir("downloads") ?: context.filesDir, animeSlug)
        return File(dir, "${animeSlug}_ep${episodeNumber}_${quality.label}.mkv")
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
            val clients = IsolatedHttpClients(applicationContext)
            val cascade = MkvCascadeResolver(clients)

            val resolved = when (val res = cascade.resolve(title, episode, quality, allowBatch)) {
                is CascadeResult.Success -> res.selected
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

            val host = resolved.directUrl.toHttpUrlOrNull()?.host
                ?: return@withPermit Result.failure()
            runCatching { PublicIpGuard.verifyPublicHost(clients.dns, host) }
                .onFailure { return@withPermit Result.failure() }

            val ext = if (resolved.isBatchArchive) "zip" else "mkv"
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
                    if (resp.code == 403 && resp.header("cf-mitigated") == "challenge") {
                        return@withPermit Result.failure(
                            Data.Builder()
                                .putString(KEY_OUTCOME, DownloadDrainOutcome.BLOCKED_CHALLENGE.name)
                                .build()
                        )
                    }
                    if (!resp.isSuccessful && resp.code != 206) {
                        return@withPermit if (resp.code in 500..599 || resp.code == 429) {
                            Result.retry()
                        } else {
                            Result.failure(
                                Data.Builder()
                                    .putString(KEY_OUTCOME, DownloadDrainOutcome.FAILED_PERMANENT.name)
                                    .build()
                            )
                        }
                    }

                    val append = resp.code == 206 && existingBytes > 0L
                    val body = resp.body ?: throw IOException("Response body kosong")
                    val contentLength = body.contentLength()
                    val totalBytes = if (contentLength > 0) {
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

                if (targetFile.exists()) targetFile.delete()
                if (!partFile.renameTo(targetFile)) {
                    throw IOException("Gagal memindahkan file unduhan final")
                }

                Result.success(
                    Data.Builder()
                        .putString(KEY_OUTCOME, DownloadDrainOutcome.SUCCESS.name)
                        .putString(KEY_OUTPUT_PATH, targetFile.absolutePath)
                        .putString(KEY_SOURCE, resolved.source.id)
                        .putString(KEY_HOSTER, resolved.hoster)
                        .build()
                )
            } catch (_: CloudflareChallengeException) {
                Result.failure(
                    Data.Builder()
                        .putString(KEY_OUTCOME, DownloadDrainOutcome.BLOCKED_CHALLENGE.name)
                        .build()
                )
            } catch (_: IOException) {
                Result.retry()
            }
        }
    }
}
