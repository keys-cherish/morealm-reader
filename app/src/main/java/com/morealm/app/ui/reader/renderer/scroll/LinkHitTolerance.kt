package com.morealm.app.ui.reader.renderer.scroll

import com.morealm.epub.render.LinkRange
import com.morealm.epub.render.ScrollChapterLayout
import com.morealm.epub.render.findColumnByPixel

/**
 * 链接命中的横向容差（dp）。约等于一个正文字的宽度。
 *
 * 取值权衡：再大就会把注号两侧的正文也算成"想点链接"，而点正文的语义是翻页；
 * 再小则对缩小的上标注号起不到作用。
 */
internal const val LINK_HIT_TOLERANCE_DP: Float = 10f

/**
 * 在 (x, [yInChapter]) 附近命中链接 —— 精确命中落空时向两侧重采样。
 *
 * [ScrollChapterLayout.findColumnByPixel] 横向是**精确匹配**（x 必须落在
 * `column.start..end` 内）。脚注注号是缩小的上标，column 宽度只有正文字符的一半上下，
 * 手指落点偏出几个像素就命中到相邻的正文字符，`linkAt` 返回 null、tap 不被消费，
 * 事件继续下传给翻页手势 —— 用户视角就是「点了半天没反应，还平白翻了一页」。
 *
 * 采样**由近及远**且命中即返回：正好点在注号上时，行为与精确命中完全一致；
 * 只有落空才向两侧找最近的那个链接。纵向不放宽 —— `findColumnByPixel` 本身已把
 * 行间空白吸附到最近的行。
 */
internal fun ScrollChapterLayout.linkNearPixel(
    x: Float,
    yInChapter: Float,
    tolerancePx: Float,
): LinkRange? {
    if (links.isEmpty() || tolerancePx <= 0f) return null
    val steps = floatArrayOf(tolerancePx * 0.5f, tolerancePx)
    for (step in steps) {
        for (dx in floatArrayOf(-step, step)) {
            val hit = findColumnByPixel(x + dx, yInChapter) ?: continue
            // 只认精确的字符列命中：这里若回退到 line.firstChapterPos，点在空白处
            // 会误命中该行行首的链接。
            val cp = hit.column?.chapterPosition ?: continue
            linkAt(cp)?.let { return it }
        }
    }
    return null
}
