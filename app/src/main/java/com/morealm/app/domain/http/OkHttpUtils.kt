package com.morealm.app.domain.http

import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.http.SSLHelper.trustAllSSL
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionSpec
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 全局 OkHttpClient
 */
val okHttpClient: OkHttpClient by lazy {
    val specs = arrayListOf(
        ConnectionSpec.MODERN_TLS,
        ConnectionSpec.COMPATIBLE_TLS,
        ConnectionSpec.CLEARTEXT
    )
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionSpecs(specs)
        .followRedirects(true)
        .followSslRedirects(true)
        // Trust self-signed / expired certs — many small Chinese book-source sites ship them.
        // Without this, "Chain validation failed" / "Trust anchor not found" kills ~30% of sources.
        .trustAllSSL()
        .addInterceptor { chain ->
            try {
                val request = chain.request()
                val builder = request.newBuilder()
                if (request.header("User-Agent") == null) {
                    builder.addHeader(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                    )
                }
                builder.addHeader("Keep-Alive", "300")
                builder.addHeader("Connection", "Keep-Alive")
                builder.addHeader("Cache-Control", "no-cache")
                chain.proceed(builder.build())
            } catch (e: IOException) {
                throw e
            } catch (e: Throwable) {
                AppLog.error("OkHttp", "Unexpected OkHttp interceptor error", e)
                throw IOException(e)
            }
        }
        // Auto cookie persistence: load stored cookies into request, save Set-Cookie from response.
        // This is what fixes "login lost after re-entering". Skip when caller opts out via
        // the `${CookieManager.cookieJarHeader}: false` header (e.g. cover image fetch).
        .addInterceptor { chain ->
            val original = chain.request()
            val optOut = original.header(CookieManager.cookieJarHeader) == "false"
            val request = if (optOut) {
                original.newBuilder().removeHeader(CookieManager.cookieJarHeader).build()
            } else {
                CookieManager.loadRequest(original)
            }
            val response = chain.proceed(request)
            if (!optOut) {
                CookieManager.saveResponse(response)
            }
            response
        }
        .build()
        .apply { installDispatcherExceptionLogger("OkHttp") }
}

fun OkHttpClient.Builder.addExceptionLoggingInterceptor(tag: String): OkHttpClient.Builder =
    addInterceptor { chain ->
        try {
            chain.proceed(chain.request())
        } catch (e: IOException) {
            throw e
        } catch (e: Throwable) {
            AppLog.error(tag, "Unexpected OkHttp interceptor error", e)
            throw IOException(e)
        }
    }

fun OkHttpClient.installDispatcherExceptionLogger(tag: String) {
    val executor = dispatcher.executorService as? ThreadPoolExecutor ?: return
    val index = AtomicInteger(1)
    executor.threadFactory = ThreadFactory { runnable ->
        Thread(runnable, "$tag Dispatcher-${index.getAndIncrement()}").apply {
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { thread, throwable ->
                AppLog.logThreadException(tag, thread, throwable)
            }
        }
    }
}

suspend fun OkHttpClient.newCallResponse(
    retry: Int = 0,
    builder: Request.Builder.() -> Unit
): Response {
    val requestBuilder = Request.Builder()
    requestBuilder.apply(builder)
    var response: Response? = null
    for (i in 0..retry) {
        response = newCall(requestBuilder.build()).await()
        if (response.isSuccessful) {
            return response
        }
    }
    return response!!
}

suspend fun OkHttpClient.newCallStrResponse(
    retry: Int = 0,
    charset: String? = null,
    builder: Request.Builder.() -> Unit
): StrResponse {
    return newCallResponse(retry, builder).let {
        StrResponse(it, it.body?.text(charset) ?: "")
    }
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { block ->
    block.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            block.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            block.resume(response)
        }
    })
}

/**
 * 把 ResponseBody 解码成字符串。charset 优先级（与 Legado parity）：
 *   1. 显式 [encode] 参数（调用方知道目标编码时强制使用——书源 searchUrl 等 option 里的
 *      `"charset": "gbk"` 沿此路径透传）
 *   2. Response Content-Type 的 charset（server 主动声明时尊重）
 *   3. HTML body sniff：`<meta charset="...">` / `<meta http-equiv="Content-Type" content="...charset=...">`
 *      —— 绝大多数老站不在 Content-Type 头里写 charset，但 HTML head 会声明
 *   4. 字节 heuristic：高位字节序列符合 GBK 双字节模式 → GBK；否则 UTF-8
 *
 * 为什么 step 3+4 不可省：书源作者通常只在 searchUrl option 里写 charset，章节正文 URL
 * 没有 option JSON，charset 不透传；如果服务端响应 Content-Type 又不带 charset（典型老
 * GBK 站），UTF-8 fallback 解码 GBK bytes → 中文乱码（图片中 `�` + 拉丁字符就是 mojibake
 * 签名）。Legado 同等位置走 EncodingDetect sniff，本实现用更轻量的 meta + byte heuristic
 * 达到同等覆盖。
 */
