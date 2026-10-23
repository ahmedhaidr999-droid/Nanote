package com.nanote.ink.engine

import kotlin.math.hypot

/**
 * Computes the closed outline polygon of a stroke at per-point half-widths.
 *
 * [outlinePoints] works on raw packed arrays (x, y, pressure, tMs) so the
 * live-drawing path can build from the in-progress buffer without allocating
 * a Stroke. The polygon is written into [out] packed as [x0, y0, x1, y1, ...]
 * walking the LEFT side forward then the RIGHT side backward (closed
 * implicitly). The render layer converts it into a filled Path with
 * midpoint-quad smoothing and adds round caps; keeping this in the engine as
 * raw points makes the geometry unit-testable (every outline point sits about
 * w/2 from the centerline).
 *
 * For a single-point stroke the result is 0 and the renderer draws a dot.
 */
fun outlinePoints(
    pts: FloatArray,
    count: Int,
    w: FloatArray,
    out: FloatArray
): Int {
    val n = count
    if (n <= 1 || out.size < n * 4) return 0
    var li = 0
    var ri = n * 4 - 2
    for (i in 0 until n) {
        val a = if (i == 0) 0 else i - 1
        val b = if (i == n - 1) n - 1 else i + 1
        var dx = pts[b * STRIDE] - pts[a * STRIDE]
        var dy = pts[b * STRIDE + 1] - pts[a * STRIDE + 1]
        val len = hypot(dx, dy)
        if (len < 1e-4f) {
            dx = 1f
            dy = 0f
        } else {
            dx /= len
            dy /= len
        }
        val r = w[i] / 2f
        // left normal = (-dy, dx), right normal = (dy, -dx)
        out[li] = pts[i * STRIDE] - dy * r
        out[li + 1] = pts[i * STRIDE + 1] + dx * r
        out[ri] = pts[i * STRIDE] + dy * r
        out[ri + 1] = pts[i * STRIDE + 1] - dx * r
        li += 2
        ri -= 2
    }
    return n * 4
}

/** Stroke convenience overload used by tests. */
fun outlinePolygon(s: Stroke, w: FloatArray, out: FloatArray): Int =
    outlinePoints(s.pts, s.count, w, out)
