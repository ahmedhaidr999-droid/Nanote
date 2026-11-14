package com.nanote.feature.editor.toolbar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nanote.feature.editor.R
import com.nanote.core.data.SettingsStore
import com.nanote.core.model.ToolbarConfig
import com.nanote.core.model.ToolbarPosition
import com.nanote.core.model.ToolType
import com.nanote.core.design.components.NanoteSecondaryButton
import com.nanote.core.design.theme.Dimens
import kotlin.math.roundToInt

/** Fixed row height of the visible-tool rows (drives the drag slot math). */
private val ToolRowHeight = 56.dp

/**
 * MORE is pinned as the LAST visible tool: it hosts the menu that opens this
 * very sheet, so it must always stay reachable. It can neither be removed
 * nor moved from the last slot, and tools added from the hidden section are
 * inserted just before it.
 */
private fun normalizeVisible(list: List<ToolType>): List<ToolType> {
    val rest = list.filter { it != ToolType.MORE }.distinct()
    return rest + ToolType.MORE
}

/**
 * Toolbar customization sheet: position picker, visible tools (drag-to-
 * reorder plus reliable up/down buttons), hidden tools (add back) and reset.
 *
 * Reordering is implemented BOTH ways: long-press drag (slot math on fixed
 * 56dp rows) for the "drag to reorder" feel, and keyboard-free up/down
 * buttons that always work, including for precise one-step moves. Every
 * mutation goes through [SettingsStore.setToolbarConfig] so it persists
 * instantly and the live toolbar recomposes.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ToolbarCustomizeSheet(settings: SettingsStore, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val cfg by settings.toolbarConfig.collectAsStateWithLifecycle()
    val visible = remember(cfg.visible) { normalizeVisible(cfg.visible) }
    // derive the hidden list so tools absent from both lists (e.g. CAMERA /
    // PDF by default) are still offered for adding
    val hiddenTools = remember(visible) { ToolType.entries.filter { it !in visible } }

    fun addToVisible(tool: ToolType) {
        if (tool in visible) return
        val list = visible.toMutableList()
        // insert before MORE (last) so MORE stays last
        list.add((list.size - 1).coerceAtLeast(0), tool)
        settings.setToolbarConfig(
            cfg.copy(visible = normalizeVisible(list), hidden = cfg.hidden - tool)
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.paddingMd)
                .navigationBarsPadding()
                .padding(bottom = Dimens.paddingMd),
            verticalArrangement = Arrangement.spacedBy(Dimens.paddingSm)
        ) {
            Text(
                text = stringResource(R.string.customize_toolbar),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            // ---- position ----
            SectionLabel(stringResource(R.string.toolbar_position))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolbarPosition.entries.forEach { pos ->
                    FilterChip(
                        selected = cfg.position == pos,
                        onClick = { settings.setToolbarConfig(cfg.copy(position = pos)) },
                        label = {
                            Text(
                                stringResource(
                                    when (pos) {
                                        ToolbarPosition.BOTTOM -> R.string.position_bottom
                                        ToolbarPosition.LEFT -> R.string.position_left
                                        ToolbarPosition.RIGHT -> R.string.position_right
                                    }
                                )
                            )
                        }
                    )
                }
            }

            // ---- visible tools ----
            SectionLabel(stringResource(R.string.visible_tools))
            Text(
                text = stringResource(R.string.drag_to_reorder),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            VisibleToolsSection(settings = settings, cfg = cfg, visible = visible)

            // ---- hidden tools ----
            SectionLabel(stringResource(R.string.hidden_tools))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                hiddenTools.forEach { tool ->
                    HiddenToolChip(tool = tool, onAdd = { addToVisible(tool) })
                }
            }

            // ---- reset ----
            NanoteSecondaryButton(
                text = stringResource(R.string.reset_toolbar),
                onClick = { settings.setToolbarConfig(ToolbarConfig()) },
                leadingIcon = Icons.Outlined.Refresh,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Dimens.paddingSm)
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = Dimens.paddingSm)
    )
}

/**
 * Ordered visible tool rows with long-press drag reordering and up/down /
 * remove buttons. Drag state is shared; per-slot pointer handlers read the
 * latest values through [rememberUpdatedState] so reorders mid-drag keep the
 * gesture alive (a plain capture would go stale after the list changes).
 */
