package com.nanote.feature.editor.toolbar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ChangeHistory
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material.icons.outlined.Pentagon
import androidx.compose.material.icons.outlined.RoundedCorner
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.SyncAlt
import androidx.compose.material.icons.outlined.TripOrigin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nanote.feature.editor.R
import com.nanote.core.data.SettingsStore
import com.nanote.core.model.QUICK_PALETTE
import com.nanote.core.model.EraserMode
import com.nanote.core.model.InkStyle
import com.nanote.core.model.ShapeKind
import com.nanote.core.model.ToolConfig
import com.nanote.core.model.ToolPreset
import com.nanote.core.model.ToolType
import com.nanote.feature.editor.PaneController
import com.nanote.core.design.theme.Dimens
import kotlin.math.roundToInt

/**
 * Internal tool-settings popovers hosted by [NanoteToolbar] (§2 / §3 / §6 / §14).
 *
 * IMPORTANT: every control writes straight through
 * [PaneController.updateToolConfig], whose collector pushes the new config to
 * the active tool and redraws the canvas immediately — color/thickness/opacity/
 * pressure changes apply live while dragging the sliders, with no page
 * flipping needed. The popover itself reads `pane.toolConfig` (NOT local
 * state) so external changes (e.g. presets applied elsewhere) are reflected too.
 */

/** Soft marker swatches offered for the highlighter. */
private val HIGHLIGHTER_PALETTE: List<Long> = listOf(
    0xFFFDE047L, 0xFF86EFACL, 0xFF93C5FDL, 0xFFFCA5A5L, 0xFFD8B4FEL, 0xFFFDBA74L
)

private val INK_TOOLS = setOf(
    ToolType.PEN, ToolType.PENCIL, ToolType.BRUSH, ToolType.FOUNTAIN, ToolType.HIGHLIGHTER
)

/** Live ink style of a configurable tool (§2 tool configuration). */
private fun inkStyleOf(cfg: ToolConfig, tool: ToolType): InkStyle = when (tool) {
    ToolType.PEN -> InkStyle(cfg.penColor, cfg.penWidth, cfg.penOpacity, cfg.penPressure, cfg.penSmoothing)
    ToolType.PENCIL -> InkStyle(cfg.pencilColor, cfg.pencilWidth, cfg.pencilOpacity, cfg.pencilPressure, cfg.pencilSmoothing)
    ToolType.BRUSH -> InkStyle(cfg.brushColor, cfg.brushWidth, cfg.brushOpacity, cfg.brushPressure, cfg.brushSmoothing)
    ToolType.FOUNTAIN -> InkStyle(cfg.fountainColor, cfg.fountainWidth, cfg.fountainOpacity, cfg.fountainPressure, cfg.fountainSmoothing)
    ToolType.HIGHLIGHTER -> InkStyle(cfg.highlighterColor, cfg.highlighterWidth, cfg.highlighterOpacity, cfg.highlighterPressure, cfg.highlighterSmoothing)
    else -> InkStyle(cfg.penColor, cfg.penWidth, cfg.penOpacity, cfg.penPressure, cfg.penSmoothing)
}

/** Builds a ToolConfig transform that applies the provided ink style fields. */
private fun inkUpdate(
    tool: ToolType,
    color: Long? = null,
    width: Float? = null,
    opacity: Int? = null,
    pressure: Float? = null,
    smoothing: Float? = null
): (ToolConfig) -> ToolConfig = { cfg ->
    when (tool) {
        ToolType.PEN -> cfg.copy(
            penColor = color ?: cfg.penColor, penWidth = width ?: cfg.penWidth,
            penOpacity = opacity ?: cfg.penOpacity, penPressure = pressure ?: cfg.penPressure,
            penSmoothing = smoothing ?: cfg.penSmoothing
        )
        ToolType.PENCIL -> cfg.copy(
            pencilColor = color ?: cfg.pencilColor, pencilWidth = width ?: cfg.pencilWidth,
            pencilOpacity = opacity ?: cfg.pencilOpacity, pencilPressure = pressure ?: cfg.pencilPressure,
            pencilSmoothing = smoothing ?: cfg.pencilSmoothing
        )
        ToolType.BRUSH -> cfg.copy(
            brushColor = color ?: cfg.brushColor, brushWidth = width ?: cfg.brushWidth,
            brushOpacity = opacity ?: cfg.brushOpacity, brushPressure = pressure ?: cfg.brushPressure,
            brushSmoothing = smoothing ?: cfg.brushSmoothing
        )
        ToolType.FOUNTAIN -> cfg.copy(
            fountainColor = color ?: cfg.fountainColor, fountainWidth = width ?: cfg.fountainWidth,
            fountainOpacity = opacity ?: cfg.fountainOpacity, fountainPressure = pressure ?: cfg.fountainPressure,
            fountainSmoothing = smoothing ?: cfg.fountainSmoothing
        )
        ToolType.HIGHLIGHTER -> cfg.copy(
            highlighterColor = color ?: cfg.highlighterColor, highlighterWidth = width ?: cfg.highlighterWidth,
            highlighterOpacity = opacity ?: cfg.highlighterOpacity, highlighterPressure = pressure ?: cfg.highlighterPressure,
            highlighterSmoothing = smoothing ?: cfg.highlighterSmoothing
        )
        else -> cfg
    }
}

