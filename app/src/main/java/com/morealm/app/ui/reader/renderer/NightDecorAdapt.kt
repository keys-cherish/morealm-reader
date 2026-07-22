package com.morealm.app.ui.reader.renderer

import androidx.core.graphics.ColorUtils
import com.morealm.epub.compat.BlockStyle

internal enum class AuthoredColorRole {
    FOREGROUND,
    BACKGROUND,
    BORDER,
}

/**
 * EPUB authored color 的统一暗底映射。背景、前景和边框分别约束饱和度与明度；前景位于
 * authored 背景盒内时，从深浅两组中选择对比度更高的一组。这样既保留原色的语义区分，
 * 又避免亮色块刺眼或黑字融入夜间背景。浅色阅读背景原样返回。
 */
internal fun adaptAuthoredColorForReaderBg(
    authoredArgb: Int,
    readerBgArgb: Int,
    role: AuthoredColorRole,
    authoredSurfaceArgb: Int? = null,
): Int {
    val readerHsl = FloatArray(3)
    ColorUtils.colorToHSL(readerBgArgb, readerHsl)
    val bgL = readerHsl[2]
    if (bgL >= 0.45f) return authoredArgb

    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(authoredArgb, hsl)
    when (role) {
        AuthoredColorRole.FOREGROUND -> {
            val surface = authoredSurfaceArgb?.let {
                adaptAuthoredColorForReaderBg(it, readerBgArgb, AuthoredColorRole.BACKGROUND)
            }
            if (surface != null) {
                val contrastSurface = if ((surface ushr 24) == 0xFF) {
                    surface
                } else {
                    ColorUtils.compositeColors(surface, readerBgArgb)
                }
                val dark = 0xFF292A27.toInt()
                val light = 0xFFC8C7C0.toInt()
                val darkContrast = ColorUtils.calculateContrast(dark, contrastSurface)
                val lightContrast = ColorUtils.calculateContrast(light, contrastSurface)
                return withAuthoredAlpha(
                    if (darkContrast >= lightContrast) dark else light,
                    authoredArgb,
                )
            }
            when {
                hsl[2] <= 0.2f -> {
                    hsl[1] = minOf(hsl[1] * 0.2f, 0.08f)
                    hsl[2] = 0.55f
                }
                hsl[2] >= 0.82f -> {
                    hsl[1] = minOf(hsl[1] * 0.25f, 0.1f)
                    hsl[2] = 0.78f
                }
                else -> {
                    hsl[1] = minOf(hsl[1] * 0.42f, 0.34f)
                    hsl[2] = hsl[2].coerceIn(0.52f, 0.64f)
                }
            }
        }
        AuthoredColorRole.BACKGROUND -> {
            when {
                hsl[1] < 0.08f && hsl[2] < 0.18f -> hsl[2] = 0.36f
                hsl[1] < 0.08f -> hsl[2] = 0.28f
                else -> {
                    hsl[1] = minOf(hsl[1] * 0.34f, 0.3f)
                    hsl[2] = if (hsl[2] >= 0.35f) 0.47f else 0.4f
                }
            }
        }
        AuthoredColorRole.BORDER -> {
            hsl[1] = minOf(hsl[1] * 0.3f, 0.18f)
            hsl[2] = hsl[2].coerceIn(0.42f, 0.54f)
        }
    }
    val rgb = ColorUtils.HSLToColor(hsl) and 0x00FFFFFF
    val alpha = (authoredArgb ushr 24) and 0xFF
    return (alpha shl 24) or rgb
}

private fun withAuthoredAlpha(rgbArgb: Int, authoredArgb: Int): Int =
    (((authoredArgb ushr 24) and 0xFF) shl 24) or (rgbArgb and 0x00FFFFFF)

internal fun adaptDecorationBgForReaderBg(epubBgArgb: Int, readerBgArgb: Int): Int =
    adaptAuthoredColorForReaderBg(epubBgArgb, readerBgArgb, AuthoredColorRole.BACKGROUND)

internal fun adaptAuthoredForegroundForReaderBg(
    authoredArgb: Int,
    readerBgArgb: Int,
    authoredSurfaceArgb: Int? = null,
): Int = adaptAuthoredColorForReaderBg(
    authoredArgb,
    readerBgArgb,
    AuthoredColorRole.FOREGROUND,
    authoredSurfaceArgb,
)

internal fun adaptAuthoredBlockDecorForReaderBg(style: BlockStyle, readerBgArgb: Int): BlockStyle {
    val readerHsl = FloatArray(3)
    ColorUtils.colorToHSL(readerBgArgb, readerHsl)
    if (readerHsl[2] >= 0.45f) return style
    fun border(color: Int?): Int? = color?.let {
        adaptAuthoredColorForReaderBg(it, readerBgArgb, AuthoredColorRole.BORDER)
    }
    return style.copy(
        backgroundColor = style.backgroundColor?.let {
            adaptAuthoredColorForReaderBg(it, readerBgArgb, AuthoredColorRole.BACKGROUND)
        },
        borderColor = border(style.borderColor),
        borderTopColor = border(style.borderTopColor),
        borderRightColor = border(style.borderRightColor),
        borderBottomColor = border(style.borderBottomColor),
        borderLeftColor = border(style.borderLeftColor),
    )
}
