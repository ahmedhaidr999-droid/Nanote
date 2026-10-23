package com.nanote.data.repo

import com.nanote.data.db.FolderDao
import com.nanote.data.db.FolderEntity
import com.nanote.data.db.NotebookDao
import com.nanote.data.db.NotebookEntity
import com.nanote.data.db.PageDao
import com.nanote.data.db.PageEntity
import com.nanote.data.db.PageTextDao
import com.nanote.data.db.PageTextEntity
import com.nanote.data.db.TagDao
import com.nanote.data.db.TagEntity
import com.nanote.data.files.PageStore
import com.nanote.di.IoDispatcher
import com.nanote.domain.model.Folder
import com.nanote.domain.model.Notebook
import com.nanote.domain.model.Page
import com.nanote.domain.model.SortOrder
import com.nanote.domain.model.toDomain
import com.nanote.ink.engine.PAGE_SIZES_PT
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for notebook/folder/page metadata (Room) plus the
 * on-disk page files (PageStore). Heavy work runs on Dispatchers.IO.
 */
@Singleton
class NotebookRepository @Inject constructor(
    private val folderDao: FolderDao,
    private val notebookDao: NotebookDao,
    private val pageDao: PageDao,
    private val tagDao: TagDao,
    private val pageTextDao: PageTextDao,
    private val pageStore: PageStore,
    @IoDispatcher private val io: CoroutineDispatcher
) {
    companion object {
        const val TRASH_KEEP_DAYS = 30L
        const val DEFAULT_COVER = 0xFF4356C4.toInt()
        const val DEFAULT_PAPER = "lined_wide"
        const val DEFAULT_SIZE = "A4"

        fun trashCutoff(now: Long = System.currentTimeMillis()): Long =
            now - TRASH_KEEP_DAYS * 24L * 60L * 60L * 1000L
    }

    // ------------------------------------------------------------- folders

    fun observeFolders(parentId: String?): Flow<List<Folder>> =
        folderDao.observeChildren(parentId).map { list -> list.map { it.toDomain() } }

    fun observeTrashedFolders(): Flow<List<Folder>> =
        folderDao.observeTrashed().map { list -> list.map { it.toDomain() } }

    suspend fun folderById(id: String): Folder? =
        withContext(io) { folderDao.byId(id)?.toDomain() }

    /** Breadcrumb chain from root to [folderId] (inclusive), oldest first. */
    fun observeBreadcrumb(folderId: String?): Flow<List<Folder>> = flow {
        val chain = ArrayDeque<Folder>()
        var current = folderId
        var depth = 0
        while (current != null && depth < 16) {
            val f = folderDao.byId(current) ?: break
            chain.addFirst(f.toDomain())
            current = f.parentId
            depth++
        }
        emit(chain.toList())
    }

    suspend fun createFolder(name: String, parentId: String?): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        withContext(io) {
            folderDao.upsert(FolderEntity(id, parentId, name, now, now))
        }
        return id
    }

    suspend fun renameFolder(id: String, name: String) = withContext(io) {
        val f = folderDao.byId(id) ?: return@withContext
        folderDao.upsert(f.copy(name = name, updatedAt = System.currentTimeMillis()))
    }

    /** Trashing a folder also trashes every subfolder and notebook inside it. */
    suspend fun trashFolders(ids: List<String>) = withContext(io) {
        val ts = System.currentTimeMillis()
        val all = collectFolderTree(ids)
        folderDao.trash(all, ts)
        val notebooks = all.flatMap { fid -> notebookDao.all().filter { it.folderId == fid && it.trashedAt == null } }
        if (notebooks.isNotEmpty()) {
            notebookDao.trash(notebooks.map { it.id }, ts)
        }
    }

    private suspend fun collectFolderTree(roots: List<String>): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        val queue = ArrayDeque(roots)
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!seen.add(id)) continue
            out.add(id)
            val children = folderDao.all().filter { it.parentId == id && it.trashedAt == null }
            children.forEach { queue.add(it.id) }
        }
        return out
    }

    suspend fun restoreFolders(ids: List<String>) = withContext(io) {
        folderDao.restore(ids)
    }

    // ---------------------------------------------------------- notebooks

    fun observeNotebooks(folderId: String?, sort: SortOrder): Flow<List<Notebook>> =
        notebookDao.observeInFolderWithCount(folderId)
            .map { list -> sortNotebooks(list.map { it.notebook.toDomain(it.pageCount) }, sort) }

    private fun sortNotebooks(list: List<Notebook>, sort: SortOrder): List<Notebook> = when (sort) {
        SortOrder.NAME -> list.sortedBy { it.title.lowercase() }
        SortOrder.CREATED -> list.sortedBy { !it.pinned }.sortedBy { it.createdAt }
        SortOrder.MODIFIED -> list.sortedWith(compareBy({ !it.pinned }, { -it.updatedAt }))
    }

    fun observePinned(): Flow<List<Notebook>> =
        notebookDao.observePinnedWithCount().map { list ->
            list.sortedBy { it.notebook.pinOrder }.map { it.notebook.toDomain(it.pageCount) }
        }

    /** Recent strip: keeps the given id order (most recent first). */
    fun observeNotebooksByIds(ids: List<String>): Flow<List<Notebook>> =
        notebookDao.observeByIds(ids).map { list ->
            val byId = list.associateBy { it.id }
            ids.mapNotNull { byId[it] }.map { it.toDomain() }
        }

    suspend fun firstPageId(notebookId: String): String? =
        withContext(io) { pageDao.firstPageId(notebookId) }

    /** Asset filename of a PDF-backed notebook (e.g. "ab12cd.pdf"), if any. */
    suspend fun pdfAssetName(notebookId: String): String? =
        withContext(io) { notebookDao.byId(notebookId)?.pdfAsset }

    fun observeTrashedNotebooks(): Flow<List<Notebook>> =
        notebookDao.observeTrashedWithCount().map { list -> list.map { it.notebook.toDomain(it.pageCount) } }

    fun observeNotebook(id: String): Flow<Notebook?> =
        notebookDao.observeById(id).map { it?.toDomain() }

    suspend fun notebookById(id: String): Notebook? =
        withContext(io) { notebookDao.byId(id)?.toDomain() }

    suspend fun createNotebook(
        title: String,
        folderId: String? = null,
        coverArgb: Int = DEFAULT_COVER,
        paper: String = DEFAULT_PAPER,
        size: String = DEFAULT_SIZE
    ): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val (w, h) = PAGE_SIZES_PT.getValue(size)
        withContext(io) {
            notebookDao.upsert(
                NotebookEntity(
                    id = id, folderId = folderId, title = title, coverArgb = coverArgb,
                    pinned = false, pinOrder = 0, pdfAsset = null,
                    createdAt = now, updatedAt = now
                )
            )
            pageDao.upsert(
                PageEntity(
                    id = UUID.randomUUID().toString(),
                    notebookId = id,
                    position = 0,
                    widthPt = w,
                    heightPt = h,
                    paper = paper,
                    bgArgb = 0xFFFFFFFF.toInt(),
                    pdfPageIndex = null,
                    updatedAt = now
                )
            )
        }
        return id
    }

    suspend fun renameNotebook(id: String, title: String) =
        withContext(io) { notebookDao.rename(id, title, System.currentTimeMillis()) }

    suspend fun setCover(id: String, color: Int) =
        withContext(io) { notebookDao.setCover(id, color, System.currentTimeMillis()) }

    suspend fun moveNotebooks(ids: List<String>, folderId: String?) =
        withContext(io) { notebookDao.move(ids, folderId, System.currentTimeMillis()) }

    suspend fun setPinned(id: String, pinned: Boolean, order: Int = 0) =
        withContext(io) { notebookDao.setPinned(id, pinned, order, System.currentTimeMillis()) }

    suspend fun setPinOrder(orderedIds: List<String>) = withContext(io) {
        orderedIds.forEachIndexed { index, id ->
            val nb = notebookDao.byId(id) ?: return@forEachIndexed
            notebookDao.setPinned(id, true, index, System.currentTimeMillis())
        }
    }

    suspend fun trashNotebooks(ids: List<String>) = withContext(io) {
        notebookDao.trash(ids, System.currentTimeMillis())
    }

    suspend fun restoreNotebooks(ids: List<String>) = withContext(io) {
        notebookDao.restore(ids)
    }

    /** Permanently deletes rows AND page files. Callers must confirm. */
    suspend fun purgeNotebooks(ids: List<String>) = withContext(io) {
        notebookDao.purge(ids)
        ids.forEach { id ->
            pageDao.deleteByNotebook(id)
            pageStore.deleteNotebook(id)
        }
    }

    /** Auto-purge of trash older than 30 days (called at app start). */
    suspend fun purgeExpiredTrash(): List<String> = withContext(io) {
        val cutoff = trashCutoff()
        val old = notebookDao.allTrashed().filter { (it.trashedAt ?: 0) < cutoff }
        if (old.isNotEmpty()) {
            val ids = old.map { it.id }
            notebookDao.purge(ids)
            ids.forEach { id ->
                pageDao.deleteByNotebook(id)
                pageStore.deleteNotebook(id)
            }
        }
        folderDao.purgeOlderThan(cutoff)
        old.map { it.id }
    }

    suspend fun duplicateNotebook(id: String): String? = withContext(io) {
        val src = notebookDao.byId(id) ?: return@withContext null
        val newId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        notebookDao.upsert(
            src.copy(
                id = newId,
                title = "${src.title} (2)",
                createdAt = now,
                updatedAt = now,
                pinned = false,
                pinOrder = 0
            )
        )
        val pages = pageDao.byNotebook(id)
        pages.forEach { p ->
            val newPageId = UUID.randomUUID().toString()
            pageDao.upsert(p.copy(id = newPageId, notebookId = newId, updatedAt = now))
            pageStore.copyPage(id, p.id, newId, newPageId)
        }
        newId
    }

    // --------------------------------------------------------------- pages

    fun observePages(notebookId: String): Flow<List<Page>> =
        pageDao.observeByNotebook(notebookId).map { list -> list.map { it.toDomain() } }

    suspend fun pagesOf(notebookId: String): List<Page> =
        withContext(io) { pageDao.byNotebook(notebookId).map { it.toDomain() } }

    suspend fun pageById(pageId: String): Page? =
        withContext(io) { pageDao.byId(pageId)?.toDomain() }

    /** Creates a page at the end, inheriting paper/size of the last page. */
    suspend fun addPage(notebookId: String): Page? = withContext(io) {
        val existing = pageDao.byNotebook(notebookId)
        val last = existing.lastOrNull() ?: return@withContext null
        val now = System.currentTimeMillis()
        val entity = PageEntity(
            id = UUID.randomUUID().toString(),
            notebookId = notebookId,
            position = (pageDao.maxPosition(notebookId) ?: -1) + 1,
            widthPt = last.widthPt,
            heightPt = last.heightPt,
            paper = last.paper,
            bgArgb = last.bgArgb,
            pdfPageIndex = null,
            updatedAt = now
        )
        pageDao.upsert(entity)
        notebookDao.byId(notebookId)?.let { notebookDao.upsert(it.copy(updatedAt = now)) }
        entity.toDomain()
    }

    suspend fun duplicatePage(pageId: String): Page? = withContext(io) {
        val p = pageDao.byId(pageId) ?: return@withContext null
        val newPageId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val copy = p.copy(
            id = newPageId,
            position = (pageDao.maxPosition(p.notebookId) ?: -1) + 1,
            updatedAt = now
        )
        pageDao.upsert(copy)
        pageStore.copyPage(p.notebookId, p.id, p.notebookId, newPageId)
        copy.toDomain()
    }

    /** A notebook always keeps at least one page. */
    suspend fun deletePage(notebookId: String, pageId: String): Boolean = withContext(io) {
        if (pageDao.byNotebook(notebookId).size <= 1) return@withContext false
        pageDao.delete(pageId)
        pageStore.deletePage(notebookId, pageId)
        renumberPages(notebookId)
        true
    }

    suspend fun reorderPages(notebookId: String, orderedIds: List<String>) = withContext(io) {
        val now = System.currentTimeMillis()
        orderedIds.forEachIndexed { index, pageId ->
            pageDao.setPosition(pageId, index, now)
        }
    }

    suspend fun setPagePaper(pageId: String, paper: String, bgArgb: Int) = withContext(io) {
        pageDao.setPaper(pageId, paper, bgArgb, System.currentTimeMillis())
    }

    suspend fun resizePage(pageId: String, widthPt: Float, heightPt: Float) = withContext(io) {
        pageDao.setSize(pageId, widthPt, heightPt, System.currentTimeMillis())
    }

    private suspend fun renumberPages(notebookId: String) {
        val pages = pageDao.byNotebook(notebookId).sortedBy { it.position }
        val now = System.currentTimeMillis()
        pages.forEachIndexed { index, p -> pageDao.setPosition(p.id, index, now) }
    }

    // --------------------------------------------------------- search / FTS

    fun searchNotebooksByTitle(query: String): Flow<List<Notebook>> =
        notebookDao.searchByTitle(query).map { list -> list.map { it.toDomain() } }

    /** FTS query over typed text boxes; returns matching notebook ids. */
    suspend fun searchNotebookIdsByContent(query: String): List<String> = withContext(io) {
        // FTS4 has no quoted-prefix syntax ("term"* is invalid); tokenize the
        // input by non-word runs (unicode-aware, Arabic included) and match
        // every term as a prefix.
        val terms = query.split(Regex("[^\\p{L}\\p{N}_]+")).filter { it.isNotBlank() }
        if (terms.isEmpty()) return@withContext emptyList()
        // FTS MATCH is per row, so a multi-word query must UNION the per-term
        // notebook hits (OR semantics across text boxes).
        val out = LinkedHashSet<String>()
        for (t in terms) {
            try {
                out.addAll(pageTextDao.searchNotebooks("$t*"))
            } catch (e: Exception) {
                // FTS4 treats some punctuation as operators; skip bad terms.
            }
        }
        out.toList()
    }

    /** Replaces the FTS rows of a page from its text boxes. */
    suspend fun reindexPageText(pageId: String, notebookId: String, texts: List<String>) =
        withContext(io) {
            pageTextDao.deleteByPage(pageId)
            if (texts.isEmpty()) return@withContext
            pageTextDao.insertAll(texts.mapIndexed { i, t ->
                PageTextEntity(rowId = i + 1, pageId = pageId, notebookId = notebookId, text = t)
            })
        }

    // -------------------------------------------------------------- tags

    fun observeTags(): Flow<List<TagEntity>> = tagDao.observeAllUsed()

    fun observeTagsForNotebook(notebookId: String): Flow<List<TagEntity>> =
        tagDao.observeForNotebook(notebookId)

    fun observeNotebooksWithTag(tagId: String): Flow<List<Notebook>> =
        tagDao.observeNotebooksWithTag(tagId).map { list -> list.map { it.toDomain() } }

    suspend fun addTagToNotebook(notebookId: String, name: String): TagEntity = withContext(io) {
        val existing = tagDao.all().firstOrNull { it.name.equals(name, ignoreCase = true) }
        val tag = existing ?: TagEntity(UUID.randomUUID().toString(), name.trim()).also { tagDao.upsert(it) }
        tagDao.link(com.nanote.data.db.NotebookTagEntity(notebookId, tag.id))
        tag
    }

    // ------------------------------------------------------- PDF import (M4)

    /** Imports a PDF as a NEW notebook; one page per PDF page. */
    suspend fun importPdfAsNotebook(
        bytes: ByteArray,
        displayName: String,
        pdfAssetOpener: (File) -> com.nanote.ink.render.PdfPageProvider,
        folderId: String? = null
    ): Result<String> = withContext(io) {
        try {
            val nbId = UUID.randomUUID().toString()
            val assetId = UUID.randomUUID().toString()
            val pdfName = "$assetId.pdf"
            pageStore.saveAsset(nbId, assetId, bytes, "pdf")
            val provider = pdfAssetOpener(pageStore.assetFile(nbId, assetId, "pdf"))
            val count = try {
                provider.pageCount
            } finally {
            }
            val now = System.currentTimeMillis()
            notebookDao.upsert(
                NotebookEntity(
                    id = nbId, folderId = folderId, title = displayName,
                    coverArgb = DEFAULT_COVER, pinned = false, pinOrder = 0,
                    pdfAsset = pdfName, createdAt = now, updatedAt = now
                )
            )
            for (i in 0 until count) {
                val (w, h) = provider.pageSizePts(i)
                pageDao.upsert(
                    PageEntity(
                        id = UUID.randomUUID().toString(), notebookId = nbId,
                        position = i, widthPt = w, heightPt = h,
                        paper = "blank", bgArgb = 0xFFFFFFFF.toInt(),
                        pdfPageIndex = i, updatedAt = now
                    )
                )
            }
            provider.close()
            Result.success(nbId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Imports images as a new notebook: each image becomes one page. */
    suspend fun importImagesAsNotebook(
        images: List<ByteArray>,
        title: String,
        folderId: String? = null
    ): Result<String> = withContext(io) {
        try {
            val nbId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            notebookDao.upsert(
                NotebookEntity(
                    id = nbId, folderId = folderId, title = title,
                    coverArgb = DEFAULT_COVER, createdAt = now, updatedAt = now
                )
            )
            images.forEachIndexed { index, bytes ->
                val assetId = UUID.randomUUID().toString()
                pageStore.saveAsset(nbId, assetId, bytes, "png")
                val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                var w = opts.outWidth.toFloat()
                var h = opts.outHeight.toFloat()
                if (w <= 0f || h <= 0f) {
                    w = 595f; h = 842f
                }
                // page = image size in points (72 dpi), capped to A4 width
                val scale = (595f / w).coerceAtMost(1f)
                val pw = w * scale
                val ph = h * scale
                val pageId = UUID.randomUUID().toString()
                pageDao.upsert(
                    PageEntity(
                        id = pageId, notebookId = nbId, position = index,
                        widthPt = pw, heightPt = ph, paper = "blank",
                        bgArgb = 0xFFFFFFFF.toInt(), updatedAt = now
                    )
                )
                val imgObj = com.nanote.domain.model.ImageObj(
                    id = UUID.randomUUID().toString(),
                    assetId = assetId,
                    m = com.nanote.domain.model.identityMatrix(),
                    z = 0,
                    widthPt = pw,
                    heightPt = ph
                )
                pageStore.save(nbId, pageId, emptyList(), listOf(imgObj))
            }
            Result.success(nbId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ------------------------------------------------------- .nanote archive

    /** Exports a notebook as a .nanote ZIP (pages + assets + manifest). */
    suspend fun exportNotebookArchive(notebookId: String, out: java.io.OutputStream): Result<Unit> =
        withContext(io) {
            try {
                val nb = notebookDao.byId(notebookId) ?: return@withContext Result.failure(IllegalStateException("Notebook missing"))
                val pages = pageDao.byNotebook(notebookId)
                val loaded = pages.map { it.id to pageStore.load(notebookId, it.id) }
                val manifest = com.nanote.data.files.NotebookArchive.ArchiveManifest(
                    notebook = com.nanote.data.files.NotebookArchive.ArchiveNotebook(
                        id = nb.id, title = nb.title, coverArgb = nb.coverArgb,
                        createdAt = nb.createdAt, updatedAt = nb.updatedAt, pdfAsset = nb.pdfAsset
                    ),
                    pages = pages.map { p ->
                        val objects = loaded.firstOrNull { it.first == p.id }?.second?.objects ?: emptyList()
                        com.nanote.data.files.NotebookArchive.ArchivePage(
                            id = p.id, position = p.position, widthPt = p.widthPt,
                            heightPt = p.heightPt, paper = p.paper, bgArgb = p.bgArgb,
                            pdfPageIndex = p.pdfPageIndex, objects = objects
                        )
                    },
                    assets = pageStore.listAssets(notebookId).map { it.name }
                )
                val pageFiles = pages.associate { p ->
                    p.id to (pageStore.pageInk(notebookId, p.id) to pageStore.pageJson(notebookId, p.id))
                }
                val assets = pageStore.listAssets(notebookId).associate { it.name to it }
                com.nanote.data.files.NotebookArchive.write(out, manifest, pageFiles, assets)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Imports a .nanote archive. Id collisions with an existing notebook get a
     * NEW id (keep-both semantics).
     */
    suspend fun importNotebookArchive(input: java.io.InputStream, folderId: String?): Result<String> =
        withContext(io) {
            try {
                val result = com.nanote.data.files.NotebookArchive.read(input)
                val m = result.manifest
                var nbId = m.notebook.id
                if (notebookDao.byId(nbId) != null) {
                    nbId = UUID.randomUUID().toString() // keep-both
                }
                val now = System.currentTimeMillis()
                notebookDao.upsert(
                    NotebookEntity(
                        id = nbId, folderId = folderId, title = m.notebook.title,
                        coverArgb = m.notebook.coverArgb, pinned = false, pinOrder = 0,
                        pdfAsset = m.notebook.pdfAsset, createdAt = now, updatedAt = now
                    )
                )
                for (p in m.pages.sortedBy { it.position }) {
                    val pageId = p.id
                    pageDao.upsert(
                        PageEntity(
                            id = pageId, notebookId = nbId, position = p.position,
                            widthPt = p.widthPt, heightPt = p.heightPt,
                            paper = p.paper, bgArgb = p.bgArgb,
                            pdfPageIndex = p.pdfPageIndex, updatedAt = now
                        )
                    )
                    val files = result.pageFiles[pageId]
                    val objs = files?.second?.let {
                        try {
                            pageStore.decodeObjects(it)
                        } catch (e: Exception) {
                            emptyList<com.nanote.domain.model.PageObject>()
                        }
                    } ?: p.objects
                    val strokes = files?.first?.let {
                        try {
                            com.nanote.ink.engine.InkFormat.decode(it)
                        } catch (e: Exception) {
                            emptyList<com.nanote.ink.engine.Stroke>()
                        }
                    } ?: emptyList()
                    pageStore.save(nbId, pageId, strokes, objs)
                }
                for ((name, bytes) in result.assets) {
                    val parts = name.split('.')
                    val ext = parts.getOrNull(1) ?: "bin"
                    pageStore.saveAsset(nbId, parts[0], bytes, ext)
                }
                Result.success(nbId)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ------------------------------------------------------- exports

    /** Loads snapshots for export (pageId -> PageSnapshot). */
    suspend fun snapshotsFor(notebookId: String): Map<String, com.nanote.ink.render.PageSnapshot> =
        withContext(io) {
            pageDao.byNotebook(notebookId).associate { p ->
                val loaded = pageStore.load(notebookId, p.id)
                p.id to com.nanote.ink.render.PageSnapshot(loaded.strokes, loaded.objects, 0)
            }
        }

    fun pdfAssetFileOf(notebook: com.nanote.data.db.NotebookEntity, notebooksRoot: java.io.File): java.io.File? {
        val name = notebook.pdfAsset ?: return null
        val parts = name.split('.')
        return java.io.File(java.io.File(notebooksRoot, notebook.id), "assets/${parts[0]}.${parts.getOrNull(1) ?: "pdf"}")
    }

    // -------------------------------------------------- library backup (M5)

    /** All notebook ids present (used to plan restore collisions). */
    suspend fun allNotebookIds(): List<String> = withContext(io) {
        notebookDao.all().map { it.id }
    }

    /** Writes the whole library (rows + files) as a backup ZIP. */
    suspend fun backupLibrary(out: java.io.OutputStream): Result<Unit> = withContext(io) {
        try {
            val folders = folderDao.all()
            val notebooks = notebookDao.all()
            val pagesByNb = notebooks.associate { nb ->
                nb.id to pageDao.byNotebook(nb.id)
            }
            val links = tagDao.allLinks()
            val tags = tagDao.all()
            val manifest = com.nanote.data.files.LibraryArchive.ArchiveManifest(
                folders = folders.map {
                    com.nanote.data.files.LibraryArchive.ArchiveFolder(it.id, it.parentId, it.name, it.createdAt, it.updatedAt)
                },
                notebooks = notebooks.map { nb ->
                    val pages = (pagesByNb[nb.id] ?: emptyList()).map { p ->
                        val objs = pageStore.load(nb.id, p.id).objects
                        com.nanote.data.files.LibraryArchive.ArchivePage(
                            id = p.id, position = p.position, widthPt = p.widthPt,
                            heightPt = p.heightPt, paper = p.paper, bgArgb = p.bgArgb,
                            pdfPageIndex = p.pdfPageIndex, objects = objs
                        )
                    }
                    com.nanote.data.files.LibraryArchive.ArchiveNotebook(
                        id = nb.id, folderId = nb.folderId, title = nb.title,
                        coverArgb = nb.coverArgb, pinned = nb.pinned, pinOrder = nb.pinOrder,
                        pdfAsset = nb.pdfAsset, createdAt = nb.createdAt, updatedAt = nb.updatedAt,
                        pages = pages
                    )
                },
                tags = tags.flatMap { t ->
                    links.filter { it.tagId == t.id }.map { l ->
                        com.nanote.data.files.LibraryArchive.ArchiveTag(t.id, t.name, l.notebookId)
                    }
                }
            )
            val root = pageStore.notebooksRoot()
            com.nanote.data.files.LibraryArchive.write(out, manifest, root)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Restores a backup. [keepBoth] controls collision policy (user picks in UI). */
    suspend fun restoreLibrary(input: java.io.InputStream, keepBoth: Boolean): Result<Int> =
        withContext(io) {
            try {
                val result = com.nanote.data.files.LibraryArchive.read(input)
                val m = result.manifest
                val existingIds = notebookDao.all().map { it.id }.toSet()
                val existingFolders = folderDao.all().map { it.id }.toSet()
                val now = System.currentTimeMillis()

                // folders first
                for (f in m.folders) {
                    if (f.id in existingFolders) continue
                    folderDao.upsert(
                        com.nanote.data.db.FolderEntity(f.id, f.parentId, f.name, f.createdAt, f.updatedAt)
                    )
                }

                // notebooks + pages with id remapping on collisions (keep-both)
                val idRemap = HashMap<String, String>()
                var restored = 0
                for (nb in m.notebooks) {
                    val collision = nb.id in existingIds
                    if (collision && !keepBoth) continue // skip
                    val nbId = if (collision) UUID.randomUUID().toString() else nb.id
                    if (collision) idRemap[nb.id] = nbId

                    notebookDao.upsert(
                        com.nanote.data.db.NotebookEntity(
                            id = nbId, folderId = nb.folderId, title = nb.title,
                            coverArgb = nb.coverArgb, pinned = nb.pinned, pinOrder = nb.pinOrder,
                            pdfAsset = nb.pdfAsset, createdAt = nb.createdAt, updatedAt = nb.updatedAt
                        )
                    )
                    for (p in nb.pages.sortedBy { it.position }) {
                        pageDao.upsert(
                            com.nanote.data.db.PageEntity(
                                id = p.id, notebookId = nbId, position = p.position,
                                widthPt = p.widthPt, heightPt = p.heightPt, paper = p.paper,
                                bgArgb = p.bgArgb, pdfPageIndex = p.pdfPageIndex, updatedAt = now
                            )
                        )
                    }
                    restored++
                }

                // files under the (possibly remapped) notebook ids
                val root = pageStore.notebooksRoot()
                for ((rel, bytes) in result.files) {
                    val parts = rel.split('/')
                    val srcNb = parts.firstOrNull() ?: continue
                    val mapped = idRemap[srcNb] ?: srcNb
                    val target = java.io.File(root, rel.replaceFirst(srcNb, mapped))
                    target.parentFile?.mkdirs()
                    target.writeBytes(bytes)
                }

                // tags (dedupe by name; link by remapped id)
                for (t in m.tags) {
                    val tag = tagDao.all().firstOrNull { it.name.equals(t.name, ignoreCase = true) }
                        ?: com.nanote.data.db.TagEntity(UUID.randomUUID().toString(), t.name)
                            .also { tagDao.upsert(it) }
                    val targetNb = idRemap[t.notebookId] ?: t.notebookId
                    tagDao.link(com.nanote.data.db.NotebookTagEntity(targetNb, tag.id))
                }
                Result.success(restored)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

}