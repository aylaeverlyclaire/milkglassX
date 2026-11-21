package com.milkglass.app.core.app

import androidx.compose.runtime.Stable

import androidx.compose.runtime.Immutable

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.milkglass.app.core.data.AppData
import com.milkglass.app.core.data.DAY_MS
import com.milkglass.app.core.data.SeedData
import com.milkglass.app.core.data.Translator
import com.milkglass.app.core.data.WEEK_MS
import com.milkglass.app.core.data.vibesOf
import com.milkglass.app.core.model.Anime
import com.milkglass.app.core.poster.PosterSpec
import com.milkglass.app.core.poster.PosterStore
import com.milkglass.app.core.remote.CachedSnapshot
import com.milkglass.app.core.remote.CatalogEntry
import com.milkglass.app.core.remote.CatalogRepository
import com.milkglass.app.core.remote.RefreshOutcome
import com.milkglass.app.core.storage.PersistedState
import com.milkglass.app.core.storage.StateStore
import com.milkglass.app.core.time.MilkTime
import java.text.Normalizer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Immutable
sealed interface ViewEntry {
    data object Search : ViewEntry
@Immutable
    data class Detail(val id: Int) : ViewEntry

    class Player(val engine: PlayerEngine, val rec: List<Int>) : ViewEntry
}

class ExitingView(val id: Int, val index: Int, val entry: ViewEntry)

@Immutable
data class ListSelection(val scope: String, val keys: List<String>)

@Immutable
sealed interface Overlay {
@Immutable
    data class LegalDocument(val key: String) : Overlay
    data object QualityPicker : Overlay
    data object ClearHistory : Overlay
    data object DeleteSelection : Overlay
@Immutable
    data class EditField(val key: String) : Overlay
@Immutable
    data class SettingOptions(val key: String) : Overlay
    data object DeleteAccount : Overlay
    data object SignOut : Overlay
}

@Immutable
data class ToastMsg(
    val id: Long, val text: String, val ms: Long,

    val actionLabel: String? = null, val onAction: (() -> Unit)? = null,
)

enum class Access { Ok, Early, Lock }

@Immutable
data class ContinueInfo(
    val episode: Int?,
    val position: Double = 0.0,
    val waiting: Boolean = false,
    val completed: Boolean = false,
)

@Immutable
data class ScheduleEntry(val a: Anime, val ts: Long, val ep: Int)

enum class NavKind { None, Push, Pop }

private val UNSAFE_CHARS = Regex("[<>\"&]")
private val COMBINING_MARKS = Regex("[\\u0300-\\u036f]")
private val CROSS_SIGNS = Regex("[\u00D7\u2715\u2716\u2717]")
private val NON_SEARCH_CHARS = Regex("[^a-z0-9\u3040-\u30FF\u4E00-\u9FFF]+")
private const val SIMILAR_MEMO_MAX = 512
private val HANDLE_RE = Regex("^[a-z0-9._]{3,20}$")
private const val UNDO_TOAST_MS = 6000L

val TAB_PAGES = listOf("profile", "home", "schedule", "social", "collection")
val GENRES = listOf(
    "action", "adventure", "fantasy", "comedy", "drama", "mystery", "sports", "mecha", "music", "supernatural", "slice-of-life",
    "romance", "sci-fi", "horror", "psychological", "thriller", "mahou-shoujo",
)

