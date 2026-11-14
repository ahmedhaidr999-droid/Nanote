@file:OptIn(ExperimentalFoundationApi::class)

package com.nanote.feature.editor.toolbar

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nanote.feature.editor.R
import com.nanote.core.data.SettingsStore
import com.nanote.core.model.DEFAULT_TOOLBAR
import com.nanote.core.model.ToolbarConfig
import com.nanote.core.model.ToolbarPosition
import com.nanote.core.model.ToolType
import com.nanote.feature.editor.PaneController
import com.nanote.core.design.theme.Dimens
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * Small Hilt ViewModel that exposes the app-wide [SettingsStore] to the
 * toolbar without changing EditorScreen's call signature. EditorScreen does
 * NOT pass the settings store into [NanoteToolbar], so the toolbar resolves
 * it through this VM (scoped to the same nav entry as the editor) to observe
 * the live, user-customized toolbar configuration.
 */
@HiltViewModel
class ToolbarViewModel @Inject constructor(val settings: SettingsStore) : ViewModel()

/** Tools that own a settings popover (see ToolPopovers.kt). */
private val POPOVER_TOOLS = setOf(
    ToolType.PEN, ToolType.PENCIL, ToolType.BRUSH,
    ToolType.HIGHLIGHTER, ToolType.ERASER, ToolType.SHAPE
)

private fun ToolType.hasSettings(): Boolean = this in POPOVER_TOOLS

// ---------------------------------------------------------------------------
// Direction-independent (physical) alignments. ToolbarPosition.LEFT/RIGHT are
// physical screen sides and popovers must not mirror in RTL, so the built-in
// Alignment.CenterStart/End (layout-direction aware) cannot be used here.
// ---------------------------------------------------------------------------

/** Centered vertically on the physical left edge of the container. */
private val PhysicalCenterLeft: Alignment = Alignment { size, space, _ ->
    IntOffset(0, (space.height - size.height) / 2)
}

/** Centered vertically on the physical right edge of the container. */
private val PhysicalCenterRight: Alignment = Alignment { size, space, _ ->
    IntOffset(space.width - size.width, (space.height - size.height) / 2)
}

/** Directly ABOVE the anchor, horizontally centered (for popovers). */
private val AboveAnchorCenter: Alignment = Alignment { size, space, _ ->
    IntOffset((space.width - size.width) / 2, -size.height)
}

/** To the physical RIGHT of the anchor, vertically centered (for popovers). */
private val RightOfAnchorCenter: Alignment = Alignment { size, space, _ ->
    IntOffset(space.width, (space.height - size.height) / 2)
}

/** To the physical LEFT of the anchor, vertically centered (for popovers). */
private val LeftOfAnchorCenter: Alignment = Alignment { size, space, _ ->
    IntOffset(-size.width, (space.height - size.height) / 2)
}

/** Live toolbar configuration (visible tools, order, position). */
@Composable
private fun rememberToolbarConfigState(): State<ToolbarConfig> {
    val vm: ToolbarViewModel = hiltViewModel()
    return vm.settings.toolbarConfig.collectAsStateWithLifecycle()
}

/**
 * Notewise-inspired floating tool pill.
 *
 * - BOTTOM: horizontal, scrollable, anchored bottom-center (ime + nav insets).
 * - LEFT/RIGHT: vertical, scrollable, anchored to that physical side.
 * - Active tool: primaryContainer background + primary tint + spring scale.
 * - UNDO/REDO disable with canUndo/canRedo; tap actions are wired 1:1 to
 *   PaneController / the screen callbacks.
 * - Tapping a settings tool (pen family, eraser, shapes) opens its popover;
 *   long-pressing any tool shows its name (or the popover for settings tools,
 *   the customize sheet for MORE).
 */
