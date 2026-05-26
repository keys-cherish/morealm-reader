package com.morealm.app.ui.reader.page

import android.graphics.Bitmap

/**
 * **动画层 ↔ 渲染层契约线**（P3-1 引入）。
 *
 * 所有翻页动画（COVER / SLIDE / SIMULATION / NONE / SLIDE_VERTICAL）经此 interface
 * 拿到「第 N 页的 Bitmap」。动画层不再认识 chapter content / TextPage / paint /
 * styling / highlights 等渲染细节，**渲染回归 ↔ 动画回归** 从此两条独立挡板：
 *
 *  - 动画 bug → 用 [com.morealm.app.ui.reader.page.test.FakePageBitmapProvider]
 *    （返回固定色块 Bitmap）复现，跟渲染层零关系
 *  - 渲染 bug → 用真 EPUB + 黄金图 pixel-diff 复现，跟动画层零关系
 *
 * **设计要点：**
 *
 *  1. **绝对 index**：[bitmapAt] 接 0..pageCount-1 的绝对页号。Caller（动画层 /
 *     PagerState）持有 currentDisplayPage，不再用 SimulationReadView 时代的
 *     「relativePos -1/0/+1」相对偏移
 *  2. **同步返回**：渲染必须**已完成**才返回 Bitmap。Caller 应在 IO/computation
 *     线程调用避免 ANR；主线程仅 Image(bitmap) 即可
 *  3. **null 不 crash**：越界 / chapter 加载中 / 渲染失败返回 null。动画层应用
 *     占位（黑屏 / cached last frame / loading spinner）处理
 *  4. **Bitmap 所有权**：所有权移交给 caller（动画层）。Caller 在 pageIndex
 *     滑出窗口后 recycle，避免 GC 抖动（参考 SimulationPager.recycleExcept 模式）
 *  5. **不含交互 / 手势 / 选区 callback**：那些是动画层自己的职责，跟数据契约
 *     无关。AnimatedPageReader 单独接收 onSingleTap / onLongPress / onPageSettled
 *     等交互 lambda
 *
 * **范围**：仅覆盖 page-level 4 动画。SCROLL 模式由 ScrollCanvasReaderHost 独立
 * 接管（没有「页」概念，连续 column 不适用此契约线）。
 *
 * ── 跟「Bitmap-baked 视图」相关的 3 个并行机制 ──
 *
 *  1. **选区命中（hitTest）走独立 interface** —— PageBitmapProvider 只负责画
 *     Bitmap，**不**返回 char position / line rect。settled 状态下用户长按 →
 *     hitTest 通过独立 `PageHitTester`（P3-3+ 引入）查 char 位置。这跟 Bitmap
 *     化无关，主流阅读器都是 settled 后才允许选词
 *  2. **TalkBack 无障碍** —— Bitmap 本身是哑的，需要在 `Image(bitmap)` 旁挂
 *     `Modifier.semantics { contentDescription = pageText }`（pageText 由
 *     [com.morealm.epub.compat.StructuredChapterContent.flattenToString] 提供）。
 *     当前 MoRealm 未实现 TalkBack 朗读页内容（TTS 是自实现），P3 不强制做，
 *     未来可选加
 *  3. **动态浮层（popup / 气泡 / TTS 跟随高亮）** —— 这些是叠在 Bitmap 之上的
 *     独立 Composable Box(modifier=overlay)，**不影响底层 Bitmap**。仅当
 *     chapter highlights 真正变化（如保存 / 删除 highlight、TTS 念到下一段）
 *     时才触发 PageBitmapProvider 重画该页 —— 重画成本 = 一次 Bitmap allocate +
 *     渲染（< 100ms 量级，可接受）
 *
 * **实现：**
 *  - [com.morealm.app.ui.reader.page.test.FakePageBitmapProvider] —— 测试用
 *    固定色块 Bitmap（P3-2 引入）
 *  - LegacyAdapter —— 把现有 pageContent: @Composable + SimulationParams 包装
 *    成 PageBitmapProvider（P3-3 引入，临时桥接老调用方）
 *  - RichPageBitmapProvider —— 真实渲染：消费 StructuredChapterContent →
 *    AnnotatedString → 画 Bitmap（P3-5 引入，flag 控制开启）
 */
interface PageBitmapProvider {

    /**
     * 返回第 [displayIndex] 页的 Bitmap，渲染到 [w]×[h] 像素。
     *
     * @param displayIndex 0..[pageCount]-1 的绝对页号。越界返回 null（不 throw）
     * @param w 目标 Bitmap 宽度（像素，> 0）
     * @param h 目标 Bitmap 高度（像素，> 0）
     * @return 已渲染完毕的 Bitmap，所有权移交 caller；越界 / 失败返回 null
     */
    fun bitmapAt(displayIndex: Int, w: Int, h: Int): Bitmap?

    /**
     * 当前可翻页面总数。
     *
     * 单章模式 = 该章 pages.size。Cross-chapter unified 模式（如 SLIDE / COVER 跨
     * 章 unified pageCount = prev + cur + next 三章）= 三章总和。
     *
     * Caller 用此值设 [androidx.compose.foundation.pager.PagerState] 的 pageCount。
     * 章节切换 / 字号变化 / chapter relayout 时此值可能变化 —— caller 应该
     * 重新订阅。
     */
    val pageCount: Int
}
