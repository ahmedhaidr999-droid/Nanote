package com.nanote.ink.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import com.nanote.ink.engine.PenType
import java.io.File

/**
 * Renders a page snapshot to a small (300 px wide) WebP thumbnail, stored at
 * filesDir/thumbs/<pageId>.webp. Regenerated after each save (spec 5).
 */
class ThumbRenderer(private val thumbsDir: File) {

    private val painter = StrokePainter()
    private val path = Path()

    fun render(
        pageId: String,
        pageW: Float,
        pageH: Float,
        paperId: String,
        bgArgb: Int,
        snapshot: PageSnapshot
    ): File? {
        if (pageW <= 0 || pageH <= 0) return null
        val width = 300
        val height = (width * pageH / pageW).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val scale = width / pageW
        canvas.scale(scale, scale)

        PaperRenderer.drawPaper(canvas, pageW, pageH, com.nanote.ink.engine.paperById(paperId), bgArgb)

        for (s in snapshot.strokes) {
            if (s.pen == PenType.HIGHLIGHTER) continue // keep thumbnails light
            painter.configure(s.pen, s.argb)
            path.rewind()
            painter.appendStrokePath(s, path)
            canvas.drawPath(path, painter.paint)
        }

        val out = File(thumbsDir, "$pageId.webp")
        out.parentFile?.mkdirs()
        out.outputStream().use { fos ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, fos)
            } else {
                @Suppress("DEPRECATION")
                bitmap.compress(Bitmap.CompressFormat.WEBP, 80, fos)
            }
        }
        bitmap.recycle()
        return out
    }

    fun thumbnailFor(pageId: String): File = File(thumbsDir, "$pageId.webp")

    fun delete(pageId: String) {
        thumbnailFor(pageId).delete()
    }
}
