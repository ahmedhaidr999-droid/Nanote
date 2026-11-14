package com.nanote.app.ui.editor.menus

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FormatUnderlined
import androidx.compose.material.icons.outlined.Highlight
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nanote.app.R
import com.nanote.app.ui.editor.PaneController
import com.nanote.app.ui.theme.Dimens
import kotlin.math.roundToInt

/**
 * Floating pill context menu shown while PDF text is selected.
 * Anchored above the selection (below when there is no room), clamped
 * horizontally inside the pane. Fully RTL-safe and non-modal: taps outside
 * the pill simply pass through to the canvas.
 */
@Composable
fun TextContextMenu(
    pane: PaneController,
    onTranslate: (String) -> Unit,
    onSearchWeb: (String) -> Unit,
    onAskAi: (String) -> Unit,
    onCopied: () -> Unit
) {
    val context = LocalContext.current
    val menuState = pane.textMenu.collectAsStateWithLifecycle().value ?: return

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val paneWidthPx = constraints.maxWidth.toFloat()
        val paneHeightPx = constraints.maxHeight.toFloat()
        var menuSize by remember { mutableStateOf(IntSize.Zero) }
        val enterState = remember { MutableTransitionState(false).apply { targetState = true } }

        AnimatedVisibility(
            visibleState = enterState,
            enter = fadeIn(animationSpec = spring(Spring.DampingRatioNoBouncy, 800f)) +
                scaleIn(
                    animationSpec = spring(Spring.DampingRatioNoBouncy, 800f),
                    initialScale = 0.9f,
                    transformOrigin = TransformOrigin(0.5f, 1f)
                )
        ) {
            Surface(
                shape = RoundedCornerShape(Dimens.radiusLg),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shadowElevation = 8.dp,
                modifier = Modifier
                    .onSizeChanged { menuSize = it }
                    .offset {
                        val marginPx = 8.dp.roundToPx()
                        val gapPx = 12.dp.roundToPx()
                        val w = menuSize.width.toFloat()
                        val h = menuSize.height.toFloat()

                        // horizontal clamp: centered on the anchor, inside [margin, width - w - margin]
                        val maxX = paneWidthPx - w - marginPx
                        val x = if (maxX > marginPx) {
                            (menuState.anchorScreenX - w / 2f).coerceIn(marginPx.toFloat(), maxX)
                        } else {
                            (paneWidthPx - w) / 2f
                        }

                        // vertical: prefer above the anchor (also keeps clear of the IME), else below
                        val aboveY = menuState.anchorScreenY - h - gapPx
                        val y = if (aboveY >= marginPx) {
                            aboveY
                        } else {
                            val belowY = menuState.anchorScreenY + gapPx
                            val maxY = paneHeightPx - h - marginPx
                            if (belowY <= maxY) belowY else maxOf(marginPx.toFloat(), maxY)
                        }
                        IntOffset(x.roundToInt(), y.roundToInt())
                    }
            ) {
                Row(
                    Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (menuState.wordCount > 0) {
                        Surface(
                            shape = RoundedCornerShape(percent = 50),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                stringResource(R.string.selected_words, menuState.wordCount),
                                Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        VerticalDivider(
                            modifier = Modifier
                                .padding(horizontal = 6.dp)
                                .height(18.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                    Row(
                        Modifier
                            .weight(1f, fill = false)
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MenuAction(Icons.Outlined.ContentCopy, stringResource(R.string.action_copy)) {
                            val clipboard =
                                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Nanote", menuState.text))
                            onCopied()
                            pane.clearTextMenu()
                        }
                        MenuAction(Icons.Outlined.Translate, stringResource(R.string.action_translate)) {
                            onTranslate(menuState.text)
                            pane.clearTextMenu()
                        }
                        MenuAction(Icons.Outlined.TravelExplore, stringResource(R.string.action_search_web)) {
                            onSearchWeb(menuState.text)
                            pane.clearTextMenu()
                        }
                        MenuAction(Icons.Outlined.Highlight, stringResource(R.string.action_highlight)) {
                            pane.highlightSelection()
                        }
                        MenuAction(Icons.Outlined.FormatUnderlined, stringResource(R.string.action_underline)) {
                            pane.underlineSelection()
                        }
                        MenuAction(Icons.Outlined.TextFields, stringResource(R.string.action_select_text)) {
                            pane.clearTextMenu()
                        }
                        MenuAction(Icons.Outlined.SelectAll, stringResource(R.string.action_select_all)) {
                            pane.selectAllText()
                        }
                        MenuAction(
                            icon = Icons.AutoMirrored.Outlined.Chat,
                            label = stringResource(R.string.action_ask_ai),
                            tint = MaterialTheme.colorScheme.primary
                        ) {
                            onAskAi(menuState.text)
                            pane.clearTextMenu()
                        }
                    }
                }
            }
        }
    }
}

/** Compact icon + label pill button used inside the text-selection menu. */
@Composable
private fun MenuAction(
    icon: ImageVector,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            maxLines = 1
        )
    }
}
