package com.morealm.app.domain.analyzeRule

import android.util.Base64
import android.provider.Settings
import androidx.annotation.Keep
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.entity.BookSource
import com.morealm.app.domain.http.CacheManager
import com.morealm.app.domain.http.CookieStore
import com.morealm.app.domain.http.StrResponse
import com.morealm.app.domain.http.addHeaders
import com.morealm.app.domain.http.newCallStrResponse
import com.morealm.app.domain.http.okHttpClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.apache.commons.text.StringEscapeUtils
import org.jsoup.Jsoup
import java.io.File
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.SimpleTimeZone
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * JS扩展工具类 - 在JS中通过 java.xxx() 调用
 * 提供 base64/md5/aes/http/cookie/编码 等常用方法
 */
@Keep
@Suppress("unused")
object JsExtensions {

    private data class RuntimeContext(
        val source: BookSource?,
        val coroutineContext: CoroutineContext,
        val ruleData: RuleDataInterface?,
        /**
         * 当前 JS 调用所归属的 AnalyzeRule 实例。Legado 那边 JsExtensions 是 interface，
         * AnalyzeRule 直接 implements，所以书源 JS 写 `java.setContent(...)` / `java.getElements(...)`
         * 时拿到的就是当前规则实例。我们这边 JsExtensions 是 `object`，没法被继承，
         * 改用 ThreadLocal 把当前 AnalyzeRule 透传进来，由本类的 setContent/getElements
         * 等方法转发出去。null = 当前调用栈没有 AnalyzeRule（极少见，只发生在
         * 直接从外部调 JsExtensions 的方法时）。
         */
        val analyzeRule: AnalyzeRule? = null,
    )

    private val runtimeStack = ThreadLocal<MutableList<RuntimeContext>>()

    /**
     * JS bridge 是全局单例，但 source/ruleData/coroutineContext 必须按本次 JS 执行隔离。
     * 搜索会并发执行多个书源，不能再把这些状态写到 object 级可变字段里，否则会串源。
     */
    fun <T> withRuntimeContext(
        source: BookSource?,
        coroutineContext: CoroutineContext = EmptyCoroutineContext,
        ruleData: RuleDataInterface? = null,
        analyzeRule: AnalyzeRule? = null,
        block: () -> T,
    ): T {
        val stack = runtimeStack.get() ?: mutableListOf<RuntimeContext>().also(runtimeStack::set)
        stack.add(RuntimeContext(source, coroutineContext, ruleData, analyzeRule))
        try {
            return block()
        } finally {
            stack.removeAt(stack.lastIndex)
            if (stack.isEmpty()) runtimeStack.remove()
        }
    }

    private fun currentRuntimeContext(): RuntimeContext? = runtimeStack.get()?.lastOrNull()

    private fun currentCoroutineContext(): CoroutineContext =
        currentRuntimeContext()?.coroutineContext
            ?: coroutineContextGetter?.invoke()
            ?: EmptyCoroutineContext

    private fun currentRuleData(): RuleDataInterface? =
        currentRuntimeContext()?.ruleData ?: ruleDataGetter?.invoke()

    @Deprecated("仅作为旧调用兜底。新代码请使用 withRuntimeContext 隔离每次 JS 执行上下文。")
    var sourceGetter: (() -> Any?)? = null
    @Deprecated("仅作为旧调用兜底。新代码请使用 withRuntimeContext 隔离每次 JS 执行上下文。")
    var coroutineContextGetter: (() -> CoroutineContext)? = null
    @Deprecated("仅作为旧调用兜底。新代码请使用 withRuntimeContext 隔离每次 JS 执行上下文。")
    var ruleDataGetter: (() -> RuleDataInterface?)? = null

    fun getSource(): BookSource? =
        currentRuntimeContext()?.source ?: sourceGetter?.invoke() as? BookSource

    fun getTag(): String? = getSource()?.bookSourceUrl

    /** 当前 JS 调用归属的 AnalyzeRule 实例（由 [AnalyzeRule.evalJS] 在 [withRuntimeContext] 时压栈）。 */
    private fun currentAnalyzeRule(): AnalyzeRule? = currentRuntimeContext()?.analyzeRule

