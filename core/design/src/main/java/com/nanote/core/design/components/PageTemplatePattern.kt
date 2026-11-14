package com.nanote.core.design.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import com.nanote.core.model.PageTemplate

/**
 * Draws a notebook page template (plain / lined / grid / dotted / cornell)
 * inside the given bounds. Used for notebook covers, template pickers and
 * the editor's page placeholders. Pure Canvas — no bitmaps.
 */
@Composable
fun PageTemplatePattern(
    template: PageTemplate,
    modifier: Modifier = Modifier,
    lineColor: Color = Color(0x1A000000),
    lineWeight: Float = 1f
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        when (template) {
            PageTemplate.PLAIN -> Unit

            PageTemplate.LINED -> {
                val rows = 9
                val step = h / (rows + 1)
                var y = step
                repeat(rows) {
                    drawLine(lineColor, Offset(0f, y), Offset(w, y), lineWeight)
                    y += step
                }
            }

            PageTemplate.GRID -> {
                val cells = 6
                val stepX = w / cells
                val stepY = h / (cells + 3)
                var x = stepX
                repeat(cells - 1) {
                    drawLine(lineColor, Offset(x, 0f), Offset(x, h), lineWeight)
                    x += stepX
                }
                var y = stepY
                while (y < h) {
                    drawLine(lineColor, Offset(0f, y), Offset(w, y), lineWeight)
                    y += stepY
                }
            }

            PageTemplate.DOTTED -> {
                val stepX = w / 7f
                val stepY = h / 10f
                var y = stepY
                while (y < h - 2f) {
                    var x = stepX
                    while (x < w - 2f) {
                        drawCircle(lineColor, radius = lineWeight * 1.1f, center = Offset(x, y))
                        x += stepX
                    }
                    y += stepY
                }
            }

            PageTemplate.CORNELL -> {
                // Note-taking area with a left cue column and bottom summary.
                val cueX = w * 0.3f
                val summaryY = h * 0.78f
                drawLine(lineColor, Offset(cueX, 0f), Offset(cueX, h), lineWeight)
                drawLine(lineColor, Offset(0f, summaryY), Offset(w, summaryY), lineWeight)
                // Lines in the main area.
                val rows = 5
                val step = (summaryY - 0f) / (rows + 1)
                var y = step
                repeat(rows) {
                    drawLine(lineColor, Offset(cueX + step * 0.4f, y), Offset(w, y), lineWeight)
                    y += step
                }
            }
        }
    }
}

/** Preview stroke used on covers to suggest handwriting. */
@Composable
fun CoverScribble(modifier: Modifier = Modifier, color: Color = Color(0x33FFFFFF)) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.18f, h * 0.30f)
            cubicTo(w * 0.30f, h * 0.10f, w * 0.38f, h * 0.50f, w * 0.50f, h * 0.28f)
            cubicTo(w * 0.60f, h * 0.10f, w * 0.66f, h * 0.48f, w * 0.80f, h * 0.26f)
        }
        drawPath(path, color, style = Stroke(width = h * 0.045f))
    }
}
