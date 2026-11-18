package com.milkglass.app

import com.milkglass.app.core.app.AppController
import com.milkglass.app.core.app.GENRES
import com.milkglass.app.core.app.ViewEntry
import com.milkglass.app.core.data.DAY_MS
import com.milkglass.app.core.data.SeedData
import com.milkglass.app.core.data.initialState
import com.milkglass.app.core.poster.PosterStore
import com.milkglass.app.core.storage.StateCodec
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.TimeZone
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

class MonteCarloStabilityTest {

    
    private class Ctx(val ctl: AppController, val rnd: Random, val clk: LongArray) {
        fun anime() = ctl.catalog[rnd.nextInt(ctl.catalog.size)]
        fun <T> pick(l: List<T>): T = l[rnd.nextInt(l.size)]
        fun text(): String = if (rnd.nextInt(4) == 0) {
            buildString { repeat(rnd.nextInt(0, 40)) { append(rnd.nextInt(0x20, 0x2FFF).toChar()) } }
        } else pick(TEXTS)
    }

    private class Act(val name: String, val weight: Int, val run: (Ctx) -> Unit)

    private class Stat {
        var n = 0L
        var total = 0L
        var max = 0L
        val hist = LongArray(HBUCKETS)
        fun add(ns: Long) {
            n++; total += ns; if (ns > max) max = ns
            val i = (ln(ns.coerceAtLeast(1).toDouble()) * 8.0 / LN2).toInt().coerceIn(0, HBUCKETS - 1)
            hist[i]++
        }
    }

    private class Finding(val kind: String, val signature: String, val seq: Int, val step: Int, val trail: List<String>) {
        var count = 1L
    }

    
    private val stats = HashMap<String, Stat>()
    private val overall = Stat()
    private val findings = LinkedHashMap<String, Finding>()
    private val rawKeyTexts = sortedSetOf<String>()
    private var sessions = 0L
    private var failedSessions = 0L
    private var actions = 0L
    private var maxStack = 0
    private var maxStateBytes = 0
    private var maxHeapUsed = 0L
    private var roundTrips = 0L
    private var invariantChecks = 0L

    private val t0 = 1_700_000_000_000L
    private val seedConv by lazy { SeedData.conversations(TestSupport.data.seedConversationsRaw, t0) }
    private val seedState by lazy { SeedData.initialState(TestSupport.translator, seedConv, t0) }
    private val posters by lazy { PosterStore.load { TestSupport.read("poster_ops.json") } }
    private val baselineIgnored = HashSet<String>()

    private fun env(name: String, def: Long): Long = System.getenv(name)?.trim()?.toLongOrNull() ?: def

    private fun newCtl(clk: LongArray) = AppController(
        TestSupport.data, posters, store = null, bootMs = t0, clock = { clk[0] },
        zone = TimeZone.getTimeZone("Asia/Jakarta"),
        seedState = TestSeed.state,
    )

    
    private fun violation(c: AppController): String? {
        val s = c.persisted
        val n = c.catalog.size
        if (s.watchlist.size != s.watchlist.toSet().size) return "watchlist-duplikat"
        if (s.watchlist.any { it < 0 || it >= n }) return "watchlist-id-di-luar-katalog"
        if (s.recentSearches.size > 6) return "recentSearches-lebih-dari-6"
        if (s.recentSearches.size != s.recentSearches.toSet().size) return "recentSearches-duplikat"
        val seen = HashSet<Long>()
        for (h in s.history) {
            if (h.id < 0 || h.id >= n) return "history-id-tidak-valid"
            if (h.episode < 1) return "history-episode-kurang-dari-1"
            if (!seen.add(h.id.toLong() * 10_000L + h.episode)) return "history-duplikat"
        }
        for ((id, eps) in s.progress) {
            val a = id.toIntOrNull()?.let { c.catalog.getOrNull(it) } ?: return "progress-kunci-tidak-valid"
            val dur = c.durationSeconds(a)
            for ((_, v) in eps) {
                if (!v.isFinite() || v < 0.0) return "progress-NaN-atau-negatif"
                if (v > dur + 1) return "progress-melebihi-durasi"
            }
        }
        for ((_, eps) in s.resumePositions) for ((_, v) in eps) if (!v.isFinite() || v < 0.0) return "resume-NaN-atau-negatif"
        val sel = c.selection
        if (sel != null && sel.keys.isEmpty()) return "selection-kosong-tapi-tidak-null"
        for (v in c.viewStack) if (v is ViewEntry.Detail && (v.id < 0 || v.id >= n)) return "detail-id-tidak-valid"
        val top = c.viewStack.takeLast(3)
        for (v in top) if (v is ViewEntry.Player) {
            val p = v.engine
            if (!p.position.isFinite() || p.position < 0.0 || p.position > p.duration + 1e-6) return "posisi-pemutar-di-luar-rentang"
            if (p.adSecondsLeft < 0.0 || p.noticeTimer < 0.0) return "timer-pemutar-negatif"
        }
        return null
    }

