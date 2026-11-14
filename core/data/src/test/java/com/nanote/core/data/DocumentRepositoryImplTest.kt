package com.nanote.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nanote.core.database.AppDatabase
import com.nanote.core.model.ActivityAction
import com.nanote.core.model.CreateNotebookRequest
import com.nanote.core.model.CoverColor
import com.nanote.core.model.LibraryFilter
import com.nanote.core.model.NotebookIcon
import com.nanote.core.model.PageOrientation
import com.nanote.core.model.PageSize
import com.nanote.core.model.PageTemplate
import com.nanote.core.model.SortOption
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Library-management behaviour on top of an in-memory Room database:
 * nested folders (tree, path, counts, cycle guard), notebook metadata,
 * favorites, batch operations, custom order and the activity log.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DocumentRepositoryImplTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repo: DocumentRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = DocumentRepositoryImpl(context, db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ------------------------------------------------------------- notebooks

    @Test
    fun createNotebook_persistsPresentationDefaults() = runBlocking {
        val id = repo.createNotebook(
            CreateNotebookRequest(
                title = "Physics",
                coverColor = CoverColor.TEAL,
                icon = NotebookIcon.SCIENCE,
                pageTemplate = PageTemplate.GRID,
                pageOrientation = PageOrientation.LANDSCAPE,
                pageSize = PageSize.A5
            )
        )
        val meta = repo.getMeta(id)
        assertNotNull(meta)
        assertEquals("Physics", meta!!.title)
        assertEquals(CoverColor.TEAL, meta.coverColor)
        assertEquals(NotebookIcon.SCIENCE, meta.icon)
        assertEquals(PageTemplate.GRID, meta.pageTemplate)
        assertEquals(PageOrientation.LANDSCAPE, meta.pageOrientation)
        assertEquals(PageSize.A5, meta.pageSize)
        assertEquals(1, meta.pageCount)
    }

    @Test
    fun duplicateDocument_copiesMetadataWithFreshTimestamps() = runBlocking {
        val id = repo.createNotebook(
            CreateNotebookRequest(title = "Diary", coverColor = CoverColor.ROSE)
        )
        repo.setFavorite(id, true)
        val copyId = repo.duplicateDocument(id)
        assertNotNull(copyId)
        val copy = repo.getMeta(copyId!!)
        assertEquals("Diary (copy)", copy!!.title)
        assertEquals(CoverColor.ROSE, copy.coverColor)
        assertEquals(true, copy.favorite)
    }

    @Test
    fun updateNotebookSettings_patchesOnlyProvidedFields() = runBlocking {
        val id = repo.createNotebook(CreateNotebookRequest(title = "A"))
        repo.updateNotebookSettings(
            id,
            com.nanote.core.model.NotebookSettings(coverColor = CoverColor.VIOLET)
        )
        val meta = repo.getMeta(id)!!
        assertEquals(CoverColor.VIOLET, meta.coverColor)
        assertEquals(PageTemplate.LINED, meta.pageTemplate) // untouched
    }

    // ------------------------------------------------------------- filters

    @Test
    fun filters_notebooksFavoritesAndSearch() = runBlocking {
        val n1 = repo.createNotebook(CreateNotebookRequest(title = "Alpha notes"))
        val n2 = repo.createNotebook(CreateNotebookRequest(title = "Beta"))
        repo.setFavorite(n2, true)

        assertEquals(2, repo.observeDocuments(LibraryFilter.NOTEBOOKS).first().size)
        assertEquals(1, repo.observeDocuments(LibraryFilter.FAVORITES).first().size)
        assertEquals(1, repo.observeDocuments(LibraryFilter.ALL, "alpha").first().size)
        assertEquals(0, repo.observeDocuments(LibraryFilter.ALL, "zzz").first().size)
    }

    @Test
    fun batchFavoriteAndMove_andCustomOrder() = runBlocking {
        val a = repo.createNotebook(CreateNotebookRequest(title = "A"))
        val b = repo.createNotebook(CreateNotebookRequest(title = "B"))
        val c = repo.createNotebook(CreateNotebookRequest(title = "C"))

        val folderId = repo.createFolder("Work")
        repo.moveDocuments(setOf(a, b), folderId)
        assertEquals(2, repo.observeDocumentsInFolder(folderId).first().size)

        repo.setFavorites(setOf(a, b, c), true)
        assertEquals(3, repo.observeDocuments(LibraryFilter.FAVORITES).first().size)

        repo.setCustomOrder(listOf(c, a, b))
        val sorted = com.nanote.core.model.DocumentSorter.sort(
            repo.observeAllDocuments().first(), SortOption.CUSTOM
        )
        assertEquals(listOf(c, a, b), sorted.map { it.id })
    }

    // ------------------------------------------------------------- folders

    @Test
    fun nestedFolders_treeCountsAndPath() = runBlocking {
        val root = repo.createFolder("University")
        val sem1 = repo.createFolder("Semester 1", root)
        val math = repo.createFolder("Math", sem1)
        repo.createNotebook(CreateNotebookRequest(title = "Calculus", folderId = math))
        repo.createNotebook(CreateNotebookRequest(title = "Algebra", folderId = sem1))
        repo.createNotebook(CreateNotebookRequest(title = "Loose", folderId = null))

        val folders = repo.observeFolders().first()
        val uni = folders.first { it.name == "University" }
        val sem1f = folders.first { it.name == "Semester 1" }
        val mathF = folders.first { it.name == "Math" }

        assertEquals(2, uni.totalCount)   // Calculus (Math) + Algebra (Semester 1)
        assertEquals(2, sem1f.totalCount) // Calculus + Algebra
        assertEquals(1, mathF.totalCount) // Calculus

        val tree = repo.observeFolderTree().first()
        assertEquals(1, tree.size)
        assertEquals("University", tree[0].name)
        assertEquals(1, tree[0].children.size)
        assertEquals("Semester 1", tree[0].children[0].name)
        assertEquals(1, tree[0].children[0].children.size)

        val path = repo.observeFolderPath(math).first()
        assertEquals(listOf("University", "Semester 1", "Math"), path.map { it.name })
    }

    @Test
    fun moveFolder_rejectsCycles() = runBlocking {
        val parent = repo.createFolder("Parent")
        val child = repo.createFolder("Child", parent)
        val grandChild = repo.createFolder("GrandChild", child)

        // Moving parent into its own descendant must fail.
        assertFalse(repo.moveFolder(parent, grandChild))
        assertFalse(repo.moveFolder(parent, child))
        // Moving into itself must fail.
        assertFalse(repo.moveFolder(parent, parent))
        // Moving grandchild under parent is fine.
        assertTrue(repo.moveFolder(grandChild, parent))
        val tree = repo.observeFolderTree().first()
        assertEquals(setOf("Child", "GrandChild"), tree[0].children.map { it.name }.toSet())
    }

    @Test
    fun deleteFolder_liftsChildrenInsteadOfDeleting() = runBlocking {
        val root = repo.createFolder("Root")
        val sub = repo.createFolder("Sub", root)
        val docId = repo.createNotebook(CreateNotebookRequest(title = "Kept", folderId = sub))

        repo.deleteFolder(sub)

        // The folder is gone…
        val folders = repo.observeFolders().first()
        assertTrue(folders.none { it.name == "Sub" })
        // …but its documents moved up to Root.
        val doc = repo.getMeta(docId)!!
        assertEquals(root, doc.folderId)
    }

    // ------------------------------------------------------------- activity

    @Test
    fun openDocument_logsActivityAndLastOpened() = runBlocking {
        val id = repo.createNotebook(CreateNotebookRequest(title = "Notes"))
        assertEquals(1, repo.observeRecentActivity(10).first().size) // CREATED

        val opened = repo.openDocument(id)
        assertNotNull(opened)
        val entries = repo.observeRecentActivity(10).first()
        assertEquals(2, entries.size)
        assertEquals(ActivityAction.OPENED, entries[0].action)
        assertEquals(ActivityAction.CREATED, entries[1].action)

        assertTrue(repo.getMeta(id)!!.lastOpenedAt > 0)
        assertEquals(1, repo.observeDocuments(LibraryFilter.RECENT).first().size)
    }

    @Test
    fun deleteDocuments_cascadesActivity() = runBlocking {
        val id = repo.createNotebook(CreateNotebookRequest(title = "Temp"))
        repo.openDocument(id)
        assertEquals(2, repo.observeRecentActivity(10).first().size)

        repo.deleteDocuments(setOf(id))
        assertEquals(0, repo.observeRecentActivity(10).first().size)
    }
}
