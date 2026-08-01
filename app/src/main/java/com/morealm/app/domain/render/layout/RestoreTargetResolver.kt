package com.morealm.app.domain.render.layout

import kotlin.math.roundToInt

/** 恢复落点的来源级别；打进日志可直接坐实这次恢复是哪级生效。 */
enum class RestoreSource { ANCHOR, PROGRESS, CHAPTER_START }

/** [resolveRestoreTarget] 的结果：落点 + 它来自哪级。 */
data class ResolvedRestore<T>(val target: T, val source: RestoreSource)

/**
 * 恢复落点分级降级（对齐成熟阅读器的恢复模型：锚点 → 百分比 → 章首，
 * 绝不因单级失配直接弃权）。
 *
 * 此前各渲染路径各写各的，且都在单级失配时直接弃权：
 * - EPUB 翻页：cp 未命中 findColumnAt → `?: 0` 回章首，同一条记录里的 progress 从没被用过
 * - EPUB 滚动：cp 未命中 → 直接 return，restoreToken 都不消费
 * - `if (cp > 0)` 把 cp == 0 当「没存过」，但整页插图章首的合法 cp 就是 0
 *
 * 降级链：
 *  - **L1 锚点**：cp > 0 时经 [resolveByAnchor] 字符级定位，命中即用（最精确）
 *  - **L2 百分比**：L1 未命中、或 cp == 0 但 progress 有值。cp == 0 无信息量 ——
 *    既可能是「没存过」也可能是「真章首」；真章首存下的 progress 本就指向第一页，
 *    两义在 L2 收敛到同一落点，无需 DB 加字段区分。
 *  - **L3 章首**：什么都没有。
 */
inline fun <T> resolveRestoreTarget(
    chapterPosition: Int,
    progressPercent: Int,
    resolveByAnchor: (cp: Int) -> T?,
    resolveByProgress: (percent: Int) -> T,
    chapterStart: () -> T,
): ResolvedRestore<T> {
    if (chapterPosition > 0) {
        resolveByAnchor(chapterPosition)?.let { return ResolvedRestore(it, RestoreSource.ANCHOR) }
    }
    if (progressPercent > 0) {
        return ResolvedRestore(resolveByProgress(progressPercent), RestoreSource.PROGRESS)
    }
    return ResolvedRestore(chapterStart(), RestoreSource.CHAPTER_START)
}

/**
 * EPUB 翻页（page-level）progress 的逆运算。
 *
 * 上报公式是 `((pageIdx + 1) / pageCount * 100).toInt()`（PageLevelReaderHost 进度
 * 上报），逆运算必须减 1 —— 直接 `p/100*count` 等于 pageIdx + 1，恒偏后一页。
 * 与 TXT 翻页的 `i / (count - 1)` 约定不同，勿混用。
 *
 * 精度极限：整数百分比只有 100 档，章超过约 66 页后上报端的 toInt 截断可能让
 * 逆运算差一页（如 total=67, i=1 → 存 2% → 反算 0）。L2 本就是 L1 失配后的兜底，
 * 差一页可接受；根治要等锚点存储升级（后续 DB 迁移）提高存储精度。
 */
fun pagedProgressToPageIndex(progressPercent: Int, pageCount: Int): Int {
    val total = pageCount.coerceAtLeast(1)
    return ((progressPercent.toFloat() / 100f * total).roundToInt() - 1).coerceIn(0, total - 1)
}
