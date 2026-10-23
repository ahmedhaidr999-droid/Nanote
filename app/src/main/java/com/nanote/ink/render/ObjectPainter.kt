package com.nanote.ink.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.nanote.domain.model.ImageObj
import com.nanote.domain.model.LinkObj
import com.nanote.domain.model.PageObject
import com.nanote.domain.model.TextBox
import kotlin.math.hypot

/**
 * Renders non-ink page objects (text boxes, images, links) in page coordinates.
 * Image bitmaps come from [imageLoader] (owner caches them); text uses
 * StaticLayout so multiline + RTL render correctly.
 */
class ObjectPainter(private val imageLoader: (assetId: String) -> Bitmap?) {

    private val textPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private val linkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF1976D2.toInt()
    }
    private val linkIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
        color = 0xFFFFFFFF.toInt()
    }
    private val linkHit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x331976D2
    }

    fun drawObjects(c: Canvas, objects: List<PageObject>) {
        objects.sortedBy { it.z }.forEach { drawObject(c, it) }
    }

    fun drawObject(c: Canvas, obj: PageObject) {
        when (obj) {
            is TextBox -> drawTextBox(c, obj)
            is ImageObj -> drawImage(c, obj)
            is LinkObj -> drawLink(c, obj)
        }
    }

    private fun matrixOf(m: List<Float>): Matrix {
        val arr = FloatArray(9)
        for (i in 0 until minOf(8, m.size)) arr[i] = m[i]
        arr[6] = 0f; arr[7] = 0f; arr[8] = 1f
        matrix.setValues(arr)
        return matrix
    }

    private fun drawTextBox(c: Canvas, obj: TextBox) {
        val save = c.save()
        c.concat(matrixOf(obj.m))
        textPaint.color = obj.colorArgb
        textPaint.textSize = obj.fontPt
        if (obj.text.isNotEmpty()) {
            val layout = StaticLayout.Builder
                .obtain(obj.text, 0, obj.text.length, textPaint, measureWidth(obj).toInt().coerceAtLeast(1))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.15f)
                .build()
            layout.draw(c)
        }
        c.restoreToCount(save)
    }

    private fun measureWidth(obj: TextBox): Float {
        textPaint.textSize = obj.fontPt
        var max = 0f
        obj.text.split('\n').forEach { line ->
            max = maxOf(max, textPaint.measureText(line))
        }
        return (max + 8f).coerceAtLeast(12f)
    }

    fun textBounds(obj: TextBox): android.graphics.RectF {
        textPaint.textSize = obj.fontPt
        val lines = obj.text.split('\n')
        var maxW = 0f
        lines.forEach { maxW = maxOf(maxW, textPaint.measureText(it)) }
        val h = lines.size * obj.fontPt * 1.15f + 4f
        return android.graphics.RectF(0f, 0f, maxW + 8f, h)
    }

    private fun drawImage(c: Canvas, obj: ImageObj) {
        val bmp = imageLoader(obj.assetId) ?: return
        val save = c.save()
        c.concat(matrixOf(obj.m))
        c.drawBitmap(bmp, null, android.graphics.RectF(0f, 0f, obj.widthPt, obj.heightPt), null)
        c.restoreToCount(save)
    }

    private fun drawLink(c: Canvas, obj: LinkObj) {
        // badge dot + subtle halo at the anchor
        c.drawCircle(obj.anchorX, obj.anchorY, 10f, linkHit)
        c.drawCircle(obj.anchorX, obj.anchorY, 6f, linkPaint)
        c.drawCircle(obj.anchorX, obj.anchorY, 6f, linkPaint)
        // simple chain glyph
        val p = linkIconPaint
        c.drawLine(obj.anchorX - 3f, obj.anchorY + 1f, obj.anchorX + 3f, obj.anchorY - 3f, p)
        c.drawCircle(obj.anchorX - 3.5f, obj.anchorY + 1.5f, 1.8f, p)
        c.drawCircle(obj.anchorX + 3.5f, obj.anchorY - 1.5f, 1.8f, p)
    }

    /**
     * Returns the object whose transformed footprint contains (x, y), or null.
     * Text bounds are estimated from the paint metrics; images use their
     * widthPt/heightPt transformed by the object matrix.
     */
    fun hitTest(objects: List<PageObject>, x: Float, y: Float, pad: Float = 4f): PageObject? {
        for (obj in objects.sortedByDescending { it.z }) {
            when (obj) {
                is TextBox -> {
                    val r = textBounds(obj)
                    val m = matrixOf(obj.m)
                    val corners = floatArrayOf(
                        r.left, r.top, r.right, r.top, r.right, r.bottom, r.left, r.bottom
                    )
                    m.mapPoints(corners)
                    if (pointInQuad(x, y, corners, pad)) return obj
                }
                is ImageObj -> {
                    val m = matrixOf(obj.m)
                    val corners = floatArrayOf(
                        0f, 0f, obj.widthPt, 0f, obj.widthPt, obj.heightPt, 0f, obj.heightPt
                    )
                    m.mapPoints(corners)
                    if (pointInQuad(x, y, corners, pad)) return obj
                }
                is LinkObj -> {
                    if (hypot(x - obj.anchorX, y - obj.anchorY) <= 12f) return obj
                }
            }
        }
        return null
    }

    private fun pointInQuad(px: Float, py: Float, quad: FloatArray, pad: Float): Boolean {
        // padded quad polygon, even-odd rule (pure engine helper)
        val poly = floatArrayOf(
            quad[0] - pad, quad[1] - pad,
            quad[2] + pad, quad[3] - pad,
            quad[4] + pad, quad[5] + pad,
            quad[6] - pad, quad[7] + pad
        )
        return com.nanote.ink.engine.pointInPolygon(px, py, poly)
    }
}
