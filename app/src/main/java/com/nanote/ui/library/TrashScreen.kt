package com.nanote.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.background
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nanote.R
import com.nanote.data.repo.NotebookRepository
import com.nanote.domain.model.Notebook
import com.nanote.ui.components.EmptyState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class TrashViewModel @Inject constructor(
    private val repo: NotebookRepository
) : ViewModel() {

    val trashed: StateFlow<List<Notebook>> = repo.observeTrashedNotebooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun restore(ids: List<String>) = viewModelScope.launch { repo.restoreNotebooks(ids) }
    fun restoreAll() = viewModelScope.launch { repo.restoreNotebooks(trashed.value.map { it.id }) }

    fun purgeOne(id: String) = viewModelScope.launch { repo.purgeNotebooks(listOf(id)) }

    fun purgeAll() = viewModelScope.launch {
        val ids = trashed.value.map { it.id }
        repo.purgeNotebooks(ids)
    }
}

/** Trash: soft-deleted notebooks with restore and auto-purge after 30 days. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(vm: TrashViewModel = hiltViewModel()) {
    val trashed by vm.trashed.collectAsState()
    var confirmPurgeAll by remember { mutableStateOf(false) }
    var confirmPurgeOne by remember { mutableStateOf<Notebook?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_trash)) },
                actions = {
                    if (trashed.isNotEmpty()) {
                        IconButton(onClick = { vm.restoreAll() }) {
                            Icon(Icons.Outlined.RestoreFromTrash, contentDescription = stringResource(R.string.restore_all))
                        }
                        IconButton(onClick = { confirmPurgeAll = true }) {
                            Icon(Icons.Filled.DeleteForever, contentDescription = stringResource(R.string.empty_trash))
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (trashed.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    icon = Icons.Outlined.Delete,
                    title = stringResource(R.string.trash_empty_title),
                    hint = stringResource(R.string.trash_empty_hint)
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(trashed, key = { it.id }) { nb ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(nb.coverArgb))
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(nb.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                stringResource(R.string.trashed_at) + " " +
                                    DateFormat.getDateInstance(DateFormat.SHORT).format(Date(nb.trashedAt ?: 0)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { vm.restore(listOf(nb.id)) }) {
                            Icon(Icons.Outlined.RestoreFromTrash, contentDescription = stringResource(R.string.action_restore))
                        }
                        IconButton(onClick = { confirmPurgeOne = nb }) {
                            Icon(Icons.Filled.DeleteForever, contentDescription = stringResource(R.string.action_delete))
                        }
                    }
                }
            }
        }
    }

    if (confirmPurgeAll) {
        AlertDialog(
            onDismissRequest = { confirmPurgeAll = false },
            title = { Text(stringResource(R.string.empty_trash)) },
            text = { Text(stringResource(R.string.empty_trash_confirm)) },
            confirmButton = {
                TextButton(onClick = { vm.purgeAll(); confirmPurgeAll = false }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmPurgeAll = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
    confirmPurgeOne?.let { nb ->
        AlertDialog(
            onDismissRequest = { confirmPurgeOne = null },
            title = { Text(stringResource(R.string.purge_notebook_title)) },
            text = { Text(stringResource(R.string.purge_notebook_text)) },
            confirmButton = {
                TextButton(onClick = { vm.purgeOne(nb.id); confirmPurgeOne = null }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmPurgeOne = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}
