package com.morealm.app.domain.render.layout

import com.morealm.epub.render.ScrollChapterLayout

/**
 * 取得当前视口锚点对应的章内字符坐标。
 *
 * 横向分页把 [viewportAnchorY] 传 0，保存当前页首；连续滚动传视口高度的 1/3，
 * 与恢复逻辑把目标字符放在上方 1/3 的算法互为逆运算，避免每次重开都向前漂几行。
 */
internal fun visibleChapterPosition(
    layout: ScrollChapterLayout,
    pageIndex: Int,
    pageOffset: Float,
    viewportAnchorY: Float = 0f,
): Int? {
    if (layout.pages.isEmpty()) return null

    var targetPageIndex = pageIndex.coerceIn(layout.pages.indices)
    var offsetInPage = pageOffset.coerceAtLeast(0f) + viewportAnchorY.coerceAtLeast(0f)

    // 连续滚动的锚点可能跨过当前排版页，先把它归一到真正承载该坐标的页。
    while (targetPageIndex < layout.pages.lastIndex) {
        val height = layout.pages[targetPageIndex].height.coerceAtLeast(0f)
        if (offsetInPage < height) break
        offsetInPage = (offsetInPage - height).coerceAtLeast(0f)
        targetPageIndex += 1
    }

    val page = layout.pages[targetPageIndex]
    val line = page.lines.firstOrNull { it.lineBottom > offsetInPage }
        ?: page.lines.lastOrNull()
    line?.let { return it.firstChapterPos.coerceAtLeast(0) }

    // 整页图片等页面可能没有文本行；取最近的可定位文本，避免把已有锚点误刷成 0。
    for (index in targetPageIndex + 1..layout.pages.lastIndex) {
        layout.pages[index].lines.firstOrNull()?.let {
            return it.firstChapterPos.coerceAtLeast(0)
        }
    }
    for (index in targetPageIndex - 1 downTo 0) {
        layout.pages[index].lines.lastOrNull()?.let {
            return it.firstChapterPos.coerceAtLeast(0)
        }
    }
    return null
}
