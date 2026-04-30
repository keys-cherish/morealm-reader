package com.morealm.app.domain.webbook

import com.morealm.app.domain.analyzeRule.AnalyzeRule
import com.morealm.app.domain.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import com.morealm.app.domain.analyzeRule.AnalyzeUrl
import com.morealm.app.domain.analyzeRule.RuleData
import com.morealm.app.domain.entity.BookSource
import com.morealm.app.domain.entity.SearchBook
import com.morealm.app.domain.http.StrResponse
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * 网络书籍引擎 - 搜索/详情/目录/正文 的统一入口
 */
object WebBook {

    /**
     * 搜索书籍
     */
    suspend fun searchBookAwait(
        bookSource: BookSource,
        key: String,
        page: Int? = 1,
    ): ArrayList<SearchBook> {
        val searchUrl = bookSource.searchUrl
        if (searchUrl.isNullOrBlank()) throw Exception("搜索url为空")

        val ruleData = RuleData()
        val analyzeUrl = AnalyzeUrl(
            mUrl = searchUrl,
            key = key,
            page = page,
            baseUrl = bookSource.bookSourceUrl,
            source = bookSource,
            ruleData = ruleData,
            coroutineContext = coroutineContext
        )
        // Legado-parity: when network call throws, give loginCheckJs a chance to
        // handle the failure — sources commonly use loginCheckJs to detect 401/403
        // sessions and trigger a re-login dance. Without this fallback, any transient
        // 5xx kills the search instead of letting JS rewrite the response.
        val checkJs = bookSource.loginCheckJs
        var res = runCatching {
            val initial = analyzeUrl.getStrResponseAwait()
            if (!checkJs.isNullOrBlank()) {
                analyzeUrl.evalJS(checkJs, initial) as StrResponse
            } else {
                initial
            }
        }.getOrElse { throwable ->
            if (!checkJs.isNullOrBlank()) {
                val errResponse = analyzeUrl.getErrStrResponse(throwable)
                try {
                    val rewritten = analyzeUrl.evalJS(checkJs, errResponse) as StrResponse
                    // If JS couldn't recover (still 500), bubble the original throwable
                    if (rewritten.code() == 500) throw throwable
                    rewritten
                } catch (_: Throwable) {
                    throw throwable
                }
            } else {
                throw throwable
            }
        }
        checkRedirect(bookSource, res)
        // Legado-parity: detect HTTP redirect on the search request. Many sources
        // single-result-redirect to the detail page; we must propagate this flag so
        // BookList can reuse the body and set bookUrl to the final URL (not search URL).
        val redirected = res.raw?.priorResponse?.isRedirect == true
        return BookList.analyzeBookList(
            bookSource = bookSource,
            ruleData = ruleData,
            analyzeUrl = analyzeUrl,
            baseUrl = res.url,
            body = res.body,
            isSearch = true,
            isRedirect = redirected,
        )
    }

    /**
     * 发现书籍
     */
    suspend fun exploreBookAwait(
        bookSource: BookSource,
        url: String,
        page: Int? = 1,
    ): ArrayList<SearchBook> {
        val ruleData = RuleData()
        val analyzeUrl = AnalyzeUrl(
            mUrl = url,
            page = page,
            baseUrl = bookSource.bookSourceUrl,
            source = bookSource,
            ruleData = ruleData,
            coroutineContext = coroutineContext
        )
        var res = analyzeUrl.getStrResponseAwait()
        // 检测书源是否已登录
        bookSource.loginCheckJs?.let { checkJs ->
            if (checkJs.isNotBlank()) {
                res = analyzeUrl.evalJS(checkJs, res) as StrResponse
            }
        }
        checkRedirect(bookSource, res)
        return BookList.analyzeBookList(
            bookSource = bookSource,
            ruleData = ruleData,
            analyzeUrl = analyzeUrl,
            baseUrl = res.url,
            body = res.body,
            isSearch = false,
        )
    }