@Stable
class AppController(
    val data: AppData,
    val posters: PosterStore,
    private val store: StateStore?,
    bootMs: Long = System.currentTimeMillis(),
    private val clock: () -> Long = System::currentTimeMillis,
    zone: java.util.TimeZone = java.util.TimeZone.getDefault(),

    private val catalogRepo: CatalogRepository? = null,

    initialCatalog: CachedSnapshot? = null,

    private val metaSource: com.milkglass.app.core.remote.EpisodeMetaSource? = null,

    private val skipSource: com.milkglass.app.core.remote.SkipSource? = null,

    private val extrasSource: com.milkglass.app.core.remote.AniListExtrasSource? = null,
    private val availabilityChecker: ((Anime) -> Boolean)? = null,

    seedState: ((com.milkglass.app.core.data.Translator, Long) -> com.milkglass.app.core.storage.PersistedState)? = null,
    private val onSignOut: (() -> Unit)? = null,
) {
    val tr = Translator(data.locale)
    val time = MilkTime(tr, clock, zone)
    val features = data.features
    private val seedCatalog: List<Anime> = SeedData.withNextAirAt(data.catalogRaw, bootMs)
    private val seedIds: Set<Int> = seedCatalog.mapTo(HashSet()) { it.id }

    private var remoteEntries by mutableStateOf<Map<Int, CatalogEntry>>(
        initialCatalog?.entries?.associateBy { it.anime.id } ?: emptyMap(),
    )

    private var snapshotComplete = initialCatalog?.complete == true

    private val firstCatalogLoad = kotlinx.coroutines.CompletableDeferred<Unit>().also { if (initialCatalog != null || catalogRepo == null) it.complete(Unit) }
    private var lastRefreshFailed = false
    private val searchedQueries = HashSet<String>()
    private var animasuSearchResults by mutableStateOf<Map<String, List<Anime>>>(emptyMap())

    /**
     * Hasil cek ketersediaan per anime. Dihitung di thread latar (pencocokan judul mahal) dan
     * hanya DIBACA di sini; jangan pernah memanggil availabilityChecker dari komposisi/derivedStateOf.
     * Anime yang belum dihitung dianggap tersedia.
     */
    private var availability by mutableStateOf<Map<Int, Boolean>>(emptyMap())
    private val availabilityDirty = java.util.concurrent.atomic.AtomicBoolean(false)
    private val availabilityRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    private val saveExec by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "milkglass-save").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        }
    }
    private val availabilityExec by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "milkglass-availability").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        }
    }

    private fun rawCatalog(): List<Anime> =
        if (remoteEntries.isEmpty()) seedCatalog else seedCatalog.filter { it.id !in remoteEntries } + remoteEntries.values.map { it.anime }

    /** Hitung ulang ketersediaan di thread pemanggil. HANYA panggil dari thread latar. */
    fun warmAvailability() {
        val checker = availabilityChecker ?: return
        val list = rawCatalog()
        val fresh = HashMap<Int, Boolean>(list.size * 2)
        for (a in list) fresh[a.id] = runCatching { checker(a) }.getOrDefault(true)
        availability = fresh
    }

    private fun requestAvailability() {
        if (availabilityChecker == null) return
        availabilityDirty.set(true)
        if (!availabilityRunning.compareAndSet(false, true)) return
        availabilityExec.execute {
            try {
                while (availabilityDirty.getAndSet(false)) warmAvailability()
            } finally {
                availabilityRunning.set(false)
                if (availabilityDirty.get()) requestAvailability()
            }
        }
    }

    fun notifyAvailabilityUpdated() {
        requestAvailability()
    }

    val catalog: List<Anime> by derivedStateOf {
        val raw = rawCatalog()
        val avail = availability
        if (availabilityChecker == null || avail.isEmpty()) raw
        else raw.filter { avail[it.id] ?: true }.ifEmpty { raw }
    }
    private val catalogIndex: Map<Int, Anime> by derivedStateOf { catalog.associateBy { it.id } }

    fun animeOf(id: Int): Anime? = catalogIndex[id]

    fun posterSpec(a: Anime, wide: Boolean): PosterSpec =
        if (a.id in seedIds) posters.spec(a.id, wide) else posters.blank(wide, remoteEntries[a.id]?.posterUrl)

    var catalogLoading by mutableStateOf(false)
        private set

    var catalogFailed by mutableStateOf(false)
        private set

    private var episodeMeta by mutableStateOf<Map<Int, Map<Int, com.milkglass.app.core.remote.EpisodeMeta>>>(emptyMap())
    private val metaRequested = HashSet<Int>()

    fun episodeThumbOf(a: Anime, ep: Int): String? = episodeMeta[a.id]?.get(ep)?.image

    fun episodeTitleOf(a: Anime, ep: Int): String = episodeMeta[a.id]?.get(ep)?.title ?: time.episodeTitle(a, ep)

    fun episodeLineOf(a: Anime, ep: Int): String {
        val base = tr.t("player.episodeTitleLine", "n" to ep)
        val t = episodeMeta[a.id]?.get(ep)?.title
        return if (t != null) "$base · $t" else base
    }

    fun nextUpLineOf(a: Anime, ep: Int): String {
        val base = tr.t("player.nextUpLine", "n" to ep)
        val t = episodeMeta[a.id]?.get(ep)?.title
        return if (t != null) "$base · $t" else base
    }

    suspend fun loadEpisodeMeta(a: Anime) {
        val src = metaSource ?: return
        if (a.id in seedIds || !metaRequested.add(a.id)) return
        try {
            val m = src.load(a.id)
            if (m == null) metaRequested.remove(a.id)
            else if (m.isNotEmpty()) episodeMeta = episodeMeta + (a.id to m)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            metaRequested.remove(a.id)
            throw ce
        }
    }

    private var extras by mutableStateOf<Map<Int, com.milkglass.app.core.remote.AniListExtras>>(emptyMap())
    private val extrasRequested = HashSet<Int>()

    suspend fun loadExtras(a: Anime) {
        val src = extrasSource ?: return
        if (a.id in seedIds || !extrasRequested.add(a.id)) return
        try {
            val x = src.load(a.id)
            if (x == null) extrasRequested.remove(a.id)
            else extras = extras + (a.id to x)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            extrasRequested.remove(a.id)
            throw ce
        }
    }

    private val skipCache = HashMap<String, com.milkglass.app.core.remote.SkipTimes>()

    internal fun initialSkipTimes(a: Anime): com.milkglass.app.core.remote.SkipTimes? =
        if (skipSource != null && a.id !in seedIds) com.milkglass.app.core.remote.SkipTimes.NONE else null

    suspend fun loadSkipTimes(e: PlayerEngine) {
        val src = skipSource ?: return
        if (e.skipTimes == null) return
        val mal = remoteEntries[e.anime.id]?.malId ?: return
        val key = "$mal:${e.episode}"
        val t = skipCache[key] ?: src.load(mal, e.episode)?.also { skipCache[key] = it } ?: return
        e.skipTimes = t
    }

    private fun mergeRemote(entries: List<CatalogEntry>) {
        if (entries.isNotEmpty()) {
            remoteEntries = remoteEntries + entries.associateBy { it.anime.id }
            requestAvailability()
        }
    }

    suspend fun loadRemoteCatalog(force: Boolean = false) {
        val repo = catalogRepo ?: return
        if (catalogLoading || (legacyComplete && !force)) return

        catalogLoading = remoteEntries.isEmpty()
        catalogFailed = false
        try {
            when (val r = repo.refreshSnapshot(force)) {
                is RefreshOutcome.Updated -> {
                    mergeRemote(r.entries)
                    snapshotComplete = r.complete
                    lastRefreshFailed = false
                }
                RefreshOutcome.Skipped, RefreshOutcome.Unchanged -> lastRefreshFailed = false
                RefreshOutcome.Failed -> {
                    lastRefreshFailed = true
                    if (remoteEntries.isEmpty()) catalogFailed = true
                }
                RefreshOutcome.Unsupported -> loadPagedCatalog(repo)
            }
        } finally {
            catalogLoading = false
            firstCatalogLoad.complete(Unit)
        }
    }

    private suspend fun loadPagedCatalog(repo: CatalogRepository) {
        catalogLoading = true
        var page = 1
        while (page <= MAX_CATALOG_PAGES) {
            val items = repo.browse(page = page).getOrNull() ?: run { catalogFailed = true; return }
            mergeRemote(items)
            if (items.size < BROWSE_PAGE_SIZE) break
            page++
        }
        legacyComplete = true
    }
    private var legacyComplete = false

    suspend fun retryCatalogIfFailed() {
        if (lastRefreshFailed || catalogFailed) loadRemoteCatalog(force = true)
    }

    suspend fun searchRemote(q: String) {
        val repo = catalogRepo ?: return
        val query = q.trim()
        if (query.length < 2) return

        if (snapshotComplete) return
        val key = query.lowercase()
        if (key in searchedQueries) return
        repo.browse(q = query).getOrNull()?.let { mergeRemote(it); searchedQueries.add(key) }
    }

    suspend fun searchUniversalIfNeeded(q: String) {
        val repo = catalogRepo ?: return
        val query = q.trim()
        if (query.length < 2) return
        val normKey = normalizeText(query)
        val key = "animasu:$normKey"
        if (key in searchedQueries) return
        searchedQueries.add(key)
        val universal = repo.searchUniversal(query)
        if (universal.isNotEmpty()) {
            mergeRemote(universal)
            animasuSearchResults = animasuSearchResults + (normKey to universal.map { it.anime })
        }
    }

    fun firstScreenPosterUrls(limit: Int = 16): List<String> {
        val ids = continueWatchingList().take(4).map { it.id } +
            trendingList().take(limit).map { it.id } +
            catalog.filter { it.nextAirAt != null }.take(6).map { it.id }
        return ids.distinct().mapNotNull { remoteEntries[it]?.posterUrl }.take(limit)
    }

    fun secondaryPosterUrls(limit: Int = 40): List<String> {
        val first = firstScreenPosterUrls().toHashSet()
        val ids = persisted.watchlist.mapNotNull { animeOf(it)?.id } + trendingList().map { it.id }
        return ids.asSequence().mapNotNull { remoteEntries[it]?.posterUrl }.filter { it !in first }.distinct().take(limit).toList()
    }

    val users = data.users

    private val seedConversations = SeedData.conversations(data.seedConversationsRaw, bootMs)
    private val defaults = (seedState?.invoke(tr, bootMs) ?: com.milkglass.app.core.storage.PersistedState()).let {

        if (seedCatalog.isEmpty()) it.copy(
            progress = emptyMap(), lastWatched = emptyMap(), history = emptyList(), watchlist = emptyList(),
            recentSearches = emptyList(), friendIds = emptyList(), conversations = emptyList(),
            isAuthenticated = 1,
        ) else it
    }

    var persisted by mutableStateOf(store?.load(defaults, seedConversations) ?: defaults)
    @Volatile private var lastSaved: PersistedState? = null

    var isBooting by mutableStateOf(true)
    var isSigningIn by mutableStateOf(false)
    val viewStack = mutableStateListOf<ViewEntry>()

    val exitingViews = mutableStateListOf<ExitingView>()
    private var exitSeq = 0

    var popFromPlayer by mutableStateOf(false)
    fun finishExit(id: Int) { exitingViews.removeAll { it.id == id } }
    var navKind by mutableStateOf(NavKind.None)
    var navToken by mutableStateOf(0)
    var overlay by mutableStateOf<Overlay?>(null)
    var searchQuery by mutableStateOf("")

    var submittedQuery by mutableStateOf("")
    var searchGenre by mutableStateOf("all")
    var searchVibe by mutableStateOf("all")
    var searchFresh by mutableStateOf(0)
    var detailTab by mutableStateOf("episodes")
    var synopsisExpanded by mutableStateOf(false)
    var episodesDescending by mutableStateOf(false)
    var toast by mutableStateOf<ToastMsg?>(null)
    var tabBeforeProfile by mutableStateOf("home")
    var selection by mutableStateOf<ListSelection?>(null)

    var online by mutableStateOf(true)
    var editError by mutableStateOf<String?>(null)
    var deleteError by mutableStateOf<String?>(null)
    var fireworksToken by mutableStateOf(0)
    private var fireworksUntil = 0L

    var tick by mutableLongStateOf(bootMs)
    private var toastSeq = 0L

    fun now(): Long = clock()

    val nowMs: Long get() { tick; return now() }

    val meName: String get() = (persisted.displayName?.takeIf { it.isNotEmpty() } ?: tr.t("common.you")).replace(UNSAFE_CHARS, "")
    val meHandle: String get() = persisted.handle?.takeIf { it.isNotEmpty() } ?: "kamu"
    fun isPremium(): Boolean {
        if (!features.premium) return false
        val sub = persisted.subscription ?: return false
        if (sub.cancelled && now() >= (sub.renewsAt ?: 0L)) return false
        return true
    }

    fun enumLabel(group: String, code: String) = tr.t("$group.$code")
    fun genreSummary(a: Anime) = a.genres.take(2).joinToString(" · ") { enumLabel("genre", it) }

    fun progressOf(a: Anime, ep: Int): Double = persisted.progress[a.id.toString()]?.get(ep.toString()) ?: 0.0
    fun resumePosition(a: Anime, ep: Int): Double = persisted.resumePositions[a.id.toString()]?.get(ep.toString()) ?: progressOf(a, ep)
    fun durationSeconds(a: Anime) = time.durationSeconds(a)
    fun isWatched(a: Anime, ep: Int) = progressOf(a, ep) >= durationSeconds(a) * .9
    fun startedEpisodes(a: Anime): List<Int> =
        (persisted.progress[a.id.toString()]?.keys ?: emptySet()).mapNotNull { it.toIntOrNull() }.filter { progressOf(a, it) > 0 }

    fun isEarlyAccessWindow(a: Anime): Boolean = time.nextReleaseAt(a)?.let { it - now() <= DAY_MS } ?: false

    fun episodeAccess(a: Anime, ep: Int): Access {
        val released = time.releasedEpisodeCount(a)
        if (ep <= released) return Access.Ok
        return if (features.premium && ep == released + 1 && time.isOngoing(a) && isEarlyAccessWindow(a)) {
            if (isPremium()) Access.Ok else Access.Early
        } else Access.Lock
    }

    fun episodeAirTime(a: Anime, ep: Int): Long? =
        extras[a.id]?.airingAtMs?.get(ep)
            ?: time.nextReleaseAt(a)?.let { it + (ep - time.releasedEpisodeCount(a) - 1) * WEEK_MS }

    fun continueInfo(a: Anime): ContinueInfo? {
        val started = startedEpisodes(a)
        if (started.isEmpty()) return null
        val l = started.max()
        if (!isWatched(a, l)) return ContinueInfo(episode = l, position = resumePosition(a, l))
        val released = time.releasedEpisodeCount(a)
        val hi = released + if (episodeAccess(a, released + 1) == Access.Ok && released < a.totalEpisodes) 1 else 0
        if (l < hi) return ContinueInfo(episode = l + 1, position = 0.0)
        return ContinueInfo(episode = null, waiting = true, completed = l >= a.totalEpisodes)
    }

    fun lastWatchedAt(a: Anime): Long = persisted.lastWatched[a.id.toString()] ?: 0L

    fun isReturning(a: Anime): Boolean { val t = lastWatchedAt(a); return t > 0L && now() - t >= 3 * DAY_MS }

    fun continueWatchingList(): List<Anime> = catalog
        .filter { a -> continueInfo(a)?.let { !it.completed } == true }
        .sortedByDescending { lastWatchedAt(it) }

    fun isInWatchlist(a: Anime) = persisted.watchlist.contains(a.id)

    fun genreAffinity(): Map<String, Int> {
        val m = linkedMapOf<String, Int>()
        catalog.filter { startedEpisodes(it).isNotEmpty() }.forEach { a -> a.genres.forEach { g -> m[g] = (m[g] ?: 0) + 1 } }
        return m
    }

    private val trending: List<Anime> by derivedStateOf { catalog.sortedByDescending { it.voteCount } }

    fun trendingList(): List<Anime> = trending

    fun similarOf(a: Anime, limit: Int = 10): List<Anime> {

        val rec = extras[a.id]?.recommendedIds?.mapNotNull { animeOf(it) }?.filter { it.id != a.id }.orEmpty()
        if (rec.isNotEmpty()) {
            if (rec.size >= limit) return rec.take(limit)
            val seen = rec.mapTo(HashSet()) { it.id }
            return rec + similarOfByGenre(a, limit).filter { seen.add(it.id) }.take(limit - rec.size)
        }
        return similarOfByGenre(a, limit)
    }

    private fun similarOfByGenre(a: Anime, limit: Int): List<Anime> {
        val cat = catalog

        if (similarMemoCatalog !== cat || similarMemo.size > SIMILAR_MEMO_MAX) {
            similarMemo.clear()
            similarMemoCatalog = cat
        }
        return similarMemo.getOrPut(a.id to limit) { computeSimilar(a, limit, cat) }
    }

    private var similarMemoCatalog: List<Anime>? = null
    private val similarMemo = HashMap<Pair<Int, Int>, List<Anime>>()

    private fun computeSimilar(a: Anime, limit: Int, cat: List<Anime>): List<Anime> {
        val fixed = a.similarIds.mapNotNull { animeOf(it) }
        if (fixed.isNotEmpty() || a.genres.isEmpty()) return fixed
        val g = a.genres.toSet()
        return cat.asSequence().filter { it.id != a.id }
            .map { it to it.genres.count(g::contains) }.filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Anime, Int>> { it.second }.thenByDescending { it.first.voteCount })
            .take(limit).map { it.first }.toList()
    }

    fun searchSuggestions(): List<String> = trendingList().map { it.romaji }.filter { it.length <= 22 }.take(5)

    fun recommendedList(): List<Anime> {
        val gs = genreAffinity()
        return catalog.filter { startedEpisodes(it).isEmpty() }
            .map { it to it.genres.sumOf { g -> gs[g] ?: 0 } }
            .sortedWith(compareByDescending<Pair<Anime, Int>> { it.second }.thenByDescending { it.first.rating })
            .map { it.first }
    }

    fun continueLabel(a: Anime): String {
        val resume = continueInfo(a) ?: return ""
        if (resume.waiting) {
            val n = time.nextReleaseAt(a)
            return if (n != null) tr.t(
                "home.waitingEpisode",
                "value" to time.releasedEpisodeCount(a) + 1,
                "WEEKDAYS_LONG" to tr.locale.weekdaysLong[time.weekdayOf(n)],
            ) else tr.t("home.completed")
        }
        val left = max(1, ((durationSeconds(a) - resume.position) / 60).let { Math.round(it).toInt() })
        return if (resume.position != 0.0) tr.t("home.continueEpisodeRemaining", "episode" to resume.episode, "left" to left)
        else tr.t("home.startEpisode", "episode" to resume.episode)
    }

    fun hasAired(ts: Long) = ts <= now()

    fun countdownText(ts: Long): String =
        if (ts > now()) tr.t("home.airsIn", "formatCountdown" to time.formatCountdown(ts - now())) else tr.t("time.alreadyAired")

    fun scheduleForDay(k: Int): List<ScheduleEntry> {
        val ds = time.startOfDay(k)
        val de = time.startOfDay(k + 1)
        val out = mutableListOf<ScheduleEntry>()
        catalog.forEach { a ->
            val air = a.nextAirAt ?: return@forEach
            val n = ceil((ds - air).toDouble() / WEEK_MS).toLong()
            val ts = air + n * WEEK_MS
            if (ts < ds || ts >= de) return@forEach
            val ep = (a.releasedEpisodes + 1 + n).toInt()
            if (ep < 1 || ep > a.totalEpisodes) return@forEach
            out += ScheduleEntry(a, ts, ep)
        }
        return out.sortedBy { it.ts }
    }

    private fun normalizeText(s: String) =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(COMBINING_MARKS, "")
            .replace(CROSS_SIGNS, "x")
            .replace(NON_SEARCH_CHARS, " ")
            .trim()

    private class SearchDoc(val haystack: String, val title: String, val vibes: Set<String>)

    private val searchIndex: Map<Int, SearchDoc> by derivedStateOf {
        catalog.associate { a ->
            a.id to SearchDoc(
                haystack = normalizeText(
                    listOf(a.title, a.altTitle, a.genres.joinToString(" ") { enumLabel("genre", it) }, a.genres.joinToString(" "), a.studio, a.year.toString()).joinToString(" "),
                ),
                title = normalizeText(a.title),
                vibes = vibesOf(a.genres),
            )
        }
    }

    private class SearchKey(val catalog: List<Anime>, val q: String, val genre: String, val vibe: String)

    private var searchMemoKey: SearchKey? = null
    private var searchMemo: List<Anime> = emptyList()

    fun warmCaches() {
        searchIndex
        trending
    }

    private fun damerauDistance(a: String, b: String, maxDist: Int): Int {
        val m = a.length
        val n = b.length
        if (kotlin.math.abs(m - n) > maxDist) return maxDist + 1
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) {
            var rowMin = maxDist + 1
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    v = minOf(v, dp[i - 2][j - 2] + 1)
                }
                dp[i][j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > maxDist) return maxDist + 1
        }
        return dp[m][n]
    }

    private fun searchMatchScore(qSpaced: String, qCompact: String, doc: SearchDoc): Int {
        if (qCompact.isEmpty()) return 1
        val titleCompact = doc.title.replace(" ", "")
        val hayCompact = doc.haystack.replace(" ", "")
        if (titleCompact == qCompact) return 100
        if (doc.title.startsWith(qSpaced) || titleCompact.startsWith(qCompact)) return 85
        if (doc.title.contains(qSpaced) || titleCompact.contains(qCompact)) return 75
        if (doc.haystack.contains(qSpaced) || hayCompact.contains(qCompact)) return 65
        val qTokens = qSpaced.split(" ").filter { it.isNotEmpty() }
        val hTokens = doc.haystack.split(" ").filter { it.isNotEmpty() }
        if (qTokens.isEmpty() || hTokens.isEmpty()) return 0
        var matched = 0
        for (qt in qTokens) {
            val allow = when {
                qt.all { it.isDigit() } || qt.length <= 3 -> 0
                qt.length <= 6 -> 1
                else -> 2
            }
            val hit = hTokens.any { ht ->
                ht == qt || (qt.length >= 3 && ht.startsWith(qt)) ||
                    (allow > 0 && damerauDistance(qt, ht, allow) <= allow)
            }
            if (hit) matched++
        }
        return if (matched == qTokens.size) 50 else 0
    }

    fun searchCatalog(): List<Anime> {
        val cat = catalog
        val rawQ = submittedQuery.trim().take(80)
        val q = normalizeText(rawQ)
        val qCompact = q.replace(" ", "")
        val genre = searchGenre
        val vibe = searchVibe
        val directAnimasu = if (q.isNotEmpty()) animasuSearchResults[q] else null
        if (directAnimasu.isNullOrEmpty()) {
            searchMemoKey?.let { k ->
                if (k.catalog === cat && k.q == q && k.genre == genre && k.vibe == vibe) return searchMemo
            }
        }
        val idx = searchIndex
        val scored = cat.mapNotNull { a ->
            val d = idx[a.id] ?: return@mapNotNull null
            if (genre != "all" && !a.genres.contains(genre)) return@mapNotNull null
            if (vibe != "all" && !d.vibes.contains(vibe)) return@mapNotNull null
            val sc = searchMatchScore(q, qCompact, d)
            if (qCompact.isNotEmpty() && sc <= 0) return@mapNotNull null
            a to sc
        }
        val localSorted = scored.sortedWith { (a, sa), (b, sb) ->
            if (sa != sb) sb.compareTo(sa) else b.rating.compareTo(a.rating)
        }.map { it.first }
        if (!directAnimasu.isNullOrEmpty()) {
            val primary = directAnimasu.filter { a ->
                (genre == "all" || a.genres.isEmpty() || a.genres.contains(genre)) &&
                    (vibe == "all" || a.genres.isEmpty() || vibesOf(a.genres).contains(vibe))
            }
            val seenIds = primary.mapTo(HashSet()) { it.id }
            val seenTitles = primary.mapTo(HashSet()) { normalizeText(it.title) }
            val secondary = localSorted.filter { a ->
                a.id !in seenIds && normalizeText(a.title) !in seenTitles
            }
            return primary + secondary
        }
        searchMemo = localSorted
        searchMemoKey = SearchKey(cat, q, genre, vibe)
        return localSorted
    }

    fun showToast(text: String, ms: Long = 2600, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        toast = ToastMsg(++toastSeq, text, ms, actionLabel, onAction)
    }
    fun clearToast(id: Long) { if (toast?.id == id) toast = null }

    fun runToastAction(id: Long) {
        val t = toast ?: return
        if (t.id != id) return
        toast = null
        t.onAction?.invoke()
    }
    private fun undoToast(text: String, restore: () -> Unit) = showToast(text, UNDO_TOAST_MS, tr.t("common.undo"), restore)

    private fun restoreCollection(before: PersistedState) {
        persisted = persisted.copy(
            watchlist = before.watchlist, history = before.history, progress = before.progress,
            resumePositions = before.resumePositions, lastWatched = before.lastWatched,
        )
        selection = null
    }

    fun pushView(v: ViewEntry) {
        overlay = null

        if (v !is ViewEntry.Player && viewStack.lastOrNull() == v) return

        exitingViews.removeAll { it.index >= viewStack.size }
        viewStack.add(v); navKind = NavKind.Push; navToken++
    }
    fun popView() {
        if (viewStack.isNotEmpty()) {
            val top = viewStack.last()
            (top as? ViewEntry.Player)?.engine?.onLeave()
            popFromPlayer = top is ViewEntry.Player
            if (top !is ViewEntry.Player) exitingViews.add(ExitingView(++exitSeq, viewStack.lastIndex, top))
            viewStack.removeAt(viewStack.lastIndex); navKind = NavKind.Pop; navToken++
        }
    }
    fun goBack() {
        if (overlay != null) { overlay = null; return }
        val top = viewStack.lastOrNull()
        if (top is ViewEntry.Player && top.engine.isFullscreen) { top.engine.toggleFullscreen(); return }
        popView()
    }
    fun openSearch() { searchQuery = ""; submittedQuery = ""; searchGenre = "all"; searchVibe = "all"; searchFresh++; pushView(ViewEntry.Search) }
    fun openDetail(id: Int) {
        val top = viewStack.lastOrNull()
        if (top is ViewEntry.Detail && top.id == id) return
        detailTab = "episodes"; synopsisExpanded = false
        pushView(ViewEntry.Detail(id))
    }

    fun openAndPlay(id: Int) {
        val a = animeOf(id) ?: return
        val info = continueInfo(a)
        val ep = info?.episode ?: 1

        if ((info == null || info.episode != null) && episodeAccess(a, ep) == Access.Ok) {
            openPlayer(id, ep, null)
        } else {
            openDetail(id)
            if (info?.waiting != true) openEpisode(a, ep)
        }
    }

    fun searchByGenre(g: String) { searchQuery = ""; submittedQuery = ""; searchGenre = g; searchVibe = "all"; searchFresh++; pushView(ViewEntry.Search) }
    fun openLegalDocument(key: String) { if (data.locale.legalDocs.containsKey(key)) overlay = Overlay.LegalDocument(key) }
    fun closeOverlay() { overlay = null }

    fun toggleWatchlist(id: Int) {
        val list = persisted.watchlist
        if (!list.contains(id)) {
            persisted = persisted.copy(watchlist = list + id)
            undoToast(tr.t(if (features.reminders) "detail.watchlistAdded" else "detail.watchlistAddedPlain")) {
                persisted = persisted.copy(watchlist = persisted.watchlist - id)
            }
        } else {
            val at = list.indexOf(id)
            persisted = persisted.copy(watchlist = list - id)
            undoToast(tr.t("detail.watchlistRemoved")) {
                val cur = persisted.watchlist
                if (!cur.contains(id)) persisted = persisted.copy(watchlist = cur.toMutableList().also { it.add(at.coerceAtMost(it.size), id) })
            }
        }
    }

    fun submitSearch() {
        val q = searchQuery.trim()
        submittedQuery = q
        if (q.isNotEmpty()) {
            persisted = persisted.copy(
                recentSearches = (listOf(q) + persisted.recentSearches.filterNot { it.equals(q, ignoreCase = true) }).take(6),
            )
        }
    }

    fun runSearch(text: String) { searchQuery = text; submitSearch() }

    fun titleSuggestions(raw: String, limit: Int = 8): List<String> {
        val q = normalizeText(raw.trim())
        if (q.isEmpty()) return emptyList()
        val idx = searchIndex
        val starts = ArrayList<String>()
        val contains = ArrayList<String>()
        val seen = HashSet<String>()
        for (a in catalog) {
            val d = idx[a.id] ?: continue
            val label = a.romaji.ifBlank { a.title }
            if (label.isBlank() || !seen.add(label.lowercase())) continue
            val t = d.title
            val r = normalizeText(a.romaji)
            when {
                t.startsWith(q) || r.startsWith(q) -> starts.add(label)
                t.contains(q) || r.contains(q) -> contains.add(label)
            }
            if (starts.size >= limit) break
        }
        return (starts + contains).take(limit)
    }
    fun clearRecentSearches() { persisted = persisted.copy(recentSearches = emptyList()) }
    fun resetSearch() { searchQuery = ""; submittedQuery = ""; searchGenre = "all"; searchVibe = "all"; searchFresh++ }

    fun openEpisode(a: Anime, ep: Int) {
        when (episodeAccess(a, ep)) {
            Access.Ok -> openPlayer(a.id, ep, null)
            Access.Early -> {  }
            Access.Lock -> {
                val at = episodeAirTime(a, ep)
                showToast(
                    if (at != null) tr.t(
                        "detail.episodeAirsOnShort",
                        "e" to ep,
                        "WEEKDAYS_LONG" to tr.locale.weekdaysLong[time.weekdayOf(at)],
                        "formatClock" to time.formatClock(at),
                    ) else tr.t("detail.episodeNotAired"),
                )
            }
        }
    }

    val player: PlayerEngine? get() = (viewStack.lastOrNull() as? ViewEntry.Player)?.engine

    var onQualityChanged: ((String) -> Unit)? = null
    var availablePlayerQualities by mutableStateOf(listOf("Auto", "1080p", "720p", "480p"))

    fun nextPlayableEpisode(a: Anime, ep: Int): Int? =
        if (ep < a.totalEpisodes && episodeAccess(a, ep + 1) == Access.Ok) ep + 1 else null

    fun playEpisode(id: Int, ep: Int? = null) {
        val a = animeOf(id) ?: return
        openPlayer(id, ep ?: continueInfo(a)?.episode ?: 1, null)
    }

    var openPlayerHook: ((Int, Int, Double?) -> Unit)? = null
    val toastCount: Long get() = toastSeq

    fun openPlayer(id: Int, ep: Int, pos: Double?) {
        openPlayerHook?.let { it(id, ep, pos); return }
        val a = animeOf(id) ?: return
        val st = episodeAccess(a, ep)
        if (st == Access.Early) return
        if (st != Access.Ok) {
            val at = episodeAirTime(a, ep)
            showToast(
                if (at != null) tr.t(
                    "detail.episodeAirsOn",
                    "ep" to ep,
                    "WEEKDAYS_LONG" to tr.locale.weekdaysLong[time.weekdayOf(at)],
                    "formatClock" to time.formatClock(at),
                ) else tr.t("detail.episodeNotAired"),
            )
            return
        }
        (viewStack.lastOrNull() as? ViewEntry.Player)?.let { it.engine.onLeave(); viewStack.removeAt(viewStack.lastIndex) }
        val sp = resumePosition(a, ep)
        val start = pos ?: if (sp > 0 && !isWatched(a, ep)) smartResume(sp, lastWatchedAt(a), now()) else 0.0
        val rec = catalog.filter { it.id != a.id }.shuffled().take(10).map { it.id }
        pushView(ViewEntry.Player(PlayerEngine(this, a, ep, start), rec))
    }

    fun effectiveQuality(): String = persisted.settings.quality.let { if (it == "Auto") tr.t("player.automaticQuality") else it }

    fun setQuality(q: String) {
        persisted = persisted.copy(settings = persisted.settings.copy(quality = q))
        player?.bufferTimer = 1.2
        overlay = null
        onQualityChanged?.invoke(q)
    }

    fun onConnectivityChanged(isOnline: Boolean) {
        if (online == isOnline) return
        online = isOnline
        val p = player ?: return
        if (!isOnline) { p.controlsTimer = 3.0; showToast(tr.t("player.connectionLost")) }
        else showToast(tr.t("player.reconnected"))
        p.updateUi()
    }

    private fun openOverlay(o: Overlay) { editError = null; deleteError = null; overlay = o }
    fun openEditField(k: String) = openOverlay(Overlay.EditField(k))
    fun openSettingOptions(k: String) = openOverlay(Overlay.SettingOptions(k))
    fun confirmDeleteAccount() = openOverlay(Overlay.DeleteAccount)
    fun confirmSignOut() = openOverlay(Overlay.SignOut)

    fun saveEditField(k: String, raw: String): Boolean {
        val v = raw.trim()
        fun err(key: String): Boolean { editError = tr.t(key); return false }
        if (k == "nm") {
            if (v.isEmpty()) return err("profile.nameEmpty")
            if (UNSAFE_CHARS.containsMatchIn(v)) return err("profile.invalidChars")
            if (users.any { (id, u) -> id != "me" && u.name.lowercase() == v.lowercase() }) return err("profile.nameTaken")
            persisted = persisted.copy(displayName = v)
        } else if (k == "hd") {
            val h = v.removePrefix("@").lowercase()
            if (!HANDLE_RE.matches(h)) return err("profile.usernameError")
            persisted = persisted.copy(handle = h)
        }
        overlay = null
        saveNow()
        return true
    }

    fun chooseSettingOption(k: String, v: String) {
        val st = persisted.settings
        persisted = persisted.copy(
            settings = when (k) {
                "theme" -> st.copy(theme = v)
                else -> st
            },
        )
        overlay = null
    }

    fun deleteAccount(input: String) {
        if (input.trim() != tr.t("settings.deleteConfirmWord")) { deleteError = tr.t("settings.typeDeleteCaps"); return }
        overlay = null
        persisted = persisted.copy(deletionScheduledAt = now() + 30 * DAY_MS)
        signOut()
        showToast(tr.t("settings.deletionScheduled"), 3600)
    }

    fun signOut() {
        (viewStack.lastOrNull() as? ViewEntry.Player)?.engine?.onLeave()
        overlay = null
        persisted = persisted.copy(isAuthenticated = 0, accessToken = null)
        runCatching { onSignOut?.invoke() }
        viewStack.clear()
        exitingViews.clear()
        navKind = NavKind.None
        isSigningIn = false
        selection = null
        saveNow()
    }

    fun launchFireworks() {
        if (now() < fireworksUntil) return
        fireworksUntil = now() + 3400
        fireworksToken++
    }

    fun isCompleted(a: Anime): Boolean = continueInfo(a)?.completed == true

    fun selectCollectionTab(t: String) {
        selection = null
        persisted = persisted.copy(collectionTab = t)
    }

    private fun selectionAllKeys(sc: String): List<String> = when (sc) {
        "watchlist" -> persisted.watchlist.map { "list|$it" }
        "completed" -> catalog.filter { isCompleted(it) }.map { "done|${it.id}" }
        "watching" -> continueWatchingList().map { "watching|${it.id}" }
        else -> persisted.history.map { "hist|${it.id}.${it.episode}" }
    }

    fun validateSelection(activeTab: String) {
        val sel = selection ?: return
        val ok = when (sel.scope) {
            "watchlist", "watching", "completed", "history" -> viewStack.isEmpty() && activeTab == "collection" && persisted.collectionTab == sel.scope
            else -> false
        }
        if (!ok) selection = null
    }

    fun toggleSelection(k: String, add: Boolean, activeTab: String) {
        val sc = k.substringBefore('|')
        var sel = selection
        if (sel != null && sel.scope != sc) sel = null
        if (sel == null) sel = ListSelection(sc, emptyList())
        val i = sel.keys.indexOf(k)
        var keys = sel.keys
        if (i < 0) keys = keys + k else if (!add) keys = keys.filterIndexed { j, _ -> j != i }
        selection = if (keys.isEmpty()) null else ListSelection(sc, keys)
        validateSelection(activeTab)
    }

    fun toggleSelectAll(sc: String) {
        val all = selectionAllKeys(sc)
        val sel = selection

        selection = if (all.isEmpty() || (sel != null && all.all { sel.keys.contains(it) })) null else ListSelection(sc, all)
    }

    fun cancelSelection() { selection = null }

    fun confirmDeleteSelection() {
        val sel = selection ?: return
        val before = persisted
        val n = sel.keys.size
        deleteSelection()
        undoToast(tr.t("collection.itemsDeleted", "count" to n)) { restoreCollection(before) }
    }
    fun confirmClearHistory() {
        val before = persisted
        clearHistory()
        undoToast(tr.t("collection.historyCleared")) { restoreCollection(before) }
    }

    fun clearHistory() {
        persisted = persisted.copy(history = emptyList(), progress = emptyMap(), resumePositions = emptyMap(), lastWatched = emptyMap())
        overlay = null
    }

    fun deleteSelection() {
        val sel = selection
        if (sel == null) { overlay = null; return }
        val ids = sel.keys.map { it.substring(it.indexOf('|') + 1) }
        var s = persisted
        when (sel.scope) {
            "watchlist" -> s = s.copy(watchlist = s.watchlist.filter { !ids.contains(it.toString()) })
            "completed", "watching" -> ids.forEach { sid ->
                val i = sid.toInt()
                s = s.copy(
                    progress = s.progress - sid, resumePositions = s.resumePositions - sid, lastWatched = s.lastWatched - sid,
                    history = s.history.filter { it.id != i },
                )
            }
            else -> ids.forEach { k ->
                val (i, e) = k.split('.').map { it.toInt() }
                val si = i.toString(); val se = e.toString()
                var h = s.history.filter { !(it.id == i && it.episode == e) }
                val progress = s.progress[si]?.let { s.progress + (si to (it - se)) } ?: s.progress
                val resume = s.resumePositions[si]?.let { s.resumePositions + (si to (it - se)) } ?: s.resumePositions
                val lw = if (h.none { it.id == i }) s.lastWatched - si else s.lastWatched
                s = s.copy(history = h, progress = progress, resumePositions = resume, lastWatched = lw)
            }
        }
        persisted = s
        selection = null
        overlay = null
    }

    suspend fun boot(warm: suspend (List<String>) -> Unit = {}) {
        isBooting = true
        val minShowMs = when {
            catalog.isNotEmpty() && persisted.isAuthenticated != 0 -> 0L
            persisted.isAuthenticated != 0 -> 350L
            else -> 900L
        }
        kotlinx.coroutines.coroutineScope {
            val minShow = launch { kotlinx.coroutines.delay(minShowMs) }
            val work = launch {
                kotlinx.coroutines.withTimeoutOrNull(BOOT_BUDGET_MS) {
                    firstCatalogLoad.await()
                    warm(firstScreenPosterUrls())
                }
            }
            minShow.join()
            work.join()
        }
        isBooting = false
    }

    suspend fun signInWithGoogle() {
        if (isSigningIn) return
        val del = persisted.deletionScheduledAt
        if (del != null && del > 1 && now() >= del) {
            store?.wipe()
            persisted = defaults
            lastSaved = null
            boot()
            return
        }
        isSigningIn = true
        kotlinx.coroutines.delay(900)
        isSigningIn = false
        val was = persisted.deletionScheduledAt
        persisted = persisted.copy(isAuthenticated = 1, deletionScheduledAt = 0)
        viewStack.clear()
        exitingViews.clear()
        navKind = NavKind.None
        saveNow()
        if (was != null && was != 0L) showToast(tr.t("settings.deletionCancelled"))
    }

    fun saveNow() {
        val st = store ?: return
        val s = persisted
        if (s == lastSaved) return
        saveExec.execute {
            if (st.save(s)) lastSaved = s
        }
    }

    suspend fun saveInBackground() {
        val st = store ?: return
        val s = persisted
        val ok = withContext(Dispatchers.IO) { if (s == lastSaved) null else st.save(s) }
        if (ok == true) lastSaved = s
    }
}

private const val BROWSE_PAGE_SIZE = 24

private const val MAX_CATALOG_PAGES = 25

private const val BOOT_BUDGET_MS = 3_500L
