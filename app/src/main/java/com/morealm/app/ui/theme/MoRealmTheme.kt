package com.morealm.app.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
    val spec = tween<Color>(300)

    // Animate all colors for smooth transition
    val primary by animateColorAsState(t.primaryColor.toComposeColor(), spec, label = "primary")
    val secondary by animateColorAsState(t.accentColor.toComposeColor(), spec, label = "secondary")
    val background by animateColorAsState(t.backgroundColor.toComposeColor(), spec, label = "bg")
    val surface by animateColorAsState(t.surfaceColor.toComposeColor(), spec, label = "surface")
    val onBg by animateColorAsState(t.onBackgroundColor.toComposeColor(), spec, label = "onBg")
    val accent by animateColorAsState(t.accentColor.toComposeColor(), spec, label = "accent")
    val readerBg by animateColorAsState(t.readerBackground.toComposeColor(), spec, label = "readerBg")
    val readerText by animateColorAsState(t.readerTextColor.toComposeColor(), spec, label = "readerText")

    // ── Derive full MD3 color roles — compute target values, then animate ALL of them ──

    val isNight = t.isNightTheme
    val targetPrimary = t.primaryColor.toComposeColor()
    val targetSecondary = t.accentColor.toComposeColor()
    val targetBackground = t.backgroundColor.toComposeColor()
    val targetSurface = t.surfaceColor.toComposeColor()
    val targetOnBg = t.onBackgroundColor.toComposeColor()

    val targetOnPrimary = contrastOn(targetPrimary)
    val targetOnSecondary = contrastOn(targetSecondary)
    val targetTertiary = targetPrimary.hueShift(60f)
    val targetOnTertiary = contrastOn(targetTertiary)

    val targetPrimaryContainer = if (isNight) targetPrimary.mix(targetSurface, 0.75f) else targetPrimary.mix(targetBackground, 0.85f)
    val targetOnPrimaryContainer = if (isNight) targetPrimary.mix(targetOnBg, 0.4f) else targetPrimary.mix(Color.Black, 0.6f)
    val targetSecondaryContainer = if (isNight) targetSecondary.mix(targetSurface, 0.80f) else targetSecondary.mix(targetBackground, 0.88f)
    val targetOnSecondaryContainer = if (isNight) targetSecondary.mix(targetOnBg, 0.4f) else targetSecondary.mix(Color.Black, 0.6f)
    val targetTertiaryContainer = if (isNight) targetTertiary.mix(targetSurface, 0.80f) else targetTertiary.mix(targetBackground, 0.88f)
    val targetOnTertiaryContainer = if (isNight) targetTertiary.mix(targetOnBg, 0.4f) else targetTertiary.mix(Color.Black, 0.6f)

    val targetSurfaceVariant = if (isNight) targetSurface.mix(targetOnBg, 0.08f) else targetSurface.mix(targetOnBg, 0.06f)
    val targetOnSurfaceVariant = if (isNight) targetOnBg.mix(targetSurface, 0.30f) else targetOnBg.mix(targetBackground, 0.30f)
    val targetOutline = if (isNight) targetOnBg.mix(targetSurface, 0.55f) else targetOnBg.mix(targetBackground, 0.62f)
    val targetOutlineVariant = if (isNight) targetOnBg.mix(targetSurface, 0.80f) else targetOnBg.mix(targetBackground, 0.85f)

    val targetSurfaceContainerLowest = if (isNight) targetSurface.mix(Color.Black, 0.10f) else targetSurface.mix(Color.White, 0.10f)
    val targetSurfaceContainerLow = if (isNight) targetSurface.mix(targetOnBg, 0.02f) else targetSurface.mix(targetOnBg, 0.01f)
    val targetSurfaceContainer = if (isNight) targetSurface.mix(targetOnBg, 0.05f) else targetSurface.mix(targetOnBg, 0.03f)
    val targetSurfaceContainerHigh = if (isNight) targetSurface.mix(targetOnBg, 0.08f) else targetSurface.mix(targetOnBg, 0.05f)
    val targetSurfaceContainerHighest = if (isNight) targetSurface.mix(targetOnBg, 0.12f) else targetSurface.mix(targetOnBg, 0.08f)
    val targetSurfaceDim = if (isNight) targetSurface.mix(Color.Black, 0.15f) else targetSurface.mix(Color.Black, 0.06f)
    val targetSurfaceBright = if (isNight) targetSurface.mix(targetOnBg, 0.15f) else targetSurface.mix(Color.White, 0.10f)

    val targetErrorColor = if (isNight) Color(0xFFFF897D) else Color(0xFFBA1A1A)
    val targetOnError = if (isNight) Color(0xFF690005) else Color.White
    val targetErrorContainer = if (isNight) targetErrorColor.mix(targetSurface, 0.75f) else targetErrorColor.mix(targetBackground, 0.85f)
    val targetOnErrorContainer = if (isNight) targetErrorColor.mix(targetOnBg, 0.4f) else targetErrorColor.mix(Color.Black, 0.6f)

    val targetInverseSurface = targetOnBg
    val targetInverseOnSurface = targetBackground
    val targetInversePrimary = if (isNight) targetPrimary.mix(Color.Black, 0.3f) else targetPrimary.mix(Color.White, 0.3f)

    // ── Animate every single color role for a unified smooth transition ──

    val onPrimary by animateColorAsState(targetOnPrimary, spec, label = "onPrimary")
    val onSecondary by animateColorAsState(targetOnSecondary, spec, label = "onSecondary")
    val tertiary by animateColorAsState(targetTertiary, spec, label = "tertiary")
    val onTertiary by animateColorAsState(targetOnTertiary, spec, label = "onTertiary")
    val primaryContainer by animateColorAsState(targetPrimaryContainer, spec, label = "primaryContainer")
    val onPrimaryContainer by animateColorAsState(targetOnPrimaryContainer, spec, label = "onPrimaryContainer")
    val secondaryContainer by animateColorAsState(targetSecondaryContainer, spec, label = "secondaryContainer")
    val onSecondaryContainer by animateColorAsState(targetOnSecondaryContainer, spec, label = "onSecondaryContainer")
    val tertiaryContainer by animateColorAsState(targetTertiaryContainer, spec, label = "tertiaryContainer")
    val onTertiaryContainer by animateColorAsState(targetOnTertiaryContainer, spec, label = "onTertiaryContainer")
    val surfaceVariant by animateColorAsState(targetSurfaceVariant, spec, label = "surfaceVariant")
    val onSurfaceVariant by animateColorAsState(targetOnSurfaceVariant, spec, label = "onSurfaceVariant")
    val outline by animateColorAsState(targetOutline, spec, label = "outline")
    val outlineVariant by animateColorAsState(targetOutlineVariant, spec, label = "outlineVariant")
    val surfaceContainerLowest by animateColorAsState(targetSurfaceContainerLowest, spec, label = "surfaceContainerLowest")
    val surfaceContainerLow by animateColorAsState(targetSurfaceContainerLow, spec, label = "surfaceContainerLow")
    val surfaceContainer by animateColorAsState(targetSurfaceContainer, spec, label = "surfaceContainer")
    val surfaceContainerHigh by animateColorAsState(targetSurfaceContainerHigh, spec, label = "surfaceContainerHigh")
    val surfaceContainerHighest by animateColorAsState(targetSurfaceContainerHighest, spec, label = "surfaceContainerHighest")
    val surfaceDim by animateColorAsState(targetSurfaceDim, spec, label = "surfaceDim")
    val surfaceBright by animateColorAsState(targetSurfaceBright, spec, label = "surfaceBright")
    val errorColor by animateColorAsState(targetErrorColor, spec, label = "error")
    val onError by animateColorAsState(targetOnError, spec, label = "onError")
    val errorContainer by animateColorAsState(targetErrorContainer, spec, label = "errorContainer")
    val onErrorContainer by animateColorAsState(targetOnErrorContainer, spec, label = "onErrorContainer")
    val inverseSurface by animateColorAsState(targetInverseSurface, spec, label = "inverseSurface")
    val inverseOnSurface by animateColorAsState(targetInverseOnSurface, spec, label = "inverseOnSurface")
    val inversePrimary by animateColorAsState(targetInversePrimary, spec, label = "inversePrimary")

    // Use a single ColorScheme constructor — no dark/light split needed since all values are explicit
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
