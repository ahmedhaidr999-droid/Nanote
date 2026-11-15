package com.nanote.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * §18 tests — deterministic shape recognition (§14):
 * recognizes clean geometry, rejects handwriting-like scribbles.
 */
class ShapeRecognitionTest {

    private fun circlePoints(cx: Float, cy: Float, r: Float, n: Int = 48): List<StrokePoint> =
        (0..n).map { i ->
            val a = 2.0 * Math.PI * i / n
            StrokePoint(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat(), 0.6f)
        }

    @Test
    fun `straight line is recognized`() {
        val pts = (0..20).map { StrokePoint(it.toFloat() * 5f, 10f + 0.4f * it, 0.5f) }
        val r = ShapeRecognition.recognize(pts)
        assertNotNull("line should be recognized", r)
        assertEquals(ShapeKind.LINE, r!!.kind)
        assertTrue(r.confidence >= ShapeRecognition.MIN_CONFIDENCE)
    }

    @Test
    fun `circle is recognized`() {
        val r = ShapeRecognition.recognize(circlePoints(100f, 100f, 60f))
        assertNotNull("circle should be recognized", r)
        assertEquals(ShapeKind.CIRCLE, r!!.kind)
        assertTrue(r.confidence >= ShapeRecognition.MIN_CONFIDENCE)
    }

    @Test
    fun `ellipse is recognized as ellipse`() {
        val pts = (0..48).map { i ->
            val a = 2.0 * Math.PI * i / 48
            StrokePoint(100f + 80f * cos(a).toFloat(), 100f + 30f * sin(a).toFloat(), 0.6f)
        }
        val r = ShapeRecognition.recognize(pts)
        assertNotNull(r)
        assertEquals(ShapeKind.ELLIPSE, r!!.kind)
    }

    @Test
    fun `rectangle is recognized`() {
        // trace a rectangle with sharp corners
        val pts = ArrayList<StrokePoint>()
        val l = 20f; val t = 20f; val rr = 120f; val b = 90f
        for (i in 0..20) pts.add(StrokePoint(l + (rr - l) * i / 20f, t, 0.6f))
        for (i in 0..14) pts.add(StrokePoint(rr, t + (b - t) * i / 14f, 0.6f))
        for (i in 0..20) pts.add(StrokePoint(rr - (rr - l) * i / 20f, b, 0.6f))
        for (i in 0..14) pts.add(StrokePoint(l, b - (b - t) * i / 14f, 0.6f))
        val r = ShapeRecognition.recognize(pts)
        assertNotNull("rectangle should be recognized", r)
        assertEquals(ShapeKind.RECT, r!!.kind)
    }

    @Test
    fun `triangle is recognized`() {
        val pts = ArrayList<StrokePoint>()
        // (50,10) -> (10,80) -> (90,80) -> back
        for (i in 0..14) {
            val t = i / 14f
            pts.add(StrokePoint(50f + (10f - 50f) * t, 10f + (80f - 10f) * t, 0.6f))
        }
        for (i in 0..14) {
            val t = i / 14f
            pts.add(StrokePoint(10f + (90f - 10f) * t, 80f, 0.6f))
        }
        for (i in 0..14) {
            val t = i / 14f
            pts.add(StrokePoint(90f + (50f - 90f) * t, 80f + (10f - 80f) * t, 0.6f))
        }
        val r = ShapeRecognition.recognize(pts)
        assertNotNull("triangle should be recognized", r)
        assertEquals(ShapeKind.TRIANGLE, r!!.kind)
    }

    @Test
    fun `scribble handwriting is NOT recognized`() {
        // jagged scribble with many turns and no closure
        val pts = ArrayList<StrokePoint>()
        var x = 50f
        for (i in 0..80) {
            x += 2f
            val y = 50f + (if (i % 7 < 3) -12f else 12f) + (i % 5)
            pts.add(StrokePoint(x, y, if (i % 2 == 0) 0.8f else 0.3f))
        }
        assertNull("scribble must not convert to a shape", ShapeRecognition.recognize(pts))
    }

    @Test
    fun `few points are ignored`() {
        val pts = listOf(StrokePoint(0f, 0f), StrokePoint(1f, 1f), StrokePoint(2f, 0f))
        assertNull(ShapeRecognition.recognize(pts))
    }

    @Test
    fun `zigzag letter-like stroke is not a line`() {
        // W-like stroke: same endpoints distance small vs path length
        val pts = ArrayList<StrokePoint>()
        pts.add(StrokePoint(0f, 0f))
        pts.add(StrokePoint(10f, 40f))
        pts.add(StrokePoint(20f, 0f))
        pts.add(StrokePoint(30f, 40f))
        pts.add(StrokePoint(40f, 0f))
        val r = ShapeRecognition.recognize(pts)
        if (r != null) {
            // if anything matches it must NOT be a line with high confidence
            assertTrue(r.kind != ShapeKind.LINE || r.confidence < 0.95f)
        }
    }
}
