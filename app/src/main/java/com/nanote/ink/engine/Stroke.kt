package com.nanote.ink.engine

import kotlin.math.hypot
import kotlin.math.abs

/**
 * The page coordinate space is PDF points (1/72 inch), origin top-left, y down.
 * Using points makes PDF import/export exact (A4 = 595 x 842).
 *
 * A point is packed in [pts] with STRIDE 4: x, y, pressure, tMs.
 * Strokes are IMMUTABLE: every edit produces a new Stroke instance.
 */
const val STRIDE = 4

enum class PenType { BALLPOINT, FOUNTAIN, PENCIL, MARKER, HIGHLIGHTER }

class Stroke(
    val id: Int,
    val pen: PenType,
    val argb: Int,
    val baseWidth: Float,
    val pts: FloatArray
) {
    val count: Int get() = pts.size / STRIDE

    fun x(i: Int): Float = pts[i * STRIDE]
    fun y(i: Int): Float = pts[i * STRIDE + 1]
    fun p(i: Int): Float = pts[i * STRIDE + 2]
    fun t(i: Int): Float = pts[i * STRIDE + 3]

    val bounds: Bounds by lazy {
        val b = Bounds()
        if (count == 0) return@lazy b
        for (i in 0 until count) {
            b.union(x(i), y(i))
        }
        val h = baseWidth * 1.5f
        b.set(
            b.left - h, b.top - h, b.right + h, b.bottom + h
        )
        b
    }

    /**
     * Returns a new stroke with points mapped through [m]. Width scales by
     * sqrt(|det|) so uniform scaling feels natural and shear does not
     * explode stroke weight. Every edit creates a new Stroke (immutability).
     */
    fun transformed(m: Mat3, newId: Int): Stroke {
        val xy = FloatArray(count * 2) { pts[(it / 2) * STRIDE + it % 2] }
        m.mapPoints(xy, 0, count)
        val k = sqrtSafe(abs(m.determinant()))
        val out = pts.copyOf()
        for (i in 0 until count) {
            out[i * STRIDE] = xy[2 * i]
            out[i * STRIDE + 1] = xy[2 * i + 1]
        }
        return Stroke(newId, pen, argb, baseWidth * k, out)
    }

    override fun equals(other: Any?): Boolean =
        other is Stroke && other.id == id && other.pen == pen && other.argb == argb &&
            other.baseWidth == baseWidth && other.pts.contentEquals(pts)

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + pen.hashCode()
        result = 31 * result + argb
        result = 31 * result + baseWidth.toBits()
        result = 31 * result + pts.contentHashCode()
        return result
    }
}

/** Builds synthetic strokes in tests and by the shape tool. */
fun strokeOf(
    id: Int,
    pen: PenType,
    argb: Int,
    width: Float,
    xy: FloatArray,
    pressure: Float = 0.5f,
    tMsStep: Float = 16f
): Stroke {
    val n = xy.size / 2
    val pts = FloatArray(n * STRIDE)
    for (i in 0 until n) {
        pts[i * STRIDE] = xy[2 * i]
        pts[i * STRIDE + 1] = xy[2 * i + 1]
        pts[i * STRIDE + 2] = pressure
        pts[i * STRIDE + 3] = i * tMsStep
    }
    return Stroke(id, pen, argb, width, pts)
}

/** Total polyline length of a stroke in points. */
fun pathLengthPts(s: Stroke): Float {
    var len = 0f
    for (i in 1 until s.count) {
        len += hypot(s.x(i) - s.x(i - 1), s.y(i) - s.y(i - 1))
    }
    return len
}

fun strokeBounds(s: Stroke, out: Bounds): Bounds {
    out.reset()
    for (i in 0 until s.count) out.union(s.x(i), s.y(i))
    return out
}

/** Maximum speed of a stroke in pt/s (used by tuning tools and tests). */
fun maxSpeedPtsPerSec(s: Stroke): Float {
    var max = 0f
    for (i in 1 until s.count) {
        val d = hypot(s.x(i) - s.x(i - 1), s.y(i) - s.y(i - 1))
        val dt = maxOf(s.t(i) - s.t(i - 1), 1f) / 1000f
        max = maxOf(max, d / dt)
    }
    return max
}
