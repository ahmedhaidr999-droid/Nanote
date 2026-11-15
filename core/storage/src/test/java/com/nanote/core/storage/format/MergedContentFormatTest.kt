package com.nanote.core.storage.format

import com.nanote.core.model.CanvasMode
import com.nanote.core.model.CURRENT_FORMAT_VERSION
import com.nanote.core.model.DocumentContent
import com.nanote.core.model.PageDef
import com.nanote.core.model.PageTemplateKind
import com.nanote.core.model.ShapeElement
import com.nanote.core.model.ShapeKind
import com.nanote.core.model.StrikeThroughAnnotation
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Part 4 integration tests — the merged Part 2 + Part 3 document format:
 * a single .nanote file carrying BOTH handwriting-engine content (infinite
 * canvas mode, the four new shape kinds with polygon vertices) AND
 * PDF-annotation content (strikethrough text marks) must round-trip
 * losslessly, and old files written by either part alone must still load.
 */
class MergedContentFormatTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Every feature family in ONE document — save → load → field-by-field. */
    @Test
    fun `part2 and part3 content in one file round-trips`() = runBlocking {
        val content = DocumentContent(
            version = CURRENT_FORMAT_VERSION,
            pages = listOf(
                PageDef(0, template = PageTemplateKind.CORNELL),
                PageDef(1, template = PageTemplateKind.GRID)
            ),
            shapes = listOf(
                ShapeElement(
                    id = "sh-da", page = 0, kind = ShapeKind.DOUBLE_ARROW,
                    color = 0xFF111111, width = 2.5f,
                    x1 = 10f, y1 = 20f, x2 = 110f, y2 = 120f
                ),
                ShapeElement(
                    id = "sh-rr", page = 0, kind = ShapeKind.ROUNDED_RECT,
                    filled = true, fillColor = 0x332563EB,
                    x1 = 10f, y1 = 10f, x2 = 90f, y2 = 50f
                ),
                ShapeElement(
                    id = "sh-el", page = 1, kind = ShapeKind.ELLIPSE,
                    rotation = 30f,
                    x1 = 0f, y1 = 0f, x2 = 80f, y2 = 40f
                ),
                ShapeElement(
                    id = "sh-pg", page = 1, kind = ShapeKind.POLYGON,
                    // hexagon-ish normalized vertex pairs
                    vertices = listOf(0.5f, 0f, 1f, 0.33f, 1f, 0.66f, 0.5f, 1f, 0f, 0.66f, 0f, 0.33f),
                    x1 = 5f, y1 = 5f, x2 = 105f, y2 = 95f
                )
            ),
            strikeThroughs = listOf(
                StrikeThroughAnnotation(
                    id = "st-1", page = 0, color = 0xFFDC2626, opacity = 90,
                    rects = listOf(listOf(0.1f, 0.2f, 0.8f, 0.25f))
                ),
                StrikeThroughAnnotation(
                    id = "st-2", page = 1,
                    rects = listOf(listOf(0.1f, 0.3f, 0.5f, 0.35f), listOf(0.1f, 0.4f, 0.7f, 0.45f))
                )
            ),
            canvasMode = CanvasMode.INFINITE
        )

        val f = tmp.newFile("merged.nanote")
        assertTrue("save must succeed", DocumentFormat.save(f, content))

        val loaded = DocumentFormat.load(f)
        assertTrue("load must succeed", loaded != null)
        val restored = loaded!!.first.snapshot()

        // Part 2 side survived
        assertEquals(CanvasMode.INFINITE, restored.canvasMode)
        assertEquals(4, restored.shapes.size)
        val da = restored.shapes.first { it.id == "sh-da" }
        assertEquals(ShapeKind.DOUBLE_ARROW, da.kind)
        val rr = restored.shapes.first { it.id == "sh-rr" }
        assertEquals(ShapeKind.ROUNDED_RECT, rr.kind)
        assertTrue(rr.filled)
        assertEquals(0x332563EB, rr.fillColor)
        val el = restored.shapes.first { it.id == "sh-el" }
        assertEquals(ShapeKind.ELLIPSE, el.kind)
        assertEquals(30f, el.rotation, 0f)
        val pg = restored.shapes.first { it.id == "sh-pg" }
        assertEquals(ShapeKind.POLYGON, pg.kind)
        assertEquals(12, pg.vertices.size)
        assertEquals(0.66f, pg.vertices[5], 0f)

        // Part 3 side survived
        assertEquals(2, restored.strikeThroughs.size)
        val st1 = restored.strikeThroughs.first { it.id == "st-1" }
        assertEquals(0xFFDC2626, st1.color)
        assertEquals(90, st1.opacity)
        assertEquals(4, st1.rects[0].size)
        val st2 = restored.strikeThroughs.first { it.id == "st-2" }
        assertEquals(2, st2.rects.size)

        // pages intact
        assertEquals(2, restored.pages.size)
        assertEquals(PageTemplateKind.CORNELL, restored.pages[0].template)
        assertEquals(PageTemplateKind.GRID, restored.pages[1].template)
    }

    /** A v2-era file (neither canvasMode nor strikeThroughs) still loads. */
    @Test
    fun `legacy file without merged fields loads with defaults`() = runBlocking {
        val f = tmp.newFile("legacy.nanote")
        f.writeText(
            """
            {"version":2,
             "pages":[{"index":0,"width":595.0,"height":842.0,"pdfPage":-1}],
             "strokes":[],"shapes":[],"texts":[],"images":[],
             "highlights":[],"underlines":[]}
            """.trimIndent()
        )
        val loaded = DocumentFormat.load(f)
        assertTrue("legacy file must load", loaded != null)
        val restored = loaded!!.first.snapshot()
        // merged defaults: PAGED canvas, no strikethroughs
        assertEquals(CanvasMode.PAGED, restored.canvasMode)
        assertTrue(restored.strikeThroughs.isEmpty())
    }

    /** Fields written by only ONE of the two parts load in isolation. */
    @Test
    fun `part3-only file with strikethroughs loads`() = runBlocking {
        val f = tmp.newFile("p3only.nanote")
        f.writeText(
            """
            {"version":3,
             "pages":[{"index":0,"width":595.0,"height":842.0,"pdfPage":-1}],
             "strokes":[],"shapes":[],"texts":[],"images":[],
             "highlights":[],"underlines":[],
             "strikeThroughs":[{"id":"s","page":0,"color":4291559142,"opacity":100,
                                 "rects":[[0.1,0.1,0.9,0.2]]}]}
            """.trimIndent()
        )
        val loaded = DocumentFormat.load(f)
        assertTrue("part3-only file must load", loaded != null)
        val restored = loaded!!.first.snapshot()
        assertEquals(1, restored.strikeThroughs.size)
        assertEquals(4, restored.strikeThroughs[0].rects[0].size)
        assertEquals(CanvasMode.PAGED, restored.canvasMode)
    }
}
