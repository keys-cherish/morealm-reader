package com.morealm.app.domain.render.scroll

/**
 * 整章像素级排版结果 —— 滚动 Canvas 引擎（ScrollLayoutEngine）的章节级产物。
 *
 * 与现有 [com.morealm.app.domain.render.PageLayout] / [com.morealm.app.domain.render.ChapterProvider]
 * 输出的区别：
 *   - 旧 ChapterProvider 服务 SIMULATION/COVER/SLIDE 翻页模式，输出 TextChapter（含 pages
 *     但带翻页动画相关字段，例如 page.containPos 用于"哪页含某 chapterPosition"分页查询）
 *   - 旧 ScrollParagraph 列表服务 LazyColumn 段流模式
 *   - **本类专为新滚动 Canvas 三块面板 (prev/cur/next) 设计**，pixelOffset 滚动模式下
 *     不需要"翻页"概念，pages 仅是渲染剔除单元；不依赖、不复用、不污染上两套
 *
 * 命名约定：所有 Scroll* 前缀 = 新滚动引擎专属，禁止与 ChapterProvider / TextChapter
 * / ScrollParagraph 混用方法或字段，避免语义漂移。
 */
data class ScrollChapterLayout(
    /** 章 idx（全书内 0-based）。三块面板 prev/cur/next 通过此字段标识。 */
    val chapterIndex: Int,
    /** 章节显示标题（已经过 contentProcessor / titleReplace 处理）。顶栏 / 进度条用。 */
    val title: String,
    /** 章内所有页，按 [ScrollPage.pageIndex] 升序。空内容章节 = 单空页（pages.size == 1，lines 空）。 */
    val pages: List<ScrollPage>,
    /** 整章像素总高 = `pages.sumOf { it.height }`；预计算供 Layout measure 阶段 O(1) 读取。 */
    val totalHeight: Float,
    /**
     * 排版时的视口宽（像素）。视口宽变化（屏幕旋转 / 分屏）触发整章重排版的判定依据：
     * `viewWidth != newWidth` → 失效，重 layout。
     */
    val viewWidth: Int,
    /**
     * 排版样式签名（字号 / 行距 / 边距 / 字体 / 标题对齐 等所有影响排版结果的字段组合 hash）。
     * 设置变更（reflow）的命中判定：`styleSignature == currentSignature` → 复用，否则重排。
     * 实现需要保证：同输入文本 + 同 signature → 输出位级一致（hash 必须等）。
     */
    val styleSignature: String,
    /**
     * 章内字符总数（含空段占 1 cp / 图片占 1 cp）—— [ScrollLayoutEngine] 在 layoutChapter
     * 结束时显式记录 `chapterPositionCounter` 终值。
     *
     * 不再用「最后 column.chapterPosition + 1」派生：末段是空段 / 末页是空页时该派生
     * 会漏算（columns 为空 → null → 0），与持久化语义错位。显式字段保证正确性。
     */
    val totalCharCount: Int,
    /**
     * 排版时的 paddingLeft（像素）。column.start 是相对 paddingLeft **内侧**的 x 坐标
     * （范围 0..visibleWidth），渲染层 ChapterPaneCanvas 需 translate(paddingLeft, 0)
     * 让文字落在正确位置（不贴屏幕左缘）。
     * 默认 0 兼容老调用方（已存在的 mock layout 不传也能 compile + 渲染贴左缘只是视觉问题）。
     */
    val paddingLeft: Int = 0,
    /**
     * 排版时的 paddingTop（像素）—— 章首留白。三块面板拼接时 prev.paddingBottom 与
     * cur.paddingTop 重叠，避免双倍 padding 视觉巨大空白。
     */
    val paddingTop: Int = 0,
    /**
     * 排版时的 paddingBottom（像素）—— 章末留白。三块面板拼接时与下章 paddingTop 重叠。
     */
    val paddingBottom: Int = 0,
) {
    /** 章是否为可见内容为空（所有 page.lines 都为空）。空章节渲染兜底"加载中"/"内容为空"占位。 */
    val isEmpty: Boolean
        get() = pages.all { it.lines.isEmpty() }
}
