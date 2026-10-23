package com.nanote.engine

import com.google.common.truth.Truth.assertThat
import com.nanote.ink.engine.Bounds
import com.nanote.ink.engine.OneEuro
import com.nanote.ink.engine.PageDoc
import com.nanote.ink.engine.PenType
import com.nanote.ink.engine.PRESSURE_LUT
import com.nanote.ink.engine.outlinePolygon
import com.nanote.ink.engine.specOf
import com.nanote.ink.engine.strokeOf
import com.nanote.ink.engine.widths
import org.junit.Test
import kotlin.math.hypot

class PageDocTest {

    private fun s(id: Int, x0: Float, y0: Float) =
        strokeOf(id, PenType.BALLPOINT, 0xFF000000.toInt(), 2f, floatArrayOf(x0, y0, x0 + 10f, y0, x0 + 20f, y0))

    @Test
    fun `add remove bump revision and keep grid in sync`() {
        val doc = PageDoc(595f, 842f)
        assertThat(doc.revision).isEqualTo(0)
        doc.addStrokes(listOf(s(1, 0f, 0f), s(2, 0f, 100f)))
        assertThat(doc.revision).isEqualTo(1)
        assertThat(doc.strokeCount).isEqualTo(2)

        val b = Bounds()
        b.set(-5f, -5f, 25f, 5f)
        val hits = HashSet<Int>()
        doc.grid.query(b, hits)
        assertThat(hits).containsExactly(1)

        doc.removeStrokes(listOf(1))
        assertThat(doc.strokeCount).isEqualTo(1)
        doc.grid.query(b, hits)
        assertThat(hits).isEmpty()
    }

    @Test
    fun `nextStrokeId is monotonic and respects loaded ids`() {
        val doc = PageDoc(595f, 842f, listOf(s(7, 0f, 0f)))
        assertThat(doc.nextStrokeId()).isEqualTo(8)
        assertThat(doc.nextStrokeId()).isEqualTo(9)
    }

    @Test
    fun `dirtySince tracks changed regions`() {
        val doc = PageDoc(595f, 842f)
        val rev0 = doc.revision
        doc.addStrokes(listOf(s(1, 10f, 10f)))
        val rev1 = doc.revision
        val dirty = Bounds()
        assertThat(doc.dirtySince(rev0, dirty)).isTrue()
        assertThat(dirty.left).isAtMost(10f)
        assertThat(dirty.right).isAtLeast(30f)

        // nothing changed after rev1
        assertThat(doc.dirtySince(rev1, dirty)).isFalse()
    }

    @Test
    fun `strokes snapshot preserves insertion order`() {
        val doc = PageDoc(595f, 842f)
        doc.addStrokes(listOf(s(2, 0f, 0f)))
        doc.addStrokes(listOf(s(1, 0f, 50f)))
        assertThat(doc.strokesSnapshot().map { it.id }).containsExactly(2, 1).inOrder()
    }
}

class OneEuroTest {

    @Test
    fun `constant input converges`() {
        val f = OneEuro()
        var out = 0f
        for (i in 0 until 200) {
            out = f.apply(5f, i * 0.008f)
        }
        assertThat(out).isWithin(0.001f).of(5f)
    }

    @Test
    fun `smooths jitter more than raw`() {
        // noisy square wave: raw jumps +-2, filtered jumps less
        val f = OneEuro()
        var prev = 0f
        var maxRawJump = 0f
        var maxFilteredJump = 0f
        var filtered = 0f
        for (i in 0 until 1000) {
            val raw = if ((i / 20) % 2 == 0) 0f else 2f
            filtered = f.apply(raw, i * 0.008f)
            if (i > 0) {
                maxRawJump = maxOf(maxRawJump, kotlin.math.abs(raw - prev))
                maxFilteredJump = maxOf(maxFilteredJump, kotlin.math.abs(filtered - prev))
            }
            prev = raw
        }
        assertThat(maxFilteredJump).isLessThan(maxRawJump)
    }

    private fun maxOf(a: Float, b: Float) = if (a > b) a else b
}

class WidthOutlineTest {

    @Test
    fun `pressure LUT is monotonic and normalized`() {
        assertThat(PRESSURE_LUT[0]).isEqualTo(0f)
        assertThat(PRESSURE_LUT[255]).isEqualTo(1f)
        for (i in 1 until PRESSURE_LUT.size) {
            assertThat(PRESSURE_LUT[i]).isAtLeast(PRESSURE_LUT[i - 1])
        }
    }

    @Test
    fun `widths stay within spec scale range for constant pressure`() {
        val s = strokeOf(1, PenType.BALLPOINT, 0xFF000000.toInt(), 3f, floatArrayOf(0f, 0f, 5f, 0f, 10f, 0f, 15f, 0f), pressure = 1f)
        val w = widths(s, specOf(PenType.BALLPOINT))
        val spec = specOf(PenType.BALLPOINT)
        w.forEach { width ->
            assertThat(width).isAtLeast(s.baseWidth * spec.minScale - 0.01f)
            assertThat(width).isAtMost(s.baseWidth * spec.maxScale + 0.01f)
        }
    }

    @Test
    fun `widths are low pass filtered (no jumps between samples)`() {
        // alternating extreme pressures: successive widths change by a bounded amount
        val pts = mutableListOf<Float>()
        for (i in 0 until 200) {
            pts.add(i * 3f)
            pts.add(0f)
            pts.add(if (i % 2 == 0) 1f else 0f) // alternating pressure
            pts.add(i * 8f)
        }
        val s = strokeOf(1, PenType.BALLPOINT, 0, 2f, pts.toFloatArray())
        val w = widths(s, specOf(PenType.BALLPOINT))
        for (i in 1 until w.size) {
            assertThat(kotlin.math.abs(w[i] - w[i - 1])).isLessThan(1.5f)
        }
    }

    @Test
    fun `outline points sit about half-width from centerline`() {
        val s = strokeOf(1, PenType.MARKER, 0xFF000000.toInt(), 8f, floatArrayOf(0f, 0f, 10f, 3f, 20f, 6f, 30f, 9f), pressure = 0.5f)
        val w = widths(s, specOf(PenType.MARKER))
        val out = FloatArray(s.count * 4)
        val n = outlinePolygon(s, w, out)
        assertThat(n).isEqualTo(s.count * 4)
        // Each pair (left_i, right_i) is w[i] apart
        val nPts = n / 2
        for (i in 0 until s.count) {
            val lx = out[2 * i]
            val ly = out[2 * i + 1]
            val rx = out[2 * (nPts - 1 - i)]
            val ry = out[2 * (nPts - 1 - i) + 1]
            val d = hypot(lx - rx, ly - ry)
            assertThat(d).isWithin(0.05f).of(w[i])
            // centerline point should be at the midpoint
            assertThat((lx + rx) / 2f).isWithin(0.01f).of(s.x(i))
            assertThat((ly + ry) / 2f).isWithin(0.01f).of(s.y(i))
        }
    }

    @Test
    fun `single point outline is empty (renderer draws a dot)`() {
        val s = strokeOf(1, PenType.BALLPOINT, 0xFF000000.toInt(), 2f, floatArrayOf(5f, 5f))
        val w = widths(s, specOf(PenType.BALLPOINT))
        assertThat(outlinePolygon(s, w, FloatArray(8))).isEqualTo(0)
    }
}