/**
 * Floating settings panel for [tool]. Hosted inside a focusable Popup by the
 * toolbar (above a bottom bar / beside a side bar).
 */
@Composable
fun ToolSettingsPopover(tool: ToolType, pane: PaneController, onDismiss: () -> Unit) {
    val vm: ToolbarViewModel = hiltViewModel()
    val cfg by pane.toolConfig.collectAsStateWithLifecycle()

    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    AnimatedVisibility(
        visible = entered,
        enter = fadeIn(animationSpec = tween(durationMillis = 140)) + scaleIn(
            initialScale = 0.92f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        )
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 320.dp),
            shape = RoundedCornerShape(Dimens.radiusLg),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = settingsTitle(tool),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = null, Modifier.size(18.dp))
                    }
                }

                when {
                    tool in INK_TOOLS -> InkSettingsPanel(tool, pane, vm.settings, cfg)
                    tool == ToolType.ERASER -> EraserSettingsPanel(pane, cfg)
                    tool == ToolType.SHAPE -> ShapeSettingsPanel(pane, cfg)
                    else -> Text(stringResource(R.string.no_settings))
                }
            }
        }
    }
}

private fun settingsTitle(tool: ToolType): String = tool.name.lowercase().replaceFirstChar { it.uppercase() }

// ===========================================================================
// Ink settings (§2 + §3)
// ===========================================================================

@Composable
private fun InkSettingsPanel(
    tool: ToolType,
    pane: PaneController,
    settings: SettingsStore,
    cfg: ToolConfig
) {
    val style = inkStyleOf(cfg, tool)
    val colorMemory by settings.colorMemory.collectAsStateWithLifecycle()
    val presets by settings.presets.collectAsStateWithLifecycle()
    var showCustomPicker by remember { mutableStateOf(false) }
    var showSavePreset by remember { mutableStateOf(false) }

    // ---- color section (§3) ----
    ColorSwatchRow(
        current = style.color,
        palette = if (tool == ToolType.HIGHLIGHTER) HIGHLIGHTER_PALETTE else QUICK_PALETTE,
        favorites = colorMemory.favorites,
        recents = colorMemory.recent,
        onPick = { c ->
            pane.updateToolConfig(inkUpdate(tool, color = c))
            settings.rememberColor(c)
        },
        onToggleFavorite = { c -> settings.toggleFavoriteColor(c) },
        onCustom = { showCustomPicker = true }
    )

    if (showCustomPicker) {
        HsvColorPickerDialog(
            initial = style.color,
            onDismiss = { showCustomPicker = false },
            onConfirm = { c ->
                pane.updateToolConfig(inkUpdate(tool, color = c))
                settings.rememberColor(c)
                showCustomPicker = false
            }
        )
    }

    // ---- width (§2 thickness) ----
    LabeledSlider(
        label = stringResource(R.string.thickness),
        value = style.width,
        range = 0.5f..80f,
        display = { "%.1f".format(it) },
        onCommit = { pane.updateToolConfig(inkUpdate(tool, width = it)) }
    )

    // ---- opacity ----
    LabeledSlider(
        label = stringResource(R.string.opacity),
        value = style.opacity.toFloat(),
        range = 5f..100f,
        display = { "${it.roundToInt()}%" },
        onCommit = { pane.updateToolConfig(inkUpdate(tool, opacity = it.roundToInt())) }
    )

    // ---- pressure response (§3) ----
    LabeledSlider(
        label = stringResource(R.string.pressure_response),
        value = style.pressureResponse,
        range = 0f..1f,
        display = { "${(it * 100).roundToInt()}%" },
        onCommit = { pane.updateToolConfig(inkUpdate(tool, pressure = it)) }
    )

    // ---- smoothing (§2) ----
    LabeledSlider(
        label = stringResource(R.string.smoothing),
        value = style.smoothing,
        range = 0f..1f,
        display = { "${(it * 100).roundToInt()}%" },
        onCommit = { pane.updateToolConfig(inkUpdate(tool, smoothing = it)) }
    )

    // ---- presets (§3 saved pen presets) ----
    val toolPresets = presets.filter { it.tool == tool }
    Text(stringResource(R.string.presets_section), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (toolPresets.isEmpty()) {
        Text(stringResource(R.string.no_presets_yet), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            toolPresets.forEach { p ->
                PresetChip(
                    preset = p,
                    onClick = {
                        pane.updateToolConfig(inkUpdate(tool, color = p.color, width = p.width, opacity = p.opacity, pressure = p.pressureResponse, smoothing = p.smoothing))
                    },
                    onLongPress = { settings.removePreset(p.name) }
                )
            }
        }
    }
    TextButton(onClick = { showSavePreset = true }) {
        Icon(Icons.Outlined.Add, contentDescription = null, Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.save_preset))
    }
    if (showSavePreset) {
        SavePresetDialog(
            initialName = "",
            onDismiss = { showSavePreset = false },
            onConfirm = { name ->
                settings.addPreset(
                    ToolPreset(
                        name = name,
                        tool = tool,
                        color = style.color,
                        width = style.width,
                        opacity = style.opacity,
                        pressureResponse = style.pressureResponse,
                        smoothing = style.smoothing
                    )
                )
                showSavePreset = false
            }
        )
    }
}