    private fun roundTripViolation(c: AppController): String? {
        val s = c.persisted
        val expected = s.copy(downloads = s.downloads.filterValues { it == 100 })
        val back = StateCodec.decode(StateCodec.encode(s), seedState, seedConv)
        return if (back == expected) null else "state-roundtrip-berbeda"
    }

    
    private fun runSession(seq: Int, seed: Long, steps: Int, timing: Boolean, soak: Boolean): Finding? {
        val rnd = Random(seed)
        val clk = longArrayOf(t0)
        val trail = ArrayDeque<String>()
        var step = -1
        var lastToastId = -1L
        fun fail(kind: String, sig: String): Finding {
            val key = "$kind|$sig"
            val f = findings[key]
            if (f != null) { f.count++; return f }
            return Finding(kind, sig, seq, step, trail.toList()).also { findings[key] = it }
        }
        val ctl = try { newCtl(clk) } catch (t: Throwable) { return fail("crash", sig(t)) }
        val c = Ctx(ctl, rnd, clk)
        while (++step < steps) {
            val act = pickAct(rnd)
            trail.addLast(act.name); if (trail.size > 12) trail.removeFirst()
            try {
                if (timing) {
                    val a = System.nanoTime()
                    act.run(c)
                    val d = System.nanoTime() - a
                    overall.add(d); stats.getOrPut(act.name) { Stat() }.add(d)
                } else act.run(c)
            } catch (t: Throwable) {
                return fail("crash", "${act.name} -> ${sig(t)}")
            }
            actions++
            invariantChecks++
            violation(ctl)?.let { if (it !in baselineIgnored) return fail("invariant", "${act.name} -> $it") }
            if (ctl.viewStack.size > maxStack) maxStack = ctl.viewStack.size
            ctl.toast?.let { if (it.id != lastToastId) { lastToastId = it.id; if (RAW_KEY.matches(it.text)) rawKeyTexts += it.text } }
            ctl.editError?.let { if (RAW_KEY.matches(it)) rawKeyTexts += it }
            ctl.deleteError?.let { if (RAW_KEY.matches(it)) rawKeyTexts += it }
            if (step % 25 == 0) {
                roundTrips++
                roundTripViolation(ctl)?.let { return fail("invariant", "step $step -> $it") }
            }
            if (step % 2000 == 0) {
                val rt = Runtime.getRuntime(); val used = rt.totalMemory() - rt.freeMemory()
                if (used > maxHeapUsed) maxHeapUsed = used
            }
            if (soak && step % 500 == 0) {
                val b = StateCodec.encode(ctl.persisted).length
                if (b > maxStateBytes) maxStateBytes = b
            }
        }
        return null
    }

    private fun sig(t: Throwable): String {
        val frame = t.stackTrace.firstOrNull { it.className.startsWith("com.milkglass") } ?: t.stackTrace.firstOrNull()
        return "${t::class.java.simpleName}: ${t.message?.take(120)} @ ${frame?.className?.substringAfterLast('.')}.${frame?.methodName}:${frame?.lineNumber}"
    }

