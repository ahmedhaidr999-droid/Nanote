package com.nanote.core.model

/**
 * App-level preferences surfaced by Settings and consumed by the app shell
 * (theme, language) and the library (defaults). Persisted via DataStore.
 */
data class UserPreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColors: Boolean = false,
    val accentColor: AccentColor = AccentColor.NANOTE_BLUE,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val defaultViewMode: ViewMode = ViewMode.GRID,
    val defaultSort: SortOption = SortOption.RECENTLY_MODIFIED,
    val notebookDefaults: NotebookDefaults = NotebookDefaults()
)

/** Default presentation & page setup applied to newly created notebooks. */
data class NotebookDefaults(
    val coverColor: CoverColor = CoverColor.BLUE,
    val icon: NotebookIcon = NotebookIcon.NOTEBOOK,
    val pageTemplate: PageTemplate = PageTemplate.LINED,
    val pageOrientation: PageOrientation = PageOrientation.PORTRAIT,
    val pageSize: PageSize = PageSize.A4
)

/** Brand accent used as the Material 3 primary family. */
enum class AccentColor {
    NANOTE_BLUE, TEAL, GREEN, VIOLET, ROSE, AMBER, GRAPHITE;

    companion object {
        fun safeOf(name: String?): AccentColor =
            entries.firstOrNull { it.name == name } ?: NANOTE_BLUE
    }
}