    // ── AnalyzeRule 转发桥 ─────────────────────────────────────────────────────
    //
    // Legado 那边 AnalyzeRule 直接 implements JsExtensions，书源 JS 写
    // `java.setContent(...)` / `java.getElements(...)` 拿到的就是当前规则实例。
    // 我们 JsExtensions 是 object 没法被继承，所以书源 JS 调到这两个方法时
    // 落到这里的占位实现。日志 `log_2026-05-04` 大量
    // `TypeError: 在对象 ... JsExtensions ... 中找不到函数 setContent / getElements`
    // 即此问题 —— 桥不全 → 书源在搜索 / 详情 / 章节列表路径直接挂掉。
    //
    // 实现策略：转发到 ThreadLocal 内的当前 AnalyzeRule。null 时返回安全兜底
    // （setContent 返回 null、getElements 返回空列表）让 JS 继续往下走，避免
    // 整段规则中断。

    /**
     * 把 [content] 注入当前规则上下文，对齐 Legado [AnalyzeRule.setContent]。
     *
     * @return 当前 [AnalyzeRule]（链式调用）；当前调用栈没有 AnalyzeRule 时返回 null。
     */
    fun setContent(content: Any?): AnalyzeRule? = setContent(content, null)

    fun setContent(content: Any?, baseUrl: String?): AnalyzeRule? {
        val rule = currentAnalyzeRule() ?: run {
            AppLog.warn(
                "JsExtensions",
                "setContent called outside an AnalyzeRule context (returning null); " +
                    "source='${getSource()?.bookSourceName ?: ""}'",
            )
            return null
        }
        return rule.setContent(content, baseUrl)
    }

    /** 给当前规则换 baseUrl，对齐 Legado [AnalyzeRule.setBaseUrl]。 */
    fun setBaseUrl(baseUrl: String?): AnalyzeRule? = currentAnalyzeRule()?.setBaseUrl(baseUrl)

    /**
     * 用规则取列表，对齐 Legado [AnalyzeRule.getElements]。
     * @return 解析结果列表；当前调用栈没有 AnalyzeRule 时返回空列表。
     */
    fun getElements(ruleStr: String): List<Any> {
        val rule = currentAnalyzeRule() ?: run {
            AppLog.warn(
                "JsExtensions",
                "getElements called outside an AnalyzeRule context (returning emptyList); " +
                    "rule='$ruleStr' source='${getSource()?.bookSourceName ?: ""}'",
            )
            return emptyList()
        }
        return runCatching { rule.getElements(ruleStr) }.getOrElse {
            AppLog.warn("JsExtensions", "getElements failed for rule='$ruleStr': ${it.message}")
            emptyList()
        }
    }

    /** 单元素版本，对齐 Legado [AnalyzeRule.getElement]，未实现时直接返回列表第一个。 */
    fun getElement(ruleStr: String): Any? = getElements(ruleStr).firstOrNull()

    // 注：Legado 那边 AnalyzeRule 还提供 `getString` / `getStringList` 作为规则解析桥，
    // 但本类的 [getString]（HTTP fetch，行 134/224）和 [JsExtensions.connect] 衍生
    // 出来的同名方法占用了相同 JVM 签名，重定义会触发 platform declaration clash。
    // 当前书源 JS 主要报 `setContent` / `getElements` 找不到，先把这两个补齐让规则
    // 能跑；规则版 getString 走旧名 `connect(...).body` 或 `ajax(...)` 兜底。

    // ── Variable get/put (delegated to ruleData, matches AnalyzeUrl API) ──

    fun put(key: String, value: String): String {
        val ruleData = currentRuleData()
        if (ruleData != null) {
            ruleData.putVariable(key, value)
        } else {
            getSource()?.put(key, value)
        }
        return value
    }

    fun get(key: String): String {
        return currentRuleData()?.getVariable(key)?.takeIf { it.isNotEmpty() }
            ?: getSource()?.get(key)?.takeIf { it.isNotEmpty() }
            ?: ""
    }