@Composable
fun NanoteToolbar(
    pane: PaneController,
    position: ToolbarPosition,
    onInsertImage: () -> Unit,
    onInsertCamera: () -> Unit,
    onInsertPdf: () -> Unit,
    onZoomControls: () -> Unit,
    onMore: () -> Unit,
    onCustomize: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cfg by rememberToolbarConfigState()
    val activeTool by pane.activeTool.collectAsStateWithLifecycle()
    val canUndo by pane.canUndo.collectAsStateWithLifecycle()
    val canRedo by pane.canRedo.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current

    // tool whose settings popover is currently open (only POPOVER_TOOLS)
    var popoverTool by remember { mutableStateOf<ToolType?>(null) }

    // close the popover when the active tool changes from anywhere else
    LaunchedEffect(activeTool) {
        if (popoverTool != null && popoverTool != activeTool) popoverTool = null
    }

    val visible = remember(cfg) { cfg.visible.ifEmpty { DEFAULT_TOOLBAR }.distinct() }

    fun handleToolTap(tool: ToolType) {
        when (tool) {
            ToolType.UNDO -> pane.undo()
            ToolType.REDO -> pane.redo()
            ToolType.ZOOM -> onZoomControls()
            ToolType.MORE -> onMore()
            ToolType.IMAGE -> onInsertImage()
            ToolType.CAMERA -> onInsertCamera()
            ToolType.PDF -> onInsertPdf()
            else -> {
                pane.setTool(tool)
                popoverTool = when {
                    !tool.hasSettings() -> null
                    popoverTool == tool -> null // second tap toggles it closed
                    else -> tool
                }
            }
        }
    }

    fun handleToolLongPress(tool: ToolType) {
        when {
            tool == ToolType.MORE -> onCustomize()
            tool.hasSettings() -> {
                pane.setTool(tool)
                popoverTool = tool
            }
            // plain tools: the name tooltip is shown by the button itself
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .imePadding()
            .navigationBarsPadding()
    ) {
        Box(
            Modifier
                .align(
                    when (position) {
                        ToolbarPosition.BOTTOM -> Alignment.BottomCenter
                        ToolbarPosition.LEFT -> PhysicalCenterLeft
                        ToolbarPosition.RIGHT -> PhysicalCenterRight
                    }
                )
                .padding(
                    when (position) {
                        ToolbarPosition.BOTTOM -> PaddingValues(bottom = 10.dp)
                        // symmetric so the side never flips in RTL
                        ToolbarPosition.LEFT, ToolbarPosition.RIGHT -> PaddingValues(horizontal = 10.dp)
                    }
                )
        ) {
            Surface(
                shape = RoundedCornerShape(Dimens.radiusLg),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shadowElevation = 8.dp,
                modifier = if (position == ToolbarPosition.BOTTOM) {
                    Modifier
                } else {
                    Modifier.heightIn(max = 480.dp)
                }
            ) {
                if (position == ToolbarPosition.BOTTOM) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            visible.forEach { tool ->
                                ToolbarToolButton(
                                    tool = tool,
                                    selected = activeTool == tool,
                                    enabled = when (tool) {
                                        ToolType.UNDO -> canUndo
                                        ToolType.REDO -> canRedo
                                        else -> true
                                    },
                                    haptic = haptic,
                                    onClick = { handleToolTap(tool) },
                                    longPressAction = if (tool == ToolType.MORE || tool.hasSettings()) {
                                        { handleToolLongPress(tool) }
                                    } else {
                                        null
                                    }
                                )
                            }
                        }
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(bottom = 6.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 6.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            visible.forEach { tool ->
                                ToolbarToolButton(
                                    tool = tool,
                                    selected = activeTool == tool,
                                    enabled = when (tool) {
                                        ToolType.UNDO -> canUndo
                                        ToolType.REDO -> canRedo
                                        else -> true
                                    },
                                    haptic = haptic,
                                    onClick = { handleToolTap(tool) },
                                    longPressAction = if (tool == ToolType.MORE || tool.hasSettings()) {
                                        { handleToolLongPress(tool) }
                                    } else {
                                        null
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // ----- floating settings popover (above bottom bar / beside side bars)
            popoverTool?.let { tool ->
                val marginPx = with(LocalDensity.current) { 12.dp.roundToPx() }
                Popup(
                    alignment = when (position) {
                        ToolbarPosition.BOTTOM -> AboveAnchorCenter
                        ToolbarPosition.LEFT -> RightOfAnchorCenter
                        ToolbarPosition.RIGHT -> LeftOfAnchorCenter
                    },
                    offset = when (position) {
                        ToolbarPosition.BOTTOM -> IntOffset(0, -marginPx)
                        ToolbarPosition.LEFT -> IntOffset(marginPx, 0)
                        ToolbarPosition.RIGHT -> IntOffset(-marginPx, 0)
                    },
                    onDismissRequest = { popoverTool = null },
                    properties = PopupProperties(focusable = true)
                ) {
                    ToolSettingsPopover(
                        tool = tool,
                        pane = pane,
                        onDismiss = { popoverTool = null }
                    )
                }
            }
        }
    }
}

/**
 * A single 44dp tool slot. Tap = action, long-press = popover / customize for
 * action tools, or a floating name tooltip for plain tools.
 */
@Composable
private fun ToolbarToolButton(
    tool: ToolType,
    selected: Boolean,
    enabled: Boolean,
    haptic: HapticFeedback,
    onClick: () -> Unit,
    longPressAction: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    var showTooltip by remember { mutableStateOf(false) }
    LaunchedEffect(showTooltip) {
        if (showTooltip) {
            delay(1200)
            showTooltip = false
        }
    }

    val bgColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        label = "toolbarToolBg"
    )
    val iconTint by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "toolbarToolTint"
    )
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.9f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "toolbarToolScale"
    )

    Box(modifier.size(44.dp)) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(Dimens.radiusMd))
                .background(bgColor)
                .combinedClickable(
                    enabled = enabled,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onClick()
                    },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (longPressAction != null) longPressAction() else showTooltip = true
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = ToolIcons.toolIcon(tool),
                contentDescription = stringResource(ToolIcons.toolLabelRes(tool)),
                tint = if (enabled) iconTint else iconTint.copy(alpha = 0.35f),
                modifier = Modifier
                    .size(Dimens.iconTool)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
            )
        }
        if (showTooltip) {
            ToolbarTooltipPopup(
                label = stringResource(ToolIcons.toolLabelRes(tool)),
                onDismiss = { showTooltip = false }
            )
        }
    }
}

/** Hairline separator between toolbar groups. */
@Composable
private fun ToolbarDivider(vertical: Boolean) {
    Box(
        Modifier
            .padding(
                if (vertical) PaddingValues(horizontal = 4.dp)
                else PaddingValues(vertical = 4.dp)
            )
            .then(
                if (vertical) Modifier.width(1.dp).height(24.dp)
                else Modifier.width(24.dp).height(1.dp)
            )
            .background(MaterialTheme.colorScheme.outline)
    )
}

/** Small inverse-colored name bubble floating above its anchor button. */
@Composable
private fun ToolbarTooltipPopup(label: String, onDismiss: () -> Unit) {
    val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
    Popup(
        alignment = AboveAnchorCenter,
        offset = IntOffset(0, -gap),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false)
    ) {
        Surface(
            shape = RoundedCornerShape(Dimens.radiusSm),
            color = MaterialTheme.colorScheme.inverseSurface,
            shadowElevation = 4.dp
        ) {
            Text(
                text = label,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                color = MaterialTheme.colorScheme.inverseOnSurface,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}
