package com.morealm.app.domain.source

import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.entity.BookSource
import com.morealm.app.domain.entity.rule.BookInfoRule
import com.morealm.app.domain.entity.rule.ContentRule
import com.morealm.app.domain.entity.rule.ExploreRule
import com.morealm.app.domain.entity.rule.SearchRule
import com.morealm.app.domain.entity.rule.TocRule
import com.script.rhino.RhinoScriptEngine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Undefined

/**
 * 书源导入器 - 解析 JSON 格式书源并转换为 [BookSource] 实体。
 *
 * 接受的形态（按优先级）：
 *  1. 顶层 `JsonArray`：标准 Legado / Yuedu 多书源数组。
 *  2. 顶层 `JsonObject` 包含 `bookSourceUrl` 字段：单书源对象。
 *  3. 顶层 `JsonObject` 内含 `sources` / `bookSources` / `data` /
 *     `list` / `items` 等常见包装键，其值为 `JsonArray`：解包后递归。
 *
 * 之前的实现把所有解析异常 `catch (_: Exception)` 吞掉，用户得到「未识别
 * 到有效书源」就再也定位不到原因。现在每一层失败都打 [AppLog] 并把首条
 * 异常通过 [lastImportError] 暴露给 UI，让用户至少能看到「期望 String 但
 * 拿到 Number」一类的具体提示。
 */
