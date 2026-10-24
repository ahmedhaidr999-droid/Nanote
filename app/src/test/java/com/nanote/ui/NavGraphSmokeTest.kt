package com.nanote.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nanote.ui.nav.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Regression test for the startup crash of v1.0.0/v1.0.1 on real devices:
 *
 *     java.lang.IllegalArgumentException: Wrong argument type for 'folderId'
 *     in argument bundle. string expected.
 *         at androidx.navigation.NavDestination.addInDefaultArgs(...)
 *         at androidx.navigation.NavGraphNavigator.navigate(...)
 *         at androidx.navigation.NavController.setGraph(...)
 *
 * Root cause: the start destination carried the literal "null" sentinel as a
 * query value ("library?folderId=null") while the argument was declared as a
 * non-nullable String with defaultValue "null". setGraph navigates to the
 * start destination immediately, so the app crashed on EVERY launch, on every
 * device — before a single pixel was drawn.
 *
 * This test runs the exact same production path — rememberNavController +
 * NavHost + setGraph -> NavGraphNavigator -> addInDefaultArgs — with the
 * same route pattern and the same argument specs (Routes.LIBRARY_ARGS). If
 * the definitions ever drift back to a sentinel scheme, the composition in
 * this test throws the same IllegalArgumentException and CI goes red BEFORE
 * an APK ships.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class NavGraphSmokeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun navGraph_startDestinationFolderNavigation_roundTrip() {
        lateinit var navController: androidx.navigation.NavHostController
        var observedFolderId: String? = "unset"
        var observedNotebookId: String? = "unset"

        compose.setContent {
            navController = rememberNavController()
            NavHost(
                navController = navController,
                startDestination = Routes.LIBRARY_BASE
            ) {
                composable(Routes.LIBRARY, arguments = Routes.LIBRARY_ARGS) { entry ->
                    observedFolderId = entry.arguments?.getString(Routes.ARG_FOLDER)
                    Text("library")
                }
                composable(Routes.EDITOR) { entry ->
                    observedNotebookId = entry.arguments?.getString(Routes.ARG_NOTEBOOK)
                    Text("editor")
                }
            }
        }

        // 1. setGraph navigates to the start destination. With the old
        // "library?folderId=null" start route this line threw the
        // IllegalArgumentException before any assert could run.
        compose.waitForIdle()
        assertNull("library root must receive a genuine null folderId", observedFolderId)

        // 2. Open a folder: query arg must round-trip as a String.
        navController.navigate(Routes.library("folder-123"))
        compose.waitForIdle()
        assertEquals("folder-123", observedFolderId)

        // 3. Tap the Library bar item (bare base route): optional query arg
        // falls back to its null default again.
        navController.navigate(Routes.LIBRARY_BASE) { launchSingleTop = true }
        compose.waitForIdle()
        assertNull(observedFolderId)

        // 4. Editor path args round-trip as Strings.
        navController.navigate(Routes.editor("nb-1"))
        compose.waitForIdle()
        assertEquals("nb-1", observedNotebookId)
    }
}
