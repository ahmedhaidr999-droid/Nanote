package com.nanote.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §18 tests — page management commands (§10), grouping, multi-type paste
 * composites and undo/redo integration through the command pipeline.
 */
class DocumentModelPart2Test {

    private fun model(): DocumentModel {
        val content = DocumentContent(
            version = CURRENT_FORMAT_VERSION,
            pages = listOf(
                PageDef(0, 595f, 842f, template = PageTemplateKind.RULED),
                PageDef(1, 595f, 842f, template = PageTemplateKind.GRID)
            )
        )
        return DocumentModel(content)
    }

    private fun stroke(id: String, page: Int, x: Float) = Stroke(
        id = id, page = page, tool = ToolType.PEN, color = 0xFF000000,
        width = 3f, opacity = 100,
        points = listOf(StrokePoint(x, 10f, 0.5f, 0f, 1L), StrokePoint(x + 20f, 10f, 0.5f, 0f, 2L)),
        timestamp = 5L, groupId = null, pressureResponse = 0.6f
    )

    // ------------------------------------------------------------ pages

    @Test
    fun `delete page removes its elements and shifts later ones down`() {
        val m = model()
        m.strokes.add(stroke("a", 0, 10f))
        m.strokes.add(stroke("b", 1, 10f))
        m.strokes.add(stroke("c", 2, 10f).copy()) // page 2 doesn't exist; clamp below
        m.strokes.removeAll { it.page > 1 }

        m.apply(m.deletePageCmd(0))

        assertEquals(1, m.pages.size)
        assertEquals(1, m.strokes.size)
        assertEquals("b", m.strokes[0].id)
        // element that lived on page 1 now lives on page 0
        assertEquals(0, m.strokes[0].page)

        // undo restores everything
        m.undo()
        assertEquals(2, m.pages.size)
        assertEquals(2, m.strokes.size)
        assertEquals(1, m.strokes.first { it.id == "b" }.page)
    }

    @Test
    fun `duplicate page copies elements with fresh ids`() {
        val m = model()
        m.strokes.add(stroke("a", 0, 10f))
        m.texts.add(TextElement("t1", 0, "hello", 10f, 10f))

        m.apply(m.duplicatePageCmd(0))

        assertEquals(3, m.pages.size)
        assertEquals(2, m.strokes.size)
        val copy = m.strokes.first { it.id != "a" }
        assertEquals(1, copy.page)
        assertEquals(2, m.texts.size)
        assertTrue(m.texts.any { it.id != "t1" && it.text == "hello" })

        m.undo()
        assertEquals(2, m.pages.size)
        assertEquals(1, m.strokes.size)
        assertEquals(1, m.texts.size)
    }

    @Test
    fun `reorder page remaps element pages and survives undo`() {
        val m = model()
        m.strokes.add(stroke("p0", 0, 10f))
        m.strokes.add(stroke("p1", 1, 10f))

        m.apply(m.reorderPageCmd(1, 0)) // page 1 moves to front

        // element "p1" now on page 0, "p0" on page 1
        assertEquals(0, m.strokes.first { it.id == "p1" }.page)
        assertEquals(1, m.strokes.first { it.id == "p0" }.page)

        m.undo()
        assertEquals(0, m.strokes.first { it.id == "p0" }.page)
        assertEquals(1, m.strokes.first { it.id == "p1" }.page)
    }

    @Test
    fun `rename and template changes are undoable`() {
        val m = model()
        m.apply(m.renamePageCmd(0, "Notes"))
        assertEquals("Notes", m.pages[0].title)

        m.apply(m.setPageDefCmd(0, m.pages[0].copy(template = PageTemplateKind.MUSIC_STAFF)))
        assertEquals(PageTemplateKind.MUSIC_STAFF, m.pages[0].template)

        m.undo()
        assertEquals(PageTemplateKind.RULED, m.pages[0].template)
        m.undo()
        assertEquals("", m.pages[0].title)
    }

    @Test
    fun `changing template never touches handwriting`() {
        val m = model()
        val s = stroke("a", 0, 10f)
        m.strokes.add(s)
        m.apply(m.setPageDefCmd(0, m.pages[0].copy(template = PageTemplateKind.DOTTED)))
        assertEquals(s, m.strokes[0])
    }

