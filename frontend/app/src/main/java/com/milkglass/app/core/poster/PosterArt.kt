package com.milkglass.app.core.poster

import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.roundToInt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import com.milkglass.app.core.data.AssetJson
import com.milkglass.app.core.design.parseCssColor
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.math.max

@Immutable
@Serializable
data class PosterOp(
    val t: String,
    val cx: Float? = null, val cy: Float? = null, val r: Float? = null,
    val rx: Float? = null, val ry: Float? = null,
    val x: Float? = null, val y: Float? = null, val width: Float? = null, val height: Float? = null,
    val d: String? = null,
    val fill: String? = null,
    @SerialName("fill-opacity") val fillOpacity: Float? = null,
    val stroke: String? = null,
    @SerialName("stroke-opacity") val strokeOpacity: Float? = null,
    @SerialName("stroke-width") val strokeWidth: Float? = null,
    @SerialName("stroke-linecap") val strokeLinecap: String? = null,
    val transform: String? = null,
)

@Immutable
@Serializable
data class PosterSpec(
    val w: Int, val h: Int, val ops: List<PosterOp>, val url: String? = null,
    /**
     * true = [url] adalah poster portrait yang dipakai di slot 16:9. Poster tidak di-crop menjadi kartu lebar
     * (hasilnya kepotong): yang digambar hanya latar ambient berupa poster yang diburamkan.
     */
    val ambient: Boolean = false,
)

class PosterStore(private val specs: Map<String, PosterSpec>) {
    fun allSpecs(wide: Boolean): List<PosterSpec> = specs.filterKeys { it.endsWith(if (wide) "w" else "p") }.values.toList()

    
    fun blank(wide: Boolean, url: String? = null): PosterSpec =
        if (wide) PosterSpec(320, 180, emptyList(), url) else PosterSpec(200, 300, emptyList(), url)

    
    fun spec(animeId: Int, wide: Boolean): PosterSpec =
        specs.getValue(animeId.toString() + if (wide) "w" else "p")

    companion object {
        fun load(read: () -> String): PosterStore = PosterStore(
            AssetJson.decodeFromString(MapSerializer(String.serializer(), PosterSpec.serializer()), read()),
        )
    }
}

private val ROTATE = Regex("""rotate\(\s*([-\d.eE]+)\s+([-\d.eE]+)\s+([-\d.eE]+)\s*\)""")

private class PreparedOp(
    val op: PosterOp,
    val path: Path?,
    val fill: Color?,
    val stroke: Color?,
    val style: Stroke,
    val hasRotation: Boolean,
    val rotationDeg: Float,
    val pivot: Offset,
)

private fun prepare(op: PosterOp): PreparedOp {
    val m = if (op.t == "rect") op.transform?.let { ROTATE.matchEntire(it) } else null
    return PreparedOp(
        op = op,
        path = if (op.t == "path" && op.d != null) PathParser().parsePathString(op.d).toPath() else null,
        fill = colorOf(op.fill, op.fillOpacity),
        stroke = colorOf(op.stroke, op.strokeOpacity),
        style = Stroke(
            width = op.strokeWidth ?: 1f,
            cap = if (op.strokeLinecap == "round") StrokeCap.Round else StrokeCap.Butt,
        ),
        hasRotation = m != null,
        rotationDeg = m?.groupValues?.get(1)?.toFloat() ?: 0f,
        pivot = if (m != null) Offset(m.groupValues[2].toFloat(), m.groupValues[3].toFloat()) else Offset.Zero,
    )
}

