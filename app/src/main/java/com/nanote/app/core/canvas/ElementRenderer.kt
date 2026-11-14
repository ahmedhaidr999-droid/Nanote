package com.nanote.app.core.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import com.nanote.app.core.model.ShapeElement
import com.nanote.app.core.model.ShapeKind
import com.nanote.app.core.model.Stroke
import com.nanote.app.core.model.StrokePoint
import com.nanote.app.core.model.TextElement
import com.nanote.app.core.model.highlighterAlphaOf
import com.nanote.app.core.model.paintAlphaOf
import com.nanote.app.util.AppLog
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Draws strokes/shapes/texts/images/annotations in WORLD coordinates.
 * - Ink strokes = variable-width smoothed outline polygons (filled, anti-aliased).
 * - Highlighter = flat wide stroke, alpha capped so text stays readable, SRC_OVER.
 * - Paths & text layouts are cached per element object (WeakHashMap → replaced
 *   elements invalidate automatically); image bitmaps in a small LRU.
 * All Paints are preallocated — no allocations inside per-frame draws.
 */
class ElementRenderer(private val context: Context) {

    companion object {
        private const val TAG = "ElementRenderer"
        private const val SMOOTH = 0.35f // outline smoothing factor
    }

    // ---------------- paints (preallocated) ----------------

    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    // ---------------- caches ----------------

    private val strokePathCache = WeakHashMap<Stroke, Path>()
    private val shapePathCache = WeakHashMap<ShapeElement, Path>()
    private val textLayoutCache = WeakHashMap<TextElement, StaticLayout>()
    private val imageCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val imageLock = Any()

    // scratch objects for in-progress stroke
    val scratchPath = Path()
    private val scratchPath2 = Path()

    // ---------------- strokes ----------------

    fun drawStroke(c: Canvas, s: Stroke, pressureEnabled: Boolean) {
        if (s.points.isEmpty()) return
        when (s.tool) {
            com.nanote.app.core.model.ToolType.HIGHLIGHTER -> drawHighlighter(c, s)
            else -> drawInk(c, s, pressureEnabled)
        }
    }

    private fun drawInk(c: Canvas, s: Stroke, pressureEnabled: Boolean) {
        val path = strokePathCache.getOrPut(s) { buildInkPath(s, pressureEnabled) }
        inkPaint.color = colorOf(s.color, paintAlphaOf(s.opacity))
        c.drawPath(path, inkPaint)
    }

    private fun drawHighlighter(c: Canvas, s: Stroke) {
        val path = strokePathCache.getOrPut(s) { buildHighlighterPath(s) }
        strokePaint.color = colorOf(s.color, highlighterAlphaOf(s.opacity))
        strokePaint.strokeWidth = s.width
        // SRC_OVER with alpha keeps underlying text readable at every opacity.
        c.drawPath(path, strokePaint)
    }

    /** Variable-width smoothed outline (fill) — quality pen/pencil/brush ink. */
    private fun buildInkPath(s: Stroke, pressureEnabled: Boolean): Path {
        val pts = s.points
        val path = Path()
        if (pts.size == 1) {
            val w = widthFor(s, pts[0], pressureEnabled) / 2f
            path.addCircle(pts[0].x, pts[0].y, w, Path.Direction.CW)
            return path
        }
        val n = pts.size
        val leftX = FloatArray(n); val leftY = FloatArray(n)
        val rightX = FloatArray(n); val rightY = FloatArray(n)
        for (i in 0 until n) {
            val p = pts[i]
            val prev = pts[maxOf(0, i - 1)]
            val next = pts[minOf(n - 1, i + 1)]
            var dx = next.x - prev.x
            var dy = next.y - prev.y
            val len = hypot(dx, dy)
            if (len < 0.0001f) { dx = 1f; dy = 0f } else { dx /= len; dy /= len }
            val half = widthFor(s, p, pressureEnabled) / 2f
            // perpendicular
            leftX[i] = p.x - dy * half; leftY[i] = p.y + dx * half
            rightX[i] = p.x + dy * half; rightY[i] = p.y - dx * half
        }
        path.moveTo(leftX[0], leftY[0])
        var i = 1
        while (i < n - 1) {
            val mx = (leftX[i] + leftX[i + 1]) / 2f
            val my = (leftY[i] + leftY[i + 1]) / 2f
            path.quadTo(leftX[i], leftY[i], mx, my)
            i++
        }
        path.lineTo(leftX[n - 1], leftY[n - 1])
        // rounded end caps
        path.quadTo(
            pts[n - 1].x + (rightX[n - 1] - pts[n - 1].x) * 0.5f, pts[n - 1].y + (rightY[n - 1] - pts[n - 1].y) * 0.5f,
            rightX[n - 1], rightY[n - 1]
        )
        i = n - 2
        while (i > 0) {
            val mx = (rightX[i] + rightX[i - 1]) / 2f
            val my = (rightY[i] + rightY[i - 1]) / 2f
            path.quadTo(rightX[i], rightY[i], mx, my)
            i--
        }
        path.lineTo(rightX[0], rightY[0])
        path.quadTo(
            pts[0].x + (leftX[0] - pts[0].x) * 0.5f, pts[0].y + (leftY[0] - pts[0].y) * 0.5f,
            leftX[0], leftY[0]
        )
        path.close()
        return path
    }