    private fun pickAct(rnd: Random): Act {
        var r = rnd.nextInt(TOTAL_WEIGHT)
        for (a in ACTIONS) { r -= a.weight; if (r < 0) return a }
        return ACTIONS.last()
    }

    
    @Test fun monteCarloStability() {
        assumeTrue("MC_SEQUENCES belum diset; simulasi dilewati.", System.getenv("MC_SEQUENCES") != null)
        val nSeq = env("MC_SEQUENCES", 2_000).toInt()
        val nSteps = env("MC_STEPS", 50).toInt()
        val seed = env("MC_SEED", 20261112L)
        val maxSec = env("MC_MAX_SECONDS", 600)
        val soakSeq = env("MC_SOAK_SEQUENCES", 100).toInt()
        val soakSteps = env("MC_SOAK_STEPS", 3_000).toInt()
        val replay = System.getenv("MC_REPLAY")?.trim()?.toIntOrNull()

        
        try {
            val clk = longArrayOf(t0)
            violation(newCtl(clk))?.let { baselineIgnored += it }
        } catch (e: Throwable) {
            File("build/reports/montecarlo").mkdirs()
            File("build/reports/montecarlo/ringkasan.md")
                .writeText("# Simulasi gagal saat inisialisasi\n\n```\n" + e.stackTraceToString().take(3000) + "\n```\n")
            throw e
        }

        if (replay != null) {
            val f = runSession(replay, seed + replay, nSteps, timing = false, soak = false)
            println("REPLAY sesi $replay: " + (f?.let { "${it.kind} ${it.signature} jejak=${it.trail}" } ?: "tanpa temuan"))
            if (f != null) fail("replay $replay: ${f.kind} ${f.signature}")
            return
        }

        val gcBefore = gcStat("getCollectionCount")
        val gcMsBefore = gcStat("getCollectionTime")
        val startNs = System.nanoTime()
        val deadline = startNs + maxSec * 1_000_000_000L
        val warmup = (nSeq / 20).coerceIn(0, 500)
        var truncated = false

        for (seq in 0 until nSeq) {
            if (System.nanoTime() > deadline) { truncated = true; break }
            sessions++
            if (runSession(seq, seed + seq, nSteps, timing = seq >= warmup, soak = false) != null) failedSessions++
        }
        val mainNs = System.nanoTime() - startNs

        val soakStart = System.nanoTime()
        var soakDone = 0
        for (i in 0 until soakSeq) {
            if (System.nanoTime() > deadline + 120_000_000_000L) { truncated = true; break }
            sessions++; soakDone++
            if (runSession(1_000_000 + i, seed + 1_000_000 + i, soakSteps, timing = false, soak = true) != null) failedSessions++
        }
        val soakNs = System.nanoTime() - soakStart
        val totalNs = System.nanoTime() - startNs

        val gc = gcStat("getCollectionCount") - gcBefore
        val gcMs = gcStat("getCollectionTime") - gcMsBefore

        val md = report(nSeq, nSteps, soakDone, soakSteps, seed, mainNs, soakNs, totalNs, gc, gcMs, truncated)
        println(md)
        val dir = File("build/reports/montecarlo").also { it.mkdirs() }
        File(dir, "ringkasan.md").writeText(md)
        File(dir, "hasil.json").writeText(json(nSeq, nSteps, seed, totalNs))

        check(actions > 0) { "tidak ada aksi yang dijalankan" }
        if (findings.isNotEmpty()) {
            fail("Simulasi menemukan ${findings.size} jenis kegagalan di $failedSessions/$sessions sesi. Lihat ringkasan.md.\n" +
                findings.values.take(10).joinToString("\n") { "- [${it.kind}] ${it.signature} (x${it.count}, sesi ${it.seq}, langkah ${it.step})" })
        }
    }

    
    private fun pctUs(s: Stat, p: Double): Double {
        if (s.n == 0L) return 0.0
        val target = ceil(s.n * p).toLong().coerceAtLeast(1)
        var acc = 0L
        for (i in s.hist.indices) { acc += s.hist[i]; if (acc >= target) return 2.0.pow((i + 1) / 8.0) / 1000.0 }
        return s.max / 1000.0
    }

    private fun wilsonUpper(f: Long, n: Long): Double {
        if (n == 0L) return 1.0
        val z = 1.96; val p = f.toDouble() / n
        val den = 1 + z * z / n
        val center = p + z * z / (2.0 * n)
        val margin = z * sqrt(p * (1 - p) / n + z * z / (4.0 * n * n))
        return ((center + margin) / den).coerceAtMost(1.0)
    }

    private fun fmt(d: Double) = String.format(java.util.Locale.US, "%.1f", d)

    
    private fun gcStat(getter: String): Long = runCatching {
        val factory = Class.forName("java.lang.management.ManagementFactory")
        val beans = factory.getMethod("getGarbageCollectorMXBeans").invoke(null) as List<*>
        beans.sumOf { b ->
            val v = b!!.javaClass.getMethod(getter).apply { isAccessible = true }.invoke(b) as Long
            v.coerceAtLeast(0L)
        }
    }.getOrDefault(0L)

