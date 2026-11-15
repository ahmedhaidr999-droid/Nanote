package com.nanote.core.pdf

import com.nanote.core.common.AppLog
import com.nanote.core.model.DocumentContent
import com.nanote.core.model.ShapeElement
import com.nanote.core.model.ShapeKind
import com.nanote.core.model.Stroke
import com.nanote.core.model.StrikeThroughAnnotation
import com.nanote.core.model.TextElement
import com.nanote.core.model.ToolType
import com.nanote.core.model.UnderlineAnnotation
import com.nanote.core.model.highlighterAlphaOf
import com.nanote.core.model.paintAlphaOf
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState

/**
 * Paints Nanote annotation content (ink, shapes, images, texts, text marks)
 * into real PDF page content streams — the single source of truth used by
 * every flatten-style export.
 *
 * PDF coordinates are bottom-left origin while Nanote world coordinates are
 * top-left origin: `y_pdf = pageHeight - y_world`.
 *
 * Every element draw is individually guarded: one broken element never
 * aborts the export.
 */
object PdfContentPainter {

    private const val TAG = "PdfContentPainter"

    /** Resolves an image element's relative path to a readable file. */
    fun interface ImageResolver {
        fun resolve(relativePath: String): File?
    }

    /**
     * Draws ALL annotations of [content] that live on [pageIndex] onto [page]
     * of [document] (content stream APPEND mode).
     *
     * @param imageResolver resolves image paths (relative to the doc dir)
     */
    fun paintPage(
        document: PDDocument,
        page: PDPage,
        content: DocumentContent,
        pageIndex: Int,
        imageResolver: ImageResolver
    ) {
        val pageW = page.mediaBox.width
        val pageH = page.mediaBox.height
        PDPageContentStream(
            document,
            page,
            PDPageContentStream.AppendMode.APPEND,
            /* compress = */ true,
            /* appendContent = */ true
        ).use { cs ->
            for (h in content.highlights) {
                if (h.page == pageIndex) drawHighlight(cs, h, pageW, pageH)
            }
            for (u in content.underlines) {
                if (u.page == pageIndex) drawUnderline(cs, u, pageW, pageH)
            }
            for (s in content.strikeThroughs) {
                if (s.page == pageIndex) drawStrikeThrough(cs, s, pageW, pageH)
            }
            for (s in content.shapes) {
                if (s.page == pageIndex) drawShape(cs, s, pageH)
            }
            for (img in content.images) {
                if (img.page == pageIndex) drawImage(cs, img, document, imageResolver, pageH)
            }
            for (txt in content.texts) {
                if (txt.page == pageIndex) drawText(cs, txt, pageH)
            }
            for (s in content.strokes) {
                if (s.page == pageIndex) drawStroke(cs, s, pageH)
            }
        }
    }

    // ------------------------------------------------------------------
    // text marks
    // ------------------------------------------------------------------

    private fun drawHighlight(
        cs: PDPageContentStream,
        h: com.nanote.core.model.HighlightAnnotation,
        pageW: Float,
        pageH: Float
    ) {
        try {
            val gs = PDExtendedGraphicsState()
            gs.nonStrokingAlphaConstant = highlighterAlphaOf(h.opacity) / 255f
            cs.setGraphicsStateParameters(gs)
            cs.setNonStrokingColor(red(h.color), green(h.color), blue(h.color))
            for (rect in h.rects) {
                if (rect.size < 4) continue
                // RelRect is [x0, y0, x1, y1] top-left origin in [0..1].
                val x0 = rect[0] * pageW
                val y0 = rect[1] * pageH
                val x1 = rect[2] * pageW
                val y1 = rect[3] * pageH
                if (x1 <= x0 || y1 <= y0) continue
                cs.addRect(x0, pageH - y1, x1 - x0, y1 - y0)
                cs.fill()
            }
            resetNonStrokingAlpha(cs)
        } catch (t: Throwable) {
            AppLog.e(TAG, "highlight ${h.id} failed", t)
        }
    }

    private fun drawUnderline(
        cs: PDPageContentStream,
        u: UnderlineAnnotation,
        pageW: Float,
        pageH: Float
    ) {
        try {
            val gs = PDExtendedGraphicsState()
            gs.strokingAlphaConstant = paintAlphaOf(u.opacity) / 255f
            cs.setGraphicsStateParameters(gs)
            cs.setStrokingColor(red(u.color), green(u.color), blue(u.color))
            cs.setLineWidth(1.5f)
            cs.setLineCapStyle(1) // round
            for (rect in u.rects) {
                if (rect.size < 4) continue
                val x0 = rect[0] * pageW
                val x1 = rect[2] * pageW
                if (x1 <= x0) continue
                val y = pageH - rect[3] * pageH // line at the rect bottom edge
                cs.moveTo(x0, y)
                cs.lineTo(x1, y)
                cs.stroke()
            }
            resetStrokingAlpha(cs)
        } catch (t: Throwable) {
            AppLog.e(TAG, "underline ${u.id} failed", t)
        }
    }

