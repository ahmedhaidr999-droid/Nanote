package com.nanote.core.pdf

import com.nanote.core.common.AppLog
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.multipdf.PageExtractor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Page-level operations on PDF files, built on pdfbox-android.
 *
 * Contract:
 *  - Every operation READS [source] and WRITES the result to [target];
 *    the source file is never modified (imported originals stay pristine).
 *  - Callers are responsible for atomic replacement of the working file
 *    (temp file + rename) — see the repository layer.
 *  - All operations run on Dispatchers.IO.
 *
 * Real pdfbox-android APIs used: PDDocument.removePage/addPage/getPage,
 * PDPageTree.insertBefore, PDPage.setRotation, PageExtractor,
 * PDFMergerUtility.appendDocument.
 */
object PdfPageOps {

    private const val TAG = "PdfPageOps"

    /** Rotates one page by [degreesDelta] (usually 90). */
    suspend fun rotatePage(source: File, target: File, pageIndex: Int, degreesDelta: Int): Boolean =
        withContext(Dispatchers.IO) {
            runDoc(source, target) { doc ->
                if (pageIndex !in 0 until doc.numberOfPages) return@runDoc false
                val page = doc.getPage(pageIndex)
                page.rotation = ((page.rotation + degreesDelta) % 360 + 360) % 360
                true
            }
        }

    /** Removes the given pages (0-based source indices). */
    suspend fun deletePages(source: File, target: File, pageIndexes: Set<Int>): Boolean =
        withContext(Dispatchers.IO) {
            runDoc(source, target) { doc ->
                if (pageIndexes.isEmpty()) return@runDoc false
                if (pageIndexes.any { it !in 0 until doc.numberOfPages }) return@runDoc false
                // Remove from the highest index down so earlier indexes stay valid.
                pageIndexes.sortedDescending().forEach { idx ->
                    doc.removePage(idx)
                }
                doc.numberOfPages > 0
            }
        }

    /**
     * Duplicates [pageIndex] and inserts the copy at [insertAt]
     * (default: right after the original).
     */
    suspend fun duplicatePage(
        source: File,
        target: File,
        pageIndex: Int,
        insertAt: Int = pageIndex + 1
    ): Boolean = withContext(Dispatchers.IO) {
        runDoc(source, target) { doc ->
            if (pageIndex !in 0 until doc.numberOfPages) return@runDoc false
            val original = doc.getPage(pageIndex)
            // Shallow import shares the page resources — valid PDF for
            // same-document duplication.
            val copy = doc.importPage(original)
            val at = insertAt.coerceIn(0, doc.numberOfPages)
            if (at == doc.numberOfPages - 1) {
                // importPage already appended at the end; nothing to move.
                return@runDoc true
            }
            movePageBefore(doc, copy, at)
            true
        }
    }

    /**
     * Reorders all pages: [newOrder] lists the ORIGINAL page indices in
     * their new sequence (must be a permutation of 0..n-1).
     */
    suspend fun reorderPages(source: File, target: File, newOrder: List<Int>): Boolean =
        withContext(Dispatchers.IO) {
            runDoc(source, target) { doc ->
                val n = doc.numberOfPages
                if (newOrder.size != n || newOrder.toSet() != (0 until n).toSet()) {
                    AppLog.w(TAG, "reorder: not a permutation (n=$n, got ${newOrder.size})")
                    return@runDoc false
                }
                val pages = doc.documentCatalog.pages
                val moving = newOrder.map { doc.getPage(it) }
                // Detach every page, then re-append in the new order. The
                // PDPage objects stay valid (their COS dictionaries are only
                // unlinked from the page tree, not destroyed).
                val all = (0 until n).map { doc.getPage(it) }
                all.forEach { pages.remove(it) }
                moving.forEach { pages.add(it) }
                true
            }
        }

    /**
     * Extracts the given pages (in the given order) into a NEW pdf written
     * to [target]. Also implements "split" (extract two complementary sets)
     * and "export selected pages".
     */
    suspend fun extractPages(source: File, target: File, pageIndexes: List<Int>): Boolean =
        withContext(Dispatchers.IO) {
            if (pageIndexes.isEmpty()) return@withContext false
            var out: PDDocument? = null
            var src: PDDocument? = null
            try {
                src = PDDocument.load(source)
                if (pageIndexes.any { it !in 0 until src.numberOfPages }) {
                    return@withContext false
                }
                out = PDDocument()
                val merger = PDFMergerUtility()
                // Group consecutive runs so PageExtractor works on ranges.
                var runStart = 0
                while (runStart < pageIndexes.size) {
                    var runEnd = runStart
                    while (runEnd + 1 < pageIndexes.size &&
                        pageIndexes[runEnd + 1] == pageIndexes[runEnd] + 1
                    ) {
                        runEnd++
                    }
                    val first = pageIndexes[runStart]
                    val last = pageIndexes[runEnd]
                    val extractor = PageExtractor(src, first + 1, last + 1)
                    val run = extractor.extract()
                    merger.appendDocument(out, run)
                    run.close()
                    runStart = runEnd + 1
                }
                out.save(target)
                true
            } catch (t: Throwable) {
                AppLog.e(TAG, "extractPages failed", t)
                false
            } finally {
                try { out?.close() } catch (_: Throwable) {}
                try { src?.close() } catch (_: Throwable) {}
            }
        }

