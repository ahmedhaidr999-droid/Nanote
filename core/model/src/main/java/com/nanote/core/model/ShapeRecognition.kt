package com.nanote.core.model

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Deterministic handwriting → shape recognition (§14 optional item).
 *
 * NO machine learning — only classical geometry:
 *  1. straight line      : path length ≈ endpoint distance
 *  2. triangle           : exactly 3 dominant corners + closed
 *  3. rectangle          : 4 dominant corners + closed + ~90° angles
 *  4. circle / ellipse   : low radial variance around centroid
 *
 * Each recognizer returns a confidence in [0..1]; the caller accepts the match
 * only above [MIN_CONFIDENCE] and always lets the user undo.
 */
object ShapeRecognition {

    const val MIN_CONFIDENCE = 0.72f

    data class Result(
        val kind: ShapeKind,
        val confidence: Float,
        val x1: Float, val y1: Float,
        val x2: Float, val y2: Float,
        /** POLYGON vertices (normalized pairs) when kind == POLYGON. */
        val vertices: List<Float> = emptyList()
    )

    fun recognize(points: List<StrokePoint>): Result? {
        if (points.size < 6) return null
        val len = StrokeOps.pathLength(points)
        if (len < 12f) return null

        return recognizeLine(points, len)
            ?: recognizeCircleish(points)
            ?: recognizePolygonal(points, len)
    }

    // ------------------------------------------------------------------ line

    private fun recognizeLine(points: List<StrokePoint>, pathLen: Float): Result? {
        val gap = StrokeOps.endpointGap(points)
        if (gap < 8f) return null
        val straightness = gap / pathLen          // 1.0 = perfectly straight
        if (straightness < 0.90f) return null
        val a = points.first()
        val b = points.last()
        return Result(
            kind = ShapeKind.LINE,
            confidence = straightness.coerceIn(0f, 1f),
            x1 = a.x, y1 = a.y, x2 = b.x, y2 = b.y
        )
    }

    // ------------------------------------------------------------ circle / ellipse

    private fun recognizeCircleish(points: List<StrokePoint>): Result? {
        val bb = StrokeOps.bboxOf(points)
        val w = bb[2] - bb[0]
        val h = bb[3] - bb[1]
        if (w < 8f || h < 8f) return null
        val cx = (bb[0] + bb[2]) / 2f
        val cy = (bb[1] + bb[3]) / 2f
        val rx = w / 2f
        val ry = h / 2f

        // radial consistency: distance from center, normalized by ellipse radii
        var sum = 0f; var sumSq = 0f; var n = 0
        for (p in points) {
            val nx = (p.x - cx) / rx
            val ny = (p.y - cy) / ry
            val r = hypot(nx, ny)
            sum += r; sumSq += r * r; n++
        }
        val mean = sum / n
        val variance = (sumSq / n) - mean * mean
        val std = kotlin.math.sqrt(variance.coerceAtLeast(0f))
        // a perfect ellipse has every normalized radius == 1
        val radialError = std / mean.coerceAtLeast(0.0001f)

        // coverage: the stroke must go most of the way around
        var angleCovered = 0f
        var prevAng = atan2(points.first().y - cy, points.first().x - cx)
        val stepLimit = 0.6f // radians; larger jumps = corner, breaks arc continuity
        var continuous = true
        for (i in 1 until points.size) {
            val ang = atan2(points[i].y - cy, points[i].x - cx)
            var d = ang - prevAng
            while (d > Math.PI.toFloat()) d -= (2f * Math.PI.toFloat())
            while (d < -Math.PI.toFloat()) d += (2f * Math.PI.toFloat())
            if (abs(d) > stepLimit) continuous = false
            angleCovered += abs(d)
            prevAng = ang
        }

        if (!continuous) return null
        if (angleCovered < 4.7f) return null        // < ~270° → not a closed blob
        val circleScore = (1f - radialError * 6f).coerceIn(0f, 1f)
        if (circleScore < MIN_CONFIDENCE) return null

        val kind = if (abs(w - h) / max(w, h) < 0.18f) ShapeKind.CIRCLE else ShapeKind.ELLIPSE
        return Result(
            kind = kind,
            confidence = circleScore,
            x1 = bb[0], y1 = bb[1], x2 = bb[2], y2 = bb[3]
        )
    }

    // -------------------------------------------------- triangle / rect / polygon

