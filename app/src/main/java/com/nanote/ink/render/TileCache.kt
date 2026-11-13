package com.nanote.ink.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache
import com.nanote.core.DiagLog
import com.nanote.domain.model.PageObject
import com.nanote.ink.engine.Bounds
import com.nanote.ink.engine.PageDoc
import com.nanote.ink.engine.PenType
import com.nanote.ink.engine.Stroke
import com.nanote.ink.engine.paperById
import java.util.concurrent.Executors
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Snapshot of committed page content (immutable — safe to hand to a render
 * thread while the main thread keeps editing).
 */
data class PageSnapshot(
    val strokes: List<Stroke>,
    val objects: List<PageObject>,
    val revision: Long
)

/**
 * Asynchronous bitmap tile cache for committed ink (spec section 7).
 *  - 512 px tiles; zoom buckets re-render about every 1.41x.
 *  - Rendering runs on a single background thread from a [PageSnapshot].
 *  - A tile captured at revision r stays valid until a change AFTER r
 *    intersects its page rectangle (PageDoc.dirtySince).
 *  - LruCache budget = 1/8 of the max heap; eviction by allocation size.
 */
class InkTileCache(
    private val pageW: Float,
    private val pageH: Float,
    private val paperId: String,
    private val bgArgb: Int,
    private val maxBytes: Int = (Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtLeast(16 shl 20)
) {

    /** Set by the view; called on the main thread when a tile finishes rendering. */
    var onTileReadyCallback: ((tile: TileInfo) -> Unit)? = null

    /**
     * v2.0: called on the main thread when a tile FAILED to render even
     * after its automatic retry. The view surfaces the in-page "couldn't
     * render — tap to retry" chip; it never leaves a silent gray gap.
     */
    var onTileFailedCallback: ((tile: TileInfo) -> Unit)? = null

    data class TileInfo(val bucket: Int, val tx: Int, val ty: Int)

    class Tile(val bitmap: Bitmap, val rev: Long, val bucket: Int)

    private val cache = object : LruCache<String, Tile>(maxBytes) {
        override fun sizeOf(key: String, value: Tile): Int = value.bitmap.allocationByteCount
    }

    private val pending = HashSet<String>()

    /** v2.0: tile keys that failed BOTH attempts — never re-requested until [clearFailures]. */
    private val failedTiles = HashSet<String>()

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val renderThread = Executors.newSingleThreadExecutor { r ->
        Thread(r, "nanote-tiles").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    private val painter = StrokePainter()
    private val path = Path()

    /** Snapshot provider is set by the owner before the first draw. */
    lateinit var snapshotProvider: () -> PageSnapshot

    /** PDF background support (M4): set when the page annotates a PDF page. */
    var pdfPageIndex: Int? = null

    /** Renders the PDF region of a tile into [out]; runs on the tile thread. */
    var pdfTileDraw: ((pageIndex: Int, tileScale: Float, pageLeft: Float, pageTop: Float, out: Bitmap) -> Unit)? = null

    private var closed = false

    private fun key(bucket: Int, tx: Int, ty: Int) = "$bucket:$tx:$ty"

    fun zoomBucket(scale: Float): Int = (ln(scale.toDouble()) * 2f).roundToInt()

    /** v2.0: failed tiles exist — the view shows the retry chip over this page. */
    fun hasFailures(): Boolean = synchronized(failedTiles) { failedTiles.isNotEmpty() }

    /** v2.0: wipe the failure set so the next draw re-requests every failed tile. */
    fun clearFailures() {
        synchronized(failedTiles) { failedTiles.clear() }
    }

    private fun tileScaleOf(bucket: Int): Float = 2.0.pow(bucket / 2.0).toFloat()

    /**
     * Draws all visible tiles into [c] and requests async renders for stale or
     * missing ones. [scale]/[transX]/[transY] describe the current view matrix.
     *
     * v2.0: the render-request window extends one tile ring beyond the
     * viewport in every direction (lazy loading with a pre-load margin,
     * spec 2.1.8) so scrolling reveals already-rendered tiles.
     */
    fun drawInto(
        c: Canvas,
        scale: Float,
        transX: Float,
        transY: Float,
        viewW: Float,
        viewH: Float,
        doc: PageDoc
    ) {
        val bucket = zoomBucket(scale)
        val tileScale = tileScaleOf(bucket)
        val tilePage = TILE_PX / tileScale
        // Visible tile range ± 1 ring = the async pre-load margin (spec 2.1.8).
        val xv0 = ((-transX) / scale / tilePage).toInt()
        val xv1 = ((viewW - transX) / scale / tilePage).toInt()
        val yv0 = ((-transY) / scale / tilePage).toInt()
        val yv1 = ((viewH - transY) / scale / tilePage).toInt()
        val x0 = (xv0 - 1).coerceAtLeast(0)
        val x1 = xv1 + 1
        val y0 = (yv0 - 1).coerceAtLeast(0)
        val y1 = yv1 + 1
        val maxTx = (pageW / tilePage).toInt()
        val maxTy = (pageH / tilePage).toInt()
        val dirty = Bounds()
        val tileBounds = Bounds()

        for (ty in y0..y1) {
            if (ty > maxTy) break
            for (tx in x0..x1) {
                if (tx > maxTx) break
                val k = key(bucket, tx, ty)
                val tile = cache.get(k)
                tileBounds.set(tx * tilePage, ty * tilePage, (tx + 1) * tilePage, (ty + 1) * tilePage)
                val changed = doc.dirtySince(tile?.rev ?: Long.MIN_VALUE, dirty)
                val valid = tile != null && (!changed || !dirty.intersects(tileBounds))
                if (tile != null && valid) {
                    drawTile(c, tile, scale, transX, transY, tx, ty)
                } else {
                    val failed = synchronized(failedTiles) { k in failedTiles }
                    if (!failed) requestRender(bucket, tx, ty, k)
                    // Stale fallback: same position from a lower-resolution bucket.
                    var fallback: Tile? = null
                    var fb = bucket - 1
                    while (fb >= bucket - 3 && fallback == null) {
                        fallback = cache.get(key(fb, tx, ty))
                        fb--
                    }
                    if (fallback != null) {
                        drawTile(c, fallback, scale, transX, transY, tx, ty)
                    }
                }
            }
        }
    }

    private val srcRect = Rect()
    private val dstRectF = RectF()

    private fun drawTile(
        c: Canvas,
        tile: Tile,
        currentScale: Float,
        transX: Float,
        transY: Float,
        tx: Int,
        ty: Int
    ) {
        val tileScale = tileScaleOf(tile.bucket)
        val k = currentScale / tileScale
        val left = tx * TILE_PX * k + transX
        val top = ty * TILE_PX * k + transY
        val size = TILE_PX * k
        srcRect.set(0, 0, TILE_PX, TILE_PX)
        dstRectF.set(left, top, left + size, top + size)
        c.drawBitmap(tile.bitmap, srcRect, dstRectF, null)
    }

    /**
     * v2.0 hardened request path — the anti-gray-page core:
     *  - EVERYTHING runs inside try/finally so [pending] is ALWAYS cleaned,
     *    even on failure (v1.x leaked the pending key on the first exception,
     *    which permanently froze that tile → gray).
     *  - A failed render retries ONCE; a second failure records the tile in
     *    [failedTiles] and fires [onTileFailedCallback] so the UI shows the
     *    "couldn't render — tap to retry" chip (never a silent gray page).
     *  - Async jobs are queued to the single render thread and are never
     *    cancelled by earlier completions (spec 2.1.10).
     */
    private fun requestRender(bucket: Int, tx: Int, ty: Int, k: String) {
        if (closed || !::snapshotProvider.isInitialized) return
        synchronized(pending) {
            if (!pending.add(k)) return
        }
        val snapshot = snapshotProvider()
        renderThread.execute {
            var bitmap: Bitmap? = null
            var failure: Throwable? = null
            try {
                var attempts = 0
                while (attempts < 2 && bitmap == null) {
                    attempts++
                    try {
                        bitmap = renderTile(snapshot, bucket, tx, ty)
                        failure = null
                    } catch (t: Throwable) {
                        failure = t
                        DiagLog.error(
                            "TileCache",
                            "render failed (attempt $attempts) bucket=$bucket tile=$tx,$ty",
                            t
                        )
                    }
                }
                if (bitmap != null) {
                    cache.put(k, Tile(bitmap, snapshot.revision, bucket))
                } else {
                    synchronized(failedTiles) { failedTiles.add(k) }
                    val f = failure
                    DiagLog.error("TileCache", "tile $k gave up after retry: ${f?.message}")
                }
            } finally {
                // ALWAYS release the pending slot — no poisoned keys, ever.
                synchronized(pending) { pending.remove(k) }
            }
            val bmp = bitmap
            if (bmp != null) {
                mainHandler.post {
                    if (!closed) onTileReadyCallback?.invoke(TileInfo(bucket, tx, ty))
                }
            } else {
                mainHandler.post {
                    if (!closed) onTileFailedCallback?.invoke(TileInfo(bucket, tx, ty))
                }
            }
        }
    }

    private fun renderTile(snapshot: PageSnapshot, bucket: Int, tx: Int, ty: Int): Bitmap? {
        val tileScale = tileScaleOf(bucket)
        val tilePage = TILE_PX / tileScale
        val pageLeft = tx * tilePage
        val pageTop = ty * tilePage
        if (pageLeft >= pageW || pageTop >= pageH) return null

        val tileBounds = Bounds()
        tileBounds.set(pageLeft, pageTop, pageLeft + tilePage, pageTop + tilePage)

        val bitmap = Bitmap.createBitmap(TILE_PX, TILE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.save()
        canvas.scale(tileScale, tileScale)
        canvas.translate(-pageLeft, -pageTop)
        canvas.clipRect(pageLeft, pageTop, pageLeft + tilePage, pageTop + tilePage)

        val pdfIndex = pdfPageIndex
        val pdfDraw = pdfTileDraw
        if (pdfIndex != null && pdfDraw != null) {
            bitmap.eraseColor(bgArgb)
            pdfDraw(pdfIndex, tileScale, pageLeft, pageTop, bitmap)
        } else {
            PaperRenderer.drawPaper(canvas, pageW, pageH, paperById(paperId), bgArgb)
        }

        // Highlighter first (multiply, under ink), then regular ink.
        // Objects (text/image/link) join here in M3.
        for (s in snapshot.strokes) {
            if (s.pen != PenType.HIGHLIGHTER) continue
            if (!s.bounds.intersects(tileBounds)) continue
            painter.configure(s.pen, s.argb, s.alphaPercent)
            path.rewind()
            painter.appendStrokePath(s, path)
            canvas.drawPath(path, painter.paint)
        }
        for (s in snapshot.strokes) {
            if (s.pen == PenType.HIGHLIGHTER) continue
            if (!s.bounds.intersects(tileBounds)) continue
            painter.configure(s.pen, s.argb, s.alphaPercent)
            path.rewind()
            painter.appendStrokePath(s, path)
            canvas.drawPath(path, painter.paint)
            painter.drawCaps(canvas, s)
        }
        canvas.restore()
        return bitmap
    }

    /** Paper/background changed (page settings): drop every tile. */
    fun invalidateAll() {
        cache.evictAll()
    }

    fun close() {
        closed = true
        renderThread.shutdownNow()
        cache.evictAll()
    }

    companion object {
        const val TILE_PX = 512
    }
}
