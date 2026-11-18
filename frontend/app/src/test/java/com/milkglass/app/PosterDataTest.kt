package com.milkglass.app

import com.milkglass.app.core.design.parseCssColor
import com.milkglass.app.core.poster.PosterStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PosterDataTest {
    private val store = PosterStore.load { TestSupport.read("poster_ops.json") }

    @Test fun allSpecsPresentWithExpectedViewBox() {
        TestSupport.data.catalogRaw.forEach { a ->
            val p = store.spec(a.id, false)
            val w = store.spec(a.id, true)
            assertEquals(300 to 400, p.w to p.h)
            assertEquals(480 to 270, w.w to w.h)
            assertTrue(p.ops.isNotEmpty() && w.ops.isNotEmpty())
        }
    }

    @Test fun totalOperationCountMatchesGolden() {
        val total = TestSupport.data.catalogRaw.sumOf { store.spec(it.id, false).ops.size + store.spec(it.id, true).ops.size }
        assertEquals(705, total)
    }

    @Test fun everyOpIsDrawable() {
        TestSupport.data.catalogRaw.forEach { a ->
            listOf(false, true).forEach { wide ->
                store.spec(a.id, wide).ops.forEach { op ->
                    assertTrue(op.t in setOf("circle", "ellipse", "rect", "path"))
                    if (op.t == "path") assertTrue(!op.d.isNullOrBlank())
                    listOf(op.fill, op.stroke).forEach { c -> if (c != null && c != "none") assertNotNull(parseCssColor(c)) }
                }
            }
        }
    }
}
