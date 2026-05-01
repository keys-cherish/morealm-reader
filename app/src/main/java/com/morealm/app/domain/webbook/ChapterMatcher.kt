package com.morealm.app.domain.webbook

import com.morealm.app.domain.entity.BookChapter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 章节智能匹配 — 换源时把用户在旧书源的阅读位置「投影」到新书源对应章节。
 *
 * 算法（依优先级，先命中即返回）：
 *  1. **完全标题匹配**：新源里找到与旧章节标题 trim 后字符串相等的章节 — 取与 oldIndex 序号最近者。
 *  2. **章节数字匹配**：从旧标题里提取「第 X 章/节/卷/回」的阿拉伯数字 X，在新源里找同 X — 取最近者。
 *  3. **±20 窗口最大相似度**：在 oldIndex 附近 ±20 章范围内做 Levenshtein 相似度，取最高者，
 *     但只有相似度 ≥ 0.6 才采用，避免不相干章名硬靠拢。
 *  4. **降级**：以上都没把握时，oldIndex 直接 clamp 到新章节范围（保留原序号语义）。
 *
 * 注意：
 * - 仅识别阿拉伯数字（含全角）。中文数字「第一百二十章」走相似度路径，对绝大多数网文够用。
 * - Levenshtein O(L·M) 在窗口 ±20 + 标题平均 20 字以内 → 总 O(40·400) ≈ 1.6 万次比较，<1ms。
 *
 * 移植自 Legado `BookHelp.getDurChapter`，但删除了对其内部 SearchBook/章节同步状态的耦合，
 * 保持纯函数无副作用。
 */
object ChapterMatcher {

    /**
     * @param oldChapters 旧源章节列表（来自 chapter db，可空 → 走降级）
     * @param oldIndex   用户在旧源的当前章节序号
     * @param newChapters 新源章节列表（WebBook.getChapterListAwait 的结果）
     * @return 在新源中应当落到的章节序号；保证 0 ≤ result ≤ newChapters.lastIndex
     */
    fun findBestMatch(
        oldChapters: List<BookChapter>,
        oldIndex: Int,
        newChapters: List<ChapterResult>,
    ): Int {
        if (newChapters.isEmpty()) return 0
        // Boundary: 旧章节缺失（首次拉 toc）— 用 oldIndex clamp 保守降级。
        if (oldChapters.isEmpty() || oldIndex !in oldChapters.indices) {
            return oldIndex.coerceIn(0, newChapters.lastIndex)
        }
        val oldTitle = oldChapters[oldIndex].title.trim()
        if (oldTitle.isEmpty()) return oldIndex.coerceIn(0, newChapters.lastIndex)

        // 1. Exact title match (preserves trailing punctuation/whitespace stripped equivalence).
        val exact = newChapters.indices.filter {
            newChapters[it].title.trim() == oldTitle
        }
        if (exact.isNotEmpty()) {
            return exact.minByOrNull { abs(it - oldIndex) }!!
        }

        // 2. Chapter-number match.
        val oldNum = extractChapterNumber(oldTitle)
        if (oldNum != null) {
            val byNum = newChapters.indices.filter {
                extractChapterNumber(newChapters[it].title) == oldNum
            }
            if (byNum.isNotEmpty()) {
                return byNum.minByOrNull { abs(it - oldIndex) }!!
            }
        }

        // 3. Bounded similarity window (±20). Stops a bad guess from spanning the whole book.
        val low = max(0, oldIndex - 20)
        val high = min(newChapters.lastIndex, oldIndex + 20)
        var bestIdx = oldIndex.coerceIn(0, newChapters.lastIndex)
        var bestSim = -1.0
        for (i in low..high) {
            val sim = similarity(oldTitle, newChapters[i].title.trim())
            if (sim > bestSim) {
                bestSim = sim
                bestIdx = i
            }
        }
        return if (bestSim >= 0.6) bestIdx else oldIndex.coerceIn(0, newChapters.lastIndex)
    }

    // ── helpers ──

    private val chapterNumRegex = Regex("第\\s*([0-9０-９]+)\\s*[章节節卷回话話篇]")
    private val pureNumRegex = Regex("^\\s*([0-9０-９]+)\\s*[\\s.\u3001、:：]")

    private fun extractChapterNumber(rawTitle: String): Long? {
        val title = normalizeFullWidthDigits(rawTitle)
        chapterNumRegex.find(title)?.groupValues?.getOrNull(1)
            ?.let { num -> num.toLongOrNull()?.let { return it } }
        pureNumRegex.find(title)?.groupValues?.getOrNull(1)
            ?.let { num -> num.toLongOrNull()?.let { return it } }
        return null
    }

    /** Convert full-width digits ０-９ to ASCII 0-9 so a single regex covers both. */
    private fun normalizeFullWidthDigits(s: String): String {
        if (s.none { it in '\uFF10'..'\uFF19' }) return s
        return buildString(s.length) {
            for (c in s) {
                if (c in '\uFF10'..'\uFF19') append('0' + (c.code - 0xFF10))
                else append(c)
            }
        }
    }

    /** Levenshtein-based similarity ratio in [0, 1]. */
    internal fun similarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val maxLen = max(a.length, b.length).toDouble()
        return 1.0 - levenshtein(a, b) / maxLen
    }

    /** Classic O(m·n) Levenshtein distance. */
    private fun levenshtein(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        if (m == 0) return n
        if (n == 0) return m
        // Two-row rolling DP — O(min(m, n)) memory.
        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)
        for (i in 1..m) {
            curr[0] = i
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    prev[j] + 1,
                    curr[j - 1] + 1,
                    prev[j - 1] + cost,
                )
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[n]
    }
}
