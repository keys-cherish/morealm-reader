package com.morealm.app.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import com.morealm.app.domain.entity.ThemeEntity
import com.morealm.app.domain.entity.BuiltinThemes

/** Parse "#AARRGGBB" or "#RRGGBB" hex string to Compose Color */
fun String.toComposeColor(): Color {
    val hex = removePrefix("#")
    val value = hex.toLongOrNull(16) ?: return Color.Magenta
    return when (hex.length) {
        8 -> Color(value.toInt())
        6 -> Color((0xFF000000 or value).toInt())
        else -> Color.Magenta
    }
}

@Stable
data class MoRealmColors(
    val accent: Color,
    val readerBackground: Color,
    val readerText: Color,
    val bottomBar: Color,
    val surfaceGlass: Color,
    val isNight: Boolean,
    val transparentBars: Boolean = false,
    val backgroundImageUri: String? = null,
)

val LocalMoRealmColors = staticCompositionLocalOf {
    MoRealmColors(
        accent = Color(0xFF7C5CFC),
        readerBackground = Color(0xFF0A0A0F),
        readerText = Color(0xFFEDEDEF),
        bottomBar = Color(0xFF111118),
        surfaceGlass = Color(0x0FFFFFFF),
        isNight = true,
    )
}

private const val THEME_ANIM_MS = 150

@Composable
fun MoRealmTheme(
    theme: ThemeEntity? = null,
    content: @Composable () -> Unit,
) {
    val t = theme ?: BuiltinThemes.moRealm
    val spec = tween<Color>(THEME_ANIM_MS)

    // Animate all colors for smooth transition
    val primary by animateColorAsState(t.primaryColor.toComposeColor(), spec, label = "primary")
    val secondary by animateColorAsState(t.accentColor.toComposeColor(), spec, label = "secondary")
    val background by animateColorAsState(t.backgroundColor.toComposeColor(), spec, label = "bg")
    val surface by animateColorAsState(t.surfaceColor.toComposeColor(), spec, label = "surface")
    val onBg by animateColorAsState(t.onBackgroundColor.toComposeColor(), spec, label = "onBg")
    val accent by animateColorAsState(t.accentColor.toComposeColor(), spec, label = "accent")
    val readerBg by animateColorAsState(t.readerBackground.toComposeColor(), spec, label = "readerBg")
    val readerText by animateColorAsState(t.readerTextColor.toComposeColor(), spec, label = "readerText")
    val bottomBar by animateColorAsState(
        if (t.transparentBars) Color.Transparent else t.bottomBackground.toComposeColor(),
        spec, label = "bottomBar")
    val glass by animateColorAsState(
        if (t.isNightTheme) Color(0x0FFFFFFF) else Color(0x0F000000),
        spec, label = "glass")

    val colorScheme = if (t.isNightTheme) {
        darkColorScheme(
            primary = primary, secondary = secondary,
            background = background, surface = surface,
            onBackground = onBg, onSurface = onBg,
        )
    } else {
        lightColorScheme(
            primary = primary, secondary = secondary,
            background = background, surface = surface,
            onBackground = onBg, onSurface = onBg,
        )
    }

    val moRealmColors = remember(accent, readerBg, readerText, bottomBar, glass, t.isNightTheme, t.transparentBars, t.backgroundImageUri) {
        MoRealmColors(
            accent = accent,
            readerBackground = readerBg,
            readerText = readerText,
            bottomBar = bottomBar,
            surfaceGlass = glass,
            isNight = t.isNightTheme,
            transparentBars = t.transparentBars,
            backgroundImageUri = t.backgroundImageUri,
        )
    }

    CompositionLocalProvider(LocalMoRealmColors provides moRealmColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content,
        )
    }
}
