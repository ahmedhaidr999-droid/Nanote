package com.nanote.app.core.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.os.ParcelFileDescriptor
import com.nanote.app.util.AppLog
import com.shockwave.pdfium.PdfDocument
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Thin, thread-safe wrapper around pdfium-android.
 *
 * HARD RULES (anti-gray-page spec):
 *  - The document is opened ONCE per session; every page render performs a proper
 *    openPage(pageIndex) before renderPageBitmap.
 *  - ALL native pdfium calls run on a single dedicated serial thread (pdfium is
 *    NOT thread-safe), additionally guarded by a mutex.
 *  - Failures are logged, retried exactly once, and surfaced (never swallowed).
 */
class PdfDocumentHandle private constructor(
    val pageCount: Int,
    /** Page sizes in PDF points (width, height), index-aligned. */
    val pageSizes: List<PointF>,
    private val core: PdfiumCoreShim,
    private val doc: PdfDocument
) {
    companion object {
        private const val TAG = "PdfEngine"

        /**
         * Opens a PDF file. Must be called off the main thread.
         * Throws [IllegalArgumentException] when the file is corrupt/empty.
         */
        suspend fun open(context: Context, file: File): PdfDocumentHandle = withContext(engineDispatcher) {
            val started = System.currentTimeMillis()
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                val core = PdfiumCoreShim(context.applicationContext)
                val doc = core.newDocument(pfd)
                val pageCount = core.getPageCount(doc)
                if (pageCount <= 0) {
                    core.closeDocument(doc)
                    throw IllegalArgumentException("PDF has no pages: ${file.name}")
                }
                val sizes = ArrayList<PointF>(pageCount)
                for (i in 0 until pageCount) {
                    val w = core.getPageWidthPoint(doc, i)
                    val h = core.getPageHeightPoint(doc, i)
                    sizes.add(PointF(if (w > 0) w.toFloat() else 595f, if (h > 0) h.toFloat() else 842f))
                }
                AppLog.i(TAG, "opened ${file.name}: $pageCount pages in ${System.currentTimeMillis() - started}ms")
                PdfDocumentHandle(pageCount, sizes, core, doc)
            } catch (t: Throwable) {
                AppLog.e(TAG, "failed to open ${file.name}", t)
                try { pfd.close() } catch (_: Exception) {}
                throw t
            }
        }

        /** Single serial thread every pdfium native call is funneled through. */
        internal val engineExecutor =
            Executors.newSingleThreadExecutor { r ->
                Thread(r, "nanote-pdfium").apply { priority = Thread.MAX_PRIORITY - 1 }
            }
        private val engineDispatcher = engineExecutor.asCoroutineDispatcher()
    }

    private val renderMutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + engineDispatcher)

    /**
     * Renders [pageIndex] into [dest]. The bitmap is used as the render target
     * directly (drawSize = dest dimensions, origin 0,0 = full page scaled).
     * Caller must ensure dest is mutable and pre-filled with white.
     * Retries once on native failure. Returns false when rendering failed.
     */
    suspend fun renderPage(pageIndex: Int, dest: Bitmap, allowRetry: Boolean = true): Boolean {
        if (closed.get()) return false
        if (pageIndex < 0 || pageIndex >= pageCount) {
            AppLog.w(TAG, "renderPage out of range: $pageIndex / $pageCount")
            return false
        }
        return withContext(engineDispatcher) {
            renderMutex.withLock {
                var attempt = if (allowRetry) 0 else 1
                var lastError: Throwable? = null
                while (attempt <= 1) {
                    try {
                        val result = core.renderPageBitmapSafe(doc, dest, pageIndex)
                        if (result) return@withLock true
                        lastError = IllegalStateException("pdfium returned failure for page $pageIndex")
                    } catch (t: Throwable) {
                        lastError = t
                        AppLog.e(TAG, "render attempt $attempt failed for page $pageIndex", t)
                    }
                    attempt++
                }
                AppLog.e(TAG, "render FAILED permanently for page $pageIndex", lastError)
                false
            }
        }
    }

    /** Renders a clip (tile) of a page: world rect [sx,sy,sw,sh] scaled into [dest]. */
    suspend fun renderTile(pageIndex: Int, dest: Bitmap, sx: Int, sy: Int, sw: Int, sh: Int): Boolean {
        if (closed.get()) return false
        return withContext(engineDispatcher) {
            renderMutex.withLock {
                try {
                    core.renderPageBitmapClip(doc, dest, pageIndex, sx, sy, sw, sh)
                } catch (t: Throwable) {
                    AppLog.e(TAG, "tile render failed p$pageIndex", t)
                    false
                }
            }
        }
    }

    fun pageSize(pageIndex: Int): PointF = pageSizes.getOrElse(pageIndex) { PointF(595f, 842f) }

    suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        withContext(engineDispatcher) {
            renderMutex.withLock {
                try {
                    core.closeDocument(doc)
                } catch (t: Throwable) {
                    AppLog.w(TAG, "close error: ${t.message}")
                }
            }
        }
    }

    /**
     * Fire-and-forget close posted straight to the native thread — survives
     * coroutine scope cancellation (used when the editor screen is destroyed).
     */
    fun closeAsync() {
        if (!closed.compareAndSet(false, true)) return
        try {
            engineExecutor.execute {
                try {
                    core.closeDocument(doc)
                } catch (t: Throwable) {
                    AppLog.w(TAG, "closeAsync error: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "closeAsync rejected: ${t.message}")
        }
    }
}