    @Test
    fun `add page with explicit def inserts at index`() {
        val m = model()
        m.strokes.add(stroke("old1", 1, 10f))
        m.apply(m.addPageWithCmd(PageDef(0, 595f, 842f, template = PageTemplateKind.CORNELL), atIndex = 0))
        assertEquals(3, m.pages.size)
        assertEquals(PageTemplateKind.CORNELL, m.pages[0].template)
        // old page 1 content shifted to page 2
        assertEquals(2, m.strokes[0].page)
        m.undo()
        assertEquals(2, m.pages.size)
        assertEquals(1, m.strokes[0].page)
    }

    // ------------------------------------------------------------ grouping

    @Test
    fun `group assigns shared id and ungroup restores`() {
        val m = model()
        val s1 = stroke("a", 0, 10f)
        val s2 = stroke("b", 0, 50f)
        m.strokes.addAll(listOf(s1, s2))

        m.apply(m.groupCmd(setOf("a", "b"), emptySet(), emptySet(), emptySet()))
        val gid = m.strokes[0].groupId
        assertNotNull(gid)
        assertEquals(gid, m.strokes[1].groupId)

        m.undo()
        assertNull(m.strokes[0].groupId)
        assertNull(m.strokes[1].groupId)

        // redo re-groups with the same group id
        m.redo()
        assertEquals(gid, m.strokes[0].groupId)

        m.apply(m.ungroupCmd(gid!!))
        assertNull(m.strokes[0].groupId)
        m.undo()
        assertEquals(gid, m.strokes[0].groupId)
    }

    // ---------------------------------------------------------- composites

    @Test
    fun `addElements and removeElements composites undo as one step`() {
        val m = model()
        val batch = listOf(stroke("x", 0, 10f), stroke("y", 0, 40f))
        val texts = listOf(TextElement("tx", 0, "hi", 5f, 5f))

        m.apply(m.addElementsCmd(batch, texts, emptyList(), emptyList()))
        assertEquals(2, m.strokes.size)
        assertEquals(1, m.texts.size)

        m.undo()
        assertEquals(0, m.strokes.size)
        assertEquals(0, m.texts.size)
        m.redo()
        assertEquals(2, m.strokes.size)

        m.apply(m.removeElementsCmd(m.strokes.toList(), m.texts.toList(), emptyList(), emptyList()))
        assertEquals(0, m.strokes.size)
        m.undo()
        assertEquals(2, m.strokes.size)
        assertEquals(1, m.texts.size)
    }

    // ---------------------------------------------------------- canvas mode

    @Test
    fun `canvas mode switch is undoable`() {
        val m = model()
        assertEquals(CanvasMode.PAGED, m.canvasMode)
        m.apply(m.setCanvasModeCmd(CanvasMode.INFINITE))
        assertEquals(CanvasMode.INFINITE, m.canvasMode)
        m.undo()
        assertEquals(CanvasMode.PAGED, m.canvasMode)
    }

    // ---------------------------------------------------------- transforms

    @Test
    fun `transform commands update by id and revert`() {
        val m = model()
        val s = stroke("a", 0, 10f) // points run x=10 → x=30
        m.strokes.add(s)
        val moved = StrokeOps.translateStroke(s, 100f, 50f)

        m.apply(m.transformStrokesCmd(listOf(s to moved)))
        assertEquals(130f, m.strokes[0].points.last().x, 0.01f)

        m.undo()
        assertEquals(30f, m.strokes[0].points.last().x, 0.01f)
    }

    // ---------------------------------------------------------- v3 fields

    @Test
    fun `stroke carries tilt timestamps group and pressure response`() {
        val s = Stroke(
            id = "s", page = 0, tool = ToolType.FOUNTAIN,
            points = listOf(StrokePoint(1f, 2f, 0.5f, 0.6f, 123L)),
            timestamp = 99L, groupId = "g1", pressureResponse = 0.9f
        )
        assertEquals(0.6f, s.points[0].tilt, 0f)
        assertEquals(123L, s.points[0].t)
        assertEquals(99L, s.timestamp)
        assertEquals("g1", s.groupId)
        assertEquals(0.9f, s.pressureResponse, 0f)
    }

    @Test
    fun `v2-style construction still works with defaults`() {
        val s = Stroke("s", 0, ToolType.PEN, 0xFF000000, 3f, 100, emptyList())
        assertNull(s.groupId)
        assertEquals(0L, s.timestamp)
        assertEquals(0.6f, s.pressureResponse, 0f)
        assertFalse(s.points.any { it.tilt != 0f })
    }
}
