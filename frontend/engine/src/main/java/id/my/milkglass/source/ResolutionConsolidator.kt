package id.my.milkglass.source

import id.my.milkglass.net.IsolatedHttpClients
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Fase 2 (Non-blocking): Konsolidasi resolusi dan kandidat MP4 lintas source di latar belakang.
 * Dipanggil setelah pemutaran pertama (first-playable-wins) sudah berjalan agar tidak menghambat TTFF.
 */
class ResolutionConsolidator(
    private val clients: IsolatedHttpClients,
    private val slugCache: SourceSlugCache? = null,
    private val sourceBaseUrls: Map<SourcePriority, String> = emptyMap()
) {
    private data class ConsolidatedEntry(
        val cachedAtMs: Long,
        val candidates: List<RawMkvCandidate>
    )

    private val cache = ConcurrentHashMap<String, ConsolidatedEntry>()

    // Hanya Samehadaku dan Otakudesu.
    private val providers: List<MkvSourceProvider> by lazy {
        listOf(
            OtakudesuSourceProvider(
                clients,
                sourceBaseUrls[SourcePriority.OTAKUDESU] ?: SourcePriority.OTAKUDESU.defaultBaseUrl,
                slugCache = slugCache
            ),
            SamehadakuSourceProvider(
                clients,
                sourceBaseUrls[SourcePriority.SAMEHADAKU] ?: SourcePriority.SAMEHADAKU.defaultBaseUrl,
                slugCache = slugCache
            )
        )
    }

    private fun key(animeTitle: String, episodeNumber: Int): String =
        "${animeTitle.lowercase(Locale.ROOT).trim()}#$episodeNumber"

    fun getConsolidatedCandidates(animeTitle: String, episodeNumber: Int): List<RawMkvCandidate> {
        val nowMs = System.currentTimeMillis()
        val entry = cache[key(animeTitle, episodeNumber)] ?: return emptyList()
        if (nowMs - entry.cachedAtMs > TTL_MS) return emptyList()
        return entry.candidates
    }

    fun consolidateInBackground(
        animeTitle: String,
        episodeNumber: Int,
        existingQualities: Set<MkvQuality>,
        excludeSourceId: String? = null,
        onMergedHints: (String, List<RawMkvCandidate>) -> Unit = { _, _ -> },
        onQualitiesUpdated: (List<MkvQuality>) -> Unit = {}
    ) {
        val cacheKey = key(animeTitle, episodeNumber)
        val existing = getConsolidatedCandidates(animeTitle, episodeNumber)
        val coveredQualities = (existingQualities + existing.filterNot { it.isBatchArchive }.map { it.quality }).toMutableSet()
        var hasMp4 = existing.any {
            !it.isBatchArchive && (it.container == MediaContainer.MP4 || it.rawUrl.contains(".mp4", ignoreCase = true))
        }
        if (coveredQualities.containsAll(MkvQuality.ALL_DESC) && hasMp4) return

        val alreadyChecked = existing.map { it.source.id }.toMutableSet().apply {
            if (!excludeSourceId.isNullOrBlank()) add(excludeSourceId)
        }
        val candidatesToCheck = providers.filter { provider ->
            provider.priority.id !in alreadyChecked && !SourceCircuitBreaker.isOpen(provider.priority)
        }.take(3)

        val mergedMap = LinkedHashMap<String, RawMkvCandidate>()
        for (c in existing) mergedMap.putIfAbsent(c.rawUrl, c)

        for (provider in candidatesToCheck) {
            if (Thread.currentThread().isInterrupted) return
            if (coveredQualities.containsAll(MkvQuality.ALL_DESC) && hasMp4) break
            val found = runCatching {
                provider.findEpisodeMkvCandidates(animeTitle, episodeNumber)
            }.getOrNull().orEmpty().filterNot { it.isBatchArchive }
            if (found.isEmpty()) continue
            SourceCircuitBreaker.recordSuccess(provider.priority)

            val newQualities = found.map { it.quality }.toSet() - coveredQualities
            val addsMp4 = !hasMp4 && found.any {
                it.container == MediaContainer.MP4 || it.rawUrl.contains(".mp4", ignoreCase = true)
            }
            if (newQualities.isNotEmpty() || addsMp4) {
                for (c in found) mergedMap.putIfAbsent(c.rawUrl, c)
                val mergedList = mergedMap.values.toList()
                if (cache.size > 64) cache.clear()
                cache[cacheKey] = ConsolidatedEntry(System.currentTimeMillis(), mergedList)

                coveredQualities.addAll(newQualities)
                if (addsMp4) hasMp4 = true
                val sortedQualities = coveredQualities.sortedByDescending { it.height }
                val balancedHints = MkvQuality.ALL_DESC.flatMap { q ->
                    mergedList.filter { it.quality == q }.take(3)
                }.take(12)
                if (balancedHints.isNotEmpty()) {
                    runCatching { onMergedHints(cacheKey, balancedHints) }
                }
                runCatching { onQualitiesUpdated(sortedQualities) }
            }
        }
    }

    companion object {
        private const val TTL_MS = 30L * 60L * 1000L
    }
}
