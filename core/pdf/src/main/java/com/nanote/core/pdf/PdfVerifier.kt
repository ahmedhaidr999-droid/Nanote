package com.nanote.core.pdf

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import com.nanote.core.common.AppLog
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Independent verification of exported PDFs: reopens the file with pdfium
 * (a DIFFERENT engine than the pdfbox writer) and checks that it opens,
 * has the expected page count and that pages actually rasterize.
 *
 * This is the "reopen exported files using an independent renderer"
 * requirement — every export runs through it before being reported as
 * successful.
 */
object PdfVerifier {

    private const val TAG = "PdfVerifier"

    data class Verification(
        val opens: Boolean,
        val pageCount: Int,
        val firstPageRenders: Boolean
    ) {
        val ok: Boolean get() = opens && pageCount > 0 && firstPageRenders
    }

    /**
     * Verifies [file]. [expectedPages] when non-null must match exactly.
     * Renders the first page into a small bitmap to prove rasterizability.
     */
    suspend fun verify(context: Context, file: File, expectedPages: Int? = null): Verification =
        withContext(Dispatchers.IO) {
            if (!file.exists() || file.length() == 0L) {
                return@withContext Verification(false, 0, false)
            }
            val core = PdfiumCore(context.applicationContext)
            var doc: PdfDocument? = null
            try {
                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                doc = core.newDocument(pfd)
                val pageCount = core.getPageCount(doc)
                if (pageCount <= 0) {
                    return@withContext Verification(false, 0, false)
                }
                if (expectedPages != null && pageCount != expectedPages) {
                    AppLog.w(TAG, "verify: page count $pageCount != expected $expectedPages")
                    return@withContext Verification(true, pageCount, false)
                }
                val size = core.getPageSize(doc, 0)
                val aspect = if (size.height > 0) size.width.toFloat() / size.height.toFloat() else 0.7f
                val w = 96
                val h = (w / aspect).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                core.openPage(doc, 0)
                core.renderPageBitmap(doc, bitmap, 0, 0, 0, w, h, false)
                val renders = !bitmapIsBlank(bitmap)
                if (!renders) AppLog.w(TAG, "verify: first page rendered blank")
                Verification(true, pageCount, renders)
            } catch (t: Throwable) {
                AppLog.e(TAG, "verify failed for ${file.name}", t)
                Verification(false, 0, false)
            } finally {
                try {
                    doc?.let { core.closeDocument(it) }
                } catch (_: Throwable) {}
            }
        }

    /** True when every pixel is (near) white — i.e. nothing was drawn. */
    private fun bitmapIsBlank(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        val step = 7
        for (y in 0 until h step step) {
            for (x in 0 until w step step) {
                if (bitmap.getPixel(x, y) != android.graphics.Color.WHITE) {
                    // tolerate near-white antialiasing
                    val p = bitmap.getPixel(x, y)
                    val r = android.graphics.Color.red(p)
                    val g = android.graphics.Color.green(p)
                    val b = android.graphics.Color.blue(p)
                    if (r < 245 || g < 245 || b < 245) return false
                }
            }
        }
        return true
    }
}