/** Palette + favorites + recents + custom-color entry (§3). */
@Composable
private fun ColorSwatchRow(
    current: Long,
    palette: List<Long>,
    favorites: List<Long>,
    recents: List<Long>,
    onPick: (Long) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onCustom: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.color), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // current color + custom picker entry
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(current.toInt()))
                    .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable { onCustom() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = stringResource(R.string.custom_color),
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            }
            palette.forEach { c ->
                ColorSwatch(color = c, selected = c == current, onClick = { onPick(c) })
            }
        }
        if (favorites.isNotEmpty()) {
            Text(stringResource(R.string.favorite_colors), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                favorites.forEach { c ->
                    Box {
                        ColorSwatch(color = c, selected = c == current, onClick = { onPick(c) })
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier
                                .size(10.dp)
                                .align(Alignment.TopEnd)
                        )
                    }
                }
            }
        }
        if (recents.isNotEmpty()) {
            Text(stringResource(R.string.recent_colors), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                recents.forEach { c -> ColorSwatch(color = c, selected = c == current, onClick = { onPick(c) }) }
            }
        }
        // favorite the current color
        TextButton(onClick = { onToggleFavorite(current) }) {
            Icon(
                if (current in favorites) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = null,
                Modifier.size(16.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(stringResource(if (current in favorites) R.string.unfavorite_color else R.string.favorite_color))
        }
    }
}

@Composable
private fun ColorSwatch(color: Long, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(Color(color.toInt()))
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = CircleShape
            )
            .clickable(onClick = onClick)
            .semantics { contentDescription = "color swatch" }
    )
}

