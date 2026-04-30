package com.morealm.app.domain.webbook

import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.analyzeRule.AnalyzeRule
import com.morealm.app.domain.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import com.morealm.app.domain.analyzeRule.AnalyzeRule.Companion.setRuleData
import com.morealm.app.domain.analyzeRule.AnalyzeUrl
import com.morealm.app.domain.analyzeRule.RuleData
import com.morealm.app.domain.entity.BookSource
import com.morealm.app.domain.entity.SearchBook
import com.morealm.app.domain.entity.rule.BookListRule
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * 获取书籍列表（搜索/发现）
 */
object BookList {

    @Throws(Exception::class)
    suspend fun analyzeBookList(
        bookSource: BookSource,
        ruleData: RuleData,
        analyzeUrl: AnalyzeUrl,
        baseUrl: String,
        body: String?,
        isSearch: Boolean = true,
        /**
         * Legado-parity: when the search request is redirected to a detail-page-shaped URL
         * (single-result shortcut on many sites), the final response body IS the detail
         * page. We must:
         *   1. Use [baseUrl] (the redirect target) as bookUrl, not the search URL
         *   2. Stash body into searchBook.infoHtml so getBookInfoAwait can skip a refetch
         */
        isRedirect: Boolean = false,
    ): ArrayList<SearchBook> {
        body ?: throw Exception("获取网页内容失败: ${analyzeUrl.ruleUrl}")
        val bookList = ArrayList<SearchBook>()
        val analyzeRule = AnalyzeRule(ruleData, bookSource)
        analyzeRule.setContent(body).setBaseUrl(baseUrl)
        analyzeRule.setRedirectUrl(baseUrl)
        analyzeRule.setCoroutineContext(coroutineContext)

        // bookUrlPattern: 搜索结果链接匹配详情页，直接按详情页解析
        if (isSearch) bookSource.bookUrlPattern?.let { pattern ->
            coroutineContext.ensureActive()
            if (pattern.isNotBlank() && baseUrl.matches(pattern.toRegex())) {
                getInfoItem(bookSource, analyzeRule, analyzeUrl, body, baseUrl, ruleData.getVariable())
                    ?.let {
                        // detail-page response — body IS the detail page, stash for reuse
                        it.infoHtml = body
                        bookList.add(it)
                    }
                return bookList
            }
        }
        // Same as above, but triggered by HTTP redirect (server-side single-result jump)
        if (isSearch && isRedirect) {
            coroutineContext.ensureActive()
            getInfoItem(bookSource, analyzeRule, analyzeUrl, body, baseUrl, ruleData.getVariable())
                ?.let {
                    it.bookUrl = baseUrl
                    it.infoHtml = body
                    bookList.add(it)
                }
            if (bookList.isNotEmpty()) return bookList
            // fall through if detail-rule-as-search didn't yield a book; try list rules
        }

        val bookListRule: BookListRule = when {
            isSearch -> bookSource.getSearchRule()
            bookSource.getExploreRule().bookList.isNullOrBlank() -> bookSource.getSearchRule()
            else -> bookSource.getExploreRule()
        }
        var ruleList: String = bookListRule.bookList ?: ""
        var reverse = false
        if (ruleList.startsWith("-")) { reverse = true; ruleList = ruleList.substring(1) }
        if (ruleList.startsWith("+")) { ruleList = ruleList.substring(1) }

        val collections = try {
            analyzeRule.getElements(ruleList)
        } catch (e: Exception) {
            AppLog.warn(
                "BookList",
                "list rule failed for ${bookSource.bookSourceName}: ${e.message?.take(120)}"
            )
            emptyList()
        }
        coroutineContext.ensureActive()

        if (collections.isEmpty() && bookSource.bookUrlPattern.isNullOrEmpty()) {
            // 列表为空且无bookUrlPattern，尝试按详情页解析
            getInfoItem(bookSource, analyzeRule, analyzeUrl, body, baseUrl, ruleData.getVariable())
                ?.let { bookList.add(it) }
        } else if (collections.isNotEmpty()) {
            val ruleName = analyzeRule.splitSourceRule(bookListRule.name)
            val ruleBookUrl = analyzeRule.splitSourceRule(bookListRule.bookUrl)
            val ruleAuthor = analyzeRule.splitSourceRule(bookListRule.author)
            val ruleCoverUrl = analyzeRule.splitSourceRule(bookListRule.coverUrl)
            val ruleIntro = analyzeRule.splitSourceRule(bookListRule.intro)
            val ruleKind = analyzeRule.splitSourceRule(bookListRule.kind)
            val ruleLastChapter = analyzeRule.splitSourceRule(bookListRule.lastChapter)
            val ruleWordCount = analyzeRule.splitSourceRule(bookListRule.wordCount)

            for ((index, item) in collections.withIndex()) {
                coroutineContext.ensureActive()
                getSearchItem(
                    bookSource, analyzeRule, item, baseUrl, ruleData.getVariable(),
                    ruleName, ruleBookUrl, ruleAuthor, ruleCoverUrl,
                    ruleIntro, ruleKind, ruleLastChapter, ruleWordCount
                )?.let { searchBook ->
                    bookList.add(searchBook)
                }
            }
            val lh = LinkedHashSet(bookList)
            bookList.clear(); bookList.addAll(lh)
            if (reverse) bookList.reverse()
        }
        return bookList
    }

