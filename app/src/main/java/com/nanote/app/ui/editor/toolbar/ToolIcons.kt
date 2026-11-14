package com.nanote.app.ui.editor.toolbar

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AutoFixNormal
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.Highlight
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Interests
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.nanote.app.R
import com.nanote.app.core.model.ToolType

/**
 * Central mapping from [ToolType] to its visual representation (extended
 * material icon) and its localized display name (R.string.tool_*).
 *
 * Shared by the main toolbar, the tool settings popovers and the toolbar
 * customization sheet so every surface shows the exact same glyphs.
 *
 * Icon substitutions (no dedicated glyph exists in the icon set):
 * - ERASER     -> AutoFixNormal (magic-eraser look)
 * - LASER      -> CenterFocusStrong (laser pointer focus feel)
 * - LASSO      -> Gesture (freehand gesture reads as lasso)
 * - SHAPE      -> Interests (circle/square/triangle cluster)
 * - SELECT_OBJECT -> Category
 * - SELECT_TEXT   -> TextFields
 * - UNDO/REDO  -> AutoMirrored variants so the arrows flip in RTL.
 */
object ToolIcons {

    /** The icon shown for a tool inside the toolbar / menus. */
    @Composable
    fun toolIcon(tool: ToolType): ImageVector = when (tool) {
        ToolType.PEN -> Icons.Outlined.Edit
        ToolType.PENCIL -> Icons.Outlined.Draw
        ToolType.BRUSH -> Icons.Outlined.Brush
        ToolType.HIGHLIGHTER -> Icons.Outlined.Highlight
        ToolType.ERASER -> Icons.Outlined.AutoFixNormal
        ToolType.LASER -> Icons.Outlined.CenterFocusStrong
        ToolType.LASSO -> Icons.Outlined.Gesture
        ToolType.SELECT_OBJECT -> Icons.Outlined.Category
        ToolType.SELECT_TEXT -> Icons.Outlined.TextFields
        ToolType.TEXT -> Icons.Outlined.Title
        ToolType.IMAGE -> Icons.Outlined.Image
        ToolType.CAMERA -> Icons.Outlined.PhotoCamera
        ToolType.PDF -> Icons.Outlined.PictureAsPdf
        ToolType.SHAPE -> Icons.Outlined.Interests
        ToolType.STICKY_NOTE -> Icons.Outlined.StickyNote2
        ToolType.HAND -> Icons.Outlined.PanTool
        ToolType.UNDO -> Icons.AutoMirrored.Outlined.Undo
        ToolType.REDO -> Icons.AutoMirrored.Outlined.Redo
        ToolType.ZOOM -> Icons.Outlined.ZoomIn
        ToolType.MORE -> Icons.Outlined.MoreHoriz
    }

    /** String resource with the human readable tool name (tooltips + a11y). */
    fun toolLabelRes(tool: ToolType): Int = when (tool) {
        ToolType.PEN -> R.string.tool_pen
        ToolType.PENCIL -> R.string.tool_pencil
        ToolType.BRUSH -> R.string.tool_brush
        ToolType.HIGHLIGHTER -> R.string.tool_highlighter
        ToolType.ERASER -> R.string.tool_eraser
        ToolType.LASER -> R.string.tool_laser
        ToolType.LASSO -> R.string.tool_lasso
        ToolType.SELECT_OBJECT -> R.string.tool_select_object
        ToolType.SELECT_TEXT -> R.string.tool_select_text
        ToolType.TEXT -> R.string.tool_text
        ToolType.IMAGE -> R.string.tool_image
        ToolType.CAMERA -> R.string.tool_camera
        ToolType.PDF -> R.string.tool_pdf
        ToolType.SHAPE -> R.string.tool_shapes
        ToolType.STICKY_NOTE -> R.string.tool_sticky
        ToolType.HAND -> R.string.tool_hand
        ToolType.UNDO -> R.string.undo
        ToolType.REDO -> R.string.redo
        ToolType.ZOOM -> R.string.zoom
        ToolType.MORE -> R.string.more
    }
}