    // ── HTTP ──

    fun ajax(url: Any): String? {
        val urlStr = if (url is List<*>) url.firstOrNull().toString() else url.toString()
        val ctx = currentCoroutineContext()
        val analyzeUrl = AnalyzeUrl(
            urlStr,
            source = getSource(),
            ruleData = currentRuleData(),
            coroutineContext = ctx,
        )
        return runCatching {
            runBlocking(ctx) { analyzeUrl.getStrResponseAwait().body }
        }.getOrElse { it.message }
    }

    fun ajax(url: Any, callTimeout: Long?): String? = ajax(url)

    /** Legado-compatible: fetch URL and return body string (used by many book sources) */
    fun getString(url: String): String? = ajax(url)

    fun getString(url: String, headers: Any?): String? = connect(url, headers).body

    fun connect(urlStr: String): StrResponse {
        return connect(urlStr, null, null)
    }

    fun connect(urlStr: String, header: Any?): StrResponse {
        return connect(urlStr, header, null)
    }

    fun connect(urlStr: String, header: Any?, callTimeout: Long?): StrResponse {
        val ctx = currentCoroutineContext()
        val headerMap = headersToMap(header).takeIf { it.isNotEmpty() }
        val analyzeUrl = AnalyzeUrl(
            urlStr,
            source = getSource(),
            ruleData = currentRuleData(),
            coroutineContext = ctx,
            headerMapF = headerMap,
        )
        return runCatching {
            runBlocking(ctx) { analyzeUrl.getStrResponseAwait() }
        }.getOrElse { StrResponse(urlStr, it.message ?: "") }
    }

    fun getStrResponse(urlStr: String): StrResponse = connect(urlStr)

    fun getByteArray(urlStr: String): ByteArray {
        val ctx = currentCoroutineContext()
        val analyzeUrl = AnalyzeUrl(
            urlStr,
            source = getSource(),
            ruleData = currentRuleData(),
            coroutineContext = ctx,
        )
        return runCatching {
            runBlocking(ctx) { analyzeUrl.getByteArrayAwait() }
        }.getOrElse { ByteArray(0) }
    }

    fun ajaxAll(urlList: Array<String>): Array<StrResponse> = ajaxAll(urlList, false)

    fun ajaxAll(urlList: Array<String>, skipRateLimit: Boolean): Array<StrResponse> {
        val ctx = currentCoroutineContext()
        val source = getSource()
        val ruleData = currentRuleData()
        return runBlocking(ctx) {
            urlList.map { url ->
                async {
                    withRuntimeContext(source, ctx, ruleData) {
                        connect(url)
                    }
                }
            }.awaitAll().toTypedArray()
        }
    }

    fun ajaxTestAll(urlList: Array<String>, timeout: Int): Array<StrResponse> {
        return ajaxTestAll(urlList, timeout, false)
    }

    fun ajaxTestAll(urlList: Array<String>, timeout: Int, skipRateLimit: Boolean): Array<StrResponse> {
        return ajaxAll(urlList, skipRateLimit)
    }

    fun post(urlStr: String, body: String): StrResponse = post(urlStr, body, null)

    fun post(urlStr: String, body: String, headers: Any?): StrResponse {
        val ctx = currentCoroutineContext()
        val headerMap = headersToMap(headers).toMutableMap()
        if (!headerMap.containsKey("User-Agent")) {
            headerMap["User-Agent"] = DEFAULT_USER_AGENT
        }
        return runCatching {
            runBlocking(ctx) {
                okHttpClient.newCallStrResponse {
                    url(urlStr)
                    addHeaders(headerMap)
                    val contentType = headerMap["Content-Type"]
                    val mediaType = (contentType ?: if (body.trimStart().startsWith("{") || body.trimStart().startsWith("[")) {
                        "application/json; charset=utf-8"
                    } else {
                        "application/x-www-form-urlencoded; charset=utf-8"
                    }).toMediaType()
                    post(body.toRequestBody(mediaType))
                }
            }
        }.getOrElse { StrResponse(urlStr, it.message ?: "") }
    }

