package com.nanote.core.design.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.nanote.core.design.R
import com.nanote.core.navigation.NanoteDestinations

/**
 * Top-level destinations rendered by the adaptive navigation shell
 * (navigation rail on tablets, bottom bar on compact phones).
 */
enum class TopLevelDestination(
    val route: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val labelRes: Int
) {
    HOME(
        route = NanoteDestinations.HOME,
        selectedIcon = Icons.Filled.Home,
        unselectedIcon = Icons.Outlined.Home,
        labelRes = R.string.nav_home
    ),
    LIBRARY(
        route = NanoteDestinations.library(NanoteDestinations.Filters.ALL),
        selectedIcon = Icons.Filled.GridView,
        unselectedIcon = Icons.Outlined.GridView,
        labelRes = R.string.nav_library
    ),
    NOTEBOOKS(
        route = NanoteDestinations.library(NanoteDestinations.Filters.NOTEBOOKS),
        selectedIcon = Icons.AutoMirrored.Filled.MenuBook,
        unselectedIcon = Icons.AutoMirrored.Filled.MenuBook,
        labelRes = R.string.nav_notebooks
    ),
    FOLDERS(
        route = NanoteDestinations.FOLDERS,
        selectedIcon = Icons.Filled.Folder,
        unselectedIcon = Icons.Outlined.Folder,
        labelRes = R.string.nav_folders
    ),
    FAVORITES(
        route = NanoteDestinations.library(NanoteDestinations.Filters.FAVORITES),
        selectedIcon = Icons.Filled.Favorite,
        unselectedIcon = Icons.Outlined.FavoriteBorder,
        labelRes = R.string.nav_favorites
    ),
    SETTINGS(
        route = NanoteDestinations.SETTINGS,
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings,
        labelRes = R.string.nav_settings
    )
}
