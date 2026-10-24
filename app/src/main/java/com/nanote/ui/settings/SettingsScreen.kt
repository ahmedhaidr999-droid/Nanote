package com.nanote.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nanote.R
import com.nanote.core.Brand
import com.nanote.core.StylusRegistry
import com.nanote.data.files.SettingsStore
import com.nanote.data.files.ToolbarPosition
import com.nanote.data.files.ThemePref
import com.nanote.data.repo.NotebookRepository
import com.nanote.ink.engine.StylusBarrelAction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsStore,
    private val repo: NotebookRepository
) : ViewModel() {

    val theme = settings.theme.stateIn(viewModelScope, SharingStarted.Eagerly, ThemePref.SYSTEM)
    val language = settings.language.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val defaultPaper = settings.defaultPaper.stateIn(viewModelScope, SharingStarted.Eagerly, "lined_wide")
    val defaultSize = settings.defaultSize.stateIn(viewModelScope, SharingStarted.Eagerly, "A4")
    val fingersDraw = settings.fingersDraw.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val palmSensitivity = settings.palmSensitivity.stateIn(viewModelScope, SharingStarted.Eagerly, 1.0f)
    val stylusButton = settings.stylusButton.stateIn(viewModelScope, SharingStarted.Eagerly, StylusBarrelAction.ERASE)
    val stylusOnly = settings.stylusOnly.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val toolbarPosition = settings.toolbarPosition.stateIn(viewModelScope, SharingStarted.Eagerly, ToolbarPosition.TOP)
    val detectedStylus = StylusRegistry.detectForThisDevice()
    val autosaveMs = settings.autosaveMs.stateIn(viewModelScope, SharingStarted.Eagerly, 1500L)
    val appLock = settings.appLock.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val lastBackup = settings.lastBackupMs.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    fun setTheme(t: ThemePref) = viewModelScope.launch { settings.setTheme(t) }

    fun setLanguage(tag: String) = viewModelScope.launch {
        settings.setLanguage(tag)
        val locales = if (tag.isBlank()) androidx.core.os.LocaleListCompat.getEmptyLocaleList()
        else androidx.core.os.LocaleListCompat.forLanguageTags(tag)
        AppCompatDelegate.setApplicationLocales(locales)
    }

    fun setFingersDraw(v: Boolean) = viewModelScope.launch { settings.setFingersDraw(v) }
    fun setPalmSensitivity(v: Float) = viewModelScope.launch { settings.setPalmSensitivity(v) }
    fun setStylusButton(a: StylusBarrelAction) = viewModelScope.launch { settings.setStylusButton(a) }
    fun setStylusOnly(v: Boolean) = viewModelScope.launch { settings.setStylusOnly(v) }
    fun setToolbarPosition(p: ToolbarPosition) = viewModelScope.launch { settings.setToolbarPosition(p) }
    fun setAutosave(ms: Long) = viewModelScope.launch { settings.setAutosaveMs(ms) }
    fun setAppLock(v: Boolean) = viewModelScope.launch { settings.setAppLock(v) }
    fun setDefaultPaper(p: String) = viewModelScope.launch { settings.setDefaultPaper(p) }
    fun setDefaultSize(s: String) = viewModelScope.launch { settings.setDefaultSize(s) }

    /** Applies the persisted language at first composition. */
    fun applyPersistedLanguage() = viewModelScope.launch {
        val tag = settings.languagePref()
        val locales = if (tag.isBlank()) androidx.core.os.LocaleListCompat.getEmptyLocaleList()
        else androidx.core.os.LocaleListCompat.forLanguageTags(tag)
        AppCompatDelegate.setApplicationLocales(locales)
    }

    fun backupLibrary(out: java.io.OutputStream, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val r = repo.backupLibrary(out)
        if (r.isSuccess) settings.setLastBackup(System.currentTimeMillis())
        onDone(r.isSuccess)
    }

    fun restoreLibrary(bytes: ByteArray, keepBoth: Boolean, onDone: (Int?) -> Unit) = viewModelScope.launch {
        val r = repo.restoreLibrary(bytes.inputStream(), keepBoth)
        onDone(r.getOrNull())
    }
}

