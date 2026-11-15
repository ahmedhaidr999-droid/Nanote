package com.nanote.core.data

import android.graphics.Bitmap
import android.net.Uri
import com.nanote.core.model.ActivityAction
import com.nanote.core.model.CreateNotebookRequest
import com.nanote.core.model.DocumentMeta
import com.nanote.core.model.DocumentModel
import com.nanote.core.model.ExportOptions
import com.nanote.core.model.Folder
import com.nanote.core.model.FolderTreeNode
import com.nanote.core.model.LibraryFilter
import com.nanote.core.model.NoteKind
import com.nanote.core.model.NotebookSettings
import com.nanote.core.model.PdfBookmark
import com.nanote.core.model.PdfFileInfo
import com.nanote.core.model.PdfPageOpResult
import com.nanote.core.model.PdfTextHit
import com.nanote.core.model.ReadingPosition
import com.nanote.core.model.RecentActivityEntry
import com.nanote.core.model.RecentSearch
import com.nanote.core.model.SearchResults
import com.nanote.core.model.Tag
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.flow.Flow

/** A fully opened document: metadata + live model + source PDF (if any). */
data class OpenedDocument(
    val meta: DocumentMeta,
    val model: DocumentModel,
    /** Original imported PDF, null for plain notebooks. */
    val pdfFile: File?
)

/**
 * Contract for the persistence layer (Room index + .nanote files + media).
 * Covers library queries, notebook management, nested folders, activity
 * logging, import/export and thumbnails.
 */
interface DocumentRepository {

    // ---------- queries ----------

    fun observeAllDocuments(): Flow<List<DocumentMeta>>
    fun observeDocuments(filter: LibraryFilter, query: String = ""): Flow<List<DocumentMeta>>
    fun observeFavorites(): Flow<List<DocumentMeta>>
    fun observeRecent(limit: Int = 10): Flow<List<DocumentMeta>>
    fun observeDocumentsInFolder(folderId: String): Flow<List<DocumentMeta>>
    fun observeDocument(id: String): Flow<DocumentMeta?>
    suspend fun getMeta(id: String): DocumentMeta?
    suspend fun searchDocuments(query: String): List<DocumentMeta>

    // ---------- folders ----------

    /** All folders with direct + total document counts. */
    fun observeFolders(): Flow<List<Folder>>
    /** Ancestor chain (root first, the folder itself last). */
    fun observeFolderPath(folderId: String): Flow<List<Folder>>
    /** Folder + its subtree as pre-flattened picker nodes. */
    fun observeFolderTree(): Flow<List<FolderTreeNode>>

    // ---------- recent activity ----------

    fun observeRecentActivity(limit: Int = 20): Flow<List<RecentActivityEntry>>
    suspend fun logActivity(id: String, action: ActivityAction)

    // ---------- documents ----------

    /** Creates a fresh blank note (1 page) and returns its id. */
    suspend fun createNote(title: String): String
    /** Creates a notebook with full presentation & page defaults. */
    suspend fun createNotebook(request: CreateNotebookRequest): String
    /** Imports a PDF via SAF, returns the new document id. */
    suspend fun importPdf(uri: Uri, title: String): String
    /** Imports a .nanote project file, returns the new document id. */
    suspend fun importNanote(uri: Uri): String?
    /** Loads a document (with version migration + partial recovery). */
    suspend fun openDocument(id: String): OpenedDocument?
    /** Persists the model atomically (temp file + rename). */
    suspend fun saveDocument(id: String, model: DocumentModel): Boolean

    // ---------- meta ops ----------

    suspend fun renameDocument(id: String, title: String)
    suspend fun deleteDocuments(ids: Set<String>)
    suspend fun duplicateDocument(id: String): String?
    suspend fun setFavorite(id: String, favorite: Boolean)
    suspend fun setFavorites(ids: Set<String>, favorite: Boolean)
    suspend fun moveDocuments(ids: Set<String>, folderId: String?)
    suspend fun updateNotebookSettings(id: String, settings: NotebookSettings)
    /** Records lastOpenedAt = now (called when the editor opens a document). */
    suspend fun touchOpened(id: String)
    /** Persists a manual (drag) ordering for the CUSTOM sort option. */
    suspend fun setCustomOrder(orderedIds: List<String>)

    // ---------- folders (mutations) ----------

    suspend fun createFolder(name: String, parentId: String? = null): String
    suspend fun renameFolder(id: String, name: String)
    /** Deletes the folder; documents and sub-folders move up to its parent. */
    suspend fun deleteFolder(id: String)
    /** Moves a folder; returns false when the target is inside the folder itself. */
    suspend fun moveFolder(id: String, newParentId: String?): Boolean

    // ---------- media ----------

