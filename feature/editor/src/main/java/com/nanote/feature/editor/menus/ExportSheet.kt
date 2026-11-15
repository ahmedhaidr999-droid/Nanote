package com.nanote.feature.editor.menus

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nanote.feature.editor.EditorViewModel
import com.nanote.feature.editor.PaneController
import com.nanote.feature.editor.R
import com.nanote.core.model.ExportFormat
import com.nanote.core.model.ExportOptions
import com.nanote.core.model.PdfExportMode

/**
 * Export sheet: format (PDF / PNG / JPEG / .nanote), PDF mode
 * (flattened / original / markup annotations), optional page selection,
 * and direct Share / Print actions. Exports to a file happen through the
 * SAF launcher callback [onExportToFile].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExportSheet(
    vm: EditorViewModel,
    pane: PaneController,
    onDismiss: () -> Unit,
    onExportToFile: (ExportOptions, String) -> Unit
) {
    val defaultFormat by vm.settings.defaultExportFormat.collectAsStateWithLifecycle()
    val defaultMode by vm.settings.defaultPdfExportMode.collectAsStateWithLifecycle()
    val isPdf = pane.isPdf
    val currentPage by pane.currentPage.collectAsStateWithLifecycle()

    var format by remember { mutableStateOf(defaultFormat) }
    var pdfMode by remember { mutableStateOf(defaultMode) }
    var pagesSelection by remember { mutableStateOf(false) }
    var selectedPages by remember { mutableStateOf(setOf(currentPage)) }

    val title = vm.noteTitle.value.ifBlank { "nanote" }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            Text(
                stringResource(R.string.export_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // ---------------- format ----------------
            Text(
                stringResource(R.string.export_format_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                Modifier.padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = format == ExportFormat.PDF,
                    onClick = { format = ExportFormat.PDF },
                    label = { Text(stringResource(R.string.export_format_pdf)) },
                    leadingIcon = { Icon(Icons.Outlined.PictureAsPdf, null, Modifier.size(18.dp)) }
                )
                FilterChip(
                    selected = format == ExportFormat.PNG,
                    onClick = { format = ExportFormat.PNG },
                    label = { Text(stringResource(R.string.export_format_png)) },
                    leadingIcon = { Icon(Icons.Outlined.Image, null, Modifier.size(18.dp)) }
                )
                FilterChip(
                    selected = format == ExportFormat.JPEG,
                    onClick = { format = ExportFormat.JPEG },
                    label = { Text(stringResource(R.string.export_format_jpeg)) },
                    leadingIcon = { Icon(Icons.Outlined.Image, null, Modifier.size(18.dp)) }
                )
                FilterChip(
                    selected = format == ExportFormat.NANOTE,
                    onClick = { format = ExportFormat.NANOTE },
                    label = { Text(stringResource(R.string.export_format_nanote)) },
                    leadingIcon = { Icon(Icons.Outlined.Description, null, Modifier.size(18.dp)) }
                )
            }

            // ---------------- PDF mode ----------------
            if (format == ExportFormat.PDF && isPdf) {
                Text(
                    stringResource(R.string.export_pdf_mode_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    Modifier.padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = pdfMode == PdfExportMode.FLATTENED,
                        onClick = { pdfMode = PdfExportMode.FLATTENED },
                        label = { Text(stringResource(R.string.export_mode_flattened)) }
                    )
                    FilterChip(
                        selected = pdfMode == PdfExportMode.WITH_MARKUP_ANNOTATIONS,
                        onClick = { pdfMode = PdfExportMode.WITH_MARKUP_ANNOTATIONS },
                        label = { Text(stringResource(R.string.export_mode_markup)) }
                    )
                    FilterChip(
                        selected = pdfMode == PdfExportMode.ORIGINAL,
                        onClick = { pdfMode = PdfExportMode.ORIGINAL },
                        label = { Text(stringResource(R.string.export_mode_original)) }
                    )
                }
                if (pdfMode == PdfExportMode.ORIGINAL) {
                    Text(
                        stringResource(R.string.export_mode_original_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (pdfMode == PdfExportMode.WITH_MARKUP_ANNOTATIONS) {
                    Text(
                        stringResource(R.string.export_mode_markup_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ---------------- page selection ----------------
            if (pane.pageCount > 1 && (format == ExportFormat.PDF || isPdf)) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.export_pages_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { pagesSelection = !pagesSelection }) {
                        Text(
                            if (pagesSelection) {
                                stringResource(R.string.export_pages_all)
                            } else {
                                stringResource(R.string.export_pages_choose)
                            }
                        )
                    }
                }
                if (pagesSelection) {
                    Text(
                        stringResource(
                            R.string.export_pages_selected,
                            selectedPages.size, pane.pageCount
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    // compact page chip grid
                    val rows = pane.pageCount
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (p in 0 until rows) {
                            FilterChip(
                                selected = p in selectedPages,
                                onClick = {
                                    selectedPages = if (p in selectedPages) {
                                        selectedPages - p
                                    } else {
                                        selectedPages + p
                                    }
                                },
                                label = { Text("${p + 1}") }
                            )
                        }
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            // ---------------- actions ----------------
            val options = ExportOptions(
                format = format,
                pdfMode = pdfMode,
                pages = if (pagesSelection && selectedPages.isNotEmpty()) selectedPages else null
            )

            ActionRow(Icons.Outlined.Description, stringResource(R.string.export_save_to_file)) {
                val ext = when (format) {
                    ExportFormat.PDF -> "pdf"
                    ExportFormat.PNG -> "png"
                    ExportFormat.JPEG -> "jpg"
                    ExportFormat.NANOTE -> "nanote"
                }
                onDismiss()
                onExportToFile(options, "$title.$ext")
            }
            ActionRow(Icons.Outlined.Share, stringResource(R.string.export_share)) {
                onDismiss()
                vm.shareExport(options)
            }
            ActionRow(Icons.Outlined.Print, stringResource(R.string.export_print)) {
                onDismiss()
                vm.printDocument()
            }

            if (format != ExportFormat.NANOTE && !isPdf) {
                Text(
                    stringResource(R.string.export_image_single_page_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
