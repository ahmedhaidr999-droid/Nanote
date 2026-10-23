package com.nanote.engine

import com.google.common.truth.Truth.assertThat
import com.nanote.ink.engine.Snap
import com.nanote.ink.engine.interiorAnglesSharp
import com.nanote.ink.engine.recognize
import com.nanote.ink.engine.snapRightAngles
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Table-driven shape recognition tests with synthetic strokes (spec 9).
 * Every case is plain geometry — no randomness, no AI.
 */
class ShapeSnapTest {

    private fun sample(fn: (Int) -> Pair<Float, Float>, n: Int): FloatArray {
        val out = FloatArray(n * 2)
        for (i in 0 until n) {
            val (x, y) = fn(i)
            out[2 * i] = x
            out[2 * i + 1] = y
        }
        return out
    }

    /** Adds small deterministic wobble to a perfect shape, like real handwriting. */
    private fun wobble(xy: FloatArray, amp: Float, seed: Int = 7): FloatArray {
        val out = xy.copyOf()
        val n = out.size / 2
        for (i in 0 until n) {
            val w1 = amp * kotlin.math.sin(i * 2.399f + seed)
            val w2 = amp * kotlin.math.sin(i * 1.7f + seed * 2f + 1f)
            out[2 * i] += w1
            out[2 * i + 1] += w2
        }
        return out
    }

    private fun line(t0: Float, t1: Float, x1: Float, y1: Float, x2: Float, y2: Float, steps: Int): FloatArray =
        sample({ i ->
            val t = t0 + (t1 - t0) * i / steps
            Pair(x1 + (x2 - x1) * t, y1 + (y2 - y1) * t)
        }, steps + 1)

    @Test
    fun `horizontal line is recognized as a line`() {
        val pts = line(0f, 1f, 10f, 100f, 200f, 102f, 60)
        val snap = recognize(wobble(pts, 0.5f))
        assertThat(snap).isInstanceOf(Snap.Line::class.java)
        val l = snap as Snap.Line
        assertThat(l.x1).isWithin(2f).of(10f)
        assertThat(l.y1).isWithin(2f).of(100f)
        assertThat(l.x2).isWithin(2f).of(200f)
        assertThat(l.y2).isWithin(2f).of(102f)
    }

    @Test
    fun `diagonal line with a hook is not a line`() {
        val path = mutableListOf<Float>()
        // mostly straight, then a sharp hook at the end
        for (i in 0..40) {
            val v = i * 4f
            path.add(10f + v)
            path.add(10f + v)
        }
        for (i in 41..50) {
            path.add(10f + 40 * 4f + (i - 40) * 2f)
            path.add(10f + 40 * 4f - (i - 40) * 6f)
        }
        val snap = recognize(path.toFloatArray())
        // A hook should either not snap at all or stay a line only when nearly straight;
        // our hook is large -> expect null (open, non-straight)
        assertThat(snap).isNull()
    }

    @Test
    fun `circle is recognized as an ellipse`() {
        val pts = sample({ i ->
            val t = 2.0 * PI * i / 72
            Pair(100f + 60f * cos(t).toFloat() + 0f, 100f + 50f * sin(t).toFloat())
        }, 72)
        val snap = recognize(wobble(pts, 0.8f))
        assertThat(snap).isInstanceOf(Snap.Ellipse::class.java)
        val e = snap as Snap.Ellipse
        assertThat(e.cx).isWithin(3f).of(100f)
        assertThat(e.cy).isWithin(3f).of(100f)
        assertThat(e.rx).isWithin(4f).of(60f)
        assertThat(e.ry).isWithin(4f).of(50f)
    }

