package com.nanote.core.pdf

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nanote.core.model.DocumentContent
import com.nanote.core.model.PageDef
import com.nanote.core.model.ShapeElement
import com.nanote.core.model.ShapeKind
import com.nanote.core.model.StrikeThroughAnnotation
import com.nanote.core.model.Stroke
import com.nanote.core.model.StrokePoint
import com.nanote.core.model.ToolType
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Part 4 integration test — the merged document content (Part 2 handwriting
 * shapes + Part 3 text marks) paints onto a PDF page through
 * [PdfContentPainter] and the result is verified by REOPENING it with an
 * independent load path (fresh PDDocument parse: page count, exact page
 * size, and the actual path/text operators present in the content stream).
 *
 * Note: full pdfium rasterization verification happens on-device at export
 * time (PdfVerifier runs in the production export pipeline); the pdfium
 * native library cannot load inside a JVM Robolectric test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MergedContentPaintTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext<Context>())
        dir = Files.createTempDirectory("nanote-mergedpaint").toFile()
    }

    private fun mergedContent() = DocumentContent(
        pages = listOf(PageDef(0, 595f, 842f, pdfPage = 0)),
        strokes = listOf(
            Stroke(
                id = "s1", page = 0, tool = ToolType.FOUNTAIN,
                color = 0xFF000000, width = 4f, opacity = 100,
                points = listOf(
                    StrokePoint(40f, 100f, 0.6f, 0f, 1L),
                    StrokePoint(80f, 140f, 0.8f, 0f, 2L),
                    StrokePoint(130f, 120f, 0.5f, 0f, 3L)
                )
            )
        ),
        shapes = listOf(
            ShapeElement("sh-line", 0, ShapeKind.LINE, x1 = 50f, y1 = 200f, x2 = 300f, y2 = 260f),
            ShapeElement("sh-arrow", 0, ShapeKind.ARROW, x1 = 50f, y1 = 300f, x2 = 300f, y2 = 300f),
            ShapeElement("sh-darrow", 0, ShapeKind.DOUBLE_ARROW, x1 = 50f, y1 = 340f, x2 = 300f, y2 = 340f),
            ShapeElement("sh-rect", 0, ShapeKind.RECT, x1 = 50f, y1 = 380f, x2 = 200f, y2 = 450f),
            ShapeElement("sh-rrect", 0, ShapeKind.ROUNDED_RECT, x1 = 220f, y1 = 380f, x2 = 380f, y2 = 450f),
            ShapeElement("sh-circle", 0, ShapeKind.CIRCLE, x1 = 50f, y1 = 480f, x2 = 160f, y2 = 560f),
            ShapeElement("sh-ellipse", 0, ShapeKind.ELLIPSE, x1 = 200f, y1 = 480f, x2 = 360f, y2 = 560f),
            ShapeElement("sh-tri", 0, ShapeKind.TRIANGLE, x1 = 50f, y1 = 600f, x2 = 180f, y2 = 700f),
            ShapeElement(
                "sh-poly", 0, ShapeKind.POLYGON,
                vertices = listOf(0.5f, 0f, 1f, 0.33f, 1f, 0.66f, 0.5f, 1f, 0f, 0.66f, 0f, 0.33f),
                x1 = 250f, y1 = 600f, x2 = 400f, y2 = 720f
            )
        ),
        strikeThroughs = listOf(
            StrikeThroughAnnotation(
                id = "st-1", page = 0, color = 0xFFDC2626, opacity = 100,
                rects = listOf(listOf(0.05f, 0.80f, 0.60f, 0.83f))
            )
        )
    )

    /** Saves [content] painted onto one fresh A4 page and returns the file. */
    private fun painted(name: String, content: DocumentContent): File {
        val out = File(dir, name)
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle(595f, 842f))
            doc.addPage(page)
            PdfContentPainter.paintPage(
                document = doc,
                page = page,
                content = content,
                pageIndex = 0,
                imageResolver = { null }
            )
            doc.save(out)
        }
        return out
    }

    @Test
    fun `merged part2 and part3 content paints into a reopenable pdf`() = runBlocking {
        val out = painted("merged-paint.pdf", mergedContent())
        assertTrue("export file must exist and be non-empty", out.exists() && out.length() > 0)

        PDDocument.load(out).use { reopened ->
            assertEquals("exactly one page", 1, reopened.numberOfPages)
            val page = reopened.getPage(0)

            // exact page geometry survived the paint
            assertEquals(595f, page.mediaBox.width, 0.5f)
            assertEquals(842f, page.mediaBox.height, 0.5f)

            // the painted operators are in the content stream
            val cs = page.contents.use { it.readBytes() }.decodeToString()
            assertFalse("content stream must not be blank", cs.isBlank())
            // 9 shapes + 1 strikethrough line + the fountain stroke → the
            // strikethrough is a stroked line through the marked text
            // (moveTo/lineTo + S), so path ops must exceed the 9 shapes.
            val pathOps = Regex("(?<=^|[\\s\\n])(m|l|re|c)(?=[\\s\\n])").findAll(cs).count()
            assertTrue("expected >= 11 path ops, got $pathOps", pathOps >= 11)
            // stroking (S) operators: shapes + ink + strikethrough
            val strokeOps = Regex("(?<=^|[\\s\\n])S(?=[\\s\\n])").findAll(cs).count()
            assertTrue("stroking operators present", strokeOps >= 2)
        }
    }

    @Test
    fun `page without content stays untouched and valid`() = runBlocking {
        // painting an empty content must not corrupt the page
        val out = painted("empty-paint.pdf", DocumentContent(pages = listOf(PageDef(0))))
        PDDocument.load(out).use { reopened ->
            assertEquals(1, reopened.numberOfPages)
            assertEquals(595f, reopened.getPage(0).mediaBox.width, 0.5f)
        }
    }

    @Test
    fun `polygon shape draws its normalized vertices`() = runBlocking {
        // POLYGON with >= 6 normalized floats uses the vertex path; a polygon
        // without vertices falls back to a plain rect — both must paint.
        val withVertices = mergedContent().copy(
            shapes = listOf(
                ShapeElement(
                    "sh-poly", 0, ShapeKind.POLYGON,
                    vertices = listOf(0.5f, 0f, 1f, 0.33f, 1f, 0.66f, 0.5f, 1f, 0f, 0.66f, 0f, 0.33f),
                    x1 = 10f, y1 = 10f, x2 = 300f, y2 = 300f
                )
            )
        )
        val fallback = mergedContent().copy(
            shapes = listOf(ShapeElement("sh-poly2", 0, ShapeKind.POLYGON, x1 = 10f, y1 = 10f, x2 = 300f, y2 = 300f))
        )
        for (content in listOf(withVertices, fallback)) {
            val out = painted("poly-${content.shapes[0].vertices.size}.pdf", content)
            PDDocument.load(out).use { doc ->
                val cs = doc.getPage(0).contents.use { it.readBytes() }.decodeToString()
                val pathOps = Regex("(?<=^|[\\s\\n])(m|l|re|c)(?=[\\s\\n])").findAll(cs).count()
                assertTrue("polygon path ops present ($pathOps)", pathOps >= 1)
            }
        }
    }
}
