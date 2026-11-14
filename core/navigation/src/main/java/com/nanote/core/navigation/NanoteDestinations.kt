package com.nanote.core.navigation

/**
 * Centralized navigation destinations for the whole app.
 *
 * Kept as plain string constants (stable, verifiable) with helper factories
 * for parameterized routes. All feature modules and the app-level NavHost
 * agree on these tokens.
 */
object NanoteDestinations {

    const val HOME = "home"
    const val LIBRARY = "library"
    const val NOTEBOOKS = "notebooks"
    const val FOLDERS = "folders"
    const val SETTINGS = "settings"

    /** Library perspective passed through the [LIBRARY] route. */
    const val ARG_LIBRARY_FILTER = "filter"

    const val LIBRARY_ROUTE = "library/{$ARG_LIBRARY_FILTER}"

    fun library(filter: String = "all"): String = "library/$filter"

    /** Folder browser; a null id opens the root. */
    const val ARG_FOLDER_ID = "folderId"
    const val FOLDERS_ROUTE = "folders/{$ARG_FOLDER_ID}"

    fun folders(folderId: String?): String =
        if (folderId == null) "folders/root" else "folders/$folderId"

    /** Is "root" the sentinel used by [folders] for the top-level view. */
    const val FOLDER_ROOT = "root"

    /** Notebook / document editor. */
    const val ARG_DOC_ID = "docId"
    const val EDITOR_ROUTE = "editor/{$ARG_DOC_ID}"

    fun editor(docId: String): String = "editor/$docId"

    /** Library filter tokens (kept in sync with LibraryFilter.name.lowercase()). */
    object Filters {
        const val ALL = "all"
        const val NOTEBOOKS = "notebooks"
        const val IMPORTED = "imported"
        const val FAVORITES = "favorites"
        const val RECENT = "recent"
    }
}