    fun get(urlStr: String, headers: Map<String, String>): StrResponse {
        return connect(urlStr, headers)
    }

    fun get(urlStr: String, headers: Map<String, String>, timeout: Int?): StrResponse {
        return connect(urlStr, headers, timeout?.toLong())
    }

    fun head(urlStr: String, headers: Map<String, String>): StrResponse {
        return head(urlStr, headers, null)
    }

    fun head(urlStr: String, headers: Map<String, String>, timeout: Int?): StrResponse {
        val ctx = currentCoroutineContext()
        val headerMap = headers.toMutableMap()
        if (!headerMap.containsKey("User-Agent")) {
            headerMap["User-Agent"] = DEFAULT_USER_AGENT
        }
        return runCatching {
            runBlocking(ctx) {
                okHttpClient.newCallStrResponse {
                    url(urlStr)
                    addHeaders(headerMap)
                    head()
                }
            }
        }.getOrElse { StrResponse(urlStr, it.message ?: "") }
    }

    // ── Logging (called by book sources for debugging) ──

    fun log(msg: Any?): Any? {
        AppLog.debug("JsExtensions", msg.toString())
        return msg
    }

    fun logType(any: Any?) {
        AppLog.debug("JsExtensions", "type: ${any?.javaClass?.name}, value: $any")
    }

    fun toast(msg: Any?) {
        log(msg)
    }

    fun longToast(msg: Any?) {
        log(msg)
    }

    // ── Base64 ──

    fun base64Decode(str: String?): String {
        if (str.isNullOrBlank()) return ""
        return String(Base64.decode(str, Base64.DEFAULT))
    }

    fun base64Decode(str: String?, charset: String): String {
        if (str.isNullOrBlank()) return ""
        return String(Base64.decode(str, Base64.DEFAULT), charset(charset))
    }

    fun base64Decode(str: String, flags: Int): String {
        return String(Base64.decode(str, flags))
    }

    fun base64DecodeToByteArray(str: String?): ByteArray? {
        if (str.isNullOrBlank()) return null
        return Base64.decode(str, Base64.DEFAULT)
    }

    fun base64DecodeToByteArray(str: String?, flags: Int): ByteArray? {
        if (str.isNullOrBlank()) return null
        return Base64.decode(str, flags)
    }

    fun base64Encode(str: String): String? {
        return Base64.encodeToString(str.toByteArray(), Base64.NO_WRAP)
    }

    fun base64Encode(str: String, flags: Int): String? {
        return Base64.encodeToString(str.toByteArray(), flags)
    }

    // ── Hex ──

    fun hexDecodeToByteArray(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }

    fun hexDecodeToString(hex: String): String {
        return String(hexDecodeToByteArray(hex))
    }

