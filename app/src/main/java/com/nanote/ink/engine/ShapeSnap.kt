package com.nanote.ink.engine

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geometry-only shape recognition (NO AI/ML — plain math as required).
 *
 * Trigger at the call site: the pen rests ~500 ms at the end of a stroke
 * (less than 2 pt of movement); the stroke is then replaced by the ideal
 * shape as one undoable ReplaceStrokes command.
 */
sealed interface Snap {
    data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : Snap
    data class Ellipse(val cx: Float, val cy: Float, val rx: Float, val ry: Float) : Snap

    /** Closed polygon: triangle, rectangle, diamond ... packed x,y pairs. */
    class Poly(val xy: FloatArray) : Snap {
        override fun equals(other: Any?): Boolean =
            other is Poly && other.xy.contentEquals(xy)
        override fun hashCode(): Int = xy.contentHashCode()
    }
}

/**
 * Minimum stroke length (points) and point count before recognition runs.
 * Same thresholds used by the table-driven unit tests.
 */
const val SNAP_MIN_DIAG_PT = 24f
const val SNAP_MIN_POINTS = 8

fun recognize(xy: FloatArray): Snap? {
    val n = xy.size / 2
    if (n < SNAP_MIN_POINTS) return null
    val box = Bounds()
    if (!boundsOf(xy, box)) return null
    val diag = hypot(box.width, box.height)
    if (diag < SNAP_MIN_DIAG_PT) return null

    val len = pathLength(xy)
    val gap = hypot(xy[0] - xy[2 * n - 2], xy[1] - xy[2 * n - 1])
    var corners = rdpXY(xy, 0.03f * diag) // corner candidates

    // Hand-drawn loops rarely end EXACTLY at the start point; RDP then keeps a
    // near-duplicate closing corner whose junction angle is degenerate and
    // would fail the polygon test. The leftover is at most one sampled segment,
    // so the merge tolerance covers the average step size too.
    if (corners.size >= 8) {
        val avgSeg = if (n > 1) len / (n - 1) else 0f
        val mergeTol = maxOf(0.02f * diag, 1.5f * avgSeg)
        val dx = corners[0] - corners[corners.size - 2]
        val dy = corners[1] - corners[corners.size - 1]
        if (hypot(dx, dy) < mergeTol) {
            corners = corners.copyOf(corners.size - 2)
        }
    }

    if (gap > 0.2f * len) {
        // Open stroke: a straight line?
        return if (corners.size / 2 == 2 || gap / len > 0.96f) {
            Snap.Line(xy[0], xy[1], xy[2 * n - 2], xy[2 * n - 1])
        } else null
    }

    val k = corners.size / 2 // deduped closed-polygon vertex count
    if (k in 3..6 && interiorAnglesSharp(corners)) {
        return Snap.Poly(snapRightAngles(corners))
    }

    // Ellipse test: ((x-cx)/rx)^2 + ((y-cy)/ry)^2 is about 1 for every point.
    val cx = box.centerX
    val cy = box.centerY
    val rx = box.width / 2f
    val ry = box.height / 2f
    if (rx < 1f || ry < 1f) return null
    var s1 = 0f
    var s2 = 0f
    for (i in 0 until n) {
        val d = hypot((xy[2 * i] - cx) / rx, (xy[2 * i + 1] - cy) / ry)
        s1 += d
        s2 += d * d
    }
    val mean = s1 / n
    val sd = sqrt(maxOf(0f, s2 / n - mean * mean))
    return if (abs(mean - 1f) < 0.12f && sd < 0.12f) Snap.Ellipse(cx, cy, rx, ry) else null
}

/**
 * Every interior angle must sit between 45 and 150 degrees for the closed
 * stroke to count as a polygon (rejects scribbles and star-like loops).
 * Consecutive duplicate corners (closed paths list the first corner again
 * at the end) are skipped by stepping to the nearest DISTINCT neighbor.
 */
