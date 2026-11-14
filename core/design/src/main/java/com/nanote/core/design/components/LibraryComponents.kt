package com.nanote.core.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nanote.core.design.R
import com.nanote.core.design.theme.Dimens
import com.nanote.core.model.DocumentMeta
import com.nanote.core.model.FolderTreeNode
import com.nanote.core.model.NoteKind
import com.nanote.core.model.PageSize
import com.nanote.core.model.SortOption
import com.nanote.core.model.ViewMode

// ---------------------------------------------------------------------------
// Search / sort / view-mode controls
// ---------------------------------------------------------------------------

/** Rounded search field with a leading icon and trailing clear button. */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = stringResource(R.string.search_hint)
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        shape = RoundedCornerShape(Dimens.radiusLg),
        singleLine = true,
        leadingIcon = {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.clear_search),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        placeholder = { Text(hint) }
    )
}

/** Sort menu with the four library sort options. */
@Composable
fun SortMenuButton(
    current: SortOption,
    onSortSelected: (SortOption) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Outlined.Sort,
                contentDescription = stringResource(R.string.sort_by),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortOption.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(sortOptionLabel(option)) },
                    trailingIcon = {
                        if (option == current) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onSortSelected(option)
                    }
                )
            }
        }
    }
}

@Composable
fun sortOptionLabel(option: SortOption): String = when (option) {
    SortOption.RECENTLY_MODIFIED -> stringResource(R.string.sort_recently_modified)
    SortOption.RECENTLY_CREATED -> stringResource(R.string.sort_recently_created)
    SortOption.NAME -> stringResource(R.string.sort_name)
    SortOption.CUSTOM -> stringResource(R.string.sort_custom)
}

/** Grid / list presentation toggle. */
@Composable
fun ViewModeToggle(
    current: ViewMode,
    onViewModeChange: (ViewMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Dimens.radiusMd),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ToggleIcon(
                icon = Icons.Outlined.GridView,
                selected = current == ViewMode.GRID,
                contentDesc = stringResource(R.string.view_grid),
                onClick = { onViewModeChange(ViewMode.GRID) }
            )
            ToggleIcon(
                icon = Icons.Outlined.ViewList,
                selected = current == ViewMode.LIST,
                contentDesc = stringResource(R.string.view_list),
                onClick = { onViewModeChange(ViewMode.LIST) }
            )
        }
    }
}

@Composable
private fun ToggleIcon(
    icon: ImageVector,
    selected: Boolean,
    contentDesc: String,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = icon,
            contentDescription = contentDesc,
            tint = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------------------------------------------------------------------------
// Notebook cover + document cards
// ---------------------------------------------------------------------------

/**
 * Notebook / document cover: coloured rounded rectangle with the page
 * template pattern, a big icon and a page-count badge.
 */
@Composable
fun NotebookCover(
    meta: DocumentMeta,
    modifier: Modifier = Modifier,
    showBadge: Boolean = true
) {
    val shape = RoundedCornerShape(
        topStart = Dimens.radiusLg,
        topEnd = Dimens.radiusLg,
        bottomStart = Dimens.radiusSm,
        bottomEnd = Dimens.radiusSm
    )
    Box(
        modifier = modifier
            .aspectRatio(0.78f)
            .clip(shape)
            .background(meta.coverColor.composeColor())
    ) {
        // Template pattern, subtle white lines.
        PageTemplatePattern(
            template = meta.pageTemplate,
            lineColor = androidx.compose.ui.graphics.Color(0x26FFFFFF),
            modifier = Modifier
                .matchParentSize()
                .padding(horizontal = 10.dp, vertical = 12.dp)
        )
        // Icon badge (bottom-end corner like a real notebook cover).
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .size(30.dp)
                .clip(CircleShape)
                .background(androidx.compose.ui.graphics.Color(0x33000000)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = meta.icon.imageVector(),
                contentDescription = null,
                tint = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.size(17.dp)
            )
        }
        if (showBadge) {
            Text(
                text = pluralStringResource(R.plurals.pages_count, meta.pageCount, meta.pageCount),
                style = MaterialTheme.typography.labelSmall,
                color = androidx.compose.ui.graphics.Color(0xCCFFFFFF),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .background(
                        androidx.compose.ui.graphics.Color(0x40000000),
                        RoundedCornerShape(50)
                    )
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            )
        }
    }
}