    private fun drawStrikeThrough(
        cs: PDPageContentStream,
        s: StrikeThroughAnnotation,
        pageW: Float,
        pageH: Float
    ) {
        try {
            val gs = PDExtendedGraphicsState()
            gs.strokingAlphaConstant = paintAlphaOf(s.opacity) / 255f
            cs.setGraphicsStateParameters(gs)
            cs.setStrokingColor(red(s.color), green(s.color), blue(s.color))
            cs.setLineWidth(1.5f)
            cs.setLineCapStyle(1) // round
            for (rect in s.rects) {
                if (rect.size < 4) continue
                val x0 = rect[0] * pageW
                val x1 = rect[2] * pageW
                if (x1 <= x0) continue
                // line through the vertical middle of the marked text
                val yTop = rect[1] * pageH
                val yBot = rect[3] * pageH
                val y = pageH - (yTop + yBot) / 2f
                cs.moveTo(x0, y)
                cs.lineTo(x1, y)
                cs.stroke()
            }
            resetStrokingAlpha(cs)
        } catch (t: Throwable) {
            AppLog.e(TAG, "strikethrough ${s.id} failed", t)
        }
    }

    // ------------------------------------------------------------------
    // ink
    // ------------------------------------------------------------------

    private fun drawStroke(cs: PDPageContentStream, stroke: Stroke, pageH: Float) {
        if (stroke.points.isEmpty()) return
        val highlighter = stroke.tool == ToolType.HIGHLIGHTER
        try {
            val gs = PDExtendedGraphicsState()
            gs.strokingAlphaConstant =
                (if (highlighter) highlighterAlphaOf(stroke.opacity) else paintAlphaOf(stroke.opacity)) / 255f
            cs.setGraphicsStateParameters(gs)
            cs.setStrokingColor(red(stroke.color), green(stroke.color), blue(stroke.color))
            cs.setLineCapStyle(1)   // round cap
            cs.setLineJoinStyle(1)  // round join
            if (stroke.points.size == 1) {
                val p = stroke.points[0]
                cs.setLineWidth(stroke.width.coerceAtLeast(0.1f))
                cs.moveTo(p.x, pageH - p.y)
                cs.lineTo(p.x + 0.1f, pageH - p.y)
                cs.stroke()
            } else {
                var prev = stroke.points[0]
                for (i in 1 until stroke.points.size) {
                    val cur = stroke.points[i]
                    // Variable pressure: each segment carries its own width.
                    val segWidth = ((prev.p + cur.p) / 2f) * stroke.width
                    cs.setLineWidth(segWidth.coerceAtLeast(0.1f))
                    cs.moveTo(prev.x, pageH - prev.y)
                    cs.lineTo(cur.x, pageH - cur.y)
                    cs.stroke()
                    prev = cur
                }
            }
            resetStrokingAlpha(cs)
        } catch (t: Throwable) {
            AppLog.e(TAG, "stroke ${stroke.id} failed", t)
        }
    }

    // ------------------------------------------------------------------
    // shapes
    // ------------------------------------------------------------------

