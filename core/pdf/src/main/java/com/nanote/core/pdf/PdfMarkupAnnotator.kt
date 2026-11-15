package com.nanote.core.pdf

import com.nanote.core.common.AppLog
import com.nanote.core.model.DocumentContent
import com.nanote.core.model.HighlightAnnotation
import com.nanote.core.model.StrikeThroughAnnotation
import com.nanote.core.model.UnderlineAnnotation
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup

/**
 * Writes Nanote text marks (highlight / underline / strikethrough) as REAL
 * PDF markup annotations (PDF 32000-1 §12.5.6.10) so exported documents keep
 * their original selectable text and the marks stay editable in other PDF
 * apps where supported.
 *
 * Quad points are emitted counter-clockwise as required by the spec.
 * Opacity is approximated by pre-blending the color toward white (PDFBox
 * 2.0.x annotations have no direct opacity constant).
 */
object PdfMarkupAnnotator {

    private const val TAG = "PdfMarkupAnnotator"

    /**
     * Appends markup annotations for every text mark of [content] on
     * [pageIndex] to [page] of [document].
     */
    fun annotatePage(
        document: PDDocument,
        page: PDPage,
        content: DocumentContent,
        pageIndex: Int
    ) {
        val pageW = page.mediaBox.width
        val pageH = page.mediaBox.height

        for (h in content.highlights) {
            if (h.page == pageIndex) addMarkups(document, page, h.rects, h.color, h.opacity, PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT)
        }
        for (u in content.underlines) {
            if (u.page == pageIndex) addMarkups(document, page, u.rects, u.color, u.opacity, PDAnnotationTextMarkup.SUB_TYPE_UNDERLINE)
        }
        for (s in content.strikeThroughs) {
            if (s.page == pageIndex) addMarkups(document, page, s.rects, s.color, s.opacity, PDAnnotationTextMarkup.SUB_TYPE_STRIKEOUT)
        }
    }

    private fun addMarkups(
        document: PDDocument,
        page: PDPage,
        rects: List<List<Float>>,
        color: Long,
        opacity: Int,
        subType: String
    ) {
        val pageW = page.mediaBox.width
        val pageH = page.mediaBox.height
        for (rect in rects) {
            if (rect.size < 4) continue
            try {
                val x0 = rect[0] * pageW
                val x1 = rect[2] * pageW
                val yTopPdf = pageH - rect[1] * pageH
                val yBotPdf = pageH - rect[3] * pageH
                if (x1 <= x0 || yBotPdf <= yTopPdf) continue

                val annot = PDAnnotationTextMarkup(subType)
                annot.rectangle = PDRectangle(x0, yTopPdf, x1 - x0, yBotPdf - yTopPdf)
                // Counter-clockwise quad: bottom-left, bottom-right, top-right, top-left.
                annot.quadPoints = floatArrayOf(
                    x0, yBotPdf,
                    x1, yBotPdf,
                    x1, yTopPdf,
                    x0, yTopPdf
                )
                annot.color = PDColor(
                    blendTowardWhite(
                        red(color), green(color), blue(color),
                        paintAlphaFraction(opacity)
                    ),
                    PDDeviceRGB.INSTANCE
                )
                // /F = 4 (Print flag): markup annotations print with the
                // page. Set through the COS dictionary — the typed flag
                // helpers differ across pdfbox-android versions.
                annot.cosObject().setInt(COSName.F, 4)
                page.annotations.add(annot)
            } catch (t: Throwable) {
                AppLog.w(TAG, "markup failed: ${t.message}")
            }
        }
    }

    private fun paintAlphaFraction(opacityPct: Int): Float =
        (opacityPct.coerceIn(0, 100) / 100f)

    /** Mixes the color with white by [fraction] (0 = white, 1 = full color). */
    private fun blendTowardWhite(r: Int, g: Int, b: Int, fraction: Float): FloatArray {
        val f = fraction.coerceIn(0f, 1f)
        return floatArrayOf(
            (r / 255f) * f + (1f - f),
            (g / 255f) * f + (1f - f),
            (b / 255f) * f + (1f - f)
        )
    }

    private fun red(c: Long): Int = ((c shr 16) and 0xFF).toInt()

    private fun green(c: Long): Int = ((c shr 8) and 0xFF).toInt()

    private fun blue(c: Long): Int = (c and 0xFF).toInt()
}
