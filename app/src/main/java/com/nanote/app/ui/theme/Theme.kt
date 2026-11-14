package com.nanote.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.nanote.app.core.model.ThemeMode

/**
 * Nanote app theme. Premium Blue identity kept intact even when
 * Material You dynamic colors are enabled (primary roles are overridden).
 */
@Composable
fun NanoteTheme(
    themeMode: ThemeMode,
    dynamicColors: Boolean,
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val context = LocalContext.current

    val colorScheme = when {
        dynamicColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val dynamic = if (dark) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
            // Keep the brand: primary roles always use our Premium Blue.
            if (dark) {
                dynamic.copy(
                    primary = BrandPrimaryDark,
                    onPrimary = PureWhite,
                    primaryContainer = BrandPrimaryContainerDark,
                    onPrimaryContainer = BrandOnPrimaryContainerDark
                )
            } else {
                dynamic.copy(
                    primary = BrandPrimary,
                    onPrimary = PureWhite,
                    primaryContainer = BrandPrimaryContainer,
                    onPrimaryContainer = BrandOnPrimaryContainer
                )
            }
        }

        dark -> NanoteDarkColors
        else -> NanoteLightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = NanoteTypography,
        content = content
    )
}
