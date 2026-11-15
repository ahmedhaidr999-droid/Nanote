package com.nanote.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §18 tests — pen drawing math, partial erase geometry preservation,
 * transforms (move/resize/rotate), geometric selection hit testing.
 */
class StrokeOpsTest {

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float, steps: Int = 20): List<StrokePoint> =
        (0..steps).map { i ->
            val t = i.toFloat() / steps
            StrokePoint(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, 0.5f, 0.1f, i * 1_000_000L)
        }

    private fun stroke(points: List<StrokePoint>) = Stroke(
        id = "s1", page = 0, tool = ToolType.PEN,
        color = 0xFF000000, width = 4f, opacity = 100, points = points,
        timestamp = 42L, groupId = null, pressureResponse = 0.6f
    )

    // ---------------------------------------------------------------- erase

    @Test
    fun `partial erase splits stroke and preserves remaining geometry`() {
        val s = stroke(line(0f, 0f, 100f, 0f))
        // eraser capsule over x 45..55, radius 5
        val kept = StrokeOps.splitStrokeAgainstCapsule(s, 50f, 20f, 50f, -20f, 8f)
        assertNotNull(kept)
        assertTrue("expected 2 segments, got ${kept!!.size}", kept.size == 2)
        // untouched geometry preserved bit-exactly (boundary point is appended at the end)
        val first = kept[0]
        val second = kept[1]
        for (i in 0 until first.size - 1) {
            assertEquals(s.points[i].x, first[i].x, 0.01f)
            assertEquals(s.points[i].p, first[i].p, 0f)
        }
        // both ends of the gap stop near the eraser boundary (interpolated: 50±8)
        assertEquals(42f, first.last().x, 0.5f)
        assertEquals(58f, second.first().x, 0.5f)
        // boundary points are interpolated between neighbors (y stays on the line)
        assertEquals(0f, first.last().y, 0.5f)
        assertEquals(0f, second.first().y, 0.5f)
    }

    @Test
    fun `partial erase of the whole stroke removes everything`() {
        val s = stroke(line(0f, 0f, 40f, 0f, steps = 8))
        // capsule covers the entire stroke with big radius
        val kept = StrokeOps.splitStrokeAgainstCapsule(s, 20f, 0f, 20f, 0f, 60f)
        assertNotNull(kept)
        assertTrue(kept!!.isEmpty())
    }

    @Test
    fun `capsule far away leaves stroke untouched`() {
        val s = stroke(line(0f, 0f, 100f, 0f))
        val kept = StrokeOps.splitStrokeAgainstCapsule(s, 500f, 500f, 510f, 500f, 10f)
        assertNull("nothing should change", kept)
    }

    @Test
    fun `point in capsule basics`() {
        assertTrue(StrokeOps.pointInCapsule(5f, 0f, 0f, 0f, 10f, 0f, 3f))
        assertFalse(StrokeOps.pointInCapsule(50f, 50f, 0f, 0f, 10f, 0f, 3f))
    }

    // ------------------------------------------------------------- transforms

    @Test
    fun `translate moves every point`() {
        val s = stroke(line(0f, 0f, 10f, 10f))
        val moved = StrokeOps.translateStroke(s, 5f, -3f)
        assertEquals(5f, moved.points.first().x, 0.001f)
        assertEquals(-3f, moved.points.first().y, 0.001f)
        assertEquals(15f, moved.points.last().x, 0.001f)
        assertEquals(7f, moved.points.last().y, 0.001f)
    }

    @Test
    fun `scale about pivot scales coordinates and width`() {
        val s = stroke(line(0f, 0f, 10f, 0f))
        val scaled = StrokeOps.scaleStroke(s, 0f, 0f, 2f, 2f)
        assertEquals(20f, scaled.points.last().x, 0.001f)
        assertTrue("width should grow", scaled.width > s.width)
    }

    @Test
    fun `rotation by 90 degrees maps axis correctly`() {
        val s = stroke(line(10f, 0f, 20f, 0f))
        val rotated = StrokeOps.rotateStroke(s, 0f, 0f, 90f)
        val last = rotated.points.last()
        assertEquals(0f, last.x, 0.01f)
        assertEquals(20f, last.y, 0.01f)
    }

    @Test
    fun `double rotation round-trips`() {
        val s = stroke(line(37f, 11f, 92f, 44f))
        val once = StrokeOps.rotateStroke(s, 50f, 50f, 33f)
        val back = StrokeOps.rotateStroke(once, 50f, 50f, -33f)
        assertEquals(s.points.last().x, back.points.last().x, 0.02f)
        assertEquals(s.points.last().y, back.points.last().y, 0.02f)
    }

    // ---------------------------------------------------------- hit testing

    @Test
    fun `stroke intersects rect geometrically when crossing it`() {
        val s = stroke(line(-10f, 5f, 30f, 5f)) // crosses x=0..10 at y=5
        val rect = floatArrayOf(0f, 0f, 10f, 10f)
        assertTrue(StrokeOps.strokeIntersectsRect(s, rect))
    }

    @Test
    fun `stroke fully inside rect intersects`() {
        val s = stroke(line(2f, 2f, 8f, 8f))
        val rect = floatArrayOf(0f, 0f, 10f, 10f)
        assertTrue(StrokeOps.strokeIntersectsRect(s, rect))
    }

    @Test
    fun `stroke outside rect does not intersect`() {
        val s = stroke(line(20f, 20f, 30f, 30f))
        val rect = floatArrayOf(0f, 0f, 10f, 10f)
        assertFalse(StrokeOps.strokeIntersectsRect(s, rect))
    }

    @Test
    fun `stroke crossing polygon edge is selected geometrically`() {
        // open triangle-ish polygon around origin
        val poly = floatArrayOf(-10f, -10f, 10f, -10f, 0f, 10f)
        val s = stroke(line(-50f, 0f, 50f, 0f)) // straight through the polygon
        assertTrue(StrokeOps.strokeIntersectsPolygon(s, poly))
    }

    @Test
    fun `segments intersect detects crossings`() {
        assertTrue(StrokeOps.segmentsIntersect(0f, 0f, 10f, 10f, 0f, 10f, 10f, 0f))
        assertFalse(StrokeOps.segmentsIntersect(0f, 0f, 1f, 1f, 5f, 5f, 6f, 6f))
    }

    // -------------------------------------------------------------- smoothing

    @Test
    fun `committed smoothing keeps endpoints near originals`() {
        val noisy = (0..40).map { i ->
            StrokePoint(i.toFloat() * 2f, (if (i % 2 == 0) 5f else -5f), 0.5f)
        }
        val smoothed = StrokeOps.smoothCommitted(noisy, 0.8f)
        // moving-average smoothing pulls points slightly, never far
        assertTrue(Math.abs(smoothed.first().x) < 6f)
        assertTrue(Math.abs(smoothed.last().x - 80f) < 6f)
        // and it actually reduces the oscillation energy
        val energyBefore = noisy.zipWithNext { a, b -> kotlin.math.abs(b.y - a.y) }.sum()
        val energyAfter = smoothed.zipWithNext { a, b -> kotlin.math.abs(b.y - a.y) }.sum()
        assertTrue(energyAfter < energyBefore)
    }

    @Test
    fun `zero smoothing is identity`() {
        val pts = line(0f, 0f, 10f, 10f)
        assertEquals(pts, StrokeOps.smoothCommitted(pts, 0f))
    }

    // ---------------------------------------------------------------- snapping

    @Test
    fun `directional snap locks near-horizontal drags to the axis`() {
        val snapped = StrokeOps.snapDirectional(0f, 0f, 100f, 3f)
        assertEquals(0f, snapped[1], 0.001f)
    }

    @Test
    fun `diagonal drags are not snapped`() {
        val snapped = StrokeOps.snapDirectional(0f, 0f, 100f, 60f)
        assertEquals(60f, snapped[1], 0.001f)
    }

    @Test
    fun `pressure curves map correctly`() {
        assertEquals(0.5f, pressureCurveApply(PressureCurve.LINEAR, 0.5f), 0.001f)
        // smoothstep: p²(3-2p)
        assertEquals(0.15625f, pressureCurveApply(PressureCurve.SOFT, 0.25f), 0.001f)
        assertEquals(1.0f, pressureCurveApply(PressureCurve.FIRM, 1.0f), 0.001f)
        // FIRM rises faster than LINEAR below 1
        assertTrue(pressureCurveApply(PressureCurve.FIRM, 0.25f) > 0.25f)
        // SOFT starts slower than LINEAR
        assertTrue(pressureCurveApply(PressureCurve.SOFT, 0.25f) < 0.25f)
    }
}
