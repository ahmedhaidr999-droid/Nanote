package com.nanote.core.data

import com.nanote.core.database.FolderEntity
import com.nanote.core.database.NoteEntity
import com.nanote.core.model.ActivityAction
import com.nanote.core.model.CoverColor
import com.nanote.core.model.DocumentMeta
import com.nanote.core.model.NoteKind
import com.nanote.core.model.NotebookIcon
import com.nanote.core.model.PageOrientation
import com.nanote.core.model.PageSize
import com.nanote.core.model.PageTemplate

/** Entity → domain mappers for the document index. */

fun NoteEntity.toMeta(): DocumentMeta = DocumentMeta(
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
    pageCount = pageCount,
    coverColor = CoverColor.safeOf(coverColor),
    icon = NotebookIcon.safeOf(icon),
    pageTemplate = PageTemplate.safeOf(pageTemplate),
    pageOrientation = try {
        PageOrientation.valueOf(pageOrientation)
    } catch (t: Throwable) {
        PageOrientation.PORTRAIT
    },
    pageSize = PageSize.safeOf(pageSize),
    lastOpenedAt = lastOpenedAt,
    sortOrder = sortOrder
)

fun com.nanote.core.database.ActivityWithDocument.toEntry() = com.nanote.core.model.RecentActivityEntry(
    id = id,
    documentId = documentId,
    action = try {
        ActivityAction.valueOf(activityAction)
    } catch (t: Throwable) {
        ActivityAction.OPENED
    },
    occurredAt = occurredAt,
    documentTitle = documentTitle,
    documentKind = try {
        NoteKind.valueOf(documentKind)
    } catch (t: Throwable) {
        NoteKind.NOTE
    },
    coverColor = CoverColor.safeOf(coverColor)
)

fun FolderEntity.toFolder(
    directCount: Int = 0,
    totalCount: Int = 0
): com.nanote.core.model.Folder = com.nanote.core.model.Folder(
    id = id,
    name = name,
    parentId = parentId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    sortOrder = sortOrder,
    directCount = directCount,
    totalCount = totalCount
)
