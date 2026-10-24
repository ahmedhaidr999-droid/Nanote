package com.nanote.core

/**
 * Built-in OFFLINE stylus database (v1.1). Nanote has no internet permission,
 * so the registry ships inside the APK: it maps known tablet models to their
 * first-party stylus and its capabilities. It powers two things:
 *
 *  1. Sensible defaults — when the tablet ships with a known pressure pen,
 *     palm rejection ("pen-only input") is enabled automatically on first
 *     editor open, because writing with a pen while resting the palm is the
 *     primary use case on these devices.
 *  2. The Settings screen shows the detected stylus and what it can do, and
 *     the barrel-button customizer explains which buttons exist.
 *
 * The registry is deliberately conservative: unknown devices simply keep the
 * app defaults, and every behavior it suggests stays user-overridable.
 * Detection is pure string matching (no android.* imports) so it is fully
 * unit-testable on the JVM.
 */
data class StylusProfile(
    val id: String,
    val brand: String,
    val name: String,
    /** Physical shortcut buttons on the barrel (excluding the tip). */
    val buttons: Int,
    val pressureLevels: Int,
    val tilt: Boolean,
    val hover: Boolean,
    val palmRejection: Boolean,
    val magneticCharging: Boolean
)

object StylusRegistry {

    private val FOCUS_PEN = StylusProfile(
        "xiaomi_focus_pen", "Xiaomi", "Xiaomi Focus Pen",
        buttons = 3, pressureLevels = 8192, tilt = true, hover = true,
        palmRejection = true, magneticCharging = true
    )

    private val FOCUS_PEN_PRO = StylusProfile(
        "xiaomi_focus_pen_pro", "Xiaomi", "Xiaomi Focus Pen Pro",
        buttons = 3, pressureLevels = 16384, tilt = true, hover = true,
        palmRejection = true, magneticCharging = true
    )

    private val SMART_PEN_2 = StylusProfile(
        "xiaomi_smart_pen_2", "Xiaomi", "Xiaomi Smart Pen (2nd gen)",
        buttons = 2, pressureLevels = 4096, tilt = true, hover = true,
        palmRejection = true, magneticCharging = true
    )

    private val S_PEN = StylusProfile(
        "samsung_s_pen", "Samsung", "Samsung S Pen",
        buttons = 1, pressureLevels = 4096, tilt = true, hover = true,
        palmRejection = true, magneticCharging = false
    )

    private val M_PENCIL_2 = StylusProfile(
        "huawei_m_pencil_2", "Huawei", "Huawei M-Pencil (2nd gen)",
        buttons = 2, pressureLevels = 4096, tilt = true, hover = true,
        palmRejection = true, magneticCharging = true
    )

    private val M_PENCIL_3 = StylusProfile(
        "huawei_m_pencil_3", "Huawei", "Huawei M-Pencil (3rd gen)",
        buttons = 2, pressureLevels = 16384, tilt = true, hover = true,
        palmRejection = true, magneticCharging = true
    )

    private val MAGIC_PENCIL_2 = StylusProfile(
        "honor_magic_pencil_2", "Honor", "Honor Magic-Pencil 2",
        buttons = 2, pressureLevels = 4096, tilt = true, hover = true,
        palmRejection = true, magneticCharging = true
    )

    private val LENOVO_TAB_PEN_PLUS = StylusProfile(
        "lenovo_tab_pen_plus", "Lenovo", "Lenovo Tab Pen Plus",
        buttons = 2, pressureLevels = 4096, tilt = true, hover = true,
        palmRejection = true, magneticCharging = true
    )

    private val LENOVO_PRECISION_3 = StylusProfile(
        "lenovo_precision_pen_3", "Lenovo", "Lenovo Precision Pen 3",
        buttons = 2, pressureLevels = 4096, tilt = true, hover = true,
        palmRejection = true, magneticCharging = true
    )

