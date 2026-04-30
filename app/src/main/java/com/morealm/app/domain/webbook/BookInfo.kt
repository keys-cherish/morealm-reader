package com.morealm.app.domain.webbook

import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.analyzeRule.AnalyzeRule
import com.morealm.app.domain.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import com.morealm.app.domain.entity.BookSource
import com.morealm.app.domain.entity.SearchBook
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * 获取书籍详情
 *
 * 防御策略：详情页任意字段解析失败都被吞，仅记 warn；保证详情页至少能"打开"，
 * 缺字段比白屏好。
 */
object BookInfo {

    private const val TAG = "BookInfo"

    suspend fun analyzeBookInfo(
        bookSource: BookSource,
        searchBook: SearchBook,
        baseUrl: String,
        redirectUrl: String,
        body: String?,
    ) {
        if (body.isNullOrBlank()) {
            AppLog.warn(TAG, "empty body for ${bookSource.bookSourceName}: $baseUrl")
            if (searchBook.tocUrl.isEmpty()) searchBook.tocUrl = baseUrl
            return
        }
        try {
            val analyzeRule = AnalyzeRule(searchBook, bookSource)
            analyzeRule.setContent(body).setBaseUrl(baseUrl)
            analyzeRule.setRedirectUrl(redirectUrl)
            analyzeRule.setCoroutineContext(coroutineContext)

            val infoRule = bookSource.getBookInfoRule()
            infoRule.init?.let {
                if (it.isNotBlank()) {
                    coroutineContext.ensureActive()
                    try {
                        analyzeRule.setContent(analyzeRule.getElement(it))
                    } catch (e: Exception) {
                        AppLog.warn(TAG, "init rule failed: ${e.message?.take(120)}")
                    }
                }
            }
            coroutineContext.ensureActive()
            try {
                analyzeRule.getString(infoRule.name).let {
                    if (it.isNotEmpty()) searchBook.name = it
                }
            } catch (e: Exception) {
                AppLog.warn(TAG, "name rule failed: ${e.message?.take(120)}")
            }
            coroutineContext.ensureActive()
            try {
                analyzeRule.getString(infoRule.author).let {
                    if (it.isNotEmpty()) searchBook.author = it
                }
            } catch (e: Exception) {
                AppLog.warn(TAG, "author rule failed: ${e.message?.take(120)}")
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
        } catch (e: Exception) {
            AppLog.warn(TAG, "analyzeBookInfo failed for ${bookSource.bookSourceName}: ${e.message?.take(160)}")
            if (searchBook.tocUrl.isEmpty()) searchBook.tocUrl = baseUrl
        }
    }
}
