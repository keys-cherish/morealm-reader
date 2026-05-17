package com.morealm.app.ui.reader.renderer.scroll

/**
 * 滚动 + 章节 swap 纯函数 —— 抽出来便于单测，不依赖 Compose UI。
 *
 * 由 [ScrollCanvasRenderer] 的 `Modifier.scrollable` 回调调用。负责：
 * 1. 把手势 delta 转成 [ScrollCanvasReaderState.pixelOffset] 累加
 * 2. 跨章边界（pixelOffset 跨过 cur.totalHeight 或 < 0）时 swap chapter 引用 + 重置 pixelOffset
 * 3. 全书首/末章边界 clamp（pixelOffset 不能超出 cur 范围）
 *
 * ── 与旧 LazyScrollRenderer 跳章 bug 的根本差异 ──
 *
 * 旧路径靠 `ChapterWindowSource` 动态窗口 + LazyColumn 索引锚定 + trim 算法，
 * trim race 会产生 chapter window hole（如 [3,4,5,6,7,8,13]）→ paragraphs 物理
 * 布局上 8 章末直接接 13 章 → 视觉跳章。
 *
 * 本函数走"swap chapter 引用"模型：prev/cur/next 三章永远是有序连续的（idx+1），
 * swap 仅交换引用 + 异步加载新的远端章，**架构层面消除 hole 可能性**。
 */

/**
 * 把滚动 delta 应用到 [state]，必要时 swap chapter 引用 + 触发 [onChapterShift] 回调。
 *
 * @param delta Compose `Modifier.scrollable` 提供：手指上推 → delta > 0；下拉 → delta < 0。
 *              语义：内容向上滚（看后面）= 正向 delta。
 *              pixelOffset 增加对应 viewport 下移到章节更靠后位置，故 `pixelOffset -= delta`。
 * @param onChapterShift swap 完成后通知 ViewModel（delta=±1）：
 *                       VM 收到后应异步加载新的远端章（prev/next）填回 state，
 *                       以及更新进度持久化 chapterIndex / chapterPosition。
 * @return 已消费的 delta（当前实现：始终全部消费返回 delta）
 */
internal fun applyScrollDelta(
    state: ScrollCanvasReaderState,
    delta: Float,
    onChapterShift: (Int) -> Unit,
): Float {
    val curH = state.currentChapter?.totalHeight ?: return delta
    val newOffset = state.pixelOffset - delta

    when {
        // 向下滚（newOffset >= curH）跨过 cur 末 + next 已就绪 → swap to next
        newOffset >= curH && state.nextChapter != null -> {
            val next = state.nextChapter!!
            state.prevChapter = state.currentChapter
            state.currentChapter = next
            state.nextChapter = null  // VM 异步加载新 next 后填回
            state.pixelOffset = newOffset - curH
            state.currentChapterIndex += 1
            onChapterShift(+1)
        }

        // 向上滚（newOffset < 0）跨过 cur 顶 + prev 已就绪 → swap to prev
        newOffset < 0f && state.prevChapter != null -> {
            val prev = state.prevChapter!!
            val prevH = prev.totalHeight
            state.nextChapter = state.currentChapter
            state.currentChapter = prev
            state.prevChapter = null  // VM 异步加载新 prev 后填回
            state.pixelOffset = newOffset + prevH
            state.currentChapterIndex -= 1
            onChapterShift(-1)
        }

        // 边界 clamp：
        //   - 末章（nextChapter=null）：pixelOffset 不能超 curH
        //   - 首章（prevChapter=null）：pixelOffset 不能 < 0
        //   - 否则正常滚动
        else -> {
            val maxOffset = if (state.nextChapter != null) Float.MAX_VALUE else curH
            val minOffset = if (state.prevChapter != null) -Float.MAX_VALUE else 0f
            state.pixelOffset = newOffset.coerceIn(minOffset, maxOffset)
        }
    }
    return delta
}
