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
    @ColumnInfo(defaultValue = "0") val sortOrder: Int
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
    /** [com.nanote.core.model.ActivityAction.name]. */
    val action: String,
    val occurredAt: Long
)
