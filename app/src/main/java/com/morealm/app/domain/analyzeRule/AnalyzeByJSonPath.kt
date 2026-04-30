package com.morealm.app.domain.analyzeRule

import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.ReadContext
import com.morealm.app.core.log.AppLog

/**
 * JSONPath 解析器
 *
 * 防御性设计：
 * - 任何 jsonpath 表达式编译/求值异常都被吞，返回空结果
 * - 自动 trim BOM、zero-width 字符、首尾空白（防止 `&&`/`||` 切分后的子规则带换行）
 * - JSON 解析失败时 fallback 到空文档，避免整个搜索源因单条规则崩溃
 */
@Suppress("RegExpRedundantEscape")
class AnalyzeByJSonPath(json: Any) {

    companion object {
        private const val TAG = "AnalyzeByJSonPath"

        fun parse(json: Any): ReadContext {
            return try {
                when (json) {
                    is ReadContext -> json
                    is String -> JsonPath.parse(json)
                    is Map<*, *> -> JsonPath.parse(json)
                    is List<*> -> JsonPath.parse(json)
                    // Jsoup Element/Elements are HTML — never valid JSON.
                    // When a source mixes @json: rules with HTML content (晋江文学's search
                    // returns HTML but the source has $.novelName style child rules),
                    // JsonPath.parse(Object) tries to cast to String and throws
                    // "org.jsoup.nodes.Element cannot be cast to java.lang.String",
                    // killing the whole rule chain. Fallback to an empty doc and warn —
                    // missing fields degrade gracefully to empty values.
                    is org.jsoup.nodes.Element,
                    is org.jsoup.select.Elements -> {
                        AppLog.warn(
                            TAG,
                            "JSONPath rule received jsoup ${json.javaClass.simpleName}; fallback to empty doc"
                        )
                        JsonPath.parse("{}")
                    }
                    // Last-resort string conversion for primitives etc.
                    else -> JsonPath.parse(json.toString())
                }
            } catch (e: Exception) {
                AppLog.warn(TAG, "JSON parse failed, fallback to empty doc: ${e.message?.take(120)}")
                JsonPath.parse("{}")
            }
        }

        /** 清理规则字符串：去 BOM/zero-width，去首尾空白；防止 `unexpected token` */
        private fun sanitizeRule(rule: String): String =
            rule.trimStart('\uFEFF', '\u200B', '\u200C', '\u200D')
                .trimEnd('\uFEFF', '\u200B', '\u200C', '\u200D')
                .trim()
    }

    private var ctx: ReadContext = parse(json)

    fun getString(rule: String): String? {
        if (rule.isEmpty()) return null
        var result: String
        val ruleAnalyzes = RuleAnalyzer(rule, true)
        val rules = ruleAnalyzes.splitRule("&&", "||")
        if (rules.size == 1) {
            ruleAnalyzes.reSetPos()
            result = ruleAnalyzes.innerRule("{$.") { getString(it) }
            if (result.isEmpty()) {
                try {
                    val cleaned = sanitizeRule(rule)
                    if (cleaned.isEmpty()) return null
                    val ob = ctx.read<Any>(cleaned)
                    result = if (ob is List<*>) ob.joinToString("\n") else ob.toString()
                } catch (e: Exception) {
                    // "No results for path: $.foo" is the JsonPath library signaling
                    // a missing field — happens for almost every search result on every
                    // source because rules cover optional fields (uptime, process, etc).
                    // Don't flood warn-level logs with these; debug-only.
                    val msg = e.message ?: ""
                    if (msg.contains("No results for path", ignoreCase = true)) {
                        AppLog.debug(TAG, "getString('${rule.take(60)}'): missing field")
                    } else {
                        AppLog.warn(TAG, "getString('${rule.take(60)}') failed: ${msg.take(120)}")
                    }
                }
            }
            return result
        } else {
            val textList = arrayListOf<String>()
            for (rl in rules) {
                val temp = getString(sanitizeRule(rl))
                if (!temp.isNullOrEmpty()) {
                    textList.add(temp)
                    if (ruleAnalyzes.elementsType == "||") break
                }
            }
            return textList.joinToString("\n")
        }
    }

    internal fun getStringList(rule: String): List<String> {
        val result = ArrayList<String>()
        if (rule.isEmpty()) return result
        val ruleAnalyzes = RuleAnalyzer(rule, true)
        val rules = ruleAnalyzes.splitRule("&&", "||", "%%")
        if (rules.size == 1) {
            ruleAnalyzes.reSetPos()
            val st = ruleAnalyzes.innerRule("{$.") { getString(it) }
            if (st.isEmpty()) {
                try {
                    val cleaned = sanitizeRule(rule)
                    if (cleaned.isEmpty()) return result
                    val obj = ctx.read<Any>(cleaned)
                    if (obj is List<*>) { for (o in obj) result.add(o.toString()) }
                    else result.add(obj.toString())
                } catch (e: Exception) {
                    val msg = e.message ?: ""
                    if (msg.contains("No results for path", ignoreCase = true)) {
                        AppLog.debug(TAG, "getStringList('${rule.take(60)}'): missing field")
                    } else {
                        AppLog.warn(TAG, "getStringList('${rule.take(60)}') failed: ${msg.take(120)}")
                    }
                }
            } else {
                result.add(st)
            }
            return result
        } else {
            val results = ArrayList<List<String>>()
            for (rl in rules) {
                val temp = getStringList(sanitizeRule(rl))
                if (temp.isNotEmpty()) {
                    results.add(temp)
                    if (temp.isNotEmpty() && ruleAnalyzes.elementsType == "||") break
                }
            }
            if (results.size > 0) {
                if ("%%" == ruleAnalyzes.elementsType) {
                    for (i in results[0].indices) {
                        for (temp in results) { if (i < temp.size) result.add(temp[i]) }
                    }
                } else {
                    for (temp in results) result.addAll(temp)
                }
            }
            return result
        }
    }

    internal fun getObject(rule: String): Any {
        return try {
            val cleaned = sanitizeRule(rule)
            if (cleaned.isEmpty()) return ArrayList<Any>()
            ctx.read(cleaned)
        } catch (e: Exception) {
            AppLog.warn(TAG, "getObject('${rule.take(60)}') failed: ${e.message?.take(120)}")
            ArrayList<Any>()
        }
    }

    internal fun getList(rule: String): ArrayList<Any>? {
        val result = ArrayList<Any>()
        if (rule.isEmpty()) return result
        val ruleAnalyzes = RuleAnalyzer(rule, true)
        val rules = ruleAnalyzes.splitRule("&&", "||", "%%")
        if (rules.size == 1) {
            ctx.let {
                try {
                    val cleaned = sanitizeRule(rules[0])
                    if (cleaned.isEmpty()) return result
                    return it.read<ArrayList<Any>>(cleaned)
                } catch (e: Exception) {
                    AppLog.warn(TAG, "getList('${rule.take(60)}') failed: ${e.message?.take(120)}")
                }
            }
        } else {
            val results = ArrayList<ArrayList<*>>()
            for (rl in rules) {
                val temp = getList(sanitizeRule(rl))
                if (!temp.isNullOrEmpty()) {
                    results.add(temp)
                    if (temp.isNotEmpty() && ruleAnalyzes.elementsType == "||") break
                }
            }
            if (results.size > 0) {
                if ("%%" == ruleAnalyzes.elementsType) {
                    for (i in 0 until results[0].size) {
                        for (temp in results) { if (i < temp.size) temp[i]?.let { result.add(it) } }
                    }
                } else {
                    for (temp in results) result.addAll(temp)
                }
            }
        }
        return result
    }
}
