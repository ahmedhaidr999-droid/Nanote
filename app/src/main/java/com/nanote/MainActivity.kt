package com.nanote

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.foundation.isSystemInDarkTheme
import com.nanote.core.Brand
import com.nanote.core.CrashCatcher
import com.nanote.data.files.SettingsStore
import com.nanote.data.files.ThemePref
import com.nanote.ui.NanoteRoot
import com.nanote.ui.theme.NanoteTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single-activity app. AppCompatActivity gives per-app language (EN/AR) and
 * BiometricPrompt for the app lock. When the lock is enabled the window is
 * FLAG_SECURE so ink never appears in the recents thumbnail.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var settings: SettingsStore

    private var unlocked = mutableStateOf(false)

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        var crashLog by mutableStateOf<String?>(null)
        try {
            crashLog = CrashCatcher.readLast(this)
        } catch (_: Throwable) {
            crashLog = null
        }

        setContent {
            val themePref by settings.theme.collectAsState(initial = ThemePref.SYSTEM)
            val lockEnabled by settings.appLock.collectAsState(initial = false)
            val alreadyUnlocked = unlocked

            LaunchedEffect(lockEnabled) {
                window.setFlags(
                    if (lockEnabled) WindowManager.LayoutParams.FLAG_SECURE else 0,
                    WindowManager.LayoutParams.FLAG_SECURE
                )
            }

            val dark = when (themePref) {
                ThemePref.DARK -> true
                ThemePref.LIGHT -> false
                ThemePref.SYSTEM -> isSystemInDarkTheme()
            }

            NanoteTheme(darkTheme = dark, dynamicColor = themePref == ThemePref.SYSTEM) {
                val report = crashLog
                if (report != null) {
                    CrashReportScreen(
                        report = report,
                        onShare = {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    "Nanote crash report:\n\n$report"
                                )
                                putExtra(Intent.EXTRA_SUBJECT, "Nanote crash report")
                            }
                            startActivity(Intent.createChooser(intent, null))
                        },
                        onDismiss = {
                            CrashCatcher.clear(this@MainActivity)
                            crashLog = null
                        }
                    )
                } else if (lockEnabled && !alreadyUnlocked.value) {
                    LockScreen(onUnlockClick = { showBiometricPrompt() })
                } else {
                    NanoteRoot(windowSizeClass = calculateWindowSizeClass(this))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Per-app language is restored automatically by AppCompatDelegate
        // (autoStoreLocales service in the manifest) — nothing to do here.
    }

    private fun showBiometricPrompt() {
        val manager = BiometricManager.from(this)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        when (manager.canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> Unit
            else -> {
                // No biometrics enrolled: device credential gate still applies;
                // if neither exists, do not lock the user out.
                if (manager.canAuthenticate(BiometricManager.Authenticators.DEVICE_CREDENTIAL) !=
                    BiometricManager.BIOMETRIC_SUCCESS
                ) {
                    unlocked.value = true
                    return
                }
            }
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlocked.value = true
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.app_locked_title))
            .setSubtitle(getString(R.string.app_unlock))
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt.authenticate(info)
    }
}

@Composable
private fun LockScreen(onUnlockClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(Brand.APP_NAME, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.app_locked_title), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onUnlockClick) {
            Text(stringResource(R.string.app_unlock))
        }
    }
}

/**
 * Shown instead of the app when the previous launch crashed: the full report
 * plus a share button, so the user can send the stack trace to the developer
 * (the app has no internet permission — sharing uses the system share sheet).
 */
@Composable
private fun CrashReportScreen(
    report: String,
    onShare: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.crash_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.crash_body), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.crash_share))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.crash_close))
        }
        Spacer(Modifier.height(16.dp))
        Text(
            report,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(24.dp))
    }
}
