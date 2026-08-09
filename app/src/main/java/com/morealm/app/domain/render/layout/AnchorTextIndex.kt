package com.morealm.app.domain.render.layout

import com.morealm.epub.render.ScrollChapterLayout

/**
 * 锚点快照参数 —— 保存端与恢复端必须同源，勿单边改。
 *
 * 设计（对照成熟阅读器的位置体系，做了简化）：
 * 成熟实现给锚点挂 kernel_version / book_revision 两个版本号，恢复时按「哪个版本变了」
 * 分诊走不同的重映射路径。我们不做版本记账 —— 锚点自带一段正文快照（[ANCHOR_SNIPPET_CP_SPAN]
 * 个 cp 跨度内的可见字符），恢复时直接拿快照对内容自校验：
 *  - cp 处文字与快照对得上 → cp 仍有效，直用（等价「版本没变」，但不依赖任何版本号）；
 *  - 对不上 → 快照在新章文本里就近搜索重定位（等价 fuzzyMatch，一条路径吸收
 *    wire 协议变化 / 替换规则变化 / 书源换正文 全部失效原因）。
 * 重定位后的新 cp 会随既有的进度保存循环自动写回 —— 自愈不需要专门代码。
 */
const val ANCHOR_SNIPPET_CP_SPAN = 32

/** 快照短于此字符数不启用搜索（信息量不足，误匹配风险大于收益）。 */
const val MIN_SNIPPET_CHARS = 6

/** cp 自校验只比对快照前缀这么多字符 —— 尾部允许因段末/图片占位差异截断。 */
private const val VERIFY_PREFIX_CHARS = 16

/** 快照搜索命中：起点 cp + 终点 cp（排他，供高亮区间重定位直接用）。 */
data class SnippetHit(val startCp: Int, val endCpExclusive: Int)

/**
 * 章文本索引 —— 把 [ScrollChapterLayout] 的可见字符摊平成 (text, cp) 平行结构，
 * 支撑锚点自校验与快照搜索。cp 在 layout 里不保证连续（图片/空段/marker 占位），
 * 所以必须带平行数组，不能拿字符下标当 cp 用。
 *
 * 构建 O(章字符数)，只在「需要校验/搜索」时建（正常翻页保存路径不建）。
 */
class AnchorTextIndex internal constructor(
    val text: String,
    private val cps: IntArray,
) {
    /** cp → 字符下标；cp 不在索引里返回 -1。cps 单调递增，二分。 */
    fun charIndexOfCp(cp: Int): Int {
        var lo = 0
        var hi = cps.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                cps[mid] < cp -> lo = mid + 1
                cps[mid] > cp -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    /**
     * 自校验：cp 处正文与 [expected] 快照前缀一致（最多比 [VERIFY_PREFIX_CHARS] 字）。
     * 快照可能带段末截断，所以「谁短按谁比」；两边都空视为不通过。
     */
    fun verifyAt(cp: Int, expected: String): Boolean {
        if (expected.isEmpty()) return false
        val idx = charIndexOfCp(cp)
        if (idx < 0) return false
        val n = minOf(expected.length, VERIFY_PREFIX_CHARS, text.length - idx)
        if (n < minOf(expected.length, MIN_SNIPPET_CHARS)) return false
        return text.regionMatches(idx, expected, 0, n)
    }

    /**
     * 快照搜索：在章文本里找 [snippet]，多处命中取离 [nearCp] 最近的一处。
     *
     * 两级降级：全串精确匹配 → 前缀核心（去空白后前 12 字）匹配。
     * 找不到返 null，让调用方落到 progress 兜底。
     */
    fun findNearestCp(snippet: String, nearCp: Int): SnippetHit? {
        val cleaned = snippet.trim()
        if (cleaned.length < MIN_SNIPPET_CHARS) return null
        return searchNearest(cleaned, nearCp)
            ?: if (cleaned.length > 12) searchNearest(cleaned.take(12), nearCp) else null
    }

    private fun searchNearest(needle: String, nearCp: Int): SnippetHit? {
        if (needle.length < MIN_SNIPPET_CHARS) return null
        var best: SnippetHit? = null
        var bestDist = Int.MAX_VALUE
        var from = 0
        var guard = 0
        while (guard++ < 256) {
            val at = text.indexOf(needle, from)
            if (at < 0) break
            val dist = kotlin.math.abs(cps[at] - nearCp)
            if (dist < bestDist) {
                bestDist = dist
                best = SnippetHit(
                    startCp = cps[at],
                    endCpExclusive = cps[at + needle.length - 1] + 1,
                )
            }
            from = at + 1
        }
        return best
    }

    companion object {
        fun fromLayout(layout: ScrollChapterLayout): AnchorTextIndex {
            val sb = StringBuilder()
            val cps = ArrayList<Int>(1024)
            for (page in layout.pages) {
                for (line in page.lines) {
                    for (col in line.columns) {
                        // charData 理论上单字符；防御多字符时 cp 对齐首字符即可
                        // （搜索/校验都是前向匹配，尾部字符共享同 cp 不影响正确性）。
                        for (ch in col.charData) {
                            sb.append(ch)
                            cps.add(col.chapterPosition)
                        }
                    }
                }
            }
            return AnchorTextIndex(sb.toString(), cps.toIntArray())
        }
    }
}

/** 便捷入口：从 layout 建索引。 */
fun ScrollChapterLayout.buildAnchorTextIndex(): AnchorTextIndex = AnchorTextIndex.fromLayout(this)
