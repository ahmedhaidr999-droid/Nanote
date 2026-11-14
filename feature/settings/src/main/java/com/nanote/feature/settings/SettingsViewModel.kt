package com.nanote.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nanote.core.common.AppLog
import com.nanote.core.data.DocumentRepository
import com.nanote.core.data.SettingsStore
import com.nanote.core.data.UserPreferencesRepository
import com.nanote.core.model.AccentColor
import com.nanote.core.model.AppLanguage
import com.nanote.core.model.NotebookDefaults
import com.nanote.core.model.SortOption
import com.nanote.core.model.ThemeMode
import com.nanote.core.model.UserPreferences
import com.nanote.core.model.ViewMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Settings state: appearance (theme / dynamic colors / accent / language),
 * library defaults, notebook defaults, editor pen behaviour, storage and
 * about info. 100% offline — no accounts, no network.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: UserPreferencesRepository,
    val editorSettings: SettingsStore,
    private val repo: DocumentRepository,
    @Named("appVersion") val appVersion: String
) : ViewModel() {

    companion object {
        private const val TAG = "SettingsViewModel"
    }

    val userPreferences: StateFlow<UserPreferences> = prefs.userPreferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserPreferences())

    private val _storageBytes = MutableStateFlow(0L)
    val storageBytes: StateFlow<Long> = _storageBytes.asStateFlow()

    init {
        refreshStorage()
    }

    // ------------------------------------------------------------- appearance

    fun setThemeMode(mode: ThemeMode) = launch { prefs.setThemeMode(mode) }

    fun setDynamicColors(enabled: Boolean) = launch { prefs.setDynamicColors(enabled) }

    fun setAccentColor(accent: AccentColor) = launch { prefs.setAccentColor(accent) }

    fun setLanguage(language: AppLanguage) = launch { prefs.setLanguage(language) }

    // ------------------------------------------------------------- library

    fun setDefaultViewMode(viewMode: ViewMode) = launch { prefs.setDefaultViewMode(viewMode) }

    fun setDefaultSort(sort: SortOption) = launch { prefs.setDefaultSort(sort) }

    fun setNotebookDefaults(defaults: NotebookDefaults) =
        launch { prefs.setNotebookDefaults(defaults) }

    // ------------------------------------------------------------- editor (legacy store)

    fun setPressure(enabled: Boolean) = editorSettings.setPressureSensitivity(enabled)

    fun setPalm(enabled: Boolean) = editorSettings.setPalmRejection(enabled)

    fun setPrefetch(enabled: Boolean) = editorSettings.setPrefetchPages(enabled)

    // ------------------------------------------------------------- storage

    fun refreshStorage() {
        viewModelScope.launch {
            _storageBytes.value = runCatching { repo.storageUsedBytes() }
                .onFailure { AppLog.w(TAG, "storageUsedBytes failed: ${it.message}") }
                .getOrDefault(0L)
        }
    }

    fun clearThumbs() {
        viewModelScope.launch {
            runCatching { repo.clearThumbnailCache() }
                .onSuccess {
                    AppLog.i(TAG, "thumbnail cache cleared")
                    refreshStorage()
                }
                .onFailure { AppLog.w(TAG, "clearThumbnailCache failed: ${it.message}") }
        }
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
