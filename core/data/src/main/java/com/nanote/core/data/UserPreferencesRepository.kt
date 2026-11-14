package com.nanote.core.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nanote.core.model.AccentColor
import com.nanote.core.model.AppLanguage
import com.nanote.core.model.CoverColor
import com.nanote.core.model.NotebookDefaults
import com.nanote.core.model.NotebookIcon
import com.nanote.core.model.PageOrientation
import com.nanote.core.model.PageSize
import com.nanote.core.model.PageTemplate
import com.nanote.core.model.SortOption
import com.nanote.core.model.ThemeMode
import com.nanote.core.model.UserPreferences
import com.nanote.core.model.ViewMode
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.userPrefsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "nanote_user_prefs"
)

/**
 * Lightweight app preferences persisted with Preferences DataStore
 * (theme, language, accent, library + notebook defaults).
 *
 * A one-time [migrateFromLegacy] copies the theme keys previously stored in
 * the v1.0 SharedPreferences ("nanote_settings") so upgrades keep the
 * user's appearance choices.
 */
interface UserPreferencesRepository {
    val userPreferences: Flow<UserPreferences>
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setDynamicColors(enabled: Boolean)
    suspend fun setAccentColor(accent: AccentColor)
    suspend fun setLanguage(language: AppLanguage)
    suspend fun setDefaultViewMode(viewMode: ViewMode)
    suspend fun setDefaultSort(sort: SortOption)
    suspend fun setNotebookDefaults(defaults: NotebookDefaults)
}

class DataStoreUserPreferencesRepository(
    private val dataStore: DataStore<Preferences>
) : UserPreferencesRepository {

    private object Keys {
        val MIGRATED = booleanPreferencesKey("migrated_from_legacy_v1")
        val THEME = stringPreferencesKey("theme_mode")
        val DYNAMIC = booleanPreferencesKey("dynamic_colors")
        val ACCENT = stringPreferencesKey("accent_color")
        val LANGUAGE = stringPreferencesKey("app_language")
        val VIEW_MODE = stringPreferencesKey("default_view_mode")
        val SORT = stringPreferencesKey("default_sort")
        val COVER = stringPreferencesKey("nb_cover")
        val ICON = stringPreferencesKey("nb_icon")
        val TEMPLATE = stringPreferencesKey("nb_template")
        val ORIENTATION = stringPreferencesKey("nb_orientation")
        val SIZE = stringPreferencesKey("nb_size")
    }

    override val userPreferences: Flow<UserPreferences> = dataStore.data
        .catch { t ->
            if (t is IOException) emit(emptyPreferences()) else throw t
        }
        .map { p ->
            UserPreferences(
                themeMode = p[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                    ?: ThemeMode.SYSTEM,
                dynamicColors = p[Keys.DYNAMIC] ?: false,
                accentColor = AccentColor.safeOf(p[Keys.ACCENT]),
                language = p[Keys.LANGUAGE]?.let { runCatching { AppLanguage.valueOf(it) }.getOrNull() }
                    ?: AppLanguage.SYSTEM,
                defaultViewMode = p[Keys.VIEW_MODE]?.let { runCatching { ViewMode.valueOf(it) }.getOrNull() }
                    ?: ViewMode.GRID,
                defaultSort = p[Keys.SORT]?.let { runCatching { SortOption.valueOf(it) }.getOrNull() }
                    ?: SortOption.RECENTLY_MODIFIED,
                notebookDefaults = NotebookDefaults(
                    coverColor = CoverColor.safeOf(p[Keys.COVER]),
                    icon = p[Keys.ICON]?.let { runCatching { NotebookIcon.valueOf(it) }.getOrNull() }
                        ?: NotebookIcon.NOTEBOOK,
                    pageTemplate = PageTemplate.safeOf(p[Keys.TEMPLATE]),
                    pageOrientation = p[Keys.ORIENTATION]?.let {
                        runCatching { PageOrientation.valueOf(it) }.getOrNull()
                    } ?: PageOrientation.PORTRAIT,
                    pageSize = PageSize.safeOf(p[Keys.SIZE])
                )
            )
        }

    /** One-time import of the legacy v1.0 appearance keys. Idempotent. */
    suspend fun migrateFromLegacy(legacy: SharedPreferences) {
        val current = dataStore.data.first()
        if (current[Keys.MIGRATED] == true) return
        dataStore.edit { p ->
            p[Keys.MIGRATED] = true
            if (!current.contains(Keys.THEME)) {
                legacy.getString("theme_mode", null)?.let { p[Keys.THEME] = it }
            }
            if (!current.contains(Keys.DYNAMIC)) {
                if (legacy.contains("dynamic_colors")) {
                    p[Keys.DYNAMIC] = legacy.getBoolean("dynamic_colors", false)
                }
            }
            if (!current.contains(Keys.LANGUAGE)) {
                legacy.getString("app_language", null)?.let { p[Keys.LANGUAGE] = it }
            }
        }
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[Keys.THEME] = mode.name }
    }

    override suspend fun setDynamicColors(enabled: Boolean) {
        dataStore.edit { it[Keys.DYNAMIC] = enabled }
    }

    override suspend fun setAccentColor(accent: AccentColor) {
        dataStore.edit { it[Keys.ACCENT] = accent.name }
    }

    override suspend fun setLanguage(language: AppLanguage) {
        dataStore.edit { it[Keys.LANGUAGE] = language.name }
    }

    override suspend fun setDefaultViewMode(viewMode: ViewMode) {
        dataStore.edit { it[Keys.VIEW_MODE] = viewMode.name }
    }

    override suspend fun setDefaultSort(sort: SortOption) {
        dataStore.edit { it[Keys.SORT] = sort.name }
    }

    override suspend fun setNotebookDefaults(defaults: NotebookDefaults) {
        dataStore.edit { p ->
            p[Keys.COVER] = defaults.coverColor.name
            p[Keys.ICON] = defaults.icon.name
            p[Keys.TEMPLATE] = defaults.pageTemplate.name
            p[Keys.ORIENTATION] = defaults.pageOrientation.name
            p[Keys.SIZE] = defaults.pageSize.name
        }
    }

    companion object {
        fun fromContext(context: Context): DataStoreUserPreferencesRepository =
            DataStoreUserPreferencesRepository(context.userPrefsDataStore)
    }
}
