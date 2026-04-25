package com.morealm.app.ui.theme

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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

/** Shift hue by [degrees] while preserving saturation and lightness (approximate via RGB rotation). */
private fun Color.hueShift(degrees: Float): Color {
    val r = red; val g = green; val b = blue
    val cosA = kotlin.math.cos(Math.toRadians(degrees.toDouble())).toFloat()
    val sinA = kotlin.math.sin(Math.toRadians(degrees.toDouble())).toFloat()
    val newR = (cosA + (1 - cosA) / 3f) * r + (1f / 3f * (1 - cosA) - kotlin.math.sqrt(1f / 3f) * sinA) * g + (1f / 3f * (1 - cosA) + kotlin.math.sqrt(1f / 3f) * sinA) * b
    val newG = (1f / 3f * (1 - cosA) + kotlin.math.sqrt(1f / 3f) * sinA) * r + (cosA + 1f / 3f * (1 - cosA)) * g + (1f / 3f * (1 - cosA) - kotlin.math.sqrt(1f / 3f) * sinA) * b
    val newB = (1f / 3f * (1 - cosA) - kotlin.math.sqrt(1f / 3f) * sinA) * r + (1f / 3f * (1 - cosA) + kotlin.math.sqrt(1f / 3f) * sinA) * g + (cosA + 1f / 3f * (1 - cosA)) * b
    return Color(newR.coerceIn(0f, 1f), newG.coerceIn(0f, 1f), newB.coerceIn(0f, 1f), alpha)
}

/** Pick contrasting on-color: black for light backgrounds, white for dark. */
private fun contrastOn(bg: Color): Color =
    if (bg.luminance() > 0.4f) Color.Black else Color.White

private data class ThemeColorTargets(
    val primary: Color,
    val secondary: Color,
    val background: Color,
    val surface: Color,
    val onBackground: Color,
    val accent: Color,
    val readerBackground: Color,
    val readerText: Color,
    val onPrimary: Color,
    val onSecondary: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val outlineVariant: Color,
    val surfaceContainerLowest: Color,
    val surfaceContainerLow: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
    val surfaceDim: Color,
    val surfaceBright: Color,
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val inverseSurface: Color,
    val inverseOnSurface: Color,
    val inversePrimary: Color,
)

