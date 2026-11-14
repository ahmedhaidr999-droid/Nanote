package com.nanote.feature.editor.toolbar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChangeHistory
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nanote.feature.editor.R
import com.nanote.core.data.SettingsStore
import com.nanote.core.model.QUICK_PALETTE
import com.nanote.core.model.EraserMode
import com.nanote.core.model.ShapeKind
import com.nanote.core.model.ToolConfig
import com.nanote.core.model.ToolPreset
import com.nanote.core.model.ToolType
import com.nanote.feature.editor.PaneController
import com.nanote.core.design.theme.Dimens
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Internal tool-settings popovers hosted by [NanoteToolbar].
 *
 * IMPORTANT: every control writes straight through
 * [PaneController.updateToolConfig], whose collector pushes the new config to
 * the active tool and redraws the canvas immediately — color/thickness/opacity
 * changes apply live while dragging the sliders, with no page flipping needed.
 * The popover itself reads `pane.toolConfig` (NOT local state) so external
 * changes (e.g. presets applied elsewhere) are reflected too.
 */

/** Soft marker swatches offered for the highlighter. */
private val HIGHLIGHTER_PALETTE: List<Long> = listOf(
    0xFFFDE047L, 0xFF86EFACL, 0xFF93C5FDL, 0xFFFCA5A5L, 0xFFD8B4FEL, 0xFFFDBA74L
)

/** Live color / width / opacity triple of a configurable ink tool. */
private data class InkValues(val color: Long, val width: Float, val opacity: Int)

private fun inkValuesOf(cfg: ToolConfig, tool: ToolType): InkValues? = when (tool) {
    ToolType.PEN -> InkValues(cfg.penColor, cfg.penWidth, cfg.penOpacity)
    ToolType.PENCIL -> InkValues(cfg.pencilColor, cfg.pencilWidth, cfg.pencilOpacity)
    ToolType.BRUSH -> InkValues(cfg.brushColor, cfg.brushWidth, cfg.brushOpacity)
    ToolType.HIGHLIGHTER -> InkValues(cfg.highlighterColor, cfg.highlighterWidth, cfg.highlighterOpacity)
    else -> null
}

/** Builds a ToolConfig transform that applies only the provided fields. */
private fun inkUpdate(
    tool: ToolType,
    color: Long? = null,
    width: Float? = null,
    opacity: Int? = null
): (ToolConfig) -> ToolConfig = { cfg ->
    when (tool) {
        ToolType.PEN -> cfg.copy(
            penColor = color ?: cfg.penColor,
            penWidth = width ?: cfg.penWidth,
            penOpacity = opacity ?: cfg.penOpacity
        )
        ToolType.PENCIL -> cfg.copy(
            pencilColor = color ?: cfg.pencilColor,
            pencilWidth = width ?: cfg.pencilWidth,
            pencilOpacity = opacity ?: cfg.pencilOpacity
        )
        ToolType.BRUSH -> cfg.copy(
            brushColor = color ?: cfg.brushColor,
            brushWidth = width ?: cfg.brushWidth,
            brushOpacity = opacity ?: cfg.brushOpacity
        )
        ToolType.HIGHLIGHTER -> cfg.copy(
            highlighterColor = color ?: cfg.highlighterColor,
            highlighterWidth = width ?: cfg.highlighterWidth,
            highlighterOpacity = opacity ?: cfg.highlighterOpacity
        )
        ToolType.SHAPE -> cfg.copy(
            shapeColor = color ?: cfg.shapeColor,
            shapeWidth = width ?: cfg.shapeWidth
        )
        else -> cfg
    }
}

/**
 * Floating settings panel for [tool]. Hosted inside a focusable Popup by the
 * toolbar (above a bottom bar / beside a side bar) — not called directly by
 * EditorScreen.
 */
