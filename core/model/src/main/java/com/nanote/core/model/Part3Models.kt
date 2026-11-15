package com.nanote.core.model

import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// Part 3 — PDF engine, local storage, search & export models
// ---------------------------------------------------------------------------

// ----------------------------- Tags ---------------------------------------

/** A user tag attached to documents (Room `tags` + `note_tags` tables). */
data class Tag(
    val name: String,
    val color: Long = 0xFF2563EB,
    val usageCount: Int = 0
)

// ----------------------------- Search --------------------------------------

/** One grouped result set of a local search. */
data class SearchResults(
    val query: String,
    val documents: List<DocumentMeta> = emptyList(),
    val folders: List<Folder> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val pdfTextHits: List<PdfTextHit> = emptyList()
) {
    val isEmpty: Boolean
        get() = documents.isEmpty() && folders.isEmpty() && tags.isEmpty() && pdfTextHits.isEmpty()
}

/**
 * A selectable-text match inside an imported PDF. [pageIndex] refers to the
 * CURRENT page ordering of the document (the .nanote page list), and
 * [pdfPage] is the underlying source page when available.
 */
data class PdfTextHit(
    val documentId: String,
    val documentTitle: String,
    val kind: NoteKind,
    val coverColor: CoverColor,
    val pageIndex: Int,
    val pdfPage: Int,
    /** Match snippet around the hit (max ~90 chars). */
    val snippet: String
)

/** A recent search entry shown as a suggestion chip. */
data class RecentSearch(
    val query: String,
    val searchedAt: Long
)

// ----------------------------- Export --------------------------------------

/** User-selectable export formats. */
enum class ExportFormat { PDF, PNG, JPEG, NANOTE }

/** How annotations are written into an exported PDF. */
enum class PdfExportMode {
    /** Copy the untouched working PDF — no annotations at all. */
    ORIGINAL,

    /** Draw every annotation into the page content (ink, marks, images…). */
    FLATTENED,

    /**
     * Ink & images are flattened into content; text-mark annotations
     * (highlight / underline / strikethrough) are written as REAL PDF
     * markup annotations so the original text stays selectable where the
     * reader supports it.
     */
    WITH_MARKUP_ANNOTATIONS
}

/** Options for one export run. */
data class ExportOptions(
    val format: ExportFormat = ExportFormat.PDF,
    val pdfMode: PdfExportMode = PdfExportMode.FLATTENED,
    /** null → all pages; otherwise the selected document page indices. */
    val pages: Set<Int>? = null
)

/** Quality preset controlling pdfium render scale for raster exports. */
enum class RenderQuality(val scaleFactor: Float) {
    LOW(1.0f),
    MEDIUM(1.5f),
    HIGH(2.0f)
}

// ----------------------------- Bookmarks & reading position ----------------

/** A user bookmark pointing at a page of an imported PDF. */
data class PdfBookmark(
    val id: String,
    val documentId: String,
    val pageIndex: Int,
    val pdfPage: Int,
    val title: String,
    val createdAt: Long
)

/** Where the user stopped reading (page + view state), persisted per document. */
data class ReadingPosition(
    val documentId: String,
    val pageIndex: Int,
    /** Zoom scale at save time (world→screen factor), 0/none when unknown. */
    val zoomScale: Float,
    val savedAt: Long
)

// ----------------------------- PDF metadata --------------------------------

/** File-level metadata of an imported PDF shown in the details sheet. */
data class PdfFileInfo(
    val documentId: String,
    val sourceFileName: String,
    val fileSizeBytes: Long,
    val pageCount: Int,
    val title: String?,
    val author: String?,
    val creator: String?,
    val producer: String?,
    val importedAt: Long,
    val lastOpenedAt: Long
)

// ----------------------------- Backup --------------------------------------

/** One notebook/PDF entry inside a backup archive (preview row). */
@Serializable
data class BackupEntry(
    val id: String,
    val title: String,
    val kind: String,
    val pageCount: Int,
    val folderName: String? = null,
    val bytes: Long = 0
)

/**
 * The manifest at the root of every `.nanote-backup` archive.
 * Format is versioned; the reader rejects unknown future versions.
 */
@Serializable
data class BackupManifest(
    val formatVersion: Int = CURRENT_BACKUP_VERSION,
    val appVersion: String = "",
    val createdAt: Long,
    /** "FULL" (everything) or "SELECTION" (chosen documents). */
    val kind: String,
    val entries: List<BackupEntry> = emptyList()
)

/** What the restore preview shows before the user confirms. */
data class BackupPreview(
    val manifest: BackupManifest,
    val supported: Boolean
)

/** Result of a restore run. */
data class RestoreResult(
    val restoredNotebooks: Int,
    val restoredPdfs: Int,
    val renamedTitles: List<String> = emptyList(),
    val skipped: Int = 0
)

const val CURRENT_BACKUP_VERSION = 1

/** Progress of a long-running backup or restore (0..100). */
data class BackupProgress(
    val step: BackupStep,
    val percent: Int
)

enum class BackupStep { IDLE, PREPARING, COPYING, FINALIZING, DONE, FAILED }

// ----------------------------- Page operations ------------------------------

/** Page-level operations available on imported PDFs (executed via PDFBox). */
enum class PdfPageOpType { ROTATE, DELETE, DUPLICATE, REORDER, EXTRACT, INSERT_FROM_PDF }

/** Result of applying a page operation to a document. */
data class PdfPageOpResult(
    val ok: Boolean,
    val newPageCount: Int,
    val message: String? = null
)