    private fun widthFor(s: Stroke, p: StrokePoint, pressureEnabled: Boolean): Float {
        if (!pressureEnabled) return s.width
        val pr = p.p.coerceIn(0.05f, 1f)
        return s.width * (0.45f + 0.75f * pr) // ~0.45x..1.2x of base width
    }

    /** Highlighter path: quad-smoothed centerline; width applied by paint. */
    private fun buildHighlighterPath(s: Stroke): Path {
        val pts = s.points
        val path = Path()
        if (pts.size == 1) {
            path.moveTo(pts[0].x, pts[0].y)
            path.lineTo(pts[0].x + 0.1f, pts[0].y)
            return path
        }
        path.moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size - 1) {
            val mx = (pts[i].x + pts[i + 1].x) / 2f
            val my = (pts[i].y + pts[i + 1].y) / 2f
            path.quadTo(pts[i].x, pts[i].y, mx, my)
        }
        path.lineTo(pts[pts.size - 1].x, pts[pts.size - 1].y)
        return path
    }

    /** Builds the live in-progress ink path into the reusable scratch path. */
    fun buildLiveInkPath(pts: List<StrokePoint>, baseWidth: Float, pressureEnabled: Boolean): Path {
        val p = scratchPath
        p.rewind()
        if (pts.isEmpty()) return p
        if (pts.size == 1) {
            p.addCircle(pts[0].x, pts[0].y, baseWidth * 0.5f, Path.Direction.CW)
            return p
        }
        val n = pts.size
        // stride keeps building cheap for long strokes (visual difference negligible)
        val stride = if (n > 400) 2 else 1
        val leftX = liveArr1; val leftY = liveArr2
        val rightX = liveArr3; val rightY = liveArr4
        var m = 0
        var i = 0
        while (i < n) {
            val pt = pts[i]
            val prev = pts[maxOf(0, i - stride)]
            val next = pts[minOf(n - 1, i + stride)]
            var dx = next.x - prev.x
            var dy = next.y - prev.y
            val len = hypot(dx, dy)
            if (len < 0.0001f) { dx = 1f; dy = 0f } else { dx /= len; dy /= len }
            val pr = if (pressureEnabled) pt.p.coerceIn(0.05f, 1f) else 1f
            val half = baseWidth * (0.45f + 0.75f * pr) / 2f
            leftX[m] = pt.x - dy * half; leftY[m] = pt.y + dx * half
            rightX[m] = pt.x + dy * half; rightY[m] = pt.y - dx * half
            m++
            i += stride
        }
        if (m == 1) {
            p.addCircle(pts[0].x, pts[0].y, baseWidth / 2f, Path.Direction.CW)
            return p
        }
        p.moveTo(leftX[0], leftY[0])
        var k = 1
        while (k < m - 1) {
            val mx = (leftX[k] + leftX[k + 1]) / 2f
            val my = (leftY[k] + leftY[k + 1]) / 2f
            p.quadTo(leftX[k], leftY[k], mx, my)
            k++
        }
        p.lineTo(leftX[m - 1], leftY[m - 1])
        k = m - 2
        while (k >= 0) {
            val mx = (rightX[k] + rightX[maxOf(0, k - 1)]) / 2f
            val my = (rightY[k] + rightY[maxOf(0, k - 1)]) / 2f
            p.quadTo(rightX[k], rightY[k], mx, my)
            k--
        }
        p.close()
        return p
    }

    private val liveArr1 = FloatArray(4096)
    private val liveArr2 = FloatArray(4096)
    private val liveArr3 = FloatArray(4096)
    private val liveArr4 = FloatArray(4096)

    fun drawLiveInk(c: Canvas, path: Path, color: Long, width: Float, opacity: Int, highlighter: Boolean) {
        if (highlighter) {
            strokePaint.color = colorOf(color, highlighterAlphaOf(opacity))
            strokePaint.strokeWidth = width
            c.drawPath(path, strokePaint)
        } else {
            inkPaint.color = colorOf(color, paintAlphaOf(opacity))
            c.drawPath(path, inkPaint)
        }
    }

    // ---------------- shapes ----------------

    fun drawShape(c: Canvas, s: ShapeElement) {
        val path = shapePathCache.getOrPut(s) { buildShapePath(s) }
        strokePaint.color = colorOf(s.color, paintAlphaOf(s.opacity))
        strokePaint.strokeWidth = s.width
        c.drawPath(path, strokePaint)
    }

    private fun buildShapePath(s: ShapeElement): Path {
        val p = Path()
        val x1 = s.x1; val y1 = s.y1; val x2 = s.x2; val y2 = s.y2
        when (s.kind) {
            ShapeKind.LINE -> { p.moveTo(x1, y1); p.lineTo(x2, y2) }
            ShapeKind.ARROW -> {
                p.moveTo(x1, y1); p.lineTo(x2, y2)
                val angle = atan2(y2 - y1, x2 - x1)
                val headLen = (hypot(x2 - x1, y2 - y1) * 0.2f).coerceIn(10f, 40f)
                val a1 = angle + Math.toRadians(150.0).toFloat()
                val a2 = angle - Math.toRadians(150.0).toFloat()
                p.moveTo(x2, y2)
                p.lineTo(x2 + headLen * kotlin.math.cos(a1), y2 + headLen * kotlin.math.sin(a1))
                p.moveTo(x2, y2)
                p.lineTo(x2 + headLen * kotlin.math.cos(a2), y2 + headLen * kotlin.math.sin(a2))
            }
            ShapeKind.RECT -> p.addRect(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2), Path.Direction.CW)
            ShapeKind.CIRCLE -> {
                p.addOval(RectF(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2)), Path.Direction.CW)
            }
            ShapeKind.TRIANGLE -> {
                val cx = (x1 + x2) / 2f
                p.moveTo(cx, minOf(y1, y2))
                p.lineTo(maxOf(x1, x2), maxOf(y1, y2))
                p.lineTo(minOf(x1, x2), maxOf(y1, y2))
                p.close()
            }
        }
        return p
    }

    fun drawLiveShape(c: Canvas, kind: ShapeKind, x1: Float, y1: Float, x2: Float, y2: Float, color: Long, width: Float) {
        val s = ShapeElement("live", 0, kind, color, width, 100, x1, y1, x2, y2)
        val path = scratchPath2
        path.set(buildShapePath(s))
        strokePaint.color = colorOf(color, 255)
        strokePaint.strokeWidth = width
        c.drawPath(path, strokePaint)
    }

    // ---------------- texts ----------------

    fun drawText(c: Canvas, t: TextElement) {
        val layout = textLayoutCache.getOrPut(t) { buildTextLayout(t) }
        if (t.sticky) {
            inkPaint.color = colorOf(0xFFFEF08A, 255)
            c.drawRoundRect(t.x - 8f, t.y - 6f, t.x + t.width + 8f, t.y + layout.height + 14f, 10f, 10f, inkPaint)
        }
        c.save()
        c.translate(t.x, t.y)
        layout.draw(c)
        c.restore()
    }

    private fun buildTextLayout(t: TextElement): StaticLayout {
        textPaint.color = colorOf(t.color, 255)
        textPaint.textSize = t.size
        textPaint.isSubpixelText = true
        val width = t.width.toInt().coerceAtLeast(24)
        return StaticLayout.Builder.obtain(t.text, 0, t.text.length, textPaint, width)
            .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.15f)
            .build()
    }

    fun measureTextHeight(t: TextElement): Int {
        val layout = textLayoutCache.getOrPut(t) { buildTextLayout(t) }
        return layout.height
    }

    // ---------------- images ----------------

    fun drawImage(c: Canvas, img: com.nanote.app.core.model.ImageElement, file: java.io.File) {
        val bmp = imageFor(file.path) ?: return
        val src = imgRect
        src.set(img.x, img.y, img.x + img.w, img.y + img.h)
        c.save()
        c.rotate(img.rotation, img.x + img.w / 2f, img.y + img.h / 2f)
        c.drawBitmap(bmp, null, src, bitmapPaint)
        c.restore()
    }

    private val imgRect = RectF()

    fun imageFor(path: String): Bitmap? {
        synchronized(imageLock) {
            imageCache.get(path)?.let { return it }
        }
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, opts)
            val maxDim = maxOf(opts.outWidth, opts.outHeight)
            var sample = 1
            while (maxDim / sample > 2048) sample *= 2
            val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            synchronized(imageLock) { imageCache.put(path, bmp) }
            bmp
        } catch (t: Throwable) {
            AppLog.w(TAG, "image decode failed: ${t.message}")
            null
        }
    }

    // ---------------- annotations ----------------

    fun drawHighlightRect(c: Canvas, rect: RectF, color: Long, opacityPct: Int) {
        inkPaint.color = colorOf(color, highlighterAlphaOf(opacityPct))
        c.drawRect(rect, inkPaint)
    }

    fun drawUnderline(c: Canvas, x0: Float, y: Float, x1: Float, color: Long, opacityPct: Int) {
        strokePaint.color = colorOf(color, paintAlphaOf(opacityPct))
        strokePaint.strokeWidth = 1.6f
        c.drawLine(x0, y, x1, y, strokePaint)
    }

    fun clearCaches() {
        strokePathCache.clear()
        shapePathCache.clear()
        textLayoutCache.clear()
        synchronized(imageLock) { imageCache.evictAll() }
    }

    // ---------------- helpers ----------------

    private fun colorOf(argb: Long, alpha: Int): Int {
        val a = alpha.coerceIn(0, 255)
        val r = ((argb shr 16) and 0xFF).toInt()
        val g = ((argb shr 8) and 0xFF).toInt()
        val b = (argb and 0xFF).toInt()
        return Color.argb(a, r, g, b)
    }
}
