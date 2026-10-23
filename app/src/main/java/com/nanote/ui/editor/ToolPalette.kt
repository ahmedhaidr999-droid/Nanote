package com.nanote.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatShapes
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.nanote.R
import com.nanote.ink.engine.EditorTool
import com.nanote.ink.engine.ShapeKind

private val TOOLS = listOf(
    EditorTool.PEN,
    EditorTool.ERASER,
    EditorTool.LASSO,
    EditorTool.SHAPE,
    EditorTool.TEXT,
    EditorTool.IMAGE,
    EditorTool.LASER
)

/**
 * The floating tool palette (spec 8): pen, eraser, lasso, shapes, text,
 * image and laser. Tapping the active tool opens a popover with width
 * presets, the palette grid, a custom color picker. Long-press reorders.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun ToolPalette(
    tool: com.nanote.ink.engine.ToolSpec,
    fingersDraw: Boolean,
    onTool: (EditorTool) -> Unit,
    onPen: (com.nanote.ink.engine.PenType) -> Unit,
    onColor: (Int) -> Unit,
    onWidth: (Float) -> Unit,
    onFingersDraw: () -> Unit,
    onShapeKind: (ShapeKind) -> Unit
) {
    var popover by remember { mutableStateOf(false) }
    var orderNames by androidx.compose.runtime.saveable.rememberSaveable {
        mutableStateOf(TOOLS.map { it.name })
    }
    val order = orderNames.mapNotNull { name ->
        EditorTool.entries.firstOrNull { it.name == name }
    }.ifEmpty { TOOLS }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (popover) {
            ToolPopover(tool, onPen, onColor, onWidth, onShapeKind, onDismiss = { popover = false })
            Spacer(Modifier.height(8.dp))
        }

        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 6.dp
        ) {
            Column(Modifier.padding(8.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(order, key = { _, t -> t.name }) { _, t ->
                        ToolChip(
                            tool = t,
                            active = tool.tool == t,
                            onClick = {
                                if (tool.tool == t) popover = !popover else onTool(t)
                            },
                            onMove = { delta ->
                                val idx = orderNames.indexOf(t.name)
                                val to = (idx + delta).coerceIn(0, orderNames.size - 1)
                                if (idx >= 0 && to != idx) {
                                    val mut = orderNames.toMutableList()
                                    mut.removeAt(idx)
                                    mut.add(to, t.name)
                                    orderNames = mut
                                }
                            }
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val palette = if (tool.pen == com.nanote.ink.engine.PenType.HIGHLIGHTER)
                        com.nanote.ink.engine.HIGHLIGHTS
                    else com.nanote.ink.engine.PALETTE
                    palette.take(6).forEach { c ->
                        Box(
                            Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .border(
                                    width = if (tool.colorArgb == c) 3.dp else 1.dp,
                                    color = if (tool.colorArgb == c) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline,
                                    shape = CircleShape
                                )
                                .clickable { onColor(c) }
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Filled.Palette,
                        contentDescription = stringResource(R.string.custom_color),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .clickable { popover = !popover }
                            .padding(3.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    var width by remember(tool.widthPt) { mutableStateOf(tool.widthPt) }
                    Slider(
                        value = width,
                        onValueChange = { width = it; onWidth(it) },
                        valueRange = 0.5f..26f,
                        modifier = Modifier.width(120.dp)
                    )
                    Icon(
                        Icons.Filled.TouchApp,
                        contentDescription = stringResource(R.string.toggle_fingers_draw),
                        tint = if (fingersDraw) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onFingersDraw)
                            .padding(3.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolChip(
    tool: EditorTool,
    active: Boolean,
    onClick: () -> Unit,
    onMove: (Int) -> Unit
) {
    val icon = when (tool) {
        EditorTool.PEN -> Icons.Outlined.Edit
        EditorTool.ERASER -> Icons.Filled.CleaningServices
        EditorTool.LASSO -> Icons.Filled.Gesture
        EditorTool.SHAPE -> Icons.Filled.FormatShapes
        EditorTool.TEXT -> Icons.Filled.TextFields
        EditorTool.IMAGE -> Icons.Filled.Image
        EditorTool.LASER -> Icons.Filled.Highlight
    }
    val label = when (tool) {
        EditorTool.PEN -> stringResource(R.string.tool_pen)
        EditorTool.ERASER -> stringResource(R.string.tool_eraser)
        EditorTool.LASSO -> stringResource(R.string.tool_lasso)
        EditorTool.SHAPE -> stringResource(R.string.tool_shape)
        EditorTool.TEXT -> stringResource(R.string.tool_text)
        EditorTool.IMAGE -> stringResource(R.string.tool_image)
        EditorTool.LASER -> stringResource(R.string.tool_laser)
    }
    var menu by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                .combinedClickable(onClick = onClick, onLongClick = { menu = true }),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp)
            )
        }
        androidx.compose.material3.DropdownMenu(
            expanded = menu,
            onDismissRequest = { menu = false }
        ) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(stringResource(R.string.move_left)) },
                onClick = { menu = false; onMove(-1) }
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(stringResource(R.string.move_right)) },
                onClick = { menu = false; onMove(1) }
            )
        }
    }
}

/**
 * Popover for the active tool: pen selector, eraser mode + radius, shape kind,
 * width presets from the spec arrays, palette grid, custom HSV picker and
 * 3 per-tool presets.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolPopover(
    tool: com.nanote.ink.engine.ToolSpec,
    onPen: (com.nanote.ink.engine.PenType) -> Unit,
    onColor: (Int) -> Unit,
    onWidth: (Float) -> Unit,
    onShapeKind: (ShapeKind) -> Unit,
    onDismiss: () -> Unit
) {
    var hue by remember { mutableStateOf(0f) }
    var sat by remember { mutableStateOf(1f) }
    var value by remember { mutableStateOf(1f) }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp
    ) {
        Column(Modifier.padding(12.dp)) {
            when (tool.tool) {
                EditorTool.ERASER -> { /* radius handled by the width slider */ }
                EditorTool.SHAPE -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ShapeKind.entries.forEach { k ->
                        Surface(
                            shape = CircleShape,
                            color = if (tool.shapeKind == k) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                            onClick = { onShapeKind(k) }
                        ) {
                            Text(
                                shapeLabel(k),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
                else -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    com.nanote.ink.engine.PenType.entries.forEach { p ->
                        Surface(
                            shape = CircleShape,
                            color = if (tool.pen == p) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                            onClick = { onPen(p) }
                        ) {
                            Text(
                                penLabel(p),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // width presets from PEN_WIDTHS_PT (or eraser radii in M3)
            val widths = if (tool.pen == com.nanote.ink.engine.PenType.HIGHLIGHTER)
                com.nanote.ink.engine.HIGHLIGHT_WIDTHS_PT
            else com.nanote.ink.engine.PEN_WIDTHS_PT
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                widths.forEach { w ->
                    Surface(
                        shape = CircleShape,
                        color = if (kotlin.math.abs(tool.widthPt - w) < 0.1f)
                            MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        onClick = { onWidth(w) }
                    ) {
                        Box(Modifier.padding(8.dp), contentAlignment = Alignment.Center) {
                            Box(
                                Modifier
                                    .size((w * 2.2f).coerceIn(4f, 34f).dp)
                                    .clip(CircleShape)
                                    .background(Color(tool.colorArgb))
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // full palette grid
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                (com.nanote.ink.engine.PALETTE.toList() + com.nanote.ink.engine.HIGHLIGHTS.toList()).forEach { c ->
                    Box(
                        Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color(c))
                            .border(
                                width = if (tool.colorArgb == c) 3.dp else 0.dp,
                                color = MaterialTheme.colorScheme.primary,
                                shape = CircleShape
                            )
                            .clickable { onColor(c) }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // custom HSV picker
            Text(stringResource(R.string.custom_color), style = MaterialTheme.typography.labelSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                // hue slider
                Slider(
                    value = hue,
                    onValueChange = {
                        hue = it
                        onColor(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)))
                    },
                    valueRange = 0f..360f,
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))),
                        activeTrackColor = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)))
                    ),
                    modifier = Modifier.width(140.dp)
                )
                Spacer(Modifier.width(8.dp))
                // saturation/value box
                Column {
                    Slider(
                        value = sat,
                        onValueChange = {
                            sat = it
                            onColor(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)))
                        },
                        modifier = Modifier.width(110.dp)
                    )
                    Slider(
                        value = value,
                        onValueChange = {
                            value = it
                            onColor(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)))
                        },
                        modifier = Modifier.width(110.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))))
                )
            }
        }
    }
}

