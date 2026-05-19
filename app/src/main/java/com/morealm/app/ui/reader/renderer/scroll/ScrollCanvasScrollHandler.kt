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
/**
 * @param allowSwap 是否允许跨章 swap。
 *   - true（默认）：fling 期 / drag session 首次 swap → 自然跨章
 *   - false：drag session 已 swap 过 1 次 → 后续 delta 只在当前章内累加 + clamp，
 *           不再跨章。根治"章顶/末持续拖动抽搐"：每次 swap 后用户视觉位置跳到 prev 末 /
 *           next 首（pixelOffset 跳 23000+px），手指 delta 在新章边界附近极易越界触发
 *           反向 swap → 振荡。session 内首次 swap 已满足"跨章"语义，后续 delta 应在
 *           新章内滚，反向跨章必须用户**抬手再按下**触发新 session（onDragStart 重置
 *           dragSwapsConsumed）。fling 期不限（用户期望惯性多跨章）。
 */
internal fun applyScrollDelta(
    state: ScrollCanvasReaderState,
    delta: Float,
    onChapterShift: (Int) -> Unit,
    allowSwap: Boolean = true,
    /**
     * [DIAG 2026-05-19] 调用来源标识，用于日志区分 drag 期 swap 还是 fling 期 swap：
     *   - "drag" → onVerticalDrag 内
     *   - "fling" → AnimationState.animateDecay 内
     *   - "" → 默认（兼容旧调用）
     * 复现完根因清晰后即删。
     */
    source: String = "",
): Float {
    val curH = state.currentChapter?.totalHeight ?: return delta
    val newOffset = state.pixelOffset - delta
    val beforeOffset = state.pixelOffset.toInt()
    val beforeChIdx = state.currentChapterIndex

    when {
        // 向下滚（newOffset >= curH）跨过 cur 末 + next 已就绪 + 本 session 允许 swap → swap to next
        newOffset >= curH && state.nextChapter != null && allowSwap -> {
            val next = state.nextChapter!!
            state.prevChapter = state.currentChapter
            state.currentChapter = next
            state.nextChapter = null  // VM 异步加载新 next 后填回
            state.pixelOffset = newOffset - curH
            state.currentChapterIndex += 1
            com.morealm.app.core.log.AppLog.info(
                "ScrollCanvasV2",
                "[swap] NEXT src=$source delta=${delta.toInt()} pixelOffset=$beforeOffset→${state.pixelOffset.toInt()}" +
                    " chIdx=$beforeChIdx→${state.currentChapterIndex} curH=${curH.toInt()}",
            )
            onChapterShift(+1)
        }

        // 向上滚（newOffset < 0）跨过 cur 顶 + prev 已就绪 + 本 session 允许 swap → swap to prev
        newOffset < 0f && state.prevChapter != null && allowSwap -> {
            val prev = state.prevChapter!!
            val prevH = prev.totalHeight
            state.nextChapter = state.currentChapter
            state.currentChapter = prev
            state.prevChapter = null  // VM 异步加载新 prev 后填回
            state.pixelOffset = newOffset + prevH
            state.currentChapterIndex -= 1
            com.morealm.app.core.log.AppLog.info(
                "ScrollCanvasV2",
                "[swap] PREV src=$source delta=${delta.toInt()} pixelOffset=$beforeOffset→${state.pixelOffset.toInt()}" +
                    " chIdx=$beforeChIdx→${state.currentChapterIndex} prevH=${prevH.toInt()}",
            )
            onChapterShift(-1)
        }

        // 边界 clamp：
        //   - 末章（nextChapter=null）或本 session 已 swap：pixelOffset 不能超 curH
        //   - 首章（prevChapter=null）或本 session 已 swap：pixelOffset 不能 < 0
        //   - 否则正常滚动（pixelOffset 接近边界但可越界，下次循环触发 swap 分支）
        else -> {
            val maxOffset = if (state.nextChapter != null && allowSwap) Float.MAX_VALUE else curH
            val minOffset = if (state.prevChapter != null && allowSwap) -Float.MAX_VALUE else 0f
            val clamped = newOffset.coerceIn(minOffset, maxOffset)
            state.pixelOffset = clamped
            // 空气墙诊断：newOffset 越界但被 allowSwap=false 阻止 swap 时 clamp 起作用 → 用户感觉
            // "拖不动"。打日志看是不是这种场景在反复发生。
            val airWall = (newOffset < minOffset && !allowSwap && state.prevChapter != null) ||
                (newOffset > maxOffset && !allowSwap && state.nextChapter != null)
            if (airWall) {
                com.morealm.app.core.log.AppLog.warn(
                    "ScrollCanvasV2",
                    "[swap] AIRWALL src=$source delta=${delta.toInt()} newOffset=${newOffset.toInt()}" +
                        " clamped=${clamped.toInt()} allowSwap=$allowSwap" +
                        " hasPrev=${state.prevChapter != null} hasNext=${state.nextChapter != null}",
                )
            }
        }
    }
    return delta
}
