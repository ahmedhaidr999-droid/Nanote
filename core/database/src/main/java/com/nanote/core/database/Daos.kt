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
