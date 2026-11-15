package com.nanote.core.pdf

import com.nanote.core.common.AppLog
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extracts the full plain text of every page of a PDF (pdfbox-android),
 * used to build the local search index at import time. Pure text — no
 * recognition of handwriting or scanned content is claimed or performed.
 */
object PdfTextIndexer {

    private const val TAG = "PdfTextIndexer"

    /** Per-page extracted text, index-aligned (empty string when no text). */
    data class IndexedPdf(
        val pageTexts: List<String>,
        val infoTitle: String?,
        val infoAuthor: String?,
        val infoCreator: String?,
        val infoProducer: String?
    )

    /**
     * Extracts text page by page. Runs entirely on Dispatchers.IO.
     * Returns null when the file cannot be parsed (callers treat the
     * document as "no text search").
     */
    suspend fun index(file: File): IndexedPdf? = withContext(Dispatchers.IO) {
        try {
            PDDocument.load(file).use { doc ->
                val texts = ArrayList<String>(doc.numberOfPages)
                for (page in 0 until doc.numberOfPages) {
                    val stripper = PDFTextStripper().apply {
                        startPage = page + 1
                        endPage = page + 1
                        sortByPosition = true
                    }
                    texts.add(stripper.getText(doc).replace(Regex("\\s+"), " ").trim())
                }
                val info = doc.documentInformation
                IndexedPdf(
                    pageTexts = texts,
                    infoTitle = info.title?.takeIf { it.isNotBlank() },
                    infoAuthor = info.author?.takeIf { it.isNotBlank() },
                    infoCreator = info.creator?.takeIf { it.isNotBlank() },
                    infoProducer = info.producer?.takeIf { it.isNotBlank() }
                )
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "index failed for ${file.name}: ${t.message}")
            null
        }
    }
}
