package com.nanote.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nanote.R
import com.nanote.ui.editor.EditorScreen
import com.nanote.ui.library.FavoritesScreen
import com.nanote.ui.library.LibraryScreen
import com.nanote.ui.library.TrashScreen
import com.nanote.ui.nav.Routes
import com.nanote.ui.settings.SettingsScreen
import com.nanote.ui.theme.NanoteTheme

/**
 * Root scaffold of the app.
 *  - Phones: bottom NavigationBar (Library, Favorites, Trash, Settings)
 *  - Tablets / foldables / Chromebooks expanded: NavigationRail
 *  - The editor opens chrome-less, full-screen.
 */
@Composable
fun NanoteRoot(windowSizeClass: WindowSizeClass) {
    NanoteTheme {
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route
        val rootRoutes = remember {
            setOf(Routes.LIBRARY, Routes.FAVORITES, Routes.TRASH, Routes.SETTINGS)
        }
        val isRoot = currentRoute in rootRoutes
        val expanded = windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when {
                isRoot && expanded -> Row(Modifier.fillMaxSize()) {
                    NavigationRail {
                        RootRailItems(navController, currentRoute)
                    }
                    Box(Modifier.weight(1f)) {
                        NanoteNavHost(navController)
                    }
                }

                isRoot -> Scaffold(
                    bottomBar = {
                        NavigationBar {
                            RootBarItems(navController, currentRoute)
                        }
                    }
                ) { innerPadding ->
                    Box(Modifier.padding(innerPadding)) {
                        NanoteNavHost(navController)
                    }
                }

                else -> NanoteNavHost(navController)
            }
        }
    }
}

private data class RootDestination(
    val route: String,
    val icon: ImageVector,
    val labelRes: Int
)

@Composable
private fun RootRailItems(navController: NavHostController, currentRoute: String?) {
    destinations().forEach { dest ->
        val label = stringResource(dest.labelRes)
        val selected = currentRoute == dest.route
        NavigationRailItem(
            selected = selected,
            onClick = { navController.navigateRoot(dest.route) },
            icon = { Icon(dest.icon, contentDescription = label) },
            label = { Text(label) },
            alwaysShowLabel = false
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.RootBarItems(
    navController: NavHostController,
    currentRoute: String?
) {
    destinations().forEach { dest ->
        val label = stringResource(dest.labelRes)
        val selected = currentRoute == dest.route
        NavigationBarItem(
            selected = selected,
            onClick = { navController.navigateRoot(dest.route) },
            icon = { Icon(dest.icon, contentDescription = label) },
            label = { Text(label) }
        )
    }
}

private fun NavHostController.navigateRoot(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun destinations() = listOf(
    RootDestination(Routes.LIBRARY, Icons.AutoMirrored.Outlined.LibraryBooks, R.string.nav_library),
    RootDestination(Routes.FAVORITES, Icons.Outlined.Star, R.string.nav_favorites),
    RootDestination(Routes.TRASH, Icons.Outlined.Delete, R.string.nav_trash),
    RootDestination(Routes.SETTINGS, Icons.Outlined.Settings, R.string.nav_settings)
)

@Composable
private fun NanoteNavHost(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Routes.library(null)
    ) {
        composable(
            route = Routes.LIBRARY,
            arguments = listOf(
                navArgument(Routes.ARG_FOLDER) {
                    type = NavType.StringType
                    defaultValue = "null"
                }
            )
        ) { entry ->
            LibraryScreen(
                folderId = entry.arguments?.getString(Routes.ARG_FOLDER)?.takeUnless { it == "null" },
                onOpenNotebook = { notebookId ->
                    navController.navigate(Routes.editor(notebookId, "first"))
                },
                onOpenFolder = { folderId ->
                    navController.navigate(Routes.library(folderId))
                }
            )
        }
        composable(Routes.FAVORITES) { FavoritesScreen(onOpenNotebook = { id -> navController.navigate(Routes.editor(id, "first")) }) }
        composable(Routes.TRASH) { TrashScreen() }
        composable(Routes.SETTINGS) { SettingsScreen() }
        composable(Routes.EDITOR) { entry ->
            val notebookId = entry.arguments?.getString(Routes.ARG_NOTEBOOK).orEmpty()
            val pageId = entry.arguments?.getString(Routes.ARG_PAGE) ?: "first"
            EditorScreen(
                notebookId = notebookId,
                initialPageId = pageId,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