/** Grid card for one document (notebook or imported PDF). */
@Composable
fun DocumentCard(
    meta: DocumentMeta,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    Box {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Dimens.radiusLg))
                .background(MaterialTheme.colorScheme.surface)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(Dimens.radiusLg)
                )
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        ) {
            Box {
                NotebookCover(meta = meta, modifier = Modifier.fillMaxWidth())
                if (selectionMode) {
                    Icon(
                        imageVector = if (selected) Icons.Filled.CheckCircle
                        else Icons.Filled.RadioButtonUnchecked,
                        contentDescription = if (selected) stringResource(R.string.selected)
                        else stringResource(R.string.unselected),
                        tint = if (selected) MaterialTheme.colorScheme.primary
                        else androidx.compose.ui.graphics.Color(0xB3FFFFFF),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .size(22.dp)
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.paddingSm, vertical = Dimens.paddingXs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = meta.title.ifBlank { stringResource(R.string.untitled_note) },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = subtitle ?: documentKindLabel(meta),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = if (meta.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = stringResource(
                        if (meta.favorite) R.string.unfavorite else R.string.favorite
                    ),
                    tint = if (meta.favorite) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .combinedClickable(onClick = onToggleFavorite)
                        .padding(6.dp)
                )
            }
        }
    }
}

/** Row item for one document in list mode. */
@Composable
fun DocumentListItem(
    meta: DocumentMeta,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.radiusMd))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = Dimens.paddingSm, vertical = Dimens.paddingSm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) {
            Icon(
                imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = Dimens.paddingSm).size(22.dp)
            )
        }
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(Dimens.radiusSm))
                .background(meta.coverColor.composeColor()),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = meta.icon.imageVector(),
                contentDescription = null,
                tint = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(Dimens.paddingMd))
        Column(Modifier.weight(1f)) {
            Text(
                text = meta.title.ifBlank { stringResource(R.string.untitled_note) },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle ?: documentKindLabel(meta),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = if (meta.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
            contentDescription = stringResource(
                if (meta.favorite) R.string.unfavorite else R.string.favorite
            ),
            tint = if (meta.favorite) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .combinedClickable(onClick = onToggleFavorite)
                .padding(6.dp)
        )
    }
}

@Composable
fun documentKindLabel(meta: DocumentMeta): String = when (meta.kind) {
    NoteKind.NOTE -> stringResource(R.string.kind_notebook)
    NoteKind.PDF -> stringResource(R.string.kind_pdf)
}

// ---------------------------------------------------------------------------
// Folder card + picker
// ---------------------------------------------------------------------------

/** Grid card for one folder. */
@Composable
fun FolderCard(
    name: String,
    count: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.radiusLg))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(Dimens.paddingMd),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(Dimens.radiusMd))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.width(Dimens.paddingMd))
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.folder_items_count, count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.Outlined.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * Folder tree picker used by "Move to folder". [nodes] is the pre-flattened
 * tree; [disabledIds] hides a folder's own subtree when moving a folder.
 */
@Composable
fun FolderPickerDialog(
    title: String,
    nodes: List<FolderTreeNode>,
    onDismiss: () -> Unit,
    onPick: (folderId: String?) -> Unit,
    disabledIds: Set<String> = emptySet(),
    allowRoot: Boolean = true,
    confirmLabel: String = stringResource(R.string.move_here)
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            LazyColumn(
                modifier = Modifier.height(340.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (allowRoot) {
                    item(key = "root") {
                        PickerRow(
                            name = stringResource(R.string.folder_root),
                            depth = 0,
                            enabled = true,
                            onClick = { onPick(null) }
                        )
                    }
                }
                items(nodes.size) { index ->
                    val node = nodes[index]
                    PickerRow(
                        name = node.name,
                        depth = node.depth,
                        enabled = node.id !in disabledIds,
                        onClick = { if (node.id !in disabledIds) onPick(node.id) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        shape = RoundedCornerShape(Dimens.radiusLg),
        containerColor = MaterialTheme.colorScheme.surface
    )
}

@Composable
private fun PickerRow(name: String, depth: Int, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.radiusMd))
            .background(
                if (enabled) MaterialTheme.colorScheme.surface
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            )
            .combinedClickable(enabled = enabled, onClick = onClick)
            .padding(start = Dimens.paddingMd + (depth * 18).dp, end = Dimens.paddingMd)
            .padding(vertical = Dimens.paddingSm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Folder,
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(Dimens.paddingSm))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Small check row used by pickers (page size / orientation). */
@Composable
fun OptionCheckRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.radiusMd))
            .combinedClickable(onClick = onSelect)
            .padding(vertical = Dimens.paddingXs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(Dimens.paddingSm))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Size label with physical dimensions, e.g. "A4 · 210 × 297 mm". */
fun pageSizeMm(size: PageSize): String {
    val w = (size.widthPts / 72f * 25.4f).toInt()
    val h = (size.heightPts / 72f * 25.4f).toInt()
    return "$w × $h mm"
}