    private fun report(nSeq: Int, nSteps: Int, soakDone: Int, soakSteps: Int, seed: Long, mainNs: Long, soakNs: Long, totalNs: Long, gc: Long, gcMs: Long, truncated: Boolean): String {
        val sb = StringBuilder()
        val sec = totalNs / 1e9
        sb.appendLine("# Simulasi Monte Carlo — stabilitas & performa logika aplikasi")
        sb.appendLine()
        sb.appendLine("- Sesi dijalankan: **$sessions** (acak: ${sessions - soakDone}, soak: $soakDone) — rencana acak $nSeq × $nSteps langkah, soak $soakDone × $soakSteps langkah")
        sb.appendLine("- Total aksi: **$actions**; pemeriksaan invarian: $invariantChecks; uji bolak-balik simpan/muat state: $roundTrips")
        sb.appendLine("- Benih dasar: $seed (sesi n memakai benih+n; ulang satu sesi dengan MC_REPLAY=n)")
        sb.appendLine("- Waktu: ${fmt(sec)} dtk (acak ${fmt(mainNs / 1e9)} dtk, soak ${fmt(soakNs / 1e9)} dtk); throughput ≈ ${fmt(actions / sec)} aksi/dtk")
        if (truncated) sb.appendLine("- ⚠️ Dihentikan lebih awal oleh batas waktu MC_MAX_SECONDS; jumlah sesi lebih kecil dari rencana.")
        sb.appendLine()
        sb.appendLine("## Stabilitas")
        val up = wilsonUpper(failedSessions, sessions)
        sb.appendLine("- Sesi gagal (exception atau pelanggaran invarian): **$failedSessions / $sessions**")
        sb.appendLine("- Batas atas 95% laju gagal per sesi (Wilson): ${String.format(java.util.Locale.US, "%.5f%%", up * 100)}" +
            if (failedSessions == 0L) " (≈ 3/N, 'aturan tiga')" else "")
        sb.appendLine("- Kedalaman tumpukan layar maksimum teramati: $maxStack; ukuran state tersimpan maksimum (soak): $maxStateBytes karakter JSON")
        sb.appendLine("- Memori terpakai maksimum (sampel JVM): ${maxHeapUsed / (1024 * 1024)} MiB; GC: $gc kali, total $gcMs ms")
        if (findings.isEmpty()) sb.appendLine("- Tidak ada temuan.")
        else {
            sb.appendLine()
            sb.appendLine("### Temuan (unik: ${findings.size})")
            findings.values.sortedByDescending { it.count }.forEach {
                sb.appendLine("- **[${it.kind}]** `${it.signature}` — ${it.count}×; pertama di sesi ${it.seq}, langkah ${it.step}")
                sb.appendLine("  - jejak aksi terakhir: ${it.trail.joinToString(" → ")}")
                sb.appendLine("  - ulang: `MC_SEQUENCES=1 MC_REPLAY=${it.seq} MC_STEPS=${if (it.seq >= 1_000_000) soakSteps else nSteps}` (seed=$seed)")
            }
        }
        if (rawKeyTexts.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("### Kunci terjemahan mentah tampil ke pengguna (bukan crash)")
            rawKeyTexts.forEach { sb.appendLine("- `$it`") }
        }
        sb.appendLine()
        sb.appendLine("## Performa logika (CPU JVM, per aksi, µs; setelah pemanasan)")
        sb.appendLine("| Aksi | n | rata-rata | p50 | p95 | p99 | maks |")
        sb.appendLine("|---|---:|---:|---:|---:|---:|---:|")
        fun row(name: String, s: Stat) = sb.appendLine("| $name | ${s.n} | ${fmt(if (s.n == 0L) 0.0 else s.total / 1000.0 / s.n)} | ${fmt(pctUs(s, .5))} | ${fmt(pctUs(s, .95))} | ${fmt(pctUs(s, .99))} | ${fmt(s.max / 1000.0)} |")
        row("**semua aksi**", overall)
        stats.entries.sortedByDescending { pctUs(it.value, .99) }.take(15).forEach { row(it.key, it.value) }
        sb.appendLine()
        sb.appendLine("_p50/p95/p99 dari histogram log (resolusi ±9%); 15 aksi teratas menurut p99._")
        sb.appendLine()
        sb.appendLine("## Batas cakupan")
        sb.appendLine("- Yang diuji: logika nyata `AppController`, `PlayerEngine`, `savePlayback`, `StateCodec` di JVM dengan jam simulasi dan tanpa penyimpanan berkas.")
        sb.appendLine("- Yang TIDAK diuji: render Compose/jank layar, memori grafis, baterai, jaringan nyata, penyimpanan nyata, siklus hidup Android. Angka µs adalah CPU JVM CI, bukan perangkat pengguna.")
        sb.appendLine("- Ketiadaan temuan bukan bukti ketiadaan bug; hanya berlaku untuk aksi dan invarian yang dimodelkan di atas.")
        return sb.toString()
    }

