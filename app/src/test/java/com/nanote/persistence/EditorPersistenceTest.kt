package com.nanote.persistence

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.nanote.data.db.AppDatabase
import com.nanote.data.files.PageStore
import com.nanote.data.repo.NotebookRepository
import com.nanote.domain.model.SortOrder
import com.nanote.ink.engine.AddStrokes
import com.nanote.ink.engine.History
import com.nanote.ink.engine.PageDoc
import com.nanote.ink.engine.PenType
import com.nanote.ink.engine.strokeOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The spec's key end-to-end test, at repository level (spec 9): create a
 * note, draw, undo, "restart the app" (fresh repository + PageStore over the
 * same files), verify strokes are identical. Also covers trash/purge, the
 * FTS index (EN + AR), .nanote archives and whole-library backup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class EditorPersistenceTest {

    private lateinit var db: AppDatabase
    private lateinit var root: File
    private lateinit var repo: NotebookRepository
    private lateinit var pageStore: PageStore

    private val strokeA = strokeOf(
        1, PenType.BALLPOINT, 0xFF000000.toInt(), 2f,
        floatArrayOf(10f, 10f, 20f, 30f, 15f, 50f)
    )
    private val strokeB = strokeOf(
        2, PenType.HIGHLIGHTER, 0xFFFFEB3B.toInt(), 12f,
        floatArrayOf(40f, 60f, 80f, 60f, 120f, 60f)
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        root = File(context.filesDir, "notebooks-test-${System.nanoTime()}").apply { mkdirs() }
        pageStore = PageStore(root)
        repo = NotebookRepository(
            db.folderDao(), db.notebookDao(), db.pageDao(), db.tagDao(), db.pageTextDao(),
            pageStore, Dispatchers.IO
        )
    }

    @After
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    /** Simulates an app restart: fresh repository + PageStore over the same files. */
    private fun freshRepo(): Pair<NotebookRepository, PageStore> {
        val store = PageStore(root)
        val fresh = NotebookRepository(
            db.folderDao(), db.notebookDao(), db.pageDao(), db.tagDao(), db.pageTextDao(),
            store, Dispatchers.IO
        )
        return fresh to store
    }

    @Test
    fun createDrawUndoRestart_identicalInk() = runBlocking {
        // 1. create note
        val nbId = repo.createNotebook("Test note")
        val pages = repo.pagesOf(nbId)
        assertThat(pages).isNotEmpty()
        val page = pages.first()

        // 2. draw two strokes
        val doc = PageDoc(page.widthPt, page.heightPt)
        val history = History()
        history.push(AddStrokes(listOf(strokeA)), doc)
        history.push(AddStrokes(listOf(strokeB)), doc)
        assertThat(doc.strokeCount).isEqualTo(2)
        pageStore.save(nbId, page.id, doc.strokesSnapshot(), emptyList())

        // 3. undo one stroke and save again (autosave flushed)
        history.undo(doc)
        assertThat(doc.strokeCount).isEqualTo(1)
        pageStore.save(nbId, page.id, doc.strokesSnapshot(), emptyList())

        // 4. "restart the app": fresh repository + PageStore over the same disk
        val (repo2, store2) = freshRepo()
        val pages2 = repo2.pagesOf(nbId)
        assertThat(pages2).hasSize(1)
        val loaded = store2.load(nbId, pages2.first().id)
        assertThat(loaded.strokes).isEqualTo(listOf(strokeA))
    }

    @Test
    fun trashAndPurge_removesRowsAndFiles() = runBlocking {
        val nbId = repo.createNotebook("To trash")
        val page = repo.pagesOf(nbId).first()
        pageStore.save(nbId, page.id, listOf(strokeA), emptyList())
        assertThat(pageStore.pageInk(nbId, page.id).exists()).isTrue()

        repo.trashNotebooks(listOf(nbId))
        repo.restoreNotebooks(listOf(nbId)) // still restorable
        assertThat(repo.notebookById(nbId)).isNotNull()

        repo.trashNotebooks(listOf(nbId))
        repo.purgeNotebooks(listOf(nbId))
        assertThat(repo.notebookById(nbId)).isNull()
        assertThat(pageStore.pageInk(nbId, page.id).exists()).isFalse()
    }

    @Test
    fun ftsIndex_searchFindsTypedText() = runBlocking {
        val nbId = repo.createNotebook("FTS note")
        val page = repo.pagesOf(nbId).first()
        repo.reindexPageText(page.id, nbId, listOf("hello world", "مرحبا بالعالم"))
        assertThat(repo.searchNotebookIdsByContent("world")).contains(nbId)
        assertThat(repo.searchNotebookIdsByContent("مرحبا")).contains(nbId)
        assertThat(repo.searchNotebookIdsByContent("zebra")).doesNotContain(nbId)
    }

    @Test
    fun nanoteArchive_roundTrip() = runBlocking {
        val nbId = repo.createNotebook("Archive note")
        val page = repo.pagesOf(nbId).first()
        pageStore.save(nbId, page.id, listOf(strokeA), emptyList())

        val out = ByteArrayOutputStream()
        assertThat(repo.exportNotebookArchive(nbId, out).isSuccess).isTrue()

        repo.trashNotebooks(listOf(nbId))
        repo.purgeNotebooks(listOf(nbId))

        val imported = repo.importNotebookArchive(ByteArrayInputStream(out.toByteArray()), null)
        assertThat(imported.isSuccess).isTrue()
        val newId = imported.getOrThrow()
        val restored = repo.pagesOf(newId)
        assertThat(restored).hasSize(1)
        val loaded = pageStore.load(newId, restored.first().id)
        assertThat(loaded.strokes).isEqualTo(listOf(strokeA))
    }

    @Test
    fun libraryBackup_roundTrip() = runBlocking {
        val nbId = repo.createNotebook("Backup note", coverArgb = 0xFF00FF00.toInt())
        val page = repo.pagesOf(nbId).first()
        pageStore.save(nbId, page.id, listOf(strokeA, strokeB), emptyList())

        val out = ByteArrayOutputStream()
        assertThat(repo.backupLibrary(out).isSuccess).isTrue()

        repo.trashNotebooks(listOf(nbId))
        repo.purgeNotebooks(listOf(nbId))

        val restored = repo.restoreLibrary(ByteArrayInputStream(out.toByteArray()), keepBoth = false)
        assertThat(restored.isSuccess).isTrue()
        assertThat(restored.getOrThrow()).isEqualTo(1)

        val (repo2, store2) = freshRepo()
        repo2.observeNotebooks(null, SortOrder.MODIFIED)
        val after = repo2.pagesOf(nbId)
        assertThat(after).hasSize(1)
        val loaded = store2.load(nbId, after.first().id)
        assertThat(loaded.strokes).isEqualTo(listOf(strokeA, strokeB))
    }
}
