package com.nanote.app.core.canvas

import com.nanote.app.core.model.PageDef
import com.nanote.app.core.model.ViewportTransform
import kotlin.math.max

/** Immutable vertical-continuous page layout in world units (PDF points). */
class PageLayout(pages: List<PageDef>) {

    companion object {
        /** Gap between pages in world units. */
        const val PAGE_GAP = 14f
    }

    val count: Int = pages.size
    private val tops = FloatArray(pages.size)
    private val widths = FloatArray(pages.size)
    private val heights = FloatArray(pages.size)
    private val lefts = FloatArray(pages.size)

    /** Widest page — all pages are centered on this width (stable, no left/right jumps). */
    val maxPageWidth: Float

    val contentHeight: Float

    init {
        var y = 0f
        var maxW = 1f
        for (p in pages) {
            if (p.width > maxW) maxW = p.width
        }
        maxPageWidth = maxW
        for (i in pages.indices) {
            tops[i] = y
            widths[i] = pages[i].width
            heights[i] = pages[i].height
            lefts[i] = (maxW - pages[i].width) / 2f
            y += pages[i].height + PAGE_GAP
        }
        contentHeight = if (pages.isEmpty()) 1000f else y - PAGE_GAP
    }

    fun pageTop(i: Int): Float = tops.getOrElse(i) { 0f }
    fun pageBottom(i: Int): Float = pageTop(i) + pageHeight(i)
    fun pageHeight(i: Int): Float = heights.getOrElse(i) { 842f }
    fun pageWidth(i: Int): Float = widths.getOrElse(i) { 595f }
    /** Left edge of page [i] in world coords (pages centered by widest). */
    fun pageLeft(i: Int): Float = lefts.getOrElse(i) { 0f }
    fun pageRight(i: Int): Float = pageLeft(i) + pageWidth(i)

    /** Page index containing world [y], or -1 when in a gap/empty area. */
    fun pageAtY(y: Float): Int {
        var lo = 0
        var hi = count - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val top = tops[mid]
            val bot = top + heights[mid]
            when {
                y < top -> hi = mid - 1
                y > bot -> lo = mid + 1
                else -> return mid
            }
        }
        return -1
    }

    fun nearestPage(y: Float): Int {
        val p = pageAtY(y)
        if (p >= 0) return p
        for (i in 0 until count) {
            if (y < tops[i]) return i
        }
        return (count - 1).coerceAtLeast(0)
    }
}

/**
 * Zoom + pan + layout state of one canvas pane.
 * Vertical continuous scrolling — pages NEVER shift left/right when flipping
 * (the reported bug); horizontal panning only happens from user zoom/pan.
 */