fun ResponseBody.text(encode: String? = null): String {
    val responseBytes = bytes()
    encode?.let {
        AppLog.debug("HttpCharset", "decode via explicit encode='$it' bytes=${responseBytes.size}")
        return runCatching { String(responseBytes, Charset.forName(it)) }
            .getOrElse { String(responseBytes, Charsets.UTF_8) }
    }
    contentType()?.charset()?.let { charset ->
        AppLog.debug("HttpCharset", "decode via Content-Type charset=$charset bytes=${responseBytes.size}")
        return String(responseBytes, charset)
    }
    sniffHtmlCharset(responseBytes)?.let { sniffed ->
        AppLog.debug("HttpCharset", "decode via meta sniff charset=$sniffed bytes=${responseBytes.size}")
        return runCatching { String(responseBytes, Charset.forName(sniffed)) }
            .getOrElse { String(responseBytes, Charsets.UTF_8) }
    }
    if (looksLikeGbk(responseBytes)) {
        AppLog.debug("HttpCharset", "decode via byte heuristic=GBK bytes=${responseBytes.size}")
        return runCatching { String(responseBytes, Charset.forName("GBK")) }
            .getOrElse { String(responseBytes, Charsets.UTF_8) }
    }
    AppLog.debug("HttpCharset", "decode via UTF-8 fallback bytes=${responseBytes.size}")
    return String(responseBytes, Charsets.UTF_8)
}

/**
 * Sniff HTML head 前 4 KB（charset 声明通常在 `<head>` 顶部），按 ASCII 解后正则匹配。
 *
 * 命中两种常见声明：
 *   - HTML5：`<meta charset="gbk">`
 *   - HTML4：`<meta http-equiv="Content-Type" content="text/html; charset=gbk">`
 *
 * Why 4 KB：head 通常 < 2 KB；4 KB 给足边距同时控制扫描成本。bytes 先按 ISO-8859-1
 * 解（单字节 1:1）确保 ASCII 字符位置不偏移，正则只在 ASCII 范围有效。
 */
private val META_CHARSET_REGEX = Regex(
    """<meta[^>]+charset\s*=\s*["']?\s*([A-Za-z0-9_\-]+)""",
    RegexOption.IGNORE_CASE,
)

private fun sniffHtmlCharset(bytes: ByteArray): String? {
    val scanLen = minOf(bytes.size, 4096)
    if (scanLen <= 0) return null
    val ascii = String(bytes, 0, scanLen, Charsets.ISO_8859_1)
    val match = META_CHARSET_REGEX.find(ascii) ?: return null
    val name = match.groupValues[1].trim()
    return name.takeIf { it.isNotEmpty() && runCatching { Charset.forName(it) }.isSuccess }
}

/**
 * Last-resort heuristic：GBK 双字节高位匹配。
 *
 * GBK 双字节：lead byte 0x81-0xFE + trail byte 0x40-0xFE（除 0x7F）。统计样本中 GBK 对
 * 数比例 > 5% 视为 GBK（与 LocalBookParser.looksLikeGbk 阈值一致）。仅当 step 1-3 都
 * miss 时使用——典型场景：服务端没声明 charset，HTML 也没 meta charset 声明。
 *
 * 扫描上限 8 KB 控制开销：足够区分 UTF-8（高位字节有强约束）与 GBK（约束宽松）。
 */
private fun looksLikeGbk(bytes: ByteArray): Boolean {
    val length = minOf(bytes.size, 8192)
    if (length < 4) return false
    var i = 0
    var gbkPairs = 0
    while (i < length - 1) {
        val b = bytes[i].toInt() and 0xFF
        if (b in 0x81..0xFE) {
            val b2 = bytes[i + 1].toInt() and 0xFF
            if (b2 in 0x40..0xFE && b2 != 0x7F) {
                gbkPairs++
                i += 2
                continue
            }
        }
        i++
    }
    return gbkPairs > length / 20
}

fun Request.Builder.addHeaders(headers: Map<String, String>) {
    headers.forEach { addHeader(it.key, it.value) }
}

fun Request.Builder.get(url: String, encodedQuery: String?) {
    val httpBuilder = url.toHttpUrl().newBuilder()
    httpBuilder.encodedQuery(encodedQuery)
    url(httpBuilder.build())
}

private val formContentType = "application/x-www-form-urlencoded".toMediaType()

fun Request.Builder.postForm(encodedForm: String) {
    post(encodedForm.toRequestBody(formContentType))
}

fun Request.Builder.postForm(form: Map<String, String>, encoded: Boolean = false) {
    val formBody = FormBody.Builder()
    form.forEach {
        if (encoded) formBody.addEncoded(it.key, it.value)
        else formBody.add(it.key, it.value)
    }
    post(formBody.build())
}

fun Request.Builder.postJson(json: String?) {
    json?.let {
        val requestBody = json.toRequestBody("application/json; charset=UTF-8".toMediaType())
        post(requestBody)
    }
}

fun Request.Builder.postMultipart(type: String?, bodyMap: Map<String, Any>) {
    val builder = okhttp3.MultipartBody.Builder()
        .setType(okhttp3.MultipartBody.FORM)
    bodyMap.forEach { (key, value) ->
        when (value) {
            is Map<*, *> -> {
                val fileName = value["fileName"]?.toString() ?: key
                val contentType = value["contentType"]?.toString() ?: "application/octet-stream"
                val bytes = when (val body = value["body"]) {
                    is ByteArray -> body
                    is String -> body.toByteArray()
                    else -> value.toString().toByteArray()
                }
                builder.addFormDataPart(
                    key, fileName,
                    bytes.toRequestBody(contentType.toMediaType())
                )
            }
            else -> builder.addFormDataPart(key, value.toString())
        }
    }
    post(builder.build())
}

suspend fun OkHttpClient.newCallByteArrayResponse(
    retry: Int = 0,
    builder: Request.Builder.() -> Unit
): ByteArray {
    val response = newCallResponse(retry, builder)
    return response.body?.bytes() ?: ByteArray(0)
}
