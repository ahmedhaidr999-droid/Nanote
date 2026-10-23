package com.nanote.engine

import com.google.common.truth.Truth.assertThat
import com.nanote.ink.engine.Bounds
import com.nanote.ink.engine.Mat3
import com.nanote.ink.engine.distPointSegSq
import com.nanote.ink.engine.pointInPolygon
import org.junit.Test

class GeomTest {

    @Test
    fun `matrix round trip maps point back`() {
        val m = Mat3()
        m.postTranslate(100f, 50f)
        m.postScale(2.5f, 2.5f, 10f, 20f)
        m.postRotate(30f, 5f, 5f)

        val inv = Mat3()
        assertThat(m.invert(inv)).isTrue()

        val p = floatArrayOf(37f, 21f)
        val orig = p.copyOf()
        m.mapPoints(p, 0, 1)
        assertThat(inv.mapX(p[0], p[1])).isWithin(1e-3f).of(orig[0])
        // second coordinate via inverse mapping the mapped point
        val back = p.copyOf()
        inv.mapPoints(back, 0, 1)
        assertThat(back[0]).isWithin(1e-3f).of(orig[0])
        assertThat(back[1]).isWithin(1e-3f).of(orig[1])
    }

    @Test
    fun `postTranslate accumulates`() {
        val m = Mat3()
        m.postTranslate(10f, 20f)
        m.postTranslate(-5f, 3f)
        assertThat(m.transX).isEqualTo(5f)
        assertThat(m.transY).isEqualTo(23f)
    }

    @Test
    fun `zoom around focus keeps focus fixed`() {
        val m = Mat3()
        m.postTranslate(50f, 50f)
        // Screen-space focus: the point S is invariant, i.e. M'(M^-1(S)) == S.
        val fx = 63f
        val fy = 67f
        val inv = Mat3()
        assertThat(m.invert(inv)).isTrue()
        val q = floatArrayOf(fx, fy)
        inv.mapPoints(q, 0, 1) // q = page point that maps to the screen focus
        m.postScale(2f, 2f, fx, fy)
        val after = floatArrayOf(q[0], q[1])
        m.mapPoints(after, 0, 1)
        assertThat(after[0]).isWithin(0.01f).of(fx)
        assertThat(after[1]).isWithin(0.01f).of(fy)
    }

    @Test
    fun `inverse of singular matrix fails`() {
        val m = Mat3()
        m.v[0] = 0f; m.v[4] = 0f // scale 0 -> singular
        assertThat(m.invert(Mat3())).isFalse()
    }

    @Test
    fun `distPointSegSq on endpoints and middle`() {
        // point at segment middle
        assertThat(distPointSegSq(5f, 5f, 0f, 0f, 10f, 10f)).isWithin(1e-4f).of(0f)
        // perpendicular distance 1 -> squared 1
        assertThat(distPointSegSq(5f, 1f, 0f, 0f, 10f, 0f)).isWithin(1e-4f).of(1f)
        // outside segment: closest endpoint
        assertThat(distPointSegSq(12f, 0f, 0f, 0f, 10f, 0f)).isWithin(1e-4f).of(4f)
        // degenerate segment (point)
        assertThat(distPointSegSq(3f, 4f, 0f, 0f, 0f, 0f)).isWithin(1e-4f).of(25f)
    }

    @Test
    fun `pointInPolygon basic cases`() {
        val square = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)
        assertThat(pointInPolygon(5f, 5f, square)).isTrue()
        assertThat(pointInPolygon(-1f, 5f, square)).isFalse()
        assertThat(pointInPolygon(15f, 15f, square)).isFalse()

        // square with a triangular notch cut into the top edge, dipping to (5,5)
        val concave = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 5f, 5f, 0f, 10f)
        assertThat(pointInPolygon(5f, 3f, concave)).isTrue()  // below the notch
        assertThat(pointInPolygon(6f, 8f, concave)).isFalse()  // inside the notch
    }

    @Test
    fun `bounds union and intersect`() {
        val a = Bounds()
        a.set(0f, 0f, 10f, 10f)
        a.union(20f, 15f)
        assertThat(a.right).isEqualTo(20f)
        assertThat(a.bottom).isEqualTo(15f)
        val b = Bounds()
        b.set(5f, 5f, 30f, 30f)
        assertThat(a.intersects(b)).isTrue()
        b.set(11f, 0f, 20f, 5f)
        assertThat(a.intersects(b)).isTrue()
        b.set(25f, 0f, 30f, 5f)
        assertThat(a.intersects(b)).isFalse()
    }
}
