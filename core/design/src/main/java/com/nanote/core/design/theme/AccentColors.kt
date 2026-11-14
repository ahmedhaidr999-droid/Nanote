package com.nanote.core.design.theme

import com.nanote.core.model.AccentColor

/**
 * Accent palettes: per-accent primary families for light + dark schemes.
 * Everything else in the base schemes stays constant, keeping the Nanote
 * identity while letting users personalise the primary colour.
 */
data class AccentPalette(
    val lightPrimary: Long,
    val lightPrimaryContainer: Long,
    val lightOnPrimaryContainer: Long,
    val darkPrimary: Long,
    val darkPrimaryContainer: Long,
    val darkOnPrimaryContainer: Long
)

private val ACCENTS: Map<AccentColor, AccentPalette> = mapOf(
    AccentColor.NANOTE_BLUE to AccentPalette(
        lightPrimary = 0xFF2563EB, lightPrimaryContainer = 0xFFDBEAFE, lightOnPrimaryContainer = 0xFF1D4ED8,
        darkPrimary = 0xFF3B82F6, darkPrimaryContainer = 0xFF1E3A8A, darkOnPrimaryContainer = 0xFFDBEAFE
    ),
    AccentColor.TEAL to AccentPalette(
        lightPrimary = 0xFF0D9488, lightPrimaryContainer = 0xFFCCFBF1, lightOnPrimaryContainer = 0xFF115E59,
        darkPrimary = 0xFF2DD4BF, darkPrimaryContainer = 0xFF134E4A, darkOnPrimaryContainer = 0xFFCCFBF1
    ),
    AccentColor.GREEN to AccentPalette(
        lightPrimary = 0xFF16A34A, lightPrimaryContainer = 0xFFDCFCE7, lightOnPrimaryContainer = 0xFF14532D,
        darkPrimary = 0xFF4ADE80, darkPrimaryContainer = 0xFF14532D, darkOnPrimaryContainer = 0xFFDCFCE7
    ),
    AccentColor.VIOLET to AccentPalette(
        lightPrimary = 0xFF7C3AED, lightPrimaryContainer = 0xFFEDE9FE, lightOnPrimaryContainer = 0xFF4C1D95,
        darkPrimary = 0xFFA78BFA, darkPrimaryContainer = 0xFF4C1D95, darkOnPrimaryContainer = 0xFFEDE9FE
    ),
    AccentColor.ROSE to AccentPalette(
        lightPrimary = 0xFFE11D48, lightPrimaryContainer = 0xFFFFE4E6, lightOnPrimaryContainer = 0xFF881337,
        darkPrimary = 0xFFFB7185, darkPrimaryContainer = 0xFF881337, darkOnPrimaryContainer = 0xFFFFE4E6
    ),
    AccentColor.AMBER to AccentPalette(
        lightPrimary = 0xFFD97706, lightPrimaryContainer = 0xFFFEF3C7, lightOnPrimaryContainer = 0xFF78350F,
        darkPrimary = 0xFFFBBF24, darkPrimaryContainer = 0xFF78350F, darkOnPrimaryContainer = 0xFFFEF3C7
    ),
    AccentColor.GRAPHITE to AccentPalette(
        lightPrimary = 0xFF334155, lightPrimaryContainer = 0xFFE2E8F0, lightOnPrimaryContainer = 0xFF0F172A,
        darkPrimary = 0xFF94A3B8, darkPrimaryContainer = 0xFF1E293B, darkOnPrimaryContainer = 0xFFE2E8F0
    )
)

fun accentPalette(accent: AccentColor): AccentPalette =
    ACCENTS[accent] ?: ACCENTS.getValue(AccentColor.NANOTE_BLUE)

/** Swatch shown in the Settings accent picker. */
fun accentSwatch(accent: AccentColor): Long = accentPalette(accent).lightPrimary
