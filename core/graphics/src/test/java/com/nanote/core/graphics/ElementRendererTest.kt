package com.nanote.core.graphics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nanote.core.model.EditorFont
import com.nanote.core.model.Stroke
import com.nanote.core.model.StrokePoint
import com.nanote.core.model.TextAlignMode
import com.nanote.core.model.TextElement
import com.nanote.core.model.TextListStyle
import com.nanote.core.model.ToolType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §18 tests — Arabic & mixed-direction text layout (§12), list prefixes and
 * per-tool stroke rendering smoke (Robolectric-backed android.graphics).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ElementRendererTest {

    private val renderer = ElementRenderer(ApplicationProvider.getApplicationContext<Context>())

    // ------------------------------------------------------------ text (§12)

    @Test
    fun `arabic text lays out with positive height`() {
        val t = TextElement(
            id = "t1", page = 0, text = "مرحبا بكم في تطبيق نوتة", x = 10f, y = 10f
        )
        val h = renderer.measureTextHeight(t)
        assertTrue("Arabic layout must produce height > 0, got $h", h > 0)
    }

    @Test
    fun `mixed arabic-english text lays out`() {
        val t = TextElement(
            id = "t2", page = 0,
            text = "الاسم: Ahmed العمر 30 — ملاحظة note here",
            x = 0f, y = 0f, width = 400f
        )
        assertTrue(renderer.measureTextHeight(t) > 0)
        // multi-line mixed content wraps to more than one line height
        val longer = t.copy(text = t.text + "\n" + t.text)
        assertTrue(renderer.measureTextHeight(longer) > renderer.measureTextHeight(t))
    }

    @Test
    fun `rtl alignment end does not crash and still measures`() {
        val t = TextElement(
            id = "t3", page = 0, text = "نص عربي طويل يتجاوز العرض المحدد لهذا العنصر",
            x = 0f, y = 0f, width = 120f, align = TextAlignMode.END
        )
        assertTrue(renderer.measureTextHeight(t) > 0)
    }

    @Test
    fun `bullet and numbered lists inject prefixes`() {
        val plain = TextElement(id = "x", page = 0, text = "one\ntwo", x = 0f, y = 0f)
        assertEquals("one\ntwo", renderer.textForLayout(plain))
        val bulleted = plain.copy(listStyle = TextListStyle.BULLET)
        val bulletText = renderer.textForLayout(bulleted)
        assertTrue(bulletText.contains("•"))
        val numbered = plain.copy(listStyle = TextListStyle.NUMBERED)
        val numberedText = renderer.textForLayout(numbered)
        assertTrue(numberedText.contains("1."))
        assertTrue(numberedText.contains("2."))
        // list prefixes make the block taller or equal, never smaller
        assertTrue(renderer.measureTextHeight(bulleted) >= renderer.measureTextHeight(plain))
    }

    @Test
    fun `font families measure differently or equal but never crash`() {
        val base = TextElement(id = "f", page = 0, text = "Sample 123", x = 0f, y = 0f)
        EditorFont.entries.forEach { font ->
            val h = renderer.measureTextHeight(base.copy(font = font))
            assertTrue("font $font must measure", h > 0)
        }
    }

    // ------------------------------------------------------- strokes (§2)

    @Test
    fun `each ink tool renders without crashing`() {
        val pts = (0..24).map { i ->
            StrokePoint(i * 3f, if (i % 2 == 0) 10f else 14f, 0.6f, 0.2f, i * 1_000_000L)
        }
        for (tool in listOf(ToolType.PEN, ToolType.FOUNTAIN, ToolType.BRUSH, ToolType.PENCIL, ToolType.HIGHLIGHTER)) {
            val s = Stroke(id = "s-$tool", page = 0, tool = tool, width = 6f, points = pts)
            // smoke: cache population + width computation run for every engine
            renderer.drawStroke(android.graphics.Canvas(), s, pressureEnabled = true)
            renderer.drawStroke(android.graphics.Canvas(), s, pressureEnabled = false)
        }
    }

    @Test
    fun `single tap stroke renders for every tool`() {
        for (tool in listOf(ToolType.PEN, ToolType.FOUNTAIN, ToolType.BRUSH, ToolType.PENCIL, ToolType.HIGHLIGHTER)) {
            val s = Stroke(id = "tap-$tool", page = 0, tool = tool, width = 6f, points = listOf(StrokePoint(5f, 5f, 0.8f)))
            renderer.drawStroke(android.graphics.Canvas(), s, true)
        }
    }

    // ----------------------------------------------------- backgrounds (§10)

    @Test
    fun `all twelve templates paint without crashing`() {
        val c = android.graphics.Canvas()
        for (kind in com.nanote.core.model.PageTemplateKind.entries) {
            PageBackgrounds.paint(c, kind, 0f, 0f, 595f, 842f, 0xFFB6C2D1.toInt())
        }
        PageBackgrounds.paintInfiniteDots(c, 0f, 0f, 500f, 500f, dark = false)
        PageBackgrounds.paintInfiniteDots(c, -200f, -200f, 300f, 300f, dark = true)
    }

    // ---------------------------------------------------- spatial index (§16)

    @Test
    fun `spatial index finds strokes in query rect`() {
        val idx = SpatialIndex()
        val far = Stroke(
            id = "far", page = 0, tool = ToolType.PEN,
            points = listOf(StrokePoint(5000f, 5000f), StrokePoint(5100f, 5000f))
        )
        val near = Stroke(
            id = "near", page = 0, tool = ToolType.PEN,
            points = listOf(StrokePoint(10f, 10f), StrokePoint(60f, 10f))
        )
        idx.rebuild(listOf(far, near))
        val hits = idx.query(0f, 0f, 100f, 100f)
        assertEquals(1, hits.size)
        assertEquals("near", hits[0].id)

        // remove works
        idx.remove(near)
        assertTrue(idx.query(0f, 0f, 100f, 100f).isEmpty())
        // far still reachable in its own region
        assertEquals(1, idx.query(4900f, 4900f, 5200f, 5200f).size)
    }
}
