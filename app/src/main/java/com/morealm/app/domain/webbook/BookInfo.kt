package com.morealm.app.domain.webbook

import com.morealm.app.domain.analyzeRule.AnalyzeRule
import com.morealm.app.domain.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import com.morealm.app.domain.entity.BookSource
import com.morealm.app.domain.entity.SearchBook
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * 获取书籍详情
 */
object BookInfo {

    @Throws(Exception::class)
    suspend fun analyzeBookInfo(
        bookSource: BookSource,
        searchBook: SearchBook,
        baseUrl: String,
        redirectUrl: String,
        body: String?,
    ) {
        body ?: throw Exception("获取网页内容失败: $baseUrl")
        val analyzeRule = AnalyzeRule(searchBook, bookSource)
        analyzeRule.setContent(body).setBaseUrl(baseUrl)
        analyzeRule.setRedirectUrl(redirectUrl)
        analyzeRule.setCoroutineContext(coroutineContext)

        val infoRule = bookSource.getBookInfoRule()
        infoRule.init?.let {
            if (it.isNotBlank()) {
                coroutineContext.ensureActive()
                analyzeRule.setContent(analyzeRule.getElement(it))
            }
        }
        coroutineContext.ensureActive()
        analyzeRule.getString(infoRule.name).let {
            if (it.isNotEmpty()) searchBook.name = it
        }
        coroutineContext.ensureActive()
        analyzeRule.getString(infoRule.author).let {
            if (it.isNotEmpty()) searchBook.author = it
        }
        coroutineContext.ensureActive()
        try {
            analyzeRule.getStringList(infoRule.kind)?.joinToString(",")?.let {
                if (it.isNotEmpty()) searchBook.kind = it
            }
        } catch (_: Exception) {}
        coroutineContext.ensureActive()
        try {
            analyzeRule.getString(infoRule.wordCount).let {
                if (it.isNotEmpty()) searchBook.wordCount = it
            }
        } catch (_: Exception) {}
        coroutineContext.ensureActive()
        try {
            analyzeRule.getString(infoRule.lastChapter).let {
                if (it.isNotEmpty()) searchBook.latestChapterTitle = it
            }
        } catch (_: Exception) {}
        coroutineContext.ensureActive()
        try {
            analyzeRule.getString(infoRule.intro).let {
                if (it.isNotEmpty()) searchBook.intro = it
            }
        } catch (_: Exception) {}
        coroutineContext.ensureActive()
        try {
            analyzeRule.getString(infoRule.coverUrl).let {
                if (it.isNotEmpty()) searchBook.coverUrl = AnalyzeRule.getAbsoluteURL(redirectUrl, it)
            }
        } catch (_: Exception) {}
        coroutineContext.ensureActive()
        try {
            searchBook.tocUrl = analyzeRule.getString(infoRule.tocUrl, isUrl = true)
        } catch (_: Exception) {
            coroutineContext.ensureActive()
            searchBook.tocUrl = baseUrl
        }
        if (searchBook.tocUrl.isEmpty()) searchBook.tocUrl = baseUrl
    }
}
