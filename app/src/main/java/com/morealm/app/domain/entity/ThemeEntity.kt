package com.morealm.app.domain.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * MoRealm theme entity. Stores both built-in and user-imported themes.
 *
 * Marked `@Serializable` so [BackupManager.buildBackupData] can include
 * the user's themes in exported `.zip` backups. Without this, the export
 * path threw `SerializationException: Serializer for class 'ThemeEntity'
 * is not found`, which (combined with the now-fixed runCatching swallow)
 * produced 0-byte backup files. All fields are plain primitives / String /
 * String?; no custom types so the default generated serializer suffices.
 */
@Serializable
@Entity(tableName = "themes")
data class ThemeEntity(
    @PrimaryKey val id: String,
    val name: String,
    val author: String = "MoRealm",
    val isBuiltin: Boolean = true,
    val isNightTheme: Boolean = false,
    val isActive: Boolean = false,
    val manifestJson: String = "{}",
    val localPath: String? = null,

    // Core colors (stored as "#AARRGGBB" strings)
    val primaryColor: String = "#FF7C5CFC",
    val accentColor: String = "#FF7C5CFC",
    val backgroundColor: String = "#FF0A0A0F",
    val surfaceColor: String = "#FF111118",
    val onBackgroundColor: String = "#FFEDEDEF",
    val bottomBackground: String = "#FF111118",

    // Reader-specific colors (independent from app theme)
    val readerBackground: String = "#FF0A0A0F",
    val readerTextColor: String = "#FFEDEDEF",

    // Background image support
    val backgroundImageUri: String? = null,   // SAF URI to background image
    val transparentBars: Boolean = false,     // Make primary/bottom bar transparent for full image coverage

    // Optional reader CSS bundled with the theme. Reader style CSS can still override it.
    val customCss: String = "",
)

/**
 * Legado ThemeConfig.Config compatible format for import/export.
 *
 * Mirrors `io.legado.app.help.config.ThemeConfig.Config` 1:1; field names
 * must match Legado's GSON output. Optional fields (`transparentNavBar`,
 * `backgroundImgPath`, `backgroundImgBlur`) are accepted but only the first
 * two map onto MoRealm — `backgroundImgBlur` has no equivalent and is
 * dropped on import. `customCss` is a MoRealm-only extension.
 *
 * Color derivation (`toThemeEntity`):
 *  - `onBackgroundColor` is chosen by *background luminance*, not the
 *    `isNightTheme` flag — Legado users sometimes mark a paper-tone theme
 *    `isNightTheme=false` with a near-black background, or vice versa.
 *  - `surfaceColor` shifts the background luminance ±4% so cards/sheets get
 *    a faint elevation shadow (matches the layered look in BuiltinThemes).
 *  - `transparentBars` mirrors Legado's `transparentNavBar`.
 *  - `backgroundImageUri` is set to `backgroundImgPath` *only if it's an
 *    http/https URL*. Local absolute paths from another device are useless
 *    and are dropped. ThemeRepository later resolves http URLs to local
 *    `file://` URIs.
 */
@Serializable
data class LegadoThemeConfig(
    val themeName: String = "",
    val isNightTheme: Boolean = false,
    val primaryColor: String = "#ff000000",
    val accentColor: String = "#ff000000",
    val backgroundColor: String = "#ffffffff",
    val bottomBackground: String = "#ffffffff",
    val transparentNavBar: Boolean = false,
    val backgroundImgPath: String? = null,
    val backgroundImgBlur: Int = 0,
    val customCss: String = "",
) {
    fun toThemeEntity(): ThemeEntity {
        val bgArgb = parseHexArgb(backgroundColor)
        val isLightBg = bgArgb?.let(::isLightArgb) ?: !isNightTheme
        val surface = bgArgb
            ?.let { shiftArgbLuminance(it, if (isLightBg) -0.04f else 0.04f) }
            ?.let(::argbToHex)
            ?: backgroundColor
        val onBg = if (isLightBg) "#FF1A1A1A" else "#FFEDEDEF"
        val httpBgUrl = backgroundImgPath?.takeIf {
            it.startsWith("http://", true) || it.startsWith("https://", true)
        }
        return ThemeEntity(
            id = "legado_${themeName.hashCode()}",
            name = themeName,
            author = "Legado Import",
            isBuiltin = false,
            isNightTheme = isNightTheme,
            primaryColor = primaryColor,
            accentColor = accentColor,
            backgroundColor = backgroundColor,
            bottomBackground = bottomBackground,
            surfaceColor = surface,
            onBackgroundColor = onBg,
            readerBackground = backgroundColor,
            readerTextColor = onBg,
            transparentBars = transparentNavBar,
            backgroundImageUri = httpBgUrl,
            customCss = customCss,
        )
    }
}

// region Color helpers — used by Legado import to derive surface/onBackground
//        from a single backgroundColor instead of treating them as identical.
//        Kept private to this file; if these prove useful elsewhere lift them
//        into a dedicated ColorUtils. Do not export the hex parser without
//        first widening test coverage on malformed inputs.

private fun parseHexArgb(hex: String): Int? {
    val s = hex.trim().removePrefix("#")
    if (s.length != 6 && s.length != 8) return null
    return runCatching {
        val v = s.toLong(16)
        if (s.length == 6) (0xFF000000L or v).toInt() else v.toInt()
    }.getOrNull()
}

/** Perceived-luminance test (Rec. 601) — > 0.5 ⇒ "light" background. */
private fun isLightArgb(argb: Int): Boolean {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return (0.299 * r + 0.587 * g + 0.114 * b) / 255.0 > 0.5
}

private fun shiftArgbLuminance(argb: Int, delta: Float): Int {
    val a = (argb ushr 24) and 0xFF
    val d = (delta * 255).toInt()
    val r = (((argb shr 16) and 0xFF) + d).coerceIn(0, 255)
    val g = (((argb shr 8) and 0xFF) + d).coerceIn(0, 255)
    val b = ((argb and 0xFF) + d).coerceIn(0, 255)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

private fun argbToHex(argb: Int): String =
    "#%08X".format(argb.toLong() and 0xFFFFFFFFL)
// endregion
