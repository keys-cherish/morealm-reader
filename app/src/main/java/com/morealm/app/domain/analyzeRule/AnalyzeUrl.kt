package com.morealm.app.domain.analyzeRule

import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.entity.BookSource
import com.morealm.app.domain.entity.HttpTts
import com.morealm.app.domain.entity.parseHeaderMap
import com.morealm.app.domain.http.CacheManager
import com.morealm.app.domain.http.ConcurrentRateLimiter
import com.morealm.app.domain.http.CookieStore
import com.morealm.app.domain.http.BackstageWebView
import com.morealm.app.domain.http.RequestMethod
import com.morealm.app.domain.http.StrResponse
import com.morealm.app.domain.http.addHeaders
import com.morealm.app.domain.http.get
import com.morealm.app.domain.http.newCallByteArrayResponse
import com.morealm.app.domain.http.newCallStrResponse
import com.morealm.app.domain.http.okHttpClient
import com.morealm.app.domain.http.postForm
import com.morealm.app.domain.http.postJson
import com.morealm.app.domain.http.postMultipart
import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.regex.Pattern
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * 搜索URL规则解析
 * 处理 searchUrl 中的 {{key}}, {{page}}, POST参数, header 等
 */
@Suppress("unused", "MemberVisibilityCanBePrivate")
class AnalyzeUrl(
    private val mUrl: String,
    private val key: String? = null,
    private val page: Int? = null,
    private var baseUrl: String = "",
    private val source: BookSource? = null,
    private val ruleData: RuleDataInterface? = null,
    private var coroutineContext: CoroutineContext = EmptyCoroutineContext,
    headerMapF: Map<String, String>? = null,
    /**
     * HttpTts 朗读路径下的待合成文本。当 != null 时：
     *   - URL 模板里的 `{{speakText}}` / `{{encode}}` 被替换为 URL 编码后的值；
     *   - JS 上下文里 `speakText` / `speakSpeed` / `encode` 三个变量可读，方便
     *     源作者写 `<js>encodeURIComponent(speakText)</js>` 之类的逻辑；
     *   - 如果调用方没传 [headerMapF] 且没传 [source]，但传了 [httpTts]，会把
     *     httpTts.header（JSON）解析进 headerMap。
     * BookSource 路径下保持 null，行为完全不变。
     */
    private val speakText: String? = null,
    /**
     * 朗读速度。MoRealm 内部用 0.3-4.0 浮点；为了和 Legado/网络 TTS 服务的整数语速
     * 对齐，调用方在传入前自行换算（例如 `((speed - 1f) * 10).roundToInt()` 之类，
     * 具体 mapping 视目标服务而定）。当 != null 时通过 `{{speakSpeed}}` 注入。
     */
    private val speakSpeed: Int? = null,
    /**
     * HttpTts 朗读源配置。仅当 [source] 为 null、且本次调用是为 TTS 朗读发起时使用。
     * 用于：
     *   - 当 [headerMapF] 未传时，把 httpTts.header（JSON 字符串）解析为 headerMap；
     *   - 限速 ([concurrentRate]) 与 cookie jar 走 BookSource 那条路径，HttpTts 暂不
     *     接入（Legado 同款行为：HttpTTS 不参与 ConcurrentRateLimiter）。
     */
    private val httpTts: HttpTts? = null,
) {
    var ruleUrl = ""
        private set
    var url: String = ""
        private set
    var type: String? = null
        private set
    val headerMap = LinkedHashMap<String, String>()
    private var body: String? = null
    private var urlNoQuery: String = ""
    private var encodedForm: String? = null
    private var encodedQuery: String? = null
    private var charset: String? = null
    private var method = RequestMethod.GET
    private var retry: Int = 0
    private var useWebView: Boolean = false
    private var webJs: String? = null
    private val domain: String
    private val enabledCookieJar = source?.enabledCookieJar == true
    private val concurrentRateLimiter = ConcurrentRateLimiter(
        source?.bookSourceUrl,
        source?.concurrentRate,
    )

    init {
        val urlMatcher = paramPattern.matcher(baseUrl)
        if (urlMatcher.find()) baseUrl = baseUrl.substring(0, urlMatcher.start())
        // 解析 source header（支持 @js: 和 <js> 动态header）
        // 优先级：调用方显式传入的 headerMapF > BookSource.header > HttpTts.header
        val resolvedHeaders = headerMapF
            ?: source?.getHeaderMap()
            ?: httpTts?.parseHeaderMap()
        resolvedHeaders?.let {
            headerMap.putAll(it)
        }
        if (!headerMap.containsKey("User-Agent")) {
            headerMap["User-Agent"] = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }
        initUrl()
        domain = getSubDomain(source?.bookSourceUrl ?: url)
    }

    fun initUrl() {
        ruleUrl = mUrl
        analyzeJs()
        replaceKeyPageJs()
        analyzeUrl()
    }

    /**
     * 执行 @js, <js></js>
     */
    private fun analyzeJs() {
        var start = 0
        val jsMatcher = AnalyzeRule.JS_PATTERN.matcher(ruleUrl)
        var result = ruleUrl
        while (jsMatcher.find()) {
            if (jsMatcher.start() > start) {
                ruleUrl.substring(start, jsMatcher.start()).trim().let {
                    if (it.isNotEmpty()) {
                        result = it.replace("@result", result)
                    }
                }
            }
            result = evalJS(jsMatcher.group(2) ?: jsMatcher.group(1), result).toString()
            start = jsMatcher.end()
        }
        if (ruleUrl.length > start) {
            ruleUrl.substring(start).trim().let {
                if (it.isNotEmpty()) {
                    result = it.replace("@result", result)
                }
            }
        }
        ruleUrl = result
    }

    /**
     * 执行JS
     */
    fun getSource(): BookSource? = source

    /**
     * 执行 URL 层 `<js>` / `@js:` / `{{...}}` 内的脚本。
     *
     * 与 [AnalyzeRule.evalJS] 行为对齐（参考 Legado AnalyzeUrl.evalJS）：
     *
     * 1. **withRuntimeContext** —— 把当前 [source] / [coroutineContext] / [ruleData]
     *    压入 [JsExtensions] 的 ThreadLocal 栈。这样书源 JS 里写 `java.connect(...)`
     *    / `java.ajax(...)` 时，[JsExtensions] 内部能拿到正确的书源（用于限流、
     *    cookie jar、bookSource header）和取消语义。
     *    历史 bug：原实现裸调 `RhinoScriptEngine.eval`，`JsExtensions.getSource()`
     *    只剩 legacy getter 兜底，并发搜索时会出现源串掉、限流失效、cookie 丢失。
     *
     * 2. **sharedScope (jsLib)** —— 与 [AnalyzeRule.evalJS] 相同：若 [source] 配了
     *    `jsLib`，把 jsLib 的 RhinoTopLevel 设为 bindings 的 prototype，让 URL @js
     *    能调到书源自定义的辅助函数（如 `getKey()` / `getDecryptKey()`）。
     *    没配 jsLib 时仍走 [RhinoScriptEngine.getRuntimeScope]，行为不变。
     */
    fun evalJS(jsStr: String, result: Any? = null): Any? {
        return JsExtensions.withRuntimeContext(source, coroutineContext, ruleData) {
            val bindings = ScriptBindings()
            org.mozilla.javascript.Context.enter()
            try {
                // FIX: previously `bindings["java"] = this` exposed AnalyzeUrl as `java`,
                // but book sources call `java.connect(...)` (or even bare `connect(...)`)
                // expecting JsExtensions. AnalyzeUrl itself doesn't have `connect`, so
                // every such source crashed with "找不到函数 connect". Bind JsExtensions
                // for the `java` namespace and keep AnalyzeUrl reachable as `analyzeUrl`
                // for the rare source that needs page/key/source helpers from it.
                bindings["java"] = JsExtensions
                bindings["analyzeUrl"] = this
                bindings["baseUrl"] = baseUrl
                bindings["page"] = page
                bindings["key"] = key
                // 兼容部分 Legado/阅读旧书源中仍在使用的别名。
                bindings["searchPage"] = page
                bindings["searchKey"] = key
                bindings["source"] = source
                bindings["result"] = result
                bindings["cookie"] = CookieStore
                bindings["cache"] = CacheManager
                // HttpTts 朗读路径：把待合成文本与速度也丢进 JS 上下文。BookSource 路径
                // 下两个值都是 null，对老书源完全无影响。
                bindings["speakText"] = speakText ?: ""
                bindings["speakSpeed"] = speakSpeed ?: 0
                bindings["httpTts"] = httpTts
            } finally {
                org.mozilla.javascript.Context.exit()
            }
            // jsLib 共享作用域 —— 失败时（脚本异常 / 网络问题）退化为标准 runtime scope，
            // 避免一个有问题的 jsLib 把整个搜索 URL 跑炸。
            val sharedScope = source?.jsLib?.let {
                runCatching { SharedJsScope.getScope(it, coroutineContext) }.getOrNull()
            }
            val scope = if (sharedScope != null) {
                bindings.apply { prototype = sharedScope }
            } else {
                RhinoScriptEngine.getRuntimeScope(bindings)
            }
            RhinoScriptEngine.eval(normalizeJsSnippet(jsStr), scope, coroutineContext)
        }
    }

    private fun normalizeJsSnippet(jsStr: String): String {
        val script = jsStr.trim().removePrefix("javascript:").trim()
        if (script.isEmpty()) return script
        val looksLikeBlock = script.contains(';') || script.contains('\n') ||
            script.contains("return ") || script.contains("function") || script.contains("=>") ||
            script.startsWith("var ") || script.startsWith("let ") || script.startsWith("const ") ||
            script.startsWith("if") || script.startsWith("for") || script.startsWith("while")
        return if (looksLikeBlock) script else "($script)"
    }

    fun startBrowser(url: String) {
        startBrowser(url, url, null)
    }

    fun startBrowser(url: String, title: String) {
        startBrowser(url, title, null)
    }

    fun startBrowser(url: String, title: String, html: String?) {
        AppLog.warn(
            "AnalyzeUrl",
            "startBrowser requested by source '${source?.bookSourceName ?: ""}': $title $url",
        )
        if (html != null) {
            runBlocking(coroutineContext) {
                BackstageWebView(url = url, html = html, javaScript = null).getStrResponse()
            }
        }
    }

    fun startBrowserAwait(url: String): StrResponse {
        return startBrowserAwait(url, url, true, null)
    }

    fun startBrowserAwait(url: String, title: String): StrResponse {
        return startBrowserAwait(url, title, true, null)
    }

    fun startBrowserAwait(url: String, title: String, refetchAfterSuccess: Boolean): StrResponse {
        return startBrowserAwait(url, title, refetchAfterSuccess, null)
    }

    fun startBrowserAwait(url: String, title: String, refetchAfterSuccess: Boolean, html: String?): StrResponse {
        AppLog.warn(
            "AnalyzeUrl",
            "startBrowserAwait fallback for source '${source?.bookSourceName ?: ""}': $title $url",
        )
        val body = if (html != null) {
            runBlocking(coroutineContext) {
                BackstageWebView(url = url, html = html, javaScript = null).getStrResponse().body
            }
        } else {
            runBlocking(coroutineContext) {
                AnalyzeUrl(url, source = source, coroutineContext = coroutineContext).getStrResponseAwait().body
            }
        }
        return StrResponse(url, body)
    }

    fun put(key: String, value: String): String {
        ruleData?.putVariable(key, value)
        return value
    }

    fun get(key: String): String {
        return ruleData?.getVariable(key)?.takeIf { it.isNotEmpty() } ?: ""
    }

    @Suppress("HardwareIds")
    fun androidId(): String {
        return try {
            val ctx = com.morealm.app.MoRealmApp.instance
            android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: ""
        } catch (_: Exception) { "" }
    }

    fun ajax(url: Any): String? {
        return ajax(url, null)
    }

    fun ajax(url: Any, callTimeout: Long?): String? {
        val urlStr = if (url is List<*>) {
            url.firstOrNull().toString()
        } else {
            url.toString()
        }
        return runCatching {
            runBlocking(coroutineContext) {
                AnalyzeUrl(
                    urlStr,
                    baseUrl = baseUrl,
                    source = source,
                    ruleData = ruleData,
                    coroutineContext = coroutineContext,
                ).getStrResponseAwait().body
            }
        }.getOrElse { it.message }
    }

    fun post(urlStr: String, body: String): StrResponse {
        return post(urlStr, body, null)
    }

    fun post(urlStr: String, body: String, headers: Any?): StrResponse {
        val requestHeaders = LinkedHashMap<String, String>()
        requestHeaders.putAll(headerMap)
        requestHeaders.putAll(headersToMap(headers))
        if (!requestHeaders.containsKey("User-Agent")) {
            requestHeaders["User-Agent"] = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }
        val postDomain = getSubDomain(urlStr)
        if (enabledCookieJar) {
            CookieStore.getCookie(postDomain).takeIf { it.isNotBlank() }?.let { cookie ->
                val headerCookie = requestHeaders["Cookie"]
                requestHeaders["Cookie"] = if (headerCookie.isNullOrBlank()) {
                    cookie
                } else {
                    mergeCookies(cookie, headerCookie)
                }
            }
        }
        return runCatching {
            runBlocking(coroutineContext) {
                okHttpClient.newCallStrResponse {
                    url(urlStr)
                    addHeaders(requestHeaders)
                    val contentType = requestHeaders["Content-Type"]
                    val mediaType = (contentType ?: if (body.trimStart().startsWith("{") || body.trimStart().startsWith("[")) {
                        "application/json; charset=utf-8"
                    } else {
                        "application/x-www-form-urlencoded; charset=utf-8"
                    }).toMediaType()
                    post(body.toRequestBody(mediaType))
                }.also { saveCookie(it.raw, postDomain) }
            }
        }.getOrElse { StrResponse(urlStr, it.message ?: "") }
    }

    fun md5Encode(str: String): String = JsExtensions.md5Encode(str)

    fun md5Encode16(str: String): String = JsExtensions.md5Encode16(str)

    fun encodeURI(str: String): String {
        return try { URLEncoder.encode(str, "UTF-8") } catch (_: Exception) { "" }
    }

    fun encodeURI(str: String, enc: String): String {
        return try { URLEncoder.encode(str, enc) } catch (_: Exception) { "" }
    }

    /**
     * 替换关键字,页数,JS
     */
    private fun replaceKeyPageJs() {
        // HttpTts 朗读路径：先把 {{speakText}} / {{speakSpeed}} / {{encode}} 三个
        // 静态占位替换掉，再走原有的 {{...}} JS evalJS 流程。简单字符串替换让源
        // 作者既能写 `?text={{speakText}}` 这种最朴素的用法，也能写 `<js>...</js>`
        // 复杂逻辑（JS 上下文里同样可读 speakText / speakSpeed）。
        if (speakText != null) {
            val encoded = URLEncoder.encode(speakText, "UTF-8")
            ruleUrl = ruleUrl
                .replace("{{speakText}}", encoded)
                .replace("{{encode}}", encoded)
            speakSpeed?.let { ruleUrl = ruleUrl.replace("{{speakSpeed}}", it.toString()) }
        }
        if (ruleUrl.contains("{{") && ruleUrl.contains("}}")) {
            val analyze = RuleAnalyzer(ruleUrl)
            val url = analyze.innerRule("{{", "}}") { expression ->
                val jsEval = evalJS(expression) ?: ""
                when {
                    jsEval is String -> jsEval
                    jsEval is Double && jsEval % 1.0 == 0.0 -> String.format("%.0f", jsEval)
                    else -> jsEval.toString()
                }
            }
            if (url.isNotEmpty()) ruleUrl = url
        }
        // page pattern <1,2,3>
        page?.let {
            val matcher = pagePattern.matcher(ruleUrl)
            while (matcher.find()) {
                val pages = matcher.group(1)!!.split(",")
                ruleUrl = if (page < pages.size) {
                    ruleUrl.replace(matcher.group(), pages[page - 1].trim { it <= ' ' })
                } else {
                    ruleUrl.replace(matcher.group(), pages.last().trim { it <= ' ' })
                }
            }
        }
    }

    /**
     * 解析Url
     */
    private fun analyzeUrl() {
        val urlMatcher = paramPattern.matcher(ruleUrl)
        val rawUrlNoOption = if (urlMatcher.find()) ruleUrl.substring(0, urlMatcher.start()) else ruleUrl
        val urlNoOption = replaceLegacySearchPlaceholders(rawUrlNoOption)
        url = AnalyzeRule.getAbsoluteURL(baseUrl, urlNoOption)
        getBaseUrl(url)?.let { baseUrl = it }

        if (rawUrlNoOption.length != ruleUrl.length) {
            val urlOptionStr = ruleUrl.substring(urlMatcher.end())
            try {
                val option = urlOptionJson.decodeFromString<UrlOption>(urlOptionStr)
                option.method?.let { if (it.equals("POST", true)) method = RequestMethod.POST }
                option.headers?.let { headerMap.putAll(headersElementToMap(it)) }
                option.body?.let { body = bodyElementToString(it) }
                option.charset?.let { charset = it }
                option.retry?.asIntCompat()?.let { retry = it }
                option.type?.let { type = it }
                option.webView?.asBooleanCompat()?.let { useWebView = it }
                option.webJs?.let { webJs = it }
                option.js?.let { jsStr ->
                    evalJS(jsStr, url)?.toString()?.let { url = it }
                }
            } catch (_: Exception) {}
        }

        urlNoQuery = url
        when (method) {
            RequestMethod.GET -> {
                val pos = url.indexOf('?')
                if (pos != -1) {
                    encodedQuery = encodeQuery(url.substring(pos + 1))
                    urlNoQuery = url.substring(0, pos)
                }
            }
            RequestMethod.POST -> body?.let {
                if (!it.trimStart().let { s -> s.startsWith("{") || s.startsWith("[") || s.startsWith("<") }) {
                    encodedForm = encodeForm(it)
                }
            }
        }
    }

    /**
     * 兼容旧版阅读/Legado 书源中裸写的 searchKey/searchPage。
     *
     * 新规则通常写作 {{key}}/{{page}}，但不少导出的旧书源仍会在 URL 或
     * URL option 的 body/header 中直接出现 searchKey/searchPage。若不在这里
     * 兜底，导入后会请求字面量 searchKey 或解析 JSON option 失败，表现为所有搜索为空。
     */
    private fun replaceLegacySearchPlaceholders(value: String): String {
        var result = value
        key?.let { result = result.replace("searchKey", it) }
        page?.let { currentPage ->
            result = legacySearchPageOffsetPattern.replace(result) { match ->
                val offset = match.groupValues.getOrNull(1)?.toIntOrNull() ?: 0
                (currentPage + offset).toString()
            }
            result = result.replace("searchPage", currentPage.toString())
        }
        return result
    }

    private fun JsonElement.replaceLegacyPlaceholders(): JsonElement {
        return when (this) {
            is JsonObject -> JsonObject(mapValues { (_, value) -> value.replaceLegacyPlaceholders() })
            is JsonArray -> JsonArray(map { it.replaceLegacyPlaceholders() })
            JsonNull -> JsonNull
            is JsonPrimitive -> if (isString) JsonPrimitive(replaceLegacySearchPlaceholders(content)) else this
        }
    }

    private fun bodyElementToString(element: JsonElement): String? {
        val normalized = element.replaceLegacyPlaceholders()
        return when (normalized) {
            JsonNull -> null
            is JsonPrimitive -> normalized.content
            else -> normalized.toString()
        }
    }

    private fun headersElementToMap(element: JsonElement): Map<String, String> {
        val normalized = element.replaceLegacyPlaceholders()
        return when (normalized) {
            is JsonObject -> normalized.mapValues { (_, value) ->
                when (value) {
                    is JsonPrimitive -> value.content
                    else -> value.toString()
                }
            }
            is JsonPrimitive -> headersToMap(normalized.content)
            else -> emptyMap()
        }
    }

    private fun JsonElement.asIntCompat(): Int? {
        return when (this) {
            is JsonPrimitive -> intOrNull ?: content.toIntOrNull()
            else -> null
        }
    }

    private fun JsonElement.asBooleanCompat(): Boolean? {
        return when (this) {
            is JsonPrimitive -> booleanOrNull ?: when (content.lowercase()) {
                "", "false", "0", "null" -> false
                "true", "1" -> true
                else -> true
            }
            else -> true
        }
    }

    /**
     * 编码查询参数，支持自定义charset
     */
    private fun encodeQuery(query: String): String {
        if (charset.isNullOrEmpty()) return query
        return try {
            val cs = Charset.forName(charset)
            val parts = query.split("&")
            parts.joinToString("&") { part ->
                val eq = part.indexOf('=')
                if (eq == -1) {
                    URLEncoder.encode(part, cs.name())
                } else {
                    val k = part.substring(0, eq)
                    val v = part.substring(eq + 1)
                    URLEncoder.encode(k, cs.name()) + "=" + URLEncoder.encode(v, cs.name())
                }
            }
        } catch (_: Exception) { query }
    }

    /**
     * 编码表单参数，支持自定义charset
     */
    private fun encodeForm(form: String): String {
        if (charset.isNullOrEmpty()) return form
        return try {
            val cs = Charset.forName(charset)
            val parts = form.split("&")
            parts.joinToString("&") { part ->
                val eq = part.indexOf('=')
                if (eq == -1) {
                    URLEncoder.encode(part, cs.name())
                } else {
                    val k = part.substring(0, eq)
                    val v = part.substring(eq + 1)
                    URLEncoder.encode(k, cs.name()) + "=" + URLEncoder.encode(v, cs.name())
                }
            }
        } catch (_: Exception) { form }
    }

    /**
     * 设置cookie: 仅在书源显式启用 CookieJar 时，才把持久化 Cookie 自动合并进请求。
     * 这样保留 header 中手写 Cookie 的同时，避免未启用的 Legado 书源被历史 Cookie 污染。
     */
    /**
     * Inject stored cookies into the outgoing header. Aligns with Legado's behavior:
     * cookies are always loaded from [CookieStore] (so the user's login state is honored
     * even when the source disables auto-jar). [BookSource.enabledCookieJar] only
     * controls whether the global OkHttp interceptor will *save* Set-Cookie on response —
     * achieved by adding the `CookieJar: false` opt-out header when disabled.
     */
    private fun setCookie() {
        val cookie = CookieStore.getCookie(domain)
        if (cookie.isNotEmpty()) {
            val headerCookie = headerMap["Cookie"]
            headerMap["Cookie"] = if (headerCookie.isNullOrBlank()) {
                cookie
            } else {
                mergeCookies(cookie, headerCookie)
            }
        }
        if (!enabledCookieJar) {
            headerMap[com.morealm.app.domain.http.CookieManager.cookieJarHeader] = "false"
        } else {
            headerMap.remove(com.morealm.app.domain.http.CookieManager.cookieJarHeader)
        }
    }

    /**
     * Build a synthetic 500 Response from a Throwable.
     * Used by loginCheckJs / HTTP TTS check JS so book-source JS can still inspect the
     * failure (status / body) when the network call threw — Legado-parity.
     */
    fun getErrResponse(e: Throwable): okhttp3.Response {
        val body = (e.message ?: "Error Response")
            .toResponseBody("text/plain; charset=utf-8".toMediaType())
        return okhttp3.Response.Builder()
            .request(okhttp3.Request.Builder().url(url.ifBlank { "https://error.local/" }).build())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(500)
            .message(e.message?.take(120) ?: "Error Response")
            .body(body)
            .build()
    }

    /** StrResponse variant of [getErrResponse] */
    fun getErrStrResponse(e: Throwable): StrResponse =
        StrResponse(getErrResponse(e), e.message ?: "")

    /**
     * 合并两个cookie字符串
     */
    private fun mergeCookies(vararg cookies: String?): String {
        val map = linkedMapOf<String, String>()
        for (cookie in cookies) {
            if (cookie.isNullOrBlank()) continue
            cookie.split(";").forEach { pair ->
                val parts = pair.split("=", limit = 2)
                if (parts.size == 2) {
                    val k = parts[0].trim()
                    val v = parts[1].trim()
                    if (k.isNotEmpty()) map[k] = v
                }
            }
        }
        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /**
     * 保存响应中的cookie
     */
    private fun saveCookie(response: okhttp3.Response, cookieDomain: String = domain) {
        if (!enabledCookieJar) return
        val setCookieHeaders = response.headers("Set-Cookie")
        if (setCookieHeaders.isNotEmpty()) {
            val cookieParts = setCookieHeaders.mapNotNull { header ->
                header.split(";").firstOrNull()?.trim()?.takeIf { it.contains("=") }
            }
            if (cookieParts.isNotEmpty()) {
                CookieStore.replaceCookie(cookieDomain, cookieParts.joinToString("; "))
            }
        }
    }

    private fun headersToMap(headers: Any?): Map<String, String> {
        return when (headers) {
            null -> emptyMap()
            is Map<*, *> -> headers.entries.associate { it.key.toString() to it.value.toString() }
            is org.mozilla.javascript.NativeObject -> headers.ids.associate { key ->
                key.toString() to org.mozilla.javascript.ScriptableObject.getProperty(headers, key.toString()).toString()
            }
            is String -> headers.split('\n', '&')
                .mapNotNull { line ->
                    val idx = line.indexOf(':').takeIf { it > 0 } ?: line.indexOf('=').takeIf { it > 0 } ?: return@mapNotNull null
                    line.substring(0, idx).trim().takeIf { it.isNotEmpty() }?.let { it to line.substring(idx + 1).trim() }
                }
                .toMap()
            else -> emptyMap()
        }
    }

    /**
     * 访问网站,返回StrResponse（带并发限制和Cookie管理）
     */
    suspend fun getStrResponseAwait(): StrResponse {
        return getStrResponseAwait(jsStr = null, sourceRegex = null, useWebView = true)
    }

    /**
     * 访问网站,返回StrResponse（带并发限制和Cookie管理）
     *
     * @param jsStr 正文规则层传入的 ContentRule.webJs；URL option 中的 webJs 优先级更高。
     * @param sourceRegex 正文规则层传入的资源嗅探正则。
     * @param useWebView 调用方是否允许使用 WebView。
     */
    suspend fun getStrResponseAwait(
        jsStr: String?,
        sourceRegex: String? = null,
        useWebView: Boolean = true,
    ): StrResponse {
        val needRuleWebView = !jsStr.isNullOrBlank() || !sourceRegex.isNullOrBlank()
        // webJs/sourceRegex 本身就是 WebView 语义；若只按 URL option 判断，会导致导入字段存在但永远不执行。
        val shouldUseWebView = useWebView && (this.useWebView || needRuleWebView)
        if (shouldUseWebView) {
            return concurrentRateLimiter.withLimit {
                setCookie()
                val webView = BackstageWebView(
                    url = url,
                    headerMap = headerMap,
                    javaScript = webJs ?: jsStr,
                    sourceRegex = sourceRegex,
                    tag = domain,
                    persistCookie = enabledCookieJar,
                )
                webView.getStrResponse()
            }
        }
        return concurrentRateLimiter.withLimit {
            setCookie()
            val strResponse = okHttpClient.newCallStrResponse(retry) {
                addHeaders(headerMap)
                when (method) {
                    RequestMethod.POST -> {
                        url(urlNoQuery)
                        val contentType = headerMap["Content-Type"]
                        val body = body
                        if (!encodedForm.isNullOrEmpty() || body.isNullOrBlank()) {
                            postForm(encodedForm ?: "")
                        } else if (!contentType.isNullOrBlank()) {
                            val requestBody = body.toRequestBody(contentType.toMediaType())
                            post(requestBody)
                        } else {
                            postJson(body)
                        }
                    }
                    else -> get(urlNoQuery, encodedQuery)
                }
            }
            saveCookie(strResponse.raw)
            strResponse
        }
    }

    /**
     * 同步版本
     */
    fun getStrResponse(): StrResponse {
        return runBlocking(coroutineContext) {
            getStrResponseAwait()
        }
    }

    fun getStrResponse(
        jsStr: String?,
        sourceRegex: String? = null,
        useWebView: Boolean = true,
    ): StrResponse {
        return runBlocking(coroutineContext) {
            getStrResponseAwait(jsStr, sourceRegex, useWebView)
        }
    }

    /**
     * 访问网站,返回ByteArray（用于图片、字体等二进制内容）
     */
    suspend fun getByteArrayAwait(): ByteArray {
        return concurrentRateLimiter.withLimit {
            setCookie()
            okHttpClient.newCallByteArrayResponse(retry) {
                addHeaders(headerMap)
                when (method) {
                    RequestMethod.POST -> {
                        url(urlNoQuery)
                        val contentType = headerMap["Content-Type"]
                        val body = body
                        if (!encodedForm.isNullOrEmpty() || body.isNullOrBlank()) {
                            postForm(encodedForm ?: "")
                        } else if (!contentType.isNullOrBlank()) {
                            val requestBody = body.toRequestBody(contentType.toMediaType())
                            post(requestBody)
                        } else {
                            postJson(body)
                        }
                    }
                    else -> get(urlNoQuery, encodedQuery)
                }
            }
        }
    }

    fun getByteArray(): ByteArray {
        return runBlocking(coroutineContext) {
            getByteArrayAwait()
        }
    }

    private fun getBaseUrl(url: String): String? {
        return try {
            val uri = java.net.URI(url)
            "${uri.scheme}://${uri.host}" + if (uri.port > 0) ":${uri.port}" else ""
        } catch (_: Exception) { null }
    }

    private fun getSubDomain(url: String): String {
        return try {
            val host = java.net.URI(url).host ?: url
            val parts = host.split(".")
            if (parts.size > 2) parts.takeLast(2).joinToString(".") else host
        } catch (_: Exception) { url }
    }

    @Serializable
    data class UrlOption(
        val method: String? = null,
        val headers: JsonElement? = null,
        val body: JsonElement? = null,
        val charset: String? = null,
        val type: String? = null,
        val webView: JsonElement? = null,
        val webJs: String? = null,
        val js: String? = null,
        val retry: JsonElement? = null,
    )

    companion object {
        val defined_PATTERN: Pattern = Pattern.compile("\\$\\d{1,2}")
        val paramPattern: Pattern = Pattern.compile("\\s*,\\s*(?=\\{)")
        private val pagePattern: Pattern = Pattern.compile("<(.*?)>")
        private val legacySearchPageOffsetPattern = Regex("searchPage([+-]\\d+)")
        private val urlOptionJson = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
