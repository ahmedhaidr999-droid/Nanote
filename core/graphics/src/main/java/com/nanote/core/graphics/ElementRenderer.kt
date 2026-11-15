package com.nanote.core.graphics

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import com.nanote.core.model.EditorFont
import com.nanote.core.model.ImageElement
import com.nanote.core.model.PressureCurve
import com.nanote.core.model.ShapeElement
import com.nanote.core.model.ShapeKind
import com.nanote.core.model.Stroke
import com.nanote.core.model.StrokePoint
import com.nanote.core.model.TextAlignMode
import com.nanote.core.model.TextElement
import com.nanote.core.model.TextListStyle
import com.nanote.core.model.ToolType
import com.nanote.core.model.highlighterAlphaOf
import com.nanote.core.model.paintAlphaOf
import com.nanote.core.model.pressureCurveApply
import com.nanote.core.common.AppLog
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws strokes/shapes/texts/images/annotations in WORLD coordinates (§1 —
 * rendering is fully separated from stroke data & input).
 *
 * Part 2 pen engines (§2):
 *  - BALLPOINT  : constant thin line, subtle pressure modulation.
 *  - FOUNTAIN   : pressure × velocity width (slow = wet & fat, fast = thin).
 *  - BRUSH      : strong pressure taper with rounded wet caps.
 *  - PENCIL     : seeded graphite grain — several jittered sub-strokes.
 *  - HIGHLIGHTER: flat wide stroke, alpha capped so text stays readable.
 *
 * Ink strokes = variable-width smoothed outline polygons (filled, anti-aliased).
 * Paths & text layouts are cached per element object (WeakHashMap → replaced
 * elements invalidate automatically); image bitmaps in a small LRU.
 * All Paints are preallocated — no allocations inside per-frame draws.
 */
class ElementRenderer(private val context: Context) {

    companion object {
        private const val TAG = "ElementRenderer"
    }

    // ------------- global pressure curve (§3) -------------

    var pressureCurve: PressureCurve = PressureCurve.LINEAR

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
    private val widthBuf = FloatArray(8192)

    // =====================================================================
    // Strokes — per-tool engines (§2)
    // =====================================================================