private fun ThemeEntity.toColorTargets(): ThemeColorTargets {
    val isNight = isNightTheme
    val primary = primaryColor.toComposeColor()
    val secondary = accentColor.toComposeColor()
    val background = backgroundColor.toComposeColor()
    val surface = surfaceColor.toComposeColor()
    val onBackground = onBackgroundColor.toComposeColor()
    val tertiary = primary.hueShift(60f)
    val error = if (isNight) Color(0xFFFF897D) else Color(0xFFBA1A1A)
    return ThemeColorTargets(
        primary = primary,
        secondary = secondary,
        background = background,
        surface = surface,
        onBackground = onBackground,
        accent = secondary,
        readerBackground = readerBackground.toComposeColor(),
        readerText = readerTextColor.toComposeColor(),
        onPrimary = contrastOn(primary),
        onSecondary = contrastOn(secondary),
        tertiary = tertiary,
        onTertiary = contrastOn(tertiary),
        primaryContainer = if (isNight) primary.mix(surface, 0.75f) else primary.mix(background, 0.85f),
        onPrimaryContainer = if (isNight) primary.mix(onBackground, 0.4f) else primary.mix(Color.Black, 0.6f),
        secondaryContainer = if (isNight) secondary.mix(surface, 0.80f) else secondary.mix(background, 0.88f),
        onSecondaryContainer = if (isNight) secondary.mix(onBackground, 0.4f) else secondary.mix(Color.Black, 0.6f),
        tertiaryContainer = if (isNight) tertiary.mix(surface, 0.80f) else tertiary.mix(background, 0.88f),
        onTertiaryContainer = if (isNight) tertiary.mix(onBackground, 0.4f) else tertiary.mix(Color.Black, 0.6f),
        surfaceVariant = if (isNight) surface.mix(onBackground, 0.08f) else surface.mix(onBackground, 0.06f),
        onSurfaceVariant = if (isNight) onBackground.mix(surface, 0.30f) else onBackground.mix(background, 0.30f),
        outline = if (isNight) onBackground.mix(surface, 0.55f) else onBackground.mix(background, 0.62f),
        outlineVariant = if (isNight) onBackground.mix(surface, 0.80f) else onBackground.mix(background, 0.85f),
        surfaceContainerLowest = if (isNight) surface.mix(Color.Black, 0.10f) else surface.mix(Color.White, 0.10f),
        surfaceContainerLow = if (isNight) surface.mix(onBackground, 0.02f) else surface.mix(onBackground, 0.01f),
        surfaceContainer = if (isNight) surface.mix(onBackground, 0.05f) else surface.mix(onBackground, 0.03f),
        surfaceContainerHigh = if (isNight) surface.mix(onBackground, 0.08f) else surface.mix(onBackground, 0.05f),
        surfaceContainerHighest = if (isNight) surface.mix(onBackground, 0.12f) else surface.mix(onBackground, 0.08f),
        surfaceDim = if (isNight) surface.mix(Color.Black, 0.15f) else surface.mix(Color.Black, 0.06f),
        surfaceBright = if (isNight) surface.mix(onBackground, 0.15f) else surface.mix(Color.White, 0.10f),
        error = error,
        onError = if (isNight) Color(0xFF690005) else Color.White,
        errorContainer = if (isNight) error.mix(surface, 0.75f) else error.mix(background, 0.85f),
        onErrorContainer = if (isNight) error.mix(onBackground, 0.4f) else error.mix(Color.Black, 0.6f),
        inverseSurface = onBackground,
        inverseOnSurface = background,
        inversePrimary = if (isNight) primary.mix(Color.Black, 0.3f) else primary.mix(Color.White, 0.3f),
    )
}
@Stable
data class MoRealmColors(
    val accent: Color,
    val readerBackground: Color,
    val readerText: Color,
    val isNight: Boolean,
    val transparentBars: Boolean = false,
    val backgroundImageUri: String? = null,
)

val LocalMoRealmColors = staticCompositionLocalOf {
    MoRealmColors(
        accent = Color(0xFF7C5CFC),
        readerBackground = Color(0xFF0A0A0F),
        readerText = Color(0xFFEDEDEF),
        isNight = true,
    )
}

