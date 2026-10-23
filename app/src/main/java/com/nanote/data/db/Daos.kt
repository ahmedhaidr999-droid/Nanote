package com.nanote.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(folder: FolderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(folders: List<FolderEntity>)

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun byId(id: String): FolderEntity?

    @Query("SELECT * FROM folders WHERE trashedAt IS NULL ORDER BY name COLLATE NOCASE ASC")
    fun observeAllNotTrashed(): Flow<List<FolderEntity>>

    @Query(
        """SELECT * FROM folders
        WHERE trashedAt IS NULL
        AND ((:parentId IS NULL AND parentId IS NULL) OR parentId = :parentId)
        ORDER BY name COLLATE NOCASE ASC"""
    )
    fun observeChildren(parentId: String?): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE trashedAt IS NOT NULL ORDER BY trashedAt DESC")
    fun observeTrashed(): Flow<List<FolderEntity>>

    @Query("UPDATE folders SET trashedAt = :ts WHERE id IN (:ids)")
    suspend fun trash(ids: List<String>, ts: Long)

    @Query("UPDATE folders SET trashedAt = NULL WHERE id IN (:ids)")
    suspend fun restore(ids: List<String>)

    @Query("DELETE FROM folders WHERE id IN (:ids)")
    suspend fun purge(ids: List<String>)

    @Query("DELETE FROM folders WHERE trashedAt IS NOT NULL AND trashedAt < :cutoff")
    suspend fun purgeOlderThan(cutoff: Long): Int

    @Query("SELECT * FROM folders")
    suspend fun all(): List<FolderEntity>
}

/** Notebook joined with its page count (single query, no N+1). */
data class NotebookWithCount(
    @Embedded val notebook: NotebookEntity,
    val pageCount: Int
)