    fun drawStroke(c: Canvas, s: Stroke, pressureEnabled: Boolean) {
        if (s.points.isEmpty()) return
        when (s.tool) {
            ToolType.HIGHLIGHTER -> drawHighlighter(c, s)
            ToolType.PENCIL -> drawPencil(c, s, pressureEnabled)
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

    /** Graphite grain: outline + deterministic jittered sub-strokes, low alpha. */
    private fun drawPencil(c: Canvas, s: Stroke, pressureEnabled: Boolean) {
        val path = strokePathCache.getOrPut(s) { buildPencilPath(s, pressureEnabled) }
        strokePaint.color = colorOf(s.color, (paintAlphaOf(s.opacity) * 0.82f).toInt().coerceIn(8, 255))
        strokePaint.strokeWidth = (s.width * 0.30f).coerceAtLeast(0.5f)
        c.drawPath(path, strokePaint)
    }

    /**
     * Per-point visual width for a committed stroke.
     * [velocity] in world units / second when timestamps exist, else -1.
     */
    private fun widthFor(s: Stroke, p: StrokePoint, velocity: Float, pressureEnabled: Boolean): Float {
        val response = if (pressureEnabled) s.pressureResponse else 0f
        val raw = p.p.coerceIn(0.04f, 1f)
        val curved = pressureCurveApply(pressureCurve, raw)
        return when (s.tool) {
            ToolType.PEN -> s.width * (0.82f + 0.28f * (curved * response + (1f - response)))          // subtle
            ToolType.FOUNTAIN -> {
                // velocity term: 1.0 (slow/fat) .. 0.55 (fast/thin)
                val speedTerm = if (velocity < 0f) 0.8f else (1f - (velocity / 2600f).coerceIn(0f, 0.45f))
                s.width * (0.38f + 0.78f * curved * response + (1f - response) * 0.5f) * speedTerm
            }
            ToolType.BRUSH -> {
                val t = curved * response + (1f - response)
                s.width * (0.16f + 0.90f * t * t)                                                     // strong taper
            }
            else -> s.width
        }
    }

    /** Variable-width smoothed outline (fill) — ballpoint / fountain / brush. */
    private fun buildInkPath(s: Stroke, pressureEnabled: Boolean): Path {
        val pts = s.points
        val path = Path()
        val widths = widthsFor(s, pressureEnabled)
        if (pts.size == 1) {
            path.addCircle(pts[0].x, pts[0].y, widths[0] / 2f, Path.Direction.CW)
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
            val half = widths[i] / 2f
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

    /**
     * Pencil texture: 3 sub-strokes offset perpendicular by seeded jitter,
     * each varying in pressure → grainy graphite look, stable across frames.
     */
    private fun buildPencilPath(s: Stroke, pressureEnabled: Boolean): Path {
        val pts = s.points
        val path = Path()
        if (pts.isEmpty()) return path
        val widths = widthsFor(s, pressureEnabled)
        val seed = s.id.hashCode()
        val rnd = java.util.Random(seed.toLong())
        val subStrokes = 3
        for (k in 0 until subStrokes) {
            val lateral = (k - 1) * widths[0] * 0.16f
            val jitter = (rnd.nextFloat() - 0.5f) * 0.6f
            var started = false
            var i = 0
            val stride = if (pts.size > 500) 2 else 1
            while (i < pts.size) {
                val p = pts[i]
                val prev = pts[maxOf(0, i - stride)]
                val next = pts[minOf(pts.size - 1, i + stride)]
                var dx = next.x - prev.x
                var dy = next.y - prev.y
                val len = hypot(dx, dy)
                if (len < 0.0001f) { dx = 1f; dy = 0f } else { dx /= len; dy /= len }
                val off = lateral + (rnd.nextFloat() - 0.5f) * widths[minOf(i, widths.size - 1)] * 0.22f + jitter * 0.4f
                val x = p.x - dy * off
                val y = p.y + dx * off
                if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                i += stride
            }
        }
        // dot for taps
        if (pts.size == 1) path.addCircle(pts[0].x, pts[0].y, widths[0] * 0.4f, Path.Direction.CW)
        return path
    }

    /** Computes per-point widths (velocity-aware for the fountain pen). */
    private fun widthsFor(s: Stroke, pressureEnabled: Boolean): FloatArray {
        val pts = s.points
        val n = pts.size
        val out = FloatArray(maxOf(n, 1))
        if (n == 0) { out[0] = s.width; return out }
        if (n == 1) { out[0] = widthFor(s, pts[0], -1f, pressureEnabled); return out }
        // velocities in world/sec (0 when timestamps missing)
        val vels = FloatArray(n)
        for (i in 1 until n) {
            val dt = (pts[i].t - pts[i - 1].t)
            val d = hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y)
            vels[i] = if (dt > 0) (d / dt * 1e9f) else -1f
        }
        vels[0] = vels[1]
        for (i in 0 until n) out[i] = widthFor(s, pts[i], vels[i], pressureEnabled)
        // taper first/last few points for brush & fountain (natural entry/exit)
        if (s.tool == ToolType.BRUSH || s.tool == ToolType.FOUNTAIN) {
            val taper = if (s.tool == ToolType.BRUSH) 5 else 3
            for (k in 0 until min(taper, n / 3)) {
                val f = (k + 1).toFloat() / (taper + 1)
                out[k] *= (0.35f + 0.65f * f)
                out[n - 1 - k] *= (0.35f + 0.65f * f)
            }
        }
        return out
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
    fun buildLiveInkPath(
        pts: List<StrokePoint>,
        baseWidth: Float,
        pressureEnabled: Boolean,
        tool: ToolType = ToolType.PEN,
        pressureResponse: Float = 0.6f,
        velocityHint: Float = -1f
    ): Path {
        val p = scratchPath
        p.rewind()
        if (pts.isEmpty()) return p
        val probe = Stroke("live", 0, tool, 0, baseWidth, 100, pts, pressureResponse = pressureResponse)
        if (pts.size == 1) {
            val w = widthFor(probe, pts[0], -1f, pressureEnabled)
            p.addCircle(pts[0].x, pts[0].y, w * 0.5f, Path.Direction.CW)
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
            val half = widthFor(probe, pt, velocityHint, pressureEnabled) / 2f
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

    fun drawLiveInk(c: Canvas, path: Path, color: Long, width: Float, opacity: Int, tool: ToolType) {
        if (tool == ToolType.HIGHLIGHTER) {
            strokePaint.color = colorOf(color, highlighterAlphaOf(opacity))
            strokePaint.strokeWidth = width
            c.drawPath(path, strokePaint)
        } else if (tool == ToolType.PENCIL) {
            strokePaint.color = colorOf(color, (paintAlphaOf(opacity) * 0.82f).toInt().coerceIn(8, 255))
            strokePaint.strokeWidth = (width * 0.30f).coerceAtLeast(0.5f)
            c.drawPath(path, strokePaint)
        } else {
            inkPaint.color = colorOf(color, paintAlphaOf(opacity))
            c.drawPath(path, inkPaint)
        }
    }

    // =====================================================================
    // Shapes (§14 — 9 kinds, fill, rotation)
    // =====================================================================

    fun drawShape(c: Canvas, s: ShapeElement) {
        val path = shapePathCache.getOrPut(s) { buildShapePath(s) }
        if (s.filled) {
            inkPaint.color = colorOf(s.fillColor, paintAlphaOf(s.opacity))
            c.save()
            rotateShapeCanvas(c, s)
            c.drawPath(path, inkPaint)
            c.restore()
        }
        strokePaint.color = colorOf(s.color, paintAlphaOf(s.opacity))
        strokePaint.strokeWidth = s.width
        c.save()
        rotateShapeCanvas(c, s)
        c.drawPath(path, strokePaint)
        c.restore()
    }

    private fun rotateShapeCanvas(c: Canvas, s: ShapeElement) {
        if (s.rotation != 0f) {
            val cx = (s.x1 + s.x2) / 2f
            val cy = (s.y1 + s.y2) / 2f
            c.rotate(s.rotation, cx, cy)
        }
    }

    private fun buildShapePath(s: ShapeElement): Path {
        val p = Path()
        val x1 = s.x1; val y1 = s.y1; val x2 = s.x2; val y2 = s.y2
        when (s.kind) {
            ShapeKind.LINE -> { p.moveTo(x1, y1); p.lineTo(x2, y2) }
            ShapeKind.ARROW -> {
                p.moveTo(x1, y1); p.lineTo(x2, y2)
                addArrowHead(p, x1, y1, x2, y2)
            }
            ShapeKind.DOUBLE_ARROW -> {
                p.moveTo(x1, y1); p.lineTo(x2, y2)
                addArrowHead(p, x1, y1, x2, y2)
                addArrowHead(p, x2, y2, x1, y1)
            }
            ShapeKind.RECT -> p.addRect(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2), Path.Direction.CW)
            ShapeKind.ROUNDED_RECT -> {
                val r = (minOf(abs(x2 - x1), abs(y2 - y1)) * 0.18f).coerceIn(4f, 40f)
                p.addRoundRect(
                    RectF(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2)),
                    r, r, Path.Direction.CW
                )
            }
            ShapeKind.CIRCLE -> {
                val cx = (x1 + x2) / 2f; val cy = (y1 + y2) / 2f
                p.addCircle(cx, cy, minOf(abs(x2 - x1), abs(y2 - y1)) / 2f, Path.Direction.CW)
            }
            ShapeKind.ELLIPSE -> {
                p.addOval(RectF(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2)), Path.Direction.CW)
            }
            ShapeKind.TRIANGLE -> {
                val cx = (x1 + x2) / 2f
                p.moveTo(cx, minOf(y1, y2))
                p.lineTo(maxOf(x1, x2), maxOf(y1, y2))
                p.lineTo(minOf(x1, x2), maxOf(y1, y2))
                p.close()
            }
            ShapeKind.POLYGON -> {
                if (s.vertices.size >= 6) {
                    val l = minOf(x1, x2); val t = minOf(y1, y2)
                    val w = abs(x2 - x1); val h = abs(y2 - y1)
                    var i = 0
                    while (i + 1 < s.vertices.size) {
                        val vx = l + s.vertices[i].coerceIn(0f, 1f) * w
                        val vy = t + s.vertices[i + 1].coerceIn(0f, 1f) * h
                        if (i == 0) p.moveTo(vx, vy) else p.lineTo(vx, vy)
                        i += 2
                    }
                    p.close()
                } else {
                    p.addRect(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2), Path.Direction.CW)
                }
            }
        }
        return p
    }

    private fun addArrowHead(p: Path, fromX: Float, fromY: Float, tipX: Float, tipY: Float) {
        val angle = atan2(tipY - fromY, tipX - fromX)
        val headLen = (hypot(tipX - fromX, tipY - fromY) * 0.2f).coerceIn(10f, 40f)
        val a1 = angle + Math.toRadians(150.0).toFloat()
        val a2 = angle - Math.toRadians(150.0).toFloat()
        p.moveTo(tipX, tipY)
        p.lineTo(tipX + headLen * cos(a1), tipY + headLen * sin(a1))
        p.moveTo(tipX, tipY)
        p.lineTo(tipX + headLen * cos(a2), tipY + headLen * sin(a2))
    }

    fun drawLiveShape(
        c: Canvas, kind: ShapeKind, x1: Float, y1: Float, x2: Float, y2: Float,
        color: Long, width: Float,
        fillColor: Long = 0, filled: Boolean = false, rotation: Float = 0f
    ) {
        val s = ShapeElement("live", 0, kind, color, width, 100, x1, y1, x2, y2, fillColor, filled, rotation)
        val path = scratchPath2
        path.set(buildShapePath(s))
        if (filled) {
            inkPaint.color = colorOf(fillColor, 82)
            c.save()
            if (rotation != 0f) c.rotate(rotation, (x1 + x2) / 2f, (y1 + y2) / 2f)
            c.drawPath(path, inkPaint)
            c.restore()
        }
        strokePaint.color = colorOf(color, 255)
        strokePaint.strokeWidth = width
        c.save()
        if (rotation != 0f) c.rotate(rotation, (x1 + x2) / 2f, (y1 + y2) / 2f)
        c.drawPath(path, strokePaint)
        c.restore()
    }

    // =====================================================================
    // Texts (§12 — fonts, styles, lists, alignment, bidi)
    // =====================================================================

    fun drawText(c: Canvas, t: TextElement) {
        val layout = textLayoutCache.getOrPut(t) { buildTextLayout(t) }
        if (t.sticky) {
            inkPaint.color = colorOf(0xFFFEF08A, 255)
            c.drawRoundRect(t.x - 8f, t.y - 6f, t.x + t.width + 8f, t.y + layout.height + 14f, 10f, 10f, inkPaint)
        } else if (t.bgColor != 0L) {
            inkPaint.color = t.bgColor.toInt()
            c.drawRoundRect(t.x - 6f, t.y - 4f, t.x + t.width + 6f, t.y + layout.height + 6f, 6f, 6f, inkPaint)
        }
        c.save()
        c.translate(t.x, t.y)
        if (t.rotation != 0f) c.rotate(t.rotation, 0f, 0f)
        layout.draw(c)
        c.restore()
    }

    private fun buildTextLayout(t: TextElement): StaticLayout {
        textPaint.color = colorOf(t.color, 255)
        textPaint.textSize = t.size
        textPaint.isSubpixelText = true
        textPaint.isUnderlineText = t.underline
        textPaint.isStrikeThruText = t.strikethrough
        textPaint.typeface = when (t.font) {
            EditorFont.DEFAULT -> android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT,
                typefaceStyle(t)
            )
            EditorFont.SERIF -> android.graphics.Typeface.create(
                android.graphics.Typeface.SERIF,
                typefaceStyle(t)
            )
            EditorFont.MONOSPACE -> android.graphics.Typeface.create(
                android.graphics.Typeface.MONOSPACE,
                typefaceStyle(t)
            )
            EditorFont.CASUAL -> android.graphics.Typeface.create("casual", typefaceStyle(t))
            EditorFont.CURSIVE -> android.graphics.Typeface.create("cursive", typefaceStyle(t))
            EditorFont.SANS_SERIF_SMALL_CAPS -> android.graphics.Typeface.create(
                android.graphics.Typeface.SANS_SERIF,
                typefaceStyle(t)
            )
        }
        val content = textForLayout(t)
        val width = t.width.toInt().coerceAtLeast(24)
        val alignment = when (t.align) {
            TextAlignMode.START -> Layout.Alignment.ALIGN_NORMAL   // bidi-aware start
            TextAlignMode.CENTER -> Layout.Alignment.ALIGN_CENTER
            TextAlignMode.END -> Layout.Alignment.ALIGN_OPPOSITE   // bidi-aware end
        }
        return StaticLayout.Builder.obtain(content, 0, content.length, textPaint, width)
            .setAlignment(alignment)
            .setLineSpacing(0f, 1.15f)
            .build()
    }

    private fun typefaceStyle(t: TextElement): Int = when {
        t.bold && t.italic -> android.graphics.Typeface.BOLD_ITALIC
        t.bold -> android.graphics.Typeface.BOLD
        t.italic -> android.graphics.Typeface.ITALIC
        else -> android.graphics.Typeface.NORMAL
    }

    /** Renders the text with list prefixes into a plain string for layout. */
    fun textForLayout(t: TextElement): String = when (t.listStyle) {
        TextListStyle.NONE -> t.text
        TextListStyle.BULLET -> t.text.lines().joinToString("\n") { if (it.isBlank()) it else "•  $it" }
        TextListStyle.NUMBERED -> t.text.lines().mapIndexed { i, line ->
            if (line.isBlank()) line else "${i + 1}.  $line"
        }.joinToString("\n")
    }

    fun measureTextHeight(t: TextElement): Int {
        val layout = textLayoutCache.getOrPut(t) { buildTextLayout(t) }
        return layout.height
    }

    // =====================================================================
    // Images (§13)
    // =====================================================================

    fun drawImage(c: Canvas, img: ImageElement, file: java.io.File) {
        val bmp = imageFor(file.path) ?: return
        val src = imgRect
        src.set(img.x, img.y, img.x + img.w, img.y + img.h)
        c.save()
        if (img.rotation != 0f) c.rotate(img.rotation, img.x + img.w / 2f, img.y + img.h / 2f)
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

    /** Decodes only the region selected by a normalized crop rect [x0,y0,x1,y1]. */
    fun croppedBitmap(path: String, crop: List<Float>): Bitmap? {
        if (crop.size != 4) return imageFor(path)
        val full = imageFor(path) ?: return null
        val x0 = (crop[0].coerceIn(0f, 1f) * full.width).toInt().coerceIn(0, full.width - 1)
        val y0 = (crop[1].coerceIn(0f, 1f) * full.height).toInt().coerceIn(0, full.height - 1)
        val x1 = (crop[2].coerceIn(0f, 1f) * full.width).toInt().coerceIn(x0 + 1, full.width)
        val y1 = (crop[3].coerceIn(0f, 1f) * full.height).toInt().coerceIn(y0 + 1, full.height)
        return try {
            Bitmap.createBitmap(full, x0, y0, x1 - x0, y1 - y0)
        } catch (t: Throwable) {
            AppLog.w(TAG, "crop failed: ${t.message}")
            null
        }
    }

    // =====================================================================
    // Annotations
    // =====================================================================

    fun drawHighlightRect(c: Canvas, rect: RectF, color: Long, opacityPct: Int) {
        inkPaint.color = colorOf(color, highlighterAlphaOf(opacityPct))
        c.drawRect(rect, inkPaint)
    }

    fun drawUnderline(c: Canvas, x0: Float, y: Float, x1: Float, color: Long, opacityPct: Int) {
        strokePaint.color = colorOf(color, paintAlphaOf(opacityPct))
        strokePaint.strokeWidth = 1.6f
        c.drawLine(x0, y, x1, y, strokePaint)
    }

    fun drawStrikeThrough(c: Canvas, x0: Float, y: Float, x1: Float, color: Long, opacityPct: Int) {
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

    // =====================================================================
    // Helpers
    // =====================================================================

    private fun colorOf(argb: Long, alpha: Int): Int {
        val a = alpha.coerceIn(0, 255)
        val r = ((argb shr 16) and 0xFF).toInt()
        val g = ((argb shr 8) and 0xFF).toInt()
        val b = (argb and 0xFF).toInt()
        return Color.argb(a, r, g, b)
    }
}