@Composable
fun MoRealmTheme(
    theme: ThemeEntity? = null,
    content: @Composable () -> Unit,
) {
    val t = theme ?: BuiltinThemes.moRealm
    val targets = remember(t) { t.toColorTargets() }
    val spec = tween<Color>(durationMillis = 420, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f))
    val transition = updateTransition(targetState = targets, label = "theme")

    // Use the HTML prototype easing: cubic-bezier(.16, 1, .3, 1).
    val primary by transition.animateColor(transitionSpec = { spec }, label = "primary") { it.primary }
    val secondary by transition.animateColor(transitionSpec = { spec }, label = "secondary") { it.secondary }
    val background by transition.animateColor(transitionSpec = { spec }, label = "bg") { it.background }
    val surface by transition.animateColor(transitionSpec = { spec }, label = "surface") { it.surface }
    val onBg by transition.animateColor(transitionSpec = { spec }, label = "onBg") { it.onBackground }
    val accent by transition.animateColor(transitionSpec = { spec }, label = "accent") { it.accent }
    val readerBg by transition.animateColor(transitionSpec = { spec }, label = "readerBg") { it.readerBackground }
    val readerText by transition.animateColor(transitionSpec = { spec }, label = "readerText") { it.readerText }
    val onPrimary by transition.animateColor(transitionSpec = { spec }, label = "onPrimary") { it.onPrimary }
    val onSecondary by transition.animateColor(transitionSpec = { spec }, label = "onSecondary") { it.onSecondary }
    val tertiary by transition.animateColor(transitionSpec = { spec }, label = "tertiary") { it.tertiary }
    val onTertiary by transition.animateColor(transitionSpec = { spec }, label = "onTertiary") { it.onTertiary }
    val primaryContainer by transition.animateColor(transitionSpec = { spec }, label = "primaryContainer") { it.primaryContainer }
    val onPrimaryContainer by transition.animateColor(transitionSpec = { spec }, label = "onPrimaryContainer") { it.onPrimaryContainer }
    val secondaryContainer by transition.animateColor(transitionSpec = { spec }, label = "secondaryContainer") { it.secondaryContainer }
    val onSecondaryContainer by transition.animateColor(transitionSpec = { spec }, label = "onSecondaryContainer") { it.onSecondaryContainer }
    val tertiaryContainer by transition.animateColor(transitionSpec = { spec }, label = "tertiaryContainer") { it.tertiaryContainer }
    val onTertiaryContainer by transition.animateColor(transitionSpec = { spec }, label = "onTertiaryContainer") { it.onTertiaryContainer }
    val surfaceVariant by transition.animateColor(transitionSpec = { spec }, label = "surfaceVariant") { it.surfaceVariant }
    val onSurfaceVariant by transition.animateColor(transitionSpec = { spec }, label = "onSurfaceVariant") { it.onSurfaceVariant }
    val outline by transition.animateColor(transitionSpec = { spec }, label = "outline") { it.outline }
    val outlineVariant by transition.animateColor(transitionSpec = { spec }, label = "outlineVariant") { it.outlineVariant }
    val surfaceContainerLowest by transition.animateColor(transitionSpec = { spec }, label = "surfaceContainerLowest") { it.surfaceContainerLowest }
    val surfaceContainerLow by transition.animateColor(transitionSpec = { spec }, label = "surfaceContainerLow") { it.surfaceContainerLow }
    val surfaceContainer by transition.animateColor(transitionSpec = { spec }, label = "surfaceContainer") { it.surfaceContainer }
    val surfaceContainerHigh by transition.animateColor(transitionSpec = { spec }, label = "surfaceContainerHigh") { it.surfaceContainerHigh }
    val surfaceContainerHighest by transition.animateColor(transitionSpec = { spec }, label = "surfaceContainerHighest") { it.surfaceContainerHighest }
    val surfaceDim by transition.animateColor(transitionSpec = { spec }, label = "surfaceDim") { it.surfaceDim }
    val surfaceBright by transition.animateColor(transitionSpec = { spec }, label = "surfaceBright") { it.surfaceBright }
    val errorColor by transition.animateColor(transitionSpec = { spec }, label = "error") { it.error }
    val onError by transition.animateColor(transitionSpec = { spec }, label = "onError") { it.onError }
    val errorContainer by transition.animateColor(transitionSpec = { spec }, label = "errorContainer") { it.errorContainer }
    val onErrorContainer by transition.animateColor(transitionSpec = { spec }, label = "onErrorContainer") { it.onErrorContainer }
    val inverseSurface by transition.animateColor(transitionSpec = { spec }, label = "inverseSurface") { it.inverseSurface }
    val inverseOnSurface by transition.animateColor(transitionSpec = { spec }, label = "inverseOnSurface") { it.inverseOnSurface }
    val inversePrimary by transition.animateColor(transitionSpec = { spec }, label = "inversePrimary") { it.inversePrimary }
    // Use a single ColorScheme constructor â€?no dark/light split needed since all values are explicit
    val colorScheme = ColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        error = errorColor,
        onError = onError,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
        background = background,
        onBackground = onBg,
        surface = surface,
        onSurface = onBg,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        outline = outline,
        outlineVariant = outlineVariant,
        surfaceContainerLowest = surfaceContainerLowest,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
        surfaceDim = surfaceDim,
        surfaceBright = surfaceBright,
        inverseSurface = inverseSurface,
        inverseOnSurface = inverseOnSurface,
        inversePrimary = inversePrimary,
        scrim = Color.Black,
        surfaceTint = primary,
    )

    val moRealmColors = remember(accent, readerBg, readerText, t.isNightTheme, t.transparentBars, t.backgroundImageUri) {
        MoRealmColors(
            accent = accent,
            readerBackground = readerBg,
            readerText = readerText,
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
