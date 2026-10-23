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
 * Platform PdfRenderer wrapper (spec 7): opens one page at a time. The
 * renderer is opened lazily ON THE CALLING THREAD — sessions use it strictly
 * from the tile render thread, imports from Dispatchers.IO, so no mutex is
 * needed in practice; the import flow closes its own instance.
 */
class PdfPageProvider(private val file: File) : Closeable {

    private var renderer: PdfRenderer? = null

    private fun open(): PdfRenderer {
        renderer?.let { return it }
        if (!file.exists()) throw IOException("Missing PDF asset ${file.name}")
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return try {
            PdfRenderer(pfd).also { renderer = it }
        } catch (e: SecurityException) {
            pfd.close()
            throw PdfProtectedException("This PDF is password-protected")
        }
    }

    val pageCount: Int get() = open().pageCount

    /** Page size in PDF points. */
    fun pageSizePts(index: Int): Pair<Float, Float> {
        val r = open()
        val page = r.openPage(index)
        try {
            return page.width.toFloat() to page.height.toFloat()
        } finally {
            page.close()
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
        val r = open()
        val page = r.openPage(index)
        try {
            val m = Matrix()
            m.setScale(tileScale, tileScale)
            m.postTranslate(-pageLeft * tileScale, -pageTop * tileScale)
            page.render(out, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        } finally {
            page.close()
        }
    }

    /** Full-page bitmap at [widthPx] (white background), for export/thumbnails. */
    fun renderFull(index: Int, widthPx: Int, bgArgb: Int = 0xFFFFFFFF.toInt()): Bitmap {
        val r = open()
        val page = r.openPage(index)
        try {
            val h = (widthPx * page.height / page.width.toFloat()).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(bgArgb)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        } finally {
            page.close()
        }
    }

    override fun close() {
        try {
            renderer?.close()
        } finally {
            renderer = null
        }
    }
}