object BookSourceImporter {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true  // 把不合法的非空值降级为字段默认值，避免整段崩
    }

    /**
     * 最近一次 [importFromJson] 调用时记录到的首个解析错误的 message。
     * UI 在解析返回空列表时可以读这里给用户友好提示。线程安全性：所有
     * 写入路径都在同一个调用链内串行完成，不需要原子化。
     */
    @Volatile
    var lastImportError: String? = null
        private set

    /** 常见包装键名 — 按出现频率从高到低排。 */
    private val WRAPPER_KEYS = listOf(
        "sources", "bookSources", "bookSource",
        "data", "list", "items", "result", "results",
    )

    fun importFromJson(jsonString: String): List<BookSource> {
        lastImportError = null
        val raw = jsonString.trim().removePrefix("\uFEFF")  // 去掉 BOM
        if (raw.isEmpty()) {
            lastImportError = "输入为空"
            return emptyList()
        }

        // 检测是否为 JS 书源文件（非 JSON 格式）
        if (isJsSource(raw)) {
            return importFromJs(raw)
        }

        // 先用低层 JsonElement 解析，方便分辨数组 / 对象 / 包装。
        val element = try {
            json.parseToJsonElement(raw)
        } catch (e: Exception) {
            val msg = "JSON 语法错误: ${e.message}"
            AppLog.warn("SourceImport", msg)
            lastImportError = msg
            return emptyList()
        }

        return decodeElement(element)
    }

    /**
     * 判断输入是否为 JS 书源文件。
     * JS 书源特征：不以 `[` 或 `{` 开头（排除 JSON），
     * 包含 JS 关键字如 var/let/const/function 声明。
     */
    private fun isJsSource(raw: String): Boolean {
        val firstChar = raw.firstOrNull() ?: return false
        if (firstChar == '[' || firstChar == '{') return false
        // 常见 JS 书源开头模式
        val jsPatterns = arrayOf("var ", "let ", "const ", "function ", "//", "/*")
        val firstLine = raw.lineSequence().firstOrNull { it.isNotBlank() } ?: return false
        return jsPatterns.any { firstLine.trimStart().startsWith(it) }
    }

    /** JS 书源文件中常见的结果变量名。 */
    private val JS_RESULT_VARS = arrayOf(
        "bookSources", "sources", "bookSource", "result", "data",
    )

    /**
     * 执行 JS 书源文件，期望返回值为 JSON 字符串或 JS 数组/对象。
     * JS 文件最终需要产出一个书源数组（或单个书源对象），
     * 通常通过最后一个表达式返回，或赋值给约定变量 `bookSources` / `result`。
     */
    private fun importFromJs(jsCode: String): List<BookSource> {
        AppLog.info("SourceImport", "检测到 JS 书源文件，执行中...")

        // 先尝试直接执行，取最后一个表达式的值
        val directResult = try {
            RhinoScriptEngine.eval(jsCode)
        } catch (e: Exception) {
            val msg = "JS 执行失败: ${e.message}"
            AppLog.warn("SourceImport", msg)
            lastImportError = msg
            return emptyList()
        }

        // 优先用直接返回值
        var jsonStr = jsResultToJson(directResult)

        // 如果直接返回值无效，尝试读取约定变量
        if (jsonStr == null) {
            for (varName in JS_RESULT_VARS) {
                val varResult = try {
                    RhinoScriptEngine.eval("$jsCode;\n$varName")
                } catch (_: Exception) {
                    continue
                }
                jsonStr = jsResultToJson(varResult)
                if (jsonStr != null) {
                    AppLog.info("SourceImport", "从变量 '$varName' 获取到书源数据")
                    break
                }
            }
        }

        if (jsonStr == null) {
            val msg = "JS 执行结果无法转为书源 (返回类型: ${directResult?.javaClass?.simpleName})"
            AppLog.warn("SourceImport", msg)
            lastImportError = msg
            return emptyList()
        }

        val element = try {
            json.parseToJsonElement(jsonStr)
        } catch (e: Exception) {
            val msg = "JS 返回值 JSON 解析失败: ${e.message}"
            AppLog.warn("SourceImport", msg)
            lastImportError = msg
            return emptyList()
        }
        return decodeElement(element)
    }

    /**
     * 将 Rhino JS 执行结果转为 JSON 字符串。
     * 支持：String/ConsString 直接返回、NativeArray/NativeObject 用 JSON.stringify 序列化。
     */
    private fun jsResultToJson(result: Any?): String? {
        if (result == null || result is Undefined) return null
        // 如果 JS 直接返回了字符串（已经是 JSON）
        if (result is CharSequence) {
            val trimmed = result.toString().trim()
            if (trimmed.startsWith("[") || trimmed.startsWith("{")) return trimmed
            return null
        }
        // NativeArray / NativeObject → JSON.stringify
        if (result is NativeArray || result is NativeObject) {
            val cx = Context.enter()
            try {
                val scope = RhinoScriptEngine.topLevel
                val jsonStr = NativeJSON.stringify(cx, scope, result, null, null)
                return jsonStr?.toString()
            } finally {
                Context.exit()
            }
        }
        return null
    }

    /**
     * 递归处理 [JsonElement]：
     *  - JsonArray：尝试整体解码为 `List<ImportBookSource>`，失败时逐项
     *    解码，跳过坏对象但保留好对象。
     *  - JsonObject：含 `bookSourceUrl` 当作单书源；否则在常见包装键里
     *    查 `JsonArray` 解包。
     */
    private fun decodeElement(element: JsonElement): List<BookSource> {
        return when (element) {
            is JsonArray -> decodeArray(element)
            is JsonObject -> decodeObject(element)
            else -> {
                lastImportError = "顶层既不是数组也不是对象，无法识别"
                AppLog.warn("SourceImport", lastImportError!!)
                emptyList()
            }
        }
    }

    private fun decodeArray(arr: JsonArray): List<BookSource> {
        // 优先整体解码，得到精确错误信息。
        try {
            return json.decodeFromJsonElement(
                kotlinx.serialization.builtins.ListSerializer(ImportBookSource.serializer()),
                arr,
            ).map { it.toBookSource() }
        } catch (e: Exception) {
            // 整体失败时退化到逐项解析，跳过坏的，保留好的。
            AppLog.warn("SourceImport", "数组整体解码失败，回退逐项: ${e.message}")
            if (lastImportError == null) lastImportError = "部分书源格式异常: ${e.message}"
        }
        val out = mutableListOf<BookSource>()
        var skipped = 0
        for ((idx, item) in arr.withIndex()) {
            try {
                val src = json.decodeFromJsonElement(ImportBookSource.serializer(), item)
                out.add(src.toBookSource())
            } catch (e: Exception) {
                skipped++
                AppLog.debug("SourceImport", "跳过第 $idx 项: ${e.message}")
            }
        }
        if (skipped > 0) {
            AppLog.info("SourceImport", "导入 ${out.size} 个，跳过 $skipped 个格式不合规")
        }
        return out
    }

    private fun decodeObject(obj: JsonObject): List<BookSource> {
        // 单书源：必须至少有 bookSourceUrl 才认。
        if ("bookSourceUrl" in obj) {
            return try {
                listOf(
                    json.decodeFromJsonElement(ImportBookSource.serializer(), obj).toBookSource(),
                )
            } catch (e: Exception) {
                val msg = "单书源对象解析失败: ${e.message}"
                AppLog.warn("SourceImport", msg)
                lastImportError = msg
                emptyList()
            }
        }
        // 包装：在常见 key 里找数组 / 对象。
        for (key in WRAPPER_KEYS) {
            val nested = obj[key] ?: continue
            AppLog.info("SourceImport", "检测到包装键 \"$key\"，解包")
            return decodeElement(nested)
        }
        val msg = "对象既无 bookSourceUrl，也未找到 ${WRAPPER_KEYS.joinToString("/")}, 等包装键"
        AppLog.warn("SourceImport", msg)
        lastImportError = msg
        return emptyList()
    }
}

/**
 * 导入用的中间数据结构，兼容主流书源JSON格式
 */