    private val OPPO_PEN = StylusProfile(
        "oppo_stylus", "OPPO", "OPPO Pencil",
        buttons = 1, pressureLevels = 4096, tilt = true, hover = true,
        palmRejection = true, magneticCharging = true
    )

    private val USI_2 = StylusProfile(
        "usi_2", "USI", "USI 2.0 Stylus",
        buttons = 2, pressureLevels = 4096, tilt = false, hover = false,
        palmRejection = true, magneticCharging = false
    )

    private val EMR_GENERIC = StylusProfile(
        "emr_generic", "Wacom", "Wacom EMR Stylus",
        buttons = 2, pressureLevels = 4096, tilt = true, hover = true,
        palmRejection = true, magneticCharging = false
    )

    val all: List<StylusProfile> = listOf(
        FOCUS_PEN, FOCUS_PEN_PRO, SMART_PEN_2,
        S_PEN, M_PENCIL_2, M_PENCIL_3, MAGIC_PENCIL_2,
        LENOVO_TAB_PEN_PLUS, LENOVO_PRECISION_3,
        OPPO_PEN, USI_2, EMR_GENERIC
    )

    fun byId(id: String): StylusProfile? = all.firstOrNull { it.id == id }

    /** Exact/prefix device-model rules; matched against the uppercased model.
     *  Deliberately conservative — only VERIFIED model lines are listed, so
     *  phones and pen-less tablets are never mis-detected (a false positive
     *  would auto-enable pen-only input on a finger-only device). */
    private val deviceRules: List<Pair<List<String>, StylusProfile>> = listOf(
        // Xiaomi Pad 6S Pro 12.4 — verified: 24018RPACG (global)
        listOf("24018RPA") to FOCUS_PEN,
        listOf("24018RPG") to FOCUS_PEN
    )

    /**
     * Maps the running device to its bundled stylus, or null when unknown.
     * [brand]/[model]/[device] come from android.os.Build (injected by the
     * caller so tests stay JVM-pure).
     */
    fun detect(brand: String, model: String, device: String): StylusProfile? {
        val b = brand.uppercase()
        val m = model.uppercase()
        val d = device.uppercase()
        val combined = "$m $d"

        return when {
            b.contains("XIAOMI") || b.contains("REDMI") ->
                deviceRules.firstOrNull { (prefixes, _) ->
                    prefixes.any { p -> m.startsWith(p) || d.startsWith(p) }
                }?.second
            b.contains("SAMSUNG") ->
                if (m.startsWith("SM-X") || m.startsWith("SM-P") || m.startsWith("SM-T8") || m.startsWith("SM-T9")) S_PEN else null
            b.contains("HUAWEI") ->
                if (combined.contains("MATEPAD")) {
                    when {
                        m.contains("13.2") || m.contains("PRO13") -> M_PENCIL_3
                        else -> M_PENCIL_2
                    }
                } else null
            b.contains("HONOR") ->
                // only V8-series tablets ship the Magic-Pencil 2
                if (combined.contains("PAD V8") || combined.contains("PADV8")) MAGIC_PENCIL_2 else null
            b.contains("LENOVO") ->
                if (m.startsWith("TB") || d.startsWith("TB")) {
                    when {
                        m.startsWith("TB3") || m.contains("P12") -> LENOVO_PRECISION_3
                        else -> LENOVO_TAB_PEN_PLUS
                    }
                } else null
            b.contains("OPPO") ->
                if (combined.contains("PAD") || m.startsWith("OPD")) OPPO_PEN else null
            combined.contains("CHROME") -> USI_2
            b.contains("GOOGLE") -> null // Pixel Tablet Pen is niche; stay silent
            else -> null
        }
    }

    /** Convenience for production code; reads android.os.Build. */
    fun detectForThisDevice(): StylusProfile? = detect(
        brand = android.os.Build.BRAND,
        model = android.os.Build.MODEL,
        device = android.os.Build.DEVICE
    )
}
