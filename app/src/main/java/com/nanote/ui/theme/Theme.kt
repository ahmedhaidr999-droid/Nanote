package com.nanote.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Indigo40,
    onPrimary = Color.White,
    primaryContainer = Indigo90,
    onPrimaryContainer = Indigo10,
    secondary = Secondary40,
    onSecondary = Color.White,
    secondaryContainer = Secondary90,
    onSecondaryContainer = Color(0xFF181A2C),
    tertiary = Tertiary40,
    onTertiary = Color.White,
    tertiaryContainer = Tertiary90,
    onTertiaryContainer = Color(0xFF39090B),
    error = Error40,
    onError = Color.White,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
    background = Neutral99,
    onBackground = Color(0xFF1D1B22),
    surface = Neutral99,
    onSurface = Color(0xFF1D1B22),
    surfaceVariant = Neutral90,
    onSurfaceVariant = Color(0xFF48454E),
    outline = Color(0xFF78767F)
)

private val DarkColors = darkColorScheme(
    primary = Indigo80,
    onPrimary = Indigo10,
    primaryContainer = Indigo20,
    onPrimaryContainer = Indigo90,
    secondary = Secondary80,
    onSecondary = Color(0xFF2D2F42),
    secondaryContainer = Color(0xFF434559),
    onSecondaryContainer = Secondary90,
    tertiary = Tertiary80,
    onTertiary = Color(0xFF4C2724),
    tertiaryContainer = Color(0xFF653D3A),
    onTertiaryContainer = Tertiary90,
    error = Error80,
    onError = Color(0xFF690005),
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    background = Neutral0,
    onBackground = Neutral90,
    surface = Neutral0,
    onSurface = Neutral90,
    surfaceVariant = Neutral20,
    onSurfaceVariant = Color(0xFFC9C4D0),
    outline = Color(0xFF938F9A)
)

private val NanoteTypography = Typography()

/**
 * Nanote theme. Material You dynamic color on Android 12+, brand indigo
 * fallback everywhere else. Dark mode follows the system by default.
 */
@Composable
fun NanoteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = NanoteTypography,
        content = content
    )
}