    private fun json(nSeq: Int, nSteps: Int, seed: Long, totalNs: Long): String {
        fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""
        val f = findings.values.joinToString(",") { "{\"kind\":${q(it.kind)},\"signature\":${q(it.signature)},\"count\":${it.count},\"seq\":${it.seq},\"step\":${it.step}}" }
        return "{\"seed\":$seed,\"plannedSequences\":$nSeq,\"steps\":$nSteps,\"sessions\":$sessions,\"failedSessions\":$failedSessions," +
            "\"actions\":$actions,\"seconds\":${totalNs / 1e9},\"p50us\":${pctUs(overall, .5)},\"p95us\":${pctUs(overall, .95)}," +
            "\"p99us\":${pctUs(overall, .99)},\"maxus\":${overall.max / 1000.0},\"maxStack\":$maxStack,\"findings\":[$f]}"
    }

    companion object {
        private const val HBUCKETS = 320
        private val LN2 = ln(2.0)
        private val RAW_KEY = Regex("^[A-Za-z]+(\\.[A-Za-z0-9_]+)+$")
        private val TEXTS = listOf(
            "", " ", "Kaelan", "mecha", "NARUTO", "😀", "a".repeat(5000), "<script>", "@kamu", "a_b.c", "ab",
            "x".repeat(21), "HAPUS", "ｆｕｌｌｗｉｄｔｈ", "é", "\n", "&", "\"", "kamu", "Dito",
        )
        private val SCOPES = listOf("watchlist", "completed", "history")
        private val TABS = listOf("collection", "home", "schedule")

        private val ACTIONS: List<Act> = listOf(
            
            Act("openDetail", 8) { it.ctl.openDetail(it.anime().id) },
            Act("openSearch", 3) { it.ctl.openSearch() },
            Act("searchByGenre", 2) { it.ctl.searchByGenre(it.pick(GENRES)) },
            Act("typeAndSubmitSearch", 5) { c -> c.ctl.searchQuery = c.text(); c.ctl.submitSearch(); c.ctl.searchCatalog() },
            Act("clearRecentSearches", 1) { it.ctl.clearRecentSearches() },
            Act("goBack", 8) { it.ctl.goBack() },
            Act("popView", 3) { it.ctl.popView() },
            
            Act("toggleWatchlist", 5) { it.ctl.toggleWatchlist(it.anime().id) },
            Act("openEpisode", 6) { c -> val a = c.anime(); c.ctl.openEpisode(a, c.rnd.nextInt(1, a.totalEpisodes + 3)) },
            Act("playEpisode", 6) { c ->
                val a = c.anime()
                c.ctl.playEpisode(a.id, if (c.rnd.nextBoolean()) null else c.rnd.nextInt(1, a.totalEpisodes + 3))
            },
            
            Act("playerTicks", 14) { c -> val p = c.ctl.player; if (p != null) repeat(c.rnd.nextInt(1, 200)) { c.ctl.player?.tick() } },
            Act("seekBy", 3) { c -> c.ctl.player?.seekBy(c.rnd.nextDouble(-600.0, 600.0)) },
            Act("seekTo", 3) { c -> c.ctl.player?.let { it.seekTo(c.rnd.nextDouble() * it.duration) } },
            Act("seekToHostile", 1) { c ->
                c.ctl.player?.seekTo(c.pick(listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -5.0, 1e9)))
            },
            Act("togglePlayPause", 3) { it.ctl.player?.togglePlayPause() },
            Act("skip", 2) { it.ctl.player?.skip() },
            Act("cancelAutoNext", 1) { it.ctl.player?.cancelAutoNext() },
            Act("replay", 1) { it.ctl.player?.replay() },
            Act("retry", 1) { it.ctl.player?.retry() },
            Act("toggleFullscreen", 1) { it.ctl.player?.toggleFullscreen() },
            Act("playNextNow", 2) { it.ctl.player?.playNextNow() },
            Act("setControls", 1) { c -> c.ctl.player?.setControls(c.rnd.nextBoolean()) },
            Act("setSpeed", 1) { c -> c.ctl.player?.speed = c.pick(listOf(0.5, 1.0, 1.5, 2.0)) },
            Act("setQuality", 1) { c -> c.ctl.setQuality(c.pick(listOf("Auto", "1080p", "720p", "480p", "??"))) },
            
            Act("selectCollectionTab", 3) { c -> c.ctl.selectCollectionTab(c.pick(SCOPES)) },
            Act("toggleSelection", 5) { c ->
                val a = c.anime()
                val key = when (c.pick(SCOPES)) {
                    "watchlist" -> "watchlist|${a.id}"
                    "completed" -> "completed|${a.id}"
                    else -> "history|${a.id}.${c.rnd.nextInt(1, a.totalEpisodes + 1)}"
                }
                c.ctl.toggleSelection(key, c.rnd.nextBoolean(), c.pick(TABS))
            },
            Act("toggleSelectAll", 2) { c -> c.ctl.toggleSelectAll(c.pick(SCOPES)) },
            Act("confirmDeleteSelection", 1) { it.ctl.confirmDeleteSelection() },
            Act("deleteSelection", 2) { it.ctl.deleteSelection() },
            Act("cancelSelection", 1) { it.ctl.cancelSelection() },
            Act("confirmClearHistory", 1) { it.ctl.confirmClearHistory() },
            Act("clearHistory", 1) { it.ctl.clearHistory() },
            
            Act("chooseSettingOption", 2) { c ->
                c.ctl.chooseSettingOption(
                    c.pick(listOf("theme", "bogus")),
                    c.pick(listOf("light", "dark", "system", "x")),
                )
            },
            Act("saveEditField", 3) { c -> c.ctl.saveEditField(c.pick(listOf("nm", "hd", "zz")), c.text()) },
            Act("openEditField", 1) { c -> c.ctl.openEditField(c.pick(listOf("nm", "hd"))) },
            Act("openSettingOptions", 1) { c -> c.ctl.openSettingOptions("theme") },
            Act("confirmDeleteAccount", 1) { it.ctl.confirmDeleteAccount() },
            Act("confirmSignOut", 1) { it.ctl.confirmSignOut() },
            Act("closeOverlay", 2) { it.ctl.closeOverlay() },
            Act("openLegalDocument", 1) { c -> c.ctl.openLegalDocument(c.pick(c.ctl.data.locale.legalDocs.keys.toList() + "bogus")) },
            Act("deleteAccount", 1) { c -> c.ctl.deleteAccount(c.text()) },
            Act("signOut", 1) { it.ctl.signOut() },
            
            Act("onConnectivityChanged", 3) { c -> c.ctl.onConnectivityChanged(c.rnd.nextBoolean()) },
            Act("advanceClock", 4) { c ->
                c.clk[0] += c.pick(listOf(250L, 20_000L, 3_600_000L, DAY_MS, 7 * DAY_MS, 30 * DAY_MS))
                c.ctl.tick = c.clk[0]
            },
            Act("launchFireworks", 1) { it.ctl.launchFireworks() },
            Act("showAndClearToast", 1) { c -> c.ctl.showToast("uji"); c.ctl.toast?.let { c.ctl.clearToast(it.id) } },
            Act("saveNow", 1) { it.ctl.saveNow() },
            
            Act("renderReads", 10) { c ->
                val k = c.ctl
                k.continueWatchingList(); k.trendingList(); k.recommendedList(); k.genreAffinity()
                k.scheduleForDay(c.rnd.nextInt(0, 7))
                for (a in k.catalog) { k.continueInfo(a); k.continueLabel(a); k.genreSummary(a) }
                k.countdownText(c.clk[0] + c.rnd.nextLong(-DAY_MS, 40 * DAY_MS))
                k.searchCatalog(); k.meName.length; k.isPremium()
            },
        )
        private val TOTAL_WEIGHT = ACTIONS.sumOf { it.weight }
    }
}
