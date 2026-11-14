package com.nanote.core.pdf

import android.graphics.Bitmap
import com.nanote.core.common.AppLog
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** What the canvas currently has for a page. */
data class PageRender(
    val page: Int,
    val bucket: Int,
    val bitmap: Bitmap?,
    val failed: Boolean
)

/**
 * Orchestrates page rendering:
 *  - First visible page renders IMMEDIATELY at high priority (fast content on open).
 *  - Prefetch ±1 neighbors.
 *  - Renders only when the DISCRETE bucket changes — zoom gestures transform the
 *    cached bitmap via the view matrix and never trigger renders (no zoom lag).
 *  - Progressive: when zooming far in, an intermediate bucket renders first.
 *  - Stale requests (page moved away / bucket superseded) never publish.
 *  - Nothing here ever blocks the main thread.
 */
class PageRenderManager(
    private val docId: String,
    private val handle: PdfDocumentHandle,
    private val cache: PageBitmapCache,
    private val density: Float,
    private val scope: CoroutineScope,
    private val prefetchEnabled: () -> Boolean = { true }
) {

    companion object {
        private const val TAG = "PageRenderManager"
    }

    private val jobs = ConcurrentHashMap<Int, Job>()
    private val desiredBucket = ConcurrentHashMap<Int, Int>()
    private val renderingBucket = ConcurrentHashMap<Int, Int>()
    private val _renders = MutableStateFlow<Map<Int, PageRender>>(emptyMap())
    val renders: StateFlow<Map<Int, PageRender>> = _renders

    @Volatile
    private var closed = false

    /** Pages the canvas currently wants, with the live view scale. */
    fun requestVisible(pages: List<Pair<Int, Float>>) {
        if (closed || pages.isEmpty()) return
        for ((p, s) in pages) requestPage(p, s)
        if (prefetchEnabled()) {
            val minP = pages.minOf { it.first }
            val maxP = pages.maxOf { it.first }
            val scale = pages.first().second
            for (p in (minP - 1)..(maxP + 1)) {
                if (p < 0 || p >= handle.pageCount) continue
                if (pages.any { it.first == p }) continue
                requestPage(p, scale)
            }
        }
    }

    fun requestPage(page: Int, viewScale: Float) {
        if (closed) return
        if (page < 0 || page >= handle.pageCount) return
        val bucket = RenderBuckets.bucketFor(viewScale, density)
        desiredBucket[page] = bucket

        val cached = cache.getTracked(cache.key(docId, page, bucket))
        if (cached != null && !cached.isRecycled) {
            jobs[page]?.cancel()
            jobs.remove(page)
            publish(PageRender(page, bucket, cached, false))
            return
        }

        val existing = jobs[page]
        if (existing != null && existing.isActive && renderingBucket[page] == bucket) return
        existing?.cancel()

        // Progressive path: jumping many buckets up → quick intermediate first.
        val current = _renders.value[page]
        val intermediate = current?.bitmap != null && bucket - current.bucket >= 3
        if (intermediate) {
            launchRender(page, bucket - 2)
        } else {
            launchRender(page, bucket)
        }
    }

    private fun launchRender(page: Int, bucket: Int) {
        renderingBucket[page] = bucket
        val job = scope.launch {
            val started = System.currentTimeMillis()
            val size = renderSize(page, bucket)
            if (size == null) {
                publish(PageRender(page, bucket, null, true))
                return@launch
            }
            val bmp = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.RGB_565)
            val ok = try {
                handle.renderPage(page, bmp)
            } catch (t: Throwable) {
                AppLog.e(TAG, "render job p$page b$bucket crashed", t)
                false
            }
            if (!isActive) return@launch // superseded — result still cached below
            if (ok) {
                cache.putTracked(cache.key(docId, page, bucket), bmp)
                val want = desiredBucket[page] ?: bucket
                if (bucket == want) {
                    publish(PageRender(page, bucket, bmp, false))
                    AppLog.perf("render", "page $page bucket $bucket ${size[0]}x${size[1]} in ${System.currentTimeMillis() - started}ms")
                } else if (bucket < want) {
                    publish(PageRender(page, bucket, bmp, false))
                    launchRender(page, want)
                }
                // bucket > want → outdated zoom-out result: cache only, don't publish
            } else {
                publish(PageRender(page, bucket, null, true))
            }
        }
        jobs[page] = job
    }

    private fun publish(pr: PageRender) {
        _renders.update { it + (pr.page to pr) }
    }

    private fun renderSize(page: Int, bucket: Int): IntArray? {
        val ps = handle.pageSize(page)
        var s = RenderBuckets.scaleFor(bucket)
        var w = (ps.x * s).roundToInt().coerceAtLeast(32)
        var h = (ps.y * s).roundToInt().coerceAtLeast(32)
        val maxEdge = maxOf(w, h)
        if (maxEdge > RenderBuckets.MAX_EDGE) {
            val f = RenderBuckets.MAX_EDGE.toFloat() / maxEdge
            w = (w * f).roundToInt(); h = (h * f).roundToInt()
        }
        return intArrayOf(w, h)
    }

    /** Retry a failed page (user taps the retry overlay). */
    fun retryPage(page: Int, viewScale: Float) {
        desiredBucket.remove(page)
        requestPage(page, viewScale)
    }

    /** Cancel jobs for pages far outside [keep] (fast scrolling guard). */
    fun cancelOutside(keep: IntRange) {
        for ((p, job) in jobs) {
            if (p !in keep) {
                job.cancel()
                jobs.remove(p)
            }
        }
    }

    fun reset() {
        for ((_, j) in jobs) j.cancel()
        jobs.clear()
        desiredBucket.clear()
        _renders.value = emptyMap()
    }

    fun close() {
        closed = true
        for ((_, j) in jobs) j.cancel()
        jobs.clear()
        handle.closeAsync()
        cache.evictDocument(docId)
    }
}