@Composable
fun ToolSettingsPopover(tool: ToolType, pane: PaneController, onDismiss: () -> Unit) {
    // presets live in SettingsStore — resolve the shared toolbar VM
    val vm: ToolbarViewModel = hiltViewModel()
    val cfg by pane.toolConfig.collectAsStateWithLifecycle()

    // spring entrance (AnimatedVisibility only animates on state changes,
    // so flip `entered` after first composition)
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
            modifier = Modifier.widthIn(max = 300.dp),
            shape = RoundedCornerShape(Dimens.radiusLg),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // header
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = settingsTitle(tool),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.close),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                when (tool) {
                    ToolType.PEN, ToolType.PENCIL, ToolType.BRUSH ->
                        InkToolSection(tool = tool, pane = pane, settings = vm.settings)
                    ToolType.HIGHLIGHTER -> HighlighterSection(pane = pane)
                    ToolType.ERASER -> EraserSection(pane = pane)
                    ToolType.SHAPE -> ShapeSection(pane = pane)
                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun settingsTitle(tool: ToolType): String = stringResource(
    when (tool) {
        ToolType.PEN -> R.string.pen_settings
        ToolType.PENCIL -> R.string.pencil_settings
        ToolType.BRUSH -> R.string.brush_settings
        ToolType.HIGHLIGHTER -> R.string.highlighter_settings
        ToolType.ERASER -> R.string.eraser_settings
        ToolType.SHAPE -> R.string.tool_shapes
        else -> R.string.tool_pen
    }
)

// ---------------------------------------------------------------------------
// PEN / PENCIL / BRUSH
// ---------------------------------------------------------------------------

@Composable
private fun InkToolSection(tool: ToolType, pane: PaneController, settings: SettingsStore) {
    val cfg by pane.toolConfig.collectAsStateWithLifecycle()
    val values = inkValuesOf(cfg, tool) ?: return
    var showPicker by remember { mutableStateOf(false) }

    Text(
        text = stringResource(R.string.color),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    ColorSwatchRow(
        palette = QUICK_PALETTE,
        selected = values.color,
        onSelect = { c -> pane.updateToolConfig(inkUpdate(tool, color = c)) },
        onCustom = { showPicker = true }
    )

    LabeledSlider(
        label = stringResource(R.string.thickness),
        valueText = stringResource(R.string.width_value, values.width),
        value = values.width,
        onValueChange = { w -> pane.updateToolConfig(inkUpdate(tool, width = w)) },
        range = 1f..40f
    )
    LabeledSlider(
        label = stringResource(R.string.opacity),
        valueText = stringResource(R.string.opacity_value, values.opacity),
        value = values.opacity.toFloat(),
        onValueChange = { v -> pane.updateToolConfig(inkUpdate(tool, opacity = v.roundToInt())) },
        range = 0f..100f
    )

    PresetSection(tool = tool, pane = pane, settings = settings)

    if (showPicker) {
        ColorPickerDialog(
            initialColor = values.color,
            onDismiss = { showPicker = false },
            onConfirm = { c ->
                pane.updateToolConfig(inkUpdate(tool, color = c))
                showPicker = false
            }
        )
    }
}

// ---------------------------------------------------------------------------
// HIGHLIGHTER
// ---------------------------------------------------------------------------

@Composable
private fun HighlighterSection(pane: PaneController) {
    val cfg by pane.toolConfig.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }

    Text(
        text = stringResource(R.string.color),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    ColorSwatchRow(
        palette = HIGHLIGHTER_PALETTE,
        selected = cfg.highlighterColor,
        onSelect = { c -> pane.updateToolConfig { it.copy(highlighterColor = c) } },
        onCustom = { showPicker = true }
    )

    LabeledSlider(
        label = stringResource(R.string.thickness),
        valueText = stringResource(R.string.width_value, cfg.highlighterWidth),
        value = cfg.highlighterWidth,
        onValueChange = { w -> pane.updateToolConfig { it.copy(highlighterWidth = w) } },
        range = 8f..60f
    )
    LabeledSlider(
        label = stringResource(R.string.opacity),
        valueText = stringResource(R.string.opacity_value, cfg.highlighterOpacity),
        value = cfg.highlighterOpacity.toFloat(),
        onValueChange = { v -> pane.updateToolConfig { it.copy(highlighterOpacity = v.roundToInt()) } },
        range = 0f..100f
    )
    // opacity is capped internally (~65% max alpha) so text stays readable
    Text(
        text = stringResource(R.string.highlighter_opacity_note),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    if (showPicker) {
        ColorPickerDialog(
            initialColor = cfg.highlighterColor,
            onDismiss = { showPicker = false },
            onConfirm = { c ->
                pane.updateToolConfig { it.copy(highlighterColor = c) }
                showPicker = false
            }
        )
    }
}

// ---------------------------------------------------------------------------
// ERASER
// ---------------------------------------------------------------------------

@Composable
private fun EraserSection(pane: PaneController) {
    val cfg by pane.toolConfig.collectAsStateWithLifecycle()

    listOf(
        EraserMode.PIXEL to R.string.tool_eraser_pixel,
        EraserMode.STROKE to R.string.tool_eraser_stroke,
        EraserMode.OBJECT to R.string.tool_eraser_object
    ).forEach { (mode, labelRes) ->
        val selected = cfg.eraserMode == mode
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Dimens.radiusMd))
                .clickable { pane.updateToolConfig { it.copy(eraserMode = mode) } }
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = { pane.updateToolConfig { it.copy(eraserMode = mode) } }
            )
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    LabeledSlider(
        label = stringResource(R.string.eraser_size),
        valueText = stringResource(R.string.width_value, cfg.eraserSize),
        value = cfg.eraserSize,
        onValueChange = { v -> pane.updateToolConfig { it.copy(eraserSize = v) } },
        range = 10f..120f
    )

    // live preview circle: the 10..120pt range maps to a 10..56dp diameter
    val previewDiameter = 10f + ((cfg.eraserSize - 10f) / 110f) * 46f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(previewDiameter.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
        )
    }
}

