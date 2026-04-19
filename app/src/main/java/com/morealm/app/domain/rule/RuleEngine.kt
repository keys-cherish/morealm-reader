package com.morealm.app.domain.rule

import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.ReadContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import org.seimicrawler.xpath.JXDocument
import org.seimicrawler.xpath.JXNode

/**
 * MoRealm rule engine — parses content using CSS, XPath, JSONPath, or Regex rules.
 *
 * Compatible with Legado rule syntax:
 * - No prefix or @css: → JSoup CSS selector
 * - @XPath: or starts with / → XPath
 * - @Json: or starts with $. → JSONPath
 * - ##regex##replacement → post-process with regex
 * - rule1 && rule2 → concat results
 * - rule1 || rule2 → first non-empty result
 */
class RuleEngine {

    private var htmlDoc: Document? = null
    private var htmlContent: Element? = null
    private var jsonContent: ReadContext? = null
    private var rawContent: String = ""
    private var baseUrl: String = ""

    fun setContent(content: String, url: String = "") {
        rawContent = content
        baseUrl = url
        htmlContent = null
        jsonContent = null
        // Auto-detect content type
        val trimmed = content.trimStart()
        when {
            trimmed.startsWith("{") || trimmed.startsWith("[") -> {
                jsonContent = try { JsonPath.parse(content) } catch (_: Exception) { null }
            }
            else -> {
                htmlDoc = try {
                    if (url.isNotEmpty()) Jsoup.parse(content, url) else Jsoup.parse(content)
                } catch (_: Exception) { null }
                htmlContent = htmlDoc?.body() ?: htmlDoc
            }
        }
    }

    // ── Public API ──

    /** Get a single string from a rule */
    fun getString(rule: String): String {
        if (rule.isBlank()) return ""
        // Handle && (concat) and || (first match)
        if (rule.contains("&&")) return rule.split("&&").joinToString("") { getString(it.trim()) }
        if (rule.contains("||")) return rule.split("||").firstNotNullOfOrNull { getString(it.trim()).takeIf { s -> s.isNotBlank() } } ?: ""

        val (actualRule, regexReplace) = splitRegexSuffix(rule)
        val result = getStringRaw(actualRule)
        return applyRegexReplace(result, regexReplace)
    }

    /** Get a list of strings from a rule */
    fun getStringList(rule: String): List<String> {
        if (rule.isBlank()) return emptyList()
        val (actualRule, regexReplace) = splitRegexSuffix(rule)
        val results = getStringListRaw(actualRule)
        return if (regexReplace != null) results.map { applyRegexReplace(it, regexReplace) } else results
    }

    /** Get elements (for iteration, e.g., book list) */
    fun getElements(rule: String): List<Any> {
        if (rule.isBlank()) return emptyList()
        val (mode, cleanRule) = detectMode(rule)
        return when (mode) {
            Mode.CSS -> htmlContent?.select(cleanRule)?.toList() ?: emptyList()
            Mode.XPATH -> try {
                val doc = htmlDoc ?: return emptyList()
                JXDocument.create(doc).selN(cleanRule)
            } catch (_: Exception) { emptyList() }
            Mode.JSON -> try {
                val result = jsonContent?.read<Any>(cleanRule)
                when (result) {
                    is List<*> -> result.filterNotNull()
                    else -> listOfNotNull(result)
                }
            } catch (_: Exception) { emptyList() }
            Mode.REGEX -> emptyList()
        }
    }

    /** Parse a child element (for iterating search results) */
    fun createChild(element: Any): RuleEngine {
        val child = RuleEngine()
        child.baseUrl = baseUrl
        when (element) {
            is Element -> {
                child.htmlContent = element
                child.htmlDoc = if (element is Document) element else Jsoup.parse(element.outerHtml())
                child.rawContent = element.outerHtml()
            }
            is JXNode -> {
                child.rawContent = element.toString()
                try {
                    val el = element.asElement()
                    child.htmlContent = el
                    child.htmlDoc = Jsoup.parse(el.outerHtml())
                } catch (_: Exception) {}
            }
            is Map<*, *> -> {
                val json = kotlinx.serialization.json.Json.encodeToString(
                    kotlinx.serialization.json.JsonObject.serializer(),
                    kotlinx.serialization.json.buildJsonObject {
                        element.forEach { (k, v) -> put(k.toString(), kotlinx.serialization.json.JsonPrimitive(v?.toString())) }
                    }
                )
                child.rawContent = json
                child.jsonContent = try { JsonPath.parse(json) } catch (_: Exception) { null }
            }
            is String -> child.setContent(element, baseUrl)
            else -> child.setContent(element.toString(), baseUrl)
        }
        return child
    }

