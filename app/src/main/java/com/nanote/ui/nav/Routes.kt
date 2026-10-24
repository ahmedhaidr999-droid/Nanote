package com.nanote.ui.nav

import androidx.navigation.NavType
import androidx.navigation.navArgument

/** Route names of the single NavHost. */
object Routes {
    const val LIBRARY = "library?folderId={folderId}"

    /**
     * Bare base route of [LIBRARY]. In navigation 2.8 the `?` query arguments
     * of a destination are optional, so navigating to "library" (no query at
     * all) matches the destination and every query arg falls back to its
     * declared default. Used as the NavHost start destination and for
     * bottom-bar / rail taps back to the library root.
     */
    const val LIBRARY_BASE = "library"

    const val FAVORITES = "favorites"
    const val TRASH = "trash"
    const val SETTINGS = "settings"

    /** Editor is chrome-less (no bottom bar / rail). */
    const val EDITOR = "editor/{notebookId}/{pageId}"

    /** Page settings sheet route inside the editor. */
    const val PAGE_DETAIL = "page/{notebookId}/{pageId}"

    /** [folderId] == null means the library root (no folder selected). */
    fun library(folderId: String? = null): String =
        if (folderId == null) LIBRARY_BASE else "library?folderId=$folderId"

    fun editor(notebookId: String, pageId: String = "first"): String =
        "editor/$notebookId/$pageId"

    const val ARG_NOTEBOOK = "notebookId"
    const val ARG_PAGE = "pageId"
    const val ARG_FOLDER = "folderId"

    /**
     * folderId is a genuinely nullable String (the DB stores String? ids).
     *
     * History: v1.0.0/v1.0.1 used the literal string "null" as a sentinel —
     * the start destination was "library?folderId=null" with a non-nullable
     * argument. On device this threw
     * `IllegalArgumentException: Wrong argument type for 'folderId' in
     * argument bundle. string expected.` from NavDestination.addInDefaultArgs
     * during setGraph, crashing the app at every launch (caught on a Xiaomi
     * Pad 6S Pro via the built-in crash reporter; identical on all devices).
     * The proper pattern (per navigation 2.8 docs) is a nullable argument
     * with a null default and a query-less base route.
     */
    val LIBRARY_ARGS = listOf(
        navArgument(ARG_FOLDER) {
            type = NavType.StringType
            nullable = true
            defaultValue = null
        }
    )
}
