package com.morealm.app.domain.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.morealm.app.domain.entity.rule.BookInfoRule
import com.morealm.app.domain.entity.rule.ContentRule
import com.morealm.app.domain.entity.rule.ExploreRule
import com.morealm.app.domain.entity.rule.ReviewRule
import com.morealm.app.domain.entity.rule.SearchRule
import com.morealm.app.domain.entity.rule.TocRule
import com.morealm.app.domain.analyzeRule.JsExtensions
import com.morealm.app.domain.http.CacheManager
import com.morealm.app.domain.http.CookieStore
import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

/**
 * 书源实体 - 完整的在线书源数据结构
 * 使用 TypeConverters 将规则子对象序列化为JSON存储
 */
@Serializable
@TypeConverters(BookSource.Converters::class)
@Entity(
    tableName = "book_sources",
    indices = [(Index(value = ["bookSourceUrl"], unique = false))]
)
data class BookSource(
    @PrimaryKey
    var bookSourceUrl: String = "",
    var bookSourceName: String = "",
    var bookSourceGroup: String? = null,
    var bookSourceType: Int = 0, // 0=text, 1=audio, 2=image, 3=file
    var bookUrlPattern: String? = null,
    @ColumnInfo(defaultValue = "0")
    var customOrder: Int = 0,
    @ColumnInfo(defaultValue = "1")
    var enabled: Boolean = true,
    @ColumnInfo(defaultValue = "1")
    var enabledExplore: Boolean = true,
    var enabledCookieJar: Boolean? = null,
    var concurrentRate: String? = null,
    var header: String? = null,
    var loginUrl: String? = null,
    var loginUi: String? = null,
    var loginCheckJs: String? = null,
    var coverDecodeJs: String? = null,
    var bookSourceComment: String? = null,
    var variableComment: String? = null,
    var jsLib: String? = null,
    var lastUpdateTime: Long = 0,
    var respondTime: Long = 180000L,
    var weight: Int = 0,
    var exploreUrl: String? = null,
    var searchUrl: String? = null,
    var ruleExplore: ExploreRule? = null,
    var ruleSearch: SearchRule? = null,
    var ruleBookInfo: BookInfoRule? = null,
    var ruleToc: TocRule? = null,
    var ruleContent: ContentRule? = null,
    var ruleReview: ReviewRule? = null,
) {
    override fun hashCode(): Int = bookSourceUrl.hashCode()

    override fun equals(other: Any?): Boolean =
        if (other is BookSource) other.bookSourceUrl == bookSourceUrl else false

    fun getKey(): String = bookSourceUrl

    fun getSource(): BookSource = this

    fun getSearchRule(): SearchRule {
        ruleSearch?.let { return it }
        val rule = SearchRule(); ruleSearch = rule; return rule
    }

    fun getExploreRule(): ExploreRule {
        ruleExplore?.let { return it }
        val rule = ExploreRule(); ruleExplore = rule; return rule
    }

    fun getBookInfoRule(): BookInfoRule {
        ruleBookInfo?.let { return it }
        val rule = BookInfoRule(); ruleBookInfo = rule; return rule
    }

    fun getTocRule(): TocRule {
        ruleToc?.let { return it }
        val rule = TocRule(); ruleToc = rule; return rule
    }

    fun getContentRule(): ContentRule {
        ruleContent?.let { return it }
        val rule = ContentRule(); ruleContent = rule; return rule
    }

    fun getDisplayNameGroup(): String {
        return if (bookSourceGroup.isNullOrBlank()) bookSourceName
        else String.format("%s (%s)", bookSourceName, bookSourceGroup)
    }

    // ── 登录体系 ──

    fun getLoginJs(): String? {
        val loginJs = loginUrl
        return when {
            loginJs == null -> null
            loginJs.startsWith("@js:") -> loginJs.substring(4)
            loginJs.startsWith("<js>") -> loginJs.substring(4, loginJs.lastIndexOf("<"))
            else -> loginJs
        }
    }

    /**
     * 判断 loginUrl 是否为纯 URL（非JS脚本）
     */
    fun isLoginUrlPureUrl(): Boolean {
        val url = loginUrl ?: return false
        return !url.startsWith("@js:") && !url.startsWith("<js>")
    }

    /**
     * 执行登录脚本，传入用户填写的表单数据
     */
    fun login(loginData: Map<String, String> = emptyMap()) {
        val loginJs = getLoginJs()
        if (!loginJs.isNullOrBlank()) {
            val js = """$loginJs
                if(typeof login=='function'){
                    login.apply(this);
                } else {
                    throw('Function login not implements!!!')
                }
            """.trimIndent()
            evalJS(js) { bindings ->
                bindings["result"] = loginData.toMutableMap()
            }
        }
    }

    /**
     * 获取登录头部信息
     */
    fun getLoginHeader(): String? {
        return CacheManager.get("loginHeader_${getKey()}")
    }

    fun getLoginHeaderMap(): Map<String, String>? {
        val cache = getLoginHeader() ?: return null
        return try {
            jsonParser.decodeFromString<Map<String, String>>(cache)
        } catch (_: Exception) { null }
    }

    /**
     * 保存登录头部信息
     */
    fun putLoginHeader(header: String) {
        try {
            val headerMap = jsonParser.decodeFromString<Map<String, String>>(header)
            val cookie = headerMap["Cookie"] ?: headerMap["cookie"]
            cookie?.let { CookieStore.replaceCookie(getKey(), it) }
        } catch (_: Exception) {}
        CacheManager.put("loginHeader_${getKey()}", header)
    }

    fun removeLoginHeader() {
        CacheManager.delete("loginHeader_${getKey()}")
        CookieStore.removeCookie(getKey())
    }

    /**
     * 获取用户登录信息
     */
    fun getLoginInfo(): String? {
        return CacheManager.get("userInfo_${getKey()}")
    }

    fun getLoginInfoMap(): Map<String, String>? {
        val info = getLoginInfo() ?: return null
        return try {
            jsonParser.decodeFromString<Map<String, String>>(info)
        } catch (_: Exception) { null }
    }

    /**
     * 保存用户登录信息
     */
    fun putLoginInfo(info: String): Boolean {
        return try {
            CacheManager.put("userInfo_${getKey()}", info)
            true
        } catch (_: Exception) { false }
    }

    fun removeLoginInfo() {
        CacheManager.delete("userInfo_${getKey()}")
    }

    // ── 自定义变量 ──

    /**
     * 设置书源级自定义变量（供JS中 source.setVariable/source.getVariable 调用）
     */
    fun setVariable(variable: String?) {
        if (variable != null) {
            CacheManager.put("sourceVariable_${getKey()}", variable)
        } else {
            CacheManager.delete("sourceVariable_${getKey()}")
        }
    }

    fun getVariable(): String {
        return CacheManager.get("sourceVariable_${getKey()}") ?: ""
    }

    /**
     * 保存数据（供JS中 source.put/source.get 调用）
     */
    fun put(key: String, value: String): String {
        CacheManager.put("v_${getKey()}_${key}", value)
        return value
    }

    fun get(key: String): String {
        return CacheManager.get("v_${getKey()}_${key}") ?: ""
    }

    // ── Header ──

    fun getHeaderMap(hasLoginHeader: Boolean = false): HashMap<String, String> = HashMap<String, String>().apply {
        header?.let {
            try {
                val headerStr = when {
                    it.startsWith("@js:", true) -> evalJS(it.substring(4)).toString()
                    it.startsWith("<js>", true) -> evalJS(it.substring(4, it.lastIndexOf("<"))).toString()
                    else -> it
                }
                jsonParser.decodeFromString<Map<String, String>>(headerStr).let { map -> putAll(map) }
            } catch (_: Exception) {}
        }
        if (!containsKey("User-Agent")) {
            put("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
        }
        if (hasLoginHeader) {
            getLoginHeaderMap()?.let { putAll(it) }
        }
    }

    // ── JS执行 ──

    /**
     * 执行JS（书源级别，供header中@js:调用、login脚本等）
     */
    fun evalJS(jsStr: String, extraBindings: ((ScriptBindings) -> Unit)? = null): Any? {
        val bindings = ScriptBindings()
        JsExtensions.sourceGetter = { this }
        JsExtensions.ruleDataGetter = { null }
        bindings["java"] = JsExtensions
        bindings["source"] = this
        bindings["baseUrl"] = bookSourceUrl
        bindings["cookie"] = CookieStore
        bindings["cache"] = CacheManager
        extraBindings?.invoke(bindings)
        val scope = RhinoScriptEngine.getRuntimeScope(bindings)
        return RhinoScriptEngine.eval(jsStr, scope)
    }

    class Converters {
        @TypeConverter
        fun exploreRuleToString(rule: ExploreRule?): String = jsonParser.encodeToString(ExploreRule.serializer().nullable, rule)
        @TypeConverter
        fun stringToExploreRule(json: String?): ExploreRule? =
            json?.let { runCatching { jsonParser.decodeFromString<ExploreRule>(it) }.getOrNull() }

        @TypeConverter
        fun searchRuleToString(rule: SearchRule?): String = jsonParser.encodeToString(SearchRule.serializer().nullable, rule)
        @TypeConverter
        fun stringToSearchRule(json: String?): SearchRule? =
            json?.let { runCatching { jsonParser.decodeFromString<SearchRule>(it) }.getOrNull() }

        @TypeConverter
        fun bookInfoRuleToString(rule: BookInfoRule?): String = jsonParser.encodeToString(BookInfoRule.serializer().nullable, rule)
        @TypeConverter
        fun stringToBookInfoRule(json: String?): BookInfoRule? =
            json?.let { runCatching { jsonParser.decodeFromString<BookInfoRule>(it) }.getOrNull() }

        @TypeConverter
        fun tocRuleToString(rule: TocRule?): String = jsonParser.encodeToString(TocRule.serializer().nullable, rule)
        @TypeConverter
        fun stringToTocRule(json: String?): TocRule? =
            json?.let { runCatching { jsonParser.decodeFromString<TocRule>(it) }.getOrNull() }

        @TypeConverter
        fun contentRuleToString(rule: ContentRule?): String = jsonParser.encodeToString(ContentRule.serializer().nullable, rule)
        @TypeConverter
        fun stringToContentRule(json: String?): ContentRule? =
            json?.let { runCatching { jsonParser.decodeFromString<ContentRule>(it) }.getOrNull() }

        @TypeConverter
        fun reviewRuleToString(rule: ReviewRule?): String = "null"
        @TypeConverter
        fun stringToReviewRule(json: String?): ReviewRule? = null
    }
}
