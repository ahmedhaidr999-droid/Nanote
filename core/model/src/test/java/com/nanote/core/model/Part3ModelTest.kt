package com.nanote.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Part 3 model tests: strikethrough annotations survive the versioned
 * .nanote JSON round-trip and old payloads without the field still decode
 * (forward compatibility of the format).
 */
class Part3ModelTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `strikeThroughs round-trip through DocumentContent`() {
        val content = DocumentContent(
            version = CURRENT_FORMAT_VERSION,
            pages = listOf(PageDef(index = 0)),
            underlines = listOf(
                UnderlineAnnotation(id = "u1", page = 0, color = 0xFFEF4444, opacity = 100, rects = listOf(listOf(0.1f, 0.2f, 0.3f, 0.25f)))
            ),
            strikeThroughs = listOf(
                StrikeThroughAnnotation(id = "s1", page = 0, color = 0xFF000000, opacity = 80, rects = listOf(listOf(0.4f, 0.5f, 0.6f, 0.55f)))
            )
        )
        val decoded = json.decodeFromString(DocumentContent.serializer(), json.encodeToString(DocumentContent.serializer(), content))
        assertEquals(1, decoded.strikeThroughs.size)
        assertEquals("s1", decoded.strikeThroughs.first().id)
        assertEquals(80, decoded.strikeThroughs.first().opacity)
        assertEquals(0.4f, decoded.strikeThroughs.first().rects.first()[0])
        assertEquals(1, decoded.underlines.size)
    }

    @Test
    fun `old payload without strikeThroughs decodes to empty list`() {
        val legacy = """{"version":2,"pages":[{"index":0,"width":595.0,"height":842.0,"pdfPage":-1}],"strokes":[]}"""
        val decoded = json.decodeFromString(DocumentContent.serializer(), legacy)
        assertTrue(decoded.strikeThroughs.isEmpty())
        assertEquals(1, decoded.pages.size)
    }

    @Test
    fun `DocumentModel exposes strikethrough commands and snapshot`() {
        val model = DocumentModel(
            DocumentContent(
                version = CURRENT_FORMAT_VERSION,
                pages = listOf(PageDef(index = 0))
            )
        )
        val ann = StrikeThroughAnnotation(id = "s9", page = 0)
        model.apply(model.addStrikeThroughCmd(ann))
        assertEquals(1, model.strikeThroughsOn(0).size)
        assertTrue(model.canUndo)

        model.undo()
        assertEquals(0, model.strikeThroughsOn(0).size)
        assertTrue(model.canRedo)

        model.redo()
        assertEquals(1, model.strikeThroughsOn(0).size)
        assertEquals(1, model.snapshot().strikeThroughs.size)
    }

    @Test
    fun `removeAnnotationsCmd reverts all three mark kinds`() {
        val model = DocumentModel(DocumentContent(pages = listOf(PageDef(index = 0))))
        val h = HighlightAnnotation(id = "h", page = 0)
        val u = UnderlineAnnotation(id = "u", page = 0)
        val s = StrikeThroughAnnotation(id = "s", page = 0)
        model.apply(model.addHighlightCmd(h))
        model.apply(model.addUnderlineCmd(u))
        model.apply(model.addStrikeThroughCmd(s))
        model.apply(model.removeAnnotationsCmd(listOf(h), listOf(u), listOf(s)))
        assertEquals(0, model.highlights.size)
        assertEquals(0, model.underlines.size)
        assertEquals(0, model.strikeThroughs.size)
        model.undo()
        assertEquals(3, model.highlights.size + model.underlines.size + model.strikeThroughs.size)
    }
}
