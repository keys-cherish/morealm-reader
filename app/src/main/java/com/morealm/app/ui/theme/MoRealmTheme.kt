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

/** Mix this color toward [other] by [fraction] (0.0 = this, 1.0 = other) */
private fun Color.mix(other: Color, fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return Color(
        red = red * (1 - f) + other.red * f,
        green = green * (1 - f) + other.green * f,
        blue = blue * (1 - f) + other.blue * f,
        alpha = alpha * (1 - f) + other.alpha * f,
    )
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

    // Derive missing on* colors from onBg so all components get correct text/icon colors.
    // Material3 lightColorScheme defaults assume M3 baseline purple — we must override them.
    val onPrimary = if (t.isNightTheme) Color(0xFF1A1A2E) else Color.White
    val onSecondary = onPrimary
    val onTertiary = onPrimary
    val surfaceVariant = if (t.isNightTheme) surface.mix(onBg, 0.08f) else surface.mix(onBg, 0.06f)
    val onSurfaceVariant = onBg.copy(alpha = 0.7f)
    val outline = onBg.copy(alpha = 0.3f)
    val outlineVariant = onBg.copy(alpha = 0.15f)
    val surfaceContainer = if (t.isNightTheme) surface.mix(onBg, 0.05f) else surface.mix(onBg, 0.03f)
    val surfaceContainerHigh = if (t.isNightTheme) surface.mix(onBg, 0.08f) else surface.mix(onBg, 0.05f)
    val surfaceContainerHighest = if (t.isNightTheme) surface.mix(onBg, 0.12f) else surface.mix(onBg, 0.08f)
    val inverseSurface = onBg
    val inverseOnSurface = background

    val colorScheme = if (t.isNightTheme) {
        darkColorScheme(
            primary = primary, secondary = secondary,
            background = background, surface = surface,
            onBackground = onBg, onSurface = onBg,
            onPrimary = onPrimary, onSecondary = onSecondary, onTertiary = onTertiary,
            surfaceVariant = surfaceVariant, onSurfaceVariant = onSurfaceVariant,
            outline = outline, outlineVariant = outlineVariant,
            surfaceContainer = surfaceContainer,
            surfaceContainerHigh = surfaceContainerHigh,
            surfaceContainerHighest = surfaceContainerHighest,
            inverseSurface = inverseSurface, inverseOnSurface = inverseOnSurface,
        )
    } else {
        lightColorScheme(
            primary = primary, secondary = secondary,
            background = background, surface = surface,
            onBackground = onBg, onSurface = onBg,
            onPrimary = onPrimary, onSecondary = onSecondary, onTertiary = onTertiary,
            surfaceVariant = surfaceVariant, onSurfaceVariant = onSurfaceVariant,
            outline = outline, outlineVariant = outlineVariant,
            surfaceContainer = surfaceContainer,
            surfaceContainerHigh = surfaceContainerHigh,
            surfaceContainerHighest = surfaceContainerHighest,
            inverseSurface = inverseSurface, inverseOnSurface = inverseOnSurface,
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
