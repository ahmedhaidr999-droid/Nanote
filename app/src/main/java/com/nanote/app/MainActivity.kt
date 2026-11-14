package com.nanote.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.os.LocaleListCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nanote.app.core.data.SettingsStore
import com.nanote.app.core.model.AppLanguage
import com.nanote.app.ui.editor.EditorScreen
import com.nanote.app.ui.home.HomeScreen
import com.nanote.app.ui.home.HomeViewModel
import com.nanote.app.ui.settings.SettingsScreen
import com.nanote.app.ui.theme.NanoteTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.map

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var settings: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        setContent {
            val themeMode by settings.themeMode.collectAsStateWithLifecycle()
            val dynamicColors by settings.dynamicColors.collectAsStateWithLifecycle()
            val language by settings.language.collectAsStateWithLifecycle()

            // per-app language (Arabic/English) without recreating the composition
            LaunchedEffect(language) {
                val tag = when (language) {
                    AppLanguage.SYSTEM -> ""
                    AppLanguage.ENGLISH -> "en"
                    AppLanguage.ARABIC -> "ar"
                }
                val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
                val target = if (tag.isEmpty()) "" else tag
                if (current != target) {
                    AppCompatDelegate.setApplicationLocales(
                        if (target.isEmpty()) LocaleListCompat.getEmptyLocaleList()
                        else LocaleListCompat.forLanguageTags(target)
                    )
                }
            }

            NanoteTheme(themeMode = themeMode, dynamicColors = dynamicColors) {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "home") {
                    composable("home") {
                        val homeVm: HomeViewModel = hiltViewModel()
                        HomeScreen(
                            vm = homeVm,
                            onOpenNote = { id -> navController.navigate("editor/$id") },
                            onOpenSettings = { navController.navigate("settings") }
                        )
                    }
                    composable("editor/{docId}") {
                        EditorScreen(
                            onBack = { navController.popBackStack() },
                            onOpenSettings = { navController.navigate("settings") }
                        )
                    }
                    composable("settings") {
                        val settingsVm: com.nanote.app.ui.settings.SettingsViewModel = hiltViewModel()
                        com.nanote.app.ui.settings.SettingsScreen(vm = settingsVm, onBack = { navController.popBackStack() })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
    }
}
