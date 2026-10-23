package com.nanote.export

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import com.nanote.domain.model.Page
import com.nanote.ink.engine.PenType
import com.nanote.ink.engine.Stroke
import com.nanote.ink.render.ObjectPainter
import com.nanote.ink.render.PageSnapshot
import com.nanote.ink.render.StrokePainter
import com.nanote.ink.engine.paperById
import java.io.OutputStream
import android.graphics.Path

/**
 * Export page content to PDF (vector ink) or PNG/JPEG (spec 7).
 * PDF backgrounds render at 2x and are scaled down; ink uses the same
 * renderer as the screen so exported ink matches what the user sees.
 */
class PageExporter(
    /** Full-page bitmap for PDF-backed pages: (pageIndex, widthPx) -> Bitmap. */
    private val pdfBitmap: ((pageIndex: Int, widthPx: Int) -> Bitmap?)? = null,
    /** Image asset loader for image objects. */
    private val imageLoader: (assetId: String) -> Bitmap? = { null }
) {

    private val painter = StrokePainter()
    private val path = Path()
    private val objectPainter = ObjectPainter(imageLoader)

    /** Renders one page into [c], assuming [c] is already scaled to page points. */
    fun drawContent(c: Canvas, page: Page, snapshot: PageSnapshot, forPdf: Boolean) {
        // Background: PDF bitmap or paper template
        if (page.pdfPageIndex != null && pdfBitmap != null) {
            val widthPx = (page.widthPt * 2f).toInt().coerceAtLeast(64) // 2x, scaled down
            val bmp = pdfBitmap(page.pdfPageIndex!!, widthPx)
            if (bmp != null) {
                val scale = page.widthPt / bmp.width
                c.drawBitmap(
                    bmp, null,
                    android.graphics.RectF(0f, 0f, page.widthPt, bmp.height * scale),
                    null
                )
            }
        } else {
            com.nanote.ink.render.PaperRenderer.drawPaper(c, page.widthPt, page.heightPt, paperById(page.paper), page.bgArgb)
        }

        // Highlighter (multiply) then ink, then objects (same order as tiles).
        for (s in snapshot.strokes) {
            if (s.pen != PenType.HIGHLIGHTER) continue
            drawStroke(c, s, forPdf)
        }
        for (s in snapshot.strokes) {
            if (s.pen == PenType.HIGHLIGHTER) continue
            drawStroke(c, s, forPdf)
        }
        objectPainter.drawObjects(c, snapshot.objects)
    }

    private fun drawStroke(c: Canvas, s: Stroke, forPdf: Boolean) {
        painter.configure(s.pen, s.argb)
        if (forPdf && painter.isMultiply(s.pen)) {
            // Some PDF canvases dislike MULTIPLY; fall back to alpha compositing
            // by temporarily dropping the blend mode (spec 7 fallback).
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                painter.paint.blendMode = android.graphics.BlendMode.SRC_OVER
            } else {
                @Suppress("DEPRECATION")
                painter.paint.xfermode = null
            }
        }
        path.rewind()
        painter.appendStrokePath(s, path)
        c.drawPath(path, painter.paint)
        painter.drawCaps(c, s)
    }

    /** Writes a whole-notebook PDF (vector ink). */
    fun exportPdf(pages: List<Page>, snapshots: Map<String, PageSnapshot>, out: OutputStream) {
        val doc = PdfDocument()
        try {
            pages.sortedBy { it.position }.forEachIndexed { i, p ->
                val pdfPage = doc.startPage(
                    PdfDocument.PageInfo.Builder(
                        p.widthPt.toInt().coerceAtLeast(1),
                        p.heightPt.toInt().coerceAtLeast(1),
                        i + 1
                    ).create()
                )
                val c = pdfPage.canvas
                drawContent(c, p, snapshots[p.id] ?: PageSnapshot(emptyList(), emptyList(), 0), forPdf = true)
                doc.finishPage(pdfPage)
            }
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    /** Rasterizes one page at the given dpi (72 = 1x points). */
    fun exportImage(page: Page, snapshot: PageSnapshot, dpi: Int, asJpeg: Boolean, out: OutputStream) {
        val scale = dpi / 72f
        val w = (page.widthPt * scale).toInt().coerceAtLeast(1)
        val h = (page.heightPt * scale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(scale, scale)
        drawContent(c, page, snapshot, forPdf = false)
        val fmt = if (asJpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
        bmp.compress(fmt, 92, out)
        bmp.recycle()
    }
}