fun interiorAnglesSharp(corners: FloatArray): Boolean {
    val n = corners.size / 2
    if (n < 3) return false
    // distinct vertices only
    val pts = ArrayList<Float>(n * 2)
    for (i in 0 until n) {
        if (pts.size >= 2 &&
            pts[pts.size - 2] == corners[2 * i] &&
            pts[pts.size - 1] == corners[2 * i + 1]
        ) continue
        pts.add(corners[2 * i]); pts.add(corners[2 * i + 1])
    }
    // drop the closing duplicate too
    if (pts.size >= 6 &&
        pts[0] == pts[pts.size - 2] &&
        pts[1] == pts[pts.size - 1]
    ) {
        pts.removeAt(pts.size - 1)
        pts.removeAt(pts.size - 1)
    }
    val m = pts.size / 2
    if (m < 3) return false
    for (i in 0 until m) {
        val px = pts[2 * ((i + m - 1) % m)]
        val py = pts[2 * ((i + m - 1) % m) + 1]
        val cx = pts[2 * i]
        val cy = pts[2 * i + 1]
        val qx = pts[2 * ((i + 1) % m)]
        val qy = pts[2 * ((i + 1) % m) + 1]
        val a1x = cx - px; val a1y = cy - py
        val a2x = qx - cx; val a2y = qy - cy
        val l1 = hypot(a1x, a1y); val l2 = hypot(a2x, a2y)
        if (l1 < 1e-4f || l2 < 1e-4f) return false
        val dot = (a1x * a2x + a1y * a2y) / (l1 * l2)
        val angleDeg = Math.toDegrees(acos(dot.coerceIn(-1f, 1f)).toDouble()).toFloat()
        if (angleDeg < 45f || angleDeg > 150f) return false
    }
    return true
}

/**
 * Corners within 12 degrees of 90 snap to exactly 90 degrees.
 * Strategy: find the dominant edge orientation theta, rotate the corners by
 * -theta, quantize coordinates that are near-equal so near-right angles
 * become exactly right, rotate back. Produces clean rectangles, diamonds and
 * triangles from slightly wobbly hand input.
 */
fun snapRightAngles(corners: FloatArray): FloatArray {
    val n = corners.size / 2
    if (n < 3) return corners

    // Dominant orientation from the longest edge.
    var bestLen = 0f
    var theta = 0f
    for (i in 0 until n) {
        val j = (i + 1) % n
        val ax = corners[2 * i]; val ay = corners[2 * i + 1]
        val bx = corners[2 * j]; val by = corners[2 * j + 1]
        val len = hypot(bx - ax, by - ay)
        if (len > bestLen) {
            bestLen = len
            theta = atan2(by - ay, bx - ax)
        }
    }
    val snapTolDeg = 12f

    // Rotate into axis-aligned frame.
    val rot = FloatArray(n * 2)
    val cosT = cos(-theta)
    val sinT = sin(-theta)
    for (i in 0 until n) {
        val x = corners[2 * i]; val y = corners[2 * i + 1]
        rot[2 * i] = x * cosT - y * sinT
        rot[2 * i + 1] = x * sinT + y * cosT
    }

    // Quantize near-equal x and y values (tolerance grows with diagonal).
    val box = Bounds()
    boundsOf(rot, box)
    val tol = 0.12f * maxOf(box.width, box.height)
    quantizeNear(rot, 0, tol)
    quantizeNear(rot, 1, tol)

    // Any interior angle now close to 90 gets forced to exactly 90.
    for (i in 0 until n) {
        val j = (i + 1) % n
        val k = (i + 2) % n
        val px = rot[2 * i]; val py = rot[2 * i + 1]
        val cx = rot[2 * j]; val cy = rot[2 * j + 1]
        val qx = rot[2 * k]; val qy = rot[2 * k + 1]
        val a1x = cx - px; val a1y = cy - py
        val a2x = qx - cx; val a2y = qy - cy
        val l1 = hypot(a1x, a1y); val l2 = hypot(a2x, a2y)
        if (l1 < 1e-4f || l2 < 1e-4f) continue
        val dot = (a1x * a2x + a1y * a2y) / (l1 * l2)
        val angleDeg = Math.toDegrees(acos(dot.coerceIn(-1f, 1f)).toDouble()).toFloat()
        if (abs(angleDeg - 90f) <= snapTolDeg) {
            // Perpendicular foot: move q onto the line through c perpendicular to (p->c).
            val ux = a1x / l1; val uy = a1y / l1
            val nx = -uy; val ny = ux
            val ex = qx - cx; val ey = qy - cy
            val t = (ex * nx + ey * ny) / (nx * nx + ny * ny)
            rot[2 * k] = cx + nx * t
            rot[2 * k + 1] = cy + ny * t
        }
    }

    // Rotate back.
    val out = FloatArray(n * 2)
    val cosB = cos(theta)
    val sinB = sin(theta)
    for (i in 0 until n) {
        val x = rot[2 * i]; val y = rot[2 * i + 1]
        out[2 * i] = x * cosB - y * sinB
        out[2 * i + 1] = x * sinB + y * cosB
    }
    return out
}

