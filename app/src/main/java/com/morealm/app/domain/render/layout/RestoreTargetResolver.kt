package com.morealm.app.domain.render.layout

import kotlin.math.roundToInt

/** 恢复落点的来源级别；打进日志可直接坐实这次恢复是哪级生效。 */
enum class RestoreSource { ANCHOR_VERIFIED, SNIPPET, ANCHOR, PROGRESS, CHAPTER_START }

/** [resolveRestoreTarget] 的结果：落点 + 它来自哪级。 */
data class ResolvedRestore<T>(val target: T, val source: RestoreSource)

/**
 * 恢复落点分级降级（对齐成熟阅读器的恢复模型：锚点 → 快照重定位 → 百分比 → 章首，
 * 绝不因单级失配直接弃权）。
 *
 * 降级链（锚点 v2，2026-08）：
 *  - **L0 校验锚点**：带正文快照的 cp，[verifyAnchor] 确认 cp 处文字与快照一致 →
 *    直用。cp == 0 在此级是合法值（快照本身就是「存过」的证明，不再有 cp==0 的
 *    两义性）。
 *  - **L1 快照重定位**：cp 处文字对不上（wire 协议变 / 替换规则变 / 换源正文变）→
 *    [resolveBySnippet] 拿快照在新章文本里就近搜索出新 cp，再走字符级定位。
 *    命中后调用方的进度保存循环会自动把新 cp 写回 —— 锚点自愈。
 *  - **L2 裸锚点**：无快照的旧数据，cp > 0 时经 [resolveByAnchor] 字符级定位。
 *  - **L3 百分比**：以上全失配、或 cp == 0 且无快照但 progress 有值。
 *  - **L4 章首**：什么都没有。
 */
inline fun <T> resolveRestoreTarget(
    chapterPosition: Int,
    progressPercent: Int,
    snippet: String = "",
    verifyAnchor: (cp: Int) -> Boolean = { false },
    resolveBySnippet: () -> Int? = { null },
    resolveByAnchor: (cp: Int) -> T?,
    resolveByProgress: (percent: Int) -> T,
    chapterStart: () -> T,
): ResolvedRestore<T> {
    if (snippet.isNotEmpty()) {
        if (chapterPosition >= 0 && verifyAnchor(chapterPosition)) {
            resolveByAnchor(chapterPosition)?.let {
                return ResolvedRestore(it, RestoreSource.ANCHOR_VERIFIED)
            }
        }
        resolveBySnippet()?.let { relocatedCp ->
            resolveByAnchor(relocatedCp)?.let {
                return ResolvedRestore(it, RestoreSource.SNIPPET)
            }
        }
    }
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
 * 逆运算差一页（如 total=67, i=1 → 存 2% → 反算 0）。L3 本就是锚点/快照全失配后
 * 的兜底，差一页可接受。
 */
fun pagedProgressToPageIndex(progressPercent: Int, pageCount: Int): Int {
    val total = pageCount.coerceAtLeast(1)
    return ((progressPercent.toFloat() / 100f * total).roundToInt() - 1).coerceIn(0, total - 1)
}
