package com.morealm.app.domain.webbook

import com.morealm.app.domain.analyzeRule.AnalyzeRule
import com.morealm.app.domain.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import com.morealm.app.domain.analyzeRule.AnalyzeUrl
import com.morealm.app.domain.analyzeRule.RuleData
import com.morealm.app.domain.entity.BookSource
import com.morealm.app.domain.entity.SearchBook
import com.morealm.app.domain.http.StrResponse
import com.morealm.app.core.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * 书源调试器 — 逐步执行书源规则，返回每步的中间结果
 *
 * 用于开发者/高级用户调试书源规则是否正确。
 * 每一步（搜索→详情→目录→正文）都返回原始响应和解析结果。
 */
object SourceDebug {

    data class DebugStep(
        val name: String,
        val url: String = "",
        val rawResponse: String = "",
        val parsedResult: String = "",
        val success: Boolean = true,
        val error: String? = null,
        val timeMs: Long = 0,
    )

    /**
     * 完整调试流程：搜索 → 详情 → 目录 → 正文
     * @param onStep 每完成一步的回调（实时输出）
     */
    suspend fun debug(
        source: BookSource,
        keyword: String,
        onStep: (DebugStep) -> Unit = {},
    ): List<DebugStep> = withContext(Dispatchers.IO) {
        val steps = mutableListOf<DebugStep>()

        // Step 1: 搜索
        val searchStep = debugSearch(source, keyword)
        steps.add(searchStep)
        onStep(searchStep)
        if (!searchStep.success) return@withContext steps

        // 从搜索结果提取第一本书的URL
        val bookUrl = extractFirstBookUrl(searchStep.parsedResult)
        if (bookUrl.isNullOrBlank()) {
            val noUrl = DebugStep("详情", error = "搜索结果中未找到书籍URL", success = false)
            steps.add(noUrl)
            onStep(noUrl)
            return@withContext steps
        }

        // Step 2: 详情
        val infoStep = debugBookInfo(source, bookUrl)
        steps.add(infoStep)
        onStep(infoStep)
        if (!infoStep.success) return@withContext steps

        // 从详情结果提取目录URL
        val tocUrl = extractTocUrl(infoStep.parsedResult) ?: bookUrl

        // Step 3: 目录
        val tocStep = debugToc(source, bookUrl, tocUrl)
        steps.add(tocStep)
        onStep(tocStep)
        if (!tocStep.success) return@withContext steps

        // 从目录结果提取第一章URL
        val chapterUrl = extractFirstChapterUrl(tocStep.parsedResult)
        if (chapterUrl.isNullOrBlank()) {
            val noCh = DebugStep("正文", error = "目录中未找到章节URL", success = false)
            steps.add(noCh)
            onStep(noCh)
            return@withContext steps
        }

        // Step 4: 正文
        val contentStep = debugContent(source, chapterUrl)
        steps.add(contentStep)
        onStep(contentStep)

        steps
    }

    /**
     * 调试搜索步骤
     */
    suspend fun debugSearch(source: BookSource, keyword: String): DebugStep {
        val start = System.currentTimeMillis()
        return try {
            val searchUrl = source.searchUrl
            if (searchUrl.isNullOrBlank()) {
                return DebugStep("搜索", error = "searchUrl为空", success = false)
            }

            val ruleData = RuleData()
            val analyzeUrl = AnalyzeUrl(
                mUrl = searchUrl, key = keyword, page = 1,
                baseUrl = source.bookSourceUrl, source = source,
                ruleData = ruleData, coroutineContext = coroutineContext,
            )
            val res = analyzeUrl.getStrResponseAwait()
            val rawBody = res.body?.take(2000) ?: "(empty)"

            val books = BookList.analyzeBookList(
                bookSource = source, ruleData = ruleData, analyzeUrl = analyzeUrl,
                baseUrl = res.url, body = res.body, isSearch = true,
            )

            val parsed = books.joinToString("\n") { "《${it.name}》 ${it.author} | ${it.bookUrl}" }
            DebugStep(
                "搜索", url = analyzeUrl.url,
                rawResponse = rawBody, parsedResult = parsed,
                timeMs = System.currentTimeMillis() - start,
            )
        } catch (e: Exception) {
            DebugStep("搜索", error = e.message?.take(200), success = false,
                timeMs = System.currentTimeMillis() - start)
        }
    }