    private fun drawShape(cs: PDPageContentStream, s: ShapeElement, pageH: Float) {
        try {
            cs.setStrokingColor(red(s.color), green(s.color), blue(s.color))
            cs.setLineWidth(s.width.coerceAtLeast(0.1f))
            cs.setLineCapStyle(1)
            cs.setLineJoinStyle(1)
            val x1 = s.x1
            val y1 = pageH - s.y1
            val x2 = s.x2
            val y2 = pageH - s.y2
            when (s.kind) {
                ShapeKind.LINE -> {
                    cs.moveTo(x1, y1)
                    cs.lineTo(x2, y2)
                    cs.stroke()
                }
                ShapeKind.ARROW -> {
                    cs.moveTo(x1, y1)
                    cs.lineTo(x2, y2)
                    val angle = atan2(y2 - y1, x2 - x1).toDouble()
                    val head = max(12f, s.width * 4f).toDouble()
                    cs.moveTo(x2, y2)
                    cs.lineTo(
                        x2 + (head * cos(angle + 0.75 * Math.PI)).toFloat(),
                        y2 + (head * sin(angle + 0.75 * Math.PI)).toFloat()
                    )
                    cs.moveTo(x2, y2)
                    cs.lineTo(
                        x2 + (head * cos(angle - 0.75 * Math.PI)).toFloat(),
                        y2 + (head * sin(angle - 0.75 * Math.PI)).toFloat()
                    )
                    cs.stroke()
                }
                ShapeKind.RECT -> {
                    val left = min(x1, x2)
                    val right = max(x1, x2)
                    val bottom = min(y1, y2)
                    val top = max(y1, y2)
                    cs.addRect(left, bottom, right - left, top - bottom)
                    cs.stroke()
                }
                ShapeKind.CIRCLE -> {
                    // Ellipse via four cubic Bezier segments (kappa approx).
                    val cx = (x1 + x2) / 2f
                    val cy = (y1 + y2) / 2f
                    val rx = abs(x2 - x1) / 2f
                    val ry = abs(y2 - y1) / 2f
                    val kx = rx * 0.5523f
                    val ky = ry * 0.5523f
                    cs.moveTo(cx + rx, cy)
                    cs.curveTo(cx + rx, cy + ky, cx + kx, cy + ry, cx, cy + ry)
                    cs.curveTo(cx - kx, cy + ry, cx - rx, cy + ky, cx - rx, cy)
                    cs.curveTo(cx - rx, cy - ky, cx - kx, cy - ry, cx, cy - ry)
                    cs.curveTo(cx + kx, cy - ry, cx + rx, cy - ky, cx + rx, cy)
                    cs.closePath()
                    cs.stroke()
                }
                ShapeKind.TRIANGLE -> {
                    val left = min(x1, x2)
                    val right = max(x1, x2)
                    val bottom = min(y1, y2)
                    val top = max(y1, y2)
                    cs.moveTo((x1 + x2) / 2f, top)
                    cs.lineTo(right, bottom)
                    cs.lineTo(left, bottom)
                    cs.closePath()
                    cs.stroke()
                }
                ShapeKind.DOUBLE_ARROW -> {
                    cs.moveTo(x1, y1)
                    cs.lineTo(x2, y2)
                    val angle = atan2(y2 - y1, x2 - x1).toDouble()
                    val head = max(12f, s.width * 4f).toDouble()
                    for (tip in listOf(floatArrayOf(x2, y2, x1, y1), floatArrayOf(x1, y1, x2, y2))) {
                        val tx = tip[0]; val ty = tip[1]
                        val ox = tip[2]; val oy = tip[3]
                        val a2 = atan2(ty - oy, tx - ox).toDouble()
                        cs.moveTo(tx, ty)
                        cs.lineTo(
                            (tx + head * cos(a2 + 0.75 * Math.PI)).toFloat(),
                            (ty + head * sin(a2 + 0.75 * Math.PI)).toFloat()
                        )
                        cs.moveTo(tx, ty)
                        cs.lineTo(
                            (tx + head * cos(a2 - 0.75 * Math.PI)).toFloat(),
                            (ty + head * sin(a2 - 0.75 * Math.PI)).toFloat()
                        )
                    }
                    cs.stroke()
                }
                ShapeKind.ROUNDED_RECT -> {
                    // rect with small chamfered corners (PDF-safe approximation)
                    val left = min(x1, x2)
                    val right = max(x1, x2)
                    val bottom = min(y1, y2)
                    val top = max(y1, y2)
                    val r = min(12f, (right - left) / 4f).coerceAtLeast(1f)
                    cs.moveTo(left + r, bottom)
                    cs.lineTo(right - r, bottom)
                    cs.lineTo(right, bottom + r)
                    cs.lineTo(right, top - r)
                    cs.lineTo(right - r, top)
                    cs.lineTo(left + r, top)
                    cs.lineTo(left, top - r)
                    cs.lineTo(left, bottom + r)
                    cs.closePath()
                    cs.stroke()
                }
                ShapeKind.ELLIPSE -> {
                    val cx = (x1 + x2) / 2f
                    val cy = (y1 + y2) / 2f
                    val rx = abs(x2 - x1) / 2f
                    val ry = abs(y2 - y1) / 2f
                    val kx = rx * 0.5523f
                    val ky = ry * 0.5523f
                    cs.moveTo(cx + rx, cy)
                    cs.curveTo(cx + rx, cy + ky, cx + kx, cy + ry, cx, cy + ry)
                    cs.curveTo(cx - kx, cy + ry, cx - rx, cy + ky, cx - rx, cy)
                    cs.curveTo(cx - rx, cy - ky, cx - kx, cy - ry, cx, cy - ry)
                    cs.curveTo(cx + kx, cy - ry, cx + rx, cy - ky, cx + rx, cy)
                    cs.closePath()
                    cs.stroke()
                }
                ShapeKind.POLYGON -> {
                    val left = min(x1, x2)
                    val top = min(y1, y2)
                    val w = abs(x2 - x1)
                    val h = abs(y2 - y1)
                    if (s.vertices.size >= 6) {
                        var i = 0
                        while (i + 1 < s.vertices.size) {
                            val vx = left + s.vertices[i].coerceIn(0f, 1f) * w
                            val vy = top + s.vertices[i + 1].coerceIn(0f, 1f) * h
                            if (i == 0) cs.moveTo(vx, vy) else cs.lineTo(vx, vy)
                            i += 2
                        }
                        cs.closePath()
                        cs.stroke()
                    } else {
                        cs.addRect(left, top, w, h)
                        cs.stroke()
                    }
                }
            }
        } catch (t: Throwable) {
            AppLog.e(TAG, "shape ${s.id} failed", t)
        }
    }