    @Test
    fun `wobbly rectangle snaps to a clean right-angled quad`() {
        val path = mutableListOf<Float>()
        fun edge(x1: Float, y1: Float, x2: Float, y2: Float) {
            val n = 30
            for (i in 0 until n) {
                val t = i.toFloat() / n
                path.add(x1 + (x2 - x1) * t)
                path.add(y1 + (y2 - y1) * t)
            }
        }
        edge(20f, 20f, 180f, 22f)
        edge(180f, 22f, 178f, 120f)
        edge(178f, 120f, 22f, 118f)
        edge(22f, 118f, 20f, 20f)
        val snap = recognize(wobble(path.toFloatArray(), 0.6f))
        assertThat(snap).isInstanceOf(Snap.Poly::class.java)
        val poly = snap as Snap.Poly
        val n = poly.xy.size / 2
        assertThat(n).isAtLeast(4)
        assertThat(n).isAtMost(6)

        // interior angles of the snapped poly must be exactly 90 (+- float noise)
        for (i in 0 until n) {
            val px = poly.xy[2 * ((i + n - 1) % n)]; val py = poly.xy[2 * ((i + n - 1) % n) + 1]
            val cx = poly.xy[2 * i]; val cy = poly.xy[2 * i + 1]
            val qx = poly.xy[2 * ((i + 1) % n)]; val qy = poly.xy[2 * ((i + 1) % n) + 1]
            val a1x = cx - px; val a1y = cy - py
            val a2x = qx - cx; val a2y = qy - cy
            val dot = a1x * a2x + a1y * a2y
            val cross = a1x * a2y - a1y * a2x
            val angle = Math.toDegrees(kotlin.math.atan2(cross.toDouble(), dot.toDouble())).toFloat()
            assertThat(angle).isWithin(0.5f).of(90f)
        }
    }

    @Test
    fun `triangle recognized as polygon`() {
        val path = mutableListOf<Float>()
        fun edge(x1: Float, y1: Float, x2: Float, y2: Float) {
            val n = 24
            for (i in 0 until n) {
                val t = i.toFloat() / n
                path.add(x1 + (x2 - x1) * t)
                path.add(y1 + (y2 - y1) * t)
            }
        }
        edge(60f, 20f, 110f, 120f)
        edge(110f, 120f, 10f, 120f)
        edge(10f, 120f, 60f, 20f)
        val snap = recognize(wobble(path.toFloatArray(), 0.5f))
        assertThat(snap).isInstanceOf(Snap.Poly::class.java)
        val poly = snap as Snap.Poly
        assertThat(poly.xy.size / 2).isEqualTo(3)
    }

    @Test
    fun `scribble is not recognized`() {
        val rng = java.util.Random(3)
        val pts = sample({ _ ->
            Pair(100f + rng.nextFloat() * 100f, 100f + rng.nextFloat() * 100f)
        }, 60)
        // random scribble is open AND closed-checks fail
        val snap = recognize(pts)
        assertThat(snap).isNull()
    }

    @Test
    fun `tiny input rejected`() {
        val snap = recognize(floatArrayOf(10f, 10f, 11f, 10.5f, 12f, 10f))
        assertThat(snap).isNull()
    }

    @Test
    fun `interiorAnglesSharp rejects star spikes`() {
        // steep zig-zag: direction vectors at each apex differ by ~168 deg
        val sawtooth = floatArrayOf(
            0f, 0f, 10f, 100f, 20f, 0f, 30f, 100f, 40f, 0f, 0f, 0f
        )
        assertThat(interiorAnglesSharp(sawtooth)).isFalse()
    }

    @Test
    fun `snapRightAngles produces a perfect rotated rectangle`() {
        val wob = floatArrayOf(
            20.5f, 20.2f, 180.3f, 21.8f, 179.1f, 119.7f, 21.4f, 118.3f
        )
        val snapped = snapRightAngles(wob)
        val n = snapped.size / 2
        assertThat(n).isEqualTo(4)

        // every interior angle exactly 90 (+- 0.5 deg of float noise)
        for (i in 0 until n) {
            val px = snapped[2 * ((i + n - 1) % n)]; val py = snapped[2 * ((i + n - 1) % n) + 1]
            val cx = snapped[2 * i]; val cy = snapped[2 * i + 1]
            val qx = snapped[2 * ((i + 1) % n)]; val qy = snapped[2 * ((i + 1) % n) + 1]
            val a1x = cx - px; val a1y = cy - py
            val a2x = qx - cx; val a2y = qy - cy
            val dot = a1x * a2x + a1y * a2y
            val cross = a1x * a2y - a1y * a2x
            val angle = Math.toDegrees(kotlin.math.atan2(cross.toDouble(), dot.toDouble())).toFloat()
            assertThat(angle).isWithin(0.5f).of(90f)
        }

        // opposite sides equal
        fun edgeLen(i: Int): Float {
            val j = (i + 1) % n
            return kotlin.math.hypot(
                (snapped[2 * j] - snapped[2 * i]).toDouble(),
                (snapped[2 * j + 1] - snapped[2 * i + 1]).toDouble()
            ).toFloat()
        }
        assertThat(edgeLen(0)).isWithin(0.6f).of(edgeLen(2))
        assertThat(edgeLen(1)).isWithin(0.6f).of(edgeLen(3))
    }
}
