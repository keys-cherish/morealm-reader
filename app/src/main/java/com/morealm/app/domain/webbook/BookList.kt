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
    ): ArrayList<SearchBook> {
        body ?: throw Exception("获取网页内容失败: ${analyzeUrl.ruleUrl}")
        val bookList = ArrayList<SearchBook>()
        val analyzeRule = AnalyzeRule(ruleData, bookSource)
        analyzeRule.setContent(body).setBaseUrl(baseUrl)
        analyzeRule.setRedirectUrl(baseUrl)
        analyzeRule.setCoroutineContext(coroutineContext)

        val bookListRule: BookListRule = when {
            isSearch -> bookSource.getSearchRule()
            bookSource.getExploreRule().bookList.isNullOrBlank() -> bookSource.getSearchRule()
            else -> bookSource.getExploreRule()
        }
        var ruleList: String = bookListRule.bookList ?: ""
        var reverse = false
        if (ruleList.startsWith("-")) { reverse = true; ruleList = ruleList.substring(1) }
        if (ruleList.startsWith("+")) { ruleList = ruleList.substring(1) }

        val collections = analyzeRule.getElements(ruleList)
        coroutineContext.ensureActive()

        if (collections.isNotEmpty()) {
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
                    if (baseUrl == searchBook.bookUrl) { /* skip self-referencing */ }
                    bookList.add(searchBook)
                }
            }
            val lh = LinkedHashSet(bookList)
            bookList.clear(); bookList.addAll(lh)
            if (reverse) bookList.reverse()
        }
        return bookList
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

        searchBook.name = analyzeRule.getString(ruleName).trim()
        if (searchBook.name.isNotEmpty()) {
            coroutineContext.ensureActive()
            searchBook.author = analyzeRule.getString(ruleAuthor).trim()
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
            searchBook.bookUrl = analyzeRule.getString(ruleBookUrl, isUrl = true)
            if (searchBook.bookUrl.isEmpty()) searchBook.bookUrl = baseUrl
            return searchBook
        }
        return null
    }
}
