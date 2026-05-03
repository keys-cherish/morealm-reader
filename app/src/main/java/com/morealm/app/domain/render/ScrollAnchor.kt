package com.morealm.app.domain.render

/**
 * LazyColumn 滚动模式专用的恢复锚点。
 *
 * 替代旧 [com.morealm.app.ui.reader.renderer.ScrollRenderer] 的
 * `(currentPageIndex, pageOffset)` 模型——段落级 item 化后 page 概念不再唯一，
 * 必须用更细的「章 + 段 + 段内行 + 行内偏移」四级定位。
 *
 * 与 bookmark `(chapterIndex, chapterPosition)` 双向兼容（见 [bookmarkToAnchor] /
 * [ScrollAnchor.toBookmark]），让书签/进度/恢复入口零迁移成本。
 */
data class ScrollAnchor(
    /** 章 idx —— 锚定到具体某章。 */
    val chapterIndex: Int,
    /** 章内段编号（1-based，对齐 [TextLine.paragraphNum]）。 */
    val paragraphNum: Int,
    /**
     * 段内行 idx（0-based）—— 同一段被滚动越过 N 行后定位到第 N+1 行。
     *
     * 默认 0：恢复到段首行。
     */
    val lineIdxInParagraph: Int = 0,
    /**
     * 行内已滚像素 —— 用户滚动到行中间时记录精确位置。
     *
     * 默认 0：恢复到行顶。普通 bookmark 转 anchor 时不需要这个粒度，留给运行时
     * 滚动状态保存使用（如 onPause 时保存 listState.firstVisibleItemScrollOffset）。
     */
    val scrollOffsetInLine: Float = 0f,
) {
    companion object {
        /** 章首锚点 —— 默认从第一段第一行开始滚。 */
        fun atChapterStart(chapterIndex: Int): ScrollAnchor =
            ScrollAnchor(chapterIndex = chapterIndex, paragraphNum = 1)
    }
}

/**
 * 在扁平段落窗口 [paragraphs] 里查找 [anchor] 对应的 LazyColumn item index。
 *
 * 用于 LazyScrollRenderer 启动时 `listState.scrollToItem(idx, scrollOffset)`。
 *
 * @return item index in [paragraphs]，找不到返回 -1
 */
fun findAnchorIndex(paragraphs: List<ScrollParagraph>, anchor: ScrollAnchor): Int {
    for (i in paragraphs.indices) {
        val p = paragraphs[i]
        if (p.chapterIndex == anchor.chapterIndex && p.paragraphNum == anchor.paragraphNum) {
            return i
        }
    }
    return -1
}

/**
 * 计算 [anchor] 在 [paragraph] 内的 LazyColumn item 内部滚动偏移（相对 item top，像素）。
 *
 * 用于 `listState.scrollToItem(idx, scrollOffset = thisValue)`。
 *
 * 注意：返回 [Int] 因为 LazyColumn API 用 Int 像素。floor 而非 round 避免向下取整时
 * 把锚点行截掉一半（用户视角"恢复到这行"应至少完整看到行顶）。
 */
fun calcAnchorScrollOffsetPx(paragraph: ScrollParagraph, anchor: ScrollAnchor): Int {
    if (paragraph.lines.isEmpty() || paragraph.linePositions.isEmpty()) return 0
    val lineIdx = anchor.lineIdxInParagraph.coerceIn(0, paragraph.lines.lastIndex)
    val lineTop = paragraph.linePositions[lineIdx]
    return (lineTop + anchor.scrollOffsetInLine).toInt().coerceAtLeast(0)
}

/**
 * 跨章 prepend 后的锚点修正。
 *
 * **快速滚动场景**：用户疯狂下滑（或上滑）时，ViewModel 异步加载邻章并 prepend/append
 * 到段落窗口。LazyColumn 默认行为：列表顶部插入 N 项后，可见首项的 idx 不变（指向旧
 * 数据）但 idx 对应的实际段已偏移 → 用户视觉看见列表"突然往上跳 N 段"。
 *
 * 修正方案：在 prepend 落地的同一帧里，调用 `listState.scrollToItem(newIdx, oldOffset)`，
 * 其中 newIdx = oldIdx + prependedCount。
 *
 * @param oldAnchorIdx prepend 前的可见首项 idx（[androidx.compose.foundation.lazy.LazyListState.firstVisibleItemIndex]）
 * @param prependedCount 新加入列表头部的段数
 */