    // ── Internal ──

    private enum class Mode { CSS, XPATH, JSON, REGEX }

    private fun detectMode(rule: String): Pair<Mode, String> = when {
        rule.startsWith("@XPath:", ignoreCase = true) -> Mode.XPATH to rule.substringAfter(":")
        rule.startsWith("@xpath:", ignoreCase = true) -> Mode.XPATH to rule.substringAfter(":")
        rule.startsWith("//") || rule.startsWith("/") -> Mode.XPATH to rule
        rule.startsWith("@Json:", ignoreCase = true) -> Mode.JSON to rule.substringAfter(":")
        rule.startsWith("@json:", ignoreCase = true) -> Mode.JSON to rule.substringAfter(":")
        rule.startsWith("$.") || rule.startsWith("$[") -> Mode.JSON to rule
        rule.startsWith("@css:", ignoreCase = true) -> Mode.CSS to rule.substringAfter(":")
        rule.startsWith("@CSS:", ignoreCase = true) -> Mode.CSS to rule.substringAfter(":")
        else -> Mode.CSS to rule
    }

    private fun getStringRaw(rule: String): String {
        val (mode, cleanRule) = detectMode(rule)
        return when (mode) {
            Mode.CSS -> evalCss(cleanRule)
            Mode.XPATH -> evalXPath(cleanRule)
            Mode.JSON -> evalJsonPath(cleanRule)
            Mode.REGEX -> evalRegex(cleanRule)
        }
    }

    private fun getStringListRaw(rule: String): List<String> {
        val (mode, cleanRule) = detectMode(rule)
        return when (mode) {
            Mode.CSS -> htmlContent?.select(cleanRule)?.map { it.text() } ?: emptyList()
            Mode.XPATH -> try {
                val doc = htmlDoc ?: return emptyList()
                JXDocument.create(doc).selN(cleanRule).map { it.toString() }
            } catch (_: Exception) { emptyList() }
            Mode.JSON -> try {
                val result = jsonContent?.read<Any>(cleanRule)
                when (result) {
                    is List<*> -> result.map { it.toString() }
                    else -> listOfNotNull(result?.toString())
                }
            } catch (_: Exception) { emptyList() }
            Mode.REGEX -> Regex(cleanRule).findAll(rawContent).map { it.value }.toList()
        }
    }

    private fun evalCss(rule: String): String {
        val el = htmlContent ?: return ""
        // Handle Legado's @text, @href, @src suffixes
        val atIdx = rule.lastIndexOf('@')
        if (atIdx > 0) {
            val selector = rule.substring(0, atIdx).trim()
            val attr = rule.substring(atIdx + 1).trim()
            val selected = if (selector.isEmpty()) el else el.select(selector).first() ?: return ""
            return when (attr.lowercase()) {
                "text" -> selected.text()
                "html", "innerhtml" -> selected.html()
                "outerhtml" -> selected.outerHtml()
                "textNodes" -> selected.textNodes().joinToString("\n") { it.text() }
                "owntext" -> selected.ownText()
                else -> selected.attr(attr)
            }
        }
        return el.select(rule).text()
    }

    private fun evalXPath(rule: String): String {
        val doc = htmlDoc ?: return ""
        return try {
            JXDocument.create(doc).selOne(rule)?.toString() ?: ""
        } catch (_: Exception) { "" }
    }

    private fun evalJsonPath(rule: String): String {
        val ctx = jsonContent ?: return ""
        return try {
            val result = ctx.read<Any>(rule)
            when (result) {
                is List<*> -> result.firstOrNull()?.toString() ?: ""
                else -> result?.toString() ?: ""
            }
        } catch (_: Exception) { "" }
    }

    private fun evalRegex(rule: String): String {
        return try { Regex(rule).find(rawContent)?.value ?: "" } catch (_: Exception) { "" }
    }

    /** Split rule##regex##replacement suffix */
    private fun splitRegexSuffix(rule: String): Pair<String, RegexReplace?> {
        val parts = rule.split("##")
        if (parts.size < 3) return rule to null
        return parts[0] to RegexReplace(parts[1], parts[2], parts.getOrNull(3) == "1")
    }

    private data class RegexReplace(val pattern: String, val replacement: String, val firstOnly: Boolean = false)

    private fun applyRegexReplace(input: String, rr: RegexReplace?): String {
        if (rr == null) return input
        return try {
            val regex = Regex(rr.pattern)
            if (rr.firstOnly) regex.replaceFirst(input, rr.replacement)
            else regex.replace(input, rr.replacement)
        } catch (_: Exception) { input }
    }
}
