package com.morealm.app.domain.render.color

/**
 * 高亮词匹配器（「文字上色」Phase 2）。对 clean text 一趟扫出每个**码点**命中的高亮词色号
 * （-1 = 未命中），与 RuleColorScanner 同码点口径，不破 AtomCpContract。
 *
 * 规则（PRD §2.7 / §11-1）：
 *  - **长词优先**：「张三丰」盖「张三」（按词长降序匹配，长词先占位，短词不覆盖已占码点）。
 *  - **同长按色号升序**（稳定，避免重叠歧义）。
 *  - **大小写不敏感**（lowercase 折叠）、**纯字面量**（不支持正则）。
 *
 * MVP 用「按长度降序的多模式逐词扫」——用户高亮词通常很少（个位数），O(码点数 × 词数 × 词长)
 * 可接受；词表很大时再换 Aho-Corasick（PRD §4.3-5）。
 */
class HighlightWordMatcher(words: List<Pair<String, Int>>) {

    // (词的码点序列, 色号)，长词优先 + 同长色号升序
    private val entries: List<Pair<IntArray, Int>> = words
        .filter { it.first.isNotEmpty() }
        .map { toCps(it.first.lowercase()) to it.second }
        .sortedWith(
            compareByDescending<Pair<IntArray, Int>> { it.first.size }.thenBy { it.second },
        )

    val isEmpty: Boolean get() = entries.isEmpty()

    /** 返回每码点命中的色号（-1 = 无）。长词先占，短词不覆盖已占码点（长词优先）。 */
    fun match(text: String): IntArray {
        val cps = toCps(text)
        val n = cps.size
        val out = IntArray(n) { -1 }
        if (entries.isEmpty() || n == 0) return out
        // 大小写折叠：用 lowercase 后的码点匹配；lowercase 改变码点数（极少数字符如 İ）时回退原码点。
        val lowerCps = toCps(text.lowercase())
        val matchCps = if (lowerCps.size == n) lowerCps else cps
        for ((wordCps, colorIdx) in entries) {
            val wlen = wordCps.size
            if (wlen > n) continue
            var i = 0
            while (i <= n - wlen) {
                if (regionMatches(matchCps, i, wordCps) && allFree(out, i, wlen)) {
                    for (k in 0 until wlen) out[i + k] = colorIdx
                    i += wlen
                } else {
                    i++
                }
            }
        }
        return out
    }

    private fun regionMatches(cps: IntArray, start: Int, word: IntArray): Boolean {
        for (k in word.indices) if (cps[start + k] != word[k]) return false
        return true
    }

    private fun allFree(out: IntArray, start: Int, len: Int): Boolean {
        for (k in 0 until len) if (out[start + k] != -1) return false
        return true
    }

    companion object {
        private fun toCps(s: String): IntArray {
            val list = ArrayList<Int>(s.length)
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                list.add(cp)
                i += Character.charCount(cp)
            }
            return list.toIntArray()
        }
    }
}
