package com.morealm.app.domain.render.color

/**
 * 正文规则上色扫描器 —— 纯本地、非 AI 的字符分类状态机（移植 ColorTxt Monarch 思路，PRD §2）。
 *
 * 输入一段「干净文本」（已剥除 EPUB inline marker），按**码点**输出每个码点的前景色 ARGB
 * （0 = 不上色，走正文默认色）。与 `ScrollLayoutEngine.currentParaCharColors` 同口径
 * （1 entry / 码点，surrogate 合 1），可直接 merge，不破 AtomCpContract。
 *
 * 状态机（PRD §2.3）按「顺序优先 + 首匹配」：
 *  - root：开符(括号/引号) > 特殊 > 数字 > 拉丁词 > 标点 > 单字符兜底(NONE)
 *  - 引号内：闭符 > 嵌套括号开符 > 特殊 > 数字 > 拉丁词 > 标点 > 兜底(quoteInner)
 *  - 括号内：闭符 > 特殊 > 数字 > 拉丁词 > 标点 > 兜底(bracketInner)（不再嵌括号）
 *
 * 默认**不跨段**（caller 每段调一次 [scan]，状态栈段间天然 reset；行内 \r\n 直接 pop）——
 * 失配开符不会把后续整段染色（PRD §10-3 容错优先）。
 *
 * 贪婪陷阱（PRD §2.5）：兜底 / inner-rest 每次只消费 1 个码点；拉丁词可连续吃（真词边界）。
 */
class RuleColorScanner(private val palette: RuleColorPalette) {

    private data class Frame(val closeCp: Int, val inner: RuleColorCategory)

    /** 开符信息：闭符码点 + 是否括号（引号子状态才允许嵌套括号）+ 子状态兜底类别。 */
    private data class Opener(val closeCp: Int, val isBracket: Boolean, val inner: RuleColorCategory)

    /** 扫描 [text]，返回每码点 ARGB（0 = 不上色）。size == text 的码点数。 */
    fun scan(text: String): IntArray {
        if (text.isEmpty()) return IntArray(0)
        // 拆码点（minSdk 21 无 String.codePoints()，手动遍历）
        val cps = ArrayList<Int>(text.length)
        var k = 0
        while (k < text.length) {
            val cp = text.codePointAt(k)
            cps.add(cp)
            k += Character.charCount(cp)
        }
        val out = IntArray(cps.size) // 默认 0 = 不上色
        val stack = ArrayDeque<Frame>()
        var j = 0
        while (j < cps.size) {
            val cp = cps[j]
            val top = stack.lastOrNull()
            val punct = palette.colorOf(RuleColorCategory.PUNCTUATION)
            if (top == null) {
                // ── root ──
                val op = openerOf(cp)
                when {
                    op != null -> {
                        out[j] = punct
                        stack.addLast(Frame(op.closeCp, op.inner))
                        j++
                    }
                    isSpecial(cp) -> { out[j] = palette.colorOf(RuleColorCategory.SPECIAL); j++ }
                    isNumber(cp) -> { out[j] = palette.colorOf(RuleColorCategory.NUMBER); j++ }
                    isLatin(cp) -> j = fillLatinWord(cps, j, out)
                    isPunctuation(cp) -> { out[j] = punct; j++ }
                    else -> { out[j] = 0; j++ } // CJK 正文等 → 不上色
                }
            } else {
                // ── 引号 / 括号子状态 ──
                val op = openerOf(cp)
                when {
                    cp == '\n'.code || cp == '\r'.code -> { stack.removeLast(); out[j] = 0; j++ }
                    // 引号内可嵌一层括号；括号内不嵌（top.inner == BRACKET_INNER 不进此分支）
                    top.inner == RuleColorCategory.QUOTE_INNER && op != null && op.isBracket -> {
                        out[j] = punct
                        stack.addLast(Frame(op.closeCp, op.inner))
                        j++
                    }
                    cp == top.closeCp -> { out[j] = punct; stack.removeLast(); j++ }
                    isSpecial(cp) -> { out[j] = palette.colorOf(RuleColorCategory.SPECIAL); j++ }
                    isNumber(cp) -> { out[j] = palette.colorOf(RuleColorCategory.NUMBER); j++ }
                    isLatin(cp) -> j = fillLatinWord(cps, j, out)
                    isPunctuation(cp) -> { out[j] = punct; j++ }
                    else -> { out[j] = palette.colorOf(top.inner); j++ } // inner-rest 兜底
                }
            }
        }
        return out
    }

