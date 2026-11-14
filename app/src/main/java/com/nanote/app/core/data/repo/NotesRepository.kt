package com.nanote.app.core.data.repo

import android.graphics.Bitmap
import android.net.Uri
import com.nanote.app.core.model.DocumentModel
import com.nanote.app.core.model.NoteKind
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.flow.Flow

data class NoteMeta(
    val id: String,
    val title: String,
    val kind: NoteKind,
    val favorite: Boolean = false,
    val folderId: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val pageCount: Int = 0
)

data class FolderMeta(val id: String, val name: String, val count: Int = 0)

data class OpenedDocument(
    val meta: NoteMeta,
    val model: DocumentModel,
    /** Original imported PDF, null for plain notes. */
    val pdfFile: File?
)

/** Contract for the persistence layer (Room index + .nanote files + media). */
interface NotesRepository {

    // ---------- queries ----------
    fun observeAllNotes(): Flow<List<NoteMeta>>
    fun observeFavorites(): Flow<List<NoteMeta>>
    fun observeRecent(limit: Int = 10): Flow<List<NoteMeta>>
    fun observeNotesInFolder(folderId: String): Flow<List<NoteMeta>>
    fun observeFolders(): Flow<List<FolderMeta>>
    suspend fun getMeta(id: String): NoteMeta?
    suspend fun searchNotes(query: String): List<NoteMeta>

    // ---------- documents ----------
    /** Creates a fresh blank note (1 page) and returns its id. */
    suspend fun createNote(title: String): String
    /** Imports a PDF via SAF, returns the new document id. */
    suspend fun importPdf(uri: Uri, title: String): String
    /** Imports a .nanote project file, returns the new document id. */
    suspend fun importNanote(uri: Uri): String?
    /** Loads a document (with version migration + partial recovery). */
    suspend fun openDocument(id: String): OpenedDocument?
    /** Persists the model atomically (temp file + rename). */
    suspend fun saveDocument(id: String, model: DocumentModel): Boolean

    // ---------- meta ops ----------
    suspend fun renameNote(id: String, title: String)
    suspend fun deleteNote(id: String)
    suspend fun duplicateNote(id: String): String?
    suspend fun setFavorite(id: String, favorite: Boolean)
    suspend fun setFolder(id: String, folderId: String?)
    suspend fun createFolder(name: String): String
    suspend fun renameFolder(id: String, name: String)
    suspend fun deleteFolder(id: String) // keeps notes, moves them to root

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