@Composable
private fun penLabel(p: com.nanote.ink.engine.PenType): String = when (p) {
    com.nanote.ink.engine.PenType.BALLPOINT -> stringResource(R.string.pen_ballpoint)
    com.nanote.ink.engine.PenType.FOUNTAIN -> stringResource(R.string.pen_fountain)
    com.nanote.ink.engine.PenType.PENCIL -> stringResource(R.string.pen_pencil)
    com.nanote.ink.engine.PenType.MARKER -> stringResource(R.string.pen_marker)
    com.nanote.ink.engine.PenType.HIGHLIGHTER -> stringResource(R.string.pen_highlighter)
}

@Composable
private fun shapeLabel(k: ShapeKind): String = when (k) {
    ShapeKind.LINE -> stringResource(R.string.shape_line)
    ShapeKind.ARROW -> stringResource(R.string.shape_arrow)
    ShapeKind.RECT -> stringResource(R.string.shape_rect)
    ShapeKind.ELLIPSE -> stringResource(R.string.shape_ellipse)
    ShapeKind.TRIANGLE -> stringResource(R.string.shape_triangle)
    ShapeKind.DIAMOND -> stringResource(R.string.shape_diamond)
    ShapeKind.STAR -> stringResource(R.string.shape_star)
}

/** Contextual bar while the lasso selection is active. */
@Composable
fun SelectionBar(
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onDelete: () -> Unit,
    onRecolor: () -> Unit,
    onDone: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp
    ) {
        Row(
            Modifier.padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton2(Icons.Filled.ContentCopy, stringResource(R.string.action_copy), onCopy)
            IconButton2(Icons.Filled.ContentPaste, stringResource(R.string.action_paste), onPaste)
            IconButton2(
                Icons.Filled.Palette, stringResource(R.string.action_recolor),
                onRecolor
            )
            IconButton2(Icons.Filled.Delete, stringResource(R.string.action_delete), onDelete)
            IconButton2(Icons.Filled.Done, stringResource(R.string.action_done), onDone)
        }
    }
}

@Composable
private fun IconButton2(icon: androidx.compose.ui.graphics.vector.ImageVector, cd: String, onClick: () -> Unit) {
    Icon(
        icon,
        contentDescription = cd,
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(8.dp)
    )
}
