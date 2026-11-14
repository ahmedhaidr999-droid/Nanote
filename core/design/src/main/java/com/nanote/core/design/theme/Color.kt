package com.nanote.core.design.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// Premium Blue brand tokens (reused across theme + dynamic-color overrides)
// ---------------------------------------------------------------------------

val BlueDeep = Color(0xFF1D4ED8)
val BlueLight = Color(0xFFDBEAFE)
val BlueAccent = Color(0xFF60A5FA)
val BorderLight = Color(0xFFE2E8F0)
val TextSecondary = Color(0xFF64748B)
val StickyYellow = Color(0xFFFEF08A)

/** Light theme primary. */
val BrandPrimary = Color(0xFF2563EB)
/** Dark theme primary. */
val BrandPrimaryDark = Color(0xFF3B82F6)
val BrandPrimaryContainer = Color(0xFFDBEAFE)
val BrandPrimaryContainerDark = Color(0xFF1E3A8A)
val BrandOnPrimaryContainer = Color(0xFF1D4ED8)
val BrandOnPrimaryContainerDark = Color(0xFFDBEAFE)
val PureWhite = Color(0xFFFFFFFF)

// ---------------------------------------------------------------------------
// Light scheme — Slate + Premium Blue
// ---------------------------------------------------------------------------

val NanoteLightColors = lightColorScheme(
    primary = BrandPrimary,
    onPrimary = PureWhite,
    primaryContainer = BrandPrimaryContainer,
    onPrimaryContainer = BrandOnPrimaryContainer,
    inversePrimary = BrandPrimaryDark,

    secondary = Color(0xFF64748B),
    onSecondary = PureWhite,
    secondaryContainer = Color(0xFFF1F5F9),
    onSecondaryContainer = Color(0xFF334155),

    tertiary = Color(0xFF7C3AED),
    onTertiary = PureWhite,
    tertiaryContainer = Color(0xFFEDE9FE),
    onTertiaryContainer = Color(0xFF5B21B6),

    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF64748B),
    surfaceTint = BrandPrimary,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8FAFC),
    surfaceContainer = Color(0xFFF1F5F9),
    surfaceContainerHigh = Color(0xFFEAEEF4),
    surfaceContainerHighest = Color(0xFFE2E8F0),

    outline = Color(0xFFE2E8F0),
    outlineVariant = Color(0xFFEDF2F7),

    error = Color(0xFFEF4444),
    onError = PureWhite,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),

    inverseSurface = Color(0xFF1E293B),
    inverseOnSurface = Color(0xFFF1F5F9),

    scrim = Color(0xFF0F172A)
)

// ---------------------------------------------------------------------------
// Dark scheme — deep Slate + brighter Blue
// ---------------------------------------------------------------------------

val NanoteDarkColors = darkColorScheme(
    primary = BrandPrimaryDark,
    onPrimary = PureWhite,
    primaryContainer = BrandPrimaryContainerDark,
    onPrimaryContainer = BrandOnPrimaryContainerDark,
    inversePrimary = BrandPrimary,

    secondary = Color(0xFF94A3B8),
    onSecondary = Color(0xFF0F172A),
    secondaryContainer = Color(0xFF1E293B),
    onSecondaryContainer = Color(0xFFE2E8F0),

    tertiary = Color(0xFFA78BFA),
    onTertiary = Color(0xFF1E1B4B),
    tertiaryContainer = Color(0xFF4C1D95),
    onTertiaryContainer = Color(0xFFEDE9FE),

    background = Color(0xFF0F172A),
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF1E293B),
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF334155),
    onSurfaceVariant = Color(0xFF94A3B8),
    surfaceTint = BrandPrimaryDark,
    surfaceContainerLowest = Color(0xFF0B1120),
    surfaceContainerLow = Color(0xFF1E293B),
    surfaceContainer = Color(0xFF26334A),
    surfaceContainerHigh = Color(0xFF334155),
    surfaceContainerHighest = Color(0xFF3E4C63),

    outline = Color(0xFF334155),
    outlineVariant = Color(0xFF334155),

    error = Color(0xFFF87171),
    onError = PureWhite,
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFECACA),

    inverseSurface = Color(0xFFF1F5F9),
    inverseOnSurface = Color(0xFF1E293B),

    scrim = Color(0xFF000000)
)
