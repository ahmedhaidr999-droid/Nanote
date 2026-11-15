package com.nanote.core.model

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Pure-Kotlin handwriting engine operations (Part 2).
 *
 * Everything here is deterministic, side-effect free and JVM-testable:
 *  - partial erase that PRESERVES remaining stroke geometry (§6),
 *  - selection transforms: move / resize / rotate (§8, §11),
 *  - geometric hit testing for lasso & rectangle selection (§11),
 *  - input smoothing for natural strokes (§4),
 *  - clipboard-safe duplication.
 *
 * No Android imports — the rendering layer consumes these results.
 */
object StrokeOps {

    // =====================================================================
    // Partial erase (§6) — vector split, never rasterize
    // =====================================================================

    /**
     * Splits [stroke] against one eraser capsule (segment from the eraser path
     * with radius [r]). Points strictly inside the capsule are removed; the two
     * exact intersections with the capsule boundary become the new segment
     * endpoints, so untouched geometry is preserved bit-exactly.
     *
     * @param ex0 capsule start x (world), ey0 start y, ex1 end x, ey1 end y
     * @return list of kept polylines (may be empty); null when nothing changed.
     */
    fun splitStrokeAgainstCapsule(
        stroke: Stroke,
        ex0: Float, ey0: Float, ex1: Float, ey1: Float,
        r: Float
    ): List<List<StrokePoint>>? {
        val pts = stroke.points
        if (pts.isEmpty()) return null

        val segments = ArrayList<List<StrokePoint>>()
        var current = ArrayList<StrokePoint>()
        var changed = false

        var i = 0
        while (i < pts.size) {
            val p = pts[i]
            if (pointInCapsule(p.x, p.y, ex0, ey0, ex1, ey1, r)) {
                changed = true
                // close the running segment with an interpolated boundary point
                if (current.isNotEmpty()) {
                    val prev = current.last()
                    val hit = capsuleBoundaryIntersection(prev.x, prev.y, p.x, p.y, ex0, ey0, ex1, ey1, r)
                    if (hit != null) current.add(p.copy(x = hit[0], y = hit[1]))
                    if (current.size >= 1) segments.add(current)
                    current = ArrayList()
                }
            } else {
                if (current.isEmpty() && i > 0) {
                    // entering a kept run from an erased run — interpolate exit point
                    val prev = pts[i - 1]
                    val hit = capsuleBoundaryIntersection(prev.x, prev.y, p.x, p.y, ex0, ey0, ex1, ey1, r)
                    if (hit != null) current.add(p.copy(x = hit[0], y = hit[1]))
                }
                current.add(p)
            }
            i++
        }
        if (current.isNotEmpty()) segments.add(current)

        if (!changed) return null
        // drop degenerate leftovers (single point that was already isolated)
        val kept = segments.filter { it.size >= 2 || (it.size == 1 && segments.size == 1) }
        if (kept.size == 1 && kept[0].size == pts.size) return null
        return kept
    }

    /** True when (x, y) lies within radius [r] of the segment (ex0,ey0)-(ex1,ey1). */
    fun pointInCapsule(x: Float, y: Float, ex0: Float, ey0: Float, ex1: Float, ey1: Float, r: Float): Boolean =
        distToSegment(x, y, ex0, ey0, ex1, ey1) < r

    /**
     * Finds the point where the segment (x0,y0)-(x1,y1) crosses into the eraser
     * capsule, solved by binary subdivision (robust, allocation-light).
     * @return float[2] {x, y} or null when no crossing exists.
     */
    fun capsuleBoundaryIntersection(
        x0: Float, y0: Float, x1: Float, y1: Float,
        ex0: Float, ey0: Float, ex1: Float, ey1: Float, r: Float
    ): FloatArray? {
        val inside0 = pointInCapsule(x0, y0, ex0, ey0, ex1, ey1, r)
        val inside1 = pointInCapsule(x1, y1, ex0, ey0, ex1, ey1, r)
        if (inside0 == inside1) return null
        var lo = 0f
        var hi = 1f
        var t = 0.5f
        repeat(18) {
            t = (lo + hi) / 2f
            val px = x0 + (x1 - x0) * t
            val py = y0 + (y1 - y0) * t
            if (pointInCapsule(px, py, ex0, ey0, ex1, ey1, r) == inside0) lo = t else hi = t
        }
        return floatArrayOf(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t)
    }

