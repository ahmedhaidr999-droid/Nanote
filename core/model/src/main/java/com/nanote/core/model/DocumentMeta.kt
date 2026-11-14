package com.nanote.core.model

/**
 * Library-facing metadata for a notebook or imported document.
 * The Room index row mapped into the domain layer.
 */
data class DocumentMeta(
    val id: String,
    val title: String,
    val kind: NoteKind,
    val favorite: Boolean = false,
    val folderId: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val pageCount: Int = 0,
    val coverColor: CoverColor = CoverColor.BLUE,
    val icon: NotebookIcon = NotebookIcon.NOTEBOOK,
    val pageTemplate: PageTemplate = PageTemplate.LINED,
    val pageOrientation: PageOrientation = PageOrientation.PORTRAIT,
    val pageSize: PageSize = PageSize.A4,
    val lastOpenedAt: Long = 0L,
    val sortOrder: Int = 0
)