/** Near-equal coordinates (within [tol] of the running group mean) snap to their mean. */
private fun quantizeNear(arr: FloatArray, pairOffset: Int, tol: Float) {
    val n = arr.size / 2
    val assigned = BooleanArray(n)
    for (i in 0 until n) {
        if (assigned[i]) continue
        assigned[i] = true
        var sum = arr[2 * i + pairOffset]
        val group = ArrayList<Int>(4)
        group.add(i)
        var grew = true
        while (grew) {
            grew = false
            for (j in 0 until n) {
                if (!assigned[j] && abs(arr[2 * j + pairOffset] - sum / group.size) <= tol) {
                    assigned[j] = true
                    group.add(j)
                    sum += arr[2 * j + pairOffset]
                    grew = true
                }
            }
        }
        val mean = sum / group.size
        for (j in group) arr[2 * j + pairOffset] = mean
    }
}

// ---------------------------------------------------------------------------
// Ideal-shape -> Stroke builders. Corner points are DUPLICATED so the render
// layer's midpoint-quad smoothing produces crisp corners; edges are sampled
// every ~4 pt so outline generation has dense input.
// ---------------------------------------------------------------------------

private const val SHAPE_STEP_PT = 4f

private fun densePolyline(points: FloatArray, out: ArrayList<Float>) {
    val n = points.size / 2
    for (i in 1 until n) {
        addDenseSegment(out, points[2 * (i - 1)], points[2 * (i - 1) + 1], points[2 * i], points[2 * i + 1])
    }
}

private fun addDenseSegment(out: ArrayList<Float>, x0: Float, y0: Float, x1: Float, y1: Float) {
    val len = hypot(x1 - x0, y1 - y0)
    val steps = maxOf(1, (len / SHAPE_STEP_PT).toInt())
    for (s in 0..steps) {
        val t = s.toFloat() / steps
        out.add(x0 + (x1 - x0) * t)
        out.add(y0 + (y1 - y0) * t)
    }
}

fun buildLineStroke(x1: Float, y1: Float, x2: Float, y2: Float, id: Int, pen: PenType, argb: Int, w: Float, t0: Float): Stroke {
    val pts = ArrayList<Float>()
    addDenseSegment(pts, x1, y1, x2, y2)
    return packedToStroke(id, pen, argb, w, t0, pts)
}

fun buildPolyStroke(corners: FloatArray, id: Int, pen: PenType, argb: Int, w: Float, t0: Float): Stroke {
    val pts = ArrayList<Float>()
    val n = corners.size / 2
    pts.add(corners[0]); pts.add(corners[1])
    for (i in 1 until n) {
        // duplicate the corner to make a crisp joint
        pts.add(corners[2 * i]); pts.add(corners[2 * i + 1])
        pts.add(corners[2 * i]); pts.add(corners[2 * i + 1])
        addDenseSegment(pts, corners[2 * (i - 1)], corners[2 * (i - 1) + 1], corners[2 * i], corners[2 * i + 1])
    }
    // close
    pts.add(corners[0]); pts.add(corners[1])
    pts.add(corners[0]); pts.add(corners[1])
    addDenseSegment(pts, corners[2 * (n - 1)], corners[2 * (n - 1) + 1], corners[0], corners[1])
    return packedToStroke(id, pen, argb, w, t0, pts)
}

fun buildEllipseStroke(cx: Float, cy: Float, rx: Float, ry: Float, id: Int, pen: PenType, argb: Int, w: Float, t0: Float, steps: Int = 64): Stroke {
    val pts = ArrayList<Float>(steps * 2 + 4)
    for (i in 0..steps) {
        val t = 2.0 * Math.PI * i / steps
        pts.add((cx + rx * cos(t)).toFloat())
        pts.add((cy + ry * sin(t)).toFloat())
    }
    return packedToStroke(id, pen, argb, w, t0, pts)
}

/**
 * Arrow as a single stroke: shaft, then head lines with duplicated corner
 * points so joints stay sharp.
 */
