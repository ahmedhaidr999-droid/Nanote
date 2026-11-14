package com.nanote.core.graphics

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.nanote.core.model.PageTemplateKind

/**
 * Paints page background templates (§10). Pure drawing, zero allocations per
 * frame — all paints are preallocated, geometry uses scratch arrays.
 *
 * Changing a template NEVER touches handwriting: it only changes what is
 * painted underneath the ink layer.
 */
object PageBackgrounds {

    // Default template ink (soft slate that works on white paper in both themes)
    private const val DEFAULT_INK = 0xFFB6C2D1
    private const val DEFAULT_INK_DARK = 0xFF3D4756
    private const val DEFAULT_ACCENT = 0xFFD8845A

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.8f
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    /** Line height in PDF points for each ruled variant. */
    const val RULED_SPACING = 32f        // college ruled (~7.1 mm)
    const val WIDE_SPACING = 42f         // wide ruled (~9.3 mm)
    const val GRID_SPACING = 24f
    const val SMALL_GRID_SPACING = 12f
    const val DOT_SPACING = 20f

    fun defaultTemplateColor(dark: Boolean): Long =
        if (dark) DEFAULT_INK_DARK else DEFAULT_INK

    fun templateColorOf(color: Long, dark: Boolean): Int {
        if (color != 0L) return color.toInt()
        return defaultTemplateColor(dark).toInt()
    }

    /**
     * Paints [template] into [c] inside the rect (left, top, right, bottom).
     * The canvas must already be translated/scaled to world coordinates and
     * clipped to the page.
     */
    fun paint(
        c: Canvas,
        template: PageTemplateKind,
        left: Float, top: Float, right: Float, bottom: Float,
        colorArgb: Int
    ) {
        if (template == PageTemplateKind.BLANK) return
        linePaint.color = colorArgb
        dotPaint.color = colorArgb
        val w = right - left
        val h = bottom - top
        when (template) {
            PageTemplateKind.RULED -> ruled(c, left, top, right, bottom, RULED_SPACING, 0f)
            PageTemplateKind.COLLEGE_RULED -> {
                // red margin + ruled lines starting below the margin line
                val accent = linePaint.color
                linePaint.color = 0xFFE0806B.toInt()
                c.drawLine(left + 64f, top, left + 64f, bottom, linePaint)
                linePaint.color = accent
                ruled(c, left, top, right, bottom, RULED_SPACING, 56f)
            }
            PageTemplateKind.WIDE_RULED -> ruled(c, left, top, right, bottom, WIDE_SPACING, 0f)
            PageTemplateKind.GRID -> grid(c, left, top, right, bottom, GRID_SPACING)
            PageTemplateKind.SMALL_GRID -> grid(c, left, top, right, bottom, SMALL_GRID_SPACING)
            PageTemplateKind.DOTTED -> dots(c, left, top, right, bottom, DOT_SPACING)
            PageTemplateKind.CORNELL -> cornell(c, left, top, right, bottom)
            PageTemplateKind.MUSIC_STAFF -> musicStaff(c, left, top, right, bottom, h, w)
            PageTemplateKind.PLANNER -> planner(c, left, top, right, bottom, h, w)
            PageTemplateKind.TODO -> todo(c, left, top, right, bottom, h, w)
            PageTemplateKind.CUSTOM -> grid(c, left, top, right, bottom, GRID_SPACING * 2f)
            PageTemplateKind.BLANK -> {}
        }
    }

    /** Infinite-canvas background: sparse dot grid painted only in the visible rect. */
    fun paintInfiniteDots(c: Canvas, viewL: Float, viewT: Float, viewR: Float, viewB: Float, dark: Boolean) {
        dotPaint.color = if (dark) 0xFF2E3947.toInt() else 0xFFCBD5E1.toInt()
        val spacing = 28f
        val startXCeil = kotlin.math.floor(viewL / spacing) * spacing
        val startYCeil = kotlin.math.floor(viewT / spacing) * spacing
        val r = 1.1f
        var x = startXCeil
        while (x <= viewR) {
            var y = startYCeil
            while (y <= viewB) {
                c.drawCircle(x, y, r, dotPaint)
                y += spacing
            }
            x += spacing
        }
    }

