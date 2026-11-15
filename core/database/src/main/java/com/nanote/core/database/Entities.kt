package com.nanote.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room row for a notebook / imported PDF document. The actual content lives
 * on disk in `filesDir/documents/<id>/doc.nanote` — this table is the
 * searchable index used by the library UI (lists, folders, favorites, recent).
 *
 * Schema v2 (Part 1 foundation): added notebook presentation columns
 * (cover/icon/template/orientation/size), lastOpenedAt, sortOrder and the
 * nested-folder + recent-activity tables. See [Migrations].
 */
@Entity(
    tableName = "notes",
    indices = [Index("folderId"), Index("updatedAt"), Index("lastOpenedAt"), Index("sortOrder")]
)
data class NoteEntity(
    @PrimaryKey val id: String,
    val title: String,
    /** [com.nanote.core.model.NoteKind.name]: "NOTE" or "PDF". */
    val kind: String,
    val favorite: Boolean,
    val folderId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val pageCount: Int,
    /** Default values mirror MIGRATION_1_2 so schema validation passes. */
    @ColumnInfo(defaultValue = "BLUE") val coverColor: String,
    @ColumnInfo(defaultValue = "NOTEBOOK") val icon: String,
    @ColumnInfo(defaultValue = "LINED") val pageTemplate: String,
    @ColumnInfo(defaultValue = "PORTRAIT") val pageOrientation: String,
    @ColumnInfo(defaultValue = "A4") val pageSize: String,
    @ColumnInfo(defaultValue = "0") val lastOpenedAt: Long,
    @ColumnInfo(defaultValue = "0") val sortOrder: Int,
    /** Schema v3 (Part 3): imported-file metadata. */
    @ColumnInfo(defaultValue = "0") val fileSizeBytes: Long = 0L,
    @ColumnInfo(defaultValue = "NULL") val sourceFileName: String? = null
)

/** Room row for a user folder (nested via [parentId]). */
@Entity(
    tableName = "folders",
    indices = [Index("parentId")]
)
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sortOrder: Int,
    val parentId: String?,
    @ColumnInfo(defaultValue = "0") val createdAt: Long,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long
)

/** Append-only activity log powering the home feed (pruned to the latest N). */
@Entity(
    tableName = "recent_activity",
    indices = [Index("documentId"), Index("occurredAt")],
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class RecentActivityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val documentId: String,
    /** "action" is reserved in Room's SQL grammar — mapped to activityAction. */
    @ColumnInfo(name = "activityAction") val action: String,
    val occurredAt: Long
)

// ---------------------------------------------------------------------------
// Schema v3 (Part 3 — PDF engine, storage, search & export)
// ---------------------------------------------------------------------------

/** A user tag. Tags are plain strings with a display color. */
@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey val name: String,
    val color: Long,
    val createdAt: Long
)

/** Document ⇄ tag assignment (cascade-deleted with either side). */
@Entity(
    tableName = "note_tags",
    primaryKeys = ["noteId", "tagName"],
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["name"],
            childColumns = ["tagName"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("noteId"), Index("tagName")]
)
data class NoteTagEntity(
    val noteId: String,
    val tagName: String
)

/**
 * Extracted text of one page of an imported PDF — the local full-text index
 * powering search. Written once at import (background) and rebuilt only when
 * the PDF's pages change.
 */
@Entity(
    tableName = "pdf_text_pages",
    indices = [Index("documentId")],
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class PdfTextPageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val documentId: String,
    /** Index into the CURRENT .nanote page list. */
    val pageIndex: Int,
    /** Underlying source PDF page (−1 when the page has no PDF backing). */
    val pdfPage: Int,
    val text: String
)

/** A user bookmark pointing at a page of an imported PDF. */
@Entity(
    tableName = "pdf_bookmarks",
    indices = [Index("documentId")],
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class PdfBookmarkEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val pageIndex: Int,
    val pdfPage: Int,
    val title: String,
    val createdAt: Long
)

/** Last reading position per document (page + zoom). */
@Entity(
    tableName = "reading_positions",
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ReadingPositionEntity(
    @PrimaryKey val documentId: String,
    val pageIndex: Int,
    val zoomScale: Float,
    val savedAt: Long
)

/** Recent search queries shown as suggestion chips. */
@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey val query: String,
    val searchedAt: Long
)
