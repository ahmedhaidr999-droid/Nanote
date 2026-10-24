package com.nanote.ink.render

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.io.IOException

/** Thrown when a PDF is password-protected (PdfRenderer throws SecurityException). */
class PdfProtectedException(message: String) : IOException(message)

/**
 * v1.3 PDF engine (rebuilt from the v1.2 wrapper):
 *  - ONE instance can now be shared by every page session of a notebook
 *    (previously each page opened its own PdfRenderer — slow page-to-page
 *    browsing). All renderer access is synchronized, so the per-page tile
 *    threads and the import thread can safely share it.
 *  - The last opened page stays open (PdfRenderer allows exactly one), so
 *    rendering consecutive tiles of the same page skips openPage churn.
 *  - Corrupt files fail with a clear message instead of a raw IOException.
 */
class PdfPageProvider(private val file: File) : Closeable {

    private val lock = Any()

    private var renderer: PdfRenderer? = null
    private var openPageIndex = -1
    private var openPage: PdfRenderer.Page? = null

    /** Opens (once) and returns the renderer under [lock]. */
    private fun openLocked(): PdfRenderer {
        renderer?.let { return it }
        if (!file.exists()) throw IOException("Missing PDF asset ${file.name}")
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            return PdfRenderer(pfd).also { renderer = it }
        } catch (e: SecurityException) {
            pfd.close()
            throw PdfProtectedException("This PDF is password-protected")
        } catch (e: IOException) {
            pfd.close()
            throw IOException("Cannot open '${file.name}' — the PDF is damaged or unsupported", e)
        } catch (e: Exception) {
            pfd.close()
            throw IOException("Cannot open '${file.name}' as a PDF", e)
        }
    }

    /** Page handle for [index], reusing the currently open page when possible. */
    private fun pageLocked(index: Int): PdfRenderer.Page {
        val r = openLocked()
        openPage?.let {
            if (openPageIndex == index) return it
            closePageLocked()
        }
        return r.openPage(index).also {
            openPage = it
            openPageIndex = index
        }
    }

    private fun closePageLocked() {
        try {
            openPage?.close()
        } finally {
            openPage = null
            openPageIndex = -1
        }
    }

    val pageCount: Int get() = synchronized(lock) { openLocked().pageCount }

    /** Page size in PDF points. */
    fun pageSizePts(index: Int): Pair<Float, Float> {
        synchronized(lock) {
            val page = pageLocked(index)
            return page.width.toFloat() to page.height.toFloat()
        }
    }

    /**
     * Renders the page region [pageLeft, pageTop] .. +tile/tileScale of page
     * points into [out] (512 px tile) at [tileScale] px/pt. Content mapping
     * outside the bitmap is clipped by Skia — cost stays tile-sized.
     */
    fun renderTile(
        index: Int,
        tileScale: Float,
        pageLeft: Float,
        pageTop: Float,
        out: Bitmap
    ) {
        synchronized(lock) {
            val page = pageLocked(index)
            val m = Matrix()
            m.setScale(tileScale, tileScale)
            m.postTranslate(-pageLeft * tileScale, -pageTop * tileScale)
            page.render(out, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
    }

    /** Full-page bitmap at [widthPx] (white background), for export/thumbnails. */
    fun renderFull(index: Int, widthPx: Int, bgArgb: Int = 0xFFFFFFFF.toInt()): Bitmap {
        synchronized(lock) {
            val page = pageLocked(index)
            val h = (widthPx * page.height / page.width.toFloat()).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(bgArgb)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        }
    }

    override fun close() {
        synchronized(lock) {
            closePageLocked()
            try {
                renderer?.close()
            } finally {
                renderer = null
            }
        }
    }
}
