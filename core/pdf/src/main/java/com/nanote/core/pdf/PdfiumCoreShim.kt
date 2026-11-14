package com.nanote.core.pdf

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore

/**
 * Isolation layer over the REAL pdfium-android 1.9.0 API
 * (verified via javap against the published artifact):
 *
 *  PdfiumCore(Context)
 *  PdfDocument newDocument(ParcelFileDescriptor)
 *  int getPageCount(PdfDocument)
 *  long openPage(PdfDocument, int)               // must be called before rendering
 *  int getPageWidthPoint / getPageHeightPoint
 *  void renderPageBitmap(PdfDocument, Bitmap, pageIndex, startX, startY, drawSizeX, drawSizeY, renderAnnotations)
 *  void closeDocument(PdfDocument)
 */
class PdfiumCoreShim(context: Context) {

    private val core: PdfiumCore = PdfiumCore(context.applicationContext)
    private var openedPages = HashSet<Int>()

    fun newDocument(pfd: ParcelFileDescriptor): PdfDocument = core.newDocument(pfd)

    fun getPageCount(doc: PdfDocument): Int = core.getPageCount(doc)

    fun getPageWidthPoint(doc: PdfDocument, index: Int): Int = core.getPageWidthPoint(doc, index)

    fun getPageHeightPoint(doc: PdfDocument, index: Int): Int = core.getPageHeightPoint(doc, index)

    /** Proper openPage(pageIndex) before every first render of that page. */
    fun ensurePageOpen(doc: PdfDocument, pageIndex: Int): Boolean {
        if (pageIndex in openedPages) return true
        val ptr = core.openPage(doc, pageIndex)
        if (ptr <= 0) {
            AppLogShim.w("openPage returned $ptr for page $pageIndex")
            return false
        }
        openedPages.add(pageIndex)
        return true
    }

    fun renderPageBitmapSafe(doc: PdfDocument, dest: Bitmap, pageIndex: Int): Boolean {
        if (!ensurePageOpen(doc, pageIndex)) return false
        dest.eraseColor(android.graphics.Color.WHITE)
        core.renderPageBitmap(
            doc, dest, pageIndex,
            /* startX = */ 0, /* startY = */ 0,
            /* drawSizeX = */ dest.width, /* drawSizeY = */ dest.height,
            /* renderAnnots = */ true
        )
        return true
    }

    fun renderPageBitmapClip(
        doc: PdfDocument, dest: Bitmap, pageIndex: Int,
        sx: Int, sy: Int, sw: Int, sh: Int
    ): Boolean {
        if (!ensurePageOpen(doc, pageIndex)) return false
        dest.eraseColor(android.graphics.Color.WHITE)
        core.renderPageBitmap(doc, dest, pageIndex, sx, sy, sw, sh, true)
        return true
    }

    fun closeDocument(doc: PdfDocument) {
        openedPages.clear()
        core.closeDocument(doc)
    }
}

private object AppLogShim {
    fun w(msg: String) = com.nanote.core.common.AppLog.w("PdfiumShim", msg)
}