// ---------------------------------------------------------------------------
// SHAPES
// ---------------------------------------------------------------------------

@Composable
private fun ShapeSection(pane: PaneController) {
    val cfg by pane.toolConfig.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
    ) {
        ShapeKindButton(Icons.Outlined.ShowChart, R.string.shape_line, cfg.shapeKind == ShapeKind.LINE) {
            pane.updateToolConfig { it.copy(shapeKind = ShapeKind.LINE) }
        }
        ShapeKindButton(Icons.Outlined.NorthEast, R.string.shape_arrow, cfg.shapeKind == ShapeKind.ARROW) {
            pane.updateToolConfig { it.copy(shapeKind = ShapeKind.ARROW) }
        }
        ShapeKindButton(Icons.Outlined.CropSquare, R.string.shape_rect, cfg.shapeKind == ShapeKind.RECT) {
            pane.updateToolConfig { it.copy(shapeKind = ShapeKind.RECT) }
        }
        ShapeKindButton(Icons.Outlined.Circle, R.string.shape_circle, cfg.shapeKind == ShapeKind.CIRCLE) {
            pane.updateToolConfig { it.copy(shapeKind = ShapeKind.CIRCLE) }
        }
        ShapeKindButton(Icons.Outlined.ChangeHistory, R.string.shape_triangle, cfg.shapeKind == ShapeKind.TRIANGLE) {
            pane.updateToolConfig { it.copy(shapeKind = ShapeKind.TRIANGLE) }
        }
    }

    Text(
        text = stringResource(R.string.color),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    ColorSwatchRow(
        palette = QUICK_PALETTE,
        selected = cfg.shapeColor,
        onSelect = { c -> pane.updateToolConfig { it.copy(shapeColor = c) } },
        onCustom = { showPicker = true }
    )

    LabeledSlider(
        label = stringResource(R.string.thickness),
        valueText = stringResource(R.string.width_value, cfg.shapeWidth),
        value = cfg.shapeWidth,
        onValueChange = { v -> pane.updateToolConfig { it.copy(shapeWidth = v) } },
        range = 1f..20f
    )

    if (showPicker) {
        ColorPickerDialog(
            initialColor = cfg.shapeColor,
            onDismiss = { showPicker = false },
            onConfirm = { c ->
                pane.updateToolConfig { it.copy(shapeColor = c) }
                showPicker = false
            }
        )
    }
}

@Composable
private fun ShapeKindButton(
    icon: ImageVector,
    labelRes: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val bg by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        label = "shapeKindBg"
    )
    val tint by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "shapeKindTint"
    )
    Box(
        modifier
            .size(40.dp)
            .clip(RoundedCornerShape(Dimens.radiusMd))
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(labelRes),
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
    }
}

// ---------------------------------------------------------------------------
// Shared building blocks
// ---------------------------------------------------------------------------

/** Label + live value text + continuous slider. */
@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range
        )
    }
}

/** Horizontally scrollable swatch row (28dp circles, 2dp primary selection ring). */
@Composable
private fun ColorSwatchRow(
    palette: List<Long>,
    selected: Long,
    onSelect: (Long) -> Unit,
    onCustom: () -> Unit
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        palette.forEach { color ->
            Swatch(color = color, selected = color == selected, onClick = { onSelect(color) })
        }
        CustomSwatch(selected = selected !in palette, onClick = onCustom)
    }
}

@Composable
private fun Swatch(color: Long, selected: Boolean, onClick: () -> Unit) {
    val hex = remember(color) { String.format("#%06X", color and 0xFFFFFF) }
    val size by animateDpAsState(
        targetValue = if (selected) 22.dp else 26.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "swatchSize"
    )
    Box(modifier = Modifier.size(30.dp), contentAlignment = Alignment.Center) {
        if (selected) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
            )
        }
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(Color(color))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                .clickable(onClick = onClick)
                .semantics { contentDescription = hex }
        )
    }
}

