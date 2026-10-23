package com.nanote.ink.render

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import com.nanote.ink.engine.PenSpec
import com.nanote.ink.engine.PenType
import com.nanote.ink.engine.Stroke
import com.nanote.ink.engine.outlinePoints
import com.nanote.ink.engine.specOf
import com.nanote.ink.engine.widths
import com.nanote.ink.engine.STRIDE

/**
 * Converts engine geometry into android.graphics drawing. All heavy objects
 * (Path, Paint, scratch arrays) are owned by the caller and reused; nothing
 * is allocated inside onDraw or the touch loop.
 */
class StrokePainter {

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val outlineScratch = FloatArray(MAX_LIVE_POINTS * 4)
    private val widthsScratch = FloatArray(MAX_LIVE_POINTS)
    private var lastWidths: FloatArray? = null

    /**
     * Appends the filled outline path of a committed stroke. The path is
     * smoothed with the midpoint-quad trick (Catmull-Rom-equivalent Bezier);
     * round caps are drawn separately by [drawCaps] when the pen wants them.
     */
    fun appendStrokePath(s: Stroke, path: Path) {
        val n = s.count
        if (n == 0) return
        if (n == 1) {
            val w = s.baseWidth.coerceAtLeast(0.3f)
            path.addCircle(s.x(0), s.y(0), w / 2f, Path.Direction.CW)
            return
        }
        val spec = specOf(s.pen)
        val w = widths(s, spec)
        lastWidths = w
        if (n > MAX_LIVE_POINTS) {
            // Extremely long stroke: split into slices to bound scratch size.
            var start = 0
            while (start < n) {
                val end = minOf(n, start + MAX_LIVE_POINTS)
                appendSlice(s.pts, start, end, w, path, spec.capOrdinal)
                start = end - 1
            }
            return
        }
        appendSlice(s.pts, 0, n, w, path, spec.capOrdinal)
    }

    /** Builds a path from a live in-progress buffer (no Stroke object). */
    fun appendLivePath(pts: FloatArray, count: Int, liveWidths: FloatArray, path: Path, capOrdinal: Int) {
        appendLivePathSlice(pts, 0, count, liveWidths, path, capOrdinal)
    }

    /** Slice variant used for the frozen-prefix + tail scheme of long strokes. */
    fun appendLivePathSlice(
        pts: FloatArray,
        from: Int,
        until: Int,
        liveWidths: FloatArray,
        path: Path,
        capOrdinal: Int
    ) {
        val n = until - from
        if (n <= 0) return
        if (n == 1) {
            path.addCircle(pts[from * STRIDE], pts[from * STRIDE + 1], liveWidths[from] / 2f, Path.Direction.CW)
            return
        }
        appendSlice(pts, from, until, liveWidths, path, capOrdinal)
    }

    /**
     * Appends the outline polygon of points [from, until) as a smoothed path.
     * Midpoint-quad smoothing: quadTo(P[i-1], mid(P[i-1], P[i])) — corners in
     * shape strokes carry duplicated points, which naturally become sharp.
     */
    private fun appendSlice(
        pts: FloatArray,
        from: Int,
        until: Int,
        w: FloatArray,
        path: Path,
        capOrdinal: Int
    ) {
        val n = until - from
        if (n < 2) return
        val out = outlineScratch
        // outlinePoints needs contiguous packed points; for slices we shift the
        // STRIDE view via a local packing loop (reuses the scratch buffer).
        for (i in 0 until n) {
            widthsScratch[i] = w[from + i]
            val src = (from + i) * STRIDE
            val dst = i * STRIDE
            out[dst] = pts[src]
            out[dst + 1] = pts[src + 1]
            out[dst + 2] = pts[src + 2]
            out[dst + 3] = pts[src + 3]
        }
        val m = outlinePoints(out, n, widthsScratch, out)
        if (m == 0) return

        val half = m / 4 // point count per side
        // Left side forward with midpoint quads
        path.moveTo(out[0], out[1])
        var i = 1
        while (i < half) {
            val px = out[2 * (i - 1)]
            val py = out[2 * (i - 1) + 1]
            val cx = out[2 * i]
            val cy = out[2 * i + 1]
            path.quadTo(px, py, (px + cx) / 2f, (py + cy) / 2f)
            i++
        }
        path.lineTo(out[2 * (half - 1)], out[2 * (half - 1) + 1])
        // End cap joint
        val rEnd = out[2 * half]   // right side starts at index 2*half
        val rEndY = out[2 * half + 1]
        path.lineTo(rEnd, rEndY)
        // Right side backward
        i = half + 1
        while (i < 2 * half) {
            val px = out[2 * i]
            val py = out[2 * i + 1]
            val cx = out[2 * (i - 1)]
            val cy = out[2 * (i - 1) + 1]
            path.quadTo(px, py, (px + cx) / 2f, (py + cy) / 2f)
            i++
        }
        path.lineTo(out[2 * (2 * half - 1)], out[2 * (2 * half - 1) + 1])
        path.close()
    }

    /** Configures the paint for a pen; returns the effective alpha. */
    fun configure(pen: PenType, argb: Int): Float {
        val spec = specOf(pen)
        paint.color = argb
        paint.alpha = ((spec.alpha * 255).toInt()).coerceIn(0, 255)
        paint.strokeCap = when (spec.capOrdinal) {
            PenSpec.CAP_SQUARE -> Paint.Cap.SQUARE
            PenSpec.CAP_BUTT -> Paint.Cap.BUTT
            else -> Paint.Cap.ROUND
        }
        if (spec.multiply) {
            if (Build.VERSION.SDK_INT >= 29) {
                paint.blendMode = BlendMode.MULTIPLY
            } else {
                @Suppress("DEPRECATION")
                paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
            }
        } else {
            if (Build.VERSION.SDK_INT >= 29) {
                paint.blendMode = BlendMode.SRC_OVER
            } else {
                @Suppress("DEPRECATION")
                paint.xfermode = null
            }
        }
        return spec.alpha
    }

    fun isMultiply(pen: PenType): Boolean = specOf(pen).multiply

    /** Draws round end caps (skip for square-cap highlighters). */
    fun drawCaps(c: Canvas, s: Stroke) {
        val spec = specOf(s.pen)
        if (spec.capOrdinal != PenSpec.CAP_ROUND) return
        if (s.count == 0) return
        val w = lastWidths ?: return
        c.drawCircle(s.x(0), s.y(0), w[0] / 2f, paint)
        c.drawCircle(s.x(s.count - 1), s.y(s.count - 1), w[s.count - 1] / 2f, paint)
    }

    fun drawDot(c: Canvas, x: Float, y: Float, r: Float) {
        c.drawCircle(x, y, r, paint)
    }

    companion object {
        private const val MAX_LIVE_POINTS = 1500
    }
}
