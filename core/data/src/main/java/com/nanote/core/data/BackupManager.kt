package com.nanote.core.data

import android.content.Context
import com.nanote.core.common.AppLog
import com.nanote.core.database.AppDatabase
import com.nanote.core.database.BookmarkDao
import com.nanote.core.database.FolderDao
import com.nanote.core.database.NoteDao
import com.nanote.core.database.PdfTextDao
import com.nanote.core.database.TagDao
import com.nanote.core.model.BackupEntry
import com.nanote.core.model.BackupManifest
import com.nanote.core.model.BackupPreview
import com.nanote.core.model.CURRENT_BACKUP_VERSION
import com.nanote.core.model.RestoreResult
import com.nanote.core.storage.format.BackupBookmarkRow
import com.nanote.core.storage.format.BackupFolderRow
import com.nanote.core.storage.format.BackupNoteRow
import com.nanote.core.storage.format.BackupNoteTagRow
import com.nanote.core.storage.format.BackupPdfTextRow
import com.nanote.core.storage.format.BackupRows
import com.nanote.core.storage.format.BackupTagRow
import com.nanote.core.storage.format.BackupFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Local manual backup & restore (100% offline).
 *
 * Backup: writes a versioned `.nanote-backup` ZIP (see [BackupFormat]) with
 * the Room index rows of the selected documents plus their whole on-disk
 * directories (content, source PDFs, images) to a user-chosen SAF location.
 *
 * Restore: stages the archive in the cache dir, validates the manifest,
 * extracts to a staging directory and only then inserts rows + moves files.
 * Restored documents always get NEW ids; name collisions resolve to
 * "Title (Restored)" so nothing existing is ever overwritten.
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    @Named("appVersion") private val appVersion: String
) {

    companion object {
        private const val TAG = "BackupManager"
    }

    private val noteDao: NoteDao = db.noteDao()
    private val folderDao: FolderDao = db.folderDao()
    private val tagDao: TagDao = db.tagDao()
    private val pdfTextDao: PdfTextDao = db.pdfTextDao()
    private val bookmarkDao: BookmarkDao = db.bookmarkDao()

    private val docsRoot: File get() = File(context.filesDir, "documents")

    // ------------------------------------------------------------------
    // backup
    // ------------------------------------------------------------------

    /**
     * Creates a backup archive.
     *
     * @param docIds null → EVERYTHING (full backup); otherwise the selection.
     * @param progress optional 0..100 callback
     */
    suspend fun createBackup(
        docIds: List<String>?,
        target: OutputStream,
        progress: (Int) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val ids = docIds ?: noteDao.getAll().map { it.id }
            if (ids.isEmpty()) {
                AppLog.w(TAG, "createBackup: nothing to back up")
                return@withContext false
            }
            val notes = ids.mapNotNull { noteDao.getById(it) }
            val notesById = notes.associateBy { it.id }

            // Folders referenced by the backed-up documents (ancestors included).
            val folderIds = HashSet<String>()
            for (n in notes) {
                var fid = n.folderId
                var guard = 0
                while (fid != null && fid !in folderIds && guard++ < 32) {
                    folderIds.add(fid)
                    fid = folderDao.getById(fid)?.parentId
                }
            }
            val folders = folderIds.mapNotNull { folderDao.getById(it) }

            val tags = tagDao.getAll()
            val noteTags = ids.flatMap { id ->
                tagDao.tagsForNote(id).map { BackupNoteTagRow(id, it) }
            }
            val pdfText = notes
                .filter { it.kind == "PDF" }
                .flatMap { n -> pdfTextDao.pagesForDoc(n.id).map { BackupPdfTextRow(it.documentId, it.pageIndex, it.pdfPage, it.text) } }
            val bookmarks = notes
                .filter { it.kind == "PDF" }
                .flatMap { n -> bookmarkDao.getForDoc(n.id).map { BackupBookmarkRow(it.id, it.documentId, it.pageIndex, it.pdfPage, it.title, it.createdAt) } }

            val manifest = BackupManifest(
                formatVersion = CURRENT_BACKUP_VERSION,
                appVersion = appVersion,
                createdAt = System.currentTimeMillis(),
                kind = if (docIds == null) "FULL" else "SELECTION",
                entries = notes.map { n ->
                    val dir = File(docsRoot, n.id)
                    val bytes = if (dir.exists()) dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
                    BackupEntry(
                        id = n.id,
                        title = n.title,
                        kind = n.kind,
                        pageCount = n.pageCount,
                        folderName = n.folderId?.let { fid -> folders.firstOrNull { it.id == fid }?.name },
                        bytes = bytes
                    )
                }
            )
            val rows = BackupRows(
                notes = notes.map { it.toRow() },
                folders = folders.map { BackupFolderRow(it.id, it.name, it.sortOrder, it.parentId, it.createdAt, it.updatedAt) },
                tags = tags.map { BackupTagRow(it.name, it.color, it.createdAt) },
                noteTags = noteTags,
                pdfText = pdfText,
                bookmarks = bookmarks
            )
            val ok = BackupFormat.write(target, rows, manifest, { id -> File(docsRoot, id) }, progress)
            if (ok) AppLog.i(TAG, "backup created: ${manifest.entries.size} documents")
            ok
        } catch (t: Throwable) {
            AppLog.e(TAG, "createBackup failed", t)
            false
        }
    }

    // ------------------------------------------------------------------
    // restore
    // ------------------------------------------------------------------

    /** Reads the manifest of a staged copy of the stream for preview. */
    suspend fun preview(source: InputStream): BackupPreview? = withContext(Dispatchers.IO) {
        val staged = stageToCache(source) ?: return@withContext null
        try {
            ZipFile(staged).use { zip ->
                val manifest = BackupFormat.readManifest(zip)
                    ?: return@withContext null
                BackupPreview(manifest = manifest, supported = true)
            }
        } catch (t: Throwable) {
            AppLog.e(TAG, "preview failed", t)
            null
        } finally {
            staged.delete()
        }
    }

    /**
     * Restores a backup: staged extraction → row inserts → file moves.
     * Existing data is never modified or overwritten.
     */
    suspend fun restore(
        source: InputStream,
        progress: (Int) -> Unit = {}
    ): RestoreResult? = withContext(Dispatchers.IO) {
        val staged = stageToCache(source) ?: return@withContext null
        var stagingDir: File? = null
        try {
            ZipFile(staged).use { zip ->
                val manifest = BackupFormat.readManifest(zip)
                    ?: return@withContext null
                val rows = BackupFormat.readRows(zip)
                val ids = manifest.entries.map { it.id }
                val staging = File(context.cacheDir, "restore-${System.currentTimeMillis()}")
                stagingDir = staging
                val extracted = BackupFormat.extractFiles(zip, ids, staging, progress)
                if (extracted.isEmpty() && ids.isNotEmpty()) {
                    AppLog.w(TAG, "restore: no document files in archive")
                }

                // --- id remapping (restore NEVER overwrites)
                val idMap = HashMap<String, String>()
                for (id in ids) {
                    idMap[id] = com.nanote.core.model.newId()
                }
                val folderIdMap = HashMap<String, String>()
                for (f in rows.folders) {
                    folderIdMap[f.id] = com.nanote.core.model.newId()
                }

                val existingTitles = noteDao.getAll().map { it.title }.toHashSet()
                val renamed = ArrayList<String>()
                var notebooks = 0
                var pdfs = 0
                val now = System.currentTimeMillis()

                // --- folders (parents first by structure depth)
                val sortedFolders = rows.folders.sortedBy { folderDepth(it, rows.folders) }
                for (f in sortedFolders) {
                    db.folderDao().upsert(
                        com.nanote.core.database.FolderEntity(
                            id = folderIdMap[f.id]!!,
                            name = f.name,
                            sortOrder = f.sortOrder,
                            parentId = f.parentId?.let { folderIdMap[it] },
                            createdAt = f.createdAt,
                            updatedAt = now
                        )
                    )
                }

                // --- tags (merge by name)
                for (t in rows.tags) {
                    tagDao.upsert(com.nanote.core.database.TagEntity(t.name, t.color, t.createdAt))
                }

                // --- documents
                var order = noteDao.getMaxSortOrder()
                for (row in rows.notes) {
                    val newId = idMap[row.id] ?: com.nanote.core.model.newId()
                    var title = row.title
                    if (title in existingTitles) {
                        title = "$title (Restored)"
                        renamed.add(title)
                    }
                    if (row.kind == "PDF") pdfs++ else notebooks++
                    noteDao.upsert(row.toEntity(newId, title, folderIdMap, now, order++))

                    // files
                    val stagedDir = File(staging, row.id)
                    if (stagedDir.exists()) {
                        val targetDir = File(docsRoot, newId)
                        if (targetDir.exists()) targetDir.deleteRecursively()
                        stagedDir.copyRecursively(targetDir, overwrite = true)
                    }
                }

                // --- tag assignments
                for (nt in rows.noteTags) {
                    val newNoteId = idMap[nt.noteId] ?: continue
                    val current = tagDao.tagsForNote(newNoteId)
                    if (nt.tagName !in current) {
                        tagDao.setNoteTags(newNoteId, current + nt.tagName)
                    }
                }

                // --- pdf text index
                val textRows = rows.pdfText.mapNotNull { r ->
                    val newId = idMap[r.documentId] ?: return@mapNotNull null
                    com.nanote.core.database.PdfTextPageEntity(
                        id = 0, documentId = newId, pageIndex = r.pageIndex,
                        pdfPage = r.pdfPage, text = r.text
                    )
                }
                if (textRows.isNotEmpty()) pdfTextDao.insertAll(textRows)

                // --- bookmarks
                for (b in rows.bookmarks) {
                    val newId = idMap[b.documentId] ?: continue
                    bookmarkDao.upsert(
                        com.nanote.core.database.PdfBookmarkEntity(
                            id = com.nanote.core.model.newId(), documentId = newId,
                            pageIndex = b.pageIndex, pdfPage = b.pdfPage,
                            title = b.title, createdAt = b.createdAt
                        )
                    )
                }

                val result = RestoreResult(
                    restoredNotebooks = notebooks,
                    restoredPdfs = pdfs,
                    renamedTitles = renamed
                )
                AppLog.i(TAG, "restore done: $notebooks notebooks, $pdfs pdfs, ${renamed.size} renamed")
                result
            }
        } catch (t: Throwable) {
            AppLog.e(TAG, "restore failed", t)
            null
        } finally {
            staged.delete()
            stagingDir?.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private fun stageToCache(source: InputStream): File? = try {
        val staged = File(context.cacheDir, "backup-preview-${System.currentTimeMillis()}.zip")
        source.use { input -> input.copyTo(staged.outputStream()) }
        staged
    } catch (t: Throwable) {
        AppLog.e(TAG, "stage failed: ${t.message}")
        null
    }

    private fun folderDepth(folder: BackupFolderRow, all: List<BackupFolderRow>): Int {
        var depth = 0
        var parentId = folder.parentId
        var guard = 0
        while (parentId != null && guard++ < 64) {
            depth++
            parentId = all.firstOrNull { it.id == parentId }?.parentId
        }
        return depth
    }
}

// ----------------------------------------------------------------------
// Row ↔ entity mapping (kept here to avoid polluting the public mappers)
// ----------------------------------------------------------------------

private fun com.nanote.core.database.NoteEntity.toRow(): BackupNoteRow = BackupNoteRow(
    id = id, title = title, kind = kind, favorite = favorite, folderId = folderId,
    createdAt = createdAt, updatedAt = updatedAt, pageCount = pageCount,
    coverColor = coverColor, icon = icon, pageTemplate = pageTemplate,
    pageOrientation = pageOrientation, pageSize = pageSize,
    lastOpenedAt = lastOpenedAt, sortOrder = sortOrder,
    fileSizeBytes = fileSizeBytes, sourceFileName = sourceFileName
)

private fun BackupNoteRow.toEntity(
    newId: String,
    resolvedTitle: String,
    folderIdMap: Map<String, String>,
    now: Long,
    order: Int
): com.nanote.core.database.NoteEntity = com.nanote.core.database.NoteEntity(
    id = newId,
    title = resolvedTitle,
    kind = kind,
    favorite = false, // restored documents start unfavorite
    folderId = folderId?.let { folderIdMap[it] },
    createdAt = now,
    updatedAt = now,
    pageCount = pageCount,
    coverColor = coverColor,
    icon = icon,
    pageTemplate = pageTemplate,
    pageOrientation = pageOrientation,
    pageSize = pageSize,
    lastOpenedAt = 0L,
    sortOrder = order,
    fileSizeBytes = fileSizeBytes,
    sourceFileName = sourceFileName
)
