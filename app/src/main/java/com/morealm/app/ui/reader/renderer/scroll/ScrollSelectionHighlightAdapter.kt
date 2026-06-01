package com.morealm.app.ui.reader.renderer.scroll

import com.morealm.app.domain.entity.Highlight
import com.morealm.epub.render.ScrollChapterLayout
import com.morealm.app.domain.render.layout.ScrollHighlightDrawSpec
import com.morealm.app.domain.render.layout.ScrollHighlightProjector

/**
 * 选区 → 高亮 spec 转换 —— 复用 [ScrollHighlightProjector] 投影路径绘制选区背景。
 *
 * 核心思路：选区视觉上 = 一个临时的 KIND_BACKGROUND 高亮（半透明蓝色覆盖文字）。
 * 转换成 [ScrollHighlightDrawSpec] 喂给 [ChapterPaneCanvas.highlightSpecs] 即可
 * 复用整个绘制流程，**零额外渲染代码**。
 *
 * 与持久化 Highlight 的区别：
 *   - 选区是用户交互瞬时状态（长按后存在；点空白消失）
 *   - Highlight 是 DB 持久数据（用户在选区菜单点「高亮」后才落库）
 *
 * 但绘制层面两者等价，故复用 Projector。
 */
object ScrollSelectionHighlightAdapter {

    /**
     * 默认选区背景色 —— 半透明蓝（A=85 / R=108 / G=204 / B=255）。
     * 颜色与 Material You / 系统选区色调和；M2.7 接入主题后可被替换为 colorScheme.primaryContainer。
     */
    const val DEFAULT_SELECTION_ARGB: Int = 0x556CCCFF.toInt()

    /**
     * 把选区转成可绘制的 [ScrollHighlightDrawSpec]。
     *
     * @return 选区跨越的 rect spec；selection inactive / chapterIndex 不匹配 / cp 不命中
     *         任何 line 返 null（调用方据此跳过绘制）
     */
    fun toHighlightSpec(
        selection: ScrollSelectionState,
        layout: ScrollChapterLayout,
        argb: Int = DEFAULT_SELECTION_ARGB,
    ): ScrollHighlightDrawSpec? {
        if (!selection.isActive) return null
        if (selection.chapterIndex != layout.chapterIndex) return null
        val range = selection.cpRange
        // 构造临时 Highlight 喂给 Projector 复用 rect 计算逻辑
        val tempHighlight = Highlight(
            id = SELECTION_HIGHLIGHT_ID,
            bookId = SELECTION_BOOK_ID,
            chapterIndex = layout.chapterIndex,
            startChapterPos = range.first,
            endChapterPos = range.last,
            content = "",
            colorArgb = argb,
            kind = Highlight.KIND_BACKGROUND,
        )
        return ScrollHighlightProjector.project(layout, listOf(tempHighlight)).firstOrNull()
    }

    /** 临时 highlight id sentinel —— 调用方可据此识别"这是选区不是真高亮"。 */
    private const val SELECTION_HIGHLIGHT_ID = "_selection_"
    private const val SELECTION_BOOK_ID = "_selection_"
}
