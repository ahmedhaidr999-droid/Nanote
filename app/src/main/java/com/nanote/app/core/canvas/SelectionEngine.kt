package com.nanote.app.core.canvas

import com.nanote.app.core.model.DocumentModel
import com.nanote.app.core.model.HighlightAnnotation
import com.nanote.app.core.model.Stroke
import com.nanote.app.core.model.TextElement
import com.nanote.app.core.model.ImageElement
import com.nanote.app.core.model.ShapeElement
import com.nanote.app.core.model.UnderlineAnnotation
import com.nanote.app.core.pdf.PageText
import com.nanote.app.core.pdf.PdfWord
import com.nanote.app.util.AppLog

/**
 * Text-selection state machine (one per pane).
 * Coordinates: PDF page space (top-left origin points) → converted to world by
 * the caller via CanvasState layout. Selection is zoom/pan independent because
 * everything is stored in page space.
 */
class TextSelectionEngine {

    companion object {
        private const val TAG = "TextSelection"
    }

    var pageIndex: Int = -1
        private set
    var pageText: PageText? = null
        private set

    /** Ordered word indices, from startIdx to endIdx inclusive. */
    private var startIdx: Int = -1
    private var endIdx: Int = -1

    val isActive: Boolean get() = pageText != null && startIdx >= 0

    fun begin(page: Int, text: PageText, word: PdfWord) {
        pageIndex = page
        pageText = text
        startIdx = text.indexOf(word)
        endIdx = startIdx
        AppLog.d(TAG, "selection begin page $page word '${word.text}' idx=$startIdx")
    }

    /** Extend selection towards the word under a drag position. */
    fun extendTo(word: PdfWord) {
        val t = pageText ?: return
        val i = t.indexOf(word)
        if (i < 0) return
        endIdx = i
    }

    fun selectAll() {
        val t = pageText ?: return
        if (t.allWords.isEmpty()) return
        startIdx = 0
        endIdx = t.allWords.size - 1
    }

    fun clear() {
        pageIndex = -1
        pageText = null
        startIdx = -1
        endIdx = -1
    }

    fun orderedRange(): IntRange {
        val a = minOf(startIdx, endIdx)
        val b = maxOf(startIdx, endIdx)
        return a..b
    }

    fun selectedText(): String {
        val t = pageText ?: return ""
        val (a, b) = { val r = orderedRange(); r.first to r.last }.invoke()
        if (a < 0) return ""
        val sb = StringBuilder()
        var prevLine = -1
        for (i in a..b) {
            val w = t.allWords[i]
            if (prevLine != -1) sb.append(if (w.lineIndex != prevLine) '\n' else ' ')
            sb.append(w.text)
            prevLine = w.lineIndex
        }
        return sb.toString()
    }

    fun selectedWordCount(): Int {
        if (!isActive) return 0
        val r = orderedRange()
        return r.last - r.first + 1
    }

    /** Line rects in page space for the current selection. */
    fun selectionRects(): List<FloatArray> {
        val t = pageText ?: return emptyList()
        if (startIdx < 0) return emptyList()
        val a = minOf(startIdx, endIdx).coerceAtLeast(0)
        val b = maxOf(startIdx, endIdx).coerceAtMost(t.allWords.size - 1)
        if (a > b) return emptyList()
        val byLine = LinkedHashMap<Int, MutableList<PdfWord>>()
        for (i in a..b) {
            val w = t.allWords[i]
            byLine.getOrPut(w.lineIndex) { ArrayList() }.add(w)
        }
        return byLine.values.map { ws ->
            floatArrayOf(ws.minOf { it.x0 }, ws.minOf { it.y0 }, ws.maxOf { it.x1 }, ws.maxOf { it.y1 })
        }
    }

    /** Handle positions in page space: bottom-start of first word / bottom-end of last. */
    fun handlePositions(): Pair<FloatArray, FloatArray>? {
        val t = pageText ?: return null
        if (startIdx < 0 || t.allWords.isEmpty()) return null
        val a = minOf(startIdx, endIdx).coerceAtLeast(0)
        val b = maxOf(startIdx, endIdx).coerceAtMost(t.allWords.size - 1)
        if (a > b) return null
        val first = t.allWords[a]
        val last = t.allWords[b]
        val leftFirst = if (startIdx <= endIdx) first.x0 else first.x1
        val rightLast = if (startIdx <= endIdx) last.x1 else last.x0
        return floatArrayOf(leftFirst, first.y1) to floatArrayOf(rightLast, last.y1)
    }

    /** Word whose box contains page-space (x,y) — for handle drags. */
    fun wordAt(x: Float, y: Float): PdfWord? = pageText?.wordNear(x, y)

    fun isReversed(): Boolean = endIdx < startIdx
}

// ---------------------------------------------------------------------------
// Object selection (strokes, texts, images, shapes, annotations)
// ---------------------------------------------------------------------------

sealed class SelectedElement {
    data class SelStroke(val stroke: Stroke) : SelectedElement()
    data class SelText(val text: TextElement) : SelectedElement()
    data class SelImage(val image: ImageElement) : SelectedElement()
    data class SelShape(val shape: ShapeElement) : SelectedElement()
    data class SelHighlight(val ann: HighlightAnnotation) : SelectedElement()
    data class SelUnderline(val ann: UnderlineAnnotation) : SelectedElement()
}

class ObjectSelection {

    var items: List<SelectedElement> = emptyList()
        private set

    val isActive: Boolean get() = items.isNotEmpty()

    /** Drag offset since move started (world units). */
    var dragAccumX: Float = 0f
    var dragAccumY: Float = 0f

    fun set(items: List<SelectedElement>) {
        this.items = items
        dragAccumX = 0f
        dragAccumY = 0f
    }

    fun clear() {
        items = emptyList()
        dragAccumX = 0f
        dragAccumY = 0f
    }

    /** Union bbox in world coords per page (pages assumed same in a selection). */
    fun bbox(doc: DocumentModel, renderer: ElementRenderer): FloatArray? {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (it in items) {
            val b: FloatArray = when (it) {
                is SelectedElement.SelStroke -> it.stroke.bbox()
                is SelectedElement.SelText -> {
                    val h = renderer.measureTextHeight(it.text).toFloat()
                    floatArrayOf(it.text.x, it.text.y, it.text.x + it.text.width, it.text.y + h)
                }
                is SelectedElement.SelImage -> floatArrayOf(it.image.x, it.image.y, it.image.x + it.image.w, it.image.y + it.image.h)
                is SelectedElement.SelShape -> it.shape.bbox()
                is SelectedElement.SelHighlight -> {
                    val page = doc.pages.getOrNull(it.ann.page) ?: continue
                    val r = it.ann.rects.firstOrNull() ?: continue
                    floatArrayOf(
                        r[0] * page.width, r[1] * page.height,
                        r[2] * page.width, r[3] * page.height
                    )
                }
                is SelectedElement.SelUnderline -> {
                    val page = doc.pages.getOrNull(it.ann.page) ?: continue
                    val r = it.ann.rects.firstOrNull() ?: continue
                    floatArrayOf(r[0] * page.width, r[1] * page.height, r[2] * page.width, r[3] * page.height)
                }
            }
            if (b[0] < minX) minX = b[0]
            if (b[1] < minY) minY = b[1]
            if (b[2] > maxX) maxX = b[2]
            if (b[3] > maxY) maxY = b[3]
        }
        if (minX > maxX) return null
        return floatArrayOf(minX, minY, maxX, maxY)
    }
}
