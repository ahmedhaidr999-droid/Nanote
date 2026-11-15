package com.nanote.feature.editor.menus

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.PostAdd
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nanote.feature.editor.EditorViewModel
import com.nanote.feature.editor.PaneController
import com.nanote.feature.editor.R
import com.nanote.core.design.theme.Dimens
import com.nanote.core.model.PdfBookmark

/**
 * Pages panel: thumbnail list with jump-to-page, bookmark indicators,
 * per-page operations (rotate / duplicate / move / delete) and the
 * insert-pages-from-PDF action for imported documents.
 */
@Composable
fun PagePanel(
    vm: EditorViewModel,
    pane: PaneController,
    onDismiss: () -> Unit,
    onInsertFromPdf: () -> Unit
) {
    val currentPage by pane.currentPage.collectAsStateWithLifecycle()
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val pageOpRunning by vm.pageOpRunning.collectAsStateWithLifecycle()
    val isPdf = pane.isPdf

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.pages_panel_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                if (isPdf) {
                    IconButtonLight(
                        icon = if (bookmarks.any { it.pageIndex == currentPage }) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                        label = stringResource(R.string.bookmark_toggle),
                        tint = if (bookmarks.any { it.pageIndex == currentPage }) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    ) { vm.toggleBookmarkOnCurrentPage() }
                }
            }

            if (pageOpRunning) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        stringResource(R.string.page_op_running),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            val listState = rememberLazyListState()
            LaunchedEffect(currentPage) {
                if (currentPage >= 0) listState.animateScrollToItem(currentPage.coerceAtLeast(0))
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 8.dp)
            ) {
                itemsIndexed(List(pane.pageCount) { it }, key = { _, p -> p }) { _, pageIndex ->
                    PageRow(
                        vm = vm,
                        pane = pane,
                        pageIndex = pageIndex,
                        isCurrent = pageIndex == currentPage,
                        bookmark = bookmarks.firstOrNull { it.pageIndex == pageIndex },
                        isPdf = isPdf,
                        onBookmarkClick = {
                            if (pageIndex == currentPage) {
                                vm.toggleBookmarkOnCurrentPage()
                            } else {
                                pane.goToPage(pageIndex)
                            }
                        }
                    )
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(onClick = { pane.addPage(); }) {
                            Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.add_page))
                        }
                        if (isPdf) {
                            TextButton(onClick = onInsertFromPdf) {
                                Icon(Icons.Outlined.PostAdd, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.insert_pages_from_pdf))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PageRow(
    vm: EditorViewModel,
    pane: PaneController,
    pageIndex: Int,
    isCurrent: Boolean,
    bookmark: PdfBookmark?,
    isPdf: Boolean,
    onBookmarkClick: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var showOps by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.radiusMd))
            .clickable {
                pane.goToPage(pageIndex)
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // thumbnail (lazy async render, cached by produceState scope)
        val bitmap by produceState<Bitmap?>(initialValue = null, pageIndex) {
            value = runCatching { vm.pageThumbnail(pageIndex) }.getOrNull()
        }
        Surface(
            shape = RoundedCornerShape(Dimens.radiusMd),
            border = if (isCurrent) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
            shadowElevation = 1.dp
        ) {
            Box(Modifier.width(64.dp).aspectRatio(0.75f), contentAlignment = Alignment.Center) {
                val bmp = bitmap
                if (bmp != null) {
                    Image(
                        bmp.asImageBitmap(),
                        contentDescription = stringResource(R.string.page_thumbnail_cd, pageIndex + 1),
                        modifier = Modifier.fillMaxHeight().fillMaxWidth(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
        }

        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.page_number_label, pageIndex + 1),
                style = MaterialTheme.typography.bodyMedium
            )
            bookmark?.let {
                Text(
                    it.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
            }
        }

        if (isPdf) {
            IconButtonLight(
                icon = if (bookmark != null) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                label = stringResource(R.string.bookmark_toggle),
                tint = if (bookmark != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = onBookmarkClick
            )
            IconButtonLight(Icons.Outlined.Delete, stringResource(R.string.page_op_menu)) {
                showOps = true
            }
        }
    }

    if (showOps) {
        AlertDialog(
            onDismissRequest = { showOps = false },
            title = { Text(stringResource(R.string.page_op_menu_title, pageIndex + 1)) },
            text = {
                Column {
                    OpRow(Icons.Outlined.RotateRight, stringResource(R.string.page_op_rotate)) {
                        vm.rotatePdfPage(pageIndex); showOps = false
                    }
                    OpRow(Icons.Outlined.FileCopy, stringResource(R.string.page_op_duplicate)) {
                        vm.duplicatePdfPage(pageIndex); showOps = false
                    }
                    OpRow(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.page_op_move_up)) {
                        vm.movePdfPage(pageIndex, (pageIndex - 1).coerceAtLeast(0)); showOps = false
                    }
                    OpRow(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.page_op_move_down)) {
                        vm.movePdfPage(pageIndex, (pageIndex + 1).coerceAtMost(pane.pageCount - 1)); showOps = false
                    }
                    OpRow(Icons.Outlined.Delete, stringResource(R.string.page_op_delete), destructive = true) {
                        confirmDelete = true; showOps = false
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showOps = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.page_op_delete_confirm_title)) },
            text = { Text(stringResource(R.string.page_op_delete_confirm_text, pageIndex + 1)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deletePdfPages(setOf(pageIndex))
                    confirmDelete = false
                }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun OpRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun IconButtonLight(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit
) {
    Icon(
        icon,
        contentDescription = label,
        tint = tint,
        modifier = Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(2.dp)
    )
}
