package com.nanote.core.pdf

import android.graphics.PointF
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition

/**
 * Collects words + bounding boxes from one PDF page using pdfbox-android.
 * Output coordinates are TOP-LEFT origin PDF points — identical to the space
 * pdfium renders pages into, so selection boxes map 1:1 onto the canvas.
 */
class WordCollector(
    private val pageIndex: Int,
    private val pageWidth: Float,
    private val pageHeight: Float
) : PDFTextStripper() {

    private val words = ArrayList<PdfWord>(256)
    private val lineIdx = intArrayOf(0)

    // current word accumulation
    private val sb = StringBuilder()
    private var wx0 = 0f; private var wy0 = 0f
    private var wx1 = 0f; private var wy1 = 0f
    private var wLine = -1

    init {
        sortByPosition = true
    }

    fun extract(doc: PDDocument, pageIdx: Int) {
        startPage = pageIdx + 1
        endPage = pageIdx + 1
        getText(doc)
        flushWord()
    }

    override fun writeLineSeparator() {
        flushWord()
        lineIdx[0]++
    }

    override fun writeWordSeparator() {
        flushWord()
    }

    override fun writeString(text: String?, positions: MutableList<TextPosition>?) {
        if (positions == null) return
        for (pos in positions) {
            val ch = pos.getUnicode() ?: continue
            if (ch.isBlank()) {
                flushWord()
                continue
            }
            val x0 = pos.getXDirAdj()
            val baseY = pos.getYDirAdj()
            val h = pos.getHeightDir().coerceAtLeast(1f)
            val adv = pos.getWidthDirAdj().coerceAtLeast(0.5f)
            val top = baseY - h * 0.78f
            val bottom = baseY + h * 0.22f
            val x1 = x0 + adv

            if (sb.isNotEmpty()) {
                // unexpected line jump inside one run → new line
                if (kotlin.math.abs(bottom - wy1) > h * 0.7f) {
                    flushWord()
                    lineIdx[0]++
                } else if (x0 - wx1 > h * 1.2f) {
                    // suspicious horizontal gap → treat as word break
                    flushWord()
                }
            }

            if (sb.isEmpty()) {
                wx0 = x0; wy0 = top; wx1 = x1; wy1 = bottom
                wLine = lineIdx[0]
            } else {
                wx1 = maxOf(wx1, x1)
                wy0 = minOf(wy0, top)
                wy1 = maxOf(wy1, bottom)
            }
            sb.append(ch)
        }
    }

    private fun flushWord() {
        if (sb.isNotEmpty()) {
            val t = sb.toString()
            if (t.isNotBlank() && wx1 > wx0) {
                words.add(PdfWord(t, wx0, wy0, wx1, wy1, wLine))
            }
            sb.setLength(0)
        }
    }

    fun build(): PageText {
        val byLine = words.sortedWith(compareBy({ it.lineIndex }, { it.x0 }))
            .groupBy { it.lineIndex }
            .map { (_, ws) -> ws.sortedBy { it.x0 } }
        return PageText(pageIndex, pageWidth, pageHeight, byLine)
    }

    @Suppress("unused")
    private fun dummy(p: PointF) {}
}