/** Full HSV picker: saturation/value square + hue slider (§3 custom colors). */
@Composable
private fun HsvColorPickerDialog(
    initial: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit
) {
    val initColor = Color(initial.toInt())
    var hue by remember { mutableFloatStateOf(0f) }
    var sat by remember { mutableFloatStateOf(0f) }
    var value by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(initColor) {
        val f = FloatArray(3)
        android.graphics.Color.colorToHSV(initColor.toArgb(), f)
        hue = f[0]; sat = f[1]; value = f[2]
    }

    fun currentColor(): Color {
        val c = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))
        return Color(c)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.custom_color)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // SV square
                var canvasSize by remember { mutableStateOf(IntSize.Zero) }
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(Dimens.radiusMd))
                        .onSizeChanged { canvasSize = it }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { off ->
                                    sat = (off.x / canvasSize.width).coerceIn(0f, 1f)
                                    value = 1f - (off.y / canvasSize.height).coerceIn(0f, 1f)
                                }
                            ) { change, _ ->
                                sat = (change.position.x / canvasSize.width).coerceIn(0f, 1f)
                                value = 1f - (change.position.y / canvasSize.height).coerceIn(0f, 1f)
                            }
                        }
                ) {
                    val w = size.width
                    val h = size.height
                    // draw SV plane as a coarse gradient grid
                    for (x in 0..24) {
                        for (y in 0..24) {
                            drawRect(
                                color = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, x / 24f, 1f - y / 24f))),
                                topLeft = Offset(w * x / 25f, h * y / 25f),
                                size = androidx.compose.ui.geometry.Size(w / 25f + 1f, h / 25f + 1f)
                            )
                        }
                    }
                    // cursor
                    drawCircle(
                        color = Color.White,
                        radius = 8f,
                        center = Offset(w * sat, h * (1f - value)),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
                    )
                    drawCircle(
                        color = Color.Black,
                        radius = 8f,
                        center = Offset(w * sat, h * (1f - value)),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f)
                    )
                }
                // hue slider
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { off -> hue = (off.x / size.width * 360f).coerceIn(0f, 360f) }
                            ) { change, _ ->
                                hue = (change.position.x / size.width * 360f).coerceIn(0f, 360f)
                            }
                        }
                ) {
                    val w = size.width
                    for (x in 0..48) {
                        val f = x / 48f
                        drawRect(
                            color = Color(android.graphics.Color.HSVToColor(floatArrayOf(f * 360f, 1f, 1f))),
                            topLeft = Offset(w * f, 0f),
                            size = androidx.compose.ui.geometry.Size(w / 48f + 1f, size.height)
                        )
                    }
                    drawRect(
                        color = Color.White,
                        topLeft = Offset(w * (hue / 360f) - 4f, 0f),
                        size = androidx.compose.ui.geometry.Size(8f, size.height),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
                    )
                }
                // preview
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(currentColor())
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    )
                    Text(
                        "#%06X".format(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)) and 0xFFFFFF),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)).toLong() or 0xFF000000)
            }) { Text(stringResource(R.string.done)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun SavePresetDialog(initialName: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.save_preset)) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.preset_name)) }
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim()) }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun PresetChip(preset: ToolPreset, onClick: () -> Unit, onLongPress: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(Dimens.radiusMd),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .clickable(onClick = onClick)
            .semantics { contentDescription = "preset ${preset.name}" }
    ) {
        Row(
            Modifier.padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color(preset.color.toInt()))
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .pointerInput(Unit) {
                        detectTapGestures(onLongPress = { onLongPress() })
                    }
            )
            Text(preset.name, style = MaterialTheme.typography.labelMedium)
        }
    }
}

// ===========================================================================
// Eraser settings (§6)
// ===========================================================================

