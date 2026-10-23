package com.nanote.data.files

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "nanote_settings")

/** Theme selection. */
enum class ThemePref { SYSTEM, LIGHT, DARK }

/** What the stylus barrel button does while drawing. */
enum class StylusButtonAction { ERASE, LASSO, DISABLED }

/**
 * All app settings on DataStore — everything stays on the device (spec 8:
 * Settings). The recent-notes list for quick switching lives here too.
 */
@Singleton
class SettingsStore @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) {

    private object Keys {
        val RECENT = stringPreferencesKey("recent_notebooks")
        val LAST_BACKUP = longPreferencesKey("last_backup_ms")
        val THEME = stringPreferencesKey("theme")
        val LANGUAGE = stringPreferencesKey("language") // "" = system, "en", "ar"
        val DEFAULT_PAPER = stringPreferencesKey("default_paper")
        val DEFAULT_SIZE = stringPreferencesKey("default_size")
        val FINGERS_DRAW = booleanPreferencesKey("fingers_draw")
        val PALM_SENSITIVITY = floatPreferencesKey("palm_sensitivity") // 0.5..2.0
        val STYLUS_BUTTON = stringPreferencesKey("stylus_button")
        val AUTOSAVE_MS = longPreferencesKey("autosave_ms")
        val APP_LOCK = booleanPreferencesKey("app_lock")
        val TAG_FILTER = stringPreferencesKey("tag_filter")
    }

    private val prefs get() = context.settingsStore.data

    // ------------------------------------------------------------- recents

    val recentIds: Flow<List<String>> = prefs.map { p ->
        (p[Keys.RECENT] ?: "").split(',').filter { it.isNotBlank() }
    }

    suspend fun touchNotebook(id: String) {
        context.settingsStore.edit { prefs ->
            val current = (prefs[Keys.RECENT] ?: "").split(',').filter { it.isNotBlank() }
            val next = (listOf(id) + current.filter { it != id }).take(12)
            prefs[Keys.RECENT] = next.joinToString(",")
        }
    }

    // ------------------------------------------------------------ settings

    val theme: Flow<ThemePref> = prefs.map { p ->
        when (p[Keys.THEME]) {
            "light" -> ThemePref.LIGHT
            "dark" -> ThemePref.DARK
            else -> ThemePref.SYSTEM
        }
    }

    suspend fun setTheme(t: ThemePref) {
        context.settingsStore.edit { it[Keys.THEME] = t.name.lowercase() }
    }

    val language: Flow<String> = prefs.map { it[Keys.LANGUAGE] ?: "" }

    suspend fun setLanguage(tag: String) {
        context.settingsStore.edit { it[Keys.LANGUAGE] = tag }
    }

    val defaultPaper: Flow<String> = prefs.map { it[Keys.DEFAULT_PAPER] ?: "lined_wide" }

    suspend fun setDefaultPaper(paper: String) {
        context.settingsStore.edit { it[Keys.DEFAULT_PAPER] = paper }
    }

    val defaultSize: Flow<String> = prefs.map { it[Keys.DEFAULT_SIZE] ?: "A4" }

    suspend fun setDefaultSize(size: String) {
        context.settingsStore.edit { it[Keys.DEFAULT_SIZE] = size }
    }

    val fingersDraw: Flow<Boolean> = prefs.map { it[Keys.FINGERS_DRAW] ?: true }

    suspend fun setFingersDraw(v: Boolean) {
        context.settingsStore.edit { it[Keys.FINGERS_DRAW] = v }
    }

    val palmSensitivity: Flow<Float> = prefs.map { it[Keys.PALM_SENSITIVITY] ?: 1.0f }

    suspend fun setPalmSensitivity(v: Float) {
        context.settingsStore.edit { it[Keys.PALM_SENSITIVITY] = v }
    }

    val stylusButton: Flow<StylusButtonAction> = prefs.map { p ->
        when (p[Keys.STYLUS_BUTTON]) {
            "lasso" -> StylusButtonAction.LASSO
            "disabled" -> StylusButtonAction.DISABLED
            else -> StylusButtonAction.ERASE
        }
    }

    suspend fun setStylusButton(a: StylusButtonAction) {
        context.settingsStore.edit { it[Keys.STYLUS_BUTTON] = a.name.lowercase() }
    }

    val autosaveMs: Flow<Long> = prefs.map { it[Keys.AUTOSAVE_MS] ?: 1500L }

    suspend fun setAutosaveMs(ms: Long) {
        context.settingsStore.edit { it[Keys.AUTOSAVE_MS] = ms }
    }

    val appLock: Flow<Boolean> = prefs.map { it[Keys.APP_LOCK] ?: false }

    suspend fun setAppLock(v: Boolean) {
        context.settingsStore.edit { it[Keys.APP_LOCK] = v }
    }

    val lastBackupMs: Flow<Long> = prefs.map { it[Keys.LAST_BACKUP] ?: 0L }

    suspend fun setLastBackup(ms: Long) {
        context.settingsStore.edit { it[Keys.LAST_BACKUP] = ms }
    }

    // one-shot reads used at startup (avoid Flow collection)
    suspend fun appLockEnabled(): Boolean =
        prefs.map { it[Keys.APP_LOCK] ?: false }.first()

    suspend fun themePref(): ThemePref =
        prefs.map { p ->
            when (p[Keys.THEME]) {
                "light" -> ThemePref.LIGHT
                "dark" -> ThemePref.DARK
                else -> ThemePref.SYSTEM
            }
        }.first()

    suspend fun languagePref(): String =
        prefs.map { it[Keys.LANGUAGE] ?: "" }.first()
}
