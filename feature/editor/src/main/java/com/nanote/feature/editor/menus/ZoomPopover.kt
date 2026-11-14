package com.nanote.feature.editor.menus

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nanote.feature.editor.R
import com.nanote.feature.editor.PaneController
import com.nanote.core.design.theme.Dimens

/**
 * Zoom control popover: quick zoom out / level / zoom in, the three fit
 * presets and a compact page navigation row. Shown as a focusable popup
 * anchored bottom-center (above the toolbar); dismissed by back or outside tap.
 */
@Composable
fun ZoomPopover(pane: PaneController, onDismiss: () -> Unit) {
    val zoom by pane.zoomPercent.collectAsStateWithLifecycle()
    val currentPage by pane.currentPage.collectAsStateWithLifecycle()
    val pageCount = pane.pageCount
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    Popup(
        alignment = Alignment.BottomCenter,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Surface(
            shape = RoundedCornerShape(Dimens.radiusLg),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 8.dp,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 92.dp)
        ) {
            Column(
                Modifier
                    .padding(16.dp)
                    .widthIn(min = 260.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // ---- zoom out / level / zoom in ----
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    IconButton(onClick = { pane.zoomOut() }) {
                        Icon(
                            Icons.Outlined.ZoomOut,
                            contentDescription = stringResource(R.string.zoom_out)
                        )
                    }
                    Text(
                        text = "$zoom%",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(min = 92.dp)
                    )
                    IconButton(onClick = { pane.zoomIn() }) {
                        Icon(
                            Icons.Outlined.ZoomIn,
                            contentDescription = stringResource(R.string.zoom_in)
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                // ---- fit presets ----
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = { pane.fitWidth() },
                        label = { Text(stringResource(R.string.fit_width)) }
                    )
                    AssistChip(
                        onClick = { pane.fitPage() },
                        label = { Text(stringResource(R.string.fit_page)) }
                    )
                    AssistChip(
                        onClick = { pane.actualSize() },
                        label = { Text(stringResource(R.string.actual_size)) }
                    )
                }

                // ---- page navigation ----
                if (pageCount > 1) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        IconButton(
                            onClick = { pane.prevPage() },
                            enabled = currentPage > 0
                        ) {
                            Icon(
                                if (rtl) Icons.Filled.ChevronRight else Icons.Filled.ChevronLeft,
                                // destination page label: "Page n of total"
                                contentDescription = stringResource(
                                    R.string.page_indicator, currentPage, pageCount
                                )
                            )
                        }
                        Text(
                            stringResource(R.string.page_indicator, currentPage + 1, pageCount),
                            style = MaterialTheme.typography.labelLarge
                        )
                        IconButton(
                            onClick = { pane.nextPage() },
                            enabled = currentPage < pageCount - 1
                        ) {
                            Icon(
                                if (rtl) Icons.Filled.ChevronLeft else Icons.Filled.ChevronRight,
                                contentDescription = stringResource(
                                    R.string.page_indicator, currentPage + 2, pageCount
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
