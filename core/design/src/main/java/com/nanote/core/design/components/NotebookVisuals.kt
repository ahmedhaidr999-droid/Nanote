package com.nanote.core.design.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Work
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.nanote.core.model.CoverColor
import com.nanote.core.model.NotebookIcon

/** Notebook cover icon set (Material symbols, offline-safe). */
fun NotebookIcon.imageVector(): ImageVector = when (this) {
    NotebookIcon.NOTEBOOK -> Icons.Outlined.MenuBook
    NotebookIcon.JOURNAL -> Icons.Outlined.AutoStories
    NotebookIcon.NOTES -> Icons.Outlined.EditNote
    NotebookIcon.STUDY -> Icons.Outlined.School
    NotebookIcon.WORK -> Icons.Outlined.Work
    NotebookIcon.SCIENCE -> Icons.Outlined.Science
    NotebookIcon.ART -> Icons.Outlined.Palette
    NotebookIcon.TRAVEL -> Icons.Outlined.Flight
}

/** Filled variant used for selected states. */
fun NotebookIcon.filledImageVector(): ImageVector = when (this) {
    NotebookIcon.NOTEBOOK -> Icons.AutoMirrored.Filled.MenuBook
    else -> imageVector()
}

/** Cover colour as a Compose [Color]. */
fun CoverColor.composeColor(): Color = Color(argb)

/** All cover colours in picker order. */
val COVER_COLORS: List<CoverColor> = CoverColor.entries

/** All notebook icons in picker order. */
val NOTEBOOK_ICONS: List<NotebookIcon> = NotebookIcon.entries