    // ------------------------------------------------------------------
    // images & text
    // ------------------------------------------------------------------

    private fun drawImage(
        cs: PDPageContentStream,
        img: com.nanote.core.model.ImageElement,
        document: PDDocument,
        imageResolver: ImageResolver,
        pageH: Float
    ) {
        try {
            val file = imageResolver.resolve(img.path)
            if (file == null || !file.exists()) {
                AppLog.w(TAG, "image ${img.path} missing")
                return
            }
            val xobj = PDImageXObject.createFromFile(file.absolutePath, document)
            val w = if (img.w > 0f) img.w else xobj.width.toFloat()
            val h = if (img.h > 0f) img.h else xobj.height.toFloat()
            cs.drawImage(xobj, img.x, pageH - img.y - h, w, h)
        } catch (t: Throwable) {
            AppLog.e(TAG, "image ${img.id} failed", t)
        }
    }

    private fun drawText(cs: PDPageContentStream, t: TextElement, pageH: Float) {
        try {
            val size = if (t.size > 0f) t.size else 18f
            if (t.sticky) {
                cs.setNonStrokingColor(253, 224, 71) // yellow-300 sticky background
                val w = if (t.width > 0f) t.width else 260f
                cs.addRect(t.x, pageH - t.y - size * 1.6f, w, size * 1.6f)
                cs.fill()
            }
            cs.setNonStrokingColor(red(t.color), green(t.color), blue(t.color))
            cs.beginText()
            cs.setFont(PDType1Font.HELVETICA, size)
            // NOTE: Arabic/RTL shaping is not supported by PDFBox's built-in
            // Type1 fonts; the text is drawn as a single left-to-right run of
            // WinAnsi-safe characters. Proper bidi/shaping requires an
            // Arabic-capable embedded font + layout engine (future work).
            cs.newLineAtOffset(t.x, pageH - t.y - size)
            val safe = winAnsiSafe(t.text)
            if (safe.isNotEmpty()) cs.showText(safe)
            cs.endText()
        } catch (e: Throwable) {
            AppLog.e(TAG, "text ${t.id} failed", e)
        }
    }

    /** Replaces characters Helvetica/WinAnsi cannot encode so showText never throws. */
    private fun winAnsiSafe(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when {
                ch == '\n' || ch == '\r' || ch == '\t' -> sb.append(' ')
                ch.code in 32..255 -> sb.append(ch)
                else -> sb.append('?')
            }
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private fun resetNonStrokingAlpha(cs: PDPageContentStream) {
        try {
            val reset = PDExtendedGraphicsState()
            reset.nonStrokingAlphaConstant = 1f
            cs.setGraphicsStateParameters(reset)
        } catch (t: Throwable) {
            AppLog.w(TAG, "reset gs failed (${t.message})")
        }
    }

    private fun resetStrokingAlpha(cs: PDPageContentStream) {
        try {
            val reset = PDExtendedGraphicsState()
            reset.strokingAlphaConstant = 1f
            cs.setGraphicsStateParameters(reset)
        } catch (t: Throwable) {
            AppLog.w(TAG, "reset gs failed (${t.message})")
        }
    }

    private fun red(c: Long): Int = ((c shr 16) and 0xFF).toInt()

    private fun green(c: Long): Int = ((c shr 8) and 0xFF).toInt()

    private fun blue(c: Long): Int = (c and 0xFF).toInt()
}