class CanvasState(
    pages: List<PageDef>
) {
    val transform = ViewportTransform()
    var layout: PageLayout = PageLayout(pages)
        private set

    var viewportWidth: Int = 1080
    var viewportHeight: Int = 1920

    /** Extra scroll allowance beyond content (world units). */
    private val overscroll = 600f

    fun setPages(pages: List<PageDef>) {
        layout = PageLayout(pages)
    }

    // ---------- viewport math ----------

    fun worldLeft(): Float = transform.screenToWorldX(0f)
    fun worldTop(): Float = transform.screenToWorldY(0f)
    fun worldRight(): Float = transform.screenToWorldX(viewportWidth.toFloat())
    fun worldBottom(): Float = transform.screenToWorldY(viewportHeight.toFloat())

    fun visibleWorldRect(): FloatArray =
        floatArrayOf(worldLeft(), worldTop(), worldRight(), worldBottom())

    fun visiblePages(): List<Int> {
        val out = ArrayList<Int>(4)
        val top = worldTop()
        val bottom = worldBottom()
        for (i in 0 until layout.count) {
            val pt = layout.pageTop(i)
            val pb = layout.pageBottom(i)
            if (pb >= top && pt <= bottom) out.add(i)
            if (pt > bottom) break
        }
        return out
    }

    /** Page under the viewport center — used for the page indicator. */
    fun currentPage(): Int = layout.nearestPage((worldTop() + worldBottom()) / 2f)

    // ---------- clamped navigation ----------

    fun clampPan() {
        val s = transform.scale
        val contentW = layout.maxPageWidth
        val contentH = max(layout.contentHeight, 100f)

        // Horizontal: keep some part of the content horizontally visible.
        val screenLeftWorld = transform.screenToWorldX(0f)
        val screenRightWorld = transform.screenToWorldX(viewportWidth.toFloat())
        if (screenLeftWorld > contentW + overscroll) {
            transform.set(s, (contentW + overscroll) * s - viewportWidth, transform.panY)
        } else if (screenRightWorld < -overscroll) {
            transform.set(s, -overscroll * s, transform.panY)
        }

        // Vertical: allow bounded overscroll past the first/last page.
        val yTop = transform.screenToWorldY(0f)
        val yBottom = transform.screenToWorldY(viewportHeight.toFloat())
        if (yTop > contentH + overscroll) {
            transform.set(transform.scale, transform.panX, viewportHeight + overscroll * transform.scale - contentH * transform.scale)
        } else if (yBottom < -overscroll) {
            transform.set(transform.scale, transform.panX, -overscroll * transform.scale)
        }
    }

    fun panBy(dxScreen: Float, dyScreen: Float) {
        transform.panBy(dxScreen, dyScreen)
        clampPan()
    }

    fun zoomAt(focusX: Float, focusY: Float, scale: Float) {
        transform.zoomAt(focusX, focusY, scale)
        clampPan()
    }

    // ---------- fit / zoom presets ----------

    fun fitWidthToPage(pageIndex: Int, animate: Boolean = false): Float {
        if (layout.count == 0) return transform.scale
        val i = pageIndex.coerceIn(0, layout.count - 1)
        val target = (viewportWidth.toFloat() / layout.pageWidth(i)).coerceIn(transform.minScale, transform.maxScale)
        if (animate) animateZoomTo(target, focusX = viewportWidth / 2f, focusY = viewportHeight / 2f)
        else applyZoomCentered(target, viewportWidth / 2f, viewportHeight / 2f)
        return target
    }

    fun fitPage(pageIndex: Int) {
        if (layout.count == 0) return
        val i = pageIndex.coerceIn(0, layout.count - 1)
        val sw = viewportWidth / layout.pageWidth(i)
        val sh = viewportHeight / layout.pageHeight(i)
        applyZoomCentered(minOf(sw, sh), viewportWidth / 2f, viewportHeight / 2f)
    }

    fun actualSize() {
        applyZoomCentered(1.5f, viewportWidth / 2f, viewportHeight / 2f)
    }

    fun applyZoomCentered(target: Float, focusX: Float, focusY: Float) {
        transform.zoomAt(focusX, focusY, target)
        clampPan()
    }

    fun animateZoomTo(target: Float, focusX: Float, focusY: Float) {
        zoomAnimator?.cancel()
        val start = transform.scale
        val anim = android.animation.ValueAnimator.ofFloat(0f, 1f)
        anim.duration = 220
        anim.interpolator = android.view.animation.DecelerateInterpolator()
        anim.addUpdateListener { a ->
            val t = a.animatedValue as Float
            transform.zoomAt(focusX, focusY, start + (target - start) * t)
            clampPan()
            onTransformChanged?.invoke()
        }
        anim.start()
        zoomAnimator = anim
    }

    private var zoomAnimator: android.animation.ValueAnimator? = null
    private var flingRunnable: Runnable? = null

    var onTransformChanged: (() -> Unit)? = null
    var onScrollChanged: (() -> Unit)? = null

    /** World Y of the viewport top. */
    fun scrollY(): Float = worldTop()

    fun scrollToPage(pageIndex: Int) {
        if (layout.count == 0) return
        val i = pageIndex.coerceIn(0, layout.count - 1)
        val targetPanY = -layout.pageTop(i) * transform.scale
        transform.set(transform.scale, transform.panX, targetPanY)
        clampPan()
        onScrollChanged?.invoke()
    }

    fun scrollToY(worldY: Float) {
        val targetPanY = -worldY * transform.scale
        transform.set(transform.scale, transform.panX, targetPanY)
        clampPan()
        onScrollChanged?.invoke()
    }
}
