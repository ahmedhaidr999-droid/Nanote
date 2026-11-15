package com.nanote.feature.editor.menus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nanote.feature.editor.EditorViewModel
import com.nanote.feature.editor.R
import java.text.DateFormat
import java.util.Date

/**
 * PDF details sheet: source file name, size, page count, document
 * information dictionary (title / author / creator / producer when the
 * file carries them) and import / open timestamps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfInfoSheet(
    vm: EditorViewModel,
    onDismiss: () -> Unit
) {
    val info by vm.pdfInfo.collectAsStateWithLifecycle()
    val dateFormat = androidx.compose.runtime.remember {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.pdf_info_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            val i = info
            if (i == null) {
                Text(stringResource(R.string.pdf_info_unavailable), style = MaterialTheme.typography.bodyMedium)
            } else {
                InfoRow(stringResource(R.string.pdf_info_file), i.sourceFileName)
                InfoRow(stringResource(R.string.pdf_info_size), formatBytes(i.fileSizeBytes))
                InfoRow(stringResource(R.string.pdf_info_pages), i.pageCount.toString())
                i.title?.let { InfoRow(stringResource(R.string.pdf_info_doc_title), it) }
                i.author?.let { InfoRow(stringResource(R.string.pdf_info_author), it) }
                i.creator?.let { InfoRow(stringResource(R.string.pdf_info_creator), it) }
                i.producer?.let { InfoRow(stringResource(R.string.pdf_info_producer), it) }
                InfoRow(stringResource(R.string.pdf_info_imported), dateFormat.format(Date(i.importedAt)))
                if (i.lastOpenedAt > 0) {
                    InfoRow(stringResource(R.string.pdf_info_opened), dateFormat.format(Date(i.lastOpenedAt)))
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    stringResource(R.string.pdf_info_text_search_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 16.dp)
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
