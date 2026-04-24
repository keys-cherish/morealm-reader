package com.morealm.app.domain.http

import androidx.annotation.Keep
import com.morealm.app.domain.db.CookieDao
import com.morealm.app.domain.entity.Cookie
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Cookie管理器，供书源JS中调用 cookie.getCookie/cookie.setCookie
 */
@Keep
@Suppress("unused")
object CookieStore {

    private lateinit var cookieDao: CookieDao

    fun init(dao: CookieDao) {
        cookieDao = dao
    }

    fun setCookie(url: String, cookie: String?) {
        val domain = getSubDomain(url)
        runBlocking(Dispatchers.IO) {
            cookieDao.insert(Cookie(domain, cookie ?: ""))
        }
    }

    fun replaceCookie(url: String, cookie: String) {
        if (url.isBlank() || cookie.isBlank()) return
        val oldCookie = getCookie(url)
        if (oldCookie.isBlank()) {
            setCookie(url, cookie)
        } else {
            val cookieMap = cookieToMap(oldCookie).toMutableMap()
            cookieMap.putAll(cookieToMap(cookie))
            setCookie(url, mapToCookie(cookieMap))
        }
    }

    fun getCookie(url: String): String {
        val domain = getSubDomain(url)
        return runBlocking(Dispatchers.IO) {
            cookieDao.get(domain)?.cookie ?: ""
        }
    }

    fun removeCookie(url: String) {
        val domain = getSubDomain(url)
        runBlocking(Dispatchers.IO) { cookieDao.delete(domain) }
    }

    fun cookieToMap(cookie: String): Map<String, String> {
        if (cookie.isBlank()) return emptyMap()
        val map = mutableMapOf<String, String>()
        cookie.split(";").forEach { pair ->
            val parts = pair.split("=", limit = 2)
            if (parts.size == 2) {
                val key = parts[0].trim()
                val value = parts[1].trim()
                if (key.isNotEmpty()) map[key] = value
            }
        }
        return map
    }

    fun mapToCookie(cookieMap: Map<String, String>?): String {
        if (cookieMap.isNullOrEmpty()) return ""
        return cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun getSubDomain(url: String): String {
        return try {
            val host = java.net.URI(url).host ?: url
            val parts = host.split(".")
            if (parts.size > 2) parts.takeLast(2).joinToString(".") else host
        } catch (_: Exception) {
            url
        }
    }
}
