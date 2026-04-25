package com.morealm.app.domain.analyzeRule

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import org.jsoup.select.Collector
import org.jsoup.select.Elements
import org.jsoup.select.Evaluator
import org.seimicrawler.xpath.JXNode

/**
 * JSoup 规则解析器
 */
class AnalyzeByJSoup(doc: Any) {

    companion object {
        private val nullSet = setOf(null)
    }

    private var element: Element = parse(doc)

    private fun parse(doc: Any): Element {
        if (doc is Element) return doc
        if (doc is JXNode) return if (doc.isElement) doc.asElement() else Jsoup.parse(doc.toString())
        kotlin.runCatching {
            if (doc.toString().startsWith("<?xml", true)) {
                return Jsoup.parse(doc.toString(), Parser.xmlParser())
            }
        }
        return Jsoup.parse(doc.toString())
    }

    internal fun getElements(rule: String): Elements {
        if (looksLikeJsonPath(rule)) return Elements()
        return getElements(element, rule)
    }

    internal fun getString(ruleStr: String): String? {
        if (ruleStr.isEmpty()) return null
        val list = getStringList(ruleStr)
        if (list.isEmpty()) return null
        if (list.size == 1) return list.first()
        return list.joinToString("\n")
    }

    internal fun getString0(ruleStr: String) =
        getStringList(ruleStr).let { if (it.isEmpty()) "" else it[0] }

    internal fun getStringList(ruleStr: String): List<String> {
        val textS = ArrayList<String>()
        if (ruleStr.isEmpty()) return textS
        val sourceRule = SourceRule(ruleStr)
        if (sourceRule.elementsRule.isEmpty()) {
            textS.add(element.data() ?: "")
        } else {
            val ruleAnalyzes = RuleAnalyzer(sourceRule.elementsRule)
            val ruleStrS = ruleAnalyzes.splitRule("&&", "||", "%%")
            val results = ArrayList<List<String>>()
            for (ruleStrX in ruleStrS) {
                val temp: ArrayList<String>? =
                    if (sourceRule.isCss) {
                        val lastIndex = ruleStrX.lastIndexOf('@')
                        getResultLast(
                            element.select(ruleStrX.substring(0, lastIndex)),
                            ruleStrX.substring(lastIndex + 1)
                        )
                    } else {
                        getResultList(ruleStrX)
                    }
                if (!temp.isNullOrEmpty()) {
                    results.add(temp)
                    if (ruleAnalyzes.elementsType == "||") break
                }
            }
            if (results.size > 0) {
                if ("%%" == ruleAnalyzes.elementsType) {
                    for (i in results[0].indices) {
                        for (temp in results) { if (i < temp.size) textS.add(temp[i]) }
                    }
                } else {
                    for (temp in results) textS.addAll(temp)
                }
            }
        }
        return textS
    }
    private fun getElements(temp: Element?, rule: String): Elements {
        if (temp == null || rule.isEmpty()) return Elements()
        if (looksLikeJsonPath(rule)) return Elements()
        val elements = Elements()
        val sourceRule = SourceRule(rule)
        val ruleAnalyzes = RuleAnalyzer(sourceRule.elementsRule)
        val ruleStrS = ruleAnalyzes.splitRule("&&", "||", "%%")
        val elementsList = ArrayList<Elements>()
        if (sourceRule.isCss) {
            for (ruleStr in ruleStrS) {
                if (looksLikeJsonPath(ruleStr)) continue
                val tempS = temp.select(ruleStr)
                elementsList.add(tempS)
                if (tempS.size > 0 && ruleAnalyzes.elementsType == "||") break
            }
        } else {
            for (ruleStr in ruleStrS) {
                val rsRule = RuleAnalyzer(ruleStr)
                rsRule.trim()
                val rs = rsRule.splitRule("@")
                val el = if (rs.size > 1) {
                    val el = Elements()
                    el.add(temp)
                    for (rl in rs) {
                        val es = Elements()
                        for (et in el) es.addAll(getElements(et, rl))
                        el.clear(); el.addAll(es)
                    }
                    el
                } else ElementsSingle().getElementsSingle(temp, ruleStr)
                elementsList.add(el)
                if (el.size > 0 && ruleAnalyzes.elementsType == "||") break
            }
        }
        if (elementsList.size > 0) {
            if ("%%" == ruleAnalyzes.elementsType) {
                for (i in 0 until elementsList[0].size) {
                    for (es in elementsList) { if (i < es.size) elements.add(es[i]) }
                }
            } else {
                for (es in elementsList) elements.addAll(es)
            }
        }
        return elements
    }

    private fun getResultList(ruleStr: String): ArrayList<String>? {
        if (ruleStr.isEmpty()) return null
        if (looksLikeJsonPath(ruleStr)) return null
        var elements = Elements()
        elements.add(element)
        val rule = RuleAnalyzer(ruleStr)
        rule.trim()
        val rules = rule.splitRule("@")
        val last = rules.size - 1
        for (i in 0 until last) {
            val es = Elements()
            for (elt in elements) es.addAll(ElementsSingle().getElementsSingle(elt, rules[i]))
            elements.clear(); elements = es
        }
        return if (elements.isEmpty()) null else getResultLast(elements, rules[last])
    }

