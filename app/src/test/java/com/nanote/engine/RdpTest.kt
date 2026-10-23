package com.nanote.engine

import com.google.common.truth.Truth.assertThat
import com.nanote.ink.engine.STRIDE
import com.nanote.ink.engine.simplify
import com.nanote.ink.engine.rdpXY
import org.junit.Test

class RdpTest {

    private fun packed(vararg xy: Float): FloatArray {
        val pts = FloatArray(xy.size / 2 * STRIDE)
        for (i in 0 until xy.size / 2) {
            pts[i * STRIDE] = xy[2 * i]
            pts[i * STRIDE + 1] = xy[2 * i + 1]
        }
        return pts
    }

    @Test
    fun `straight line reduces to two points`() {
        val src = packed(*Array(50) { i -> floatArrayOf(i * 2f, i * 4f) }.flatMap { it.toList() }.toFloatArray())
        val out = simplify(src, 0.15f)
        assertThat(out.size).isEqualTo(2 * STRIDE)
    }

    @Test
    fun `endpoints are always kept`() {
        val src = packed(0f, 0f, 5f, 3f, 10f, 0f, 15f, 8f, 20f, 2f)
        val out = simplify(src, 0.15f)
        assertThat(out[0]).isEqualTo(0f)
        assertThat(out[1]).isEqualTo(0f)
        assertThat(out[out.size - STRIDE]).isEqualTo(20f)
        assertThat(out[out.size - STRIDE + 1]).isEqualTo(2f)
    }

    @Test
    fun `deviations beyond epsilon are kept`() {
        val src = packed(0f, 0f, 10f, 10f, 20f, 0f, 30f, 10f)
        val out = simplify(src, 0.5f)
        // the 10,10 apex is 7+ points away from the chord -> must survive
        assertThat(out.size).isAtLeast(3 * STRIDE)
    }

    @Test
    fun `tiny epsilon keeps everything nearly collinear stays collinear`() {
        val src = packed(0f, 0f, 1f, 0.001f, 2f, -0.001f, 3f, 0f)
        val out = simplify(src, 0.5f)
        assertThat(out.size).isEqualTo(2 * STRIDE)
    }

    @Test
    fun `rdpXY returns corners of a rectangle`() {
        val xy = mutableListOf<Float>()
        // wobbly rectangle starting top-left, clockwise, ~60 samples per side
        fun seg(x0: Float, y0: Float, x1: Float, y1: Float, wob: Float) {
            val n = 60
            for (i in 0 until n) {
                val t = i.toFloat() / n
                xy.add(x0 + (x1 - x0) * t + wob)
                xy.add(y0 + (y1 - y0) * t - wob)
            }
        }
        seg(10f, 10f, 110f, 10f, 0f)
        seg(110f, 10f, 110f, 90f, 0f)
        seg(110f, 90f, 10f, 90f, 0f)
        seg(10f, 90f, 10f, 10f, 0f)
        val corners = rdpXY(xy.toFloatArray(), 3f)
        assertThat(corners.size / 2).isAtLeast(4)
    }
}