@Dao
interface NotebookDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(notebook: NotebookEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(notebooks: List<NotebookEntity>)

    @Query("SELECT * FROM notebooks WHERE id = :id")
    suspend fun byId(id: String): NotebookEntity?

    @Query("SELECT * FROM notebooks WHERE id = :id")
    fun observeById(id: String): Flow<NotebookEntity?>

    @Query(
        """SELECT * FROM notebooks
        WHERE trashedAt IS NULL
        AND ((:folderId IS NULL AND folderId IS NULL) OR folderId = :folderId)"""
    )
    fun observeInFolder(folderId: String?): Flow<List<NotebookEntity>>

    @Query(
        """SELECT n.*, (SELECT COUNT(*) FROM pages p WHERE p.notebookId = n.id) AS pageCount
        FROM notebooks n
        WHERE n.trashedAt IS NULL
        AND ((:folderId IS NULL AND n.folderId IS NULL) OR n.folderId = :folderId)"""
    )
    fun observeInFolderWithCount(folderId: String?): Flow<List<NotebookWithCount>>

    @Query("""SELECT n.*, (SELECT COUNT(*) FROM pages p WHERE p.notebookId = n.id) AS pageCount
        FROM notebooks n WHERE n.trashedAt IS NULL""")
    fun observeAllNotTrashedWithCount(): Flow<List<NotebookWithCount>>

    @Query(
        """SELECT n.*, (SELECT COUNT(*) FROM pages p WHERE p.notebookId = n.id) AS pageCount
        FROM notebooks n WHERE n.trashedAt IS NULL AND n.pinned = 1"""
    )
    fun observePinnedWithCount(): Flow<List<NotebookWithCount>>

    @Query(
        """SELECT n.*, (SELECT COUNT(*) FROM pages p WHERE p.notebookId = n.id) AS pageCount
        FROM notebooks n WHERE n.trashedAt IS NOT NULL ORDER BY n.trashedAt DESC"""
    )
    fun observeTrashedWithCount(): Flow<List<NotebookWithCount>>

    @Query("SELECT * FROM notebooks WHERE trashedAt IS NULL")
    fun observeAllNotTrashed(): Flow<List<NotebookEntity>>

    @Query("SELECT * FROM notebooks WHERE trashedAt IS NULL AND pinned = 1")
    fun observePinned(): Flow<List<NotebookEntity>>

    @Query("SELECT * FROM notebooks WHERE trashedAt IS NOT NULL ORDER BY trashedAt DESC")
    fun observeTrashed(): Flow<List<NotebookEntity>>

    @Query("SELECT * FROM notebooks WHERE trashedAt IS NOT NULL")
    suspend fun allTrashed(): List<NotebookEntity>

    @Query("SELECT * FROM notebooks")
    suspend fun all(): List<NotebookEntity>

    @Query("UPDATE notebooks SET trashedAt = :ts WHERE id IN (:ids)")
    suspend fun trash(ids: List<String>, ts: Long)

    @Query("UPDATE notebooks SET trashedAt = NULL WHERE id IN (:ids)")
    suspend fun restore(ids: List<String>)

    @Query("DELETE FROM notebooks WHERE id IN (:ids)")
    suspend fun purge(ids: List<String>)

    @Query("DELETE FROM notebooks WHERE trashedAt IS NOT NULL AND trashedAt < :cutoff")
    suspend fun purgeOlderThan(cutoff: Long)

    @Query("UPDATE notebooks SET title = :title, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: String, title: String, now: Long)

    @Query("UPDATE notebooks SET coverArgb = :color, updatedAt = :now WHERE id = :id")
    suspend fun setCover(id: String, color: Int, now: Long)

    @Query("UPDATE notebooks SET folderId = :folderId, updatedAt = :now WHERE id IN (:ids)")
    suspend fun move(ids: List<String>, folderId: String?, now: Long)

    @Query("UPDATE notebooks SET pinned = :pinned, pinOrder = :order, updatedAt = :now WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean, order: Int, now: Long)

    @Query("SELECT * FROM notebooks WHERE title LIKE '%' || :q || '%' AND trashedAt IS NULL")
    fun searchByTitle(q: String): Flow<List<NotebookEntity>>

    @Query("UPDATE notebooks SET pdfAsset = :asset, updatedAt = :now WHERE id = :id")
    suspend fun setPdfAsset(id: String, asset: String?, now: Long)

    @Query("SELECT COUNT(*) FROM notebooks WHERE trashedAt IS NULL")
    suspend fun count(): Int

    @Query("SELECT * FROM notebooks WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<NotebookEntity>

    @Query("SELECT * FROM notebooks WHERE id IN (:ids)")
    fun observeByIds(ids: List<String>): Flow<List<NotebookEntity>>

    @Query("SELECT * FROM notebooks WHERE trashedAt IS NULL ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecentlyUpdated(limit: Int): Flow<List<NotebookEntity>>
}

@Dao
interface PageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(page: PageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(pages: List<PageEntity>)

    @Query("SELECT * FROM pages WHERE id = :id")
    suspend fun byId(id: String): PageEntity?

    @Query("SELECT * FROM pages WHERE notebookId = :notebookId ORDER BY position ASC")
    suspend fun byNotebook(notebookId: String): List<PageEntity>

    @Query("SELECT * FROM pages WHERE notebookId = :notebookId ORDER BY position ASC")
    fun observeByNotebook(notebookId: String): Flow<List<PageEntity>>

    @Query("SELECT COUNT(*) FROM pages WHERE notebookId = :notebookId")
    fun observeCount(notebookId: String): Flow<Int>

    @Query("DELETE FROM pages WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM pages WHERE id IN (:ids)")
    suspend fun deleteAll(ids: List<String>)

    @Query("DELETE FROM pages WHERE notebookId = :notebookId")
    suspend fun deleteByNotebook(notebookId: String)

    @Query("UPDATE pages SET position = :position, updatedAt = :now WHERE id = :id")
    suspend fun setPosition(id: String, position: Int, now: Long)

    @Query("UPDATE pages SET paper = :paper, bgArgb = :bg, updatedAt = :now WHERE id = :id")
    suspend fun setPaper(id: String, paper: String, bg: Int, now: Long)

    @Query("UPDATE pages SET widthPt = :w, heightPt = :h, updatedAt = :now WHERE id = :id")
    suspend fun setSize(id: String, w: Float, h: Float, now: Long)

    @Query("SELECT * FROM pages")
    suspend fun all(): List<PageEntity>

    @Query("SELECT MAX(position) FROM pages WHERE notebookId = :notebookId")
    suspend fun maxPosition(notebookId: String): Int?

    @Query("SELECT id FROM pages WHERE notebookId = :notebookId ORDER BY position ASC LIMIT 1")
    suspend fun firstPageId(notebookId: String): String?

    @Query("SELECT * FROM pages WHERE notebookId = :notebookId ORDER BY position ASC LIMIT 1")
    suspend fun firstPage(notebookId: String): PageEntity?
}

@Dao
interface TagDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tag: TagEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(tags: List<TagEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun linkAll(links: List<NotebookTagEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun link(link: NotebookTagEntity)

    @Query("DELETE FROM notebook_tags WHERE notebookId = :notebookId AND tagId = :tagId")
    suspend fun unlink(notebookId: String, tagId: String)

    @Query(
        """SELECT t.* FROM tags t
        INNER JOIN notebook_tags nt ON nt.tagId = t.id
        WHERE nt.notebookId = :notebookId ORDER BY t.name COLLATE NOCASE ASC"""
    )
    fun observeForNotebook(notebookId: String): Flow<List<TagEntity>>

    @Query(
        """SELECT t.* FROM tags t INNER JOIN notebook_tags nt ON nt.tagId = t.id
        GROUP BY t.id ORDER BY COUNT(*) DESC, t.name COLLATE NOCASE ASC"""
    )
    fun observeAllUsed(): Flow<List<TagEntity>>

    @Query(
        """SELECT n.* FROM notebooks n
        INNER JOIN notebook_tags nt ON nt.notebookId = n.id
        WHERE nt.tagId = :tagId AND n.trashedAt IS NULL"""
    )
    fun observeNotebooksWithTag(tagId: String): Flow<List<NotebookEntity>>

    @Query("SELECT * FROM tags")
    suspend fun all(): List<TagEntity>

    @Query("SELECT * FROM notebook_tags")
    suspend fun allLinks(): List<NotebookTagEntity>

    @Query("DELETE FROM tags WHERE id NOT IN (SELECT DISTINCT tagId FROM notebook_tags)")
    suspend fun deleteOrphans()
}

@Dao
interface PageTextDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<PageTextEntity>)

    @Query("DELETE FROM page_text WHERE pageId = :pageId")
    suspend fun deleteByPage(pageId: String)

    @Query("SELECT DISTINCT notebookId FROM page_text WHERE page_text MATCH :query")
    suspend fun searchNotebooks(query: String): List<String>

    @Query("SELECT pageId FROM page_text WHERE page_text MATCH :query AND notebookId = :notebookId")
    suspend fun searchPages(query: String, notebookId: String): List<String>
}

