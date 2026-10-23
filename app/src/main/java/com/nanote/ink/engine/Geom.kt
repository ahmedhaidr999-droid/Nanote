package com.nanote.ink.engine

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-Kotlin geometry: a 3x3 affine matrix with the same value layout as
 * android.graphics.Matrix (row-major, [scaleX, skewX, transX, skewY, scaleY,
 * transY, 0, 0, 1]) so the render layer can copy values in and out with
 * Matrix.setValues/getValues. Keeping this in the engine (no android.* imports)
 * makes transforms unit-testable on the JVM.
 *
 * Not thread-safe by design: it reuses a small scratch buffer. All engine
 * state lives on the main thread.
 */
class Mat3 {

    val v = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private val t = FloatArray(9)

    val scaleX: Float get() = v[0]
    val scaleY: Float get() = v[4]
    val skewX: Float get() = v[1]
    val transX: Float get() = v[2]
    val transY: Float get() = v[5]

    val isAffine: Boolean get() = v[6] == 0f && v[7] == 0f && v[8] == 1f

    fun reset() {
        v[0] = 1f; v[1] = 0f; v[2] = 0f
        v[3] = 0f; v[4] = 1f; v[5] = 0f
        v[6] = 0f; v[7] = 0f; v[8] = 1f
    }

    fun set(o: Mat3) {
        System.arraycopy(o.v, 0, v, 0, 9)
    }

    /** M' = T(dx, dy) * M */
    fun postTranslate(dx: Float, dy: Float) {
        v[2] += dx
        v[5] += dy
    }

    /** M' = M * T(dx, dy) */
    fun preTranslate(dx: Float, dy: Float) {
        v[2] += dx * v[0] + dy * v[1]
        v[5] += dx * v[3] + dy * v[4]
    }

    /** M' = S(sx, sy about px, py) * M */
    fun postScale(sx: Float, sy: Float, px: Float, py: Float) {
        precomposeScaleTranslate(sx, sy, px, py, isScale = true)
    }

    /** M' = M * S(sx, sy about px, py) */
    fun preScale(sx: Float, sy: Float, px: Float, py: Float) {
        // M * T(p) * S * T(-p) == T(M(p)) * S_p ... simplest correct route: concat this*scratch.
        setScratchScaleAbout(t, sx, sy, px, py)
        concatThis(t, pre = true)
    }

    /** M' = R(deg about px, py) * M */
    fun postRotate(degrees: Float, px: Float, py: Float) {
        setScratchRotateAbout(t, degrees, px, py)
        concatThis(t, pre = false)
    }

    /** M' = R(deg about px, py) * M  — identical to postRotate. */
    fun rotateAbout(degrees: Float, px: Float, py: Float) = postRotate(degrees, px, py)

    /** M' = other * M (other applied after this, in point-flow terms). */
    fun postConcat(o: Mat3) = concatThis(o.v, pre = false)

    /** M' = M * other (other applied first, in point-flow terms). */
    fun preConcat(o: Mat3) = concatThis(o.v, pre = true)

    /**
     * Scale-about-point composed directly: M' = T(p)·S·T(-p)·M.
     * (Specialized to avoid a scratch matrix allocation on the zoom hot path.)
     */
    private fun precomposeScaleTranslate(sx: Float, sy: Float, px: Float, py: Float, isScale: Boolean) {
        // N = T(p)·S·T(-p):  | sx      0    px(1-sx) |
        //                      | 0      sy    py(1-sy) |
        //                      | 0      0       1      |
        val nx0 = sx; val ny4 = sy
        val nx2 = px * (1f - sx)
        val ny5 = py * (1f - sy)
        // result = N · M
        val r0 = nx0 * v[0]
        val r1 = nx0 * v[1]
        val r2 = nx0 * v[2] + nx2
        val r3 = ny4 * v[3]
        val r4 = ny4 * v[4]
        val r5 = ny4 * v[5] + ny5
        v[0] = r0; v[1] = r1; v[2] = r2
        v[3] = r3; v[4] = r4; v[5] = r5
    }

    /** concat: if pre, result = this·o else result = o·this. o is a raw 9-float matrix. */
    private fun concatThis(o: FloatArray, pre: Boolean) {
        val a = if (pre) v else o   // left
        val b = if (pre) o else v   // right
        for (row in 0..2) {
            val r = row * 3
            val a0 = a[r]; val a1 = a[r + 1]; val a2 = a[r + 2]
            t[r] = a0 * b[0] + a1 * b[3] + a2 * b[6]
            t[r + 1] = a0 * b[1] + a1 * b[4] + a2 * b[7]
            t[r + 2] = a0 * b[2] + a1 * b[5] + a2 * b[8]
        }
        System.arraycopy(t, 0, v, 0, 9)
    }

    private fun setScratchScaleTranslate(sx: Float, sy: Float, px: Float, py: Float) {
        t[0] = sx; t[1] = 0f; t[2] = px * (1f - sx)
        t[3] = 0f; t[4] = sy; t[5] = py * (1f - sy)
        t[6] = 0f; t[7] = 0f; t[8] = 1f
    }

    private fun setScratchScaleAbout(out: FloatArray, sx: Float, sy: Float, px: Float, py: Float) {
        out[0] = sx; out[1] = 0f; out[2] = px * (1f - sx)
        out[3] = 0f; out[4] = sy; out[5] = py * (1f - sy)
        out[6] = 0f; out[7] = 0f; out[8] = 1f
    }

