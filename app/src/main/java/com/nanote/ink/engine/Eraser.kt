package com.nanote.ink.engine

import kotlin.math.hypot

/**
 * Whole-stroke ("vector") eraser hit test: sample the eraser movement every
 * r/2 and test against each segment of the candidate stroke. Only strokes
 * whose (width-inflated) bounds are near the path need testing — the caller
 * pre-filters via SpatialGrid.
 */
fun eraserHits(x0: Float, y0: Float, x1: Float, y1: Float, r: Float, s: Stroke): Boolean {
    val reach = (r + s.baseWidth / 2f).let { it * it }
    val steps = maxOf(1, (hypot(x1 - x0, y1 - y0) / (r / 2f)).toInt())
    if (s.count == 1) {
        for (k in 0..steps) {
            val px = x0 + (x1 - x0) * k / steps
            val py = y0 + (y1 - y0) * k / steps
            if (hypot(px - s.x(0), py - s.y(0)) <= r + s.baseWidth / 2f) return true
        }
        return false
    }
    for (k in 0..steps) {
        val px = x0 + (x1 - x0) * k / steps
        val py = y0 + (y1 - y0) * k / steps
        for (i in 0 until s.count - 1) {
            if (distPointSegSq(px, py, s.x(i), s.y(i), s.x(i + 1), s.y(i + 1)) <= reach) return true
        }
    }
    return false
}

/** True when any point of [s] lies inside the circle — used by pixel erase. */
private fun strokeTouchesCircle(s: Stroke, cx: Float, cy: Float, r: Float): Boolean {
    for (i in 0 until s.count) {
        if (hypot(s.x(i) - cx, s.y(i) - cy) <= r) return true
    }
    return false
}

private fun packedXY(s: Stroke): FloatArray {
    val xy = FloatArray(s.count * 2)
    for (i in 0 until s.count) {
        xy[2 * i] = s.x(i)
        xy[2 * i + 1] = s.y(i)
    }
    return xy
}

/**
 * Pixel eraser over an entire gesture path.
 *
 * [centers] is the packed list of eraser positions (x0, y0, x1, y1, ...)
 * already sampled every r/2 along the movement. The stroke is resampled to
 * at most r/2 spacing, every point inside ANY eraser circle is dropped and
 * each surviving run of >= 2 points becomes a new stroke (same pen, color,
 * width). The whole gesture becomes ONE ReplaceStrokes command at the call site.
 *
 * Returns:
 *  - null          — stroke untouched (fast reject + exact check)
 *  - empty list    — stroke fully consumed
 *  - list          — surviving fragments in z order
 */
fun pixelErasePath(
    s: Stroke,
    centers: FloatArray,
    r: Float,
    idAllocator: () -> Int
): List<Stroke>? {
    if (s.count == 0 || centers.isEmpty()) return null
    if (s.count == 1) {
        val x = s.x(0); val y = s.y(0)
        for (k in centers.indices step 2) {
            if (hypot(x - centers[k], y - centers[k + 1]) <= r) return emptyList()
        }
        return null
    }

    // Fast reject via bounds, then exact point test.
    var touched = false
    outer@ for (i in 0 until s.count) {
        val px = s.x(i); val py = s.y(i)
        for (k in centers.indices step 2) {
            if (hypot(px - centers[k], py - centers[k + 1]) <= r) {
                touched = true
                break@outer
            }
        }
    }
    if (!touched) return null

    val rs = resampleXY(packedXY(s), r / 2f)
    val rsN = rs.size / 2
    val out = ArrayList<Stroke>(2)
    var runStart = -1

    fun inside(i: Int): Boolean {
        val px = rs[2 * i]
        val py = rs[2 * i + 1]
        for (k in centers.indices step 2) {
            if (hypot(px - centers[k], py - centers[k + 1]) <= r) return true
        }
        return false
    }

    fun endRun(endExclusive: Int) {
        if (runStart < 0) return
        val len = endExclusive - runStart
        if (len == 1) {
            // A lone survivor becomes a dot only if it stays outside all circles
            // (it always does, by construction) — visible ink must not vanish.
            val i = runStart
            out.add(dotStroke(s, rs[2 * i], rs[2 * i + 1], idAllocator()))
        } else {
            val pts = FloatArray(len * STRIDE)
            for (j in 0 until len) {
                val srcIdx = minOf(runStart + j, rsN - 1)
                pts[j * STRIDE] = rs[2 * srcIdx]
                pts[j * STRIDE + 1] = rs[2 * srcIdx + 1]
                pts[j * STRIDE + 2] = s.p(minOf((srcIdx * s.count / rsN), s.count - 1))
                pts[j * STRIDE + 3] = s.t(minOf((srcIdx * s.count / rsN), s.count - 1))
            }
            out.add(Stroke(idAllocator(), s.pen, s.argb, s.baseWidth, pts))
        }
        runStart = -1
    }

    for (i in 0 until rsN) {
        if (inside(i)) {
            endRun(i)
        } else if (runStart < 0) {
            runStart = i
        }
    }
    endRun(rsN)
    return out
}

private fun dotStroke(proto: Stroke, x: Float, y: Float, id: Int): Stroke =
    Stroke(id, proto.pen, proto.argb, proto.baseWidth, floatArrayOf(x, y, 0.5f, 0f))


