package com.nanote.feature.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nanote.core.common.AppLog
import com.nanote.core.data.BackupManager
import com.nanote.core.data.DocumentRepository
import com.nanote.core.data.SettingsStore
import com.nanote.core.data.UserPreferencesRepository
import com.nanote.core.model.AccentColor
import com.nanote.core.model.AppLanguage
import com.nanote.core.model.BackupPreview
import com.nanote.core.model.ExportFormat
import com.nanote.core.model.NotebookDefaults
import com.nanote.core.model.PdfExportMode
import com.nanote.core.model.RenderQuality
import com.nanote.core.model.RestoreResult
import com.nanote.core.model.SortOption
import com.nanote.core.model.ThemeMode
import com.nanote.core.model.UserPreferences
import com.nanote.core.model.ViewMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * library defaults, notebook defaults, editor pen behaviour, export
 * defaults, PDF rendering, autosave, backup & restore, storage and about
 * info. 100% offline — no accounts, no network.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val prefs: UserPreferencesRepository,
    val editorSettings: SettingsStore,
    private val repo: DocumentRepository,
    private val backupManager: BackupManager,
    @Named("appVersion") val appVersion: String
) : ViewModel() {

    companion object {
        private const val TAG = "SettingsViewModel"
    }

    val userPreferences: StateFlow<UserPreferences> = prefs.userPreferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserPreferences())

    private val _storageBytes = MutableStateFlow(0L)
    val storageBytes: StateFlow<Long> = _storageBytes.asStateFlow()

    /** Backup/restore pipeline state (one operation at a time). */
    private val _backupBusy = MutableStateFlow(false)
    val backupBusy: StateFlow<Boolean> = _backupBusy.asStateFlow()

    private val _backupMessage = MutableStateFlow<String?>(null)
    val backupMessage: StateFlow<String?> = _backupMessage.asStateFlow()

    /** Preview of a picked backup archive awaiting user confirmation. */
    private val _restorePreview = MutableStateFlow<BackupPreview?>(null)
    val restorePreview: StateFlow<BackupPreview?> = _restorePreview.asStateFlow()

    private val _restoreResult = MutableStateFlow<RestoreResult?>(null)
    val restoreResult: StateFlow<RestoreResult?> = _restoreResult.asStateFlow()

    /** Uri of the archive whose preview is showing (restored on confirm). */
    private var pendingRestoreUri: Uri? = null

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

    // ------------------------------------------------------------- export & PDF (Part 3)

    fun setDefaultExportFormat(format: ExportFormat) = editorSettings.setDefaultExportFormat(format)

    fun setDefaultPdfExportMode(mode: PdfExportMode) = editorSettings.setDefaultPdfExportMode(mode)

    fun setRenderQuality(quality: RenderQuality) = editorSettings.setRenderQuality(quality)

    // ------------------------------------------------------------- autosave (Part 3)

    fun setAutosaveEnabled(enabled: Boolean) = editorSettings.setAutosaveEnabled(enabled)

    fun setAutosaveDebounceSeconds(seconds: Int) =
        editorSettings.setAutosaveDebounceSeconds(seconds)

    // ------------------------------------------------------------- backup & restore

    /** Writes a FULL backup of every document to the SAF target. */
    fun createBackup(uri: Uri) {
        viewModelScope.launch {
            _backupBusy.value = true
            _backupMessage.value = null
            val ok = try {
                val out = runCatching {
                    appContext.contentResolver.openOutputStream(uri, "wt")
                }.getOrNull()
                if (out == null) {
                    false
                } else {
                    out.use { backupManager.createBackup(null, it) }
                }
            } catch (t: Throwable) {
                AppLog.e(TAG, "backup failed", t)
                false
            }
            _backupBusy.value = false
            _backupMessage.value = if (ok) "ok" else "failed"
        }
    }

    /** Reads the manifest of the picked archive for the confirm dialog. */
    fun previewRestore(uri: Uri) {
        viewModelScope.launch {
            _backupBusy.value = true
            _restoreResult.value = null
            val preview = try {
                val input = runCatching {
                    appContext.contentResolver.openInputStream(uri)
                }.getOrNull()
                if (input == null) null else input.use { backupManager.preview(it) }
            } catch (t: Throwable) {
                AppLog.e(TAG, "preview failed", t)
                null
            }
            _backupBusy.value = false
            if (preview != null) {
                _restorePreview.value = preview
                pendingRestoreUri = uri
            } else {
                _backupMessage.value = "invalid"
            }
        }
    }

    fun cancelRestore() {
        _restorePreview.value = null
        pendingRestoreUri = null
    }

    /** Restores the previously previewed archive. */
    fun confirmRestore() {
        val uri = pendingRestoreUri ?: return
        viewModelScope.launch {
            _backupBusy.value = true
            _restorePreview.value = null
            val result = try {
                val input = runCatching {
                    appContext.contentResolver.openInputStream(uri)
                }.getOrNull()
                if (input == null) null else input.use { backupManager.restore(it) }
            } catch (t: Throwable) {
                AppLog.e(TAG, "restore failed", t)
                null
            }
            _backupBusy.value = false
            pendingRestoreUri = null
            _restoreResult.value = result
            if (result != null) refreshStorage()
        }
    }

    fun dismissRestoreResult() {
        _restoreResult.value = null
    }

    fun dismissMessage() {
        _backupMessage.value = null
    }

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
