package com.nanote.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/** Index queries for notebooks/documents. All content I/O happens on disk. */
@Dao
interface NoteDao {

    @Upsert
    suspend fun upsert(note: NoteEntity)

    @Upsert
    suspend fun upsertAll(notes: List<NoteEntity>)

    @Query("SELECT * FROM notes")
    fun observeAll(): Flow<List<NoteEntity>>

    /**
     * Dynamic filter/query built by the repository (bound args only —
     * no user input is ever interpolated).
     */
    @androidx.room.RawQuery(observedEntities = [NoteEntity::class])
    fun observeFiltered(query: SupportSQLiteQuery): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE favorite = 1")
    fun observeFavorites(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes ORDER BY lastOpenedAt DESC, updatedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE folderId = :folderId")
    fun observeByFolder(folderId: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE folderId IS NULL")
    fun observeRootDocuments(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    fun observeById(id: String): Flow<NoteEntity?>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getById(id: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE title LIKE '%' || :q || '%' ORDER BY updatedAt DESC")
    suspend fun search(q: String): List<NoteEntity>

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM notes WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: Set<String>)

    @Query("UPDATE notes SET favorite = :fav WHERE id = :id")
    suspend fun updateFavorite(id: String, fav: Boolean)

    @Query("UPDATE notes SET favorite = :fav WHERE id IN (:ids)")
    suspend fun updateFavorites(ids: Set<String>, fav: Boolean)

    @Query("UPDATE notes SET folderId = :folderId WHERE id IN (:ids)")
    suspend fun updateFolder(ids: Set<String>, folderId: String?)

    @Query("UPDATE notes SET title = :title, updatedAt = :ts WHERE id = :id")
    suspend fun updateTitle(id: String, title: String, ts: Long)

    @Query("UPDATE notes SET updatedAt = :ts WHERE id = :id")
    suspend fun updateTimestamp(id: String, ts: Long)

    @Query("UPDATE notes SET pageCount = :pc WHERE id = :id")
    suspend fun updatePageCount(id: String, pc: Int)

    @Query("UPDATE notes SET lastOpenedAt = :ts WHERE id = :id")
    suspend fun updateLastOpened(id: String, ts: Long)

    @Query("UPDATE notes SET sortOrder = :newOrder WHERE id = :id")
    suspend fun updateSortOrder(id: String, newOrder: Int)

    /** Notebook presentation & page-defaults patch. */
    @Query(
        """UPDATE notes SET
            coverColor = :cover,
            icon = :icon,
            pageTemplate = :template,
            pageOrientation = :orientation,
            pageSize = :size,
            updatedAt = :ts
        WHERE id = :id"""
    )
    suspend fun updateNotebookSettings(
        id: String,
        cover: String,
        icon: String,
        template: String,
        orientation: String,
        size: String,
        ts: Long
    )
}

/** Folder CRUD (nested folders via parentId). */
@Dao
interface FolderDao {

    @Upsert
    suspend fun upsert(folder: FolderEntity)

    @Query("SELECT * FROM folders ORDER BY sortOrder, name")
    fun observeAll(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE parentId IS NULL ORDER BY sortOrder, name")
    fun observeRootFolders(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE parentId = :parentId ORDER BY sortOrder, name")
    fun observeChildren(parentId: String): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun getById(id: String): FolderEntity?

    @Query("SELECT * FROM folders")
    suspend fun getAll(): List<FolderEntity>

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE folders SET name = :name, updatedAt = :ts WHERE id = :id")
    suspend fun rename(id: String, name: String, ts: Long)

    @Query("UPDATE folders SET parentId = :parentId, updatedAt = :ts WHERE id = :id")
    suspend fun updateParent(id: String, parentId: String?, ts: Long)

    @Query("UPDATE notes SET folderId = :newParent WHERE folderId = :oldFolder")
    suspend fun moveDocumentsOutOfFolder(oldFolder: String, newParent: String?)

    @Query("UPDATE folders SET parentId = :newParent WHERE parentId = :oldFolder")
    suspend fun moveFoldersOutOfFolder(oldFolder: String, newParent: String?)

    /**
     * Deletes a folder without deleting anything inside it: documents and
     * sub-folders are lifted to the deleted folder's parent (root if null).
     */
    @Transaction
    suspend fun deleteFolderLiftingChildren(folderId: String, newParentId: String?) {
        moveDocumentsOutOfFolder(folderId, newParentId)
        moveFoldersOutOfFolder(folderId, newParentId)
        delete(folderId)
    }
}

/** Append-only recent activity log. */
@Dao
interface RecentActivityDao {

    @Upsert
    suspend fun insert(entry: RecentActivityEntity)

    /** Activity joined with its document (title/kind/cover for display). */
    @Query(
        """SELECT recent_activity.id AS id,
                  recent_activity.documentId AS documentId,
                  recent_activity.activityAction AS activityAction,
                  recent_activity.occurredAt AS occurredAt,
                  notes.title AS documentTitle,
                  notes.kind AS documentKind,
                  notes.coverColor AS coverColor
           FROM recent_activity
           INNER JOIN notes ON notes.id = recent_activity.documentId
           ORDER BY occurredAt DESC
           LIMIT :limit"""
    )
    fun observeRecent(limit: Int): Flow<List<ActivityWithDocument>>

    @Query(
        """DELETE FROM recent_activity WHERE id NOT IN (
               SELECT id FROM recent_activity ORDER BY occurredAt DESC LIMIT :keep)"""
    )
    suspend fun prune(keep: Long)
}

/** Join projection for the activity feed. */
data class ActivityWithDocument(
    val id: Long,
    val documentId: String,
    val activityAction: String,
    val occurredAt: Long,
    val documentTitle: String,
    val documentKind: String,
    val coverColor: String
)

// ---------------------------------------------------------------------------
// Part 3 DAOs — tags, PDF text index, bookmarks, positions, search history
// ---------------------------------------------------------------------------

/** Tag CRUD + document assignments. */
@Dao
interface TagDao {

    @Upsert
    suspend fun upsert(tag: TagEntity)

    @Query("SELECT * FROM tags ORDER BY name")
    fun observeAll(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE name LIKE '%' || :q || '%' ORDER BY name")
    suspend fun search(q: String): List<TagEntity>

    @Query("SELECT * FROM tags ORDER BY name")
    suspend fun getAll(): List<TagEntity>

    @Query("DELETE FROM tags WHERE name = :name")
    suspend fun delete(name: String)

    /** Tag rows joined with their usage counts. */
    @Query(
        """SELECT tags.name AS name, tags.color AS color, tags.createdAt AS createdAt,
                  COUNT(note_tags.noteId) AS usageCount
           FROM tags LEFT JOIN note_tags ON note_tags.tagName = tags.name
           GROUP BY tags.name
           ORDER BY tags.name"""
    )
    fun observeTagsWithCounts(): Flow<List<TagWithCount>>

    @Query(
        """SELECT tags.name AS name, tags.color AS color, tags.createdAt AS createdAt,
                  COUNT(note_tags.noteId) AS usageCount
           FROM tags LEFT JOIN note_tags ON note_tags.tagName = tags.name
           GROUP BY tags.name
           ORDER BY tags.name"""
    )
    suspend fun getTagsWithCounts(): List<TagWithCount>

    // ---- assignments ----

    @Query("SELECT tagName FROM note_tags WHERE noteId = :noteId ORDER BY tagName")
    suspend fun tagsForNote(noteId: String): List<String>

    @Query("SELECT tagName FROM note_tags WHERE noteId = :noteId ORDER BY tagName")
    fun observeTagsForNote(noteId: String): Flow<List<String>>

    @Query("SELECT noteId FROM note_tags WHERE tagName = :tag")
    suspend fun notesWithTag(tag: String): List<String>

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insertNoteTags(rows: List<NoteTagEntity>)

    @Query("DELETE FROM note_tags WHERE noteId = :noteId")
    suspend fun clearNoteTags(noteId: String)

    /** Atomically replaces the tag set of one document. */
    @Transaction
    suspend fun setNoteTags(noteId: String, tagNames: List<String>) {
        clearNoteTags(noteId)
        if (tagNames.isNotEmpty()) {
            insertNoteTags(tagNames.map { NoteTagEntity(noteId, it) })
        }
    }

    /** Deletes assignments then orphaned tags. */
    @Transaction
    suspend fun deleteTagAndAssignments(name: String) {
        _deleteTagAssignments(name)
        delete(name)
    }

    @Query("DELETE FROM note_tags WHERE tagName = :tag")
    suspend fun _deleteTagAssignments(tag: String)

    @Query("SELECT COUNT(*) FROM note_tags WHERE noteId = :noteId")
    suspend fun countNoteTags(noteId: String): Int
}

/** Tag row with usage count (LEFT JOIN projection). */
data class TagWithCount(
    val name: String,
    val color: Long,
    val createdAt: Long,
    val usageCount: Int
)

/** Local full-text index over extracted PDF page text. */
@Dao
interface PdfTextDao {

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<PdfTextPageEntity>)

    @Query("SELECT * FROM pdf_text_pages WHERE documentId = :docId ORDER BY pageIndex")
    suspend fun pagesForDoc(docId: String): List<PdfTextPageEntity>

    @Query("SELECT COUNT(*) FROM pdf_text_pages WHERE documentId = :docId")
    suspend fun countForDoc(docId: String): Int

    @Query("DELETE FROM pdf_text_pages WHERE documentId = :docId")
    suspend fun deleteForDoc(docId: String)

    /** Search hit joined with its document display data. */
    @Query(
        """SELECT pdf_text_pages.documentId AS documentId,
                  pdf_text_pages.pageIndex AS pageIndex,
                  pdf_text_pages.pdfPage AS pdfPage,
                  pdf_text_pages.text AS text,
                  notes.title AS documentTitle,
                  notes.kind AS documentKind,
                  notes.coverColor AS coverColor
           FROM pdf_text_pages
           INNER JOIN notes ON notes.id = pdf_text_pages.documentId
           WHERE pdf_text_pages.text LIKE '%' || :q || '%'
           ORDER BY notes.updatedAt DESC
           LIMIT :limit"""
    )
    suspend fun search(q: String, limit: Int): List<PdfTextHitRow>

    @Query("DELETE FROM pdf_text_pages WHERE documentId = :docId AND pageIndex = :pageIndex")
    suspend fun deletePage(docId: String, pageIndex: Int)
}

/** Projection of a PDF text search hit. */
data class PdfTextHitRow(
    val documentId: String,
    val pageIndex: Int,
    val pdfPage: Int,
    val text: String,
    val documentTitle: String,
    val documentKind: String,
    val coverColor: String
)

/** PDF bookmarks (per document). */
@Dao
interface BookmarkDao {

    @Upsert
    suspend fun upsert(bookmark: PdfBookmarkEntity)

    @Query("SELECT * FROM pdf_bookmarks WHERE documentId = :docId ORDER BY pageIndex")
    fun observeForDoc(docId: String): Flow<List<PdfBookmarkEntity>>

    @Query("SELECT * FROM pdf_bookmarks WHERE documentId = :docId ORDER BY pageIndex")
    suspend fun getForDoc(docId: String): List<PdfBookmarkEntity>

    @Query("SELECT * FROM pdf_bookmarks WHERE documentId = :docId AND pageIndex = :pageIndex LIMIT 1")
    suspend fun getForPage(docId: String, pageIndex: Int): PdfBookmarkEntity?

    @Query("DELETE FROM pdf_bookmarks WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM pdf_bookmarks WHERE documentId = :docId AND pageIndex = :pageIndex")
    suspend fun deleteForPage(docId: String, pageIndex: Int)
}

/** Reading positions (page + zoom) per document. */
@Dao
interface ReadingPositionDao {

    @Query("SELECT * FROM reading_positions WHERE documentId = :docId")
    suspend fun get(docId: String): ReadingPositionEntity?

    @Upsert
    suspend fun upsert(position: ReadingPositionEntity)

    @Query("DELETE FROM reading_positions WHERE documentId = :docId")
    suspend fun delete(docId: String)
}

/** Recent search queries. */
@Dao
interface SearchHistoryDao {

    @Query("SELECT * FROM search_history ORDER BY searchedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SearchHistoryEntity>>

    @Query("SELECT * FROM search_history ORDER BY searchedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<SearchHistoryEntity>

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: SearchHistoryEntity)

    @Query("DELETE FROM search_history")
    suspend fun clear()

    @Query("DELETE FROM search_history WHERE query = :query")
    suspend fun delete(query: String)
}
