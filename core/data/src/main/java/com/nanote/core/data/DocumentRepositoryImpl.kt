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
import com.nanote.core.storage.format.DocumentFormat
import com.nanote.core.storage.format.DocumentMigrations
import com.nanote.core.model.ActivityAction
import com.nanote.core.model.CURRENT_FORMAT_VERSION
import com.nanote.core.model.CreateNotebookRequest
import com.nanote.core.model.DocumentContent
import com.nanote.core.model.DocumentMeta
import com.nanote.core.model.DocumentModel
import com.nanote.core.model.Folder
import com.nanote.core.model.FolderTreeNode
import com.nanote.core.model.HighlightAnnotation
import com.nanote.core.model.ImageElement
import com.nanote.core.model.LibraryFilter
import com.nanote.core.model.NoteKind
import com.nanote.core.model.NotebookSettings
import com.nanote.core.model.PageDef
import com.nanote.core.model.RecentActivityEntry
import com.nanote.core.model.ShapeElement
import com.nanote.core.model.ShapeKind
import com.nanote.core.model.Stroke
import com.nanote.core.model.TextElement
import com.nanote.core.model.ToolType
import com.nanote.core.model.UnderlineAnnotation
import com.nanote.core.model.highlighterAlphaOf
import com.nanote.core.model.newId
import com.nanote.core.model.paintAlphaOf
import com.nanote.core.common.AppLog
import com.nanote.core.database.RecentActivityEntity
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Room + filesystem implementation of [DocumentRepository].
 *
 * Storage layout (under `filesDir`):
 * ```
 * documents/<docId>/doc.nanote    serialized DocumentContent (JSON, atomic writes)
 * documents/<docId>/original.pdf  the imported source PDF, when kind == PDF
 * documents/<docId>/images/       embedded gallery/camera images
 * thumbnails/<docId>.png          first-page thumbnail cache
 * ```
 */
class DocumentRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase
) : DocumentRepository {

    companion object {
        private const val TAG = "DocumentRepository"
        private const val DOC_FILE = "doc.nanote"
        private const val PDF_FILE = "original.pdf"
        private const val IMAGES_DIR = "images"
        private const val THUMBS_DIR = "thumbnails"
        private const val DEFAULT_PAGE_W = 595f
        private const val DEFAULT_PAGE_H = 842f
        private const val ACTIVITY_KEEP = 100
    }

    private val noteDao = db.noteDao()
    private val folderDao = db.folderDao()
    private val activityDao = db.recentActivityDao()
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
                    sortOrder = nextSortOrder()
                )
            )
            logActivityInternal(id, ActivityAction.IMPORTED)
            AppLog.i(TAG, "imported PDF '$title' as $id (${pages.size} pages)")
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
            val f = File(docDir(id), PDF_FILE)
            if (f.exists()) f else null
        } else {
            null
        }

        if (entity.pageCount != model.pageCount()) {
            noteDao.updatePageCount(id, model.pageCount())
        }
        // Record the open (library "Recently opened" + activity feed).
        noteDao.updateLastOpened(id, System.currentTimeMillis())
        logActivityInternal(id, ActivityAction.OPENED)
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
        withContext(Dispatchers.IO) {
            var doc: PDDocument? = null
            try {
                ensurePdfBoxReady()

                val entity = noteDao.getById(id) ?: return@withContext false
                val loaded = DocumentFormat.load(docFile(id)) ?: return@withContext false
                val content = DocumentMigrations.migrate(loaded.first.snapshot())
                val kind = try {
                    NoteKind.valueOf(entity.kind)
                } catch (t: Throwable) {
                    NoteKind.NOTE
                }
                val original = if (kind == NoteKind.PDF) File(docDir(id), PDF_FILE) else null
                val hasOriginal = original != null && original.exists()

                doc = if (hasOriginal) PDDocument.load(original) else PDDocument()
                val document = doc!!

                if (!hasOriginal) {
                    // Plain note (or a PDF-kind import whose source PDF was
                    // not carried over): build blank white pages.
                    for (page in content.pages) {
                        val w = if (page.width > 0f) page.width else DEFAULT_PAGE_W
                        val h = if (page.height > 0f) page.height else DEFAULT_PAGE_H
                        val pdPage = PDPage(PDRectangle(w, h))
                        document.addPage(pdPage)
                        PDPageContentStream(
                            document,
                            pdPage,
                            PDPageContentStream.AppendMode.OVERWRITE,
                            true,
                            true
                        ).use { cs ->
                            cs.setNonStrokingColor(255, 255, 255)
                            cs.addRect(0f, 0f, w, h)
                            cs.fill()
                        }
                    }
                }

                // Flatten every content page onto its target PDF page.
                // PDF coordinates are bottom-left origin while our world
                // coordinates are top-left origin: y_pdf = pageHeight - y_world.
                content.pages.forEachIndexed { index, def ->
                    val pdfIndex = if (hasOriginal && def.pdfPage >= 0) def.pdfPage else index
                    if (pdfIndex < 0 || pdfIndex >= document.numberOfPages) {
                        AppLog.w(TAG, "exportPdf: content page $index out of range, skipped")
                        return@forEachIndexed
                    }
                    val page = document.getPage(pdfIndex)
                    val pageW = page.mediaBox.width
                    val pageH = page.mediaBox.height
                    PDPageContentStream(
                        document,
                        page,
                        PDPageContentStream.AppendMode.APPEND,
                        true,
                        true
                    ).use { cs ->
                        for (h in content.highlights) {
                            if (h.page == index) drawHighlight(cs, h, pageW, pageH)
                        }
                        for (u in content.underlines) {
                            if (u.page == index) drawUnderline(cs, u, pageW, pageH)
                        }
                        for (s in content.shapes) {
                            if (s.page == index) drawShape(cs, s, pageH)
                        }
                        for (img in content.images) {
                            if (img.page == index) drawImage(cs, img, document, id, pageH)
                        }
                        for (txt in content.texts) {
                            if (txt.page == index) drawText(cs, txt, pageH)
                        }
                        for (s in content.strokes) {
                            if (s.page == index) drawStroke(cs, s, pageH)
                        }
                    }
                }

                document.save(target)
                AppLog.i(TAG, "exported $id to PDF (${document.numberOfPages} pages)")
                true
            } catch (t: Throwable) {
                AppLog.e(TAG, "exportPdf failed for $id", t)
                false
            } finally {
                try {
                    doc?.close()
                } catch (t: Throwable) {
                    AppLog.w(TAG, "exportPdf: close failed (${t.message})")
                }
            }
        }

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
    // PDF flattening helpers (pdfbox-android)
    // ------------------------------------------------------------------

    private fun drawHighlight(
        cs: PDPageContentStream,
        h: HighlightAnnotation,
        pageW: Float,
        pageH: Float
    ) {
        try {
            val gs = PDExtendedGraphicsState()
            gs.nonStrokingAlphaConstant = highlighterAlphaOf(h.opacity) / 255f
            cs.setGraphicsStateParameters(gs)
            cs.setNonStrokingColor(red(h.color), green(h.color), blue(h.color))
            for (rect in h.rects) {
                if (rect.size < 4) continue
                // RelRect is [x0, y0, x1, y1] top-left origin in [0..1].
                val x0 = rect[0] * pageW
                val y0 = rect[1] * pageH
                val x1 = rect[2] * pageW
                val y1 = rect[3] * pageH
                if (x1 <= x0 || y1 <= y0) continue
                cs.addRect(x0, pageH - y1, x1 - x0, y1 - y0)
                cs.fill()
            }
            resetNonStrokingAlpha(cs)
        } catch (t: Throwable) {
            AppLog.e(TAG, "exportPdf: highlight ${h.id} failed", t)
        }
    }

    private fun drawUnderline(
        cs: PDPageContentStream,
        u: UnderlineAnnotation,
        pageW: Float,
        pageH: Float
    ) {
        try {
            val gs = PDExtendedGraphicsState()
            gs.strokingAlphaConstant = paintAlphaOf(u.opacity) / 255f
            cs.setGraphicsStateParameters(gs)
            cs.setStrokingColor(red(u.color), green(u.color), blue(u.color))
            cs.setLineWidth(1.5f)
            cs.setLineCapStyle(1) // round
            for (rect in u.rects) {
                if (rect.size < 4) continue
                val x0 = rect[0] * pageW
                val x1 = rect[2] * pageW
                if (x1 <= x0) continue
                val y = pageH - rect[3] * pageH // line at the rect bottom edge
                cs.moveTo(x0, y)
                cs.lineTo(x1, y)
                cs.stroke()
            }
            resetStrokingAlpha(cs)
        } catch (t: Throwable) {
            AppLog.e(TAG, "exportPdf: underline ${u.id} failed", t)
        }
    }

    private fun drawStroke(cs: PDPageContentStream, stroke: Stroke, pageH: Float) {
        if (stroke.points.isEmpty()) return
        val highlighter = stroke.tool == ToolType.HIGHLIGHTER
        try {
            val gs = PDExtendedGraphicsState()
            gs.strokingAlphaConstant =
                (if (highlighter) highlighterAlphaOf(stroke.opacity) else paintAlphaOf(stroke.opacity)) / 255f
            cs.setGraphicsStateParameters(gs)
            cs.setStrokingColor(red(stroke.color), green(stroke.color), blue(stroke.color))
            cs.setLineCapStyle(1)   // round cap
            cs.setLineJoinStyle(1)  // round join
            if (stroke.points.size == 1) {
                val p = stroke.points[0]
                cs.setLineWidth(stroke.width.coerceAtLeast(0.1f))
                cs.moveTo(p.x, pageH - p.y)
                cs.lineTo(p.x + 0.1f, pageH - p.y)
                cs.stroke()
            } else {
                var prev = stroke.points[0]
                for (i in 1 until stroke.points.size) {
                    val cur = stroke.points[i]
                    // Variable pressure: each segment carries its own width.
                    val segWidth = ((prev.p + cur.p) / 2f) * stroke.width
                    cs.setLineWidth(segWidth.coerceAtLeast(0.1f))
                    cs.moveTo(prev.x, pageH - prev.y)
                    cs.lineTo(cur.x, pageH - cur.y)
                    cs.stroke()
                    prev = cur
                }
            }
            resetStrokingAlpha(cs)
        } catch (t: Throwable) {
            AppLog.e(TAG, "exportPdf: stroke ${stroke.id} failed", t)
        }
    }

    private fun drawShape(cs: PDPageContentStream, s: ShapeElement, pageH: Float) {
        try {
            cs.setStrokingColor(red(s.color), green(s.color), blue(s.color))
            cs.setLineWidth(s.width.coerceAtLeast(0.1f))
            cs.setLineCapStyle(1)
            cs.setLineJoinStyle(1)
            val x1 = s.x1
            val y1 = pageH - s.y1
            val x2 = s.x2
            val y2 = pageH - s.y2
            when (s.kind) {
                ShapeKind.LINE -> {
                    cs.moveTo(x1, y1)
                    cs.lineTo(x2, y2)
                    cs.stroke()
                }
                ShapeKind.ARROW -> {
                    cs.moveTo(x1, y1)
                    cs.lineTo(x2, y2)
                    val angle = atan2(y2 - y1, x2 - x1).toDouble()
                    val head = max(12f, s.width * 4f).toDouble()
                    cs.moveTo(x2, y2)
                    cs.lineTo(
                        x2 + (head * cos(angle + 0.75 * Math.PI)).toFloat(),
                        y2 + (head * sin(angle + 0.75 * Math.PI)).toFloat()
                    )
                    cs.moveTo(x2, y2)
                    cs.lineTo(
                        x2 + (head * cos(angle - 0.75 * Math.PI)).toFloat(),
                        y2 + (head * sin(angle - 0.75 * Math.PI)).toFloat()
                    )
                    cs.stroke()
                }
                ShapeKind.RECT -> {
                    val left = min(x1, x2)
                    val right = max(x1, x2)
                    val bottom = min(y1, y2)
                    val top = max(y1, y2)
                    cs.addRect(left, bottom, right - left, top - bottom)
                    cs.stroke()
                }
                ShapeKind.CIRCLE -> {
                    // Ellipse via four cubic Bezier segments (kappa approx).
                    val cx = (x1 + x2) / 2f
                    val cy = (y1 + y2) / 2f
                    val rx = abs(x2 - x1) / 2f
                    val ry = abs(y2 - y1) / 2f
                    val kx = rx * 0.5523f
                    val ky = ry * 0.5523f
                    cs.moveTo(cx + rx, cy)
                    cs.curveTo(cx + rx, cy + ky, cx + kx, cy + ry, cx, cy + ry)
                    cs.curveTo(cx - kx, cy + ry, cx - rx, cy + ky, cx - rx, cy)
                    cs.curveTo(cx - rx, cy - ky, cx - kx, cy - ry, cx, cy - ry)
                    cs.curveTo(cx + kx, cy - ry, cx + rx, cy - ky, cx + rx, cy)
                    cs.closePath()
                    cs.stroke()
                }
                ShapeKind.TRIANGLE -> {
                    val left = min(x1, x2)
                    val right = max(x1, x2)
                    val bottom = min(y1, y2)
                    val top = max(y1, y2)
                    cs.moveTo((x1 + x2) / 2f, top)
                    cs.lineTo(right, bottom)
                    cs.lineTo(left, bottom)
                    cs.closePath()
                    cs.stroke()
                }
            }
        } catch (t: Throwable) {
            AppLog.e(TAG, "exportPdf: shape ${s.id} failed", t)
        }
    }

    private fun drawImage(
        cs: PDPageContentStream,
        img: ImageElement,
        document: PDDocument,
        docId: String,
        pageH: Float
    ) {
        try {
            val file = resolveImage(docId, img.path)
            if (!file.exists()) {
                AppLog.w(TAG, "exportPdf: image ${img.path} missing")
                return
            }
            val xobj = PDImageXObject.createFromFile(file.absolutePath, document)
            val w = if (img.w > 0f) img.w else xobj.width.toFloat()
            val h = if (img.h > 0f) img.h else xobj.height.toFloat()
            cs.drawImage(xobj, img.x, pageH - img.y - h, w, h)
        } catch (t: Throwable) {
            AppLog.e(TAG, "exportPdf: image ${img.id} failed", t)
        }
    }

    private fun drawText(cs: PDPageContentStream, t: TextElement, pageH: Float) {
        try {
            val size = if (t.size > 0f) t.size else 18f
            if (t.sticky) {
                cs.setNonStrokingColor(253, 224, 71) // yellow-300 sticky background
                val w = if (t.width > 0f) t.width else 260f
                cs.addRect(t.x, pageH - t.y - size * 1.6f, w, size * 1.6f)
                cs.fill()
            }
            cs.setNonStrokingColor(red(t.color), green(t.color), blue(t.color))
            cs.beginText()
            cs.setFont(PDType1Font.HELVETICA, size)
            // NOTE: Arabic/RTL shaping is not supported by PDFBox's built-in
            // Type1 fonts; the text is drawn as a single left-to-right run of
            // WinAnsi-safe characters. Proper bidi/shaping requires an
            // Arabic-capable embedded font + layout engine (future work).
            cs.newLineAtOffset(t.x, pageH - t.y - size)
            val safe = winAnsiSafe(t.text)
            if (safe.isNotEmpty()) cs.showText(safe)
            cs.endText()
        } catch (e: Throwable) {
            AppLog.e(TAG, "exportPdf: text ${t.id} failed", e)
        }
    }

    /** Replaces characters Helvetica/WinAnsi cannot encode so showText never throws. */
    private fun winAnsiSafe(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when {
                ch == '\n' || ch == '\r' || ch == '\t' -> sb.append(' ')
                ch.code in 32..255 -> sb.append(ch)
                else -> sb.append('?')
            }
        }
        return sb.toString()
    }

    private fun resetNonStrokingAlpha(cs: PDPageContentStream) {
        try {
            val reset = PDExtendedGraphicsState()
            reset.nonStrokingAlphaConstant = 1f
            cs.setGraphicsStateParameters(reset)
        } catch (t: Throwable) {
            AppLog.w(TAG, "exportPdf: reset gs failed (${t.message})")
        }
    }

    private fun resetStrokingAlpha(cs: PDPageContentStream) {
        try {
            val reset = PDExtendedGraphicsState()
            reset.strokingAlphaConstant = 1f
            cs.setGraphicsStateParameters(reset)
        } catch (t: Throwable) {
            AppLog.w(TAG, "exportPdf: reset gs failed (${t.message})")
        }
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
