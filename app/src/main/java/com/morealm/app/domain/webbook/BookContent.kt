package com.morealm.app.domain.webbook

import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.analyzeRule.AnalyzeRule
import com.morealm.app.domain.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import com.morealm.app.domain.analyzeRule.AnalyzeRule.Companion.setNextChapterUrl
import com.morealm.app.domain.analyzeRule.AnalyzeUrl
import com.morealm.app.domain.entity.BookSource
import com.morealm.app.domain.entity.rule.ContentRule
import kotlinx.coroutines.ensureActive
import org.apache.commons.text.StringEscapeUtils
import kotlin.coroutines.coroutineContext

/**
 * 获取正文内容
 *
 * 防御策略：
 * - 单页解析失败 → 返回空内容字符串，让其他页继续抓
 * - 翻页中某一页失败 → break 翻页循环，使用已抓到的内容
 * - 顶层任何未捕获异常 → 返回空字符串而非抛异常，UI 显示"加载失败"而非崩溃
 */
object BookContent {

    private const val TAG = "BookContent"

    suspend fun analyzeContent(
        bookSource: BookSource,
        baseUrl: String,
        redirectUrl: String,
        body: String?,
        nextChapterUrl: String? = null,
    ): String {
        if (body.isNullOrBlank()) {
            AppLog.warn(TAG, "empty body for ${bookSource.bookSourceName}: $baseUrl")
            return ""
        }
        return try {
            doAnalyze(bookSource, baseUrl, redirectUrl, body, nextChapterUrl)
        } catch (e: Exception) {
            AppLog.warn(
                TAG,
                "analyzeContent failed for ${bookSource.bookSourceName}@$baseUrl: ${e.message?.take(160)}"
            )
            ""
        }
    }

    private suspend fun doAnalyze(
        bookSource: BookSource,
        baseUrl: String,
        redirectUrl: String,
        body: String,
        nextChapterUrl: String?,
    ): String {
        val contentList = arrayListOf<String>()
        val nextUrlList = arrayListOf(redirectUrl)
        val contentRule = bookSource.getContentRule()
        val analyzeRule = AnalyzeRule(null, bookSource)
        analyzeRule.setContent(body, baseUrl)
        analyzeRule.setRedirectUrl(redirectUrl)
        analyzeRule.setCoroutineContext(coroutineContext)
        analyzeRule.setNextChapterUrl(nextChapterUrl)
        coroutineContext.ensureActive()

        var contentData = analyzeContentPage(baseUrl, redirectUrl, body, contentRule, bookSource, nextChapterUrl)
        contentList.add(contentData.first)

        if (contentData.second.size == 1) {
            var nextUrl = contentData.second[0]
            while (nextUrl.isNotEmpty() && !nextUrlList.contains(nextUrl)) {
                if (!nextChapterUrl.isNullOrEmpty() &&
                    AnalyzeRule.getAbsoluteURL(redirectUrl, nextUrl) ==
                    AnalyzeRule.getAbsoluteURL(redirectUrl, nextChapterUrl)
                ) break
                nextUrlList.add(nextUrl)
                coroutineContext.ensureActive()
                try {
                    val analyzeUrl = AnalyzeUrl(mUrl = nextUrl, source = bookSource, coroutineContext = coroutineContext)
                    val res = analyzeUrl.getStrResponseAwait(
                        jsStr = contentRule.webJs,
                        sourceRegex = contentRule.sourceRegex,
                    )
                    val nextBody = res.body
                    if (nextBody.isNullOrBlank()) {
                        AppLog.warn(TAG, "next-content-page empty body: $nextUrl")
                        break
                    }
                    contentData = analyzeContentPage(nextUrl, res.url, nextBody, contentRule, bookSource, nextChapterUrl)
                    nextUrl = if (contentData.second.isNotEmpty()) contentData.second[0] else ""
                    contentList.add(contentData.first)
                } catch (e: Exception) {
                    AppLog.warn(TAG, "next-content-page fetch failed: $nextUrl: ${e.message?.take(120)}")
                    break
                }
            }
        }

        var contentStr = contentList.joinToString("\n")
        val replaceRegex = contentRule.replaceRegex
        if (!replaceRegex.isNullOrEmpty()) {
            contentStr = try {
                analyzeRule.getString(replaceRegex, contentStr)
            } catch (e: Exception) {
                AppLog.warn(TAG, "replaceRegex failed: ${e.message?.take(120)}")
                contentStr
            }
        }
        return contentStr
    }

    private suspend fun analyzeContentPage(
        baseUrl: String,
        redirectUrl: String,
        body: String,
        contentRule: ContentRule,
        bookSource: BookSource,
        nextChapterUrl: String?,
    ): Pair<String, List<String>> {
        return try {
            val analyzeRule = AnalyzeRule(null, bookSource)
            analyzeRule.setContent(body, baseUrl)
            analyzeRule.setCoroutineContext(coroutineContext)
            analyzeRule.setRedirectUrl(redirectUrl)
            analyzeRule.setNextChapterUrl(nextChapterUrl)

            val nextUrlList = arrayListOf<String>()
            var content = try {
                analyzeRule.getString(contentRule.content, unescape = false)
            } catch (e: Exception) {
                AppLog.warn(TAG, "content rule failed: ${e.message?.take(120)}")
                ""
            }
            if (content.indexOf('&') > -1) {
                content = StringEscapeUtils.unescapeHtml4(content)
            }
            val nextUrlRule = contentRule.nextContentUrl
            if (!nextUrlRule.isNullOrEmpty()) {
                try {
                    analyzeRule.getStringList(nextUrlRule, isUrl = true)?.let {
                        nextUrlList.addAll(it)
                    }
                } catch (e: Exception) {
                    AppLog.warn(TAG, "nextContentUrl rule failed: ${e.message?.take(120)}")
                }
            }
            Pair(content, nextUrlList)
        } catch (e: Exception) {
            AppLog.warn(TAG, "analyzeContentPage failed: ${e.message?.take(120)}")
            Pair("", emptyList())
        }
    }
}