    /**
     * 按详情页规则解析单本书（bookUrlPattern匹配或列表为空时的回退）
     */
    @Throws(Exception::class)
    private suspend fun getInfoItem(
        bookSource: BookSource,
        analyzeRule: AnalyzeRule,
        analyzeUrl: AnalyzeUrl,
        body: String,
        baseUrl: String,
        variable: String?,
    ): SearchBook? {
        val searchBook = SearchBook(variable = variable)
        searchBook.bookUrl = AnalyzeRule.getAbsoluteURL(analyzeUrl.url, analyzeUrl.ruleUrl)
        searchBook.origin = bookSource.bookSourceUrl
        searchBook.originName = bookSource.bookSourceName
        searchBook.originOrder = bookSource.customOrder
        searchBook.type = bookSource.bookSourceType
        analyzeRule.setRuleData(searchBook)
        BookInfo.analyzeBookInfo(
            bookSource = bookSource,
            searchBook = searchBook,
            baseUrl = baseUrl,
            redirectUrl = baseUrl,
            body = body,
        )
        return if (searchBook.name.isNotBlank()) searchBook else null
    }
    @Throws(Exception::class)
    private suspend fun getSearchItem(
        bookSource: BookSource,
        analyzeRule: AnalyzeRule,
        item: Any,
        baseUrl: String,
        variable: String?,
        ruleName: List<AnalyzeRule.SourceRule>,
        ruleBookUrl: List<AnalyzeRule.SourceRule>,
        ruleAuthor: List<AnalyzeRule.SourceRule>,
        ruleCoverUrl: List<AnalyzeRule.SourceRule>,
        ruleIntro: List<AnalyzeRule.SourceRule>,
        ruleKind: List<AnalyzeRule.SourceRule>,
        ruleLastChapter: List<AnalyzeRule.SourceRule>,
        ruleWordCount: List<AnalyzeRule.SourceRule>,
    ): SearchBook? {
        val searchBook = SearchBook(variable = variable)
        searchBook.type = bookSource.bookSourceType
        searchBook.origin = bookSource.bookSourceUrl
        searchBook.originName = bookSource.bookSourceName
        searchBook.originOrder = bookSource.customOrder
        analyzeRule.setRuleData(searchBook)
        analyzeRule.setContent(item)
        coroutineContext.ensureActive()

        searchBook.name = try {
            analyzeRule.getString(ruleName).trim()
        } catch (e: Exception) {
            AppLog.warn("BookList", "name rule failed for ${bookSource.bookSourceName}: ${e.message?.take(120)}")
            ""
        }
        if (searchBook.name.isNotEmpty()) {
            coroutineContext.ensureActive()
            searchBook.author = try {
                analyzeRule.getString(ruleAuthor).trim()
            } catch (e: Exception) {
                AppLog.warn("BookList", "author rule failed for ${bookSource.bookSourceName}: ${e.message?.take(120)}")
                ""
            }
            coroutineContext.ensureActive()
            try { searchBook.kind = analyzeRule.getStringList(ruleKind)?.joinToString(",") } catch (_: Exception) {}
            coroutineContext.ensureActive()
            try { searchBook.wordCount = analyzeRule.getString(ruleWordCount) } catch (_: Exception) {}
            coroutineContext.ensureActive()
            try { searchBook.latestChapterTitle = analyzeRule.getString(ruleLastChapter) } catch (_: Exception) {}
            coroutineContext.ensureActive()
            try { searchBook.intro = analyzeRule.getString(ruleIntro) } catch (_: Exception) {}
            coroutineContext.ensureActive()
            try {
                analyzeRule.getString(ruleCoverUrl).let {
                    if (it.isNotEmpty()) searchBook.coverUrl = AnalyzeRule.getAbsoluteURL(baseUrl, it)
                }
            } catch (_: Exception) {}
            coroutineContext.ensureActive()
            searchBook.bookUrl = try {
                analyzeRule.getString(ruleBookUrl, isUrl = true)
            } catch (e: Exception) {
                AppLog.warn("BookList", "bookUrl rule failed for ${bookSource.bookSourceName}: ${e.message?.take(120)}")
                ""
            }
            if (searchBook.bookUrl.isEmpty()) searchBook.bookUrl = baseUrl
            return searchBook
        }
        return null
    }
}