    private fun setScratchRotateAbout(out: FloatArray, degrees: Float, px: Float, py: Float) {
        val rad = Math.toRadians(degrees.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        out[0] = c; out[1] = -s; out[2] = px - (c * px - s * py)
        out[3] = s; out[4] = c; out[5] = py - (s * px + c * py)
        out[6] = 0f; out[7] = 0f; out[8] = 1f
    }

    fun determinant(): Float =
        v[0] * (v[4] * v[8] - v[5] * v[7]) -
        v[1] * (v[3] * v[8] - v[5] * v[6]) +
        v[2] * (v[3] * v[7] - v[4] * v[6])

    /** Inverts into [out]; returns false when the matrix is singular. */
    fun invert(out: Mat3): Boolean {
        val a0 = v[0].toDouble(); val a1 = v[1].toDouble(); val a2 = v[2].toDouble()
        val a3 = v[3].toDouble(); val a4 = v[4].toDouble(); val a5 = v[5].toDouble()
        val a6 = v[6].toDouble(); val a7 = v[7].toDouble(); val a8 = v[8].toDouble()
        val det = a0 * (a4 * a8 - a5 * a7) - a1 * (a3 * a8 - a5 * a6) + a2 * (a3 * a7 - a4 * a6)
        if (abs(det) < 1e-12) return false
        val inv = 1.0 / det
        val o = out.v
        o[0] = ((a4 * a8 - a5 * a7) * inv).toFloat()
        o[1] = ((a2 * a7 - a1 * a8) * inv).toFloat()
        o[2] = ((a1 * a5 - a2 * a4) * inv).toFloat()
        o[3] = ((a5 * a6 - a3 * a8) * inv).toFloat()
        o[4] = ((a0 * a8 - a2 * a6) * inv).toFloat()
        o[5] = ((a2 * a3 - a0 * a5) * inv).toFloat()
        o[6] = ((a3 * a7 - a4 * a6) * inv).toFloat()
        o[7] = ((a1 * a6 - a0 * a7) * inv).toFloat()
        o[8] = ((a0 * a4 - a1 * a3) * inv).toFloat()
        return true
    }

    fun mapX(x: Float, y: Float): Float = v[0] * x + v[1] * y + v[2]
    fun mapY(x: Float, y: Float): Float = v[3] * x + v[4] * y + v[5]

    /** Maps count (x, y) pairs in place starting at [offset]. */
    fun mapPoints(pts: FloatArray, offset: Int, count: Int) {
        var i = offset
        val end = offset + count * 2
        while (i < end) {
            val x = pts[i]; val y = pts[i + 1]
            pts[i] = v[0] * x + v[1] * y + v[2]
            pts[i + 1] = v[3] * x + v[4] * y + v[5]
            i += 2
        }
    }

    /** Applies the inverse of this matrix in place; returns false if singular. */
    fun inverseMapPoints(pts: FloatArray, offset: Int, count: Int): Boolean {
        if (!invert(SCRATCH)) return false
        SCRATCH.mapPoints(pts, offset, count)
        return true
    }

    companion object {
        private val SCRATCH = Mat3()

        /** Simple pooled matrix for temporary use on the main thread. */
        fun obtain(): Mat3 = Mat3()
    }
}

/**
 * Axis-aligned float bounds — the engine's pure-Kotlin stand-in for
 * android.graphics.RectF. Callers reuse instances to avoid allocations.
 */
class Bounds {
    var left = Float.MAX_VALUE
    var top = Float.MAX_VALUE
    var right = -Float.MAX_VALUE
    var bottom = -Float.MAX_VALUE

    val isEmpty: Boolean get() = left > right || top > bottom
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f

    fun set(l: Float, t: Float, r: Float, b: Float) {
        left = l; top = t; right = r; bottom = b
    }

    fun set(o: Bounds) {
        left = o.left; top = o.top; right = o.right; bottom = o.bottom
    }

    fun reset() {
        left = Float.MAX_VALUE; top = Float.MAX_VALUE
        right = -Float.MAX_VALUE; bottom = -Float.MAX_VALUE
    }

    fun union(x: Float, y: Float) {
        if (x < left) left = x
        if (x > right) right = x
        if (y < top) top = y
        if (y > bottom) bottom = y
    }

    fun union(o: Bounds) {
        if (o.left < left) left = o.left
        if (o.right > right) right = o.right
        if (o.top < top) top = o.top
        if (o.bottom > bottom) bottom = o.bottom
    }

    fun intersects(o: Bounds): Boolean =
        !(o.right < left || o.left > right || o.bottom < top || o.top > bottom)

    override fun toString(): String = "Bounds[$left,$top $right,$bottom]"
}

/** Squared distance from point (px, py) to segment (ax, ay)-(bx, by). */
fun distPointSegSq(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
    val abx = bx - ax
    val aby = by - ay
    val l2 = abx * abx + aby * aby
    val tt = if (l2 == 0f) 0f
    else (((px - ax) * abx + (py - ay) * aby) / l2).coerceIn(0f, 1f)
    val cx = ax + tt * abx - px
    val cy = ay + tt * aby - py
    return cx * cx + cy * cy
}

/**
 * Even-odd rule point-in-polygon test. [poly] is packed x0, y0, x1, y1, ...
 * Used by the lasso. Pure Kotlin, allocation-free.
 */
fun pointInPolygon(x: Float, y: Float, poly: FloatArray): Boolean {
    var inside = false
    val n = poly.size / 2
    var j = n - 1
    for (i in 0 until n) {
        val xi = poly[2 * i]
        val yi = poly[2 * i + 1]
        val xj = poly[2 * j]
        val yj = poly[2 * j + 1]
        if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
        j = i
    }
    return inside
}

fun sqrtSafe(f: Float): Float = if (f <= 0f) 0f else sqrt(f)
