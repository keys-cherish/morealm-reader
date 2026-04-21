package com.morealm.app.domain.source

import com.morealm.app.domain.entity.BookSource
import com.morealm.app.domain.rule.RuleEngine
import com.morealm.app.core.log.AppLog
import kotlinx.serialization.json.Json

/**
 * Online book fetching engine.
 * Uses RuleEngine to parse content from book sources following Legado rule syntax.
 *
 * Pipeline: search → bookInfo → toc → content
 */
object WebBookEngine {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class BookInfo(
        val name: String, val author: String = "", val intro: String = "",
        val coverUrl: String? = null, val tocUrl: String? = null,
        val wordCount: String? = null,
    )

    data class Chapter(val title: String, val url: String)

    // ── Fetch book detail page ──

    fun fetchBookInfo(source: BookSource, bookUrl: String, baseUrl: String): BookInfo? {
        val rules = source.ruleBookInfo ?: return null
        val body = fetchUrl(bookUrl, source.getHeaderMap()) ?: return null

        val engine = RuleEngine()
        engine.setContent(body, bookUrl)
        return BookInfo(
            name = rules.name?.let { engine.getString(it) } ?: "",
            author = rules.author?.let { engine.getString(it) } ?: "",
            intro = rules.intro?.let { engine.getString(it) } ?: "",
            coverUrl = rules.coverUrl?.let { engine.getString(it) }?.resolveUrl(baseUrl),
            tocUrl = rules.tocUrl?.let { engine.getString(it) }?.resolveUrl(baseUrl),
            wordCount = rules.wordCount?.let { engine.getString(it) },
        )
    }

    // ── Fetch chapter list ──

    fun fetchToc(source: BookSource, tocUrl: String, baseUrl: String): List<Chapter> {
        val rules = source.ruleToc ?: return emptyList()
        val chapterListRule = rules.chapterList ?: return emptyList()

        val chapters = mutableListOf<Chapter>()
        var currentUrl: String? = tocUrl
        var page = 0

        while (currentUrl != null && page < 50) {
            val body = fetchUrl(currentUrl, source.getHeaderMap()) ?: break
            val engine = RuleEngine()
            engine.setContent(body, currentUrl)

            val elements = engine.getElements(chapterListRule)
            for (el in elements) {
                val child = engine.createChild(el)
                val title = rules.chapterName?.let { child.getString(it) }?.takeIf { it.isNotBlank() } ?: continue
                val url = rules.chapterUrl?.let { child.getString(it) }?.resolveUrl(baseUrl) ?: continue
                chapters.add(Chapter(title, url))
            }

            currentUrl = rules.nextTocUrl?.let { engine.getString(it) }?.takeIf { it.isNotBlank() }?.resolveUrl(baseUrl)
            page++
        }
        return chapters
    }

    // ── Fetch chapter content ──

    fun fetchContent(source: BookSource, contentUrl: String, baseUrl: String): String {
        val rules = source.ruleContent ?: return ""
        val contentRule = rules.content ?: return ""

        val parts = mutableListOf<String>()
        var currentUrl: String? = contentUrl
        var page = 0

        while (currentUrl != null && page < 20) {
            val body = fetchUrl(currentUrl, source.getHeaderMap()) ?: break
            val engine = RuleEngine()
            engine.setContent(body, currentUrl)

            val text = engine.getString(contentRule)
            if (text.isNotBlank()) parts.add(text)

            currentUrl = rules.nextContentUrl?.let { engine.getString(it) }?.takeIf { it.isNotBlank() }?.resolveUrl(baseUrl)
            page++
        }

        var result = parts.joinToString("\n")
        rules.replaceRegex?.takeIf { it.isNotBlank() }?.let { regex ->
            try { result = Regex(regex).replace(result, "") } catch (_: Exception) {}
        }
        return result
    }

    // ── Helpers ──

    private fun fetchUrl(url: String, headers: Map<String, String>): String? = try {
        val reqBuilder = okhttp3.Request.Builder().url(url)
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        com.morealm.app.domain.http.okHttpClient
            .newCall(reqBuilder.build()).execute().body?.string()
    } catch (e: Exception) {
        AppLog.warn("WebBook", "Fetch failed: $url - ${e.message}")
        null
    }

    private fun String.resolveUrl(baseUrl: String): String = when {
        startsWith("http") -> this
        startsWith("//") -> "https:$this"
        startsWith("/") -> {
            val scheme = if (baseUrl.startsWith("https")) "https" else "http"
            "$scheme://${java.net.URI(baseUrl).host}$this"
        }
        else -> baseUrl.substringBeforeLast("/") + "/$this"
    }
}
