package com.morealm.app.ui.reader.renderer.scroll

import android.text.TextPaint
import com.morealm.app.domain.font.EpubFontRegistry

/**
 * 在一小段 EPUB 文本的绘制期间应用其自带字体，结束后恢复调用方 paint。
 *
 * block 字体仍由整行绘制负责；这里处理 span、atom 和 table cell 对 block 字体的覆盖，
 * 避免共享 TextPaint 把某一段字体泄漏到后续文字。
 */
internal inline fun <T> TextPaint.withEpubTypeface(
    family: String?,
    draw: () -> T,
): T {
    val resolved = EpubFontRegistry.resolveActive(family) ?: return draw()
    val previous = typeface
    typeface = resolved
    return try {
        draw()
    } finally {
        typeface = previous
    }
}
