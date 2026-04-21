package com.morealm.app.domain.analyzeRule

import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.entity.BookSource
import com.morealm.app.domain.http.RequestMethod
import com.morealm.app.domain.http.StrResponse
import com.morealm.app.domain.http.addHeaders
import com.morealm.app.domain.http.get
import com.morealm.app.domain.http.newCallStrResponse
import com.morealm.app.domain.http.okHttpClient
import com.morealm.app.domain.http.postForm
import com.morealm.app.domain.http.postJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import java.net.URLEncoder
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
) {
    var ruleUrl = ""
        private set
    var url: String = ""
        private set
    val headerMap = LinkedHashMap<String, String>()
    private var body: String? = null
    private var urlNoQuery: String = ""
    private var encodedForm: String? = null
    private var encodedQuery: String? = null
    private var charset: String? = null
    private var method = RequestMethod.GET
    private var retry: Int = 0

    init {
        val urlMatcher = paramPattern.matcher(baseUrl)
        if (urlMatcher.find()) baseUrl = baseUrl.substring(0, urlMatcher.start())
        // 解析 source header
        source?.header?.let { headerStr ->
            try {
                val json = Json { ignoreUnknownKeys = true; isLenient = true }
                json.decodeFromString<Map<String, String>>(headerStr).forEach { (k, v) ->
                    headerMap[k] = v
                }
            } catch (_: Exception) {}
        }
        if (!headerMap.containsKey("User-Agent")) {
            headerMap["User-Agent"] = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }
        initUrl()
    }

    fun initUrl() {
        ruleUrl = mUrl
        replaceKeyPageJs()
        analyzeUrl()
    }
    /**
     * 替换关键字,页数
     */
    private fun replaceKeyPageJs() {
        // 替换 {{key}} {{page}} 等内嵌模板
        if (ruleUrl.contains("{{") && ruleUrl.contains("}}")) {
            val analyze = RuleAnalyzer(ruleUrl)
            val url = analyze.innerRule("{{", "}}") { expression ->
                when {
                    expression.trim() == "key" -> key?.let { URLEncoder.encode(it, "UTF-8") } ?: ""
                    expression.trim() == "page" -> (page ?: 1).toString()
                    expression.trim().startsWith("key") -> key?.let { URLEncoder.encode(it, "UTF-8") } ?: ""
                    else -> expression // JS expressions not supported yet
                }
            }
            if (url.isNotEmpty()) ruleUrl = url
        }
        // page pattern <1,2,3>
        page?.let {
            val matcher = pagePattern.matcher(ruleUrl)
            while (matcher.find()) {
                val pages = matcher.group(1)!!.split(",")
                ruleUrl = if (page <= pages.size) {
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
        val urlNoOption = if (urlMatcher.find()) ruleUrl.substring(0, urlMatcher.start()) else ruleUrl
        url = AnalyzeRule.getAbsoluteURL(baseUrl, urlNoOption)
        getBaseUrl(url)?.let { baseUrl = it }

        if (urlNoOption.length != ruleUrl.length) {
            val urlOptionStr = ruleUrl.substring(urlMatcher.end())
            try {
                val json = Json { ignoreUnknownKeys = true; isLenient = true }
                val option = json.decodeFromString<UrlOption>("{$urlOptionStr")
                option.method?.let { if (it.equals("POST", true)) method = RequestMethod.POST }
                option.headers?.forEach { (k, v) -> headerMap[k] = v }
                option.body?.let { body = it }
                option.charset?.let { charset = it }
            } catch (_: Exception) {}
        }

        urlNoQuery = url
        when (method) {
            RequestMethod.GET -> {
                val pos = url.indexOf('?')
                if (pos != -1) {
                    encodedQuery = url.substring(pos + 1)
                    urlNoQuery = url.substring(0, pos)
                }
            }
            RequestMethod.POST -> body?.let {
                if (!it.trimStart().let { s -> s.startsWith("{") || s.startsWith("[") || s.startsWith("<") }) {
                    encodedForm = it
                }
            }
        }
    }

    /**
     * 访问网站,返回StrResponse
     */
    suspend fun getStrResponseAwait(): StrResponse {
        return okHttpClient.newCallStrResponse(retry) {
            addHeaders(headerMap)
            when (method) {
                RequestMethod.POST -> {
                    url(urlNoQuery)
                    val contentType = headerMap["Content-Type"]
                    val body = body
                    if (!encodedForm.isNullOrEmpty() || body.isNullOrBlank()) {
                        postForm(encodedForm ?: "")
                    } else if (!contentType.isNullOrBlank()) {
                        val requestBody = body.toByteArray().let {
                            okhttp3.RequestBody.Companion.create(it, contentType.toMediaType())
                        }
                        post(requestBody)
                    } else {
                        postJson(body)
                    }
                }
                else -> get(urlNoQuery, encodedQuery)
            }
        }
    }

    private fun getBaseUrl(url: String): String? {
        return try {
            val uri = java.net.URI(url)
            "${uri.scheme}://${uri.host}" + if (uri.port > 0) ":${uri.port}" else ""
        } catch (_: Exception) { null }
    }

    @Serializable
    data class UrlOption(
        val method: String? = null,
        val headers: Map<String, String>? = null,
        val body: String? = null,
        val charset: String? = null,
        val type: String? = null,
        val webView: Boolean? = null,
        val js: String? = null,
        val retry: Int? = null,
    )

    companion object {
        val defined_PATTERN: Pattern = Pattern.compile("\\$\\d{1,2}")
        val paramPattern: Pattern = Pattern.compile("\\s*,\\s*\\{")
        private val pagePattern: Pattern = Pattern.compile("<(.*?)>")
    }
}
