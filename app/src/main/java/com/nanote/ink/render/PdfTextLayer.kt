package com.nanote.ink.render

import android.os.Handler
import android.os.Looper
import com.nanote.core.DiagLog
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * PDF text extraction for the selection tool (spec 5.3): real text with
 * per-word bounding boxes, extracted by PdfBox-Android on a dedicated
 * background thread (never the pdfium thread, never the main thread).
 *
 * Coordinates are PDF points in the page's top-left origin space — the same
 * space pdfium reports page sizes in, so word rects map 1:1 onto the page
 * coordinate system used by the ink layer.
 */
class PdfTextLayer(private val file: File) : Closeable {

    companion object {
        /** Single text-extraction thread shared process-wide. */
        private val TEXT_THREAD: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "nanote-pdftext")
        }

        private val MAIN = Handler(Looper.getMainLooper())
    }

    private var doc: PDDocument? = null
    private val wordsByPage = ConcurrentHashMap<Int, List<PdfWord>>()
    private val pending = ConcurrentHashMap.newKeySet<Int>()

    private fun document(): PDDocument {
        doc?.let { return it }
        if (!file.exists()) throw java.io.IOException("Missing PDF asset ${file.name}")
        val d = PDDocument.load(file)
        doc = d
        DiagLog.log("PdfText", "opened ${file.name} (${d.numberOfPages} pages)")
        return d
    }

    /** Blocking extraction (call off the main thread); cached per page. */
    fun words(pageIndex: Int): List<PdfWord> {
        wordsByPage[pageIndex]?.let { return it }
        val extracted = try {
            val d = document()
            if (pageIndex >= d.numberOfPages) emptyList()
            else extractWords(d, pageIndex)
        } catch (t: Throwable) {
            DiagLog.error("PdfText", "extraction failed page=$pageIndex", t)
            emptyList()
        }
        wordsByPage[pageIndex] = extracted
        return extracted
    }

    /**
     * Async extraction with a main-thread callback. Safe to call repeatedly;
     * the callback fires once per successful (or cached) load.
     */
    fun prefetch(pageIndex: Int, onReady: (Int) -> Unit = {}) {
        wordsByPage[pageIndex]?.let {
            MAIN.post { onReady(it.size) }
            return
        }
        if (!pending.add(pageIndex)) return
        TEXT_THREAD.execute {
            try {
                val n = words(pageIndex).size
                MAIN.post { onReady(n) }
            } catch (t: Throwable) {
                DiagLog.error("PdfText", "prefetch failed page=$pageIndex", t)
            } finally {
                pending.remove(pageIndex)
            }
        }
    }

    /** Word-grouping text stripper: accumulates glyph runs into word rects. */
    private fun extractWords(d: PDDocument, pageIndex: Int): List<PdfWord> {
        val out = ArrayList<PdfWord>(256)
        val stripper = object : PDFTextStripper() {
            init {
                setSortByPosition(true)
                setStartPage(pageIndex + 1)
                setEndPage(pageIndex + 1)
            }

            override fun writeString(text: String, positions: List<TextPosition>) {
                var left = Float.MAX_VALUE
                var top = Float.MAX_VALUE
                var right = -Float.MAX_VALUE
                var bottom = -Float.MAX_VALUE
                val sb = StringBuilder()
                var any = false
                for (p in positions) {
                    val c = p.unicode ?: continue
                    if (c.isBlank() || c == "\u00A0") {
                        if (any) {
                            out.add(PdfWord(sb.toString(), left, top, right, bottom))
                            sb.setLength(0)
                            left = Float.MAX_VALUE; top = Float.MAX_VALUE
                            right = -Float.MAX_VALUE; bottom = -Float.MAX_VALUE
                            any = false
                        }
                        continue
                    }
                    sb.append(c)
                    any = true
                    val x = p.xDirAdj
                    val yBase = p.yDirAdj
                    val w = p.widthDirAdj
                    val h = p.heightDir
                    if (x < left) left = x
                    if (x + w > right) right = x + w
                    val glyphTop = yBase - h
                    if (glyphTop < top) top = glyphTop
                    if (yBase > bottom) bottom = yBase
                }
                if (any && sb.isNotEmpty()) {
                    out.add(PdfWord(sb.toString(), left, top, right, bottom))
                }
            }
        }
        stripper.getText(d)
        // Drop degenerate boxes.
        return out.filter { it.right > it.left && it.bottom > it.top }
    }

    override fun close() {
        val d = doc
        doc = null
        wordsByPage.clear()
        if (d != null) {
            try {
                TEXT_THREAD.execute { d.close() }
            } catch (t: Throwable) {
                DiagLog.error("PdfText", "close failed", t)
            }
        }
    }
}