@Composable
private fun EraserSettingsPanel(pane: PaneController, cfg: ToolConfig) {
    Text(stringResource(R.string.eraser_mode), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Column {
        EraserModeRow(
            label = stringResource(R.string.eraser_stroke),
            selected = cfg.eraserMode == EraserMode.STROKE,
            onSelect = { pane.updateToolConfig { it.copy(eraserMode = EraserMode.STROKE) } }
        )
        EraserModeRow(
            label = stringResource(R.string.eraser_object),
            selected = cfg.eraserMode == EraserMode.OBJECT,
            onSelect = { pane.updateToolConfig { it.copy(eraserMode = EraserMode.OBJECT) } }
        )
        EraserModeRow(
            label = stringResource(R.string.eraser_area),
            selected = cfg.eraserMode == EraserMode.PIXEL,
            onSelect = { pane.updateToolConfig { it.copy(eraserMode = EraserMode.PIXEL) } }
        )
    }
    LabeledSlider(
        label = stringResource(R.string.eraser_size),
        value = cfg.eraserSize,
        range = 8f..160f,
        display = { "${it.roundToInt()}" },
        onCommit = { pane.updateToolConfig { c -> c.copy(eraserSize = it) } }
    )
}

@Composable
private fun EraserModeRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

// ===========================================================================
// Shape settings (§14)
// ===========================================================================

@Composable
private fun ShapeSettingsPanel(pane: PaneController, cfg: ToolConfig) {
    Text(stringResource(R.string.shape_kind), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

    // 3x3 grid of the nine shape kinds
    val kinds = listOf(
        ShapeKind.LINE to Icons.Outlined.ShowChart,
        ShapeKind.ARROW to Icons.Outlined.NorthEast,
        ShapeKind.DOUBLE_ARROW to Icons.Outlined.SyncAlt,
        ShapeKind.RECT to Icons.Outlined.CropSquare,
        ShapeKind.ROUNDED_RECT to Icons.Outlined.RoundedCorner,
        ShapeKind.CIRCLE to Icons.Outlined.Circle,
        ShapeKind.ELLIPSE to Icons.Outlined.TripOrigin,
        ShapeKind.TRIANGLE to Icons.Outlined.ChangeHistory,
        ShapeKind.POLYGON to Icons.Outlined.Pentagon
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        kinds.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { (kind, icon) ->
                    val selected = cfg.shapeKind == kind
                    val bg by animateColorAsState(
                        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        label = "shapeKindBg"
                    )
                    Surface(
                        shape = RoundedCornerShape(Dimens.radiusMd),
                        color = bg,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { pane.updateToolConfig { it.copy(shapeKind = kind) } }
                    ) {
                        Column(
                            Modifier.padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                icon,
                                contentDescription = shapeKindLabel(kind),
                                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // stroke color
    ColorSwatchRow(
        current = cfg.shapeColor,
        palette = QUICK_PALETTE,
        favorites = emptyList(),
        recents = emptyList(),
        onPick = { c -> pane.updateToolConfig { it.copy(shapeColor = c) } },
        onToggleFavorite = {},
        onCustom = {}
    )

    LabeledSlider(
        label = stringResource(R.string.thickness),
        value = cfg.shapeWidth,
        range = 0.5f..40f,
        display = { "%.1f".format(it) },
        onCommit = { pane.updateToolConfig { c -> c.copy(shapeWidth = it) } }
    )
    LabeledSlider(
        label = stringResource(R.string.opacity),
        value = cfg.shapeOpacity.toFloat(),
        range = 5f..100f,
        display = { "${it.roundToInt()}%" },
        onCommit = { pane.updateToolConfig { c -> c.copy(shapeOpacity = it.roundToInt()) } }
    )

    SwitchRow(
        label = stringResource(R.string.filled_shape),
        checked = cfg.shapeFilled,
        onChecked = { pane.updateToolConfig { it.copy(shapeFilled = !it.shapeFilled) } }
    )
    if (cfg.shapeFilled) {
        ColorSwatchRow(
            current = cfg.shapeFillColor,
            palette = listOf(
                0x332563EBL, 0x3322C55EL, 0x33EF4444L, 0x33F59E0BL, 0x338B5CF6L, 0x33000000L
            ),
            favorites = emptyList(),
            recents = emptyList(),
            onPick = { c -> pane.updateToolConfig { it.copy(shapeFillColor = c) } },
            onToggleFavorite = {},
            onCustom = {}
        )
    }
    SwitchRow(
        label = stringResource(R.string.snap_to_geometry),
        checked = cfg.shapeSnap,
        onChecked = { pane.updateToolConfig { it.copy(shapeSnap = !it.shapeSnap) } }
    )
    SwitchRow(
        label = stringResource(R.string.shape_recognition),
        checked = cfg.shapeRecognition,
        onChecked = { pane.updateToolConfig { it.copy(shapeRecognition = !it.shapeRecognition) } }
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChecked: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onChecked),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = { onChecked() })
    }
}

@Composable
private fun shapeKindLabel(kind: ShapeKind): String = stringResource(
    when (kind) {
        ShapeKind.LINE -> R.string.shape_line
        ShapeKind.ARROW -> R.string.shape_arrow
        ShapeKind.DOUBLE_ARROW -> R.string.shape_double_arrow
        ShapeKind.RECT -> R.string.shape_rect
        ShapeKind.ROUNDED_RECT -> R.string.shape_rounded_rect
        ShapeKind.CIRCLE -> R.string.shape_circle
        ShapeKind.ELLIPSE -> R.string.shape_ellipse
        ShapeKind.TRIANGLE -> R.string.shape_triangle
        ShapeKind.POLYGON -> R.string.shape_polygon
    }
)

// ===========================================================================
// Shared widgets
// ===========================================================================

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: (Float) -> String,
    onCommit: (Float) -> Unit
) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(display(value), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = { onCommit(it) },
            valueRange = range
        )
    }
}
