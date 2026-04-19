package com.morealm.app.ui.theme

import com.morealm.app.domain.entity.ThemeEntity

/**
 * 6 built-in themes for MoRealm.
 * Each theme has carefully tuned colors for both app chrome and reader.
 */
object BuiltinThemes {

    /** 墨境 — Dark purple glassmorphism, signature theme */
    val moRealm = ThemeEntity(
        id = "morealm_default",
        name = "墨境",
        isBuiltin = true,
        isNightTheme = true,
        isActive = true,
        primaryColor = "#FF7C5CFC",
        accentColor = "#FF7C5CFC",
        backgroundColor = "#FF0A0A0F",
        surfaceColor = "#FF111118",
        onBackgroundColor = "#FFEDEDEF",
        bottomBackground = "#FF111118",
        readerBackground = "#FF0A0A0F",
        readerTextColor = "#FFEDEDEF",
    )

    /** 纸上 — Warm paper, classic reading with paper texture */
    val paper = ThemeEntity(
        id = "paper",
        name = "纸上",
        isBuiltin = true,
        isNightTheme = false,
        primaryColor = "#FF92400E",
        accentColor = "#FFD97706",
        backgroundColor = "#FFFDFBF7",
        surfaceColor = "#FFF5F0E8",
        onBackgroundColor = "#FF1A1A1A",
        bottomBackground = "#FFF5F0E8",
        readerBackground = "#FFFAF6EF",
        readerTextColor = "#FF2D2D2D",
        backgroundImageUri = "texture:paper",
    )

    /** 赛博朋克 — Neon magenta cyberpunk */
    val cyber = ThemeEntity(
        id = "cyber",
        name = "赛博朋克",
        isBuiltin = true,
        isNightTheme = true,
        primaryColor = "#FFFF2D95",
        accentColor = "#FFFF2D95",
        backgroundColor = "#FF0D0D0D",
        surfaceColor = "#FF1A1A2E",
        onBackgroundColor = "#FFE0E0E0",
        bottomBackground = "#FF0D0D1A",
        readerBackground = "#FF0D0D0D",
        readerTextColor = "#FFE0E0E0",
    )

    /** 森林 — Deep green, eye-friendly */
    val forest = ThemeEntity(
        id = "forest",
        name = "森林",
        isBuiltin = true,
        isNightTheme = true,
        primaryColor = "#FF4CAF50",
        accentColor = "#FF81C784",
        backgroundColor = "#FF1B2A1B",
        surfaceColor = "#FF243324",
        onBackgroundColor = "#FFD7E8D7",
        bottomBackground = "#FF1B2A1B",
        readerBackground = "#FF1B2A1B",
        readerTextColor = "#FFDCE8DC",
    )

    /** 深夜 — Pure AMOLED black */
    val midnight = ThemeEntity(
        id = "midnight",
        name = "深夜",
        isBuiltin = true,
        isNightTheme = true,
        primaryColor = "#FF6366F1",
        accentColor = "#FF818CF8",
        backgroundColor = "#FF000000",
        surfaceColor = "#FF0A0A0A",
        onBackgroundColor = "#FFB0B0B0",
        bottomBackground = "#FF050505",
        readerBackground = "#FF000000",
        readerTextColor = "#FFA0A0A0",
    )

    /** 墨水屏 — High contrast grayscale, E-Ink optimized */
    val eink = ThemeEntity(
        id = "eink",
        name = "墨水屏",
        isBuiltin = true,
        isNightTheme = false,
        primaryColor = "#FF333333",
        accentColor = "#FF555555",
        backgroundColor = "#FFFFFFFF",
        surfaceColor = "#FFF0F0F0",
        onBackgroundColor = "#FF000000",
        bottomBackground = "#FFF5F5F5",
        readerBackground = "#FFFFFFFF",
        readerTextColor = "#FF000000",
    )

    fun all(): List<ThemeEntity> = listOf(moRealm, paper, cyber, forest, midnight, eink)
}
