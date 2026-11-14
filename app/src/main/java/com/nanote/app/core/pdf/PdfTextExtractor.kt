package com.nanote.app.core.pdf

import android.graphics.PointF
import com.nanote.app.util.AppLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A word extracted from a PDF page with its bounding box in PDF-point space,
 * TOP-LEFT origin — the same coordinate space pdfium renders into.
 */
data class PdfWord(
    val text: String,
    val x0: Float, val y0: Float,
    val x1: Float, val y1: Float,
    val lineIndex: Int
) {
    fun center(): PointF = PointF((x0 + x1) / 2f, (y0 + y1) / 2f)
    fun containsPoint(x: Float, y: Float, pad: Float): Boolean =
        x >= x0 - pad && x <= x1 + pad && y >= y0 - pad && y <= y1 + pad
}

/** All extractable text of one page, grouped by line. */
class PageText(
    val pageIndex: Int,
    val pageWidth: Float,
    val pageHeight: Float,
    val lines: List<List<PdfWord>>
) {
    val allWords: List<PdfWord> = lines.flatten()

    val hasText: Boolean get() = allWords.isNotEmpty()

    /** Nearest word to a point (for long-press), null when too far (empty area). */
    fun wordNear(x: Float, y: Float): PdfWord? {
        var best: PdfWord? = null
        var bestDist = Float.MAX_VALUE
        for (w in allWords) {
            if (w.containsPoint(x, y, HIT_PAD)) return w
            val cx = (w.x0 + w.x1) / 2f
            val cy = (w.y0 + w.y1) / 2f
            val d = kotlin.math.hypot(cx - x, cy - y)
            if (d < bestDist) { bestDist = d; best = w }
        }
        return if (best != null && bestDist < MAX_NEAREST_DIST) best else null
    }

    fun indexOf(word: PdfWord): Int = allWords.indexOfFirst { it === word || it.text == word.text && it.x0 == word.x0 && it.y0 == word.y0 }

    /** Text between two words inclusive, in reading order. */
    fun textBetween(a: PdfWord, b: PdfWord): String {
        val ia = indexOf(a); val ib = indexOf(b)
        if (ia < 0 || ib < 0) return a.text
        val from = minOf(ia, ib); val to = maxOf(ia, ib)
        val sb = StringBuilder()
        var prevLine = -1
        for (i in from..to) {
            val w = allWords[i]
            if (prevLine != -1) {
                sb.append(if (w.lineIndex != prevLine) '\n' else ' ')
            }
            sb.append(w.text)
            prevLine = w.lineIndex
        }
        return sb.toString()
    }

    /** Line rects covering the words between a..b (absolute page coords). */
    fun lineRectsBetween(a: PdfWord, b: PdfWord): List<FloatArray> {
        val ia = indexOf(a); val ib = indexOf(b)
        if (ia < 0 || ib < 0) return emptyList()
        val from = minOf(ia, ib); val to = maxOf(ia, ib)
        val byLine = LinkedHashMap<Int, MutableList<PdfWord>>()
        for (i in from..to) {
            val w = allWords[i]
            byLine.getOrPut(w.lineIndex) { ArrayList() }.add(w)
        }
        return byLine.values.map { ws ->
            floatArrayOf(
                ws.minOf { it.x0 },
                ws.minOf { it.y0 },
                ws.maxOf { it.x1 },
                ws.maxOf { it.y1 }
            )
        }
    }

    fun allRects(): List<FloatArray> =
        allWords.groupBy { it.lineIndex }.values.map { ws ->
            floatArrayOf(ws.minOf { it.x0 }, ws.minOf { it.y0 }, ws.maxOf { it.x1 }, ws.maxOf { it.y1 })
        }

    fun fullText(): String = allWords.joinToString(" ") { it.text }

    companion object {
        private const val HIT_PAD = 4f
        private const val MAX_NEAREST_DIST = 28f
    }
}

/**
 * Lazy, cached PDF text extraction built on pdfbox-android.
 * The PDDocument is opened only when text is first requested (so opening a
 * document for reading stays fast), then page texts are extracted on demand.
 */
class PdfTextExtractor {

    companion object {
        private const val TAG = "PdfTextExtractor"
    }

    private var sourceFile: File? = null
    @Volatile
    private var pdDoc: Any? = null
    private val openMutex = Mutex()
    private val cache = ConcurrentHashMap<Int, PageText>()
    @Volatile
    private var broken = false

    val isAttached: Boolean get() = sourceFile != null

    fun attach(file: File) {
        if (sourceFile == file) return
        close()
        sourceFile = file
    }

    /**
     * Returns the page text, extracting it on first use.
     * Returns null when the page has no extractable text (e.g. scanned image)
     * or the document cannot be parsed — callers must treat null as
     * "no text selection here".
     */
    suspend fun pageText(pageIndex: Int, pageSize: PointF): PageText? = withContext(Dispatchers.IO) {
        if (broken) return@withContext null
        cache[pageIndex]?.let { return@withContext it }
        val file = sourceFile ?: return@withContext null
        try {
            val doc = ensureOpen(file)
            val extractor = WordCollector(pageIndex, pageSize.x, pageSize.y)
            extractor.extract(doc, pageIndex)
            val result = extractor.build()
            cache[pageIndex] = result
            result
        } catch (t: Throwable) {
            AppLog.w(TAG, "text extraction failed page $pageIndex: ${t.message}")
            null
        }
    }

    fun evict(pageIndex: Int) {
        cache.remove(pageIndex)
    }

    fun close() {
        val d = pdDoc
        pdDoc = null
        cache.clear()
        broken = false
        sourceFile = null
        if (d != null) {
            try {
                (d as com.tom_roush.pdfbox.pdmodel.PDDocument).close()
            } catch (t: Throwable) {
                AppLog.w(TAG, "close: ${t.message}")
            }
        }
    }

    private suspend fun ensureOpen(file: File): com.tom_roush.pdfbox.pdmodel.PDDocument =
        openMutex.withLock {
            (pdDoc as? com.tom_roush.pdfbox.pdmodel.PDDocument)?.let { return it }
            val doc = com.tom_roush.pdfbox.pdmodel.PDDocument.load(file)
            pdDoc = doc
            AppLog.i(TAG, "PDDocument opened lazily for text extraction")
            doc
        }
}
