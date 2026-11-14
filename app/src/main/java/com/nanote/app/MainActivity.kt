package com.nanote.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.os.LocaleListCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nanote.core.common.AppLog
import com.nanote.core.data.DocumentRepository
import com.nanote.core.data.UserPreferencesRepository
import com.nanote.core.design.components.TopLevelDestination
import com.nanote.core.design.theme.NanoteTheme
import com.nanote.core.model.AppLanguage
import com.nanote.core.model.UserPreferences
import com.nanote.core.navigation.NanoteDestinations
import com.nanote.feature.editor.EditorScreen
import com.nanote.feature.folders.FoldersScreen
import com.nanote.feature.home.HomeScreen
import com.nanote.feature.library.LibraryScreen
import com.nanote.feature.settings.SettingsScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Single-activity, tablet-first shell. Expanded widths get a permanent
 * navigation rail; compact phones get a bottom bar. Handles "open PDF"
 * intents from file managers (offline import into the library).
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    @Inject
    lateinit var userPreferences: UserPreferencesRepository

    @Inject
    lateinit var documentRepository: DocumentRepository

    /** Document imported from an ACTION_VIEW intent, opened once nav is ready. */
    private var pendingDocId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        handleViewIntent(intent)

        setContent {
            MainActivityContent(activity = this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri: Uri = intent.data ?: return
        if (intent.type != "application/pdf") return
        lifecycleScope.launch {
            val title = queryDisplayName(uri) ?: "PDF"
            runCatching { documentRepository.importPdf(uri, title) }
                .onSuccess { pendingDocId = it }
                .onFailure { AppLog.e(TAG, "open-PDF import failed: ${it.message}", it) }
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainActivityContent(activity: MainActivity) {
    val windowSizeClass: WindowSizeClass = calculateWindowSizeClass(activity)
    val prefs by activity.userPreferences.userPreferences
        .collectAsStateWithLifecycle(initialValue = UserPreferences())

    // Per-app language (Arabic/English) without recreating the composition.
    LaunchedEffect(prefs.language) {
        val tag = when (prefs.language) {
            AppLanguage.SYSTEM -> ""
            AppLanguage.ENGLISH -> "en"
            AppLanguage.ARABIC -> "ar"
        }
        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        if (current != tag) {
            AppCompatDelegate.setApplicationLocales(
                if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
                else LocaleListCompat.forLanguageTags(tag)
            )
        }
    }

    NanoteTheme(
        themeMode = prefs.themeMode,
        dynamicColors = prefs.dynamicColors,
        accentColor = prefs.accentColor
    ) {
        val navController = rememberNavController()

        LaunchedEffect(activity.pendingDocId) {
            activity.pendingDocId?.let { id ->
                navController.navigate(NanoteDestinations.editor(id))
                activity.pendingDocId = null
            }
        }

        NanoteAppShell(
            windowSizeClass = windowSizeClass,
            navController = navController
        )
    }
}

/** Adaptive scaffold: navigation rail (tablet) or bottom bar (phone). */
@Composable
fun NanoteAppShell(
    windowSizeClass: WindowSizeClass,
    navController: NavHostController
) {
    val compact = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Compact
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // Hide navigation chrome inside the immersive editor.
    val inEditor = currentRoute != null && currentRoute.startsWith("editor")

    Scaffold(
        bottomBar = {
            if (compact && !inEditor) {
                NavigationBar {
                    bottomDestinations().forEach { dest ->
                        val label = stringResource(dest.labelRes)
                        NavigationBarItem(
                            selected = isSelected(currentRoute, dest),
                            onClick = { navController.navigateTopLevel(dest.route) },
                            icon = {
                                Icon(
                                    imageVector = if (isSelected(currentRoute, dest)) dest.selectedIcon
                                    else dest.unselectedIcon,
                                    contentDescription = label
                                )
                            },
                            label = { Text(label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .padding(padding)
        ) {
            if (!compact && !inEditor) {
                NavigationRail {
                    TopLevelDestination.entries.forEach { dest ->
                        val label = stringResource(dest.labelRes)
                        NavigationRailItem(
                            selected = isSelected(currentRoute, dest),
                            onClick = { navController.navigateTopLevel(dest.route) },
                            icon = {
                                Icon(
                                    imageVector = if (isSelected(currentRoute, dest)) dest.selectedIcon
                                    else dest.unselectedIcon,
                                    contentDescription = label
                                )
                            },
                            label = { Text(label) }
                        )
                    }
                }
            }
            NanoteNavHost(
                navController = navController,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }
    }
}

private fun bottomDestinations(): List<TopLevelDestination> = listOf(
    TopLevelDestination.HOME,
    TopLevelDestination.LIBRARY,
    TopLevelDestination.NOTEBOOKS,
    TopLevelDestination.FOLDERS,
    TopLevelDestination.SETTINGS
)

private fun isSelected(currentRoute: String?, dest: TopLevelDestination): Boolean {
    if (currentRoute == null) return false
    if (dest.route.startsWith("library/") && currentRoute.startsWith("library/")) {
        return dest.route == currentRoute
    }
    return currentRoute == dest.route
}

private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(NanoteDestinations.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** All application destinations. */
@Composable
fun NanoteNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = NanoteDestinations.HOME,
        modifier = modifier
    ) {
        composable(NanoteDestinations.HOME) {
            HomeScreen(
                onOpenDocument = { id -> navController.navigate(NanoteDestinations.editor(id)) },
                onOpenSettings = { navController.navigate(NanoteDestinations.SETTINGS) },
                onOpenLibrary = {
                    navController.navigate(NanoteDestinations.library(NanoteDestinations.Filters.ALL))
                },
                onOpenNotebooks = {
                    navController.navigate(NanoteDestinations.library(NanoteDestinations.Filters.NOTEBOOKS))
                },
                onOpenFolders = { navController.navigate(NanoteDestinations.folders(null)) },
                onOpenFolder = { folderId -> navController.navigate(NanoteDestinations.folders(folderId)) }
            )
        }

        composable(NanoteDestinations.LIBRARY_ROUTE) {
            LibraryScreen(
                onOpenDocument = { id -> navController.navigate(NanoteDestinations.editor(id)) },
                onOpenSettings = { navController.navigate(NanoteDestinations.SETTINGS) },
                onOpenFolders = { navController.navigate(NanoteDestinations.folders(null)) }
            )
        }

        composable(NanoteDestinations.FOLDERS_ROUTE) {
            FoldersScreen(
                onOpenFolder = { folderId -> navController.navigate(NanoteDestinations.folders(folderId)) },
                onOpenDocument = { id -> navController.navigate(NanoteDestinations.editor(id)) },
                onBackToRoot = { navController.popBackStack(NanoteDestinations.folders(null), false) }
            )
        }

        composable(NanoteDestinations.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(NanoteDestinations.EDITOR_ROUTE) {
            EditorScreen(
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(NanoteDestinations.SETTINGS) }
            )
        }
    }
}