    private fun recognizePolygonal(points: List<StrokePoint>, pathLen: Float): Result? {
        // must be a closed shape
        val gap = StrokeOps.endpointGap(points)
        if (gap > pathLen * 0.25f) return null

        val corners = dominantCorners(points)
        val bb = StrokeOps.bboxOf(points)
        val w = bb[2] - bb[0]
        val h = bb[3] - bb[1]
        if (w < 8f || h < 8f) return null

        return when {
            corners.size == 3 -> {
                val conf = cornerConfidence(points, corners, pathLen)
                if (conf < MIN_CONFIDENCE) null
                else Result(ShapeKind.TRIANGLE, conf, bb[0], bb[1], bb[2], bb[3])
            }
            corners.size == 4 -> {
                val conf = rectangleConfidence(corners)
                if (conf < MIN_CONFIDENCE) null
                else Result(ShapeKind.RECT, conf, bb[0], bb[1], bb[2], bb[3])
            }
            corners.size in 5..10 -> {
                // generic polygon: keep actual corner vertices
                val vs = ArrayList<Float>(corners.size * 2)
                for (c in corners) {
                    vs.add((c.first - bb[0]) / w)
                    vs.add((c.second - bb[1]) / h)
                }
                Result(ShapeKind.POLYGON, 0.74f, bb[0], bb[1], bb[2], bb[3], vs)
            }
            else -> null
        }
    }

    /** Ramer-ish corner detection via turn-angle thresholding. */
    private fun dominantCorners(points: List<StrokePoint>): List<Pair<Float, Float>> {
        // raw corners: the start point + every strong turn
        val raw = ArrayList<Pair<Float, Float>>()
        val window = 6
        val thresholdDeg = 38f
        var i = window
        val first = points.first()
        raw.add(first.x to first.y)
        while (i < points.size - window) {
            val prev = points[i - window]
            val cur = points[i]
            val next = points[min(points.size - 1, i + window)]
            val a1 = atan2(cur.y - prev.y, cur.x - prev.x)
            val a2 = atan2(next.y - cur.y, next.x - cur.x)
            var turn = Math.toDegrees((a2 - a1).toDouble()).toFloat()
            while (turn > 180f) turn -= 360f
            while (turn < -180f) turn += 360f
            if (abs(turn) > thresholdDeg) {
                raw.add(cur.x to cur.y)
                // the turn persists across ~2 windows — skip past the whole bend
                i += window * 2
            } else {
                i++
            }
        }

        // merge pass: a single physical corner can trigger twice (approach +
        // exit of the same bend). Drop corners closer than a fraction of the
        // shape diagonal to the previously kept one, and treat corners landing
        // back on the start point as closure (never a separate vertex).
        val bb = StrokeOps.bboxOf(points)
        val diag = hypot(bb[2] - bb[0], bb[3] - bb[1]).coerceAtLeast(40f)
        val mergeDist = diag * 0.22f
        val merged = ArrayList<Pair<Float, Float>>()
        for (c in raw) {
            val lastKept = merged.lastOrNull()
            val nearLast = lastKept != null && hypot(c.first - lastKept.first, c.second - lastKept.second) < mergeDist
            val nearFirst = merged.isNotEmpty() &&
                hypot(c.first - merged.first().first, c.second - merged.first().second) < mergeDist
            if (!nearLast && !nearFirst) merged.add(c)
        }
        return merged
    }

    /** How much of the path length is spent near corner-to-corner hull edges. */
    private fun cornerConfidence(points: List<StrokePoint>, corners: List<Pair<Float, Float>>, pathLen: Float): Float {
        if (corners.size < 3) return 0f
        val hullLen = run {
            var l = 0f
            for (i in corners.indices) {
                val a = corners[i]
                val b = corners[(i + 1) % corners.size]
                l += hypot(b.first - a.first, b.second - a.second)
            }
            l
        }
        if (pathLen <= 0f) return 0f
        return (hullLen / pathLen).coerceIn(0f, 1f)
    }

    private fun rectangleConfidence(corners: List<Pair<Float, Float>>): Float {
        if (corners.size != 4) return 0f
        // consecutive edges ~perpendicular & opposite edges ~parallel
        val angles = ArrayList<Float>(4)
        for (i in 0 until 4) {
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            angles.add(atan2(b.second - a.second, b.first - a.first))
        }
        var score = 0f
        for (i in 0 until 4) {
            val a1 = angles[i]
            val a2 = angles[(i + 1) % 4]
            var d = Math.toDegrees((a1 - a2).toDouble()).toFloat()
            while (d > 180f) d -= 360f
            while (d < -180f) d += 360f
            score += (1f - abs(abs(d) - 90f) / 45f).coerceIn(0f, 1f)
        }
        return score / 4f
    }
}
