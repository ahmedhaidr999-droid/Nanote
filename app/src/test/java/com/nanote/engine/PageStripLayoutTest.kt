package com.nanote.engine

import com.google.common.truth.Truth.assertThat
import com.nanote.ink.engine.PageStripLayout
import org.junit.Test

class PageStripLayoutTest {

    private val pages = listOf(
        595f to 842f,   // A4 portrait
        842f to 595f,   // A4 landscape (widest)
        595f to 842f
    )

    private val layout = PageStripLayout.layout(pages)

    @Test
    fun `tops stack heights with the constant gap`() {
        val gap = PageStripLayout.GAP_PT
        assertThat(layout[0].topPt).isEqualTo(0f)
        assertThat(layout[1].topPt).isEqualTo(842f + gap)
        assertThat(layout[2].topPt).isEqualTo(842f + gap + 595f + gap)
        assertThat(PageStripLayout.totalHeightPt(layout))
            .isEqualTo(842f + gap + 595f + gap + 842f)
    }

    @Test
    fun `narrow pages are centered on the widest page`() {
        // page 0 (595 wide) centered within 842: left = (842-595)/2
        assertThat(layout[0].leftPt).isEqualTo((842f - 595f) / 2f)
        // widest page starts at 0
        assertThat(layout[1].leftPt).isEqualTo(0f)
        assertThat(PageStripLayout.stripWidthPt(layout)).isEqualTo(842f)
    }

    @Test
    fun `indexAt finds the page under a strip y`() {
        val gap = PageStripLayout.GAP_PT
        assertThat(PageStripLayout.indexAt(layout, 10f)).isEqualTo(0)
        assertThat(PageStripLayout.indexAt(layout, 842f + gap + 10f)).isEqualTo(1)
        assertThat(PageStripLayout.indexAt(layout, 842f + gap + 595f + gap + 800f)).isEqualTo(2)
    }

    @Test
    fun `indexAt inside a gap picks the nearest page`() {
        val gap = PageStripLayout.GAP_PT
        val justBelowPage0 = 842f + gap * 0.25f
        assertThat(PageStripLayout.indexAt(layout, justBelowPage0)).isEqualTo(0)
        val justAbovePage1 = 842f + gap * 0.75f
        assertThat(PageStripLayout.indexAt(layout, justAbovePage1)).isEqualTo(1)
    }

    @Test
    fun `indexAt beyond the ends clamps to first and last`() {
        assertThat(PageStripLayout.indexAt(layout, -500f)).isEqualTo(0)
        assertThat(PageStripLayout.indexAt(layout, 99999f)).isEqualTo(2)
        assertThat(PageStripLayout.indexAt(emptyList(), 10f)).isEqualTo(-1)
    }

    @Test
    fun `clampOffsetY keeps a quarter of the strip visible`() {
        val totalH = 3000f
        val vh = 1000f
        val scale = 1f
        // strip taller than viewport: offsets keep content in the window
        assertThat(PageStripLayout.clampOffsetY(500f, scale, totalH, vh)).isEqualTo(500f)
        // scrolled too far up (strip bottom above the 25% line): clamped
        assertThat(PageStripLayout.clampOffsetY(-10000f, scale, totalH, vh))
            .isEqualTo(250f - 3000f)
        // scrolled too far down (strip top below 75% vh): clamped
        assertThat(PageStripLayout.clampOffsetY(10000f, scale, totalH, vh))
            .isEqualTo(vh - 250f)
    }

    @Test
    fun `clampOffsetX follows the active page 25 percent rule`() {
        // active page: left=123.5, width=595, viewport 1000, scale 1
        val left = 123.5f
        val w = 595f
        val vw = 1000f
        // fully inside -> free within [minVis-left-w, vw-minVis-left]
        val minVis = 595f * 0.25f
        assertThat(PageStripLayout.clampOffsetX(0f, 1f, left, w, vw))
            .isEqualTo(0f)
        assertThat(PageStripLayout.clampOffsetX(-5000f, 1f, left, w, vw))
            .isEqualTo(minVis - left - w)
        assertThat(PageStripLayout.clampOffsetX(5000f, 1f, left, w, vw))
            .isEqualTo(vw - minVis - left)
    }

    @Test
    fun `layout of a single page has no gap effects`() {
        val one = PageStripLayout.layout(listOf(595f to 842f))
        assertThat(one).hasSize(1)
        assertThat(one[0].topPt).isEqualTo(0f)
        assertThat(one[0].leftPt).isEqualTo(0f)
        assertThat(PageStripLayout.totalHeightPt(one)).isEqualTo(842f)
    }
}
