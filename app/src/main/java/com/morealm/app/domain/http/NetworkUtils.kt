package com.morealm.app.domain.http

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI

/**
 * Network helpers — Legado-parity utilities used by CookieManager / AnalyzeUrl.
 *
 * - [getSubDomain] returns the registrable two-level domain (e.g. example.com from www.a.example.com),
 *   which is the unit Cookie persistence is keyed on.
 * - [getBaseUrl] returns the scheme://host[:port] portion of a URL.
 * - [splitNotBlank] is a small string helper matching Legado's extension.
 */
object NetworkUtils {

    /**
     * Best-effort sub-domain extraction. We don't ship a Public Suffix List, so we use the
     * simple "last two labels" heuristic — this matches what Legado does in practice for the
     * vast majority of book-source domains (.com / .net / .cn / .org / etc.).
     *
     * Edge cases:
     * - IP addresses → returned as-is
     * - localhost or single-label hosts → returned as-is
     * - well-known multi-segment TLDs (.com.cn, .co.uk, .com.tw, .net.cn, .org.cn, .gov.cn,
     *   .ac.cn, .com.hk) → take the last 3 labels so we don't collapse to "com.cn"
     */
    fun getSubDomain(url: String): String {
        if (url.isBlank()) return url
        val host = try {
            val parsed = url.toHttpUrlOrNull()
            parsed?.host ?: URI(url).host ?: url
        } catch (_: Exception) {
            url
        } ?: return url

        // IP addresses: return as-is
        if (host.matches(Regex("^[0-9.:]+$")) || host.contains(':')) return host

        val parts = host.split(".")
        if (parts.size < 2) return host

        val twoSegmentTlds = setOf(
            "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn", "ac.cn",
            "co.uk", "ac.uk", "gov.uk", "co.jp", "co.kr",
            "com.tw", "com.hk", "com.au"
        )
        val lastTwo = parts.takeLast(2).joinToString(".")
        return if (parts.size > 2 && lastTwo in twoSegmentTlds) {
            parts.takeLast(3).joinToString(".")
        } else {
            parts.takeLast(2).joinToString(".")
        }
    }

    /** scheme://host[:port] from a URL string, or null if unparseable */
    fun getBaseUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            val parsed = url.toHttpUrlOrNull() ?: return null
            val portPart = if (parsed.port == parsed.scheme.let { if (it == "https") 443 else 80 }) "" else ":${parsed.port}"
            "${parsed.scheme}://${parsed.host}$portPart"
        } catch (_: Exception) {
            null
        }
    }

    /** Match Legado's String.splitNotBlank — split by a delimiter and drop blank segments */
    fun splitNotBlank(s: String, delimiter: String): List<String> =
        s.split(delimiter).map { it.trim() }.filter { it.isNotEmpty() }
}

/** Match Legado's `"a;b;".splitNotBlank(";")` extension form */
fun String.splitNotBlank(delimiter: String): List<String> =
    NetworkUtils.splitNotBlank(this, delimiter)