@Composable
private fun CustomSwatch(selected: Boolean, onClick: () -> Unit) {
    val size by animateDpAsState(
        targetValue = if (selected) 22.dp else 26.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "customSwatchSize"
    )
    Box(modifier = Modifier.size(30.dp), contentAlignment = Alignment.Center) {
        if (selected) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
            )
        }
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = stringResource(R.string.custom_color),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/** Preset chips (filtered per tool) + "save current as preset" button. */
@Composable
private fun PresetSection(tool: ToolType, pane: PaneController, settings: SettingsStore) {
    val cfg by pane.toolConfig.collectAsStateWithLifecycle()
    val presets by settings.presets.collectAsStateWithLifecycle()
    val mine = remember(presets, tool) { presets.filter { it.tool == tool } }
    val values = inkValuesOf(cfg, tool) ?: return
    val nextName = stringResource(R.string.preset_name, mine.size + 1)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.presets),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        // save the current color / width / opacity as a new preset
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .clickable {
                    settings.addPreset(
                        ToolPreset(
                            name = nextName,
                            tool = tool,
                            color = values.color,
                            width = values.width,
                            opacity = values.opacity
                        )
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = stringResource(R.string.save_preset),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp)
            )
        }
    }

    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        mine.forEach { preset ->
            val isCurrent = preset.color == values.color &&
                abs(preset.width - values.width) < 0.05f &&
                preset.opacity == values.opacity
            Surface(
                shape = RoundedCornerShape(Dimens.radiusMd),
                color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
                border = if (isCurrent) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                modifier = Modifier
                    .clip(RoundedCornerShape(Dimens.radiusMd))
                    .clickable {
                        pane.updateToolConfig(
                            inkUpdate(
                                tool,
                                color = preset.color,
                                width = preset.width,
                                opacity = preset.opacity
                            )
                        )
                    }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(Color(preset.color))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    )
                    Text(
                        text = preset.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Compact HSV color picker
// ---------------------------------------------------------------------------

/**
 * Compact custom color dialog: hue spectrum bar + saturation / brightness
 * sliders with a live preview circle. Returns the chosen ARGB Long
 * (0xFF......, matching the ToolConfig color format).
 */
@Composable
private fun ColorPickerDialog(
    initialColor: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit
) {
    val hsv = remember(initialColor) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initialColor.toInt(), it) }
    }
    var hue by remember(initialColor) { mutableFloatStateOf(hsv[0]) }
    var saturation by remember(initialColor) { mutableFloatStateOf(hsv[1]) }
    var brightness by remember(initialColor) { mutableFloatStateOf(hsv[2]) }
    val preview = Color.hsv(hue, saturation, brightness)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.custom_color),
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(preview)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        .align(Alignment.CenterHorizontally)
                )
                Text(
                    text = stringResource(R.string.hue),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SpectrumSlider(
                    position = hue / 360f,
                    onPositionChange = { hue = it * 360f }
                )
                Text(
                    text = stringResource(R.string.saturation),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(value = saturation, onValueChange = { saturation = it })
                Text(
                    text = stringResource(R.string.brightness),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(value = brightness, onValueChange = { brightness = it })
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(preview.toArgb().toLong() and 0xFFFFFFFFL) }
            ) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/**
 * Draggable hue spectrum bar (tap-to-seek + horizontal drag). The rainbow
 * gradient is intrinsic to a color picker, everything else uses the theme.
 */
@Composable
private fun SpectrumSlider(position: Float, onPositionChange: (Float) -> Unit) {
    var trackWidthPx by remember { mutableFloatStateOf(1f) }
    val thumbRadiusPx = with(LocalDensity.current) { 10.dp.toPx() }

    fun Float.toPosition(): Float {
        val usable = trackWidthPx - 2f * thumbRadiusPx
        if (usable <= 0f) return 0f
        return ((this - thumbRadiusPx) / usable).coerceIn(0f, 1f)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .onSizeChanged { trackWidthPx = it.width.toFloat().coerceAtLeast(1f) }
            .clip(RoundedCornerShape(14.dp))
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onPositionChange(offset.x.toPosition())
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    onPositionChange(change.position.x.toPosition())
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val step = w / 48f
            var x = 0f
            while (x < w) {
                drawRect(
                    color = Color.hsv((x / w) * 360f, 1f, 1f),
                    topLeft = Offset(x, 0f),
                    size = Size(step + 1f, size.height)
                )
                x += step
            }
        }
        // thumb (reads live position so it tracks the drag)
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset {
                    val usable = (trackWidthPx - 2f * thumbRadiusPx).coerceAtLeast(1f)
                    IntOffset((position * usable).roundToInt(), 0)
                }
                .size(20.dp)
                .clip(CircleShape)
                .background(Color.hsv(position * 360f, 1f, 1f))
                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
        )
    }
}
