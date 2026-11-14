package com.nanote.core.storage.format

import com.nanote.core.model.CanvasMode
import com.nanote.core.model.CURRENT_FORMAT_VERSION
import com.nanote.core.model.DocumentContent
import com.nanote.core.model.DocumentModel
import com.nanote.core.model.PageTemplateKind
import com.nanote.core.model.PageDef
import com.nanote.core.model.Stroke
import com.nanote.core.model.StrokePoint
import com.nanote.core.model.TextAlignMode
import com.nanote.core.model.TextElement
import com.nanote.core.model.TextListStyle
import com.nanote.core.model.ToolType
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * §18 tests — data restoration: v3 round-trip with every new field, plus
 * backward compatibility loading a real v2 file (tilt/groupId/template/
 * canvasMode default in) and migration to v3.
 */
class DocumentFormatV3Test {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `v3 round-trip preserves all new fields`() = runBlocking {
        val content = DocumentContent(
            version = CURRENT_FORMAT_VERSION,
            pages = listOf(
                PageDef(
                    0, 595f, 842f, pdfPage = -1,
                    template = PageTemplateKind.MUSIC_STAFF,
                    templateColor = 0xFFAABBCC,
                    title = "First"
                )
            ),
            strokes = listOf(
                Stroke(
                    id = "s1", page = 0, tool = ToolType.FOUNTAIN,
                    color = 0xFF123456, width = 7.5f, opacity = 80,
                    points = listOf(
                        StrokePoint(1f, 2f, 0.5f, 0.25f, 10L),
                        StrokePoint(3f, 4f, 0.7f, 0.30f, 20L)
                    ),
                    timestamp = 123456L, groupId = "grp", pressureResponse = 0.85f
                )
            ),
            texts = listOf(
                TextElement(
                    id = "t1", page = 0, text = "مرحبا Hello",
                    x = 5f, y = 6f, size = 20f, color = 0xFF000000,
                    sticky = false, width = 300f,
                    bold = true, italic = true, underline = true, strikethrough = true,
                    align = TextAlignMode.END, listStyle = TextListStyle.BULLET,
                    bgColor = 0x33FFFF00, rotation = 15f, timestamp = 42L, groupId = "grp"
                )
            ),
            canvasMode = CanvasMode.INFINITE
        )
        val f = tmp.newFile("roundtrip.nanote")
        assertTrue(DocumentFormat.save(f, content))
        val loaded = DocumentFormat.load(f)
        assertTrue("load must succeed", loaded != null)
        val model = loaded!!.first
        val restored = model.snapshot()

        assertEquals(CURRENT_FORMAT_VERSION, restored.version)
        assertEquals(CanvasMode.INFINITE, restored.canvasMode)
        val page = restored.pages[0]
        assertEquals(PageTemplateKind.MUSIC_STAFF, page.template)
        assertEquals(0xFFAABBCC, page.templateColor)
        assertEquals("First", page.title)
        val s = restored.strokes[0]
        assertEquals(0.25f, s.points[0].tilt, 0f)
        assertEquals(10L, s.points[0].t)
        assertEquals(123456L, s.timestamp)
        assertEquals("grp", s.groupId)
        assertEquals(0.85f, s.pressureResponse, 0f)
        val t = restored.texts[0]
        assertTrue(t.bold && t.italic && t.underline && t.strikethrough)
        assertEquals(TextAlignMode.END, t.align)
        assertEquals(TextListStyle.BULLET, t.listStyle)
        assertEquals(15f, t.rotation, 0f)
    }

    @Test
    fun `v2 file loads with v3 defaults and migrates`() = runBlocking {
        val f = tmp.newFile("v2.nanote")
        f.writeText(
            """
            {"version":2,
             "pages":[{"index":0,"width":595.0,"height":842.0,"pdfPage":-1}],
             "strokes":[{"id":"s1","page":0,"tool":"PEN","color":4278190335,"width":4.0,"opacity":100,
                          "points":[{"x":1.0,"y":2.0,"p":0.5}]}],
             "shapes":[],"texts":[],"images":[],"highlights":[],"underlines":[]}
            """.trimIndent()
        )
        val loaded = DocumentFormat.load(f)
        assertTrue("v2 file must load", loaded != null)
        val model = loaded!!.first
        val migrated = DocumentMigrations.migrate(model.snapshot())
        assertEquals(3, migrated.version)
        // default canvas mode is PAGED, default template BLANK
        assertEquals(CanvasMode.PAGED, migrated.canvasMode)
        assertEquals(PageTemplateKind.BLANK, migrated.pages[0].template)
        // legacy stroke point has no tilt/timestamp → defaults
        assertEquals(0f, migrated.strokes[0].points[0].tilt, 0f)
        assertEquals(0.6f, migrated.strokes[0].pressureResponse, 0f)
    }

    @Test
    fun `save-restore via DocumentModel keeps revision-independent content`() = runBlocking {
        val model = DocumentModel(
            DocumentContent(
                pages = listOf(PageDef(0, template = PageTemplateKind.DOTTED)),
                canvasMode = CanvasMode.PAGED
            )
        )
        model.apply(model.addStrokeCmd(
            Stroke("n1", 0, ToolType.PENCIL, points = listOf(StrokePoint(1f, 1f, 0.4f, 0.2f, 5L)))
        ))
        val f = tmp.newFile("restore.nanote")
        assertTrue(DocumentFormat.save(f, model.snapshot()))

        val restored = DocumentFormat.load(f)!!.first
        assertEquals(1, restored.strokes.size)
        assertEquals(PageTemplateKind.DOTTED, restored.pages[0].template)
        assertEquals(0.2f, restored.strokes[0].points[0].tilt, 0f)
    }
}
