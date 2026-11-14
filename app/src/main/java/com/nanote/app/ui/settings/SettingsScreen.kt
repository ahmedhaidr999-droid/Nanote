package com.nanote.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nanote.app.R
import com.nanote.app.core.model.AppLanguage
import com.nanote.app.core.model.ThemeMode
import com.nanote.app.ui.common.LabelledSwitchRow
import com.nanote.app.ui.common.NanoteIconButton
import com.nanote.app.ui.common.NanoteSecondaryButton
import com.nanote.app.ui.common.SettingsSectionCard
import com.nanote.app.ui.theme.Dimens

/**
 * Settings: appearance (theme / dynamic colors / language), pen behaviour,
 * AI provider configuration + connection test, storage and about info.
 */
@Composable
fun SettingsScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    val context = LocalContext.current

    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    val dynamicColors by vm.dynamicColors.collectAsStateWithLifecycle()
    val language by vm.language.collectAsStateWithLifecycle()
    val pressure by vm.pressureSensitivity.collectAsStateWithLifecycle()
    val palm by vm.palmRejection.collectAsStateWithLifecycle()
    val prefetch by vm.prefetchPages.collectAsStateWithLifecycle()
    val testState by vm.testState.collectAsStateWithLifecycle()
    val storageBytes by vm.storageBytes.collectAsStateWithLifecycle()

    var aiUrl by remember { mutableStateOf(vm.aiConfig.value.baseUrl) }
    var aiKey by remember { mutableStateOf(vm.aiConfig.value.apiKey) }
    var aiModel by remember { mutableStateOf(vm.aiConfig.value.model) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // ----- Header -----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = Dimens.paddingSm,
                    end = Dimens.paddingSm,
                    top = Dimens.paddingSm,
                    bottom = Dimens.paddingXs
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NanoteIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDesc = stringResource(R.string.back),
                onClick = onBack
            )
            Spacer(Modifier.width(Dimens.paddingSm))
            Text(
                text = stringResource(R.string.settings),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Dimens.paddingMd,
                end = Dimens.paddingMd,
                bottom = Dimens.paddingLg
            ),
            verticalArrangement = Arrangement.spacedBy(Dimens.paddingMd)
        ) {
            // ----- Appearance -----
            item {
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
                        selected = themeMode == ThemeMode.SYSTEM,
                        onSelect = { vm.setThemeMode(ThemeMode.SYSTEM) }
                    )
                    ThemeOptionRow(
                        label = stringResource(R.string.theme_light),
                        selected = themeMode == ThemeMode.LIGHT,
                        onSelect = { vm.setThemeMode(ThemeMode.LIGHT) }
                    )
                    ThemeOptionRow(
                        label = stringResource(R.string.theme_dark),
                        selected = themeMode == ThemeMode.DARK,
                        onSelect = { vm.setThemeMode(ThemeMode.DARK) }
                    )
                    Spacer(Modifier.height(Dimens.paddingSm))
                    LabelledSwitchRow(
                        title = stringResource(R.string.dynamic_colors),
                        subtitle = stringResource(R.string.dynamic_colors_desc),
                        checked = dynamicColors,
                        onCheckedChange = vm::setDynamicColors
                    )
                    Spacer(Modifier.height(Dimens.paddingSm))
                    LanguageDropdown(
                        current = language,
                        onSelect = vm::setLanguage
                    )
                }
            }

            // ----- Pen & tools -----
            item {
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

            // ----- AI -----
            item {
                SettingsSectionCard(
                    title = stringResource(R.string.settings_ai),
                    icon = Icons.AutoMirrored.Outlined.Chat
                ) {
                    OutlinedTextField(
                        value = aiUrl,
                        onValueChange = {
                            aiUrl = it
                            vm.updateAi(it, aiKey, aiModel)
                        },
                        label = { Text(stringResource(R.string.ai_provider_url)) },
                        singleLine = true,
                        shape = RoundedCornerShape(Dimens.radiusMd),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(Dimens.paddingSm))
                    OutlinedTextField(
                        value = aiKey,
                        onValueChange = {
                            aiKey = it
                            vm.updateAi(aiUrl, it, aiModel)
                        },
                        label = { Text(stringResource(R.string.ai_provider_key)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        shape = RoundedCornerShape(Dimens.radiusMd),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(Dimens.paddingSm))
                    OutlinedTextField(
                        value = aiModel,
                        onValueChange = {
                            aiModel = it
                            vm.updateAi(aiUrl, aiKey, it)
                        },
                        label = { Text(stringResource(R.string.ai_model)) },
                        singleLine = true,
                        shape = RoundedCornerShape(Dimens.radiusMd),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(Dimens.paddingMd))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.paddingMd)
                    ) {
                        NanoteSecondaryButton(
                            text = stringResource(R.string.ai_test),
                            onClick = { vm.testAi() },
                            leadingIcon = Icons.Outlined.Bolt
                        )
                        when (testState) {
                            TestState.Idle -> Spacer(Modifier.weight(1f))

                            TestState.Running -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = stringResource(R.string.ai_thinking),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.weight(1f))
                            }

                            TestState.Ok -> Text(
                                text = stringResource(R.string.ai_test_ok),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )

                            is TestState.Fail -> Text(
                                text = stringResource(R.string.ai_test_fail),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // ----- Storage -----
            item {
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
                            color = MaterialTheme.colorScheme.onSurface,
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
            item {
                SettingsSectionCard(
                    title = stringResource(R.string.settings_about),
                    icon = Icons.Outlined.Info
                ) {
                    SettingsRow(
                        label = stringResource(R.string.app),
                        value = stringResource(R.string.app_name)
                    )
                    SettingsRow(
                        label = stringResource(R.string.version),
                        value = vm.appVersion
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Private helpers
// ---------------------------------------------------------------------------

/** Selectable radio row for the theme picker. */
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
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** Read-only language picker rendered as an exposed dropdown. */
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

/** Simple label/value row used in the About section. */
@Composable
private fun SettingsRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.paddingSm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Formats a byte count as B / KB / MB / GB with localized units. */
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
