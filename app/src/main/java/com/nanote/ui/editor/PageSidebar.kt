package com.nanote.ui.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nanote.R
import com.nanote.domain.model.Page
import com.nanote.ink.engine.PAPERS
import com.nanote.ink.engine.PAGE_SIZES_PT
import com.nanote.ink.engine.paperById

/**
 * Page sidebar: permanent on tablets, a bottom sheet on phones (spec 8).
 * Add / duplicate / delete / reorder pages with one tap each.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageSidebar(
    expanded: Boolean,
    pages: List<Page>,
    currentIndex: Int,
    thumbs: Map<String, android.graphics.Bitmap?>,
    onThumbRequest: (String) -> Unit,
    onOpenPage: (Int) -> Unit,
    onAdd: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onMove: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    if (expanded) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(8.dp)
        ) {
            SidebarHeader(onAdd, onDuplicate, onDelete, onOpenSettings)
            PageList(pages, currentIndex, thumbs, onThumbRequest, onOpenPage, onMove, vertical = true)
        }
    } else {
        ModalBottomSheet(onDismissRequest = onDismiss) {
            Column(Modifier.padding(bottom = 24.dp)) {
                SidebarHeader(onAdd, onDuplicate, onDelete, onOpenSettings)
                PageList(pages, currentIndex, thumbs, onThumbRequest, onOpenPage, onMove, vertical = false)
            }
        }
    }
}

@Composable
private fun SidebarHeader(
    onAdd: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onSettings: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(R.string.pages_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onSettings) {
            Icon(Icons.Filled.Tune, contentDescription = stringResource(R.string.page_settings))
        }
        IconButton(onClick = onDuplicate) {
            Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.action_duplicate_page))
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete_page))
        }
        IconButton(onClick = onAdd) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_add_page))
        }
    }
}

@Composable
private fun PageList(
    pages: List<Page>,
    currentIndex: Int,
    thumbs: Map<String, android.graphics.Bitmap?>,
    onThumbRequest: (String) -> Unit,
    onOpenPage: (Int) -> Unit,
    onMove: (Int) -> Unit,
    vertical: Boolean
) {
    if (vertical) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(pages, key = { _, p -> p.id }) { index, page ->
                PageThumb(
                    page = page,
                    number = index + 1,
                    selected = index == currentIndex,
                    bmp = thumbs[page.id],
                    onThumbRequest = onThumbRequest,
                    onClick = { onOpenPage(index) },
                    onMove = onMove,
                    horizontalArrows = true
                )
            }
        }
    } else {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)
        ) {
            itemsIndexed(pages, key = { _, p -> p.id }) { index, page ->
                PageThumb(
                    page = page,
                    number = index + 1,
                    selected = index == currentIndex,
                    bmp = thumbs[page.id],
                    onThumbRequest = onThumbRequest,
                    onClick = { onOpenPage(index) },
                    onMove = onMove,
                    horizontalArrows = false
                )
            }
        }
    }
}

@Composable
private fun PageThumb(
    page: Page,
    number: Int,
    selected: Boolean,
    bmp: android.graphics.Bitmap?,
    onThumbRequest: (String) -> Unit,
    onClick: () -> Unit,
    onMove: (Int) -> Unit,
    horizontalArrows: Boolean
) {
    onThumbRequest(page.id)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(96.dp)
    ) {
        Box(
            Modifier
                .size(width = 88.dp, height = 110.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(8.dp)
                )
                .clickable(onClick = onClick)
        ) {
            bmp?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = stringResource(R.string.page_number, number),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } ?: Box(
                Modifier
                    .fillMaxSize()
                    .padding(6.dp)
            ) {
                PaperPreview(page)
            }
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 4.dp)
            )
        }
        if (horizontalArrows) {
            Row {
                IconButton(onClick = { onMove(-1) }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.move_left), modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = { onMove(1) }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.move_right), modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/** Tiny live preview of the paper template. */
@Composable
private fun PaperPreview(page: Page) {
    val paper = paperById(page.paper)
    val lineColor = androidx.compose.ui.graphics.Color(paper.argb)
    val bg = androidx.compose.ui.graphics.Color(if (page.bgArgb != 0) page.bgArgb else 0xFFFFFFFF.toInt())
    Box(
        Modifier
            .fillMaxSize()
            .background(bg)
    ) {
        if (paper.spacing > 0f) {
            val rows = ((110f / paper.spacing).toInt() + 1).coerceAtMost(12)
            Column(Modifier.fillMaxSize()) {
                repeat(rows) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(lineColor.copy(alpha = 0.6f))
                    )
                    Spacer(Modifier.height((paper.spacing / 4).dp))
                }
            }
        }
    }
}

/** Page settings: paper template, size, background color (spec 3 pages M/S). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageSettingsSheet(
    page: Page?,
    onPaperPick: (String, Int) -> Unit,
    onSizePick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.page_settings),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // paper templates
            Text(
                stringResource(R.string.paper_section),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                items(PAPERS.toList()) { paper ->
                    val id = paper.id
                    Surface2(
                        selected = page?.paper == id,
                        label = paperLabel(id),
                        onClick = { onPaperPick(id, page?.bgArgb ?: 0xFFFFFFFF.toInt()) }
                    )
                }
            }

            // sizes
            Text(
                stringResource(R.string.size_section),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                items(PAGE_SIZES_PT.keys.toList()) { size ->
                    Surface2(
                        selected = page != null && page.widthPt == PAGE_SIZES_PT.getValue(size).first &&
                            page.heightPt == PAGE_SIZES_PT.getValue(size).second,
                        label = size,
                        onClick = { onSizePick(size) }
                    )
                }
            }

            // background colors
            Text(
                stringResource(R.string.background_section),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                val bgs = listOf(
                    0xFFFFFFFF.toInt(), 0xFFFFF8E1.toInt(), 0xFFE3F2FD.toInt(), 0xFFF3E5F5.toInt(), 0xFF212121.toInt()
                )
                bgs.forEach { c ->
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(50))
                            .background(androidx.compose.ui.graphics.Color(c))
                            .border(
                                width = if (page?.bgArgb == c) 3.dp else 1.dp,
                                color = if (page?.bgArgb == c) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                shape = RoundedCornerShape(50)
                            )
                            .clickable { onPaperPick(page?.paper ?: "blank", c) }
                    )
                }
            }
        }
    }
}

@Composable
private fun Surface2(selected: Boolean, label: String, onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        onClick = onClick
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun paperLabel(id: String): String = when (id) {
    "blank" -> stringResource(R.string.paper_blank)
    "lined_narrow" -> stringResource(R.string.paper_narrow)
    "lined_wide" -> stringResource(R.string.paper_wide)
    "grid_5mm" -> stringResource(R.string.paper_grid)
    "dots_5mm" -> stringResource(R.string.paper_dots)
    "cornell" -> stringResource(R.string.paper_cornell)
    else -> id
}