/** Settings per spec 8: theme, defaults, input behavior, language, app lock, backup, about. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val theme by vm.theme.collectAsState()
    val language by vm.language.collectAsState()
    val defaultPaper by vm.defaultPaper.collectAsState()
    val defaultSize by vm.defaultSize.collectAsState()
    val fingersDraw by vm.fingersDraw.collectAsState()
    val palmSensitivity by vm.palmSensitivity.collectAsState()
    val stylusButton by vm.stylusButton.collectAsState()
    val stylusOnly by vm.stylusOnly.collectAsState()
    val toolbarPosition by vm.toolbarPosition.collectAsState()
    val autosave by vm.autosaveMs.collectAsState()
    val appLock by vm.appLock.collectAsState()
    val lastBackup by vm.lastBackup.collectAsState()

    var restorePending by remember { mutableStateOf<ByteArray?>(null) }

    LaunchedEffect(Unit) { vm.applyPersistedLanguage() }

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                vm.backupLibrary(out) { }
            }
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null) restorePending = bytes
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ---- theme
            SectionLabel(stringResource(R.string.theme_section))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip(stringResource(R.string.theme_system), theme == ThemePref.SYSTEM) { vm.setTheme(ThemePref.SYSTEM) }
                ChoiceChip(stringResource(R.string.theme_light), theme == ThemePref.LIGHT) { vm.setTheme(ThemePref.LIGHT) }
                ChoiceChip(stringResource(R.string.theme_dark), theme == ThemePref.DARK) { vm.setTheme(ThemePref.DARK) }
            }

            // ---- language
            SectionLabel(stringResource(R.string.language_section))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip(stringResource(R.string.lang_system), language.isBlank()) { vm.setLanguage("") }
                ChoiceChip("English", language == "en") { vm.setLanguage("en") }
                ChoiceChip("العربية", language == "ar") { vm.setLanguage("ar") }
            }

            // ---- defaults
            SectionLabel(stringResource(R.string.defaults_section))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.nanote.ink.engine.PAPERS.forEach { p ->
                    ChoiceChip(p.id, defaultPaper == p.id) { vm.setDefaultPaper(p.id) }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.nanote.ink.engine.PAGE_SIZES_PT.keys.forEach { s ->
                    ChoiceChip(s, defaultSize == s) { vm.setDefaultSize(s) }
                }
            }

            // ---- toolbar position (v1.1)
            SectionLabel(stringResource(R.string.toolbar_position))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip(stringResource(R.string.pos_top), toolbarPosition == ToolbarPosition.TOP) {
                    vm.setToolbarPosition(ToolbarPosition.TOP)
                }
                ChoiceChip(stringResource(R.string.pos_bottom), toolbarPosition == ToolbarPosition.BOTTOM) {
                    vm.setToolbarPosition(ToolbarPosition.BOTTOM)
                }
                ChoiceChip(stringResource(R.string.pos_left), toolbarPosition == ToolbarPosition.LEFT) {
                    vm.setToolbarPosition(ToolbarPosition.LEFT)
                }
                ChoiceChip(stringResource(R.string.pos_right), toolbarPosition == ToolbarPosition.RIGHT) {
                    vm.setToolbarPosition(ToolbarPosition.RIGHT)
                }
            }

            // ---- input
            SectionLabel(stringResource(R.string.input_section))
            ToggleRow(stringResource(R.string.toggle_fingers_draw), fingersDraw) { vm.setFingersDraw(it) }
            ToggleRow(stringResource(R.string.stylus_only), stylusOnly) { vm.setStylusOnly(it) }
            Text(
                stringResource(R.string.palm_sensitivity) + ": %.1f".format(palmSensitivity),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = palmSensitivity,
                onValueChange = { vm.setPalmSensitivity(it) },
                valueRange = 0.5f..2.0f,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                stringResource(R.string.stylus_button_setting),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip(stringResource(R.string.stylus_erase), stylusButton == StylusBarrelAction.ERASE) {
                    vm.setStylusButton(StylusBarrelAction.ERASE)
                }
                ChoiceChip(stringResource(R.string.stylus_lasso), stylusButton == StylusBarrelAction.LASSO) {
                    vm.setStylusButton(StylusBarrelAction.LASSO)
                }
                ChoiceChip(stringResource(R.string.stylus_undo), stylusButton == StylusBarrelAction.UNDO) {
                    vm.setStylusButton(StylusBarrelAction.UNDO)
                }
                ChoiceChip(stringResource(R.string.stylus_redo), stylusButton == StylusBarrelAction.REDO) {
                    vm.setStylusButton(StylusBarrelAction.REDO)
                }
                ChoiceChip(stringResource(R.string.stylus_disabled), stylusButton == StylusBarrelAction.DISABLED) {
                    vm.setStylusButton(StylusBarrelAction.DISABLED)
                }
            }

            // ---- stylus database (v1.1)
            SectionLabel(stringResource(R.string.stylus_section))
            val pen = vm.detectedStylus
            if (pen != null) {
                Text(
                    stringResource(R.string.stylus_detected, pen.name),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    stringResource(R.string.stylus_pressure_levels, pen.pressureLevels) +
                        " · " + stringResource(R.string.stylus_button_count, pen.buttons),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (pen.tilt || pen.hover) {
                    Text(
                        stringResource(R.string.stylus_tilt_hover),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (pen.palmRejection) {
                    Text(
                        stringResource(R.string.stylus_palm_supported),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    stringResource(R.string.stylus_none_detected),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                stringResource(R.string.autosave_delay) + ": ${autosave} ms",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = autosave.toFloat(),
                onValueChange = { vm.setAutosave(it.toLong()) },
                valueRange = 500f..5000f,
                modifier = Modifier.fillMaxWidth()
            )

            // ---- privacy
            SectionLabel(stringResource(R.string.privacy_section))
            ToggleRow(stringResource(R.string.app_lock), appLock) { enabled ->
                vm.setAppLock(enabled)
            }

            // ---- backup
            SectionLabel(stringResource(R.string.backup_section))
            Text(
                if (lastBackup > 0)
                    stringResource(R.string.last_backup) + ": " +
                        DateFormat.getDateTimeInstance().format(Date(lastBackup))
                else stringResource(R.string.never_backed_up),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Button(onClick = { backupLauncher.launch("nanote-backup.zip") }) {
                    Text(stringResource(R.string.backup_now))
                }
                androidx.compose.material3.OutlinedButton(onClick = {
                    restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                }) {
                    Text(stringResource(R.string.restore_now))
                }
            }

            // ---- about
            SectionLabel(stringResource(R.string.about_section))
            Text(
                "${Brand.APP_NAME} ${Brand.VERSION} — offline handwritten notes",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(R.string.about_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    restorePending?.let { bytes ->
        AlertDialog(
            onDismissRequest = { restorePending = null },
            title = { Text(stringResource(R.string.restore_title)) },
            text = { Text(stringResource(R.string.restore_keep_both_text)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.restoreLibrary(bytes, keepBoth = true) { restorePending = null }
                }) { Text(stringResource(R.string.keep_both)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.restoreLibrary(bytes, keepBoth = false) { restorePending = null }
                }) { Text(stringResource(R.string.skip_existing)) }
            }
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        onClick = onClick
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
}