    /**
     * 获取书籍详情
     *
     * Legado-parity: when [searchBook.infoHtml] is non-empty (set by BookList during
     * search-redirect or bookUrlPattern match), reuse that body and skip the network
     * request entirely. This avoids:
     *   - one redundant network round-trip per book opened
     *   - the "second-fetch failure" that breaks ~10% of opens on flaky sources
     */
    suspend fun getBookInfoAwait(
        bookSource: BookSource,
        searchBook: SearchBook,
    ): SearchBook {
        searchBook.infoHtml?.takeIf { it.isNotBlank() }?.let { cachedBody ->
            BookInfo.analyzeBookInfo(
                bookSource = bookSource,
                searchBook = searchBook,
                baseUrl = searchBook.bookUrl,
                redirectUrl = searchBook.bookUrl,
                body = cachedBody,
            )
            // Drop the cached body once consumed — keeps SearchBook small in memory.
            searchBook.infoHtml = null
            return searchBook
        }
        val analyzeUrl = AnalyzeUrl(
            mUrl = searchBook.bookUrl,
            baseUrl = bookSource.bookSourceUrl,
            source = bookSource,
            ruleData = null,
            coroutineContext = coroutineContext
        )
        var res = analyzeUrl.getStrResponseAwait()
        // 检测书源是否已登录
        bookSource.loginCheckJs?.let { checkJs ->
            if (checkJs.isNotBlank()) {
                res = analyzeUrl.evalJS(checkJs, res) as StrResponse
            }
        }
        checkRedirect(bookSource, res)
        BookInfo.analyzeBookInfo(
            bookSource = bookSource,
            searchBook = searchBook,
            baseUrl = searchBook.bookUrl,
            redirectUrl = res.url,
            body = res.body,
        )
        return searchBook
    }

    /**
     * 获取目录
     */
    suspend fun getChapterListAwait(
        bookSource: BookSource,
        bookUrl: String,
        tocUrl: String,
    ): List<ChapterResult> {
        // preUpdateJs: 目录预更新脚本
        bookSource.getTocRule().preUpdateJs?.let { preJs ->
            if (preJs.isNotBlank()) {
                kotlin.runCatching {
                    AnalyzeRule(null, bookSource)
                        .setCoroutineContext(coroutineContext)
                        .evalJS(preJs)
                }
            }
        }
        val analyzeUrl = AnalyzeUrl(
            mUrl = tocUrl,
            baseUrl = bookUrl,
            source = bookSource,
            coroutineContext = coroutineContext
        )
        var res = analyzeUrl.getStrResponseAwait()
        // 检测书源是否已登录
        bookSource.loginCheckJs?.let { checkJs ->
            if (checkJs.isNotBlank()) {
                res = analyzeUrl.evalJS(checkJs, res) as StrResponse
            }
        }
        checkRedirect(bookSource, res)
        return BookChapterList.analyzeChapterList(
            bookSource = bookSource,
            bookUrl = bookUrl,
            tocUrl = tocUrl,
            redirectUrl = res.url,
            body = res.body,
        )
    }

    /**
     * 获取正文
     */
    suspend fun getContentAwait(
        bookSource: BookSource,
        contentUrl: String,
        nextChapterUrl: String? = null,
    ): String {
        val contentRule = bookSource.getContentRule()
        if (contentRule.content.isNullOrEmpty()) return contentUrl
        val requestUrl = AnalyzeRule.getAbsoluteURL(bookSource.bookSourceUrl, contentUrl)
        val analyzeUrl = AnalyzeUrl(
            mUrl = requestUrl,
            baseUrl = requestUrl,
            source = bookSource,
            coroutineContext = coroutineContext
        )
        var res = analyzeUrl.getStrResponseAwait(
            jsStr = contentRule.webJs,
            sourceRegex = contentRule.sourceRegex,
        )
        // 检测书源是否已登录
        bookSource.loginCheckJs?.let { checkJs ->
            if (checkJs.isNotBlank()) {
                res = analyzeUrl.evalJS(checkJs, res) as StrResponse
            }
        }
        checkRedirect(bookSource, res)
        return BookContent.analyzeContent(
            bookSource = bookSource,
            baseUrl = requestUrl,
            redirectUrl = res.url,
            body = res.body,
            nextChapterUrl = nextChapterUrl,
        )
    }

    /**
     * 精准搜索
     */
    suspend fun preciseSearchAwait(
        bookSource: BookSource,
        name: String,
        author: String,
    ): SearchBook? {
        coroutineContext.ensureActive()
        val results = searchBookAwait(bookSource, name)
        return results.firstOrNull { it.name == name && it.author == author }
    }

    private fun checkRedirect(bookSource: BookSource, response: StrResponse) {
        // 重定向检测（日志记录）
    }
}
