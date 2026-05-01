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
 */
@Serializable
data class LegadoThemeConfig(
    val themeName: String = "",
    val isNightTheme: Boolean = false,
    val primaryColor: String = "#ff000000",
    val accentColor: String = "#ff000000",
    val backgroundColor: String = "#ffffffff",
    val bottomBackground: String = "#ffffffff",
    val customCss: String = "",
) {
    fun toThemeEntity(): ThemeEntity = ThemeEntity(
        id = "legado_${themeName.hashCode()}",
        name = themeName,
        author = "Legado Import",
        isBuiltin = false,
        isNightTheme = isNightTheme,
        primaryColor = primaryColor,
        accentColor = accentColor,
        backgroundColor = backgroundColor,
        bottomBackground = bottomBackground,
        surfaceColor = backgroundColor,
        onBackgroundColor = if (isNightTheme) "#FFEDEDEF" else "#FF1A1A1A",
        readerBackground = backgroundColor,
        readerTextColor = if (isNightTheme) "#FFEDEDEF" else "#FF1A1A1A",
        customCss = customCss,
    )
}
