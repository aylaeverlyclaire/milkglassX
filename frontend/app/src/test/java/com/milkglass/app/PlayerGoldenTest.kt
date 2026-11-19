package com.milkglass.app

import com.milkglass.app.core.app.AppController
import com.milkglass.app.core.app.PlayerEngine
import com.milkglass.app.core.poster.PosterStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class PlayerGoldenTest {
    private val g = Json.parseToJsonElement(TestSupport.resource("golden/player_golden.json")).jsonObject
    private val t0 = g.getValue("now").jsonPrimitive.long

    private fun controller() = AppController(
        TestSupport.data, PosterStore.load { TestSupport.read("poster_ops.json") },
        store = null, bootMs = t0, clock = { t0 }, zone = TimeZone.getTimeZone("Asia/Jakarta"),
        seedState = TestSeed.state,
    )

    @Test fun scenariosMatchJs() {
        g.getValue("scenarios").jsonArray.forEach { sc ->
            val o = sc.jsonObject
            val name = o.getValue("name").jsonPrimitive.content
            val ctl = controller()
            ctl.persisted = ctl.persisted.copy(progress = emptyMap(), resumePositions = emptyMap(), lastWatched = emptyMap(), history = emptyList())
            val opened = mutableListOf<List<Number?>>()
            ctl.openPlayerHook = { id, ep, pos -> opened += listOf(id, ep, pos) }
            val id = o.getValue("id").jsonPrimitive.int
            val ep = o.getValue("ep").jsonPrimitive.int
            val pl = PlayerEngine(ctl, ctl.catalog[id], ep, o.getValue("pos").jsonPrimitive.double)
            val toasts0 = ctl.toastCount
            val outs = o.getValue("out").jsonArray.iterator()
            o.getValue("steps").jsonArray.forEach { st ->
                val a = st.jsonArray
                val op = a[0].jsonPrimitive.content
                val arg = if (a.size > 1) a[1].jsonPrimitive.content else ""
                val n = if (a.size > 2) a[2].jsonPrimitive.int else 1
                repeat(n) { i ->
                    if (!outs.hasNext()) return@repeat
                    val exp = outs.next().jsonObject
                    when (op) {
                        "tick" -> pl.tick()
                        "seekBy" -> pl.seekBy(arg.toDouble())
                        "toggle" -> pl.togglePlayPause()
                        "skip" -> pl.skip()
                        "cancelNext" -> pl.cancelAutoNext()
                        "replay" -> pl.replay()
                        "setPos" -> pl.position = arg.toDouble()
                        "controls" -> pl.setControls(arg.toBoolean())
                    }
                    val lbl = "$name ${exp.getValue("label").jsonPrimitive.content}"
                    assertEquals("$lbl position", exp.getValue("position").jsonPrimitive.double, pl.position, 1e-9)
                    assertEquals("$lbl isPlaying", exp.getValue("isPlaying").jsonPrimitive.boolean, pl.isPlaying)
                    assertEquals("$lbl hasEnded", exp.getValue("hasEnded").jsonPrimitive.boolean, pl.hasEnded)
                    assertEquals("$lbl notice", exp.getValue("noticeTimer").jsonPrimitive.double, pl.noticeTimer, 1e-9)
                    assertEquals("$lbl buffer", exp.getValue("bufferTimer").jsonPrimitive.double, pl.bufferTimer, 1e-9)
                    assertEquals("$lbl controls", exp.getValue("controlsTimer").jsonPrimitive.double, pl.controlsTimer, 1e-9)
                    assertEquals("$lbl faded", exp.getValue("faded").jsonPrimitive.boolean, pl.faded)
                    assertEquals("$lbl opSkip", exp.getValue("openingSkipped").jsonPrimitive.boolean, pl.openingSkipped)
                    assertEquals("$lbl edSkip", exp.getValue("endingSkipped").jsonPrimitive.boolean, pl.endingSkipped)
                    val expOpened = Json.parseToJsonElement(exp.getValue("opened").jsonPrimitive.content) as JsonArray
                    assertEquals("$lbl opened", expOpened.size, opened.size)
                    assertEquals("$lbl toasts", exp.getValue("toasts").jsonPrimitive.int.toLong(), ctl.toastCount - toasts0)
                }
            }
            val st = o.getValue("state").jsonObject
            val ps = ctl.persisted
            val expProgress = st.getValue("progress").jsonObject.mapValues { e -> e.value.jsonObject.mapValues { it.value.jsonPrimitive.double } }
            assertEquals("$name progress", expProgress, ps.progress)
            val expResume = st.getValue("resumePositions").jsonObject.mapValues { e -> e.value.jsonObject.mapValues { it.value.jsonPrimitive.double } }
            assertEquals("$name resume", expResume, ps.resumePositions)
            assertEquals("$name lastWatched", st.getValue("lastWatched").jsonObject.mapValues { it.value.jsonPrimitive.long }, ps.lastWatched)
            val expHist = st.getValue("history").jsonArray.map { Triple(it.jsonObject.getValue("id").jsonPrimitive.int, it.jsonObject.getValue("episode").jsonPrimitive.int, it.jsonObject.getValue("watchedAt").jsonPrimitive.long) }
            assertEquals("$name history", expHist, ps.history.map { Triple(it.id, it.episode, it.watchedAt) })
        }
    }
}
