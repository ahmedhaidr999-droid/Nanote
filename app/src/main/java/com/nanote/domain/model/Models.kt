package com.nanote.domain.model

import com.nanote.data.db.FolderEntity
import com.nanote.data.db.NotebookEntity
import com.nanote.data.db.PageEntity

/** Domain models shown by the UI. Room entities never leave the data layer. */

data class Folder(
    val id: String,
    val parentId: String?,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long
)

fun FolderEntity.toDomain() = Folder(id, parentId, name, createdAt, updatedAt)

data class Notebook(
    val id: String,
    val folderId: String?,
    val title: String,
    val coverArgb: Int,
    val pinned: Boolean,
    val pinOrder: Int,
    val hasPdf: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val pageCount: Int = 0,
    val trashedAt: Long? = null
)

fun NotebookEntity.toDomain(pageCount: Int = 0) = Notebook(
    id = id,
    folderId = folderId,
    title = title,
    coverArgb = coverArgb,
    pinned = pinned,
    pinOrder = pinOrder,
    hasPdf = pdfAsset != null,
    createdAt = createdAt,
    updatedAt = updatedAt,
    pageCount = pageCount,
    trashedAt = trashedAt
)

data class Page(
    val id: String,
    val notebookId: String,
    val position: Int,
    val widthPt: Float,
    val heightPt: Float,
    val paper: String,
    val bgArgb: Int,
    val pdfPageIndex: Int?,
    val updatedAt: Long
)

fun PageEntity.toDomain() = Page(
    id = id,
    notebookId = notebookId,
    position = position,
    widthPt = widthPt,
    heightPt = heightPt,
    paper = paper,
    bgArgb = bgArgb,
    pdfPageIndex = pdfPageIndex,
    updatedAt = updatedAt
)

enum class SortOrder { NAME, CREATED, MODIFIED }