    /**
     * 调试详情步骤
     */
    suspend fun debugBookInfo(source: BookSource, bookUrl: String): DebugStep {
        val start = System.currentTimeMillis()
        return try {
            val analyzeUrl = AnalyzeUrl(
                mUrl = bookUrl, baseUrl = source.bookSourceUrl,
                source = source, coroutineContext = coroutineContext,
            )
            val res = analyzeUrl.getStrResponseAwait()
            val rawBody = res.body?.take(2000) ?: "(empty)"

            val searchBook = SearchBook()
            searchBook.bookUrl = bookUrl
            searchBook.origin = source.bookSourceUrl
            BookInfo.analyzeBookInfo(source, searchBook, bookUrl, res.url, res.body)

            val parsed = buildString {
                appendLine("书名: ${searchBook.name}")
                appendLine("作者: ${searchBook.author}")
                appendLine("简介: ${searchBook.intro?.take(100)}")
                appendLine("封面: ${searchBook.coverUrl}")
                appendLine("目录URL: ${searchBook.tocUrl}")
            }
            DebugStep(
                "详情", url = bookUrl,
                rawResponse = rawBody, parsedResult = parsed,
                timeMs = System.currentTimeMillis() - start,
            )
        } catch (e: Exception) {
            DebugStep("详情", url = bookUrl, error = e.message?.take(200), success = false,
                timeMs = System.currentTimeMillis() - start)
        }
    }

    /**
     * 调试目录步骤
     */
    suspend fun debugToc(source: BookSource, bookUrl: String, tocUrl: String): DebugStep {
        val start = System.currentTimeMillis()
        return try {
            val chapters = WebBook.getChapterListAwait(source, bookUrl, tocUrl)
            val parsed = buildString {
                appendLine("共 ${chapters.size} 章")
                chapters.take(10).forEachIndexed { i, ch ->
                    appendLine("${i + 1}. ${ch.title} | ${ch.url}")
                }
                if (chapters.size > 10) appendLine("... (省略 ${chapters.size - 10} 章)")
            }
            DebugStep(
                "目录", url = tocUrl, parsedResult = parsed,
                timeMs = System.currentTimeMillis() - start,
            )
        } catch (e: Exception) {
            DebugStep("目录", url = tocUrl, error = e.message?.take(200), success = false,
                timeMs = System.currentTimeMillis() - start)
        }
    }

    /**
     * 调试正文步骤
     */
    suspend fun debugContent(source: BookSource, chapterUrl: String): DebugStep {
        val start = System.currentTimeMillis()
        return try {
            val content = WebBook.getContentAwait(source, chapterUrl)
            val preview = content.take(1000)
            DebugStep(
                "正文", url = chapterUrl, parsedResult = preview,
                timeMs = System.currentTimeMillis() - start,
                success = content.isNotBlank(),
                error = if (content.isBlank()) "正文为空" else null,
            )
        } catch (e: Exception) {
            DebugStep("正文", url = chapterUrl, error = e.message?.take(200), success = false,
                timeMs = System.currentTimeMillis() - start)
        }
    }

    // ── Helpers: 从调试结果中提取URL ──

    private fun extractFirstBookUrl(parsed: String): String? {
        // 格式: 《书名》 作者 | bookUrl
        val line = parsed.lines().firstOrNull { it.contains("|") } ?: return null
        return line.substringAfterLast("|").trim().takeIf { it.isNotBlank() }
    }

    private fun extractTocUrl(parsed: String): String? {
        val line = parsed.lines().firstOrNull { it.startsWith("目录URL:") } ?: return null
        return line.substringAfter("目录URL:").trim().takeIf { it.isNotBlank() && it != "null" }
    }

    private fun extractFirstChapterUrl(parsed: String): String? {
        val line = parsed.lines().firstOrNull { it.matches(Regex("^\\d+\\..*\\|.*")) } ?: return null
        return line.substringAfterLast("|").trim().takeIf { it.isNotBlank() }
    }
}
