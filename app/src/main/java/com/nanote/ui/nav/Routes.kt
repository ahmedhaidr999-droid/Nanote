package com.nanote.ui.nav

/** Route names of the single NavHost. */
object Routes {
    const val LIBRARY = "library?folderId={folderId}"
    const val FAVORITES = "favorites"
    const val TRASH = "trash"
    const val SETTINGS = "settings"

    /** Editor is chrome-less (no bottom bar / rail). */
    const val EDITOR = "editor/{notebookId}/{pageId}"

    /** Page settings sheet route inside the editor. */
    const val PAGE_DETAIL = "page/{notebookId}/{pageId}"

    fun library(folderId: String? = null): String =
        if (folderId == null) "library?folderId=null" else "library?folderId=$folderId"

    fun editor(notebookId: String, pageId: String = "first"): String =
        "editor/$notebookId/$pageId"

    const val ARG_NOTEBOOK = "notebookId"
    const val ARG_PAGE = "pageId"
    const val ARG_FOLDER = "folderId"
}