@Composable
private fun VisibleToolsSection(
    settings: SettingsStore,
    cfg: ToolbarConfig,
    visible: List<ToolType>
) {
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowHeightPx = with(LocalDensity.current) { ToolRowHeight.toPx() }
    val haptic = LocalHapticFeedback.current

    fun move(from: Int, to: Int) {
        if (from == to) return
        if (from !in visible.indices || to !in visible.indices) return
        val list = visible.toMutableList()
        val item = list.removeAt(from)
        // never insert after MORE (last element), matching normalizeVisible
        val insertAt = to.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        list.add(insertAt, item)
        settings.setToolbarConfig(cfg.copy(visible = normalizeVisible(list)))
    }

    fun removeAt(index: Int) {
        if (index !in visible.indices) return
        val tool = visible[index]
        if (tool == ToolType.MORE) return
        settings.setToolbarConfig(
            cfg.copy(
                visible = normalizeVisible(visible.filterIndexed { i, _ -> i != index }),
                hidden = cfg.hidden + tool
            )
        )
    }

    // fresh references for the long-lived drag handler
    val visibleState = rememberUpdatedState(visible)
    val moveState = rememberUpdatedState<(Int, Int) -> Unit> { from, to -> move(from, to) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        visible.forEachIndexed { index, tool ->
            val pinned = tool == ToolType.MORE
            val isDragging = draggingIndex == index
            val pinnedState = rememberUpdatedState(pinned)

            Surface(
                shape = RoundedCornerShape(Dimens.radiusMd),
                color = if (isDragging) MaterialTheme.colorScheme.surface
                else MaterialTheme.colorScheme.surfaceVariant,
                shadowElevation = if (isDragging) 6.dp else 0.dp,
                border = if (isDragging) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ToolRowHeight)
                    .zIndex(if (isDragging) 1f else 0f)
                    .offset { IntOffset(0, if (isDragging) dragOffset.roundToInt() else 0) }
                    .pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                if (pinnedState.value) return@detectDragGesturesAfterLongPress
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                draggingIndex = index
                                dragOffset = 0f
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                val idx = draggingIndex
                                    ?: return@detectDragGesturesAfterLongPress
                                if (pinnedState.value) {
                                    return@detectDragGesturesAfterLongPress
                                }
                                dragOffset += amount.y
                                val shift = (dragOffset / rowHeightPx).roundToInt()
                                if (shift != 0) {
                                    // non-pinned rows may occupy slots 0..size-2
                                    // (MORE owns the last slot)
                                    val maxSlot = (visibleState.value.size - 2).coerceAtLeast(0)
                                    val target = (idx + shift).coerceIn(0, maxSlot)
                                    if (target != idx) {
                                        moveState.value.invoke(idx, target)
                                        draggingIndex = target
                                        dragOffset -= shift * rowHeightPx
                                    } else {
                                        // clamp at the edge so the row does not drift
                                        val minOffset = -idx * rowHeightPx
                                        val maxOffset = (maxSlot - idx) * rowHeightPx
                                        dragOffset = dragOffset.coerceIn(minOffset, maxOffset)
                                    }
                                }
                            },
                            onDragEnd = {
                                draggingIndex = null
                                dragOffset = 0f
                            },
                            onDragCancel = {
                                draggingIndex = null
                                dragOffset = 0f
                            }
                        )
                    }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = Dimens.paddingSm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DragHandle,
                        contentDescription = stringResource(R.string.drag_to_reorder),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(Dimens.paddingSm))
                    Icon(
                        imageVector = ToolIcons.toolIcon(tool),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(Dimens.paddingMd))
                    Text(
                        text = stringResource(ToolIcons.toolLabelRes(tool)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { move(index, index - 1) },
                        enabled = !pinned && index > 0,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.KeyboardArrowUp,
                            contentDescription = stringResource(R.string.move_up),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = { move(index, index + 1) },
                        enabled = !pinned && index < visible.size - 2,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.move_down),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = { removeAt(index) },
                        enabled = !pinned,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.remove_from_toolbar),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

/** Chip of a currently hidden tool; tapping adds it back to the toolbar. */
@Composable
private fun HiddenToolChip(tool: ToolType, onAdd: () -> Unit) {
    val shape = RoundedCornerShape(Dimens.radiusMd)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .clip(shape)
            .clickable(onClick = onAdd)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = ToolIcons.toolIcon(tool),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = stringResource(ToolIcons.toolLabelRes(tool)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = stringResource(R.string.add_to_toolbar),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