object PosterBitmaps {
    private class Key(val spec: PosterSpec, val w: Int, val h: Int) {
        override fun equals(other: Any?) = other is Key && other.spec === spec && other.w == w && other.h == h
        override fun hashCode() = (System.identityHashCode(spec) * 31 + w) * 31 + h
    }

    
    private val cache = object : android.util.LruCache<Key, ImageBitmap>(40 * 1024 * 1024) {
        override fun sizeOf(key: Key, value: ImageBitmap) = value.width * value.height * 4
    }
    private val prepared = java.util.IdentityHashMap<PosterSpec, List<PreparedOp>>()
    private val inflight = HashMap<Key, MutableList<() -> Unit>>()
    private val pool = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "poster-render").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }

    
    fun peek(spec: PosterSpec, w: Int, h: Int): ImageBitmap? = cache.get(Key(spec, w, h))

    
    fun requestAsync(spec: PosterSpec, w: Int, h: Int, onReady: () -> Unit) {
        val k = Key(spec, w, h)
        
        
        synchronized(inflight) {
            val waiting = inflight[k]
            if (waiting != null) { waiting.add(onReady); return }
            inflight[k] = mutableListOf(onReady)
        }
        pool.execute {
            var ok = false
            try { render(spec, w, h); ok = true } finally {
                val callbacks = synchronized(inflight) { inflight.remove(k) }
                if (ok) callbacks?.forEach { it() }
            }
        }
    }

    private fun render(spec: PosterSpec, w: Int, h: Int): ImageBitmap {
        val k = Key(spec, w, h)
        cache.get(k)?.let { return it }
        val ops = synchronized(prepared) { prepared.getOrPut(spec) { spec.ops.map { prepare(it) } } }
        val bmp = ImageBitmap(w, h)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp), Size(w.toFloat(), h.toFloat())) {
            
            val s = max(size.width / spec.w, size.height / spec.h)
            val ox = (size.width - spec.w * s) / 2f
            val oy = (size.height - spec.h * s) / 2f
            clipRect {
                withTransform({
                    translate(ox, oy)
                    scale(s, s, pivot = Offset.Zero)
                }) {
                    for (i in ops.indices) drawPrepared(ops[i])
                }
            }
        }
        cache.put(k, bmp)
        return bmp
    }

    
    fun prewarm(store: PosterStore, density: Float) {
        for (dp in intArrayOf(132, 52, 44)) {
            val w = (dp * density).roundToInt()
            val h = (w * 4f / 3f).roundToInt()
            store.allSpecs(wide = false).forEach { requestAsync(it, w, h) { } }
        }
    }
}

@Composable
fun PosterArt(spec: PosterSpec, modifier: Modifier = Modifier) {
    if (spec.url != null) {
        if (!spec.ambient) {
            coil.compose.AsyncImage(model = spec.url, contentDescription = null, modifier = modifier, contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        } else {
            AmbientBackdrop(spec.url, modifier)
        }
        return
    }
    val version = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    Spacer(
        modifier.drawWithCache {
            val w = size.width.roundToInt()
            val h = size.height.roundToInt()
            onDrawBehind {
                version.intValue 
                if (w > 0 && h > 0) {
                    val bmp = PosterBitmaps.peek(spec, w, h)
                    if (bmp != null) drawImage(bmp) else PosterBitmaps.requestAsync(spec, w, h) { version.intValue++ }
                }
            }
        },
    )
}

/**
 * Latar ambient dari poster portrait: diminta dalam resolusi sangat kecil lalu direntangkan, hasilnya buram di
 * semua versi Android (tanpa Modifier.blur yang hanya jalan di Android 12+) dan tidak ada bagian poster yang
 * terbaca sebagai gambar terpotong.
 */
@Composable
private fun AmbientBackdrop(url: String, modifier: Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val req = remember(url) {
        coil.request.ImageRequest.Builder(ctx).data(url).size(48, 72).crossfade(false).build()
    }
    coil.compose.AsyncImage(
        model = req, contentDescription = null,
        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        modifier = modifier.graphicsLayer { alpha = .7f },
    )
}

private fun colorOf(value: String?, opacity: Float?): Color? {
    if (value == null || value == "none") return null
    val c = parseCssColor(value) ?: return null
    return c.copy(alpha = c.alpha * (opacity ?: 1f))
}

private fun DrawScope.drawPrepared(p: PreparedOp) {
    val op = p.op
    val fillColor = p.fill
    val strokeColor = p.stroke
    val strokeStyle = p.style
    when (op.t) {
        "circle" -> {
            val c = Offset(op.cx!!, op.cy!!)
            fillColor?.let { drawCircle(it, op.r!!, c, style = Fill) }
            strokeColor?.let { drawCircle(it, op.r!!, c, style = strokeStyle) }
        }
        "ellipse" -> {
            val tl = Offset(op.cx!! - op.rx!!, op.cy!! - op.ry!!)
            val sz = Size(op.rx * 2f, op.ry * 2f)
            fillColor?.let { drawOval(it, tl, sz, style = Fill) }
            strokeColor?.let { drawOval(it, tl, sz, style = strokeStyle) }
        }
        "rect" -> {
            val tl = Offset(op.x!!, op.y!!)
            val sz = Size(op.width!!, op.height!!)
            val draw: DrawScope.() -> Unit = {
                fillColor?.let { drawRect(it, tl, sz, style = Fill) }
                strokeColor?.let { drawRect(it, tl, sz, style = strokeStyle) }
            }
            if (p.hasRotation) rotate(p.rotationDeg, p.pivot) { draw() } else draw()
        }
        "path" -> if (p.path != null) {
            fillColor?.let { drawPath(p.path, it, style = Fill) }
            strokeColor?.let { drawPath(p.path, it, style = strokeStyle) }
        }
    }
}