fun calcNewAnchorAfterPrepend(oldAnchorIdx: Int, prependedCount: Int): Int {
    require(prependedCount >= 0) { "prependedCount 不能为负，得到 $prependedCount" }
    return oldAnchorIdx + prependedCount
}

/**
 * 章内阅读进度百分比（0..100 闭区间）。
 *
 * 算法：用 paragraph.firstChapterPosition + (item 内已滚比例 × paragraph.charSize)
 * 估算「已读字符数」，再除以章总字符。比 page-based 进度更细，无 page 切换抖动。
 *
 * @param paragraph 当前可见的首段（[androidx.compose.foundation.lazy.LazyListLayoutInfo.visibleItemsInfo].first）
 * @param scrollOffsetInItem item 内已滚像素（[androidx.compose.foundation.lazy.LazyListState.firstVisibleItemScrollOffset]）
 * @param chapterCharSize 当前章总字符数（用 [TextChapter.getContent].length 取）
 */
fun calcChapterProgress(
    paragraph: ScrollParagraph,
    scrollOffsetInItem: Float,
    chapterCharSize: Int,
): Int {
    if (chapterCharSize <= 0) return 0
    val itemFraction = if (paragraph.totalHeight > 0f) {
        (scrollOffsetInItem / paragraph.totalHeight).coerceIn(0f, 1f)
    } else 0f
    // firstChapterPosition + 段内已读字符 = 已读字符总数
    val readChars = paragraph.firstChapterPosition + (paragraph.charSize * itemFraction).toInt()
    return ((readChars * 100f) / chapterCharSize).toInt().coerceIn(0, 100)
}

/**
 * 把 bookmark `(chapterIndex, chapterPosition)` 转换为 [ScrollAnchor]。
 *
 * 在 [paragraphs] 中找到 [chapterIndex] 章内 firstChapterPosition 不超过
 * [chapterPosition] 的**最末**段（保证 chapterPosition 落在该段范围内或之后），
 * 然后在段内 lines 里找到 chapterPosition 对应行。
 *
 * 用于阅读器恢复 / 跳转书签 —— 旧 bookmark 数据格式不变，渲染端按需转换。
 *
 * @return null 表示 [paragraphs] 不含 [chapterIndex] 章，调用方应等窗口加载完再重试
 */
fun bookmarkToAnchor(
    chapterIndex: Int,
    chapterPosition: Int,
    paragraphs: List<ScrollParagraph>,
): ScrollAnchor? {
    // 第 1 步：定位段。线性扫描 —— 段数百级别 + 章内段单调，没必要二分。
    var hit: ScrollParagraph? = null
    for (p in paragraphs) {
        if (p.chapterIndex != chapterIndex) {
            // 已扫过目标章后又遇到不同章 → 截断
            if (hit != null) break
            continue
        }
        if (p.firstChapterPosition <= chapterPosition) {
            hit = p
        } else {
            break
        }
    }
    val target = hit ?: return null

    // 第 2 步：段内定位行。lines.chapterPosition 单调递增。
    var lineIdx = 0
    for (i in target.lines.indices) {
        if (target.lines[i].chapterPosition <= chapterPosition) {
            lineIdx = i
        } else {
            break
        }
    }
    return ScrollAnchor(
        chapterIndex = chapterIndex,
        paragraphNum = target.paragraphNum,
        lineIdxInParagraph = lineIdx,
        scrollOffsetInLine = 0f,
    )
}

/**
 * [ScrollAnchor] 转 bookmark `(chapterIndex, chapterPosition)`。
 *
 * 逆操作：找到 [paragraphs] 中匹配 (chapterIndex, paragraphNum) 的段，再取
 * lineIdxInParagraph 对应行的 [TextLine.chapterPosition]。
 *
 * @return Pair(chapterIndex, chapterPosition)。如果 [paragraphs] 不含目标段，回退到段首
 *         （chapterPosition = 0）—— 调用方决定是否容忍这个回退（通常做 onPause 持久化时
 *         窗口已含目标段，不会触发回退）。
 */
fun ScrollAnchor.toBookmark(paragraphs: List<ScrollParagraph>): Pair<Int, Int> {
    val target = paragraphs.firstOrNull {
        it.chapterIndex == chapterIndex && it.paragraphNum == paragraphNum
    } ?: return chapterIndex to 0
    val line = target.lines.getOrNull(lineIdxInParagraph) ?: target.lines.firstOrNull()
    return chapterIndex to (line?.chapterPosition ?: target.firstChapterPosition)
}
