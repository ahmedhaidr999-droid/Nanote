package com.nanote.ink.engine


/**
 * Pan/zoom state of one editor page.
 *
 *   screen = M * page, with M = | s 0 tx |
 *                                | 0 s ty |
 *                                | 0 0  1 |
 *
 * Backed by the pure-Kotlin Mat3 (same layout as android.graphics.Matrix);
 * the render layer copies values out each frame. Double-tap animation is
 * driven by the view, which interpolates towards [fitToWidth] / 2x.
 */
class ViewTransform(
    private val minScale: Float = 0.25f,
    private val maxScale: Float = 8f
) {
    val m = Mat3()
    private val inv = Mat3()

    val scale: Float
        get() = m.scaleX

    val transX: Float get() = m.transX
    val transY: Float get() = m.transY

    fun reset() = m.reset()

    fun zoom(factor: Float, fx: Float, fy: Float) {
        val k = (scale * factor).coerceIn(minScale, maxScale) / scale
        m.postScale(k, k, fx, fy)
    }

    fun setScale(newScale: Float, fx: Float, fy: Float) {
        val k = newScale.coerceIn(minScale, maxScale) / scale
        m.postScale(k, k, fx, fy)
    }

    fun pan(dx: Float, dy: Float) {
        m.postTranslate(dx, dy)
    }

    /**
     * Keep at least 25% of the smaller dimension (page vs viewport) visible
     * on each axis, so the user can never lose the page completely.
     */
    fun clampPan(pageW: Float, pageH: Float, vw: Float, vh: Float) {
        val s = scale
        val pw = pageW * s
        val ph = pageH * s
        val minVisX = minOf(vw, pw) * 0.25f
        val minVisY = minOf(vh, ph) * 0.25f
        val tx = m.transX.coerceIn(minVisX - pw, vw - minVisX)
        val ty = m.transY.coerceIn(minVisY - ph, vh - minVisY)
        m.postTranslate(tx - m.transX, ty - m.transY)
    }

    fun toPageX(sx: Float, sy: Float): Float {
        val p = floatArrayOf(sx, sy)
        if (!m.inverseMapPoints(p, 0, 1)) return Float.NaN
        return p[0]
    }

    /** Maps a screen point to page space; returns false if the matrix is singular. */
    fun toPage(sx: Float, sy: Float, out: FloatArray): Boolean {
        out[0] = sx
        out[1] = sy
        return m.inverseMapPoints(out, 0, 1)
    }

    fun toScreen(px: Float, py: Float, out: FloatArray) {
        out[0] = px
        out[1] = py
        m.mapPoints(out, 0, 1)
    }

    /** Fits the page width to the viewport width (no margins in M1). */
    fun fitToWidth(pageW: Float, pageH: Float, vw: Float, vh: Float) {
        val s = (vw / maxOf(pageW, 1f)).coerceIn(minScale, maxScale)
        val tx = 0f
        val ty = (vh - pageH * s) / 2f
        setIdentityScaleTranslate(s, tx, ty)
    }

    /** Fits the whole page inside the viewport. */
    fun fitToPage(pageW: Float, pageH: Float, vw: Float, vh: Float) {
        val s = minOf(vw / maxOf(pageW, 1f), vh / maxOf(pageH, 1f)).coerceIn(minScale, maxScale)
        val tx = (vw - pageW * s) / 2f
        val ty = (vh - pageH * s) / 2f
        setIdentityScaleTranslate(s, tx, ty)
    }

    /** Sets M = | s 0 tx | directly (used by the double-tap animator). */
    ///        | 0 s ty |
    fun setScaleTranslate(s: Float, tx: Float, ty: Float) = setIdentityScaleTranslate(s, tx, ty)

    private fun setIdentityScaleTranslate(s: Float, tx: Float, ty: Float) {
        val v = m.v
        v[0] = s; v[1] = 0f; v[2] = tx
        v[3] = 0f; v[4] = s; v[5] = ty
        v[6] = 0f; v[7] = 0f; v[8] = 1f
    }
}


