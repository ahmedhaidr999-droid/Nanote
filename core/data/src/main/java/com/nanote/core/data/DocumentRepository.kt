package com.nanote.core.data

import android.graphics.Bitmap
import android.net.Uri
import com.nanote.core.model.ActivityAction
import com.nanote.core.model.CreateNotebookRequest
import com.nanote.core.model.DocumentMeta
import com.nanote.core.model.DocumentModel
import com.nanote.core.model.Folder
import com.nanote.core.model.FolderTreeNode
import com.nanote.core.model.LibraryFilter
import com.nanote.core.model.NoteKind
import com.nanote.core.model.NotebookSettings
import com.nanote.core.model.RecentActivityEntry
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
}
