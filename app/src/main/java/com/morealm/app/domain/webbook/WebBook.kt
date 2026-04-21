package com.morealm.app.domain.webbook

import com.morealm.app.domain.analyzeRule.AnalyzeUrl
import com.morealm.app.domain.analyzeRule.RuleData
import com.morealm.app.domain.entity.BookSource
import com.morealm.app.domain.entity.SearchBook
import com.morealm.app.domain.http.StrResponse
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
        var res = analyzeUrl.getStrResponseAwait()
        checkRedirect(bookSource, res)
        return BookList.analyzeBookList(
            bookSource = bookSource,
            ruleData = ruleData,
            analyzeUrl = analyzeUrl,
            baseUrl = res.url,
            body = res.body,
            isSearch = true,
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
     */
    suspend fun getBookInfoAwait(
        bookSource: BookSource,
        searchBook: SearchBook,
    ): SearchBook {
        val analyzeUrl = AnalyzeUrl(
            mUrl = searchBook.bookUrl,
            baseUrl = bookSource.bookSourceUrl,
            source = bookSource,
            ruleData = null,
            coroutineContext = coroutineContext
        )
        val res = analyzeUrl.getStrResponseAwait()
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
        val analyzeUrl = AnalyzeUrl(
            mUrl = tocUrl,
            baseUrl = bookUrl,
            source = bookSource,
            coroutineContext = coroutineContext
        )
        val res = analyzeUrl.getStrResponseAwait()
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
        if (bookSource.getContentRule().content.isNullOrEmpty()) return contentUrl
        val analyzeUrl = AnalyzeUrl(
            mUrl = contentUrl,
            source = bookSource,
            coroutineContext = coroutineContext
        )
        val res = analyzeUrl.getStrResponseAwait()
        checkRedirect(bookSource, res)
        return BookContent.analyzeContent(
            bookSource = bookSource,
            baseUrl = contentUrl,
            redirectUrl = res.url,
            body = res.body,
            nextChapterUrl = nextChapterUrl,
        )
    }

    private fun checkRedirect(bookSource: BookSource, response: StrResponse) {
        // 重定向检测（日志记录）
    }
}
