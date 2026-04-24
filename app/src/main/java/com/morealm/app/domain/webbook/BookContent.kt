package com.morealm.app.domain.webbook

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
 */
object BookContent {

    @Throws(Exception::class)
    suspend fun analyzeContent(
        bookSource: BookSource,
        baseUrl: String,
        redirectUrl: String,
        body: String?,
        nextChapterUrl: String? = null,
    ): String {
        body ?: throw Exception("获取网页内容失败: $baseUrl")
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
                val analyzeUrl = AnalyzeUrl(mUrl = nextUrl, source = bookSource, coroutineContext = coroutineContext)
                val res = analyzeUrl.getStrResponseAwait()
                res.body?.let { nextBody ->
                    contentData = analyzeContentPage(nextUrl, res.url, nextBody, contentRule, bookSource, nextChapterUrl)
                    nextUrl = if (contentData.second.isNotEmpty()) contentData.second[0] else ""
                    contentList.add(contentData.first)
                }
            }
        }

        var contentStr = contentList.joinToString("\n")
        val replaceRegex = contentRule.replaceRegex
        if (!replaceRegex.isNullOrEmpty()) {
            contentStr = analyzeRule.getString(replaceRegex, contentStr)
        }
        return contentStr
    }

    @Throws(Exception::class)
    private suspend fun analyzeContentPage(
        baseUrl: String,
        redirectUrl: String,
        body: String,
        contentRule: ContentRule,
        bookSource: BookSource,
        nextChapterUrl: String?,
    ): Pair<String, List<String>> {
        val analyzeRule = AnalyzeRule(null, bookSource)
        analyzeRule.setContent(body, baseUrl)
        analyzeRule.setCoroutineContext(coroutineContext)
        analyzeRule.setRedirectUrl(redirectUrl)
        analyzeRule.setNextChapterUrl(nextChapterUrl)

        val nextUrlList = arrayListOf<String>()
        var content = analyzeRule.getString(contentRule.content, unescape = false)
        if (content.indexOf('&') > -1) {
            content = StringEscapeUtils.unescapeHtml4(content)
        }
        val nextUrlRule = contentRule.nextContentUrl
        if (!nextUrlRule.isNullOrEmpty()) {
            analyzeRule.getStringList(nextUrlRule, isUrl = true)?.let {
                nextUrlList.addAll(it)
            }
        }
        return Pair(content, nextUrlList)
    }
}
