package com.morealm.app.ui.reader.renderer

import androidx.core.graphics.ColorUtils

/**
 * 夜间装饰底色自适应。EPUB 自带 box `background-color` 在暗色阅读背景下按字面画会
 * 「浅灰白底大块闪眼 / 纯黑圆标糊进暗底看不见」。仅当阅读背景为暗色（明度 < 0.5，
 * 即夜间纯色背景）时，把装饰底色明度镜像锚定到阅读背景之上：亮底→贴近暗背景的暗
 * 面板、暗色→提亮到可见；色相 / 饱和度 / alpha 全部保留。浅色背景（日间 / sepia）
 * 原样返回，零行为变化。
 */
internal fun adaptDecorationBgForReaderBg(epubBgArgb: Int, readerBgArgb: Int): Int {
    val readerHsl = FloatArray(3)
    ColorUtils.colorToHSL(readerBgArgb, readerHsl)
    val bgL = readerHsl[2]
    if (bgL >= 0.5f) return epubBgArgb

    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(epubBgArgb, hsl)
    // 装饰相对白底(L=1)的暗量 = (1 - origL)，镜像锚到暗背景明度之上。
    // 下限 bgL+0.04 → 面板始终略亮于页面不消失；上限 0.72 → 避免近白刺眼。
    hsl[2] = (bgL + (1f - hsl[2])).coerceIn(bgL + 0.04f, 0.72f)
    val rgb = ColorUtils.HSLToColor(hsl) and 0x00FFFFFF
    val alpha = (epubBgArgb ushr 24) and 0xFF
    return (alpha shl 24) or rgb
}
