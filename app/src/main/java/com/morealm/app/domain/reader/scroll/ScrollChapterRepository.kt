package com.morealm.app.domain.reader.scroll

/**
 * 滚动 Canvas 引擎章节数据提供接口 —— 让 [com.morealm.app.ui.reader.renderer.scroll.ScrollCanvasRenderer]
 * 不直接耦合 WebBook / LocalBookParser 等具体数据源。
 *
 * 生产代码（DI 模块）注入桥接 [com.morealm.app.domain.webbook.WebBook] /
 * [com.morealm.app.domain.parser.LocalBookParser] / [com.morealm.app.domain.repository.CacheRepository]
 * 的实现；单测注入 mock 实现验证 swap / prefetch 等场景。
 *
 * 与旧 [com.morealm.app.presentation.reader.scroll.ChapterWindowSource] 的根本区别：
 *   - 旧 ChapterWindowSource 维护**动态窗口**（loadedChapters 升序，1-N 章可同时存在），
 *     trim 算法 race + hole 导致跳章 bug（见 log 2026-05-17）
 *   - 本接口只服务**固定 prev/cur/next 三章模型**（M2 架构层面消除 hole 可能），
 *     调用方按需要异步加载 chapterIndex 拿 ScrollChapterContent，不再有 window 概念
 */
interface ScrollChapterRepository {
    /**
     * 异步加载指定章节的原始内容（未排版）。
     *
     * 实现负责：网络下载 / 缓存命中 / contentProcessor 处理 / 章节文本替换等。
     * 排版（→ ScrollChapterLayout）由调用方持有的
     * [com.morealm.app.domain.render.scroll.ScrollLayoutEngine] 完成。
     *
     * @param chapterIndex 章 idx（全书 0-based）
     * @return [ScrollChapterContent]；越界 / 加载失败返 null（调用方自行处理 retry / UI 兜底）
     */
    suspend fun loadChapterContent(chapterIndex: Int): ScrollChapterContent?

    /** 全书章节总数。chapterIndex < 0 或 >= count 越界。 */
    val chapterCount: Int
}

/**
 * 章节原始内容（未排版）—— [ScrollChapterRepository.loadChapterContent] 返回值。
 *
 * 不直接持 [com.morealm.app.domain.render.scroll.ScrollChapterLayout]：layout 依赖
 * 渲染参数（viewWidth/paint 等），由 UI 层在拿到 content 后调用 ScrollLayoutEngine
 * 排版得到。Repository 只关心"内容从哪来"，不关心"怎么显示"。
 */
data class ScrollChapterContent(
    val chapterIndex: Int,
    val title: String,
    val content: String,
)
