package com.nanote.core.data

import android.content.Context
import com.nanote.core.model.AppLanguage
import com.nanote.core.model.ToolbarConfig
import com.nanote.core.model.ThemeMode
import com.nanote.core.model.ToolConfig
import com.nanote.core.model.ToolPreset
import com.nanote.core.common.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val PREFS = "nanote_settings"
private const val KEY_TOOL = "tool_config"
private const val KEY_TOOLBAR = "toolbar_config"
private const val KEY_THEME = "theme_mode"
private const val KEY_DYNAMIC = "dynamic_colors"
private const val KEY_LANG = "app_language"
private const val KEY_PRESSURE = "pressure_sensitivity"
private const val KEY_PALM = "palm_rejection"
private const val KEY_PREFETCH = "prefetch_pages"
private const val KEY_PRESETS = "tool_presets"

/**
 * App settings persisted with SharedPreferences + JSON (kotlinx-serialization),
 * mirrored into StateFlows so Compose/toolbars react instantly.
 */
class SettingsStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _toolConfig = MutableStateFlow(readTool())
    val toolConfig: StateFlow<ToolConfig> = _toolConfig

    private val _toolbarConfig = MutableStateFlow(readToolbar())
    val toolbarConfig: StateFlow<ToolbarConfig> = _toolbarConfig

    private val _themeMode = MutableStateFlow(readTheme())
    val themeMode: StateFlow<ThemeMode> = _themeMode

    private val _dynamicColors = MutableStateFlow(prefs.getBoolean(KEY_DYNAMIC, false))
    val dynamicColors: StateFlow<Boolean> = _dynamicColors

    private val _language = MutableStateFlow(readLanguage())
    val language: StateFlow<AppLanguage> = _language

    private val _pressureSensitivity = MutableStateFlow(prefs.getBoolean(KEY_PRESSURE, true))
    val pressureSensitivity: StateFlow<Boolean> = _pressureSensitivity

    private val _palmRejection = MutableStateFlow(prefs.getBoolean(KEY_PALM, true))
    val palmRejection: StateFlow<Boolean> = _palmRejection

    private val _prefetchPages = MutableStateFlow(prefs.getBoolean(KEY_PREFETCH, true))
    val prefetchPages: StateFlow<Boolean> = _prefetchPages

    private val _presets = MutableStateFlow(readPresets())
    val presets: StateFlow<List<ToolPreset>> = _presets

    // ---------------- readers ----------------

    private fun readTool(): ToolConfig = try {
        prefs.getString(KEY_TOOL, null)?.let { json.decodeFromString<ToolConfig>(it) } ?: ToolConfig()
    } catch (t: Throwable) {
        AppLog.w("SettingsStore", "tool config reset: ${t.message}"); ToolConfig()
    }

    private fun readToolbar(): ToolbarConfig = try {
        prefs.getString(KEY_TOOLBAR, null)?.let { json.decodeFromString<ToolbarConfig>(it) } ?: ToolbarConfig()
    } catch (t: Throwable) {
        AppLog.w("SettingsStore", "toolbar config reset: ${t.message}"); ToolbarConfig()
    }

    private fun readTheme(): ThemeMode =
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.SYSTEM.name)!!) }
            .getOrDefault(ThemeMode.SYSTEM)

    private fun readLanguage(): AppLanguage =
        runCatching { AppLanguage.valueOf(prefs.getString(KEY_LANG, AppLanguage.SYSTEM.name)!!) }
            .getOrDefault(AppLanguage.SYSTEM)

    private fun readPresets(): List<ToolPreset> = try {
        prefs.getString(KEY_PRESETS, null)
            ?.let { json.decodeFromString<List<ToolPreset>>(it) } ?: emptyList()
    } catch (t: Throwable) {
        emptyList()
    }

    // ---------------- writers ----------------

    fun updateToolConfig(update: (ToolConfig) -> ToolConfig) {
        val next = update(_toolConfig.value)
        _toolConfig.value = next
        prefs.edit().putString(KEY_TOOL, json.encodeToString(next)).apply()
    }

    fun setToolbarConfig(config: ToolbarConfig) {
        _toolbarConfig.value = config
        prefs.edit().putString(KEY_TOOLBAR, json.encodeToString(config)).apply()
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.edit().putString(KEY_THEME, mode.name).apply()
    }

    fun setDynamicColors(enabled: Boolean) {
        _dynamicColors.value = enabled
        prefs.edit().putBoolean(KEY_DYNAMIC, enabled).apply()
    }

    fun setLanguage(lang: AppLanguage) {
        _language.value = lang
        prefs.edit().putString(KEY_LANG, lang.name).apply()
    }

    fun setPressureSensitivity(enabled: Boolean) {
        _pressureSensitivity.value = enabled
        prefs.edit().putBoolean(KEY_PRESSURE, enabled).apply()
    }

    fun setPalmRejection(enabled: Boolean) {
        _palmRejection.value = enabled
        prefs.edit().putBoolean(KEY_PALM, enabled).apply()
    }

    fun setPrefetchPages(enabled: Boolean) {
        _prefetchPages.value = enabled
        prefs.edit().putBoolean(KEY_PREFETCH, enabled).apply()
    }

    fun setPresets(presets: List<ToolPreset>) {
        _presets.value = presets
        prefs.edit().putString(KEY_PRESETS, json.encodeToString(presets)).apply()
    }

    fun addPreset(preset: ToolPreset) {
        val list = _presets.value.filterNot { it.name == preset.name } + preset
        setPresets(list)
    }
}
