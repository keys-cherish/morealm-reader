package com.morealm.app.domain.analyzeRule

/**
 * 通用的规则切分处理
 * 处理 &&、||、%% 等规则分隔符，以及平衡组匹配
 */
class RuleAnalyzer(data: String, code: Boolean = false) {

    private var queue: String = data
    private var pos = 0
    private var start = 0
    private var startX = 0

    private var rule = ArrayList<String>()
    private var step: Int = 0
    var elementsType = ""

    fun trim() {
        if (queue[pos] == '@' || queue[pos] < '!') {
            pos++
            while (queue[pos] == '@' || queue[pos] < '!') pos++
            start = pos
            startX = pos
        }
    }

    fun reSetPos() {
        pos = 0
        startX = 0
    }

    private fun consumeTo(seq: String): Boolean {
        start = pos
        val offset = queue.indexOf(seq, pos)
        return if (offset != -1) { pos = offset; true } else false
    }

    private fun consumeToAny(vararg seq: String): Boolean {
        var pos = pos
        while (pos != queue.length) {
            for (s in seq) {
                if (queue.regionMatches(pos, s, 0, s.length)) {
                    step = s.length
                    this.pos = pos
                    return true
                }
            }
            pos++
        }
        return false
    }

    private fun findToAny(vararg seq: Char): Int {
        var pos = pos
        while (pos != queue.length) {
            for (s in seq) if (queue[pos] == s) return pos
            pos++
        }
        return -1
    }
    private fun chompCodeBalanced(open: Char, close: Char): Boolean {
        var pos = pos
        var depth = 0
        var otherDepth = 0
        var inSingleQuote = false
        var inDoubleQuote = false
        do {
            if (pos == queue.length) break
            val c = queue[pos++]
            if (c != ESC) {
                if (c == '\'' && !inDoubleQuote) inSingleQuote = !inSingleQuote
                else if (c == '"' && !inSingleQuote) inDoubleQuote = !inDoubleQuote
                if (inSingleQuote || inDoubleQuote) continue
                if (c == '[') depth++
                else if (c == ']') depth--
                else if (depth == 0) {
                    if (c == open) otherDepth++
                    else if (c == close) otherDepth--
                }
            } else pos++
        } while (depth > 0 || otherDepth > 0)
        return if (depth > 0 || otherDepth > 0) false else { this.pos = pos; true }
    }

    private fun chompRuleBalanced(open: Char, close: Char): Boolean {
        var pos = pos
        var depth = 0
        var inSingleQuote = false
        var inDoubleQuote = false
        do {
            if (pos == queue.length) break
            val c = queue[pos++]
            if (c == '\'' && !inDoubleQuote) inSingleQuote = !inSingleQuote
            else if (c == '"' && !inSingleQuote) inDoubleQuote = !inDoubleQuote
            if (inSingleQuote || inDoubleQuote) continue
            else if (c == '\\') { pos++; continue }
            if (c == open) depth++
            else if (c == close) depth--
        } while (depth > 0)
        return if (depth > 0) false else { this.pos = pos; true }
    }

    tailrec fun splitRule(vararg split: String): ArrayList<String> {
        if (split.size == 1) {
            elementsType = split[0]
            return if (!consumeTo(elementsType)) {
                rule += queue.substring(startX); rule
            } else { step = elementsType.length; splitRule() }
        } else if (!consumeToAny(*split)) {
            rule += queue.substring(startX); return rule
        }
        val end = pos; pos = start
        do {
            val st = findToAny('[', '(')
            if (st == -1) {
                rule = arrayListOf(queue.substring(startX, end))
                elementsType = queue.substring(end, end + step); pos = end + step
                while (consumeTo(elementsType)) { rule += queue.substring(start, pos); pos += step }
                rule += queue.substring(pos); return rule
            }
            if (st > end) {
                rule = arrayListOf(queue.substring(startX, end))
                elementsType = queue.substring(end, end + step); pos = end + step
                while (consumeTo(elementsType) && pos < st) { rule += queue.substring(start, pos); pos += step }
                return if (pos > st) { startX = start; splitRule() }
                else { rule += queue.substring(pos); rule }
            }
            pos = st
            val next = if (queue[pos] == '[') ']' else ')'
            if (!chompBalanced(queue[pos], next)) throw Error(queue.substring(0, start) + "后未平衡")
        } while (end > pos)
        start = pos; return splitRule(*split)
    }

    @JvmName("splitRuleNext")
    private tailrec fun splitRule(): ArrayList<String> {
        val end = pos; pos = start
        do {
            val st = findToAny('[', '(')
            if (st == -1) {
                rule += arrayOf(queue.substring(startX, end)); pos = end + step
                while (consumeTo(elementsType)) { rule += queue.substring(start, pos); pos += step }
                rule += queue.substring(pos); return rule
            }
            if (st > end) {
                rule += arrayListOf(queue.substring(startX, end)); pos = end + step
                while (consumeTo(elementsType) && pos < st) { rule += queue.substring(start, pos); pos += step }
                return if (pos > st) { startX = start; splitRule() }
                else { rule += queue.substring(pos); rule }
            }
            pos = st
            val next = if (queue[pos] == '[') ']' else ')'
            if (!chompBalanced(queue[pos], next)) throw Error(queue.substring(0, start) + "后未平衡")
        } while (end > pos)
        start = pos
        return if (!consumeTo(elementsType)) { rule += queue.substring(startX); rule }
        else splitRule()
    }

    fun innerRule(inner: String, startStep: Int = 1, endStep: Int = 1, fr: (String) -> String?): String {
        val st = StringBuilder()
        while (consumeTo(inner)) {
            val posPre = pos
            if (chompCodeBalanced('{', '}')) {
                val frv = fr(queue.substring(posPre + startStep, pos - endStep))
                if (!frv.isNullOrEmpty()) {
                    st.append(queue.substring(startX, posPre) + frv)
                    startX = pos; continue
                }
            }
            pos += inner.length
        }
        return if (startX == 0) "" else st.apply { append(queue.substring(startX)) }.toString()
    }

    fun innerRule(startStr: String, endStr: String, fr: (String) -> String?): String {
        val st = StringBuilder()
        while (consumeTo(startStr)) {
            pos += startStr.length
            val posPre = pos
            if (consumeTo(endStr)) {
                val frv = fr(queue.substring(posPre, pos))
                st.append(queue.substring(startX, posPre - startStr.length) + frv)
                pos += endStr.length; startX = pos
            }
        }
        return if (startX == 0) queue else st.apply { append(queue.substring(startX)) }.toString()
    }

    val chompBalanced = if (code) ::chompCodeBalanced else ::chompRuleBalanced

    companion object {
        private const val ESC = '\\'
    }
}
