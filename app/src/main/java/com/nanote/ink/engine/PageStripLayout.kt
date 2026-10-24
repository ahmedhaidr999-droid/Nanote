package com.nanote.ink.engine

/**
 * Minimal geometry the strip index lookup needs — implemented by both the
 * pure layout rows and the view's live slots, so no per-frame conversion.
 */
interface StripGeom {
    val index: Int
    val topPt: Float
    val heightPt: Float
}

/**
 * Pure geometry of one page inside the continuous vertical strip (v1.2,
 * Notewise-style browsing): pages are stacked top-to-bottom with a constant
 * gap and horizontally centered on the widest page. The view owns the strip
 * transform and derives each page's local matrix from it:
 *
 *   screenX = stripOffX + (leftPt + pageX) * scale
 *   screenY = stripOffY + (topPt  + pageY) * scale
 */
data class StripPageLayout(
    override val index: Int,
    /** Strip-space y of the page's top edge. */
    override val topPt: Float,
    /** Strip-space x of the page's left edge (centers on the widest page). */
    val leftPt: Float,
    val widthPt: Float,
    override val heightPt: Float
) : StripGeom

object PageStripLayout {

    /** Vertical gray gap between consecutive pages, in page points. */
    const val GAP_PT = 28f

    /** Stacks [pages] (index-ordered) into the strip. */
    fun layout(pages: List<Pair<Float, Float>>): List<StripPageLayout> {
        var y = 0f
        val out = ArrayList<StripPageLayout>(pages.size)
        val maxW = pages.maxOfOrNull { it.first } ?: 0f
        pages.forEachIndexed { i, (w, h) ->
            out.add(StripPageLayout(i, y, (maxW - w) / 2f, w, h))
            y += h + GAP_PT
        }
        return out
    }

    fun totalHeightPt(l: List<StripPageLayout>): Float =
        if (l.isEmpty()) 0f else l.last().topPt + l.last().heightPt

    fun stripWidthPt(l: List<StripPageLayout>): Float =
        l.maxOfOrNull { it.leftPt + it.widthPt } ?: 0f

    /**
     * Page index at strip y [stripY]. Points inside a gap (or beyond the
     * strip ends) resolve to the NEAREST page so the pen never lands in
     * limbo. Returns -1 only for an empty strip.
     */
    fun indexAt(l: List<out StripGeom>, stripY: Float): Int {
        if (l.isEmpty()) return -1
        var best = 0
        var bestDist = Float.MAX_VALUE
        for (p in l) {
            val bottom = p.topPt + p.heightPt
            val d = when {
                stripY < p.topPt -> p.topPt - stripY
                stripY > bottom -> stripY - bottom
                else -> 0f
            }
            if (d < bestDist) {
                bestDist = d
                best = p.index
                if (d == 0f) break
            }
        }
        return best
    }

    /**
     * Vertical pan clamp for the whole strip: keeps at least 25% of the
     * smaller of (strip height, viewport) on screen — same rule the classic
     * single-page clamp used, applied to the strip extent.
     */
    fun clampOffsetY(offY: Float, scale: Float, totalH: Float, vh: Float): Float {
        if (totalH <= 0f) return offY
        val ph = totalH * scale
        val minVis = minOf(vh, ph) * 0.25f
        return offY.coerceIn(minVis - ph, vh - minVis)
    }

    /**
     * Horizontal pan clamp against ONE page (the active one, 25% rule),
     * expressed in strip-offset coordinates: the page's left edge sits at
     * `offX + leftPt*scale` and is `pageW*scale` wide.
     */
    fun clampOffsetX(offX: Float, scale: Float, leftPt: Float, pageW: Float, vw: Float): Float {
        val pw = pageW * scale
        val minVis = minOf(vw, pw) * 0.25f
        val maxOffX = vw - minVis - leftPt * scale
        val minOffX = minVis - leftPt * scale - pw
        return offX.coerceIn(minOffX, maxOffX)
    }
}
