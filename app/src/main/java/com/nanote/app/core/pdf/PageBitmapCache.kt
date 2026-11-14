package com.nanote.app.core.pdf

import android.graphics.Bitmap
import android.util.LruCache
import com.nanote.app.util.AppLog

/**
 * LRU bitmap cache for rendered PDF pages.
 *
 * Key = "docId:page:bucket" where the bucket is a DISCRETE resolution step
 * (see [RenderBuckets]) — so small zoom changes reuse the cached bitmap and
 * only significant zoom changes trigger a re-render. Adaptive memory budget.
 */
class PageBitmapCache(maxBytes: Long = computeBudget()) {

    companion object {
        private const val TAG = "PageBitmapCache"

        /** Adaptive: 1/8 of app heap, clamped to 24..64 MB (spec: ~64MB LRU). */
        fun computeBudget(): Long {
            val max = Runtime.getRuntime().maxMemory()
            return (max / 8L).coerceIn(24L * 1024 * 1024, 64L * 1024 * 1024)
        }
    }

    private val lru = object : LruCache<String, Bitmap>(maxBytes.toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val maxBytesV = maxBytes

    fun key(docId: String, page: Int, bucket: Int): String = "$docId:$page:$bucket"

    fun get(k: String): Bitmap? = lru.get(k)

    fun put(k: String, bmp: Bitmap): Boolean {
        if (bmp.byteCount > maxBytesV) {
            AppLog.w(TAG, "bitmap too large for cache (${bmp.byteCount / 1048576}MB)")
            return false
        }
        lru.put(k, bmp)
        return true
    }

    /** Drop every entry belonging to a document (cache-crossing guard). */
    fun evictDocument(docId: String) {
        val prefix = "$docId:"
        val keys = ArrayList<String>()
        // LruCache snapshot is not exposed; iterate via trimToSize trick is unsafe,
        // instead track keys ourselves.
        synchronized(trackedKeys) {
            for (k in trackedKeys) if (k.startsWith(prefix)) keys.add(k)
            for (k in keys) { trackedKeys.remove(k); lru.remove(k) }
        }
    }

    fun clear() {
        synchronized(trackedKeys) { trackedKeys.clear() }
        lru.evictAll()
    }

    private val trackedKeys = HashSet<String>()

    fun putTracked(k: String, bmp: Bitmap) {
        if (put(k, bmp)) {
            synchronized(trackedKeys) { trackedKeys.add(k) }
        }
    }

    fun hitRatio(): Float = if (requests == 0L) 0f else hits.toFloat() / requests

    private var requests = 0L
    private var hits = 0L

    fun getTracked(k: String): Bitmap? {
        requests++
        val b = lru.get(k)
        if (b != null) hits++
        return b
    }
}

/**
 * Discrete render-resolution buckets.
 * renderScale (bitmap px per world pt) = 2^(bucket/2), bucket ∈ [-2 .. 6].
 * Adjacent zoom levels usually land in the same bucket → the page bitmap is
 * simply transformed by the view matrix during/after zooming instead of
 * re-rendering (kills zoom lag + cache thrash).
 */
object RenderBuckets {
    const val MIN_BUCKET = -2
    const val MAX_BUCKET = 6

    /** viewScale = world->screen px; density multiplies to physical px. */
    fun bucketFor(viewScale: Float, density: Float): Int {
        val s = (viewScale * density).coerceIn(0.4f, 12f)
        val b = kotlin.math.log2(s.toDouble()).let { (it * 2.0).toInt() }
        return b.coerceIn(MIN_BUCKET, MAX_BUCKET)
    }

    fun scaleFor(bucket: Int): Float = Math.pow(2.0, bucket / 2.0).toFloat()

    /** Max bitmap edge to keep single-page renders bounded (OOM guard). */
    const val MAX_EDGE = 3600
}
