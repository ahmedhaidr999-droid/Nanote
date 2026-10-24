package com.nanote.engine

import com.google.common.truth.Truth.assertThat
import com.nanote.ink.engine.Mat3
import com.nanote.ink.engine.ViewTransform
import org.junit.Test

class ViewTransformTest {

    @Test
    fun `page to screen to page round trip`() {
        val vt = ViewTransform()
        vt.zoom(3.7f, 100f, 200f)
        vt.pan(40f, -20f)
        vt.zoom(0.9f, 10f, 10f)

        val out = FloatArray(2)
        val px = 123.4f
        val py = 567.8f
        vt.toScreen(px, py, out)
        assertThat(vt.toPage(out[0], out[1], out)).isTrue()
        assertThat(out[0]).isWithin(1e-2f).of(px)
        assertThat(out[1]).isWithin(1e-2f).of(py)
    }

    @Test
    fun `zoom keeps focus point fixed`() {
        val vt = ViewTransform()
        vt.fitToPage(595f, 842f, 1000f, 2000f)
        val fx = 333f
        val fy = 444f
        val before = FloatArray(2)
        vt.toScreen(fx, fy, before)
        vt.zoom(1.25f, before[0], before[1])
        val after = FloatArray(2)
        vt.toScreen(fx, fy, after)
        assertThat(after[0]).isWithin(0.01f).of(before[0])
        assertThat(after[1]).isWithin(0.01f).of(before[1])
    }

    @Test
    fun `scale clamped to bounds`() {
        val vt = ViewTransform()
        vt.zoom(1000f, 0f, 0f)
        assertThat(vt.scale).isAtMost(8.001f)
        vt.zoom(0.0001f, 0f, 0f)
        assertThat(vt.scale).isAtLeast(0.2499f)
    }

    @Test
    fun `clamp keeps at least quarter of page visible`() {
        val vt = ViewTransform()
        val pageW = 595f
        val pageH = 842f
        val vw = 1080f
        val vh = 1920f
        vt.fitToPage(pageW, pageH, vw, vh)
        vt.setScale(4f, 0f, 0f)
        // pan far away then clamp
        vt.pan(-5000f, -5000f)
        vt.pan(9000f, 9000f)
        vt.clampPan(pageW, pageH, vw, vh)

        val pw = pageW * vt.scale
        val ph = pageH * vt.scale
        val visibleW = minOf(pw + vt.transX, vw) - maxOf(vt.transX, 0f)
        val visibleH = minOf(ph + vt.transY, vh) - maxOf(vt.transY, 0f)
        val minVisW = minOf(vw, pw) * 0.25f
        val minVisH = minOf(vh, ph) * 0.25f
        assertThat(visibleW).isAtLeast(minVisW - 1f)
        assertThat(visibleH).isAtLeast(minVisH - 1f)
    }

    @Test
    fun `fitToWidth matches viewport width`() {
        val vt = ViewTransform()
        vt.fitToWidth(595f, 842f, 1080f, 1920f)
        assertThat(vt.scale).isWithin(1e-4f).of(1080f / 595f)
        assertThat(vt.transX).isWithin(1e-4f).of(0f)
        // page vertically centered
        assertThat(vt.transY).isWithin(1f).of((1920f - 842f * vt.scale) / 2f)
    }

    @Test
    fun `fitToPage fits the whole page`() {
        val vt = ViewTransform()
        vt.fitToPage(595f, 842f, 1080f, 1920f)
        val out = FloatArray(2)
        vt.toScreen(595f, 842f, out)
        assertThat(out[0]).isAtMost(1080.001f)
        assertThat(out[1]).isAtMost(1920.001f)
        assertThat(out[0]).isAtLeast(-0.001f)
        assertThat(out[1]).isAtLeast(-0.001f)
    }

    @Test
    fun `matrix values stay affine`() {
        val vt = ViewTransform()
        vt.zoom(2f, 1f, 1f)
        vt.pan(3f, 4f)
        vt.zoom(0.5f, 9f, 9f)
        assertThat(vt.m.isAffine).isTrue()
        // no skew from zoom+pan
        assertThat(vt.m.skewX).isWithin(1e-5f).of(0f)
        assertThat(vt.m.v[1]).isEqualTo(0f)
        assertThat(vt.m.v[3]).isEqualTo(0f)
    }

    // ------------------------------------- v1.1: fit ALL PDF page sizes

    @Test
    fun `A0 poster page fits width without the old 0point25 clamp`() {
        val vt = ViewTransform()
        // A0 poster = 3370 x 2384 PDF points, landscape tablet viewport
        vt.fitToWidth(3370f, 2384f, 2560f, 1600f)
        assertThat(vt.scale).isWithin(1e-4f).of(2560f / 3370f) // 0.76 > 0.25 anyway
    }

    @Test
    fun `giant CAD sheet fits even below the default zoom floor`() {
        val vt = ViewTransform()
        vt.fitToWidth(20000f, 9000f, 2560f, 1600f) // fit = 0.128 < 0.25
        assertThat(vt.scale).isWithin(1e-4f).of(2560f / 20000f)
        assertThat(vt.oversizedPage).isTrue()
        // zooming out further stays possible down to the adaptive floor
        vt.zoom(0.5f, 0f, 0f)
        assertThat(vt.scale).isAtMost(2560f / 20000f + 1e-4f)
    }

    @Test
    fun `giant page fitToPage shows the whole sheet`() {
        val vt = ViewTransform()
        vt.fitToPage(20000f, 9000f, 2560f, 1600f)
        val out = FloatArray(2)
        vt.toScreen(20000f, 9000f, out)
        assertThat(out[0]).isAtMost(2560.001f)
        assertThat(out[1]).isAtMost(1600.001f)
        assertThat(out[0]).isAtLeast(-0.001f)
        assertThat(out[1]).isAtLeast(-0.001f)
    }

    @Test
    fun `small pages keep the default zoom floor`() {
        val vt = ViewTransform()
        vt.fitToPage(300f, 200f, 2560f, 1600f)
        assertThat(vt.oversizedPage).isFalse()
        vt.zoom(0.0001f, 0f, 0f)
        assertThat(vt.scale).isAtLeast(0.2499f)
    }

}