package com.nanote.engine

import com.google.common.truth.Truth.assertThat
import com.nanote.ink.engine.PenType
import com.nanote.ink.engine.eraserHits
import com.nanote.ink.engine.pixelErasePath
import com.nanote.ink.engine.strokeOf
import org.junit.Test

class EraserTest {

    private fun horizontalStroke(x0: Float = 0f, x1: Float = 100f, y: Float = 50f): List<Float> {
        val xs = mutableListOf<Float>()
        val n = 40
        for (i in 0..n) {
            xs.add(x0 + (x1 - x0) * i / n)
            xs.add(y)
        }
        return xs
    }

    @Test
    fun `whole stroke eraser hits crossing line`() {
        val s = strokeOf(1, PenType.BALLPOINT, 0xFF000000.toInt(), 2f, horizontalStroke().toFloatArray())
        // eraser sweep crossing the line at y=50
        assertThat(eraserHits(50f, 0f, 50f, 100f, 5f, s)).isTrue()
    }

    @Test
    fun `whole stroke eraser misses far away`() {
        val s = strokeOf(1, PenType.BALLPOINT, 0xFF000000.toInt(), 2f, horizontalStroke().toFloatArray())
        assertThat(eraserHits(50f, 80f, 60f, 90f, 5f, s)).isFalse()
    }

    @Test
    fun `radius adds to stroke half width`() {
        val s = strokeOf(1, PenType.BALLPOINT, 0xFF000000.toInt(), 6f, horizontalStroke().toFloatArray())
        // center 8pt away, stroke halfwidth 3 + eraser 5 = reach 8 -> hits
        assertThat(eraserHits(50f, 58f, 50f, 58f, 5f, s)).isTrue()
        // 10pt away -> miss
        assertThat(eraserHits(50f, 60f, 50f, 60f, 5f, s)).isFalse()
    }

    @Test
    fun `single point stroke erased when circle covers it`() {
        val s = strokeOf(1, PenType.BALLPOINT, 0xFF000000.toInt(), 2f, floatArrayOf(50f, 50f))
        assertThat(eraserHits(48f, 48f, 52f, 52f, 4f, s)).isTrue()
        assertThat(pixelErasePath(s, floatArrayOf(50f, 50f), 4f) { 100 }).isEmpty()
    }

    @Test
    fun `pixel erase splits stroke into two fragments`() {
        val s = strokeOf(1, PenType.BALLPOINT, 0xFF000000.toInt(), 2f, horizontalStroke().toFloatArray())
        var nextId = 10
        val fragments = pixelErasePath(s, floatArrayOf(50f, 50f), 5f) { nextId++ }

        assertThat(fragments).isNotNull()
        assertThat(fragments!!.size).isEqualTo(2)
        val left = fragments[0]
        val right = fragments[1]
        assertThat(left.count).isGreaterThan(1)
        assertThat(right.count).isGreaterThan(1)
        assertThat(left.id).isNotEqualTo(s.id)
        assertThat(left.pen).isEqualTo(s.pen)
        assertThat(left.argb).isEqualTo(s.argb)
        assertThat(left.baseWidth).isEqualTo(s.baseWidth)
        // fragments are ordered left-to-right (z order of a horizontal stroke)
        assertThat(left.x(0)).isLessThan(right.x(left.count - 1))
    }

    @Test
    fun `pixel erase untouched stroke returns null`() {
        val s = strokeOf(1, PenType.BALLPOINT, 0xFF000000.toInt(), 2f, horizontalStroke().toFloatArray())
        assertThat(pixelErasePath(s, floatArrayOf(50f, 90f), 5f) { 10 }).isNull()
    }

    @Test
    fun `pixel erase circle bigger than stroke removes it entirely`() {
        val s = strokeOf(1, PenType.BALLPOINT, 0xFF000000.toInt(), 2f, horizontalStroke(40f, 60f).toFloatArray())
        val res = pixelErasePath(s, floatArrayOf(50f, 50f), 30f) { 10 }
        assertThat(res).isEmpty()
    }

    @Test
    fun `pixel erase path of several circles equals union`() {
        val s = strokeOf(1, PenType.BALLPOINT, 0xFF000000.toInt(), 2f, horizontalStroke().toFloatArray())
        var nextId = 10
        val both = pixelErasePath(s, floatArrayOf(20f, 50f, 80f, 50f), 6f) { nextId++ }
        assertThat(both).isNotNull()
        assertThat(both!!.size).isEqualTo(3) // [0,20) [20+,80+) wait: two cuts -> three fragments
    }
}
