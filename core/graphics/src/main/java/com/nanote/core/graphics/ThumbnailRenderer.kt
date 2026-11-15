package com.nanote.core.graphics

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.LruCache
import com.nanote.core.model.DocumentModel
import com.nanote.core.model.PageDef
import com.nanote.core.model.PageTemplateKind
import com.nanote.core.model.ToolType

/**
 * Renders small page thumbnails for the editor page strip (§10).
 *
 * Deliberately simple & thread-safe: plain polylines for ink, boxes for
 * shapes/texts — visual fidelity beyond recognition is not needed at 96 px.
 * Bitmaps are cached by (pageIndex, revision) in a small LRU.
 */
class ThumbnailRenderer {

    companion object {
        const val THUMB_W = 96
        const val THUMB_H = 128
    }

    private val cache = LruCache<String, Bitmap>(32)
    private val paperPaint = Paint().apply { color = Color.WHITE }
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.parseColor("#94A3B8")
    }
    private val path = Path()

    /** Returns a cached thumbnail, rendering it on first request. Thread-safe. */
    @Synchronized
    fun thumbnail(doc: DocumentModel, page: Int, revision: Long): Bitmap? {
        val def: PageDef = doc.pages.getOrNull(page) ?: return null
        val key = "$page@${def.width.toInt()}x${def.height.toInt()}@$revision"
        cache.get(key)?.let { return it }
        val bmp = createBitmap() ?: return null
        val scale = minOf(THUMB_W / def.width, THUMB_H / def.height)
        val w = (def.width * scale).toInt().coerceAtLeast(1)
        val h = (def.height * scale).toInt().coerceAtLeast(1)
        val c = Canvas(bmp)
        c.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        // paper
        c.save()
        c.translate((THUMB_W - w) / 2f, (THUMB_H - h) / 2f)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paperPaint)
        c.clipRect(0f, 0f, w.toFloat(), h.toFloat())
        c.scale(scale, scale)
        // template hint
        if (def.pdfPage < 0) {
            PageBackgrounds.paint(c, def.template, 0f, 0f, def.width, def.height, Color.parseColor("#D3DCE7"))
        }
        // content (page-local coordinates)
        for (s in doc.strokes.filter { it.page == page }) {
            path.rewind()
            if (s.points.isNotEmpty()) {
                path.moveTo(s.points[0].x, s.points[0].y)
                for (i in 1 until s.points.size) path.lineTo(s.points[i].x, s.points[i].y)
            }
            inkPaint.color = colorOf(s.color, s.opacity)
            inkPaint.strokeWidth = (s.width * (if (s.tool == ToolType.HIGHLIGHTER) 0.6f else 0.35f)).coerceAtLeast(1f)
            c.drawPath(path, inkPaint)
        }
        for (sh in doc.shapes.filter { it.page == page }) {
            blockPaint.color = colorOf(sh.color, 100)
            c.drawRect(
                minOf(sh.x1, sh.x2), minOf(sh.y1, sh.y2),
                maxOf(sh.x1, sh.x2), maxOf(sh.y1, sh.y2), blockPaint
            )
        }
        for (t in doc.texts.filter { it.page == page }) {
            blockPaint.color = colorOf(t.color, 100)
            c.drawRect(t.x, t.y, t.x + t.width, t.y + t.size * 1.4f, blockPaint)
        }
        for (i in doc.images.filter { it.page == page }) {
            blockPaint.color = Color.parseColor("#64748B")
            c.drawRect(i.x, i.y, i.x + i.w, i.y + i.h, blockPaint)
        }
        c.restore()
        cache.put(key, bmp)
        return bmp
    }

    private fun createBitmap(): Bitmap? = try {
        Bitmap.createBitmap(THUMB_W, THUMB_H, Bitmap.Config.ARGB_8888)
    } catch (t: Throwable) {
        null
    }

    private fun colorOf(argb: Long, opacityPct: Int): Int {
        val a = (opacityPct.coerceIn(0, 100) * 255) / 100
        val r = ((argb shr 16) and 0xFF).toInt()
        val g = ((argb shr 8) and 0xFF).toInt()
        val b = (argb and 0xFF).toInt()
        return Color.argb(a, r, g, b)
    }
}
