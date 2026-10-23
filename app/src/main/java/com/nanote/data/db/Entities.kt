package com.nanote.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room holds METADATA only — never stroke data (strokes live in files, see
 * data/files/PageStore). All ids are UUID strings; deletion is soft:
 * `trashedAt` is set, rows are purged after 30 days.
 */

@Entity(tableName = "folders", indices = [Index("parentId")])
data class FolderEntity(
    @PrimaryKey val id: String,
    val parentId: String?,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val trashedAt: Long? = null
)

@Entity(
    tableName = "notebooks",
    indices = [Index("folderId"), Index("updatedAt"), Index("trashedAt"), Index("pinned")]
)
data class NotebookEntity(
    @PrimaryKey val id: String,
    val folderId: String?,
    val title: String,
    val coverArgb: Int,
    val pinned: Boolean = false,
    val pinOrder: Int = 0,
    val pdfAsset: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val trashedAt: Long? = null
)

@Entity(tableName = "pages", indices = [Index(value = ["notebookId", "position"])])
data class PageEntity(
    @PrimaryKey val id: String,
    val notebookId: String,
    val position: Int,
    /** Page space is PDF points (1/72"): A4 = 595x842, A5 = 420x595, Letter = 612x792. */
    val widthPt: Float,
    val heightPt: Float,
    /** Paper template id, see core/PaperSpec.kt. */
    val paper: String,
    val bgArgb: Int,
    /** Non-null when this page annotates the given page of the imported PDF asset. */
    val pdfPageIndex: Int? = null,
    val updatedAt: Long
)

@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String
)

@Entity(
    tableName = "notebook_tags",
    primaryKeys = ["notebookId", "tagId"],
    indices = [Index("tagId")]
)
data class NotebookTagEntity(
    val notebookId: String,
    val tagId: String
)

/**
 * Full-text index over typed text boxes: one row per text box. unicode61
 * tokenizer so Arabic text is tokenized correctly.
 */
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "page_text")
data class PageTextEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Int,
    val pageId: String,
    val notebookId: String,
    val text: String
)
