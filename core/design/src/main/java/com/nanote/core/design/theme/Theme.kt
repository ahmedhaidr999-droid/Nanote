package com.nanote.core.design.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.nanote.core.model.AccentColor
import com.nanote.core.model.ThemeMode

/**
 * Nanote app theme. Premium Blue identity kept intact even when
 * Material You dynamic colors are enabled (primary roles are overridden),
 * with an optional user-selectable accent family.
 */
@Composable
fun NanoteTheme(
    themeMode: ThemeMode,
    dynamicColors: Boolean,
    accentColor: AccentColor = AccentColor.NANOTE_BLUE,
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val context = LocalContext.current
    val accent = accentPalette(accentColor)

    fun androidx.compose.material3.ColorScheme.applyAccent() = copy(
        primary = Color(accent.lightPrimary),
        onPrimary = PureWhite,
        primaryContainer = Color(accent.lightPrimaryContainer),
        onPrimaryContainer = Color(accent.lightOnPrimaryContainer),
        inversePrimary = Color(accent.darkPrimary)
    )

    fun androidx.compose.material3.ColorScheme.applyAccentDark() = copy(
        primary = Color(accent.darkPrimary),
        onPrimary = Color(0xFF0B1120),
        primaryContainer = Color(accent.darkPrimaryContainer),
        onPrimaryContainer = Color(accent.darkOnPrimaryContainer),
        inversePrimary = Color(accent.lightPrimary)
    )

    val colorScheme = when {
        dynamicColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val dynamic = if (dark) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
            // Keep the brand: primary roles always use the selected accent.
            if (dark) dynamic.applyAccentDark() else dynamic.applyAccent()
        }

        dark -> NanoteDarkColors.applyAccentDark()
        else -> NanoteLightColors.applyAccent()
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = NanoteTypography,
        content = content
    )
}