    // =====================================================================
    // Selection hit testing (§11) — geometric, not bbox-only
    // =====================================================================

    /**
     * True when any part of the stroke is geometrically inside the rectangle
     * (a segment crosses the rect border, or a point lies inside it).
     */
    fun strokeIntersectsRect(stroke: Stroke, rect: FloatArray): Boolean {
        val pts = stroke.points
        if (pts.isEmpty()) return false
        var allInside = true
        for (p in pts) {
            if (rectContains(rect, p.x, p.y)) return true
            allInside = false
        }
        if (allInside) return true
        // any segment crossing one of the four rect edges?
        var prev = pts[0]
        for (i in 1 until pts.size) {
            val cur = pts[i]
            if (segmentIntersectsRect(prev.x, prev.y, cur.x, cur.y, rect)) return true
            prev = cur
        }
        return false
    }

    /** Segment vs axis-aligned rect intersection (rect = [x0,y0,x1,y1]). */
    fun segmentIntersectsRect(x0: Float, y0: Float, x1: Float, y1: Float, rect: FloatArray): Boolean {
        // Liang-Barsky clip
        var t0 = 0f
        var t1 = 1f
        val dx = x1 - x0
        val dy = y1 - y0
        val p = floatArrayOf(-dx, dx, -dy, dy)
        val q = floatArrayOf(
            x0 - rect[0], rect[2] - x0,
            y0 - rect[1], rect[3] - y0
        )
        for (i in 0 until 4) {
            if (p[i] == 0f) {
                if (q[i] < 0) return false
            } else {
                val t = q[i] / p[i]
                if (p[i] < 0) {
                    if (t > t1) return false
                    if (t > t0) t0 = t
                } else {
                    if (t < t0) return false
                    if (t < t1) t1 = t
                }
            }
        }
        return true
    }

    /**
     * True when the stroke is geometrically engaged with the lasso polygon:
     * any point inside the polygon OR any stroke segment crossing a polygon edge.
     */
    fun strokeIntersectsPolygon(stroke: Stroke, poly: FloatArray): Boolean {
        val pts = stroke.points
        if (pts.isEmpty()) return false
        for (p in pts) if (pointInPolygon(p.x, p.y, poly)) return true
        if (pts.size < 2) return false
        val n = poly.size / 2
        var prev = pts[0]
        for (i in 1 until pts.size) {
            val cur = pts[i]
            for (e in 0 until n) {
                val ex0 = poly[e * 2]; val ey0 = poly[e * 2 + 1]
                val ex1 = poly[((e + 1) % n) * 2]; val ey1 = poly[((e + 1) % n) * 2 + 1]
                if (segmentsIntersect(prev.x, prev.y, cur.x, cur.y, ex0, ey0, ex1, ey1)) return true
            }
            prev = cur
        }
        return false
    }

