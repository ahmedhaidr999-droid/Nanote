package com.nanote.app.core.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.nanote.app.core.data.repo.FolderMeta
import com.nanote.app.core.data.repo.NoteMeta
import com.nanote.app.core.model.NoteKind

/**
 * Room row for a note / PDF document. The actual content lives on disk in
 * `filesDir/documents/<id>/doc.nanote` — this table is only the searchable
 * index used by the library UI (list, favorites, folders, recent).
 */
@Entity(
    tableName = "notes",
    indices = [Index("folderId"), Index("updatedAt")]
)
data class NoteEntity(
    @PrimaryKey val id: String,
    val title: String,
    /** [NoteKind.name]: "NOTE" or "PDF". */
    val kind: String,
    val favorite: Boolean,
    val folderId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val pageCount: Int
) {
    /** Converts the row into the UI-facing [NoteMeta]. */
    fun toMeta(): NoteMeta = NoteMeta(
        id = id,
        title = title,
        kind = try {
            NoteKind.valueOf(kind)
        } catch (t: Throwable) {
            NoteKind.NOTE
        },
        favorite = favorite,
        folderId = folderId,
        createdAt = createdAt,
        updatedAt = updatedAt,
        pageCount = pageCount
    )

    companion object {
        /** Builds a row from a [NoteMeta]. */
        fun fromMeta(meta: NoteMeta): NoteEntity = NoteEntity(
            id = meta.id,
            title = meta.title,
            kind = meta.kind.name,
            favorite = meta.favorite,
            folderId = meta.folderId,
            createdAt = meta.createdAt,
            updatedAt = meta.updatedAt,
            pageCount = meta.pageCount
        )
    }
}

/** Room row for a user folder. */
@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sortOrder: Int
) {
    /**
     * Converts the row into a [FolderMeta]. The note count is not tracked by
     * this table, so callers that need it must pass [noteCount].
     */
    fun toMeta(noteCount: Int = 0): FolderMeta = FolderMeta(
        id = id,
        name = name,
        count = noteCount
    )

    companion object {
        /** Builds a row from a [FolderMeta]. */
        fun fromMeta(meta: FolderMeta, sortOrder: Int = 0): FolderEntity = FolderEntity(
            id = meta.id,
            name = meta.name,
            sortOrder = sortOrder
        )
    }
}
