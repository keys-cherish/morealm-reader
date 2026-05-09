package com.morealm.app.domain.render

import com.morealm.app.domain.entity.ReaderStyle

/**
 * ReaderStyle 派生工具。注意 v29 起 ReaderStyle 不再持有颜色字段，颜色相关的派生
 * 函数（textColorInt / bgColorInt / bgImagePath）已被移除——颜色由 [com.morealm.app.domain.entity.ThemeEntity]
 * 提供。这里只保留与排版相关的轻量派生。
 */

fun ReaderStyle.effectiveTitleSize(): Int =
    if (titleSize > 0) titleSize else textSize + 4

fun ReaderStyle.indentString(): String =
    paragraphIndent.ifEmpty { "　　" }

fun ReaderStyle.indentCount(): Int =
    paragraphIndent.count { it == '　' }

fun ReaderStyle.isJustify(): Boolean = textAlign == "justify"
