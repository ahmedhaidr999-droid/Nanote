package com.nanote.feature.notebooks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nanote.core.design.R as CommonR
import com.nanote.core.design.components.COVER_COLORS
import com.nanote.core.design.components.CoverScribble
import com.nanote.core.design.components.NOTEBOOK_ICONS
import com.nanote.core.design.components.OptionCheckRow
import com.nanote.core.design.components.PageTemplatePattern
import com.nanote.core.design.components.composeColor
import com.nanote.core.design.components.imageVector
import com.nanote.core.design.components.pageSizeMm
import com.nanote.core.design.theme.Dimens
import com.nanote.core.model.CreateNotebookRequest
import com.nanote.core.model.CoverColor
import com.nanote.core.model.NotebookIcon
import com.nanote.core.model.PageOrientation
import com.nanote.core.model.PageSize
import com.nanote.core.model.PageTemplate

/**
 * Mutable form state behind [NotebookFormDialog] — create *and* edit
 * (notebook settings) share the same presentation controls.
 */
data class NotebookFormState(
    val title: String,
    val coverColor: CoverColor,
    val icon: NotebookIcon,
    val pageTemplate: PageTemplate,
    val pageOrientation: PageOrientation,
    val pageSize: PageSize
) {
    fun toRequest(folderId: String?): CreateNotebookRequest = CreateNotebookRequest(
        title = title.trim(),
        folderId = folderId,
        coverColor = coverColor,
        icon = icon,
        pageTemplate = pageTemplate,
        pageOrientation = pageOrientation,
        pageSize = pageSize
    )

    companion object {
        fun fromRequestDefaults(defaults: com.nanote.core.model.NotebookDefaults, title: String = "") =
            NotebookFormState(
                title = title,
                coverColor = defaults.coverColor,
                icon = defaults.icon,
                pageTemplate = defaults.pageTemplate,
                pageOrientation = defaults.pageOrientation,
                pageSize = defaults.pageSize
            )
    }
}

/**
 * Full notebook form: title, cover colour, icon, page template, orientation
 * and page size. Confirm delivers the finished [NotebookFormState].
 */
@Composable
fun NotebookFormDialog(
    state: NotebookFormState,
    onStateChange: (NotebookFormState) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    dialogTitle: String,
    confirmLabel: String = stringResource(CommonR.string.create),
    showTitleField: Boolean = true
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dialogTitle, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Dimens.paddingMd)
            ) {
                if (showTitleField) {
                    OutlinedTextField(
                        value = state.title,
                        onValueChange = { onStateChange(state.copy(title = it)) },
                        singleLine = true,
                        placeholder = { Text(stringResource(CommonR.string.note_title_hint)) },
                        shape = RoundedCornerShape(Dimens.radiusMd),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // ---- Cover colour ----
                FormLabel(stringResource(R.string.nb_cover_color))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Dimens.paddingSm),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    COVER_COLORS.take(4).forEach { color ->
                        ColorSwatch(
                            color = color,
                            selected = state.coverColor == color,
                            onClick = { onStateChange(state.copy(coverColor = color)) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Dimens.paddingSm),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    COVER_COLORS.drop(4).forEach { color ->
                        ColorSwatch(
                            color = color,
                            selected = state.coverColor == color,
                            onClick = { onStateChange(state.copy(coverColor = color)) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // ---- Icon ----
                FormLabel(stringResource(R.string.nb_icon))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(104.dp),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.paddingSm),
                    verticalArrangement = Arrangement.spacedBy(Dimens.paddingSm)
                ) {
                    items(NOTEBOOK_ICONS.size) { index ->
                        val icon = NOTEBOOK_ICONS[index]
                        val selected = state.icon == icon
                        Surface(
                            shape = RoundedCornerShape(Dimens.radiusMd),
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.clickable { onStateChange(state.copy(icon = icon)) }
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = icon.imageVector(),
                                    contentDescription = null,
                                    tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }

                // ---- Page template ----
                FormLabel(stringResource(R.string.nb_template))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Dimens.paddingSm),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    PageTemplate.entries.forEach { template ->
                        val selected = state.pageTemplate == template
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(Dimens.radiusMd))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable { onStateChange(state.copy(pageTemplate = template)) }
                                .padding(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .clip(RoundedCornerShape(Dimens.radiusSm))
                                    .background(Color.White)
                                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(Dimens.radiusSm))
                            ) {
                                PageTemplatePattern(
                                    template = template,
                                    lineColor = Color(0x33000000),
                                    modifier = Modifier.matchParentSize().padding(4.dp)
                                )
                            }
                            Text(
                                text = templateLabel(template),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }

                // ---- Orientation + size ----
                FormLabel(stringResource(R.string.nb_orientation))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Dimens.paddingMd),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OptionCheckRow(
                        label = stringResource(R.string.nb_portrait),
                        selected = state.pageOrientation == PageOrientation.PORTRAIT,
                        onSelect = { onStateChange(state.copy(pageOrientation = PageOrientation.PORTRAIT)) },
                        modifier = Modifier.weight(1f)
                    )
                    OptionCheckRow(
                        label = stringResource(R.string.nb_landscape),
                        selected = state.pageOrientation == PageOrientation.LANDSCAPE,
                        onSelect = { onStateChange(state.copy(pageOrientation = PageOrientation.LANDSCAPE)) },
                        modifier = Modifier.weight(1f)
                    )
                }

                FormLabel(stringResource(R.string.nb_page_size))
                PageSize.entries.forEach { size ->
                    OptionCheckRow(
                        label = "${sizeLabel(size)} · ${pageSizeMm(size)}",
                        selected = state.pageSize == size,
                        onSelect = { onStateChange(state.copy(pageSize = size)) }
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.cancel)) }
        },
        shape = RoundedCornerShape(Dimens.radiusLg),
        containerColor = MaterialTheme.colorScheme.surface
    )
}

@Composable
private fun FormLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ColorSwatch(
    color: CoverColor,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(Dimens.radiusMd))
            .background(color.composeColor())
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(Dimens.radiusMd)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        } else {
            CoverScribble(
                color = Color(0x33FFFFFF),
                modifier = Modifier.fillMaxWidth().height(26.dp).padding(horizontal = 8.dp)
            )
        }
    }
}

@Composable
fun templateLabel(template: PageTemplate): String = when (template) {
    PageTemplate.PLAIN -> stringResource(R.string.template_plain)
    PageTemplate.LINED -> stringResource(R.string.template_lined)
    PageTemplate.GRID -> stringResource(R.string.template_grid)
    PageTemplate.DOTTED -> stringResource(R.string.template_dotted)
    PageTemplate.CORNELL -> stringResource(R.string.template_cornell)
}

@Composable
fun sizeLabel(size: PageSize): String = when (size) {
    PageSize.A4 -> stringResource(R.string.size_a4)
    PageSize.A5 -> stringResource(R.string.size_a5)
    PageSize.LETTER -> stringResource(R.string.size_letter)
}
