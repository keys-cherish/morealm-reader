package com.morealm.app.domain.source

import com.morealm.app.domain.rule.RuleEngine
import com.morealm.app.core.log.AppLog
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Online book fetching engine.
 * Uses RuleEngine to parse content from book sources following Legado rule syntax.
 *
 * Pipeline: search → bookInfo → toc → content
 */
object WebBookEngine {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class BookInfo(
        val name: String, val author: String = "", val intro: String = "",
        val coverUrl: String? = null, val tocUrl: String? = null,
        val wordCount: String? = null,
    )

    data class Chapter(val title: String, val url: String)

    // ── Fetch book detail page ──

    fun fetchBookInfo(sourceJson: String, bookUrl: String, baseUrl: String): BookInfo? {
        val source = parseSource(sourceJson) ?: return null
        val rules = source.ruleBookInfo ?: return null
        val body = fetchUrl(bookUrl, source.header) ?: return null

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

    fun fetchToc(sourceJson: String, tocUrl: String, baseUrl: String): List<Chapter> {
        val source = parseSource(sourceJson) ?: return emptyList()
        val rules = source.ruleToc ?: return emptyList()
        val chapterListRule = rules.chapterList ?: return emptyList()

        val chapters = mutableListOf<Chapter>()
        var currentUrl: String? = tocUrl
        var page = 0

        while (currentUrl != null && page < 50) {
            val body = fetchUrl(currentUrl, source.header) ?: break
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

    fun fetchContent(sourceJson: String, contentUrl: String, baseUrl: String): String {
        val source = parseSource(sourceJson) ?: return ""
        val rules = source.ruleContent ?: return ""
        val contentRule = rules.content ?: return ""

        val parts = mutableListOf<String>()
        var currentUrl: String? = contentUrl
        var page = 0

        while (currentUrl != null && page < 20) {
            val body = fetchUrl(currentUrl, source.header) ?: break
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

    private fun parseSource(sourceJson: String): LegadoBookSource? = try {
        json.decodeFromString<LegadoBookSource>(sourceJson)
    } catch (_: Exception) { null }

    private fun fetchUrl(url: String, headerJson: String?): String? = try {
        val reqBuilder = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
        headerJson?.let {
            try {
                json.decodeFromString<Map<String, String>>(it).forEach { (k, v) -> reqBuilder.header(k, v) }
            } catch (_: Exception) {}
        }
        client.newCall(reqBuilder.build()).execute().body?.string()
    } catch (e: Exception) {
        AppLog.warn("WebBook", "Fetch failed: $url - ${e.message}")
        null
    }

    private fun String.resolveUrl(baseUrl: String): String = when {
        startsWith("http") -> this
        startsWith("//") -> "https:$this"
        startsWith("/") -> baseUrl.substringBefore("/", baseUrl).let {
            val scheme = if (baseUrl.startsWith("https")) "https" else "http"
            "$scheme://${java.net.URI(baseUrl).host}$this"
        }
        else -> baseUrl.substringBeforeLast("/") + "/$this"
    }
}
