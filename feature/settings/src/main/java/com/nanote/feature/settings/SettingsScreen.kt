package com.nanote.feature.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nanote.core.design.R as CommonR
import com.nanote.core.design.components.LabelledSwitchRow
import com.nanote.core.design.components.NanoteSecondaryButton
import com.nanote.core.design.components.OptionCheckRow
import com.nanote.core.design.components.SettingsSectionCard
import com.nanote.core.design.components.SortMenuButton
import com.nanote.core.design.components.ViewModeToggle
import com.nanote.core.design.components.sortOptionLabel
import com.nanote.core.design.theme.Dimens
import com.nanote.core.design.theme.accentSwatch
import com.nanote.core.model.AccentColor
import com.nanote.core.model.AppLanguage
import com.nanote.core.model.NotebookDefaults
import com.nanote.core.model.ThemeMode
import com.nanote.feature.notebooks.NotebookFormDialog
import com.nanote.feature.notebooks.NotebookFormState

/**
 * Settings: appearance (theme, dynamic colors, accent, language), library
 * defaults, new-notebook defaults, editor pen behaviour, storage and about.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current

    val prefs by vm.userPreferences.collectAsStateWithLifecycle()
    val storageBytes by vm.storageBytes.collectAsStateWithLifecycle()
    val pressure by vm.editorSettings.pressureSensitivity.collectAsStateWithLifecycle()
    val palm by vm.editorSettings.palmRejection.collectAsStateWithLifecycle()
    val prefetch by vm.editorSettings.prefetchPages.collectAsStateWithLifecycle()

    var showDefaultsForm by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(CommonR.string.nav_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = Dimens.paddingMd,
                end = Dimens.paddingMd,
                bottom = Dimens.paddingLg
            ),
            verticalArrangement = Arrangement.spacedBy(Dimens.paddingMd)
        ) {
            // ----- Appearance -----
            item(key = "appearance") {
                SettingsSectionCard(
                    title = stringResource(R.string.settings_appearance),
                    icon = Icons.Outlined.Palette
                ) {
                    Text(
                        text = stringResource(R.string.theme),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ThemeOptionRow(
                        label = stringResource(R.string.theme_system),
                        selected = prefs.themeMode == ThemeMode.SYSTEM,
                        onSelect = { vm.setThemeMode(ThemeMode.SYSTEM) }
                    )
                    ThemeOptionRow(
                        label = stringResource(R.string.theme_light),
                        selected = prefs.themeMode == ThemeMode.LIGHT,
                        onSelect = { vm.setThemeMode(ThemeMode.LIGHT) }
                    )
                    ThemeOptionRow(
                        label = stringResource(R.string.theme_dark),
                        selected = prefs.themeMode == ThemeMode.DARK,
                        onSelect = { vm.setThemeMode(ThemeMode.DARK) }
                    )
                    Spacer(Modifier.height(Dimens.paddingSm))
                    LabelledSwitchRow(
                        title = stringResource(R.string.dynamic_colors),
                        subtitle = stringResource(R.string.dynamic_colors_desc),
                        checked = prefs.dynamicColors,
                        onCheckedChange = vm::setDynamicColors
                    )
                    Spacer(Modifier.height(Dimens.paddingSm))
                    Text(
                        text = stringResource(R.string.accent_color),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Dimens.paddingSm),
                        horizontalArrangement = Arrangement.spacedBy(Dimens.paddingSm)
                    ) {
                        AccentColor.entries.forEach { accent ->
                            val selected = prefs.accentColor == accent
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .clip(RoundedCornerShape(Dimens.radiusMd))
                                    .background(Color(accentSwatch(accent)))
                                    .border(
                                        width = if (selected) 3.dp else 1.dp,
                                        color = if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline,
                                        shape = RoundedCornerShape(Dimens.radiusMd)
                                    )
                                    .clickable { vm.setAccentColor(accent) }
                            )
                        }
                    }
                    Spacer(Modifier.height(Dimens.paddingSm))
                    LanguageDropdown(
                        current = prefs.language,
                        onSelect = vm::setLanguage
                    )
                }
            }

            // ----- Library defaults -----
            item(key = "library") {
                SettingsSectionCard(
                    title = stringResource(R.string.settings_library),
                    icon = Icons.Outlined.Palette
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Dimens.paddingSm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.default_view_mode),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        ViewModeToggle(
                            current = prefs.defaultViewMode,
                            onViewModeChange = vm::setDefaultViewMode
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Dimens.paddingSm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.default_sort),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = sortOptionLabel(prefs.defaultSort),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        SortMenuButton(
                            current = prefs.defaultSort,
                            onSortSelected = vm::setDefaultSort
                        )
                    }
                }
            }

            // ----- Notebook defaults -----
            item(key = "notebook-defaults") {
                SettingsSectionCard(
                    title = stringResource(R.string.settings_notebook_defaults),
                    icon = Icons.Outlined.Draw
                ) {
                    Text(
                        text = stringResource(R.string.notebook_defaults_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(Dimens.paddingSm))
                    NanoteSecondaryButton(
                        text = stringResource(R.string.customize_defaults),
                        onClick = { showDefaultsForm = true },
                        leadingIcon = Icons.Outlined.Palette
                    )
                }
            }

            // ----- Pen & tools -----
            item(key = "pen") {
                SettingsSectionCard(
                    title = stringResource(R.string.settings_pen),
                    icon = Icons.Outlined.Draw
                ) {
                    LabelledSwitchRow(
                        title = stringResource(R.string.pressure_sensitivity),
                        checked = pressure,
                        onCheckedChange = vm::setPressure
                    )
                    LabelledSwitchRow(
                        title = stringResource(R.string.palm_rejection),
                        checked = palm,
                        onCheckedChange = vm::setPalm
                    )
                    LabelledSwitchRow(
                        title = stringResource(R.string.prefetch_pages),
                        checked = prefetch,
                        onCheckedChange = vm::setPrefetch
                    )
                }
            }

            // ----- Storage -----
            item(key = "storage") {
                SettingsSectionCard(
                    title = stringResource(R.string.settings_storage),
                    icon = Icons.Outlined.Storage
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Dimens.paddingSm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.storage_usage),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = formatStorageSize(storageBytes),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(Dimens.paddingSm))
                    NanoteSecondaryButton(
                        text = stringResource(R.string.clear_thumbnails),
                        onClick = {
                            vm.clearThumbs()
                            Toast.makeText(
                                context,
                                context.getString(R.string.thumbnails_cleared),
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        leadingIcon = Icons.Outlined.DeleteSweep
                    )
                }
            }

            // ----- About -----
            item(key = "about") {
                SettingsSectionCard(
                    title = stringResource(R.string.settings_about),
                    icon = Icons.Outlined.Info
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Dimens.paddingSm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.CloudOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(Dimens.paddingMd))
                        Text(
                            text = stringResource(R.string.offline_statement),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    AboutRow(stringResource(R.string.app), stringResource(CommonR.string.app_name))
                    AboutRow(stringResource(R.string.version), vm.appVersion)
                }
            }
        }
    }

    if (showDefaultsForm) {
        var form by remember(prefs.notebookDefaults) {
            mutableStateOf(
                NotebookFormState.fromRequestDefaults(prefs.notebookDefaults)
            )
        }
        NotebookFormDialog(
            state = form,
            onStateChange = { form = it },
            onDismiss = { showDefaultsForm = false },
            onConfirm = {
                showDefaultsForm = false
                vm.setNotebookDefaults(
                    NotebookDefaults(
                        coverColor = form.coverColor,
                        icon = form.icon,
                        pageTemplate = form.pageTemplate,
                        pageOrientation = form.pageOrientation,
                        pageSize = form.pageSize
                    )
                )
            },
            dialogTitle = stringResource(R.string.settings_notebook_defaults),
            confirmLabel = stringResource(CommonR.string.confirm),
            showTitleField = false
        )
    }
}

@Composable
private fun ThemeOptionRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.radiusMd))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = Dimens.paddingXs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(Dimens.paddingSm))
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageDropdown(current: AppLanguage, onSelect: (AppLanguage) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(
        AppLanguage.SYSTEM to stringResource(R.string.lang_system),
        AppLanguage.ENGLISH to stringResource(R.string.lang_english),
        AppLanguage.ARABIC to stringResource(R.string.lang_arabic)
    )
    val currentLabel = options.firstOrNull { it.first == current }?.second.orEmpty()

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = currentLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.language)) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            shape = RoundedCornerShape(Dimens.radiusMd),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { (mode, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelect(mode)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.paddingSm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun formatStorageSize(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> stringResource(R.string.size_gb, gb)
        mb >= 1.0 -> stringResource(R.string.size_mb, mb)
        kb >= 1.0 -> stringResource(R.string.size_kb, kb)
        else -> stringResource(R.string.size_bytes, bytes)
    }
}
