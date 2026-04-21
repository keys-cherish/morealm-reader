package com.morealm.app.domain.analyzeRule

import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.entity.BookSource
import org.apache.commons.text.StringEscapeUtils
import org.jsoup.nodes.Node
import java.net.URL
import java.util.regex.Pattern
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * 解析规则获取结果
 * 支持 JSoup/CSS、XPath、JSONPath、JS、Regex 五种解析模式
 */
@Suppress("unused", "RegExpRedundantEscape", "MemberVisibilityCanBePrivate")
class AnalyzeRule(
    private var ruleData: RuleDataInterface? = null,
    private val source: BookSource? = null,
) {

    private var content: Any? = null
    private var baseUrl: String? = null
    private var redirectUrl: URL? = null
    private var isJSON: Boolean = false
    private var isRegex: Boolean = false

    private var analyzeByXPath: AnalyzeByXPath? = null
    private var analyzeByJSoup: AnalyzeByJSoup? = null
    private var analyzeByJSonPath: AnalyzeByJSonPath? = null

    private val stringRuleCache = hashMapOf<String, List<SourceRule>>()
    private val regexCache = hashMapOf<String, Regex?>()

    private var coroutineContext: CoroutineContext = EmptyCoroutineContext

    @JvmOverloads
    fun setContent(content: Any?, baseUrl: String? = null): AnalyzeRule {
        if (content == null) throw AssertionError("Content cannot be null")
        this.content = content
        isJSON = when (content) {
            is Node -> false
            else -> {
                val s = content.toString().trimStart()
                s.startsWith("{") || s.startsWith("[")
            }
        }
        setBaseUrl(baseUrl)
        analyzeByXPath = null
        analyzeByJSoup = null
        analyzeByJSonPath = null
        return this
    }

    fun setBaseUrl(baseUrl: String?): AnalyzeRule {
        baseUrl?.let { this.baseUrl = it }
        return this
    }

    fun setRedirectUrl(url: String): URL? {
        try { redirectUrl = URL(url) } catch (e: Exception) {
            AppLog.warn("AnalyzeRule", "URL($url) error: ${e.localizedMessage}")
        }
        return redirectUrl
    }
    private fun getAnalyzeByXPath(o: Any): AnalyzeByXPath {
        return if (o != content) AnalyzeByXPath(o)
        else { if (analyzeByXPath == null) analyzeByXPath = AnalyzeByXPath(content!!); analyzeByXPath!! }
    }

    private fun getAnalyzeByJSoup(o: Any): AnalyzeByJSoup {
        return if (o != content) AnalyzeByJSoup(o)
        else { if (analyzeByJSoup == null) analyzeByJSoup = AnalyzeByJSoup(content!!); analyzeByJSoup!! }
    }

    private fun getAnalyzeByJSonPath(o: Any): AnalyzeByJSonPath {
        return if (o != content) AnalyzeByJSonPath(o)
        else { if (analyzeByJSonPath == null) analyzeByJSonPath = AnalyzeByJSonPath(content!!); analyzeByJSonPath!! }
    }

    // ── 获取文本列表 ──

    @JvmOverloads
    fun getStringList(rule: String?, mContent: Any? = null, isUrl: Boolean = false): List<String>? {
        if (rule.isNullOrEmpty()) return null
        val ruleList = splitSourceRuleCacheString(rule)
        return getStringList(ruleList, mContent, isUrl)
    }

    @JvmOverloads
    fun getStringList(
        ruleList: List<SourceRule>, mContent: Any? = null, isUrl: Boolean = false
    ): List<String>? {
        var result: Any? = null
        val content = mContent ?: this.content
        if (content != null && ruleList.isNotEmpty()) {
            result = content
            for (sourceRule in ruleList) {
                putRule(sourceRule.putMap)
                sourceRule.makeUpRule(result)
                result ?: continue
                val rule = sourceRule.rule
                if (rule.isNotEmpty()) {
                    result = when (sourceRule.mode) {
                        Mode.Js -> rule // JS not supported yet, return rule as-is
                        Mode.Json -> getAnalyzeByJSonPath(result).getStringList(rule)
                        Mode.XPath -> getAnalyzeByXPath(result).getStringList(rule)
                        Mode.Default -> getAnalyzeByJSoup(result).getStringList(rule)
                        else -> rule
                    }
                }
                if (sourceRule.replaceRegex.isNotEmpty() && result is List<*>) {
                    val newList = ArrayList<String>()
                    for (item in result) newList.add(replaceRegex(item.toString(), sourceRule))
                    result = newList
                } else if (sourceRule.replaceRegex.isNotEmpty()) {
                    result = replaceRegex(result.toString(), sourceRule)
                }
            }
        }
        if (result == null) return null
        if (result is String) result = result.split("\n")
        if (isUrl) {
            val urlList = ArrayList<String>()
            if (result is List<*>) {
                for (url in result) {
                    val absoluteURL = getAbsoluteURL(redirectUrl, url.toString())
                    if (absoluteURL.isNotEmpty() && !urlList.contains(absoluteURL)) urlList.add(absoluteURL)
                }
            }
            return urlList
        }
        @Suppress("UNCHECKED_CAST")
        return result as? List<String>
    }

    // ── 获取文本 ──

    @JvmOverloads
    fun getString(ruleStr: String?, mContent: Any? = null, isUrl: Boolean = false): String {
        if (ruleStr.isNullOrEmpty()) return ""
        val ruleList = splitSourceRuleCacheString(ruleStr)
        return getString(ruleList, mContent, isUrl)
    }

    fun getString(ruleStr: String?, unescape: Boolean): String {
        if (ruleStr.isNullOrEmpty()) return ""
        val ruleList = splitSourceRuleCacheString(ruleStr)
        return getString(ruleList, unescape = unescape)
    }

    @JvmOverloads
    fun getString(
        ruleList: List<SourceRule>, mContent: Any? = null, isUrl: Boolean = false, unescape: Boolean = true
    ): String {
        var result: Any? = null
        val content = mContent ?: this.content
        if (content != null && ruleList.isNotEmpty()) {
            result = content
            for (sourceRule in ruleList) {
                putRule(sourceRule.putMap)
                sourceRule.makeUpRule(result)
                result ?: continue
                val rule = sourceRule.rule
                if (rule.isNotBlank() || sourceRule.replaceRegex.isEmpty()) {
                    result = when (sourceRule.mode) {
                        Mode.Js -> rule // JS not supported yet
                        Mode.Json -> getAnalyzeByJSonPath(result).getString(rule)
                        Mode.XPath -> getAnalyzeByXPath(result).getString(rule)
                        Mode.Default -> if (isUrl) {
                            getAnalyzeByJSoup(result).getString0(rule)
                        } else {
                            getAnalyzeByJSoup(result).getString(rule)
                        }
                        else -> rule
                    }
                }
                if (result != null && sourceRule.replaceRegex.isNotEmpty()) {
                    result = replaceRegex(result.toString(), sourceRule)
                }
            }
        }
        if (result == null) result = ""
        val resultStr = result.toString()
        val str = if (unescape && resultStr.indexOf('&') > -1) {
            StringEscapeUtils.unescapeHtml4(resultStr)
        } else resultStr
        if (isUrl) {
            return if (str.isBlank()) baseUrl ?: "" else getAbsoluteURL(redirectUrl, str)
        }
        return str
    }
    // ── 获取Element ──

    fun getElement(ruleStr: String): Any? {
        if (ruleStr.isEmpty()) return null
        var result: Any? = null
        val content = this.content
        val ruleList = splitSourceRule(ruleStr, true)
        if (content != null && ruleList.isNotEmpty()) {
            result = content
            for (sourceRule in ruleList) {
                putRule(sourceRule.putMap)
                sourceRule.makeUpRule(result)
                result ?: continue
                val rule = sourceRule.rule
                result = when (sourceRule.mode) {
                    Mode.Regex -> AnalyzeByRegex.getElement(result.toString(), rule.split("&&").filter { it.isNotBlank() }.toTypedArray())
                    Mode.Js -> rule
                    Mode.Json -> getAnalyzeByJSonPath(result).getObject(rule)
                    Mode.XPath -> getAnalyzeByXPath(result).getElements(rule)
                    else -> getAnalyzeByJSoup(result).getElements(rule)
                }
                if (sourceRule.replaceRegex.isNotEmpty()) {
                    result = replaceRegex(result.toString(), sourceRule)
                }
            }
        }
        return result
    }

    // ── 获取列表 ──

    @Suppress("UNCHECKED_CAST")
    fun getElements(ruleStr: String): List<Any> {
        var result: Any? = null
        val content = this.content
        val ruleList = splitSourceRule(ruleStr, true)
        if (content != null && ruleList.isNotEmpty()) {
            result = content
            for (sourceRule in ruleList) {
                putRule(sourceRule.putMap)
                result ?: continue
                val rule = sourceRule.rule
                result = when (sourceRule.mode) {
                    Mode.Regex -> AnalyzeByRegex.getElements(result.toString(), rule.split("&&").filter { it.isNotBlank() }.toTypedArray())
                    Mode.Js -> rule
                    Mode.Json -> getAnalyzeByJSonPath(result).getList(rule)
                    Mode.XPath -> getAnalyzeByXPath(result).getElements(rule)
                    else -> getAnalyzeByJSoup(result).getElements(rule)
                }
            }
        }
        result?.let { return it as List<Any> }
        return ArrayList()
    }

    // ── 变量存取 ──

    private fun putRule(map: Map<String, String>) {
        for ((key, value) in map) put(key, getString(value))
    }

    fun put(key: String, value: String): String {
        ruleData?.putVariable(key, value) ?: source?.let { /* no-op for now */ }
        return value
    }

    fun get(key: String): String {
        return ruleData?.getVariable(key)?.takeIf { it.isNotEmpty() } ?: ""
    }

    // ── 正则替换 ──

    private fun replaceRegex(result: String, rule: SourceRule): String {
        if (rule.replaceRegex.isEmpty()) return result
        val replaceRegex = rule.replaceRegex
        val replacement = rule.replacement
        val regex = compileRegexCache(replaceRegex)
        if (rule.replaceFirst) {
            if (regex != null) kotlin.runCatching {
                val pattern = regex.toPattern()
                val matcher = pattern.matcher(result)
                return if (matcher.find()) matcher.group(0)!!.replaceFirst(regex, replacement) else ""
            }
            return replacement
        } else {
            if (regex != null) kotlin.runCatching { return result.replace(regex, replacement) }
            return result.replace(replaceRegex, replacement)
        }
    }

    private fun compileRegexCache(regex: String): Regex? {
        return regexCache.getOrPut(regex) {
            try { regex.toRegex() } catch (_: Exception) { null }
        }
    }
    // ── 分离put规则 ──

    private fun splitPutRule(ruleStr: String, putMap: HashMap<String, String>): String {
        var vRuleStr = ruleStr
        val putMatcher = putPattern.matcher(vRuleStr)
        while (putMatcher.find()) {
            vRuleStr = vRuleStr.replace(putMatcher.group(), "")
            try {
                val putJsonStr = putMatcher.group(1)
                val map = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
                    .decodeFromString<Map<String, String>>(putJsonStr)
                putMap.putAll(map)
            } catch (_: Exception) {}
        }
        return vRuleStr
    }

    // ── 规则缓存 ──

    private fun splitSourceRuleCacheString(ruleStr: String?): List<SourceRule> {
        if (ruleStr.isNullOrEmpty()) return emptyList()
        return stringRuleCache.getOrPut(ruleStr) { splitSourceRule(ruleStr) }
    }

    // ── 分解规则生成规则列表 ──

    fun splitSourceRule(ruleStr: String?, allInOne: Boolean = false): List<SourceRule> {
        if (ruleStr.isNullOrEmpty()) return emptyList()
        val ruleList = ArrayList<SourceRule>()
        var mMode: Mode = Mode.Default
        var start = 0
        if (allInOne && ruleStr.startsWith(":")) {
            mMode = Mode.Regex; isRegex = true; start = 1
        } else if (isRegex) {
            mMode = Mode.Regex
        }
        var tmp: String
        val jsMatcher = JS_PATTERN.matcher(ruleStr)
        while (jsMatcher.find()) {
            if (jsMatcher.start() > start) {
                tmp = ruleStr.substring(start, jsMatcher.start()).trim { it <= ' ' }
                if (tmp.isNotEmpty()) ruleList.add(SourceRule(tmp, mMode))
            }
            ruleList.add(SourceRule(jsMatcher.group(2) ?: jsMatcher.group(1), Mode.Js))
            start = jsMatcher.end()
        }
        if (ruleStr.length > start) {
            tmp = ruleStr.substring(start).trim { it <= ' ' }
            if (tmp.isNotEmpty()) ruleList.add(SourceRule(tmp, mMode))
        }
        return ruleList
    }

    // ── URL 工具 ──

    companion object {
        private val putPattern = Pattern.compile("@put:(\\{[^}]+?\\})", Pattern.CASE_INSENSITIVE)
        private val evalPattern = Pattern.compile("@get:\\{[^}]+?\\}|\\{\\{[\\w\\W]*?\\}\\}", Pattern.CASE_INSENSITIVE)
        private val regexPattern = Pattern.compile("\\$\\d{1,2}")
        val JS_PATTERN: Pattern = Pattern.compile("@js:[\\s\\S]*$|<js>[\\s\\S]*?</js>", Pattern.CASE_INSENSITIVE)

        fun getAbsoluteURL(baseUrl: URL?, relativePath: String): String {
            if (relativePath.isBlank()) return ""
            if (relativePath.startsWith("http://") || relativePath.startsWith("https://")) return relativePath
            if (relativePath.startsWith("//")) return "https:$relativePath"
            val base = baseUrl ?: return relativePath
            return try { URL(base, relativePath).toString() } catch (_: Exception) { relativePath }
        }

        fun getAbsoluteURL(baseUrl: String?, relativePath: String): String {
            if (relativePath.isBlank()) return ""
            if (relativePath.startsWith("http://") || relativePath.startsWith("https://")) return relativePath
            if (relativePath.startsWith("//")) return "https:$relativePath"
            if (baseUrl.isNullOrBlank()) return relativePath
            return try { URL(URL(baseUrl), relativePath).toString() } catch (_: Exception) { relativePath }
        }

        fun AnalyzeRule.setCoroutineContext(context: CoroutineContext): AnalyzeRule {
            coroutineContext = context; return this
        }

        fun AnalyzeRule.setRuleData(ruleData: RuleDataInterface?): AnalyzeRule {
            this.ruleData = ruleData; return this
        }
    }
    // ── 规则类 ──

    inner class SourceRule internal constructor(
        ruleStr: String,
        internal var mode: Mode = Mode.Default
    ) {
        internal var rule: String
        internal var replaceRegex = ""
        internal var replacement = ""
        internal var replaceFirst = false
        internal val putMap = HashMap<String, String>()
        private val ruleParam = ArrayList<String>()
        private val ruleType = ArrayList<Int>()
        private val getRuleType = -2
        private val jsRuleType = -1
        private val defaultRuleType = 0

        init {
            rule = when {
                mode == Mode.Js || mode == Mode.Regex -> ruleStr
                ruleStr.startsWith("@CSS:", true) -> { mode = Mode.Default; ruleStr }
                ruleStr.startsWith("@@") -> { mode = Mode.Default; ruleStr.substring(2) }
                ruleStr.startsWith("@XPath:", true) -> { mode = Mode.XPath; ruleStr.substring(7) }
                ruleStr.startsWith("@Json:", true) -> { mode = Mode.Json; ruleStr.substring(6) }
                isJSON || ruleStr.startsWith("$.") || ruleStr.startsWith("$[") -> { mode = Mode.Json; ruleStr }
                ruleStr.startsWith("/") -> { mode = Mode.XPath; ruleStr }
                else -> ruleStr
            }
            rule = splitPutRule(rule, putMap)
            var start = 0
            var tmp: String
            val evalMatcher = evalPattern.matcher(rule)
            if (evalMatcher.find()) {
                tmp = rule.substring(start, evalMatcher.start())
                if (mode != Mode.Js && mode != Mode.Regex && (evalMatcher.start() == 0 || !tmp.contains("##"))) {
                    mode = Mode.Regex
                }
                do {
                    if (evalMatcher.start() > start) { tmp = rule.substring(start, evalMatcher.start()); splitRegex(tmp) }
                    tmp = evalMatcher.group()
                    when {
                        tmp.startsWith("@get:", true) -> { ruleType.add(getRuleType); ruleParam.add(tmp.substring(6, tmp.lastIndex)) }
                        tmp.startsWith("{{") -> { ruleType.add(jsRuleType); ruleParam.add(tmp.substring(2, tmp.length - 2)) }
                        else -> splitRegex(tmp)
                    }
                    start = evalMatcher.end()
                } while (evalMatcher.find())
            }
            if (rule.length > start) { tmp = rule.substring(start); splitRegex(tmp) }
        }

        private fun splitRegex(ruleStr: String) {
            var start = 0; var tmp: String
            val ruleStrArray = ruleStr.split("##")
            val regexMatcher = regexPattern.matcher(ruleStrArray[0])
            if (regexMatcher.find()) {
                if (mode != Mode.Js && mode != Mode.Regex) mode = Mode.Regex
                do {
                    if (regexMatcher.start() > start) { tmp = ruleStr.substring(start, regexMatcher.start()); ruleType.add(defaultRuleType); ruleParam.add(tmp) }
                    tmp = regexMatcher.group(); ruleType.add(tmp.substring(1).toInt()); ruleParam.add(tmp)
                    start = regexMatcher.end()
                } while (regexMatcher.find())
            }
            if (ruleStr.length > start) { tmp = ruleStr.substring(start); ruleType.add(defaultRuleType); ruleParam.add(tmp) }
        }

        fun makeUpRule(result: Any?) {
            val infoVal = StringBuilder()
            if (ruleParam.isNotEmpty()) {
                var index = ruleParam.size
                while (index-- > 0) {
                    val regType = ruleType[index]
                    when {
                        regType > defaultRuleType -> {
                            @Suppress("UNCHECKED_CAST")
                            (result as? List<String?>)?.run {
                                if (this.size > regType) this[regType]?.let { infoVal.insert(0, it) }
                            } ?: infoVal.insert(0, ruleParam[index])
                        }
                        regType == jsRuleType -> {
                            // JS evaluation - for now just insert the param
                            infoVal.insert(0, ruleParam[index])
                        }
                        regType == getRuleType -> infoVal.insert(0, get(ruleParam[index]))
                        else -> infoVal.insert(0, ruleParam[index])
                    }
                }
                rule = infoVal.toString()
            }
            val ruleStrS = rule.split("##")
            rule = ruleStrS[0].trim()
            if (ruleStrS.size > 1) replaceRegex = ruleStrS[1]
            if (ruleStrS.size > 2) replacement = ruleStrS[2]
            if (ruleStrS.size > 3) replaceFirst = true
        }

        fun getParamSize(): Int = ruleParam.size
    }

    enum class Mode {
        XPath, Json, Default, Js, Regex
    }
}
