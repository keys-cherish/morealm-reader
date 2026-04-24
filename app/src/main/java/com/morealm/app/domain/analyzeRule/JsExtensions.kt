package com.morealm.app.domain.analyzeRule

import android.util.Base64
import android.provider.Settings
import androidx.annotation.Keep
import com.morealm.app.domain.http.CacheManager
import com.morealm.app.domain.http.CookieStore
import com.morealm.app.domain.http.StrResponse
import kotlinx.coroutines.runBlocking
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.SimpleTimeZone
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

    var sourceGetter: (() -> Any?)? = null
    var coroutineContextGetter: (() -> CoroutineContext)? = null
    var ruleDataGetter: (() -> RuleDataInterface?)? = null

    // ── Variable get/put (delegated to ruleData, matches AnalyzeUrl API) ──

    fun put(key: String, value: String): String {
        ruleDataGetter?.invoke()?.putVariable(key, value)
        return value
    }

    fun get(key: String): String {
        return ruleDataGetter?.invoke()?.getVariable(key)?.takeIf { it.isNotEmpty() } ?: ""
    }

    // ── HTTP ──

    fun ajax(url: Any): String? {
        val urlStr = if (url is List<*>) url.firstOrNull().toString() else url.toString()
        val ctx = coroutineContextGetter?.invoke() ?: EmptyCoroutineContext
        val analyzeUrl = AnalyzeUrl(urlStr, coroutineContext = ctx)
        return runCatching {
            runBlocking(ctx) { analyzeUrl.getStrResponseAwait().body }
        }.getOrElse { it.message }
    }

    /** Legado-compatible: fetch URL and return body string (used by many book sources) */
    fun getString(url: String): String? = ajax(url)

    fun getString(url: String, headers: Any?): String? {
        // headers param accepted for Legado compat but currently ignored (uses source headers)
        return ajax(url)
    }

    fun connect(urlStr: String): StrResponse {
        val ctx = coroutineContextGetter?.invoke() ?: EmptyCoroutineContext
        val analyzeUrl = AnalyzeUrl(urlStr, coroutineContext = ctx)
        return runCatching {
            runBlocking(ctx) { analyzeUrl.getStrResponseAwait() }
        }.getOrElse { StrResponse(urlStr, it.message ?: "") }
    }

    fun getStrResponse(urlStr: String): StrResponse = connect(urlStr)

    fun getByteArray(urlStr: String): ByteArray {
        val ctx = coroutineContextGetter?.invoke() ?: EmptyCoroutineContext
        val analyzeUrl = AnalyzeUrl(urlStr, coroutineContext = ctx)
        return runCatching {
            runBlocking(ctx) { analyzeUrl.getByteArrayAwait() }
        }.getOrElse { ByteArray(0) }
    }

    // ── Logging (called by book sources for debugging) ──

    fun log(msg: Any?): Any? {
        com.morealm.app.core.log.AppLog.debug("JsExtensions", msg.toString())
        return msg
    }

    fun logType(any: Any?) {
        com.morealm.app.core.log.AppLog.debug("JsExtensions", "type: ${any?.javaClass?.name}, value: $any")
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
        return str.toByteArray().joinToString("") { "%02x".format(it) }
    }

    // ── MD5 ──

    fun md5Encode(str: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(str.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
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

    // ── Digest ──

    fun digestHex(data: String, algorithm: String): String {
        val md = MessageDigest.getInstance(algorithm)
        return md.digest(data.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    // ── Encoding ──

    fun encodeURI(str: String): String {
        return try { URLEncoder.encode(str, "UTF-8") } catch (_: Exception) { "" }
    }

    fun encodeURI(str: String, enc: String): String {
        return try { URLEncoder.encode(str, enc) } catch (_: Exception) { "" }
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

    // ── WebView ──

    fun webView(html: String?, url: String?, js: String?): String? {
        val ctx = coroutineContextGetter?.invoke() ?: EmptyCoroutineContext
        return runBlocking(ctx) {
            com.morealm.app.domain.http.BackstageWebView(
                url = url,
                html = html,
                javaScript = js,
            ).getStrResponse().body
        }
    }
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