    /** Copies an image into the doc dir, returns the stored file. */
    suspend fun importImageIntoDoc(docId: String, uri: Uri): File?
    fun resolveImage(docId: String, relativePath: String): File
    fun cameraFile(docId: String): File

    // ---------- export ----------

    /** Flattens annotations + ink into a new PDF written to [target]. */
    suspend fun exportPdf(id: String, target: OutputStream): Boolean
    /** Exports the raw .nanote project file to [target]. */
    suspend fun exportNanote(id: String, target: OutputStream): Boolean

    // ---------- thumbnails ----------

    /** First-page thumbnail, cached under thumbnails/. */
    suspend fun thumbnail(docId: String, maxW: Int = 360): Bitmap?
    suspend fun clearThumbnailCache()

    // ---------- storage ----------

    suspend fun storageUsedBytes(): Long

    // ------------------------------------------------------------------
    // Part 3 — PDF engine, tags, search, bookmarks, positions, page ops
    // ------------------------------------------------------------------

    // ---------- tags ----------

    /** All tags with usage counts (observable). */
    fun observeTags(): Flow<List<Tag>>

    /** Current tag names of one document (observable). */
    fun observeDocumentTags(docId: String): Flow<List<String>>

    /** Replaces the tag set of a document; creates unknown tags. */
    suspend fun setDocumentTags(docId: String, tags: List<String>)

    /** Deletes a tag everywhere. */
    suspend fun deleteTag(name: String)

    // ---------- search ----------

    /** Grouped local search across titles, folders, tags and PDF text. */
    suspend fun searchAcross(query: String, pdfTextLimit: Int = 20): SearchResults

    /** Records a query in the search history (deduplicated). */
    suspend fun recordSearch(query: String)

    /** Recent search queries, most recent first. */
    fun observeRecentSearches(limit: Int = 8): Flow<List<RecentSearch>>

    /** Removes one query from the history. */
    suspend fun deleteRecentSearch(query: String)

    /** Clears the whole search history. */
    suspend fun clearSearchHistory()

    // ---------- PDF bookmarks & reading position ----------

    /** Bookmarks of one document, observable. */
    fun observeBookmarks(docId: String): Flow<List<PdfBookmark>>

    /** Adds or removes a bookmark on [pageIndex]; returns the new state. */
    suspend fun toggleBookmark(docId: String, pageIndex: Int, pdfPage: Int, title: String): Boolean

    /** Deletes a bookmark by id. */
    suspend fun deleteBookmark(bookmarkId: String)

    /** Saves where the user stopped reading (page + zoom). */
    suspend fun saveReadingPosition(docId: String, pageIndex: Int, zoomScale: Float)

    /** Last saved reading position, null when none. */
    suspend fun readingPosition(docId: String): ReadingPosition?

    // ---------- PDF metadata ----------

    /** File-level metadata of an imported PDF (sizes, dates, doc info). */
    suspend fun pdfFileInfo(docId: String): PdfFileInfo?

    // ---------- PDF page operations ----------

    /**
     * Deletes pages of an imported PDF (working copy only — the original
     * source file is never touched). Annotations on removed pages are
     * dropped; the caller must reload the document afterwards.
     */
    suspend fun deletePdfPages(docId: String, pageIndexes: Set<Int>): PdfPageOpResult

    /** Duplicates one page (inserted right after it). */
    suspend fun duplicatePdfPage(docId: String, pageIndex: Int): PdfPageOpResult

    /** Rotates one page by 90° clockwise. */
    suspend fun rotatePdfPage(docId: String, pageIndex: Int): PdfPageOpResult

    /** Moves one page to a new position. */
    suspend fun movePdfPage(docId: String, fromIndex: Int, toIndex: Int): PdfPageOpResult

    /** Extracts selected pages into a NEW document (also implements split). */
    suspend fun extractPdfPagesToDocument(docId: String, pageIndexes: List<Int>, title: String): String?

    /** Inserts all pages of another PDF (SAF uri) at [insertAt]. */
    suspend fun insertPdfPagesFrom(docId: String, uri: Uri, insertAt: Int): PdfPageOpResult

    // ---------- exports ----------

    /**
     * Universal export with options: PDF (original / flattened / markup
     * annotations), PNG or JPEG, optional page subset. PDFs are verified
     * with an independent renderer before being reported as successful.
     */
    suspend fun exportDocument(id: String, options: ExportOptions, target: OutputStream): Boolean

    /**
     * Renders one page to a bitmap (PDFs via pdfium, notes via the offscreen
     * page painter) at up to [maxWidthPx] wide.
     */
    suspend fun renderPageBitmap(id: String, pageIndex: Int, maxWidthPx: Int): Bitmap?

    // ---------- text index ----------

    /** (Re)builds the PDF text search index; returns indexed page count. */
    suspend fun reindexPdfText(docId: String): Int
}
