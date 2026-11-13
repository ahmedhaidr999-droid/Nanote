package com.nanote.ink.render

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import com.nanote.core.DiagLog
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Thrown when a PDF is password-protected. */
class PdfProtectedException(message: String) : IOException(message)

/** One selectable word with its bounding box in PDF points (origin top-left, y down). */
data class PdfWord(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

/**
 * v2.0 PDF engine — pdfium (com.github.mhiew:pdfium-android) replacing the
 * framework android.graphics.pdf.PdfRenderer.
 *
 * Why the old engine showed gray pages (v1.x): PdfRenderer allows exactly ONE
 * open page at a time, forcing constant openPage/closePage churn under
 * multi-thread contention; a single uncaught exception in a tile worker then
 * permanently poisoned that tile's pending-render entry — the rest of the
 * document stayed the raw surface color (gray). pdfium keeps any number of
 * pages open simultaneously and this wrapper adds the v2 hardening rules:
 *
 *  1. The document is opened ONCE per session (lazy, on first use) and closed
 *     only by [close]. No code path reopens the file per page.
 *  2. Every page is opened through openPage(index) with ITS OWN index — there
 *     is no path that renders page 0 regardless of the requested index.
 *  3. Strip layout uses the real per-page sizes from [pageSizePts] — the total
 *     height is the sum of actual page heights, never first-page-height × count.
 *  4. pageCount is validated > 0 on open; a 0-page/corrupt file fails loudly
 *     with a clear message instead of rendering nothing.
 *  5. ALL pdfium calls are funneled through one dedicated single-thread
 *     executor ([PDF_THREAD]) — pdfium is not concurrency-safe, so every call
 *     is serialized there regardless of the calling thread (tile threads,
 *     import coroutines, export…). Callers block on the Future, which also
 *     keeps the old (synchronous) public API intact.
 *  6. Rendering retries once on failure (fresh parameters, page re-opened);
 *     a second failure propagates so the tile layer can surface the
 *     "couldn't render — tap to retry" chip. Exceptions are NEVER swallowed
 *     silently — everything is logged to DiagLog.
 *
 * The public API is drop-in compatible with the v1.x provider:
 * [pageCount], [pageSizePts], [renderTile], [renderFull], [close] — plus the
 * new text layer [words] used by PDF text selection.
 */
class PdfPageProvider(private val file: File) : Closeable {

    companion object {
        /**
         * THE single pdfium thread (spec 2.1.6). One process-wide executor:
         * even two split-workspace notebooks share it, so pdfium never sees
         * concurrent calls.
         */
        private val PDF_THREAD: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "nanote-pdfium").apply { priority = Thread.NORM_PRIORITY + 1 }
        }

        /** Application context for PdfiumCore (set once in NanoteApp.onCreate). */
        @Volatile
        var appContext: Context? = null

        private fun <T> onPdfThread(block: () -> T): T {
            val future: Future<T> = PDF_THREAD.submit(Callable(block))
            return future.get()
        }
    }

    private val lock = Any()

    private var core: PdfiumCore? = null
    private var doc: PdfDocument? = null
    private var fd: ParcelFileDescriptor? = null

    private var cachedPageCount = -1

    /** Real page sizes in PDF points, filled on open. */
    private var sizes: Array<Pair<Float, Float>>? = null

    private val textLayer = PdfTextLayer(file)

    val pageCount: Int
        get() = synchronized(lock) {
            cachedPageCount.takeIf { it >= 0 } ?: run {
                val n = onPdfThread {
                    val o = openLocked()
                    o.core.getPageCount(o.doc)
                }
                if (n <= 0) {
                    DiagLog.error("PdfEngine", "PDF has no pages: ${file.name}")
                    throw IOException("PDF has no pages: ${file.name}")
                }
                cachedPageCount = n
                DiagLog.log("PdfEngine", "opened ${file.name}: pageCount=$n")
                n
            }
        }

    // ------------------------------------------------------------- internals

    /** Opens the pdfium document once; must be called on [PDF_THREAD]. */
    private fun openLocked(): Opened {
        doc?.let { d -> return Opened(core!!, d) }
        if (!file.exists()) throw IOException("Missing PDF asset ${file.name}")
        val ctx = appContext
            ?: throw IOException("PdfPageProvider.appContext not initialized")
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            val c = PdfiumCore(ctx)
            val d = c.newDocument(pfd)
            core = c
            doc = d
            fd = pfd
            return Opened(c, d)
        } catch (e: com.shockwave.pdfium.PdfPasswordException) {
            pfd.close()
            throw PdfProtectedException("This PDF is password-protected")
        } catch (e: SecurityException) {
            pfd.close()
            throw PdfProtectedException("This PDF is password-protected")
        } catch (e: IOException) {
            pfd.close()
            throw IOException("Cannot open '${file.name}' — the PDF is damaged or unsupported", e)
        } catch (e: Throwable) {
            pfd.close()
            throw IOException("Cannot open '${file.name}' as a PDF", e)
        }
    }

    private class Opened(val core: PdfiumCore, val doc: PdfDocument)

    /** Opens page [index] (its own, correct index — rule 2) on the pdfium thread. */
    private fun Opened.ensurePage(index: Int) {
        check(index in 0 until cachedPageCount) { "Page index $index out of range" }
        core.openPage(doc, index)
    }

    private fun Opened.sizeOf(index: Int): Pair<Float, Float> {
        ensurePage(index)
        val w = core.getPageWidthPoint(doc, index).toFloat()
        val h = core.getPageHeightPoint(doc, index).toFloat()
        if (w <= 0f || h <= 0f) throw IOException("Page $index reports invalid size ${w}x${h}")
        return w to h
    }

    /** Page size in PDF points; sizes are cached after the first query. */
    fun pageSizePts(index: Int): Pair<Float, Float> = synchronized(lock) {
        val arr = sizes ?: run {
            val n = pageCount
            val a = arrayOfNulls<Pair<Float, Float>>(n)
            onPdfThread {
                val o = openLocked()
                for (i in 0 until n) a[i] = o.sizeOf(i)
            }
            @Suppress("UNCHECKED_CAST")
            (a as Array<Pair<Float, Float>>).also { sizes = it }
        }
        arr.getOrElse(index) { 595f to 842f }
    }

    // ------------------------------------------------------------- rendering

    /**
     * Renders the page region [pageLeft, pageTop] .. +tile/tileScale of page
     * points into [out] (a TILE_PX tile) at [tileScale] px/pt, using pdfium's
     * destination-offset rendering: the whole page is scaled to
     * (pageW*tileScale, pageH*tileScale) and translated so that page point
     * (pageLeft, pageTop) lands at bitmap (0,0); pdfium clips to the bitmap.
     * Retries exactly once on failure (rule 6).
     */
    fun renderTile(
        index: Int,
        tileScale: Float,
        pageLeft: Float,
        pageTop: Float,
        out: Bitmap
    ) {
        val s = synchronized(lock) {
            onPdfThread {
                var lastError: Throwable? = null
                repeat(2) { attempt ->
                    try {
                        val o = openLocked()
                        o.ensurePage(index)
                        val (w, h) = o.sizeOf(index)
                        val drawW = (w * tileScale).roundToInt().coerceAtLeast(1)
                        val drawH = (h * tileScale).roundToInt().coerceAtLeast(1)
                        val startX = (-pageLeft * tileScale).roundToInt()
                        val startY = (-pageTop * tileScale).roundToInt()
                        o.core.renderPageBitmap(o.doc, out, index, startX, startY, drawW, drawH)
                        return@onPdfThread true
                    } catch (t: Throwable) {
                        lastError = t
                        DiagLog.error("PdfEngine", "renderTile page=$index attempt=$attempt failed", t)
                    }
                }
                throw lastError ?: IOException("renderTile failed for page $index")
            }
        }
        if (!s) throw IOException("renderTile failed for page $index")
    }

    /** Full-page bitmap at [widthPx] (white background), for export/thumbnails. */
    fun renderFull(index: Int, widthPx: Int, bgArgb: Int = 0xFFFFFFFF.toInt()): Bitmap {
        val (pw, ph) = pageSizePts(index)
        val w = widthPx.coerceIn(32, 4096)
        val h = (w * ph / pw).roundToInt().coerceIn(1, 8192)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(bgArgb)
        val ok = synchronized(lock) {
            onPdfThread {
                var lastError: Throwable? = null
                repeat(2) { attempt ->
                    try {
                        val o = openLocked()
                        o.ensurePage(index)
                        o.core.renderPageBitmap(o.doc, bmp, index, 0, 0, w, h)
                        return@onPdfThread true
                    } catch (t: Throwable) {
                        lastError = t
                        DiagLog.error("PdfEngine", "renderFull page=$index attempt=$attempt failed", t)
                    }
                }
                throw lastError ?: IOException("renderFull failed for page $index")
            }
        }
        if (!ok) throw IOException("renderFull failed for page $index")
        return bmp
    }

    // ------------------------------------------------------------ text layer

    /**
     * Extracted words of a page (PDF points, page coordinate space) for text
     * selection. Runs on the dedicated text thread (PdfBox), never blocks the
     * pdfium thread, and caches per page. Returns an empty list when the page
     * has no extractable text.
     */
    fun words(index: Int): List<PdfWord> {
        if (index !in 0 until pageCount) return emptyList()
        return try {
            textLayer.words(index)
        } catch (t: Throwable) {
            DiagLog.error("PdfEngine", "text extraction failed for page $index", t)
            emptyList()
        }
    }

    /** Warm up the text layer of a page (async fire-and-forget). */
    fun prefetchWords(index: Int) {
        textLayer.prefetch(index) { n -> DiagLog.log("PdfEngine", "text layer ready: page $index ($n words)") }
    }

    override fun close() {
        synchronized(lock) {
            val d = doc
            val c = core
            doc = null
            core = null
            sizes = null
            cachedPageCount = -1
            if (d != null && c != null) {
                try {
                    onPdfThread { c.closeDocument(d) }
                    DiagLog.log("PdfEngine", "closed ${file.name}")
                } catch (t: Throwable) {
                    DiagLog.error("PdfEngine", "close failed for ${file.name}", t)
                }
            }
            try {
                fd?.close()
            } catch (_: Throwable) {
            }
            fd = null
        }
        textLayer.close()
    }
}
