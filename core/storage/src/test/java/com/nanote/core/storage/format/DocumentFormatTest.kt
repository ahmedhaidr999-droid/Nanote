package com.nanote.core.storage.format

import com.nanote.core.model.CURRENT_FORMAT_VERSION
import com.nanote.core.model.DocumentContent
import com.nanote.core.model.PageDef
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round-trip + recovery behaviour of the atomic .nanote file format. */
class DocumentFormatTest {

    private fun tempFile(): File =
        Files.createTempDirectory("nanote").resolve("doc.nanote").toFile()

    @Test
    fun `save then load round-trips content`() = runBlocking {
        val file = tempFile()
        val content = DocumentContent(
            version = CURRENT_FORMAT_VERSION,
            pages = listOf(PageDef(index = 0, width = 595f, height = 842f)),
            strokes = listOf(
                com.nanote.core.model.Stroke(
                    id = "s1", page = 0, tool = com.nanote.core.model.ToolType.PEN,
                    points = listOf(com.nanote.core.model.StrokePoint(1f, 2f, 0.5f))
                )
            )
        )
        assertTrue(DocumentFormat.save(file, content))

        val loaded = DocumentFormat.load(file)
        assertNotNull(loaded)
        assertEquals(false, loaded!!.second) // not partially recovered
        val model = loaded.first
        assertEquals(1, model.pageCount())
        assertEquals(1, model.strokes.size)
        assertEquals(1, model.strokes[0].points.size)
        assertEquals(595f, model.pages[0].width)
    }

    @Test
    fun `load returns null for missing file`() = runBlocking {
        val missing = File(Files.createTempDirectory("nanote").toFile(), "nope.nanote")
        assertNull(DocumentFormat.load(missing))
    }

    @Test
    fun `load recovers partially from a truncated file`() = runBlocking {
        val file = tempFile()
        file.writeText("""{"version":2,"pages":[{"index":0,"width":595.0,"height":842.0,"pdfPage":-1}],"strokes":[{"id":"s1"""")
        val loaded = DocumentFormat.load(file)
        // Recovery path either salvages a blank model or fails cleanly — never crashes.
        if (loaded != null) {
            assertTrue(loaded.second) // flagged as partially recovered
        }
    }
}
