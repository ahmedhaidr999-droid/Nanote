package com.nanote.core.model

import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.sin

/**
 * World <-> screen transforms.
 * screen = world * scale + pan   (pan expressed in screen px)
 */
class ViewportTransform {
    var scale: Float = 1f
        private set
    var panX: Float = 0f
        private set
    var panY: Float = 0f
        private set

    val minScale: Float get() = 0.5f
    val maxScale: Float get() = 10f

    fun worldToScreenX(x: Float): Float = x * scale + panX
    fun worldToScreenY(y: Float): Float = y * scale + panY
    fun screenToWorldX(x: Float): Float = (x - panX) / scale
    fun screenToWorldY(y: Float): Float = (y - panY) / scale

    fun worldLenToScreen(l: Float): Float = l * scale
    fun screenLenToWorld(l: Float): Float = l / scale

    fun set(scale: Float, panX: Float, panY: Float) {
        this.scale = scale.coerceIn(minScale, maxScale)
        this.panX = panX
        this.panY = panY
    }

    /** Zoom keeping the world point under [focusScreenX/Y] stationary. */
    fun zoomAt(focusScreenX: Float, focusScreenY: Float, newScaleRaw: Float) {
        val newScale = newScaleRaw.coerceIn(minScale, maxScale)
        val wx = screenToWorldX(focusScreenX)
        val wy = screenToWorldY(focusScreenY)
        scale = newScale
        panX = focusScreenX - wx * newScale
        panY = focusScreenY - wy * newScale
    }

    fun panBy(dxScreen: Float, dyScreen: Float) {
        panX += dxScreen
        panY += dyScreen
    }
}

fun rectsIntersect(a: FloatArray, b: FloatArray): Boolean =
    a[0] <= b[2] && a[2] >= b[0] && a[1] <= b[3] && a[3] >= b[1]

fun rectContains(r: FloatArray, x: Float, y: Float): Boolean =
    x >= r[0] && x <= r[2] && y >= r[1] && y <= r[3]

/** Distance from point to polyline, in world units. */
fun distToPolyline(px: Float, py: Float, pts: List<StrokePoint>): Float {
    var best = Float.MAX_VALUE
    if (pts.isEmpty()) return best
    var prev = pts[0]
    for (i in 1 until pts.size) {
        val cur = pts[i]
        best = minOf(best, distToSegment(px, py, prev.x, prev.y, cur.x, cur.y))
        prev = cur
    }
    if (pts.size == 1) best = minOf(best, kotlin.math.hypot(px - pts[0].x, py - pts[0].y))
    return best
}

fun distToSegment(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
    val dx = x2 - x1
    val dy = y2 - y1
    val len2 = dx * dx + dy * dy
    val t = if (len2 == 0f) 0f else (((px - x1) * dx + (py - y1) * dy) / len2).coerceIn(0f, 1f)
    val cx = x1 + t * dx
    val cy = y1 + t * dy
    return kotlin.math.hypot(px - cx, py - cy)
}

/** Point-in-polygon (even-odd rule). */
fun pointInPolygon(x: Float, y: Float, poly: FloatArray): Boolean {
    var inside = false
    var j = poly.size - 2
    var i = 0
    while (i < poly.size) {
        val xi = poly[i]; val yi = poly[i + 1]
        val xj = poly[j]; val yj = poly[j + 1]
        if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
        j = i
        i += 2
    }
    return inside
}

fun expandRect(r: RectF, by: Float) {
    r.left -= by; r.top -= by; r.right += by; r.bottom += by
}

fun rotatePoint(x: Float, y: Float, cx: Float, cy: Float, angleDeg: Float): FloatArray {
    val a = Math.toRadians(angleDeg.toDouble())
    val rx = (x - cx) * cos(a) - (y - cy) * sin(a) + cx
    val ry = (x - cx) * sin(a) + (y - cy) * cos(a) + cy
    return floatArrayOf(rx.toFloat(), ry.toFloat())
}