    /** Standard segment-pair intersection test. */
    fun segmentsIntersect(
        ax0: Float, ay0: Float, ax1: Float, ay1: Float,
        bx0: Float, by0: Float, bx1: Float, by1: Float
    ): Boolean {
        val d1 = cross(bx0, by0, bx1, by1, ax0, ay0)
        val d2 = cross(bx0, by0, bx1, by1, ax1, ay1)
        val d3 = cross(ax0, ay0, ax1, ay1, bx0, by0)
        val d4 = cross(ax0, ay0, ax1, ay1, bx1, by1)
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) &&
            ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
        ) return true
        if (d1 == 0f && onSegment(bx0, by0, bx1, by1, ax0, ay0)) return true
        if (d2 == 0f && onSegment(bx0, by0, bx1, by1, ax1, ay1)) return true
        if (d3 == 0f && onSegment(ax0, ay0, ax1, ay1, bx0, by0)) return true
        if (d4 == 0f && onSegment(ax0, ay0, ax1, ay1, bx1, by1)) return true
        return false
    }

    private fun cross(x0: Float, y0: Float, x1: Float, y1: Float, px: Float, py: Float): Float =
        (x1 - x0) * (py - y0) - (y1 - y0) * (px - x0)

    private fun onSegment(x0: Float, y0: Float, x1: Float, y1: Float, px: Float, py: Float): Boolean =
        px in min(x0, x1)..max(x0, x1) && py in min(y0, y1)..max(y0, y1)

    // =====================================================================
    // Transforms (§8 move / resize / rotate)
    // =====================================================================

    fun translateStroke(s: Stroke, dx: Float, dy: Float): Stroke =
        s.copy(points = s.points.map { it.copy(x = it.x + dx, y = it.y + dy) })

    /**
     * Scales a stroke about pivot (cx, cy) by [sx]/[sy]. Stroke width follows
     * the geometric mean of the scale factors (ink behaves like real ink).
     */
    fun scaleStroke(s: Stroke, cx: Float, cy: Float, sx: Float, sy: Float, scaleWidth: Boolean = true): Stroke {
        val wScale = if (scaleWidth) hypot(sx, sy) / kotlin.math.sqrt(2f) else 1f
        return s.copy(
            width = (s.width * wScale).coerceIn(0.1f, 400f),
            points = s.points.map { it.copy(x = cx + (it.x - cx) * sx, y = cy + (it.y - cy) * sy) }
        )
    }

    /** Rotates a stroke about pivot (cx, cy) by [angleDeg] degrees. */
    fun rotateStroke(s: Stroke, cx: Float, cy: Float, angleDeg: Float): Stroke {
        if (angleDeg == 0f) return s
        val a = Math.toRadians(angleDeg.toDouble())
        val ca = cos(a).toFloat()
        val sa = sin(a).toFloat()
        return s.copy(points = s.points.map {
            val dx = it.x - cx
            val dy = it.y - cy
            it.copy(x = cx + dx * ca - dy * sa, y = cy + dx * sa + dy * ca)
        })
    }

    // =====================================================================
    // Input smoothing (§4 natural interpolation)
    // =====================================================================

    /**
     * Exponential position smoothing applied while capturing input.
     * [strength] 0 → raw points, 1 → maximum lag. Returns the smoothed point.
     */
    fun smoothPoint(prevX: Float, prevY: Float, x: Float, y: Float, strength: Float): FloatArray {
        val a = strength.coerceIn(0f, 0.9f)
        return floatArrayOf(prevX + (x - prevX) * (1f - a), prevY + (y - prevY) * (1f - a))
    }

    /**
     * Resamples + smooths a committed stroke: moving-average filter over a
     * window derived from [strength], then drops redundant near-duplicate
     * points. Pressure values are smoothed with the same kernel.
     */
    fun smoothCommitted(points: List<StrokePoint>, strength: Float): List<StrokePoint> {
        if (points.size < 5 || strength <= 0.01f) return points
        val window = (1 + (strength * 3f).toInt()).coerceAtMost(4) // 1..4
        val out = ArrayList<StrokePoint>(points.size)
        for (i in points.indices) {
            var sx = 0f; var sy = 0f; var sp = 0f; var st = 0f; var n = 0
            for (k in -window..window) {
                val j = i + k
                if (j in points.indices) {
                    sx += points[j].x; sy += points[j].y; sp += points[j].p; st += points[j].tilt; n++
                }
            }
            val src = points[i]
            val mix = 0.55f + 0.4f * strength
            out.add(
                src.copy(
                    x = src.x * (1f - mix) + (sx / n) * mix,
                    y = src.y * (1f - mix) + (sy / n) * mix,
                    p = src.p * (1f - mix) + (sp / n) * mix
                )
            )
        }
        // drop near-duplicates introduced by smoothing
        val dedup = ArrayList<StrokePoint>(out.size)
        for (p in out) {
            val last = dedup.lastOrNull()
            if (last == null || hypot(p.x - last.x, p.y - last.y) > 0.35f || p === out.last()) {
                dedup.add(p)
            }
        }
        return dedup.ifEmpty { points }
    }

    // =====================================================================
    // Duplication (§8 clipboard-safe copy)
    // =====================================================================

    fun duplicateStroke(s: Stroke, dx: Float = 16f, dy: Float = 16f): Stroke =
        s.copy(id = newId(), points = s.points.map { it.copy(x = it.x + dx, y = it.y + dy) }, groupId = null)

    fun duplicateText(t: TextElement, dx: Float = 16f, dy: Float = 16f): TextElement =
        t.copy(id = newId(), x = t.x + dx, y = t.y + dy, groupId = null)

    fun duplicateImage(i: ImageElement, dx: Float = 16f, dy: Float = 16f): ImageElement =
        i.copy(id = newId(), x = i.x + dx, y = i.y + dy, groupId = null)

    fun duplicateShape(s: ShapeElement, dx: Float = 16f, dy: Float = 16f): ShapeElement =
        s.copy(id = newId(), x1 = s.x1 + dx, y1 = s.y1 + dy, x2 = s.x2 + dx, y2 = s.y2 + dy, groupId = null)

    // =====================================================================
    // Geometry helpers used across the engine
    // =====================================================================

    fun polygonArea2(poly: FloatArray): Float {
        var a = 0f
        val n = poly.size / 2
        for (i in 0 until n) {
            val j = (i + 1) % n
            a += poly[i * 2] * poly[j * 2 + 1] - poly[j * 2] * poly[i * 2 + 1]
        }
        return a
    }

    fun pathLength(points: List<StrokePoint>): Float {
        var len = 0f
        for (i in 1 until points.size) {
            len += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
        }
        return len
    }

    /** Distance between first and last point (for line/closure detection). */
    fun endpointGap(points: List<StrokePoint>): Float =
        if (points.size < 2) 0f else hypot(points.last().x - points.first().x, points.last().y - points.first().y)

    fun bboxOf(points: List<StrokePoint>): FloatArray {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (p in points) {
            minX = min(minX, p.x); minY = min(minY, p.y)
            maxX = max(maxX, p.x); maxY = max(maxY, p.y)
        }
        if (points.isEmpty()) return floatArrayOf(0f, 0f, 0f, 0f)
        return floatArrayOf(minX, minY, maxX, maxY)
    }

    /** Chebyshev-ish angle snap: snaps [angleDeg] to the closest multiple of [step]. */
    fun snapAngle(angleDeg: Float, step: Float = 15f): Float =
        (kotlin.math.round(angleDeg / step) * step)

    /** Snaps a drag point to 0/45/90° axes relative to origin, when within tolerance. */
    fun snapDirectional(ox: Float, oy: Float, x: Float, y: Float, toleranceDeg: Float = 7f): FloatArray {
        val len = hypot(x - ox, y - oy)
        if (len < 1f) return floatArrayOf(x, y)
        val angle = Math.toDegrees(kotlin.math.atan2((y - oy).toDouble(), (x - ox).toDouble())).toFloat()
        val snapped = snapAngle(angle, 45f)
        if (abs(angle - snapped) > toleranceDeg) return floatArrayOf(x, y)
        val a = Math.toRadians(snapped.toDouble())
        return floatArrayOf(ox + len * cos(a).toFloat(), oy + len * sin(a).toFloat())
    }
}