fun buildArrowStroke(x1: Float, y1: Float, x2: Float, y2: Float, id: Int, pen: PenType, argb: Int, w: Float, t0: Float, headLen: Float = 18f): Stroke {
    val dx = x2 - x1
    val dy = y2 - y1
    val len = hypot(dx, dy)
    if (len < 1e-3f) return buildLineStroke(x1, y1, x2, y2, id, pen, argb, w, t0)
    val ux = dx / len; val uy = dy / len
    val hl = minOf(headLen, len * 0.3f)
    val hx = x2 - ux * hl
    val hy = y2 - uy * hl
    val px = -uy; val py = ux
    val pts = ArrayList<Float>()
    addDenseSegment(pts, x1, y1, x2, y2)
    pts.add(x2); pts.add(y2)              // corner
    pts.add(hx + px * hl * 0.5f); pts.add(hy + py * hl * 0.5f) // head left tip
    pts.add(hx + px * hl * 0.5f); pts.add(hy + py * hl * 0.5f) // duplicate: crisp corner
    addDenseSegment(pts, x2, y2, hx + px * hl * 0.5f, hy + py * hl * 0.5f)
    pts.add(x2); pts.add(y2)              // corner
    pts.add(hx - px * hl * 0.5f); pts.add(hy - py * hl * 0.5f) // head right tip
    addDenseSegment(pts, x2, y2, hx - px * hl * 0.5f, hy - py * hl * 0.5f)
    return packedToStroke(id, pen, argb, w, t0, pts)
}

fun buildTriangleStroke(x1: Float, y1: Float, x2: Float, y2: Float, id: Int, pen: PenType, argb: Int, w: Float, t0: Float): Stroke {
    val cx = (x1 + x2) / 2f
    val corners = floatArrayOf(cx, y1, x2, y2, x1, y2)
    return buildPolyStroke(corners, id, pen, argb, w, t0)
}

fun buildRectStroke(x1: Float, y1: Float, x2: Float, y2: Float, id: Int, pen: PenType, argb: Int, w: Float, t0: Float): Stroke {
    val corners = floatArrayOf(x1, y1, x2, y1, x2, y2, x1, y2)
    return buildPolyStroke(corners, id, pen, argb, w, t0)
}

fun buildDiamondStroke(x1: Float, y1: Float, x2: Float, y2: Float, id: Int, pen: PenType, argb: Int, w: Float, t0: Float): Stroke {
    val cx = (x1 + x2) / 2f
    val cy = (y1 + y2) / 2f
    val corners = floatArrayOf(cx, y1, x2, cy, cx, y2, x1, cy)
    return buildPolyStroke(corners, id, pen, argb, w, t0)
}

fun buildStarStroke(x1: Float, y1: Float, x2: Float, y2: Float, id: Int, pen: PenType, argb: Int, w: Float, t0: Float, points: Int = 5): Stroke {
    val cx = (x1 + x2) / 2f
    val cy = (y1 + y2) / 2f
    val rx = abs(x2 - x1) / 2f
    val ry = abs(y2 - y1) / 2f
    val pts = ArrayList<Float>()
    val p = maxOf(3, points)
    for (i in 0 until p * 2) {
        val t = Math.PI * i / p - Math.PI / 2.0
        val k = if (i % 2 == 0) 1f else 0.4f
        val vx = (cx + rx * k * cos(t)).toFloat()
        val vy = (cy + ry * k * sin(t)).toFloat()
        pts.add(vx)
        pts.add(vy)
        pts.add(vx) // duplicate corner for a crisp joint
        pts.add(vy)
    }
    pts.add(pts[0]); pts.add(pts[1])
    return packedToStroke(id, pen, argb, w, t0, pts)
}

private fun packedToStroke(id: Int, pen: PenType, argb: Int, w: Float, t0: Float, xy: ArrayList<Float>): Stroke {
    val n = xy.size / 2
    val pts = FloatArray(n * STRIDE)
    for (i in 0 until n) {
        pts[i * STRIDE] = xy[2 * i]
        pts[i * STRIDE + 1] = xy[2 * i + 1]
        pts[i * STRIDE + 2] = 0.6f
        pts[i * STRIDE + 3] = t0 + i * 4f
    }
    return Stroke(id, pen, argb, w, pts)
}


