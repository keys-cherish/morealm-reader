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
 * 把 ResponseBody 解码成字符串。charset 优先级：
 *   1. 显式 [encode] 参数（调用方知道目标编码时强制使用）
 *   2. Response Content-Type 的 charset（server 主动声明时优先尊重）
 *   3. UTF-8 fallback（无任何信号时的合理默认）
 *
 * 注意：调用方应在已知书源 charset（如 GBK 站）时通过 [encode] 透传，否则若 server 没在
 * Content-Type 里声明 charset（典型老站），UTF-8 fallback 解码 GBK bytes 会得到中文乱码。
 */
fun ResponseBody.text(encode: String? = null): String {
    val responseBytes = bytes()
    encode?.let {
        return runCatching { String(responseBytes, Charset.forName(it)) }
            .getOrElse { String(responseBytes, Charsets.UTF_8) }
    }
    contentType()?.charset()?.let { charset ->
        return String(responseBytes, charset)
    }
    return String(responseBytes, Charsets.UTF_8)
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
