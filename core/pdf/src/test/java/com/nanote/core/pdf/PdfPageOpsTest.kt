package com.nanote.core.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
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
 * Page-level PDF operations executed through pdfbox-android and verified by
 * REOPENING the result (independent read path). Runs under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfPageOpsTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("nanote-pageops").toFile()
    }

    /** Builds a PDF whose page i draws the marker text "PAGE i". */
    private fun makePdf(name: String, count: Int): File {
        val file = File(dir, name)
        PDDocument().use { doc ->
            for (i in 0 until count) {
                val page = PDPage(PDRectangle(595f, 842f))
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font.HELVETICA_BOLD, 48f)
                    cs.newLineAtOffset(72f, 700f)
                    cs.showText("PAGE $i")
                    cs.endText()
                }
            }
            doc.save(file)
        }
        return file
    }

    private fun pageCount(file: File): Int = PDDocument.load(file).use { it.numberOfPages }

    @Test
    fun deletePages_removesAndKeepsOrder() = runBlocking {
        val src = makePdf("delete-src.pdf", 5)
        val out = File(dir, "delete-out.pdf")
        assertTrue(PdfPageOps.deletePages(src, out, setOf(1, 3)))
        assertEquals(3, pageCount(out))
        // Surviving pages keep their relative order (0, 2, 4).
        assertEquals(listOf("PAGE 0", "PAGE 2", "PAGE 4"), pageTexts(out))
        // The source file is untouched.
        assertEquals(5, pageCount(src))
    }

    @Test
    fun duplicatePage_insertsCopyRightAfter() = runBlocking {
        val src = makePdf("dup-src.pdf", 3)
        val out = File(dir, "dup-out.pdf")
        assertTrue(PdfPageOps.duplicatePage(src, out, 1))
        assertEquals(4, pageCount(out))
        assertEquals(listOf("PAGE 0", "PAGE 1", "PAGE 1", "PAGE 2"), pageTexts(out))
    }

    @Test
    fun rotatePage_changesRotationOnly() = runBlocking {
        val src = makePdf("rot-src.pdf", 2)
        val out = File(dir, "rot-out.pdf")
        assertTrue(PdfPageOps.rotatePage(src, out, 0, 90))
        PDDocument.load(out).use { doc ->
            assertEquals(2, doc.numberOfPages)
            assertEquals(90, doc.getPage(0).rotation)
            assertEquals(0, doc.getPage(1).rotation)
        }
    }

    @Test
    fun reorderPages_movesForward() = runBlocking {
        val src = makePdf("move-src.pdf", 5)
        val out = File(dir, "move-out.pdf")
        // Move page 1 to position 3: [0, 2, 3, 1, 4]
        val order = (0 until 5).filter { it != 1 }.toMutableList()
        order.add(3, 1)
        assertTrue(PdfPageOps.reorderPages(src, out, order))
        assertEquals(listOf("PAGE 0", "PAGE 2", "PAGE 3", "PAGE 1", "PAGE 4"), pageTexts(out))
    }

    @Test
    fun reorderPages_rejectsNonPermutation() = runBlocking {
        val src = makePdf("bad-src.pdf", 3)
        val out = File(dir, "bad-out.pdf")
        assertFalse(PdfPageOps.reorderPages(src, out, listOf(0, 0, 1)))
        assertFalse(out.exists())
    }

    @Test
    fun extractPages_createsSubsetInGivenOrder() = runBlocking {
        val src = makePdf("ext-src.pdf", 6)
        val out = File(dir, "ext-out.pdf")
        assertTrue(PdfPageOps.extractPages(src, out, listOf(4, 1, 2)))
        assertEquals(listOf("PAGE 4", "PAGE 1", "PAGE 2"), pageTexts(out))
    }

    @Test
    fun insertPagesFrom_splicesAtPosition() = runBlocking {
        val base = makePdf("ins-base.pdf", 3)
        val other = makePdf("ins-other.pdf", 2)
        val out = File(dir, "ins-out.pdf")
        // Insert the 2 pages of `other` at index 1 → 5 pages total.
        assertTrue(PdfPageOps.insertPagesFrom(base, other, out, listOf(0, 1), 1))
        assertEquals(5, pageCount(out))
        assertEquals(
            listOf("PAGE 0", "PAGE 0", "PAGE 1", "PAGE 1", "PAGE 2"),
            pageTexts(out)
        )
    }

    @Test
    fun mergeDocuments_concatenates() = runBlocking {
        val a = makePdf("merge-a.pdf", 2)
        val b = makePdf("merge-b.pdf", 3)
        val out = File(dir, "merge-out.pdf")
        assertTrue(PdfPageOps.mergeDocuments(listOf(a, b), out))
        assertEquals(5, pageCount(out))
    }

    @Test
    fun deletePages_rejectsOutOfRange() = runBlocking {
        val src = makePdf("range-src.pdf", 2)
        val out = File(dir, "range-out.pdf")
        assertFalse(PdfPageOps.deletePages(src, out, setOf(5)))
        assertFalse(out.exists())
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Text markers of every page in order (independent read-back). */
    private fun pageTexts(file: File): List<String> {
        return PDDocument.load(file).use { doc ->
            (0 until doc.numberOfPages).map { i ->
                val stripper = com.tom_roush.pdfbox.text.PDFTextStripper().apply {
                    startPage = i + 1
                    endPage = i + 1
                }
                stripper.getText(doc).trim()
            }
        }
    }
}
