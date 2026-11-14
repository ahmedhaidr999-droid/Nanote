package com.nanote.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nanote.app.BuildConfig
import com.nanote.app.core.ai.AiService
import com.nanote.app.core.data.AiConfig
import com.nanote.app.core.data.SettingsStore
import com.nanote.app.core.data.repo.NotesRepository
import com.nanote.app.core.model.AppLanguage
import com.nanote.app.core.model.ThemeMode
import com.nanote.app.util.AppLog
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Status of the AI "Test connection" action. */
sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data object Ok : TestState
    data class Fail(val message: String? = null) : TestState
}

/**
 * Settings screen state: appearance, pen behaviour, AI provider config,
 * storage usage and about info.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsStore,
    private val ai: AiService,
    private val repo: NotesRepository
) : ViewModel() {

    companion object {
        private const val TAG = "SettingsViewModel"
    }

    // ---------------- appearance ----------------

    val themeMode: StateFlow<ThemeMode> = settings.themeMode
    val dynamicColors: StateFlow<Boolean> = settings.dynamicColors
    val language: StateFlow<AppLanguage> = settings.language

    // ---------------- pen & tools ----------------

    val pressureSensitivity: StateFlow<Boolean> = settings.pressureSensitivity
    val palmRejection: StateFlow<Boolean> = settings.palmRejection
    val prefetchPages: StateFlow<Boolean> = settings.prefetchPages

    // ---------------- AI ----------------

    val aiConfig: StateFlow<AiConfig> = settings.aiConfig

    private val _testState = MutableStateFlow<TestState>(TestState.Idle)
    val testState: StateFlow<TestState> = _testState.asStateFlow()

    // ---------------- storage ----------------

    private val _storageBytes = MutableStateFlow(0L)
    val storageBytes: StateFlow<Long> = _storageBytes.asStateFlow()

    val appVersion: String = BuildConfig.VERSION_NAME

    init {
        refreshStorage()
    }

    // ---------------- setters ----------------

    fun setThemeMode(mode: ThemeMode) = settings.setThemeMode(mode)

    fun setDynamicColors(enabled: Boolean) = settings.setDynamicColors(enabled)

    fun setLanguage(lang: AppLanguage) = settings.setLanguage(lang)

    fun setPressure(enabled: Boolean) = settings.setPressureSensitivity(enabled)

    fun setPalm(enabled: Boolean) = settings.setPalmRejection(enabled)

    fun setPrefetch(enabled: Boolean) = settings.setPrefetchPages(enabled)

    fun updateAi(url: String, key: String, model: String) {
        settings.updateAiConfig { current ->
            current.copy(
                baseUrl = url.trim(),
                apiKey = key.trim(),
                model = model.trim()
            )
        }
    }

    // ---------------- AI test ----------------

    fun testAi() {
        if (_testState.value == TestState.Running) return
        _testState.value = TestState.Running
        viewModelScope.launch {
            val ok = runCatching { ai.testConnection() }.getOrDefault(false)
            AppLog.i(TAG, "AI test connection -> $ok")
            _testState.value = if (ok) TestState.Ok else TestState.Fail(null)
        }
    }

    // ---------------- storage ----------------

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
}