    private fun getResultLast(elements: Elements, lastRule: String): ArrayList<String> {
        val textS = ArrayList<String>()
        when (lastRule) {
            "text" -> for (element in elements) {
                val text = element.text()
                if (text.isNotEmpty()) textS.add(text)
            }
            "textNodes" -> for (element in elements) {
                val tn = arrayListOf<String>()
                val contentEs = element.textNodes()
                for (item in contentEs) {
                    val text = item.text().trim { it <= ' ' }
                    if (text.isNotEmpty()) tn.add(text)
                }
                if (tn.isNotEmpty()) textS.add(tn.joinToString("\n"))
            }
            "ownText" -> for (element in elements) {
                val text = element.ownText()
                if (text.isNotEmpty()) textS.add(text)
            }
            "html" -> {
                elements.select("script").remove()
                elements.select("style").remove()
                val html = elements.outerHtml()
                if (html.isNotEmpty()) textS.add(html)
            }
            "all" -> textS.add(elements.outerHtml())
            else -> for (element in elements) {
                val url = element.attr(lastRule)
                if (url.isBlank() || textS.contains(url)) continue
                textS.add(url)
            }
        }
        return textS
    }

    @Suppress("UNCHECKED_CAST")
    data class ElementsSingle(
        var split: Char = '.',
        var beforeRule: String = "",
        val indexDefault: MutableList<Int> = mutableListOf(),
        val indexes: MutableList<Any> = mutableListOf()
    ) {
        fun getElementsSingle(temp: Element, rule: String): Elements {
            findIndexSet(rule)
            var elements =
                if (beforeRule.isEmpty()) temp.children()
                else {
                    val rules = beforeRule.split(".")
                    when (rules[0]) {
                        "children" -> temp.children()
                        "class" -> temp.getElementsByClass(rules[1])
                        "tag" -> temp.getElementsByTag(rules[1])
                        "id" -> Collector.collect(Evaluator.Id(rules[1]), temp)
                        "text" -> temp.getElementsContainingOwnText(rules[1])
                        else -> temp.select(beforeRule)
                    }
                }
            val len = elements.size
            val lastIndexes = (indexDefault.size - 1).takeIf { it != -1 } ?: (indexes.size - 1)
            val indexSet = mutableSetOf<Int>()
            if (indexes.isEmpty()) for (ix in lastIndexes downTo 0) {
                val it = indexDefault[ix]
                if (it in 0 until len) indexSet.add(it)
                else if (it < 0 && len >= -it) indexSet.add(it + len)
            } else for (ix in lastIndexes downTo 0) {
                if (indexes[ix] is Triple<*, *, *>) {
                    val (startX, endX, stepX) = indexes[ix] as Triple<Int?, Int?, Int>
                    var start = startX ?: 0; if (start < 0) start += len
                    var end = endX ?: (len - 1); if (end < 0) end += len
                    if ((start < 0 && end < 0) || (start >= len && end >= len)) continue
                    if (start >= len) start = len - 1 else if (start < 0) start = 0
                    if (end >= len) end = len - 1 else if (end < 0) end = 0
                    if (start == end || stepX >= len) { indexSet.add(start); continue }
                    val step = if (stepX > 0) stepX else if (-stepX < len) stepX + len else 1
                    indexSet.addAll(if (end > start) start..end step step else start downTo end step step)
                } else {
                    val it = indexes[ix] as Int
                    if (it in 0 until len) indexSet.add(it)
                    else if (it < 0 && len >= -it) indexSet.add(it + len)
                }
            }
            if (split == '!') {
                val removeSet = mutableSetOf<Element>()
                for (pcInt in indexSet) removeSet.add(elements[pcInt])
                elements.removeAll(removeSet)
            } else if (split == '.') {
                val es = Elements()
                for (pcInt in indexSet) es.add(elements[pcInt])
                elements = es
            }
            return elements
        }

        private fun findIndexSet(rule: String) {
            val rus = rule.trim { it <= ' ' }
            var len = rus.length
            var curInt: Int?
            var curMinus = false
            val curList = mutableListOf<Int?>()
            var l = ""
            val head = rus.last() == ']'
            if (head) {
                len--
                while (len-- >= 0) {
                    var rl = rus[len]
                    if (rl == ' ') continue
                    if (rl in '0'..'9') l = rl + l
                    else if (rl == '-') curMinus = true
                    else {
                        curInt = if (l.isEmpty()) null else if (curMinus) -l.toInt() else l.toInt()
                        when (rl) {
                            ':' -> curList.add(curInt)
                            else -> {
                                if (curList.isEmpty()) {
                                    if (curInt == null) break
                                    indexes.add(curInt)
                                } else {
                                    indexes.add(Triple(curInt, curList.last(), if (curList.size == 2) curList.first() else 1))
                                    curList.clear()
                                }
                                if (rl == '!') { split = '!'; do { rl = rus[--len] } while (len > 0 && rl == ' ') }
                                if (rl == '[') { beforeRule = rus.substring(0, len); return }
                                if (rl != ',') break
                            }
                        }
                        l = ""; curMinus = false
                    }
                }
            } else while (len-- >= 0) {
                val rl = rus[len]
                if (rl == ' ') continue
                if (rl in '0'..'9') l = rl + l
                else if (rl == '-') curMinus = true
                else {
                    if (rl == '!' || rl == '.' || rl == ':') {
                        indexDefault.add(if (curMinus) -l.toInt() else l.toInt())
                        if (rl != ':') { split = rl; beforeRule = rus.substring(0, len); return }
                    } else break
                    l = ""; curMinus = false
                }
            }
            split = ' '; beforeRule = rus
        }
    }

    internal inner class SourceRule(ruleStr: String) {
        var isCss = false
        var elementsRule: String = if (ruleStr.startsWith("@CSS:", true)) {
            isCss = true; ruleStr.substring(5).trim { it <= ' ' }
        } else ruleStr
    }

    private fun looksLikeJsonPath(rule: String): Boolean {
        val trimmed = rule
            .trimStart('\uFEFF', '\u200B', '\u200C', '\u200D')
            .trim()
            .removePrefix("@Json:")
            .removePrefix("@json:")
            .trim()
        return trimmed.startsWith("$.") || trimmed.startsWith("$[")
    }
}