    // ------------------------------------------------------------------ kinds

    private fun ruled(c: Canvas, l: Float, t: Float, r: Float, b: Float, spacing: Float, startOffset: Float) {
        var y = t + spacing
        if (startOffset > 0f) y = t + startOffset + spacing * 0.5f
        while (y < b) {
            c.drawLine(l, y, r, y, linePaint)
            y += spacing
        }
    }

    private fun grid(c: Canvas, l: Float, t: Float, r: Float, b: Float, spacing: Float) {
        var x = l + spacing
        while (x < r) {
            c.drawLine(x, t, x, b, linePaint)
            x += spacing
        }
        var y = t + spacing
        while (y < b) {
            c.drawLine(l, y, r, y, linePaint)
            y += spacing
        }
    }

    private fun dots(c: Canvas, l: Float, t: Float, r: Float, b: Float, spacing: Float) {
        val radius = 1.2f
        var x = l + spacing * 0.5f
        while (x < r) {
            var y = t + spacing * 0.5f
            while (y < b) {
                c.drawCircle(x, y, radius, dotPaint)
                y += spacing
            }
            x += spacing
        }
    }

    /** Cornell: margin line at 30% + summary header + ruled body. */
    private fun cornell(c: Canvas, l: Float, t: Float, r: Float, b: Float) {
        val marginX = l + (r - l) * 0.30f
        val headerY = t + (b - t) * 0.12f
        val accent = linePaint.color
        linePaint.color = (accent and 0x00FFFFFF) or 0xFFB48A78.toInt()
        c.drawLine(marginX, t + 24f, marginX, b - 24f, linePaint)
        linePaint.color = accent
        c.drawLine(l + 24f, headerY, r - 24f, headerY, linePaint)
        ruled(c, l, headerY, r, b, RULED_SPACING, 0f)
    }

    /** Music staff: groups of 5 lines. */
    private fun musicStaff(c: Canvas, l: Float, t: Float, r: Float, b: Float, h: Float, w: Float) {
        val groupGap = RULED_SPACING * 1.4f
        val lineGap = 9f
        var groupTop = t + groupGap * 0.6f
        while (groupTop + lineGap * 4f < b - 12f) {
            for (i in 0 until 5) {
                val y = groupTop + i * lineGap
                c.drawLine(l + 18f, y, r - 18f, y, linePaint)
            }
            groupTop += lineGap * 4f + groupGap
        }
    }

    /** Planner: weekday columns + hour rows. */
    private fun planner(c: Canvas, l: Float, t: Float, r: Float, b: Float, h: Float, w: Float) {
        val headerH = 34f
        val accent = linePaint.color
        linePaint.color = (accent and 0x00FFFFFF) or 0xFF9DB3C8.toInt()
        c.drawLine(l, t + headerH, r, t + headerH, linePaint)
        val cols = 7
        for (i in 1 until cols) {
            val x = l + w * i / cols
            c.drawLine(x, t, x, b, linePaint)
        }
        linePaint.color = accent
        val rowH = 42f
        var y = t + headerH + rowH
        while (y < b) {
            c.drawLine(l, y, r, y, linePaint)
            y += rowH
        }
    }

    /** To-do: checkbox column + ruled lines. */
    private fun todo(c: Canvas, l: Float, t: Float, r: Float, b: Float, h: Float, w: Float) {
        val accent = linePaint.color
        linePaint.color = (accent and 0x00FFFFFF) or 0xFF8FA3B8.toInt()
        val boxX = l + 46f
        c.drawLine(boxX, t + 16f, boxX, b - 12f, linePaint)
        linePaint.color = accent
        var y = t + 34f
        while (y < b) {
            // checkbox
            c.drawRoundRect(boxX - 22f, y - 8f, boxX - 6f, y + 8f, 3f, 3f, linePaint)
            // writing line
            c.drawLine(boxX + 12f, y, r - 20f, y, linePaint)
            y += 40f
        }
    }
}