    /** 从 [start] 起连续吃拉丁字母 + 变音符，全标 LATIN 色；返回下一个待处理 cp 索引。 */
    private fun fillLatinWord(cps: List<Int>, start: Int, out: IntArray): Int {
        val color = palette.colorOf(RuleColorCategory.LATIN)
        var j = start
        while (j < cps.size && (isLatin(cps[j]) || isCombiningMark(cps[j]))) {
            out[j] = color
            j++
        }
        return j
    }

    // ── 字符分类（PRD §2.2，刻意列区间不用 \p{}）──

    private fun openerOf(cp: Int): Opener? = when (cp) {
        // 括号（9）
        0x300A -> Opener(0x300B, true, RuleColorCategory.BRACKET_INNER) // 《》
        0xFF1C -> Opener(0xFF1E, true, RuleColorCategory.BRACKET_INNER) // ＜＞
        0x28 -> Opener(0x29, true, RuleColorCategory.BRACKET_INNER) // ()
        0xFF08 -> Opener(0xFF09, true, RuleColorCategory.BRACKET_INNER) // （）
        0x5B -> Opener(0x5D, true, RuleColorCategory.BRACKET_INNER) // []
        0x3010 -> Opener(0x3011, true, RuleColorCategory.BRACKET_INNER) // 【】
        0x3016 -> Opener(0x3017, true, RuleColorCategory.BRACKET_INNER) // 〖〗
        0x7B -> Opener(0x7D, true, RuleColorCategory.BRACKET_INNER) // {}
        0xFF5B -> Opener(0xFF5D, true, RuleColorCategory.BRACKET_INNER) // ｛｝
        // 引号（5）
        0x22 -> Opener(0x22, false, RuleColorCategory.QUOTE_INNER) // "" ASCII 开闭同字符
        0x300C -> Opener(0x300D, false, RuleColorCategory.QUOTE_INNER) // 「」
        0x300E -> Opener(0x300F, false, RuleColorCategory.QUOTE_INNER) // 『』
        0x201C -> Opener(0x201D, false, RuleColorCategory.QUOTE_INNER) // “”
        0x2018 -> Opener(0x2019, false, RuleColorCategory.QUOTE_INNER) // ‘’
        else -> null
    }

    private fun isNumber(cp: Int): Boolean = cp in 0x30..0x39 || cp in 0xFF10..0xFF19

    private fun isLatin(cp: Int): Boolean = when (cp) {
        in 0x41..0x5A, in 0x61..0x7A -> true // A-Z a-z
        in 0xFF21..0xFF3A, in 0xFF41..0xFF5A -> true // 全角 Ａ-Ｚ ａ-ｚ
        in 0xC0..0xD6, in 0xD8..0xF6, in 0xF8..0xFF -> true // Latin-1（跳过 × 0xD7 / ÷ 0xF7）
        in 0x100..0x17F -> true // Latin Ext-A
        in 0x180..0x24F -> true // Latin Ext-B（含拼音字母）
        else -> false
    }

    private fun isCombiningMark(cp: Int): Boolean = cp in 0x300..0x36F

    private fun isSpecial(cp: Int): Boolean = cp in SPECIAL_SET

    private fun isPunctuation(cp: Int): Boolean = cp in PUNCTUATION_SET

    companion object {
        // 特殊符号（PRD §2.2）—— × U+00D7 在此，故从拉丁字母类排除
        private val SPECIAL_SET = setOf(
            0xB7, 0x2022, 0x25AA, 0x2A, 0xFF0A, 0x2732, 0x2748, 0x203B, 0x2606,
            0x2661, 0x2665, 0x25CB, 0x25CF, 0x221A, 0x2714, 0x2611, 0xD7, 0x2718, 0x2612,
        )

        // 标点（PRD §2.2）—— 闭符 + 孤立标点；成对符号「开」字符不在此（已被 openerOf 先吃）
        private val PUNCTUATION_SET = setOf(
            0x2C, 0xFF0C, 0x2E, 0x3002, 0x21, 0xFF01, 0x3F, 0xFF1F, 0x3A, 0xFF1A,
            0x3B, 0xFF1B, 0x3001, 0xFF09, 0x5D, 0x7D, 0xFF5D, 0x3011, 0x3017, 0x300B,
            0xFF1E, 0x3E, 0x3C, 0x2026, 0x2014, 0x2D,
        )

        /**
         * CSS 优先合并（PRD §4.4 选项 A）：EPUB CSS 显式色（[css]，0=无）盖过规则色（[rule]）。
         * TXT 无 CSS（css=null）→ 全用规则色。两数组须同码点口径、同长。
         */
        fun mergeCssPriority(css: IntArray?, rule: IntArray): IntArray {
            if (css == null) return rule
            return IntArray(rule.size) { i ->
                val c = css.getOrElse(i) { 0 }
                if (c != 0) c else rule[i]
            }
        }
    }
}
