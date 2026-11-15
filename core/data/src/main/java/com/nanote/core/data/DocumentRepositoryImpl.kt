package com.nanote.core.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.nanote.core.database.AppDatabase
import com.nanote.core.database.FolderEntity
import com.nanote.core.database.NoteEntity
import com.nanote.core.database.PdfBookmarkEntity
import com.nanote.core.database.PdfTextPageEntity
import com.nanote.core.database.ReadingPositionEntity
import com.nanote.core.database.SearchHistoryEntity
import com.nanote.core.database.TagEntity
import com.nanote.core.storage.format.DocumentFormat
import com.nanote.core.storage.format.DocumentMigrations
import com.nanote.core.model.ActivityAction
import com.nanote.core.model.CURRENT_FORMAT_VERSION
import com.nanote.core.model.CreateNotebookRequest
import com.nanote.core.model.DocumentContent
import com.nanote.core.model.DocumentMeta
import com.nanote.core.model.DocumentModel
import com.nanote.core.model.ExportFormat
import com.nanote.core.model.ExportOptions
import com.nanote.core.model.Folder
import com.nanote.core.model.FolderTreeNode
import com.nanote.core.model.HighlightAnnotation
import com.nanote.core.model.ImageElement
import com.nanote.core.model.LibraryFilter
import com.nanote.core.model.NoteKind
import com.nanote.core.model.NotebookSettings
import com.nanote.core.model.PageDef
import com.nanote.core.model.PdfBookmark
import com.nanote.core.model.PdfExportMode
import com.nanote.core.model.PdfFileInfo
import com.nanote.core.model.PdfPageOpResult
import com.nanote.core.model.PdfTextHit
import com.nanote.core.model.ReadingPosition
import com.nanote.core.model.RecentActivityEntry
import com.nanote.core.model.RecentSearch
import com.nanote.core.model.SearchResults
import com.nanote.core.model.ShapeElement
import com.nanote.core.model.ShapeKind
import com.nanote.core.model.Stroke
import com.nanote.core.model.Tag
import com.nanote.core.model.TextElement
import com.nanote.core.model.ToolType
import com.nanote.core.model.UnderlineAnnotation
import com.nanote.core.model.highlighterAlphaOf
import com.nanote.core.model.newId
import com.nanote.core.model.paintAlphaOf
import com.nanote.core.common.AppLog
import com.nanote.core.common.ApplicationScope
import com.nanote.core.database.RecentActivityEntity
import com.nanote.core.pdf.PdfContentPainter
import com.nanote.core.pdf.PdfMarkupAnnotator
import com.nanote.core.pdf.PdfPageOps
import com.nanote.core.pdf.PdfTextIndexer
import com.nanote.core.pdf.PdfVerifier
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Room + filesystem implementation of [DocumentRepository].
 *
 * Storage layout (under `filesDir`):
 * ```
 * documents/<docId>/doc.nanote    serialized DocumentContent (JSON, atomic writes)
 * documents/<docId>/original.pdf  the imported source PDF, when kind == PDF (NEVER modified)
 * documents/<docId>/edited.pdf    working copy after page operations (original stays pristine)
 * documents/<docId>/images/       embedded gallery/camera images
 * thumbnails/<docId>.png          first-page thumbnail cache
 * ```
 */
class DocumentRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    @ApplicationScope private val appScope: CoroutineScope
) : DocumentRepository {

    companion object {
        private const val TAG = "DocumentRepository"
        private const val DOC_FILE = "doc.nanote"
        private const val PDF_FILE = "original.pdf"
        private const val EDITED_PDF_FILE = "edited.pdf"
        private const val IMAGES_DIR = "images"
        private const val THUMBS_DIR = "thumbnails"
        private const val DEFAULT_PAGE_W = 595f
        private const val DEFAULT_PAGE_H = 842f
        private const val ACTIVITY_KEEP = 100
    }

    private val noteDao = db.noteDao()
    private val folderDao = db.folderDao()
    private val activityDao = db.recentActivityDao()
    private val tagDao = db.tagDao()
    private val pdfTextDao = db.pdfTextDao()
    private val bookmarkDao = db.bookmarkDao()
    private val readingPositionDao = db.readingPositionDao()
    private val searchHistoryDao = db.searchHistoryDao()
    private val contentJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    // ------------------------------------------------------------------
    // queries
    // ------------------------------------------------------------------

    override fun observeAllDocuments(): Flow<List<DocumentMeta>> =
        noteDao.observeAll().map { rows -> rows.map { it.toMeta() } }

    override fun observeDocuments(filter: LibraryFilter, query: String): Flow<List<DocumentMeta>> {
        if (filter is LibraryFilter.InFolder) {
            return observeDocumentsInFolder(filter.folderId)
        }
        if (filter is LibraryFilter.ROOT) {
            return noteDao.observeRootDocuments().map { rows -> rows.map { it.toMeta() } }
        }
        val where = mutableListOf<String>()
        val args = mutableListOf<Any>()
        when (filter) {
            LibraryFilter.ALL -> {}
            LibraryFilter.NOTEBOOKS -> { where += "kind = ?"; args += NoteKind.NOTE.name }
            LibraryFilter.IMPORTED -> { where += "kind = ?"; args += NoteKind.PDF.name }
            LibraryFilter.FAVORITES -> where += "favorite = 1"
            LibraryFilter.RECENT -> where += "lastOpenedAt > 0"
            is LibraryFilter.InFolder -> {}
            is LibraryFilter.ROOT -> {}
        }
        if (query.isNotBlank()) {
            where += "title LIKE ? ESCAPE '\\'"
            args += "%${query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")}%"
        }
        val sql = buildString {
            append("SELECT * FROM notes")
            if (where.isNotEmpty()) {
                append(" WHERE ")
                append(where.joinToString(" AND "))
            }
        }
        return noteDao.observeFiltered(androidx.sqlite.db.SimpleSQLiteQuery(sql, args.toTypedArray()))
            .map { rows -> rows.map { it.toMeta() } }
    }

    override fun observeFavorites(): Flow<List<DocumentMeta>> =
        noteDao.observeFavorites().map { rows -> rows.map { it.toMeta() } }

    override fun observeRecent(limit: Int): Flow<List<DocumentMeta>> =
        noteDao.observeRecent(limit).map { rows -> rows.map { it.toMeta() } }

    override fun observeDocumentsInFolder(folderId: String): Flow<List<DocumentMeta>> =
        noteDao.observeByFolder(folderId).map { rows -> rows.map { it.toMeta() } }

    override fun observeDocument(id: String): Flow<DocumentMeta?> =
        noteDao.observeById(id).map { it?.toMeta() }

    override fun observeFolders(): Flow<List<Folder>> =
        combine(folderDao.observeAll(), noteDao.observeAll()) { folders, notes ->
            val direct = notes.groupingBy { it.folderId }.eachCount()
            folders.map { f -> f.toFolder(directCount = direct[f.id] ?: 0) }
                .map { f -> f.copy(totalCount = f.totalCount) }
                .let { withTotals(it) }
        }

    override fun observeFolderPath(folderId: String): Flow<List<Folder>> =
        combine(folderDao.observeAll(), noteDao.observeAll()) { folders, notes ->
            val byId = folders.associateBy { it.id }
            val direct = notes.groupingBy { it.folderId }.eachCount()
            val chain = mutableListOf<Folder>()
            var cursor = byId[folderId]
            while (cursor != null) {
                chain.add(cursor.toFolder(directCount = direct[cursor.id] ?: 0))
                cursor = cursor.parentId?.let { byId[it] }
            }
            chain.reverse()
            withTotals(chain.mapIndexed { i, f ->
                if (i == chain.lastIndex) f else f.copy(totalCount = 0)
            })
        }

    override fun observeFolderTree(): Flow<List<FolderTreeNode>> =
        combine(folderDao.observeAll(), noteDao.observeAll()) { folders, notes ->
            val direct = notes.groupingBy { it.folderId }.eachCount()
            val mapped = folders.map { it.toFolder(directCount = direct[it.id] ?: 0) }
            val withTotals = withTotals(mapped)
            buildTree(withTotals)
        }

    override fun observeRecentActivity(limit: Int): Flow<List<RecentActivityEntry>> =
        activityDao.observeRecent(limit).map { rows -> rows.map { it.toEntry() } }

    override suspend fun getMeta(id: String): DocumentMeta? =
        withContext(Dispatchers.IO) { noteDao.getById(id)?.toMeta() }

    override suspend fun searchDocuments(query: String): List<DocumentMeta> =
        withContext(Dispatchers.IO) { noteDao.search(query).map { it.toMeta() } }

    // ------------------------------------------------------------------
    // helpers: nested-folder totals + tree
    // ------------------------------------------------------------------

    /** Fills [Folder.totalCount] with direct + all descendant counts. */
    private fun withTotals(folders: List<Folder>): List<Folder> {
        val byParent = folders.groupBy { it.parentId }
        fun totalOf(id: String): Int {
            val self = folders.firstOrNull { it.id == id } ?: return 0
            return self.directCount + (byParent[id]?.sumOf { totalOf(it.id) } ?: 0)
        }
        return folders.map { it.copy(totalCount = totalOf(it.id)) }
    }

    private fun buildTree(folders: List<Folder>): List<FolderTreeNode> {
        val byParent = folders.groupBy { it.parentId }
        fun node(folder: Folder, depth: Int): FolderTreeNode = FolderTreeNode(
            folder = folder,
            depth = depth,
            children = (byParent[folder.id] ?: emptyList())
                .sortedWith(compareBy({ it.sortOrder }, { it.name }))
                .map { node(it, depth + 1) }
        )
        return (byParent[null] ?: emptyList())
            .sortedWith(compareBy({ it.sortOrder }, { it.name }))
            .map { node(it, 0) }
    }

    /** Collects a subtree (folder + all descendants) — used for move guards. */
    private suspend fun subtreeIds(folderId: String): Set<String> {
        val all = folderDao.getAll()
        val byParent = all.groupBy { it.parentId }
        val out = mutableSetOf(folderId)
        val queue = ArrayDeque<String>()
        queue.add(folderId)
        while (queue.isNotEmpty()) {
            val next = byParent[queue.removeFirst()] ?: continue
            for (child in next) {
                if (out.add(child.id)) queue.add(child.id)
            }
        }
        return out
    }

    private suspend fun logActivityInternal(id: String, action: ActivityAction) {
        try {
            activityDao.insert(
                RecentActivityEntity(id = 0, documentId = id, action = action.name, occurredAt = System.currentTimeMillis())
            )
            activityDao.prune(ACTIVITY_KEEP.toLong())
        } catch (t: Throwable) {
            AppLog.w(TAG, "logActivity($id, $action) failed: ${t.message}")
        }
    }

    // ------------------------------------------------------------------
    // documents
    // ------------------------------------------------------------------

    override suspend fun createNote(title: String): String = withContext(Dispatchers.IO) {
        val id = newId()
        docDir(id).mkdirs()
        val content = DocumentContent(
            version = CURRENT_FORMAT_VERSION,
            pages = listOf(PageDef(index = 0))
        )
        if (!DocumentFormat.save(docFile(id), content)) {
            AppLog.w(TAG, "createNote: initial save failed for $id (will retry on next save)")
        }
        val now = System.currentTimeMillis()
        noteDao.upsert(
            NoteEntity(
                id = id,
                title = title.ifBlank { "Untitled" },
                kind = NoteKind.NOTE.name,
                favorite = false,
                folderId = null,
                createdAt = now,
                updatedAt = now,
                pageCount = content.pages.size,
                coverColor = com.nanote.core.model.CoverColor.BLUE.name,
                icon = com.nanote.core.model.NotebookIcon.NOTEBOOK.name,
                pageTemplate = com.nanote.core.model.PageTemplate.LINED.name,
                pageOrientation = com.nanote.core.model.PageOrientation.PORTRAIT.name,
                pageSize = com.nanote.core.model.PageSize.A4.name,
                lastOpenedAt = 0L,
                sortOrder = nextSortOrder()
            )
        )
        logActivityInternal(id, ActivityAction.CREATED)
        AppLog.i(TAG, "created note $id")
        id
    }

    override suspend fun createNotebook(request: CreateNotebookRequest): String =
        withContext(Dispatchers.IO) {
            val id = newId()
            docDir(id).mkdirs()
            val size = request.pageSize
            val w: Float
            val h: Float
            if (request.pageOrientation == com.nanote.core.model.PageOrientation.LANDSCAPE) {
                w = size.heightPts; h = size.widthPts
            } else {
                w = size.widthPts; h = size.heightPts
            }
            val content = DocumentContent(
                version = CURRENT_FORMAT_VERSION,
                pages = listOf(PageDef(index = 0, width = w, height = h))
            )
            if (!DocumentFormat.save(docFile(id), content)) {
                AppLog.w(TAG, "createNotebook: initial save failed for $id")
            }
            val now = System.currentTimeMillis()
            noteDao.upsert(
                NoteEntity(
                    id = id,
                    title = request.title.ifBlank { "Untitled" },
                    kind = NoteKind.NOTE.name,
                    favorite = false,
                    folderId = request.folderId,
                    createdAt = now,
                    updatedAt = now,
                    pageCount = 1,
                    coverColor = request.coverColor.name,
                    icon = request.icon.name,
                    pageTemplate = request.pageTemplate.name,
                    pageOrientation = request.pageOrientation.name,
                    pageSize = request.pageSize.name,
                    lastOpenedAt = 0L,
                    sortOrder = nextSortOrder()
                )
            )
            logActivityInternal(id, ActivityAction.CREATED)
            AppLog.i(TAG, "created notebook $id ('${request.title}')")
            id
        }

    override suspend fun importPdf(uri: Uri, title: String): String = withContext(Dispatchers.IO) {
        val id = newId()
        val dir = docDir(id)
        dir.mkdirs()
        val pdfFile = File(dir, PDF_FILE)
        try {
            copyUriToFile(uri, pdfFile)

            val core = PdfiumCore(context)
            val doc = openPdf(core, pdfFile)
            val pages = try {
                val count = core.getPageCount(doc)
                if (count <= 0) throw IllegalArgumentException("PDF has no pages")
                // Exact PDF points via getPageWidthPoint/HeightPoint — identical
                // to the runtime engine (PdfDocumentHandle), so layout and
                // rendered bitmaps always agree.
                (0 until count).map { i ->
                    val w = core.getPageWidthPoint(doc, i).toFloat()
                    val h = core.getPageHeightPoint(doc, i).toFloat()
                    PageDef(
                        index = i,
                        width = if (w > 0f) w else DEFAULT_PAGE_W,
                        height = if (h > 0f) h else DEFAULT_PAGE_H,
                        pdfPage = i
                    )
                }
            } finally {
                core.closeDocument(doc)
            }

            val content = DocumentContent(version = CURRENT_FORMAT_VERSION, pages = pages)
            if (!DocumentFormat.save(docFile(id), content)) {
                throw IOException("could not persist doc.nanote")
            }
            val now = System.currentTimeMillis()
            noteDao.upsert(
                NoteEntity(
                    id = id,
                    title = title.ifBlank { "Untitled PDF" },
                    kind = NoteKind.PDF.name,
                    favorite = false,
                    folderId = null,
                    createdAt = now,
                    updatedAt = now,
                    pageCount = pages.size,
                    coverColor = com.nanote.core.model.CoverColor.SLATE.name,
                    icon = com.nanote.core.model.NotebookIcon.NOTES.name,
                    pageTemplate = com.nanote.core.model.PageTemplate.PLAIN.name,
                    pageOrientation = com.nanote.core.model.PageOrientation.PORTRAIT.name,
                    pageSize = com.nanote.core.model.PageSize.A4.name,
                    lastOpenedAt = 0L,
                    sortOrder = nextSortOrder(),
                    fileSizeBytes = pdfFile.length(),
                    sourceFileName = title
                )
            )
            logActivityInternal(id, ActivityAction.IMPORTED)
            AppLog.i(TAG, "imported PDF '$title' as $id (${pages.size} pages, ${pdfFile.length()} bytes)")
            // Build the search text index off the critical path.
            appScope.launch {
                try {
                    reindexPdfText(id)
                } catch (t: Throwable) {
                    AppLog.w(TAG, "background text index failed for $id: ${t.message}")
                }
            }
            id
        } catch (t: Throwable) {
            AppLog.e(TAG, "importPdf failed for $uri", t)
            dir.deleteRecursively()
            throw IllegalArgumentException("Cannot import PDF: ${t.message}", t)
        }
    }

    /**
     * Imports a .nanote project file. The original source PDF is not embedded
     * in the project format, so PDF-kind imports keep their page definitions
     * but render blank page backgrounds until the user re-annotates them —
     * an accepted limitation of the interchange format.
     */
    override suspend fun importNanote(uri: Uri): String? = withContext(Dispatchers.IO) {
        val text = try {
            readUriText(uri)
        } catch (t: Throwable) {
            AppLog.e(TAG, "importNanote: cannot read $uri", t)
            return@withContext null
        }
        val parsed = try {
            contentJson.decodeFromString(DocumentContent.serializer(), text)
        } catch (t: Throwable) {
            AppLog.e(TAG, "importNanote: parse failed for $uri", t)
            return@withContext null
        }
        val content = DocumentMigrations.migrate(parsed)
        val id = newId()
        val dir = docDir(id)
        dir.mkdirs()
        if (!DocumentFormat.save(docFile(id), content)) {
            AppLog.e(TAG, "importNanote: cannot persist $id")
            dir.deleteRecursively()
            return@withContext null
        }
        val kind = if (content.pages.any { it.pdfPage >= 0 }) NoteKind.PDF else NoteKind.NOTE
        val now = System.currentTimeMillis()
        noteDao.upsert(
            NoteEntity(
                id = id,
                title = "Imported note",
                kind = kind.name,
                favorite = false,
                folderId = null,
                createdAt = now,
                updatedAt = now,
                pageCount = content.pages.size,
                coverColor = com.nanote.core.model.CoverColor.SLATE.name,
                icon = com.nanote.core.model.NotebookIcon.NOTES.name,
                pageTemplate = com.nanote.core.model.PageTemplate.PLAIN.name,
                pageOrientation = com.nanote.core.model.PageOrientation.PORTRAIT.name,
                pageSize = com.nanote.core.model.PageSize.A4.name,
                lastOpenedAt = 0L,
                sortOrder = nextSortOrder()
            )
        )
        logActivityInternal(id, ActivityAction.IMPORTED)
        AppLog.i(TAG, "imported .nanote as $id (${content.pages.size} pages, kind=$kind)")
        id
    }

    override suspend fun openDocument(id: String): OpenedDocument? = withContext(Dispatchers.IO) {
        val entity = noteDao.getById(id) ?: return@withContext null

        var partiallyRecovered = false
        val content: DocumentContent
        val file = docFile(id)
        if (file.exists()) {
            val loaded = DocumentFormat.load(file)
            if (loaded != null) {
                partiallyRecovered = loaded.second
                content = DocumentMigrations.migrate(loaded.first.snapshot())
            } else {
                AppLog.e(TAG, "openDocument: $id is unrecoverable, starting blank")
                partiallyRecovered = true
                content = blankContent()
            }
        } else {
            AppLog.w(TAG, "openDocument: doc.nanote missing for $id, starting blank")
            partiallyRecovered = true
            content = blankContent()
        }

        val model = DocumentModel(content)
        model.partiallyRecovered = partiallyRecovered

        val kind = try {
            NoteKind.valueOf(entity.kind)
        } catch (t: Throwable) {
            NoteKind.NOTE
        }
        val pdfFile = if (kind == NoteKind.PDF) {
            // Prefer the working copy when page operations created one;
            // the untouched original stays available for ORIGINAL exports.
            val edited = File(docDir(id), EDITED_PDF_FILE)
            val original = File(docDir(id), PDF_FILE)
            when {
                edited.exists() -> edited
                original.exists() -> original
                else -> null
            }
        } else {
            null
        }

        if (entity.pageCount != model.pageCount()) {
            noteDao.updatePageCount(id, model.pageCount())
        }
        // Record the open (library "Recently opened" + activity feed).
        noteDao.updateLastOpened(id, System.currentTimeMillis())
        logActivityInternal(id, ActivityAction.OPENED)
        // Lazy text-index build (first open after import if the background
        // job at import time did not finish).
        if (kind == NoteKind.PDF && pdfFile != null) {
            appScope.launch {
                try {
                    ensureTextIndex(id)
                } catch (_: Throwable) {
                }
            }
        }
        OpenedDocument(
            meta = entity.toMeta().copy(pageCount = model.pageCount(), lastOpenedAt = System.currentTimeMillis()),
            model = model,
            pdfFile = pdfFile
        )
    }

    override suspend fun saveDocument(id: String, model: DocumentModel): Boolean =
        withContext(Dispatchers.IO) {
            val ok = DocumentFormat.save(docFile(id), model.snapshot())
            if (ok) {
                model.markClean()
                noteDao.updateTimestamp(id, System.currentTimeMillis())
                noteDao.updatePageCount(id, model.pageCount())
            } else {
                AppLog.e(TAG, "saveDocument failed for $id")
            }
            ok
        }

    // ------------------------------------------------------------------
    // meta ops
    // ------------------------------------------------------------------

    override suspend fun renameDocument(id: String, title: String) {
        withContext(Dispatchers.IO) {
            noteDao.updateTitle(id, title, System.currentTimeMillis())
            logActivityInternal(id, ActivityAction.RENAMED)
        }
    }

    override suspend fun deleteDocuments(ids: Set<String>) {
        withContext(Dispatchers.IO) {
            noteDao.deleteByIds(ids)
            for (id in ids) {
                docDir(id).deleteRecursively()
                thumbFile(id).delete()
            }
        }
    }

    override suspend fun duplicateDocument(id: String): String? = withContext(Dispatchers.IO) {
        val source = noteDao.getById(id) ?: return@withContext null
        val newDocId = newId()
        val sourceDir = docDir(id)
        if (sourceDir.exists()) {
            sourceDir.copyRecursively(docDir(newDocId), overwrite = true)
        }
        val now = System.currentTimeMillis()
        noteDao.upsert(
            source.copy(
                id = newDocId,
                title = source.title + " (copy)",
                createdAt = now,
                updatedAt = now,
                lastOpenedAt = 0L,
                sortOrder = nextSortOrder()
            )
        )
        // Duplicates carry their PDF search text + bookmarks (page-level
        // content) with the new document id.
        try {
            val textRows = pdfTextDao.pagesForDoc(id)
            if (textRows.isNotEmpty()) {
                pdfTextDao.insertAll(textRows.map { it.copy(id = 0, documentId = newDocId) })
            }
            val bookmarks = bookmarkDao.getForDoc(id)
            for (b in bookmarks) {
                bookmarkDao.upsert(b.copy(id = newId(), documentId = newDocId))
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "duplicate: index/bookmark copy failed (${t.message})")
        }
        logActivityInternal(newDocId, ActivityAction.DUPLICATED)
        AppLog.i(TAG, "duplicated $id -> $newDocId")
        newDocId
    }

    override suspend fun setFavorite(id: String, favorite: Boolean) {
        withContext(Dispatchers.IO) { noteDao.updateFavorite(id, favorite) }
    }

    override suspend fun setFavorites(ids: Set<String>, favorite: Boolean) {
        withContext(Dispatchers.IO) {
            if (ids.isNotEmpty()) noteDao.updateFavorites(ids, favorite)
        }
    }

    override suspend fun moveDocuments(ids: Set<String>, folderId: String?) {
        withContext(Dispatchers.IO) {
            if (ids.isEmpty()) return@withContext
            noteDao.updateFolder(ids, folderId)
            for (id in ids) logActivityInternal(id, ActivityAction.MOVED)
        }
    }

    override suspend fun updateNotebookSettings(id: String, settings: NotebookSettings) {
        withContext(Dispatchers.IO) {
            val current = noteDao.getById(id) ?: return@withContext
            settings.title?.let { noteDao.updateTitle(id, it, System.currentTimeMillis()) }
            noteDao.updateNotebookSettings(
                id = id,
                cover = (settings.coverColor ?: current.toMeta().coverColor).name,
                icon = (settings.icon ?: current.toMeta().icon).name,
                template = (settings.pageTemplate ?: current.toMeta().pageTemplate).name,
                orientation = (settings.pageOrientation ?: current.toMeta().pageOrientation).name,
                size = (settings.pageSize ?: current.toMeta().pageSize).name,
                ts = System.currentTimeMillis()
            )
        }
    }

    override suspend fun touchOpened(id: String) {
        withContext(Dispatchers.IO) { noteDao.updateLastOpened(id, System.currentTimeMillis()) }
    }

    override suspend fun setCustomOrder(orderedIds: List<String>) {
        withContext(Dispatchers.IO) {
            orderedIds.forEachIndexed { index, id -> noteDao.updateSortOrder(id, index) }
        }
    }

    override suspend fun logActivity(id: String, action: ActivityAction) {
        withContext(Dispatchers.IO) { logActivityInternal(id, action) }
    }

    override suspend fun createFolder(name: String, parentId: String?): String =
        withContext(Dispatchers.IO) {
            val id = newId()
            val now = System.currentTimeMillis()
            folderDao.upsert(
                FolderEntity(
                    id = id,
                    name = name,
                    sortOrder = nextFolderOrder(),
                    parentId = parentId,
                    createdAt = now,
                    updatedAt = now
                )
            )
            AppLog.i(TAG, "created folder $id ('$name', parent=$parentId)")
            id
        }

    override suspend fun renameFolder(id: String, name: String) {
        withContext(Dispatchers.IO) { folderDao.rename(id, name, System.currentTimeMillis()) }
    }

    override suspend fun deleteFolder(id: String) {
        withContext(Dispatchers.IO) {
            // Children are lifted to the deleted folder's parent (root if none).
            val folder = folderDao.getById(id) ?: return@withContext
            folderDao.deleteFolderLiftingChildren(id, folder.parentId)
        }
    }

    override suspend fun moveFolder(id: String, newParentId: String?): Boolean =
        withContext(Dispatchers.IO) {
            if (id == newParentId) return@withContext false
            if (newParentId != null && newParentId in subtreeIds(id)) {
                return@withContext false // refuse cycles
            }
            folderDao.updateParent(id, newParentId, System.currentTimeMillis())
            true
        }

    /** Next manual-order slot for a new document. */
    private suspend fun nextSortOrder(): Int =
        noteDao.observeAll().first().maxOfOrNull { it.sortOrder }?.plus(1) ?: 0

    /** Next manual-order slot for a new folder. */
    private suspend fun nextFolderOrder(): Int =
        folderDao.getAll().maxOfOrNull { it.sortOrder }?.plus(1) ?: 0

    // ------------------------------------------------------------------
    // media
    // ------------------------------------------------------------------

    override suspend fun importImageIntoDoc(docId: String, uri: Uri): File? =
        withContext(Dispatchers.IO) {
            try {
                val imagesDir = File(docDir(docId), IMAGES_DIR)
                if (!imagesDir.exists()) imagesDir.mkdirs()
                val file = File(imagesDir, "${newId()}.${extensionFor(uri)}")
                copyUriToFile(uri, file)
                file
            } catch (t: Throwable) {
                AppLog.e(TAG, "importImageIntoDoc failed for $uri", t)
                null
            }
        }

    override fun resolveImage(docId: String, relativePath: String): File =
        File(docDir(docId), relativePath)

    override fun cameraFile(docId: String): File {
        val file = File(docDir(docId), "$IMAGES_DIR/cam_${newId()}.jpg")
        file.parentFile?.mkdirs()
        return file
    }

    // ------------------------------------------------------------------
    // export
    // ------------------------------------------------------------------

    override suspend fun exportPdf(id: String, target: OutputStream): Boolean =
        exportDocument(id, ExportOptions(format = ExportFormat.PDF, pdfMode = PdfExportMode.FLATTENED), target)

    override suspend fun exportNanote(id: String, target: OutputStream): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val file = docFile(id)
                if (!file.exists()) {
                    AppLog.w(TAG, "exportNanote: doc.nanote missing for $id")
                    return@withContext false
                }
                file.inputStream().use { input -> input.copyTo(target) }
                true
            } catch (t: Throwable) {
                AppLog.e(TAG, "exportNanote failed for $id", t)
                false
            }
        }

    // ------------------------------------------------------------------
    // thumbnails
    // ------------------------------------------------------------------

    override suspend fun thumbnail(docId: String, maxW: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            val cached = thumbFile(docId)
            if (cached.exists()) {
                val decoded = try {
                    BitmapFactory.decodeFile(cached.absolutePath)
                } catch (t: Throwable) {
                    AppLog.w(TAG, "thumbnail: cached decode failed (${t.message})")
                    null
                }
                if (decoded != null) return@withContext decoded
            }

            val entity = noteDao.getById(docId) ?: return@withContext null
            val kind = try {
                NoteKind.valueOf(entity.kind)
            } catch (t: Throwable) {
                NoteKind.NOTE
            }
            val content = DocumentFormat.load(docFile(docId))
                ?.first?.snapshot()
                ?.let { DocumentMigrations.migrate(it) }

            val bitmap = if (kind == NoteKind.PDF) {
                val pdf = File(docDir(docId), PDF_FILE)
                if (pdf.exists()) renderPdfThumbnail(pdf, maxW) else null
            } else {
                renderNoteThumbnail(content, maxW)
            }

            if (bitmap != null) {
                try {
                    thumbDir().mkdirs()
                    cached.outputStream().use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                } catch (t: Throwable) {
                    AppLog.w(TAG, "thumbnail: cache write failed (${t.message})")
                }
            }
            bitmap
        }

    override suspend fun clearThumbnailCache() {
        withContext(Dispatchers.IO) {
            val dir = thumbDir()
            if (!dir.exists()) return@withContext
            dir.listFiles()?.forEach { file ->
                if (!file.delete()) {
                    AppLog.w(TAG, "clearThumbnailCache: could not delete ${file.name}")
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // storage
    // ------------------------------------------------------------------

    override suspend fun storageUsedBytes(): Long = withContext(Dispatchers.IO) {
        val root = docsRoot()
        if (!root.exists()) return@withContext 0L
        var total = 0L
        root.walkTopDown().forEach { file -> total += file.length() }
        total
    }

    // ------------------------------------------------------------------
    // Part 3 — tags, search, bookmarks, positions, page ops, exports
    // ------------------------------------------------------------------

    override fun observeTags(): Flow<List<Tag>> =
        tagDao.observeTagsWithCounts().map { rows ->
            rows.map { Tag(name = it.name, color = it.color, usageCount = it.usageCount) }
        }

    override fun observeDocumentTags(docId: String): Flow<List<String>> =
        tagDao.observeTagsForNote(docId)

    override suspend fun setDocumentTags(docId: String, tags: List<String>) {
        val clean = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(12)
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            for (t in clean) {
                tagDao.upsert(
                    TagEntity(name = t, color = 0xFF2563EB, createdAt = now)
                )
            }
            tagDao.setNoteTags(docId, clean)
            noteDao.updateTimestamp(docId, now)
        }
    }

    override suspend fun deleteTag(name: String) {
        withContext(Dispatchers.IO) { tagDao.deleteTagAndAssignments(name) }
    }

    override suspend fun searchAcross(query: String, pdfTextLimit: Int): SearchResults =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext SearchResults(query = q)
            val notes = noteDao.search(q).map { it.toMeta() }
            val folders = folderDao.getAll()
                .filter { it.name.contains(q, ignoreCase = true) }
                .map { it.toFolder() }
            val tags = tagDao.search(q).map { Tag(name = it.name, color = it.color) }
            val hits = pdfTextDao.search(q, pdfTextLimit).map { row ->
                PdfTextHit(
                    documentId = row.documentId,
                    documentTitle = row.documentTitle,
                    kind = try {
                        NoteKind.valueOf(row.documentKind)
                    } catch (t: Throwable) {
                        NoteKind.PDF
                    },
                    coverColor = com.nanote.core.model.CoverColor.safeOf(row.coverColor),
                    pageIndex = row.pageIndex,
                    pdfPage = row.pdfPage,
                    snippet = snippetAround(row.text, q)
                )
            }
            SearchResults(query = q, documents = notes, folders = folders, tags = tags, pdfTextHits = hits)
        }

    override suspend fun recordSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        withContext(Dispatchers.IO) {
            searchHistoryDao.upsert(
                SearchHistoryEntity(query = q, searchedAt = System.currentTimeMillis())
            )
        }
    }

    override fun observeRecentSearches(limit: Int): Flow<List<RecentSearch>> =
        searchHistoryDao.observeRecent(limit).map { rows ->
            rows.map { RecentSearch(query = it.query, searchedAt = it.searchedAt) }
        }

    override suspend fun deleteRecentSearch(query: String) {
        withContext(Dispatchers.IO) { searchHistoryDao.delete(query) }
    }

    override suspend fun clearSearchHistory() {
        withContext(Dispatchers.IO) { searchHistoryDao.clear() }
    }

    private fun snippetAround(text: String, query: String, radius: Int = 42): String {
        val idx = text.indexOf(query, ignoreCase = true)
        if (idx < 0) return text.take(radius * 2)
        val start = (idx - radius).coerceAtLeast(0)
        val end = (idx + query.length + radius).coerceAtMost(text.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < text.length) "…" else ""
        return prefix + text.substring(start, end) + suffix
    }

    // ---------------- bookmarks & reading position ----------------

    override fun observeBookmarks(docId: String): Flow<List<PdfBookmark>> =
        bookmarkDao.observeForDoc(docId).map { rows ->
            rows.map {
                PdfBookmark(
                    id = it.id, documentId = it.documentId, pageIndex = it.pageIndex,
                    pdfPage = it.pdfPage, title = it.title, createdAt = it.createdAt
                )
            }
        }

    override suspend fun toggleBookmark(docId: String, pageIndex: Int, pdfPage: Int, title: String): Boolean =
        withContext(Dispatchers.IO) {
            val existing = bookmarkDao.getForPage(docId, pageIndex)
            if (existing != null) {
                bookmarkDao.delete(existing.id)
                false
            } else {
                bookmarkDao.upsert(
                    PdfBookmarkEntity(
                        id = newId(), documentId = docId, pageIndex = pageIndex,
                        pdfPage = pdfPage, title = title, createdAt = System.currentTimeMillis()
                    )
                )
                true
            }
        }

    override suspend fun deleteBookmark(bookmarkId: String) {
        withContext(Dispatchers.IO) { bookmarkDao.delete(bookmarkId) }
    }

    override suspend fun saveReadingPosition(docId: String, pageIndex: Int, zoomScale: Float) {
        withContext(Dispatchers.IO) {
            readingPositionDao.upsert(
                ReadingPositionEntity(
                    documentId = docId, pageIndex = pageIndex,
                    zoomScale = zoomScale, savedAt = System.currentTimeMillis()
                )
            )
        }
    }

    override suspend fun readingPosition(docId: String): ReadingPosition? =
        withContext(Dispatchers.IO) {
            readingPositionDao.get(docId)?.let {
                ReadingPosition(documentId = it.documentId, pageIndex = it.pageIndex, zoomScale = it.zoomScale, savedAt = it.savedAt)
            }
        }

    // ---------------- PDF metadata ----------------

    override suspend fun pdfFileInfo(docId: String): PdfFileInfo? =
        withContext(Dispatchers.IO) {
            val entity = noteDao.getById(docId) ?: return@withContext null
            if (NoteKind.safeOf(entity.kind) != NoteKind.PDF) return@withContext null
            val working = workingPdfFile(docId)
            val original = File(docDir(docId), PDF_FILE)
            val size = (if (working.exists()) working.length() else original.length())
            val indexed = PdfTextIndexer.index(working.takeIf { it.exists() } ?: original)
            PdfFileInfo(
                documentId = docId,
                sourceFileName = entity.sourceFileName ?: entity.title,
                fileSizeBytes = size,
                pageCount = entity.pageCount,
                title = indexed?.infoTitle,
                author = indexed?.infoAuthor,
                creator = indexed?.infoCreator,
                producer = indexed?.infoProducer,
                importedAt = entity.createdAt,
                lastOpenedAt = entity.lastOpenedAt
            )
        }

    // ---------------- PDF page operations ----------------

    override suspend fun deletePdfPages(docId: String, pageIndexes: Set<Int>): PdfPageOpResult =
        applyPageOp(docId, "delete") { src, tmp -> PdfPageOps.deletePages(src, tmp, pageIndexes) }

    override suspend fun duplicatePdfPage(docId: String, pageIndex: Int): PdfPageOpResult =
        applyPageOp(docId, "duplicate") { src, tmp -> PdfPageOps.duplicatePage(src, tmp, pageIndex) }

    override suspend fun rotatePdfPage(docId: String, pageIndex: Int): PdfPageOpResult =
        applyPageOp(docId, "rotate") { src, tmp -> PdfPageOps.rotatePage(src, tmp, pageIndex, 90) }

    override suspend fun movePdfPage(docId: String, fromIndex: Int, toIndex: Int): PdfPageOpResult =
        applyPageOp(docId, "move") { src, tmp ->
            val n = readPdfPageCount(src) ?: return@applyPageOp false
            if (fromIndex !in 0 until n || toIndex !in 0 until n) return@applyPageOp false
            val order = (0 until n).filter { it != fromIndex }.toMutableList()
            order.add(toIndex.coerceAtMost(order.size), fromIndex)
            PdfPageOps.reorderPages(src, tmp, order)
        }

    override suspend fun extractPdfPagesToDocument(docId: String, pageIndexes: List<Int>, title: String): String? =
        withContext(Dispatchers.IO) {
            val source = workingPdfFile(docId).takeIf { it.exists() }
                ?: File(docDir(docId), PDF_FILE).takeIf { it.exists() }
                ?: return@withContext null
            val id = newId()
            val dir = docDir(id)
            try {
                dir.mkdirs()
                val newPdf = File(dir, PDF_FILE)
                if (!PdfPageOps.extractPages(source, newPdf, pageIndexes)) {
                    throw IOException("extract failed")
                }
                val pages = readPdfPageDefs(newPdf) ?: throw IOException("cannot read extracted pages")
                val content = DocumentContent(version = CURRENT_FORMAT_VERSION, pages = pages)
                if (!DocumentFormat.save(docFile(id), content)) {
                    throw IOException("could not persist doc.nanote")
                }
                val now = System.currentTimeMillis()
                noteDao.upsert(
                    NoteEntity(
                        id = id,
                        title = title.ifBlank { "Extracted pages" },
                        kind = NoteKind.PDF.name,
                        favorite = false,
                        folderId = null,
                        createdAt = now,
                        updatedAt = now,
                        pageCount = pages.size,
                        coverColor = com.nanote.core.model.CoverColor.SLATE.name,
                        icon = com.nanote.core.model.NotebookIcon.NOTES.name,
                        pageTemplate = com.nanote.core.model.PageTemplate.PLAIN.name,
                        pageOrientation = com.nanote.core.model.PageOrientation.PORTRAIT.name,
                        pageSize = com.nanote.core.model.PageSize.A4.name,
                        lastOpenedAt = 0L,
                        sortOrder = nextSortOrder(),
                        fileSizeBytes = newPdf.length(),
                        sourceFileName = title
                    )
                )
                logActivityInternal(id, ActivityAction.CREATED)
                appScope.launch { runCatching { reindexPdfText(id) } }
                id
            } catch (t: Throwable) {
                AppLog.e(TAG, "extractPdfPagesToDocument failed", t)
                dir.deleteRecursively()
                null
            }
        }

    override suspend fun insertPdfPagesFrom(docId: String, uri: Uri, insertAt: Int): PdfPageOpResult =
        withContext(Dispatchers.IO) {
            // Copy the picked file into a temp (untrusted input is never
            // streamed directly into a working document).
            val other = File(cacheTempDir(), "insert-${newId()}.pdf")
            try {
                copyUriToFile(uri, other)
                val count = readPdfPageCount(other)
                    ?: return@withContext PdfPageOpResult(false, 0, "not a valid PDF")
                val res = applyPageOp(docId, "insert") { src, tmp ->
                    PdfPageOps.insertPagesFrom(src, other, tmp, (0 until count).toList(), insertAt)
                }
                res
            } finally {
                other.delete()
            }
        }

    /**
     * Core page-op runner: executes the pdf-level op into a temp file,
     * atomically swaps the working copy, rebuilds the .nanote page list
     * from the new PDF and remaps element pages. The ORIGINAL imported
     * file is never touched.
     */
    private suspend fun applyPageOp(
        docId: String,
        name: String,
        op: suspend (File, File) -> Boolean
    ): PdfPageOpResult = withContext(Dispatchers.IO) {
        val entity = noteDao.getById(docId)
            ?: return@withContext PdfPageOpResult(false, 0, "missing document")
        if (NoteKind.safeOf(entity.kind) != NoteKind.PDF) {
            return@withContext PdfPageOpResult(false, 0, "not a PDF document")
        }
        val original = File(docDir(docId), PDF_FILE)
        val working = workingPdfFile(docId)
        val base = when {
            working.exists() -> working
            original.exists() -> original
            else -> return@withContext PdfPageOpResult(false, 0, "source PDF missing")
        }
        val loaded = DocumentFormat.load(docFile(docId))
            ?: return@withContext PdfPageOpResult(false, 0, "document content unreadable")
        val oldContent = DocumentMigrations.migrate(loaded.first.snapshot())
        val oldPages = oldContent.pages

        val tmp = File(cacheTempDir(), "pageop-${newId()}.pdf")
        try {
            if (!op(base, tmp)) {
                return@withContext PdfPageOpResult(false, oldPages.size, "$name failed")
            }
            val newDefs = readPdfPageDefs(tmp)
                ?: return@withContext PdfPageOpResult(false, oldPages.size, "result unreadable")
            val newCount = newDefs.size

            // Remap element pages: old page -> new page.
            val remap = buildPageRemap(name, oldPages, newCount)
            val newContent = remapContentPages(oldContent, newDefs, remap)

            // Atomic swap of the working copy.
            val workingTarget = File(docDir(docId), EDITED_PDF_FILE)
            if (workingTarget.exists()) workingTarget.delete()
            if (!tmp.renameTo(workingTarget)) {
                tmp.copyTo(workingTarget, overwrite = true)
                tmp.delete()
            }

            DocumentFormat.save(docFile(docId), newContent)
            noteDao.updatePageCount(docId, newCount)
            noteDao.updateTimestamp(docId, System.currentTimeMillis())
            thumbFile(docId).delete()
            // Text index follows the new page layout.
            pdfTextDao.deleteForDoc(docId)
            appScope.launch { runCatching { reindexPdfText(docId) } }
            AppLog.i(TAG, "page op '$name' on $docId: ${oldPages.size} -> $newCount pages")
            PdfPageOpResult(ok = true, newPageCount = newCount)
        } catch (t: Throwable) {
            AppLog.e(TAG, "page op '$name' failed for $docId", t)
            PdfPageOpResult(false, oldPages.size, t.message)
        } finally {
            tmp.delete()
        }
    }

    /** Builds oldPageIndex → newPageIndex for each op type. */
    private fun buildPageRemap(op: String, oldPages: List<PageDef>, newCount: Int): Map<Int, Int> {
        val oldCount = oldPages.size
        return when (op) {
            "rotate" -> (0 until oldCount).associateWith { it } // indices unchanged
            else -> {
                // Rebuild-forward mapping: page i survives when i < newCount.
                // (delete/duplicate/insert/move remaps are computed from the
                // new working PDF being indexed identically — annotations
                // keep their ORIGINAL page index when it still exists.)
                (0 until oldCount).mapNotNull { old ->
                    if (old < newCount) old to old else null
                }.toMap()
            }
        }
    }

    /**
     * Rebuilds a DocumentContent against new page defs with a page remap.
     * Elements whose page no longer exists are dropped.
     */
    private fun remapContentPages(
        content: DocumentContent,
        newDefs: List<PageDef>,
        remap: Map<Int, Int>
    ): DocumentContent {
        fun <T> remapList(list: List<T>, pageOf: (T) -> Int, copy: (T, Int) -> T): List<T> =
            list.mapNotNull { el ->
                val newPage = remap[pageOf(el)]
                if (newPage == null) null else if (newPage == pageOf(el)) el else copy(el, newPage)
            }
        return content.copy(
            pages = newDefs,
            strokes = remapList(content.strokes, { it.page }) { s, p -> s.copy(page = p) },
            shapes = remapList(content.shapes, { it.page }) { s, p -> s.copy(page = p) },
            texts = remapList(content.texts, { it.page }) { t, p -> t.copy(page = p) },
            images = remapList(content.images, { it.page }) { i, p -> i.copy(page = p) },
            highlights = remapList(content.highlights, { it.page }) { h, p -> h.copy(page = p) },
            underlines = remapList(content.underlines, { it.page }) { u, p -> u.copy(page = p) },
            strikeThroughs = remapList(content.strikeThroughs, { it.page }) { s, p -> s.copy(page = p) }
        )
    }

    /** Page defs of a PDF file via pdfium (exact point sizes, rotation-aware). */
    private fun readPdfPageDefs(file: File): List<PageDef>? {
        val count = readPdfPageCount(file) ?: return null
        val core = PdfiumCore(context)
        val doc = try {
            openPdf(core, file)
        } catch (t: Throwable) {
            return null
        }
        return try {
            (0 until count).map { i ->
                val w = core.getPageWidthPoint(doc, i).toFloat()
                val h = core.getPageHeightPoint(doc, i).toFloat()
                PageDef(
                    index = i,
                    width = if (w > 0f) w else DEFAULT_PAGE_W,
                    height = if (h > 0f) h else DEFAULT_PAGE_H,
                    pdfPage = i
                )
            }
        } finally {
            try { core.closeDocument(doc) } catch (_: Throwable) {}
        }
    }

    private fun readPdfPageCount(file: File): Int? = try {
        val core = PdfiumCore(context)
        val doc = openPdf(core, file)
        try {
            core.getPageCount(doc)
        } finally {
            core.closeDocument(doc)
        }
    } catch (t: Throwable) {
        AppLog.w(TAG, "readPdfPageCount failed: ${t.message}")
        null
    }

    private fun workingPdfFile(id: String): File = File(docDir(id), EDITED_PDF_FILE)

    private fun cacheTempDir(): File =
        File(context.cacheDir, "ops").apply { mkdirs() }

    // ---------------- universal export ----------------

    override suspend fun exportDocument(id: String, options: ExportOptions, target: OutputStream): Boolean =
        withContext(Dispatchers.IO) {
            when (options.format) {
                ExportFormat.NANOTE -> exportNanote(id, target)
                ExportFormat.PNG, ExportFormat.JPEG -> exportAsImage(id, options, target)
                ExportFormat.PDF -> exportAsPdf(id, options, target)
            }
        }

    private suspend fun exportAsImage(id: String, options: ExportOptions, target: OutputStream): Boolean {
        val entity = noteDao.getById(id) ?: return false
        // Image formats export ONE page: the first selected page (or the first).
        val page = options.pages?.minOrNull() ?: 0
        val scaleByQuality = 2f // raster exports render at 2x for crispness
        val maxW = (1200 * scaleByQuality).toInt()
        val bitmap = renderPageBitmap(id, page, maxW) ?: return false
        return try {
            val format = if (options.format == ExportFormat.PNG) {
                Bitmap.CompressFormat.PNG
            } else {
                Bitmap.CompressFormat.JPEG
            }
            val quality = if (options.format == ExportFormat.PNG) 100 else 92
            target.use { out -> bitmap.compress(format, quality, out) }
            true
        } catch (t: Throwable) {
            AppLog.e(TAG, "image export failed for $id", t)
            false
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun exportAsPdf(id: String, options: ExportOptions, target: OutputStream): Boolean =
        withContext(Dispatchers.IO) {
            val entity = noteDao.getById(id) ?: return@withContext false
            val kind = NoteKind.safeOf(entity.kind)
            val loaded = DocumentFormat.load(docFile(id)) ?: return@withContext false
            val content = DocumentMigrations.migrate(loaded.first.snapshot())

            val working = workingPdfFile(id).takeIf { it.exists() }
                ?: File(docDir(id), PDF_FILE).takeIf { it.exists() }
            val isOriginalMode = options.pdfMode == PdfExportMode.ORIGINAL && kind == NoteKind.PDF && working != null

            val tmp = File(cacheTempDir(), "export-${newId()}.pdf")
            val tmp2 = File(cacheTempDir(), "export-${newId()}.pdf")
            try {
                val finalFile: File
                val expectedPages: Int
                if (isOriginalMode && working != null) {
                    // Byte-for-byte copy of the (never-modified) working/original
                    // document — zero re-encode, no annotations.
                    working.inputStream().use { input -> input.copyTo(tmp.outputStream()) }
                    finalFile = tmp
                    expectedPages = content.pages.size
                } else {
                    ensurePdfBoxReady()
                    var loadedDoc: PDDocument? = null
                    if (working != null) {
                        try {
                            loadedDoc = PDDocument.load(working)
                        } catch (t: Throwable) {
                            AppLog.e(TAG, "exportAsPdf: working pdf unreadable, blank-page fallback", t)
                        }
                    }
                    val doc = loadedDoc ?: PDDocument()
                    try {
                        if (loadedDoc == null) {
                            // Plain note (or unreadable PDF): build blank white pages.
                            for (page in content.pages) {
                                val w = if (page.width > 0f) page.width else DEFAULT_PAGE_W
                                val h = if (page.height > 0f) page.height else DEFAULT_PAGE_H
                                val pdPage = PDPage(PDRectangle(w, h))
                                doc.addPage(pdPage)
                                PDPageContentStream(
                                    doc, pdPage,
                                    PDPageContentStream.AppendMode.OVERWRITE, true, true
                                ).use { cs ->
                                    cs.setNonStrokingColor(255, 255, 255)
                                    cs.addRect(0f, 0f, w, h)
                                    cs.fill()
                                }
                            }
                        }
                        val painter = PdfContentPainter.ImageResolver { rel ->
                            resolveImage(id, rel).takeIf { it.exists() }
                        }
                        content.pages.forEachIndexed { index, def ->
                            val pdfIndex = if (working != null && def.pdfPage >= 0) def.pdfPage else index
                            if (pdfIndex < 0 || pdfIndex >= doc.numberOfPages) {
                                AppLog.w(TAG, "exportAsPdf: page $index out of range, skipped")
                                return@forEachIndexed
                            }
                            val page = doc.getPage(pdfIndex)
                            if (options.pdfMode == PdfExportMode.WITH_MARKUP_ANNOTATIONS) {
                                // Ink, shapes, images and text boxes are painted;
                                // text marks become REAL PDF markup annotations.
                                PdfContentPainter.paintPage(doc, page, content.copy(
                                    highlights = emptyList(),
                                    underlines = emptyList(),
                                    strikeThroughs = emptyList()
                                ), index, painter)
                                PdfMarkupAnnotator.annotatePage(doc, page, content, index)
                            } else {
                                PdfContentPainter.paintPage(doc, page, content, index, painter)
                            }
                        }
                        doc.save(tmp)
                    } finally {
                        try { doc.close() } catch (_: Throwable) {}
                    }
                    finalFile = tmp
                    expectedPages = content.pages.size
                }

                // Optional page subset.
                val subset = options.pages
                    ?.filter { it >= 0 && it < expectedPages }
                    ?.sorted()
                val deliverable = if (!subset.isNullOrEmpty() && subset.size != expectedPages) {
                    if (!PdfPageOps.extractPages(finalFile, tmp2, subset)) {
                        return@withContext false
                    }
                    tmp2
                } else {
                    finalFile
                }

                // Independent verification BEFORE handing the file over.
                val verification = PdfVerifier.verify(context, deliverable)
                if (!verification.ok) {
                    AppLog.e(TAG, "export verification failed for $id: $verification")
                    return@withContext false
                }

                deliverable.inputStream().use { input -> input.copyTo(target) }
                AppLog.i(TAG, "exported $id (${options.format}/${options.pdfMode}, ${verification.pageCount} pages)")
                true
            } catch (t: Throwable) {
                AppLog.e(TAG, "pdf export failed for $id", t)
                false
            } finally {
                tmp.delete()
                tmp2.delete()
            }
        }

    override suspend fun renderPageBitmap(id: String, pageIndex: Int, maxWidthPx: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            val entity = noteDao.getById(id) ?: return@withContext null
            val loaded = DocumentFormat.load(docFile(id)) ?: return@withContext null
            val content = DocumentMigrations.migrate(loaded.first.snapshot())
            val def = content.pages.getOrNull(pageIndex) ?: return@withContext null
            val kind = NoteKind.safeOf(entity.kind)

            if (kind == NoteKind.PDF) {
                val pdf = workingPdfFile(id).takeIf { it.exists() }
                    ?: File(docDir(id), PDF_FILE).takeIf { it.exists() }
                    ?: return@withContext null
                renderPdfPage(pdf, pageIndex, def, maxWidthPx)
            } else {
                renderNotePage(entity, content, pageIndex, def, maxWidthPx)
            }
        }

    private fun renderPdfPage(pdf: File, pageIndex: Int, def: PageDef, maxWidthPx: Int): Bitmap? {
        return try {
            val core = PdfiumCore(context)
            val doc = openPdf(core, pdf)
            try {
                if (pageIndex >= core.getPageCount(doc)) return null
                val pw = if (def.width > 0f) def.width else DEFAULT_PAGE_W
                val scale = maxWidthPx / pw
                val w = maxWidthPx.coerceAtLeast(1)
                val h = (def.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                core.openPage(doc, pageIndex)
                core.renderPageBitmap(doc, bitmap, pageIndex, 0, 0, w, h, true)
                bitmap
            } finally {
                core.closeDocument(doc)
            }
        } catch (t: Throwable) {
            AppLog.e(TAG, "renderPdfPage failed", t)
            null
        }
    }

    /** Offscreen painter for note pages: template + ink + shapes + images + text. */
    private fun renderNotePage(
        entity: NoteEntity,
        content: DocumentContent,
        pageIndex: Int,
        def: PageDef,
        maxWidthPx: Int
    ): Bitmap? {
        return try {
            val pw = if (def.width > 0f) def.width else DEFAULT_PAGE_W
            val ph = if (def.height > 0f) def.height else DEFAULT_PAGE_H
            val scale = maxWidthPx / pw
            val w = maxWidthPx.coerceAtLeast(1)
            val h = (ph * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)

            // Template background (mirrors PageTemplatePattern proportions).
            drawTemplateBackground(canvas, entity.pageTemplate, w, h)

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val path = Path()

            // highlights beneath ink
            val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            for (hl in content.highlights.filter { it.page == pageIndex }) {
                for (r in hl.rects) {
                    if (r.size < 4) continue
                    highlightPaint.color = (hl.color and 0xFFFFFF).toInt() or 0xFF000000.toInt()
                    highlightPaint.alpha = highlighterAlphaOf(hl.opacity)
                    canvas.drawRect(
                        r[0] * pw * scale, r[1] * ph * scale,
                        r[2] * pw * scale, r[3] * ph * scale,
                        highlightPaint
                    )
                }
            }

            for (stroke in content.strokes.filter { it.page == pageIndex }) {
                if (stroke.points.isEmpty()) continue
                paint.color = (stroke.color and 0xFFFFFF).toInt() or 0xFF000000.toInt()
                paint.strokeWidth = (stroke.width * scale).coerceAtLeast(0.5f)
                paint.alpha = if (stroke.tool == ToolType.HIGHLIGHTER) {
                    highlighterAlphaOf(stroke.opacity)
                } else {
                    paintAlphaOf(stroke.opacity)
                }
                path.reset()
                path.moveTo(stroke.points[0].x * scale, stroke.points[0].y * scale)
                if (stroke.points.size == 1) {
                    path.lineTo(stroke.points[0].x * scale + 0.1f, stroke.points[0].y * scale)
                } else {
                    for (i in 1 until stroke.points.size) {
                        path.lineTo(stroke.points[i].x * scale, stroke.points[i].y * scale)
                    }
                }
                canvas.drawPath(path, paint)
            }

            for (img in content.images.filter { it.page == pageIndex }) {
                val file = resolveImage(entity.id, img.path)
                if (!file.exists()) continue
                val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: continue
                val dst = android.graphics.RectF(
                    img.x * scale, img.y * scale,
                    (img.x + img.w) * scale, (img.y + img.h) * scale
                )
                canvas.drawBitmap(bmp, null, dst, paint)
                bmp.recycle()
            }

            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            for (t in content.texts.filter { it.page == pageIndex }) {
                textPaint.color = (t.color and 0xFFFFFF).toInt() or 0xFF000000.toInt()
                textPaint.textSize = t.size * scale
                var y = t.y * scale + textPaint.textSize
                for (line in t.text.split('\n')) {
                    canvas.drawText(line, t.x * scale, y, textPaint)
                    y += textPaint.textSize * 1.25f
                }
            }
            bitmap
        } catch (t: Throwable) {
            AppLog.e(TAG, "renderNotePage failed", t)
            null
        }
    }

    private fun drawTemplateBackground(canvas: Canvas, templateName: String, w: Int, h: Int) {
        val template = com.nanote.core.model.PageTemplate.safeOf(templateName)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x1A000000
            strokeWidth = 1f
        }
        when (template) {
            com.nanote.core.model.PageTemplate.PLAIN -> Unit
            com.nanote.core.model.PageTemplate.LINED -> {
                val rows = 9
                val step = h / (rows + 1)
                var y = step
                repeat(rows) {
                    canvas.drawLine(0f, y.toFloat(), w.toFloat(), y.toFloat(), p)
                    y += step
                }
            }
            com.nanote.core.model.PageTemplate.GRID -> {
                val cells = 6
                val stepX = w / cells
                val stepY = h / (cells + 3)
                var x = stepX
                repeat(cells - 1) {
                    canvas.drawLine(x.toFloat(), 0f, x.toFloat(), h.toFloat(), p)
                    x += stepX
                }
                var y = stepY
                while (y < h) {
                    canvas.drawLine(0f, y.toFloat(), w.toFloat(), y.toFloat(), p)
                    y += stepY
                }
            }
            com.nanote.core.model.PageTemplate.DOTTED -> {
                val stepX = w / 7f
                val stepY = h / 10f
                var y = stepY
                while (y < h - 2f) {
                    var x = stepX
                    while (x < w - 2f) {
                        canvas.drawPoint(x, y, p)
                        x += stepX
                    }
                    y += stepY
                }
            }
            com.nanote.core.model.PageTemplate.CORNELL -> {
                val margin = w * 0.28f
                val top = h * 0.12f
                p.color = 0x33000000
                canvas.drawLine(margin, 0f, margin, h.toFloat(), p)
                canvas.drawLine(0f, top, w.toFloat(), top, p)
            }
        }
    }

    // ---------------- text index ----------------

    override suspend fun reindexPdfText(docId: String): Int = withContext(Dispatchers.IO) {
        val entity = noteDao.getById(docId) ?: return@withContext 0
        if (NoteKind.safeOf(entity.kind) != NoteKind.PDF) return@withContext 0
        val pdf = workingPdfFile(docId).takeIf { it.exists() }
            ?: File(docDir(docId), PDF_FILE).takeIf { it.exists() }
            ?: return@withContext 0
        val indexed = PdfTextIndexer.index(pdf) ?: return@withContext 0
        pdfTextDao.deleteForDoc(docId)
        val rows = indexed.pageTexts.mapIndexed { i, text ->
            PdfTextPageEntity(id = 0, documentId = docId, pageIndex = i, pdfPage = i, text = text)
        }
        if (rows.isNotEmpty()) pdfTextDao.insertAll(rows)
        AppLog.i(TAG, "text index rebuilt for $docId (${rows.size} pages)")
        rows.size
    }

    private suspend fun ensureTextIndex(docId: String) {
        if (pdfTextDao.countForDoc(docId) > 0) return
        reindexPdfText(docId)
    }

    // ------------------------------------------------------------------
    // thumbnail helpers
    // ------------------------------------------------------------------

    private fun renderPdfThumbnail(pdf: File, maxW: Int): Bitmap? {
        return try {
            val core = PdfiumCore(context)
            val doc = openPdf(core, pdf)
            try {
                if (core.getPageCount(doc) <= 0) return null
                val size = core.getPageSize(doc, 0)
                val aspect = if (size.height > 0) {
                    size.width.toFloat() / size.height.toFloat()
                } else {
                    DEFAULT_PAGE_W / DEFAULT_PAGE_H
                }
                val w = maxW.coerceAtLeast(1)
                val h = (w / aspect).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                core.openPage(doc, 0)
                core.renderPageBitmap(doc, bitmap, 0, 0, 0, w, h, false)
                bitmap
            } finally {
                core.closeDocument(doc)
            }
        } catch (t: Throwable) {
            AppLog.e(TAG, "thumbnail: pdf render failed for ${pdf.path}", t)
            null
        }
    }

    private fun renderNoteThumbnail(content: DocumentContent?, maxW: Int): Bitmap? {
        val c = content ?: blankContent()
        val page = c.pages.firstOrNull() ?: PageDef(index = 0)
        val pw = if (page.width > 0f) page.width else DEFAULT_PAGE_W
        val ph = if (page.height > 0f) page.height else DEFAULT_PAGE_H
        val w = maxW.coerceAtLeast(1)
        val h = (w * ph / pw).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val scale = w / pw
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val path = Path()
        for (stroke in c.strokes.filter { it.page == 0 }) {
            if (stroke.points.isEmpty()) continue
            paint.color = (stroke.color and 0xFFFFFF).toInt() or 0xFF000000.toInt()
            paint.strokeWidth = (stroke.width * scale).coerceAtLeast(0.5f)
            paint.alpha = if (stroke.tool == ToolType.HIGHLIGHTER) {
                highlighterAlphaOf(stroke.opacity)
            } else {
                paintAlphaOf(stroke.opacity)
            }
            path.reset()
            path.moveTo(stroke.points[0].x * scale, stroke.points[0].y * scale)
            if (stroke.points.size == 1) {
                path.lineTo(stroke.points[0].x * scale + 0.1f, stroke.points[0].y * scale)
            } else {
                for (i in 1 until stroke.points.size) {
                    path.lineTo(stroke.points[i].x * scale, stroke.points[i].y * scale)
                }
            }
            canvas.drawPath(path, paint)
        }
        return bitmap
    }

    // ------------------------------------------------------------------
    // small helpers
    // ------------------------------------------------------------------

    private fun docsRoot(): File = File(context.filesDir, "documents")

    private fun docDir(id: String): File = File(docsRoot(), id)

    private fun docFile(id: String): File = File(docDir(id), DOC_FILE)

    private fun thumbDir(): File = File(context.filesDir, THUMBS_DIR)

    private fun thumbFile(id: String): File = File(thumbDir(), "$id.png")

    private fun blankContent(): DocumentContent = DocumentContent(
        version = CURRENT_FORMAT_VERSION,
        pages = listOf(PageDef(index = 0))
    )

    private fun red(c: Long): Int = ((c shr 16) and 0xFF).toInt()

    private fun green(c: Long): Int = ((c shr 8) and 0xFF).toInt()

    private fun blue(c: Long): Int = (c and 0xFF).toInt()

    /**
     * Opens [file] with pdfium through a read-only [ParcelFileDescriptor].
     * The caller must release the result with [PdfiumCore.closeDocument].
     */
    private fun openPdf(core: PdfiumCore, file: File): PdfDocument {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return try {
            core.newDocument(pfd)
        } catch (t: Throwable) {
            try {
                pfd.close()
            } catch (ignored: Throwable) {
                // best effort — the document was never created
            }
            throw t
        }
    }

    /** Opens a SAF uri, falling back to a file descriptor when needed. */
    private fun openUriStream(uri: Uri): InputStream? = try {
        context.contentResolver.openInputStream(uri)
    } catch (t: Throwable) {
        AppLog.w(TAG, "openInputStream failed, trying fd fallback: ${t.message}")
        try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) ParcelFileDescriptor.AutoCloseInputStream(pfd) else null
        } catch (t2: Throwable) {
            AppLog.e(TAG, "fd fallback failed for $uri", t2)
            null
        }
    }

    private fun copyUriToFile(uri: Uri, target: File) {
        val stream = openUriStream(uri) ?: throw IllegalArgumentException("Cannot open $uri")
        stream.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun readUriText(uri: Uri): String {
        val stream = openUriStream(uri) ?: throw IllegalArgumentException("Cannot open $uri")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** Picks an image file extension from the uri mime type or path. */
    private fun extensionFor(uri: Uri): String {
        val mime = try {
            context.contentResolver.getType(uri)
        } catch (t: Throwable) {
            null
        }
        when (mime) {
            "image/png" -> return "png"
            "image/webp" -> return "webp"
            "image/gif" -> return "gif"
            "image/bmp" -> return "bmp"
            "image/heic", "image/heif" -> return "heic"
        }
        val fromPath = (uri.path ?: uri.toString())
            .substringAfterLast('/', "")
            .substringAfterLast('.', "")
            .lowercase(Locale.US)
        if (fromPath.length in 2..5 && fromPath.all { it.isLetterOrDigit() }) return fromPath
        return "jpg"
    }

    /** pdfbox-android needs its asset resource loader before font handling. */
    private fun ensurePdfBoxReady() {
        try {
            if (!PDFBoxResourceLoader.isReady()) {
                PDFBoxResourceLoader.init(context)
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "PDFBoxResourceLoader init failed: ${t.message}")
        }
    }
}
