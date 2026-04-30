package com.morealm.app.domain.http

import com.morealm.app.core.log.AppLog
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response

/**
 * Cookie 自动持久化层 — Legado parity.
 *
 * 职责：
 * - [saveResponse]：把响应里的 Set-Cookie 自动持久化（持久 cookie 写 DB；session cookie 仅写内存）
 * - [loadRequest]：在请求出门前把已存的 cookie 合并进 Cookie 头
 * - [getSessionCookie]：从内存层取 session cookie（应用重启即失效）
 * - [mergeCookies]：把多个 cookie 字符串合并为单串
 *
 * 这两个方法被绑到 [okHttpClient] 的拦截器里，对所有走该客户端的请求生效。
 *
 * 这是修复**"登录后再次进入丢登录态"**的关键：登录页 200 OK 后服务器在响应头里下发的
 * Set-Cookie 之前没有被任何代码消费 → 离开登录页就丢。现在拦截器会自动读响应、写
 * CookieStore，下次进入相同 sub-domain 自动带上。
 */
object CookieManager {

    private const val TAG = "CookieManager"
    const val cookieJarHeader = "CookieJar"

    /**
     * 从响应头中提取 Set-Cookie 并按 persistent / session 分别保存。
     */
    fun saveResponse(response: Response) {
        try {
            val url = response.request.url
            val headers = response.headers
            saveCookiesFromHeaders(url, headers)
        } catch (e: Exception) {
            AppLog.warn(TAG, "saveResponse failed: ${e.message}")
        }
    }

    private fun saveCookiesFromHeaders(url: HttpUrl, headers: Headers) {
        val domain = NetworkUtils.getSubDomain(url.toString())
        val cookies = Cookie.parseAll(url, headers)

        val sessionCookie = cookies.filter { !it.persistent }.toCookieString()
        if (sessionCookie.isNotEmpty()) {
            updateSessionCookie(domain, sessionCookie)
        }

        val persistentCookie = cookies.filter { it.persistent }.toCookieString()
        if (persistentCookie.isNotEmpty()) {
            CookieStore.replaceCookie(domain, persistentCookie)
        }
    }

    /**
     * 把已存 cookie 合并到 request 的 Cookie 头里。
     * 已有 Cookie 头时合并而非覆盖（书源 JS 自定义 Header 优先）。
     */
    fun loadRequest(request: Request): Request {
        return try {
            val urlStr = request.url.toString()
            val stored = CookieStore.getCookie(urlStr)
            if (stored.isBlank()) return request

            val existing = request.header("Cookie")
            val merged = mergeCookies(existing, stored) ?: return request

            request.newBuilder()
                .header("Cookie", merged)
                .build()
        } catch (e: Exception) {
            AppLog.warn(TAG, "loadRequest failed for ${request.url}: ${e.message}")
            request
        }
    }

    fun getSessionCookie(domain: String): String? =
        CacheManager.getFromMemory("${domain}_session_cookie") as? String

    private fun updateSessionCookie(domain: String, cookies: String) {
        val existing = getSessionCookie(domain)
        if (existing.isNullOrEmpty()) {
            CacheManager.putMemory("${domain}_session_cookie", cookies)
            return
        }
        val merged = mergeCookies(existing, cookies) ?: return
        CacheManager.putMemory("${domain}_session_cookie", merged)
    }

    fun mergeCookies(vararg cookies: String?): String? {
        val map = mergeCookiesToMap(*cookies)
        if (map.isEmpty()) return null
        return CookieStore.mapToCookie(map)
    }

    fun mergeCookiesToMap(vararg cookies: String?): MutableMap<String, String> {
        val maps = cookies.filterNotNull().map { CookieStore.cookieToMap(it) }
        if (maps.isEmpty()) return mutableMapOf()
        return maps.fold(mutableMapOf()) { acc, m -> acc.apply { putAll(m) } }
    }

    /** 删除某 URL 下的单个 cookie key（同时清理 session 与 persistent 层） */
    fun removeCookie(url: String, key: String) {
        val domain = NetworkUtils.getSubDomain(url)

        // session
        val sessionMap = getSessionCookie(domain)?.let { CookieStore.cookieToMap(it) }?.toMutableMap()
        if (sessionMap != null) {
            sessionMap.remove(key)
            CacheManager.putMemory("${domain}_session_cookie", CookieStore.mapToCookie(sessionMap))
        }

        // persistent
        val persistent = CookieStore.getCookieNoSession(url)
        if (persistent.isNotEmpty()) {
            val map = CookieStore.cookieToMap(persistent).toMutableMap()
            map.remove(key)
            CookieStore.setCookie(url, CookieStore.mapToCookie(map))
        }
    }

    /** 把已持久化的 cookie 同步到 WebView（登录态在 WebView 里也能读到） */
    fun applyToWebView(url: String) {
        val baseUrl = NetworkUtils.getBaseUrl(url) ?: return
        val ck = CookieStore.getCookie(url)
        if (ck.isBlank()) return
        try {
            val webCookieManager = android.webkit.CookieManager.getInstance()
            ck.splitNotBlank(";").forEach { single ->
                webCookieManager.setCookie(baseUrl, single.trim())
            }
            webCookieManager.flush()
        } catch (e: Exception) {
            AppLog.warn(TAG, "applyToWebView failed: ${e.message}")
        }
    }

    private fun List<Cookie>.toCookieString(): String = buildString {
        this@toCookieString.forEachIndexed { i, c ->
            if (i > 0) append("; ")
            append(c.name).append('=').append(c.value)
        }
    }
}