    fun hexEncodeToString(str: String): String {
        return str.toByteArray().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    // ── MD5 ──

    fun md5Encode(str: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(str.toByteArray())
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun md5Encode16(str: String): String {
        return md5Encode(str).substring(8, 24)
    }

    // ── AES (通过 createSymmetricCrypto 统一接口) ──

    fun createSymmetricCrypto(
        transformation: String,
        key: ByteArray?,
        iv: ByteArray?
    ): SymmetricCryptoHelper {
        return SymmetricCryptoHelper(transformation, key, iv)
    }

    fun createSymmetricCrypto(transformation: String, key: ByteArray): SymmetricCryptoHelper {
        return createSymmetricCrypto(transformation, key, null)
    }

    fun createSymmetricCrypto(transformation: String, key: String): SymmetricCryptoHelper {
        return createSymmetricCrypto(transformation, key.toByteArray(), null)
    }

    fun createSymmetricCrypto(transformation: String, key: String, iv: String?): SymmetricCryptoHelper {
        return createSymmetricCrypto(transformation, key.toByteArray(), iv?.toByteArray())
    }

    // ── Legado 兼容 AES 便捷接口 ──
    //
    // 移植自 io.legado.app.help.JsEncodeUtils（已 @Deprecated 但仍被大量书源 JS 调用）。
    // 形如「{{书源 JS}}」里写 `java.aesBase64DecodeToString(data, key, "AES/CBC/PKCS5Padding", iv)`
    // 的旧规则会通过 Rhino 解析到这些方法，找不到就刷屏 `TypeError: 找不到函数 aesBase64DecodeToString`。
    // 全部转调本类已有的 [createSymmetricCrypto] / [SymmetricCryptoHelper]，零运行时新依赖。
    //
    // 解码失败（key 错 / 数据非合法 base64 / cipher 抛 BadPaddingException 等）一律
    // 返回 null —— 与 Legado 行为一致，让书源 JS 可以 try/catch 回退。绝不抛到 caller。

    /**
     * 已经 base64 编码的 AES 密文 → 解密为字符串。
     * @param str base64 编码的 AES 加密数据
     * @param key 解密 key
     * @param transformation 形如 "AES/CBC/PKCS5Padding"
     * @param iv 偏移向量（CBC 必填，ECB 传任意值即可，本实现 byte 化处理）
     */
    fun aesBase64DecodeToString(
        str: String, key: String, transformation: String, iv: String,
    ): String? = runCatching {
        createSymmetricCrypto(transformation, key, iv).decryptStr(str)
    }.getOrNull()

    /** [aesBase64DecodeToString] 的 ByteArray 版本。 */
    fun aesBase64DecodeToByteArray(
        str: String, key: String, transformation: String, iv: String,
    ): ByteArray? = runCatching {
        createSymmetricCrypto(transformation, key, iv).decrypt(str)
    }.getOrNull()

    /** AES 加密原文 → base64 编码字符串。 */
    fun aesEncodeToBase64String(
        data: String, key: String, transformation: String, iv: String,
    ): String? = runCatching {
        createSymmetricCrypto(transformation, key, iv).encryptBase64(data)
    }.getOrNull()

    /** [aesEncodeToBase64String] 的 ByteArray 版本（base64 字符串再 .toByteArray()）。 */
    fun aesEncodeToBase64ByteArray(
        data: String, key: String, transformation: String, iv: String,
    ): ByteArray? = runCatching {
        createSymmetricCrypto(transformation, key, iv).encryptBase64(data).toByteArray()
    }.getOrNull()

    /** AES 加密原文 → 原始密文 ByteArray（不做 base64）。 */
    fun aesEncodeToByteArray(
        data: String, key: String, transformation: String, iv: String,
    ): ByteArray? = runCatching {
        createSymmetricCrypto(transformation, key, iv).encrypt(data)
    }.getOrNull()

    /**
     * AES 加密原文 → 原始密文字符串（用 platform default charset 解码 ByteArray）。
     * 注意：该接口在 Legado 命名上虽叫 `aesEncodeToString` 但语义和 `decryptStr` 一致（Legado
     * 这边有历史遗留——回调实际还是 decryptStr）。我们沿用 Legado 行为以保证 JS 规则兼容。
     */
    fun aesEncodeToString(
        data: String, key: String, transformation: String, iv: String,
    ): String? = runCatching {
        createSymmetricCrypto(transformation, key, iv).decryptStr(data)
    }.getOrNull()

    /** AES 已加密的非 base64 ByteArray → 解密为字符串。 */
    fun aesDecodeToString(
        data: String, key: String, transformation: String, iv: String,
    ): String? = runCatching {
        createSymmetricCrypto(transformation, key, iv).decryptStr(data)
    }.getOrNull()

    /** [aesDecodeToString] 的 ByteArray 版本。 */
    fun aesDecodeToByteArray(
        data: String, key: String, transformation: String, iv: String,
    ): ByteArray? = runCatching {
        createSymmetricCrypto(transformation, key, iv).decrypt(data)
    }.getOrNull()

    // ── Digest ──

    fun digestHex(data: String, algorithm: String): String {
        val md = MessageDigest.getInstance(algorithm)
        return md.digest(data.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    // ── Encoding ──

    fun encodeURI(str: String): String {
        return try { URLEncoder.encode(str, "UTF-8") } catch (_: Exception) { "" }
    }

    fun encodeURI(str: String, enc: String): String {
        return try { URLEncoder.encode(str, enc) } catch (_: Exception) { "" }
    }

    fun htmlFormat(str: String): String {
        return Jsoup.parse(StringEscapeUtils.unescapeHtml4(str)).text()
    }

    fun t2s(text: String): String {
        return runCatching { com.github.liuyueyi.quick.transfer.ChineseUtils.t2s(text) }.getOrDefault(text)
    }

    fun s2t(text: String): String {
        return runCatching { com.github.liuyueyi.quick.transfer.ChineseUtils.s2t(text) }.getOrDefault(text)
    }

    // ── String/Bytes conversion ──

    fun strToBytes(str: String): ByteArray = str.toByteArray(Charsets.UTF_8)
    fun strToBytes(str: String, charset: String): ByteArray = str.toByteArray(charset(charset))
    fun bytesToStr(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)
    fun bytesToStr(bytes: ByteArray, charset: String): String = String(bytes, charset(charset))

    // ── Time ──

    fun timeFormatUTC(time: Long, format: String, sh: Int): String? {
        val utc = SimpleTimeZone(sh, "UTC")
        return SimpleDateFormat(format, Locale.getDefault()).run {
            timeZone = utc
            format(Date(time))
        }
    }

    fun timeFormat(time: Long): String {
        return SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(time))
    }

    // ── Cookie ──

    fun getCookie(tag: String): String = CookieStore.getCookie(tag)

    fun getCookie(tag: String, key: String?): String {
        if (key == null) return CookieStore.getCookie(tag)
        val cookieMap = CookieStore.cookieToMap(CookieStore.getCookie(tag))
        return cookieMap[key] ?: ""
    }

    fun getWebViewUA(): String = DEFAULT_USER_AGENT

    // ── QueryTTF (字体反爬解密) ──

    fun queryTTF(data: Any?): QueryTTF? {
        try {
            val bytes: ByteArray = when (data) {
                is ByteArray -> data
                is String -> {
                    if (data.isBlank()) return null
                    Base64.decode(data, Base64.DEFAULT)
                }
                else -> return null
            }
            return QueryTTF(bytes)
        } catch (_: Exception) {
            return null
        }
    }

    fun queryBase64TTF(data: String?): QueryTTF? = queryTTF(data)

    /**
     * 替换字体反爬文字
     * @param text 原始文字
     * @param font1 网页使用的自定义字体
     * @param font2 标准字体（用于对照）
     */
    fun replaceFont(text: String, font1: QueryTTF?, font2: QueryTTF?): String {
        if (font1 == null || font2 == null) return text
        val sb = StringBuilder()
        for (char in text) {
            val code = char.code
            val glyf = font1.getGlyfByUnicode(code)
            if (glyf != null) {
                val newCode = font2.getUnicodeByGlyf(glyf)
                sb.append(if (newCode != 0) newCode.toChar() else char)
            } else {
                sb.append(char)
            }
        }
        return sb.toString()
    }

    // ── Device Info (called by some book sources) ──

    @Suppress("HardwareIds")
    fun androidId(): String {
        return try {
            val ctx = com.morealm.app.MoRealmApp.instance
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        } catch (_: Exception) { "" }
    }

    fun randomUUID(): String = UUID.randomUUID().toString()

    // ── WebView ──

    fun webView(html: String?, url: String?, js: String?): String? {
        return webView(html, url, js, false)
    }

    fun webView(html: String?, url: String?, js: String?, cacheFirst: Boolean): String? {
        val ctx = currentCoroutineContext()
        return runBlocking(ctx) {
            com.morealm.app.domain.http.BackstageWebView(
                url = url,
                html = html,
                javaScript = js,
            ).getStrResponse().body
        }
    }

    fun webViewGetSource(html: String?, url: String?, js: String?, sourceRegex: String): String? {
        return webViewGetSource(html, url, js, sourceRegex, false)
    }

    fun webViewGetSource(html: String?, url: String?, js: String?, sourceRegex: String, cacheFirst: Boolean): String? {
        val ctx = currentCoroutineContext()
        return runBlocking(ctx) {
            com.morealm.app.domain.http.BackstageWebView(
                url = url,
                html = html,
                javaScript = js,
                sourceRegex = sourceRegex,
                tag = getSource()?.bookSourceUrl,
            ).getStrResponse().body
        }
    }

    fun webViewGetOverrideUrl(html: String?, url: String?, js: String?, overrideUrlRegex: String): String? {
        return webViewGetOverrideUrl(html, url, js, overrideUrlRegex, false)
    }

    fun webViewGetOverrideUrl(html: String?, url: String?, js: String?, overrideUrlRegex: String, cacheFirst: Boolean): String? {
        val ctx = currentCoroutineContext()
        return runBlocking(ctx) {
            com.morealm.app.domain.http.BackstageWebView(
                url = url,
                html = html,
                javaScript = js,
                overrideUrlRegex = overrideUrlRegex,
                tag = getSource()?.bookSourceUrl,
            ).getStrResponse().body
        }
    }

    fun startBrowser(url: String) {
        startBrowser(url, url, null)
    }

    fun startBrowser(url: String, title: String) {
        startBrowser(url, title, null)
    }

    fun startBrowser(url: String, title: String, html: String?) {
        AppLog.warn(
            "JsExtensions",
            "startBrowser requested by source '${getSource()?.bookSourceName ?: ""}': $title $url",
        )
        if (html != null) {
            webView(html, url, null)
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
            "JsExtensions",
            "startBrowserAwait fallback for source '${getSource()?.bookSourceName ?: ""}': $title $url",
        )
        val body = if (html != null) {
            webView(html, url, null)
        } else {
            connect(url).body
        }
        return StrResponse(url, body)
    }

    fun openVideoPlayer(url: String, title: String) {
        openVideoPlayer(url, title, false)
    }

    fun openVideoPlayer(url: String, title: String, isFloat: Boolean) {
        AppLog.warn(
            "JsExtensions",
            "openVideoPlayer requested by source '${getSource()?.bookSourceName ?: ""}': $title $url",
        )
    }

    fun openUrl(url: String) {
        openUrl(url, null)
    }

    fun openUrl(url: String, mimeType: String? = null) {
        AppLog.warn(
            "JsExtensions",
            "openUrl requested by source '${getSource()?.bookSourceName ?: ""}': $url",
        )
    }

    fun importScript(path: String): String = readTxtFile(path)

    fun getVerificationCode(imageUrl: String): String = ""

    fun cacheFile(urlStr: String): String = cacheFile(urlStr, 0)

    fun cacheFile(urlStr: String, saveTime: Int): String {
        val key = md5Encode16(urlStr)
        CacheManager.get(key)?.takeIf { File(it).exists() }?.let { return it }
        val suffix = urlStr.substringBefore('?')
            .substringAfterLast('.', "cache")
            .takeIf { it.length in 1..8 }
            ?: "cache"
        val file = File(com.morealm.app.MoRealmApp.instance.cacheDir, "source_files/$key.$suffix")
        file.parentFile?.mkdirs()
        file.writeBytes(getByteArray(urlStr))
        CacheManager.put(key, file.absolutePath, saveTime)
        return file.absolutePath
    }

    fun downloadFile(url: String): String = cacheFile(url)

    fun downloadFile(content: String, url: String): String {
        val key = md5Encode16(url)
        val suffix = url.substringBefore('?')
            .substringAfterLast('.', "txt")
            .takeIf { it.length in 1..8 }
            ?: "txt"
        val file = File(com.morealm.app.MoRealmApp.instance.cacheDir, "source_files/$key.$suffix")
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
        CacheManager.put(key, file.absolutePath, 0)
        return file.absolutePath
    }

    fun getFile(path: String): File = File(path)

    fun readFile(path: String): ByteArray? = runCatching { File(path).readBytes() }.getOrNull()

    fun readTxtFile(path: String): String = readTxtFile(path, "UTF-8")

    fun readTxtFile(path: String, charsetName: String): String {
        return runCatching { File(path).readText(charset(charsetName)) }.getOrDefault("")
    }

    fun deleteFile(path: String): Boolean = runCatching { File(path).delete() }.getOrDefault(false)

    fun unzipFile(zipPath: String): String = zipPath
    fun un7zFile(zipPath: String): String = zipPath
    fun unrarFile(zipPath: String): String = zipPath
    fun unArchiveFile(zipPath: String): String = zipPath
    fun getTxtInFolder(path: String): String {
        return runCatching {
            File(path).walkTopDown()
                .filter { it.isFile && it.extension.equals("txt", true) }
                .joinToString("\n") { it.readText(Charsets.UTF_8) }
        }.getOrDefault("")
    }

    fun getZipStringContent(url: String, path: String): String = ""
    fun getZipStringContent(url: String, path: String, charsetName: String): String = ""
    fun getRarStringContent(url: String, path: String): String = ""
    fun getRarStringContent(url: String, path: String, charsetName: String): String = ""
    fun get7zStringContent(url: String, path: String): String = ""
    fun get7zStringContent(url: String, path: String, charsetName: String): String = ""
    fun getZipByteArrayContent(url: String, path: String): ByteArray? = null
    fun getRarByteArrayContent(url: String, path: String): ByteArray? = null
    fun get7zByteArrayContent(url: String, path: String): ByteArray? = null

    fun toNumChapter(s: String?): String? = s

    fun toURL(urlStr: String): JsURL = JsURL(urlStr)

    fun toURL(url: String, baseUrl: String? = null): JsURL = JsURL(url, baseUrl)

    fun getReadBookConfig(): String = "{}"
    fun getReadBookConfigMap(): Map<String, Any> = emptyMap()
    fun getThemeMode(): String = "followSystem"
    fun getThemeConfig(): String = "{}"
    fun getThemeConfigMap(): Map<String, Any?> = emptyMap()

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

    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
}

/**
 * 对称加密辅助类 - 供JS中 java.createSymmetricCrypto() 返回使用
 */
@Keep
@Suppress("unused")
class SymmetricCryptoHelper(
    private val transformation: String,
    private val key: ByteArray?,
    private val iv: ByteArray?,
) {
    private fun getCipher(mode: Int): Cipher {
        val cipher = Cipher.getInstance(transformation)
        val keySpec = SecretKeySpec(key, transformation.split("/")[0])
        if (iv != null && iv.isNotEmpty()) {
            cipher.init(mode, keySpec, IvParameterSpec(iv))
        } else {
            cipher.init(mode, keySpec)
        }
        return cipher
    }

    fun decrypt(data: String): ByteArray {
        val cipher = getCipher(Cipher.DECRYPT_MODE)
        return cipher.doFinal(Base64.decode(data, Base64.DEFAULT))
    }

    fun decryptStr(data: String): String {
        return String(decrypt(data))
    }

    fun encrypt(data: String): ByteArray {
        val cipher = getCipher(Cipher.ENCRYPT_MODE)
        return cipher.doFinal(data.toByteArray())
    }

    fun encryptBase64(data: String): String {
        return Base64.encodeToString(encrypt(data), Base64.NO_WRAP)
    }

    fun encryptHex(data: String): String {
        return encrypt(data).joinToString("") { "%02x".format(it) }
    }
}

@Keep
@Suppress("MemberVisibilityCanBePrivate")
class JsURL(url: String, baseUrl: String? = null) {
    val searchParams: Map<String, String>?
    val host: String
    val origin: String
    val pathname: String

    init {
        val parsed = if (!baseUrl.isNullOrEmpty()) {
            URL(URL(baseUrl), url)
        } else {
            URL(url)
        }
        host = parsed.host
        origin = if (parsed.port > 0) {
            "${parsed.protocol}://$host:${parsed.port}"
        } else {
            "${parsed.protocol}://$host"
        }
        pathname = parsed.path
        searchParams = parsed.query?.let { query ->
            val map = hashMapOf<String, String>()
            query.split("&").forEach { pair ->
                val parts = pair.split("=", limit = 2)
                if (parts.size == 2) {
                    map[parts[0]] = URLDecoder.decode(parts[1], "utf-8")
                }
            }
            map
        }
    }
}