@Serializable
data class ImportBookSource(
    val bookSourceUrl: String = "",
    val bookSourceName: String = "",
    val bookSourceGroup: String? = null,
    val bookSourceType: Int = 0,
    val bookSourceComment: String? = null,
    val bookUrlPattern: String? = null,
    val loginUrl: String? = null,
    val loginCheckJs: String? = null,
    val loginUi: String? = null,
    val header: String? = null,
    val enabledCookieJar: Boolean? = null,
    val concurrentRate: String? = null,
    val coverDecodeJs: String? = null,
    val variableComment: String? = null,
    val jsLib: String? = null,
    val searchUrl: String? = null,
    val exploreUrl: String? = null,
    val ruleSearch: ImportSearchRule? = null,
    val ruleExplore: ImportExploreRule? = null,
    val ruleBookInfo: ImportBookInfoRule? = null,
    val ruleToc: ImportTocRule? = null,
    val ruleContent: ImportContentRule? = null,
    val enabled: Boolean = true,
    val enabledExplore: Boolean = true,
    val customOrder: Int = 0,
    val lastUpdateTime: Long = 0,
    val respondTime: Long = 180000L,
    val weight: Int = 0,
) {
    fun toBookSource(): BookSource = BookSource(
        bookSourceUrl = bookSourceUrl,
        bookSourceName = bookSourceName,
        bookSourceGroup = bookSourceGroup,
        bookSourceType = bookSourceType,
        bookUrlPattern = bookUrlPattern,
        customOrder = customOrder,
        enabled = enabled,
        enabledExplore = enabledExplore,
        enabledCookieJar = enabledCookieJar,
        concurrentRate = concurrentRate,
        header = header,
        loginUrl = loginUrl,
        loginUi = loginUi,
        loginCheckJs = loginCheckJs,
        coverDecodeJs = coverDecodeJs,
        bookSourceComment = bookSourceComment,
        variableComment = variableComment,
        jsLib = jsLib,
        lastUpdateTime = lastUpdateTime,
        respondTime = respondTime,
        weight = weight,
        exploreUrl = exploreUrl,
        searchUrl = searchUrl,
        ruleSearch = ruleSearch?.toSearchRule(),
        ruleExplore = ruleExplore?.toExploreRule(),
        ruleBookInfo = ruleBookInfo?.toBookInfoRule(),
        ruleToc = ruleToc?.toTocRule(),
        ruleContent = ruleContent?.toContentRule(),
    )
}

@Serializable
data class ImportSearchRule(
    val checkKeyWord: String? = null,
    val bookList: String? = null,
    val name: String? = null,
    val author: String? = null,
    val intro: String? = null,
    val kind: String? = null,
    val lastChapter: String? = null,
    val updateTime: String? = null,
    val bookUrl: String? = null,
    val coverUrl: String? = null,
    val wordCount: String? = null,
) {
    fun toSearchRule() = SearchRule(checkKeyWord, bookList, name, author, intro, kind, lastChapter, updateTime, bookUrl, coverUrl, wordCount)
}

@Serializable
data class ImportExploreRule(
    val bookList: String? = null,
    val name: String? = null,
    val author: String? = null,
    val intro: String? = null,
    val kind: String? = null,
    val lastChapter: String? = null,
    val updateTime: String? = null,
    val bookUrl: String? = null,
    val coverUrl: String? = null,
    val wordCount: String? = null,
) {
    fun toExploreRule() = ExploreRule(bookList, name, author, intro, kind, lastChapter, updateTime, bookUrl, coverUrl, wordCount)
}

@Serializable
data class ImportBookInfoRule(
    val init: String? = null,
    val name: String? = null,
    val author: String? = null,
    val intro: String? = null,
    val kind: String? = null,
    val lastChapter: String? = null,
    val updateTime: String? = null,
    val coverUrl: String? = null,
    val tocUrl: String? = null,
    val wordCount: String? = null,
    val canReName: String? = null,
    val downloadUrls: String? = null,
) {
    fun toBookInfoRule() = BookInfoRule(init, name, author, intro, kind, lastChapter, updateTime, coverUrl, tocUrl, wordCount, canReName, downloadUrls)
}

@Serializable
data class ImportTocRule(
    val preUpdateJs: String? = null,
    val chapterList: String? = null,
    val chapterName: String? = null,
    val chapterUrl: String? = null,
    val formatJs: String? = null,
    val isVolume: String? = null,
    val isVip: String? = null,
    val isPay: String? = null,
    val updateTime: String? = null,
    val nextTocUrl: String? = null,
) {
    fun toTocRule() = TocRule(preUpdateJs, chapterList, chapterName, chapterUrl, formatJs, isVolume, isVip, isPay, updateTime, nextTocUrl)
}

@Serializable
data class ImportContentRule(
    val content: String? = null,
    val title: String? = null,
    val nextContentUrl: String? = null,
    val webJs: String? = null,
    val sourceRegex: String? = null,
    val replaceRegex: String? = null,
    val imageStyle: String? = null,
    val imageDecode: String? = null,
    val payAction: String? = null,
) {
    fun toContentRule() = ContentRule(content, title, nextContentUrl, webJs, sourceRegex, replaceRegex, imageStyle, imageDecode, payAction)
}
