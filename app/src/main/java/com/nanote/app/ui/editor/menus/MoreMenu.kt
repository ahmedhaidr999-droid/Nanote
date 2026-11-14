package com.nanote.app.ui.editor.menus

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Redo
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Splitscreen
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nanote.app.R
import com.nanote.app.ui.editor.PaneController
import com.nanote.app.ui.theme.Dimens
import kotlin.math.roundToInt

/**
 * "More" overflow menu: quick page navigation (slider + add page),
 * document export, split-screen toggle, toolbar customization, settings
 * and an undo/redo row. Focusable popup anchored at the top-end corner.
 */
@Composable
fun MoreMenu(
    pane: PaneController,
    onDismiss: () -> Unit,
    onExportPdf: () -> Unit,
    onExportNanote: () -> Unit,
    onSplitToggle: () -> Unit,
    onCustomize: () -> Unit,
    onSettings: () -> Unit
) {
    val canUndo by pane.canUndo.collectAsStateWithLifecycle()
    val canRedo by pane.canRedo.collectAsStateWithLifecycle()
    val currentPage by pane.currentPage.collectAsStateWithLifecycle()
    val pageCount = pane.pageCount

    var sliderValue by remember { mutableFloatStateOf((currentPage + 1).toFloat()) }
    LaunchedEffect(currentPage) { sliderValue = (currentPage + 1).toFloat() }

    Popup(
        alignment = Alignment.TopEnd,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Surface(
            shape = RoundedCornerShape(Dimens.radiusLg),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 8.dp,
            modifier = Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp)
        ) {
            Column(
                Modifier
                    .padding(8.dp)
                    .widthIn(min = 250.dp, max = 300.dp)
            ) {
                // ---- page navigation section ----
                if (pageCount > 1) {
                    Text(
                        stringResource(R.string.page_indicator, sliderValue.roundToInt(), pageCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        onValueChangeFinished = {
                            pane.goToPage(sliderValue.roundToInt() - 1)
                        },
                        valueRange = 1f..pageCount.toFloat(),
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                }

                MenuRow(Icons.Outlined.Add, stringResource(R.string.add_page)) {
                    pane.addPage()
                    onDismiss()
                }
                MenuRow(Icons.Outlined.PictureAsPdf, stringResource(R.string.export_pdf)) {
                    onExportPdf()
                }
                MenuRow(Icons.Outlined.Description, stringResource(R.string.export_nanote)) {
                    onExportNanote()
                }
                MenuRow(Icons.Outlined.Splitscreen, stringResource(R.string.split_screen)) {
                    onSplitToggle()
                }
                MenuRow(Icons.Outlined.Tune, stringResource(R.string.customize_toolbar)) {
                    onCustomize()
                }
                MenuRow(Icons.Outlined.Settings, stringResource(R.string.settings)) {
                    onSettings()
                }

                HorizontalDivider(
                    Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                // ---- undo / redo ----
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = { pane.undo() },
                        enabled = canUndo,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Outlined.Undo, contentDescription = stringResource(R.string.undo))
                    }
                    IconButton(
                        onClick = { pane.redo() },
                        enabled = canRedo,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Outlined.Redo, contentDescription = stringResource(R.string.redo))
                    }
                }
            }
        }
    }
}

/** Standard overflow-menu row: leading icon + label, 48dp min height, ripple. */
@Composable
private fun MenuRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
