package com.nanote.core.model

import java.text.Collator
import java.util.Locale

// ---------------------------------------------------------------------------
// Notebook identity & page setup
// ---------------------------------------------------------------------------

/** Palette used for notebook covers in the library. */
enum class CoverColor(val argb: Long) {
    BLUE(0xFF2563EB),
    TEAL(0xFF0D9488),
    GREEN(0xFF16A34A),
    AMBER(0xFFD97706),
    ROSE(0xFFE11D48),
    VIOLET(0xFF7C3AED),
    SLATE(0xFF475569),
    SAND(0xFFB45309);

    companion object {
        fun safeOf(name: String?): CoverColor =
            entries.firstOrNull { it.name == name } ?: BLUE
    }
}

/** Decorative icon shown on notebook covers. */
enum class NotebookIcon {
    NOTEBOOK, JOURNAL, NOTES, STUDY, WORK, SCIENCE, ART, TRAVEL;

    companion object {
        fun safeOf(name: String?): NotebookIcon =
            entries.firstOrNull { it.name == name } ?: NOTEBOOK
    }
}

/** Background template of the notebook's pages (rendered from Part 2 on). */
enum class PageTemplate {
    PLAIN, LINED, GRID, DOTTED, CORNELL;

    companion object {
        fun safeOf(name: String?): PageTemplate =
            entries.firstOrNull { it.name == name } ?: LINED
    }
}

enum class PageOrientation { PORTRAIT, LANDSCAPE }

/** Physical page size, portrait point dimensions (72 pt = 1 inch). */
enum class PageSize(val widthPts: Float, val heightPts: Float) {
    A4(595f, 842f),
    A5(420f, 595f),
    LETTER(612f, 792f);

    /** Aspect ratio (width / height) honouring the orientation. */
    fun aspectRatio(orientation: PageOrientation): Float = when (orientation) {
        PageOrientation.PORTRAIT -> widthPts / heightPts
        PageOrientation.LANDSCAPE -> heightPts / widthPts
    }

    companion object {
        fun safeOf(name: String?): PageSize = entries.firstOrNull { it.name == name } ?: A4
    }
}

// ---------------------------------------------------------------------------
// Library browsing
// ---------------------------------------------------------------------------

/** Sorting options offered by the library. */
enum class SortOption { RECENTLY_MODIFIED, RECENTLY_CREATED, NAME, CUSTOM }

/** Grid or list presentation of the library. */
enum class ViewMode { GRID, LIST }

/** Perspectives over the document library. */
sealed interface LibraryFilter {
    data object ALL : LibraryFilter
    data object NOTEBOOKS : LibraryFilter
    data object IMPORTED : LibraryFilter
    data object FAVORITES : LibraryFilter
    data object RECENT : LibraryFilter
    /** Contents of a specific folder (folders screen). */
    data class InFolder(val folderId: String) : LibraryFilter
    /** Loose documents that live in no folder (folders screen root). */
    data object ROOT : LibraryFilter

    companion object {
        /** Parses a route token; unknown values fall back to [ALL]. */
        fun fromToken(token: String?): LibraryFilter = when (token) {
            "notebooks" -> NOTEBOOKS
            "imported" -> IMPORTED
            "favorites" -> FAVORITES
            "recent" -> RECENT
            else -> ALL
        }

        fun token(filter: LibraryFilter): String = when (filter) {
            NOTEBOOKS -> "notebooks"
            IMPORTED -> "imported"
            FAVORITES -> "favorites"
            RECENT -> "recent"
            else -> "all"
        }
    }
}

// ---------------------------------------------------------------------------
// Folders (nested)
// ---------------------------------------------------------------------------

/** A user folder. [directCount]/[totalCount] are computed by the data layer. */
data class Folder(
    val id: String,
    val name: String,
    val parentId: String?,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val sortOrder: Int = 0,
    val directCount: Int = 0,
    val totalCount: Int = 0
)

/** Pre-Flattened folder tree entry used by pickers. */
data class FolderTreeNode(
    val folder: Folder,
    val depth: Int,
    val children: List<FolderTreeNode>
) {
    val id: String get() = folder.id
    val name: String get() = folder.name
}

// ---------------------------------------------------------------------------
// Recent activity feed
// ---------------------------------------------------------------------------

enum class ActivityAction { CREATED, OPENED, RENAMED, DUPLICATED, IMPORTED, MOVED, FAVORITED }

data class RecentActivityEntry(
    val id: Long,
    val documentId: String,
    val action: ActivityAction,
    val occurredAt: Long,
    val documentTitle: String,
    val documentKind: NoteKind,
    val coverColor: CoverColor
)

// ---------------------------------------------------------------------------
// Notebook mutation payloads
// ---------------------------------------------------------------------------

/** Partial update for notebook presentation & page defaults. */
data class NotebookSettings(
    val title: String? = null,
    val coverColor: CoverColor? = null,
    val icon: NotebookIcon? = null,
    val pageTemplate: PageTemplate? = null,
    val pageOrientation: PageOrientation? = null,
    val pageSize: PageSize? = null
)

/** Everything needed to create a new notebook. */
data class CreateNotebookRequest(
    val title: String,
    val folderId: String? = null,
    val coverColor: CoverColor = CoverColor.BLUE,
    val icon: NotebookIcon = NotebookIcon.NOTEBOOK,
    val pageTemplate: PageTemplate = PageTemplate.LINED,
    val pageOrientation: PageOrientation = PageOrientation.PORTRAIT,
    val pageSize: PageSize = PageSize.A4
)

// ---------------------------------------------------------------------------
// Sorting
// ---------------------------------------------------------------------------

/** Locale-aware sorting for library documents. Pure Kotlin — unit tested. */
object DocumentSorter {

    fun sort(documents: List<DocumentMeta>, option: SortOption): List<DocumentMeta> {
        val collator = Collator.getInstance(Locale.getDefault())
        return when (option) {
            SortOption.RECENTLY_MODIFIED -> documents.sortedByDescending { it.updatedAt }
            SortOption.RECENTLY_CREATED -> documents.sortedByDescending { it.createdAt }
            SortOption.NAME -> documents.sortedWith { a, b ->
                collator.compare(a.title.ifBlank { "~" }, b.title.ifBlank { "~" })
            }
            SortOption.CUSTOM -> documents.sortedBy { it.sortOrder }
        }
    }

    /** "Recently opened" ordering used by the Recent perspective. */
    fun byLastActivity(documents: List<DocumentMeta>): List<DocumentMeta> =
        documents.sortedByDescending { doc ->
            maxOf(doc.lastOpenedAt, doc.updatedAt, doc.createdAt)
        }
}