    /**
     * Inserts the selected pages of [other] into the source at [insertAt]
     * (0-based; equals source page count → append at the end).
     */
    suspend fun insertPagesFrom(
        source: File,
        other: File,
        target: File,
        otherPageIndexes: List<Int>,
        insertAt: Int
    ): Boolean = withContext(Dispatchers.IO) {
        if (otherPageIndexes.isEmpty()) return@withContext false
        var out: PDDocument? = null
        var src: PDDocument? = null
        var extracted: PDDocument? = null
        try {
            src = PDDocument.load(source)
            out = PDDocument.load(source)
            extracted = extractToTemp(other, otherPageIndexes) ?: return@withContext false
            val merger = PDFMergerUtility()
            merger.appendDocument(out, extracted)

            val n = out.numberOfPages
            val at = insertAt.coerceIn(0, n)
            if (at < n) {
                // Pages were appended at the end; move them before the anchor
                // page. Every insertion goes before the SAME anchor so the
                // group keeps its order: [.. anchor, ..] -> [.. p1 p2 .. anchor, ..]
                val anchor = out.getPage(at)
                val addedCount = otherPageIndexes.size
                val moving = ((n - addedCount) until n).map { out.getPage(it) }
                moving.forEach { p -> out.documentCatalog.pages.remove(p) }
                for (p in moving) {
                    out.documentCatalog.pages.insertBefore(p, anchor)
                }
            }
            out.save(target)
            true
        } catch (t: Throwable) {
            AppLog.e(TAG, "insertPagesFrom failed", t)
            false
        } finally {
            try { out?.close() } catch (_: Throwable) {}
            try { src?.close() } catch (_: Throwable) {}
            try { extracted?.close() } catch (_: Throwable) {}
        }
    }

    /** Full merge: concatenates [files] in order into [target]. */
    suspend fun mergeDocuments(files: List<File>, target: File): Boolean =
        withContext(Dispatchers.IO) {
            if (files.isEmpty()) return@withContext false
            var out: PDDocument? = null
            try {
                out = PDDocument()
                val merger = PDFMergerUtility()
                for (file in files) {
                    PDDocument.load(file).use { part ->
                        merger.appendDocument(out, part)
                    }
                }
                out.save(target)
                true
            } catch (t: Throwable) {
                AppLog.e(TAG, "mergeDocuments failed", t)
                false
            } finally {
                try { out?.close() } catch (_: Throwable) {}
            }
        }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Extracts selected pages of [file] into an in-memory PDDocument. */
    private fun extractToTemp(file: File, pageIndexes: List<Int>): PDDocument? {
        val src = PDDocument.load(file)
        try {
            if (pageIndexes.any { it !in 0 until src.numberOfPages }) return null
            val out = PDDocument()
            val merger = PDFMergerUtility()
            var runStart = 0
            while (runStart < pageIndexes.size) {
                var runEnd = runStart
                while (runEnd + 1 < pageIndexes.size &&
                    pageIndexes[runEnd + 1] == pageIndexes[runEnd] + 1
                ) {
                    runEnd++
                }
                val extractor = PageExtractor(src, pageIndexes[runStart] + 1, pageIndexes[runEnd] + 1)
                val run = extractor.extract()
                merger.appendDocument(out, run)
                run.close()
                runStart = runEnd + 1
            }
            return out
        } finally {
            try { src.close() } catch (_: Throwable) {}
        }
    }

    /** Moves [page] so it becomes the page at index [at] in the tree. */
    private fun movePageBefore(doc: PDDocument, page: com.tom_roush.pdfbox.pdmodel.PDPage, at: Int) {
        val tree = doc.documentCatalog.pages
        tree.remove(page)
        if (at >= doc.numberOfPages) {
            tree.add(page)
        } else {
            tree.insertBefore(page, doc.getPage(at))
        }
    }

    /** Loads [source], applies [block], saves to [target]; closes everything. */
    private inline fun runDoc(source: File, target: File, block: (PDDocument) -> Boolean): Boolean {
        var doc: PDDocument? = null
        return try {
            doc = PDDocument.load(source)
            val ok = block(doc)
            if (ok) doc.save(target)
            ok
        } catch (t: Throwable) {
            AppLog.e(TAG, "operation failed on ${source.name}", t)
            false
        } finally {
            try { doc?.close() } catch (_: Throwable) {}
        }
    }
}
