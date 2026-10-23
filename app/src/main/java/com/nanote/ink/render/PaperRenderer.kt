package com.nanote.ink.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.nanote.ink.engine.Paper

/**
 * Draws the paper template of a page in PAGE coordinates (the canvas is
 * expected to be pre-scaled so 1 unit = 1 pt). Templates per spec 6.10:
 * blank, lined (narrow/wide), 5mm grid, 5mm dots, Cornell (cue column +
 * summary band).
 */
object PaperRenderer {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    fun drawPaper(c: Canvas, w: Float, h: Float, paper: Paper, bgArgb: Int) {
        // Background
        if (bgArgb != 0) {
            c.drawColor(bgArgb)
        } else {
            c.drawColor(Color.WHITE)
        }
        if (paper.id == "blank" || paper.spacing <= 0f) return

        linePaint.color = paper.argb
        linePaint.strokeWidth = paper.lineW
        dotPaint.color = paper.argb
        dotPaint.strokeWidth = paper.lineW

        when (paper.id) {
            "grid_5mm" -> {
                var x = paper.spacing
                while (x < w) {
                    c.drawLine(x, 0f, x, h, linePaint)
                    x += paper.spacing
                }
                var y = paper.spacing
                while (y < h) {
                    c.drawLine(0f, y, w, y, linePaint)
                    y += paper.spacing
                }
            }
            "dots_5mm" -> drawDots(c, w, h, paper.spacing, dotPaint)
            "cornell" -> {
                drawRuled(c, w, h, paper)
                // Cue column on the writing side (RTL-friendly: draw both directions
                // are handled by page geometry; the column sits on the left edge).
                val cueX = 170f
                c.drawLine(cueX, paper.margin, cueX, h - paper.margin, linePaint)
                // Summary band at the bottom
                val bandTop = h - 100f
                c.drawLine(0f, bandTop, w, bandTop, linePaint)
            }
            else -> drawRuled(c, w, h, paper) // lined_narrow / lined_wide
        }
    }

    private fun drawRuled(c: Canvas, w: Float, h: Float, paper: Paper) {
        var y = paper.margin
        while (y < h - paper.margin * 0.5f) {
            c.drawLine(paper.margin * 0.35f, y, w - paper.margin * 0.35f, y, linePaint)
            y += paper.spacing
        }
        // margin line
        if (paper.margin > 0f) {
            linePaint.strokeWidth = paper.lineW * 1.6f
            c.drawLine(paper.margin * 0.35f, 0f, paper.margin * 0.35f, h, linePaint)
            linePaint.strokeWidth = paper.lineW
        }
    }

    /** One drawPoints call for thousands of dots (spec 6.10). */
    fun drawDots(c: Canvas, w: Float, h: Float, step: Float, paint: Paint) {
        val cols = (w / step).toInt()
        val rows = (h / step).toInt()
        if (cols <= 0 || rows <= 0) return
        val pts = FloatArray(cols * rows * 2)
        var k = 0
        for (r in 1..rows) {
            for (q in 1..cols) {
                pts[k++] = q * step
                pts[k++] = r * step
            }
        }
        c.drawPoints(pts, 0, k, paint)
    }
}
