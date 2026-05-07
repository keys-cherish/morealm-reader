package com.morealm.app.domain.webbook

import com.morealm.app.domain.analyzeRule.AnalyzeRule
import com.morealm.app.domain.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import com.morealm.app.domain.analyzeRule.AnalyzeUrl
import com.morealm.app.domain.analyzeRule.RuleData
import com.morealm.app.domain.entity.BookSource
import com.morealm.app.domain.entity.SearchBook
import com.morealm.app.domain.http.BackstageWebView
import com.morealm.app.domain.http.StrResponse
import com.morealm.app.core.log.AppLog
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
        val res = fetchWithLoginCheck(analyzeUrl, bookSource)
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
        var res = fetchWithLoginCheck(analyzeUrl, bookSource)
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
        var res = fetchWithLoginCheck(analyzeUrl, bookSource)
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
        var res = fetchWithLoginCheck(analyzeUrl, bookSource)
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
        var res = fetchWithLoginCheck(
            analyzeUrl = analyzeUrl,
            bookSource = bookSource,
            jsStr = contentRule.webJs,
            sourceRegex = contentRule.sourceRegex,
        )
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

    /**
     * Fetch with loginCheckJs error-recovery (Legado parity).
     *
     * Wraps [analyzeUrl.getStrResponseAwait] so that if the network call throws AND
     * the source has a loginCheckJs, the JS gets a chance to inspect the synthetic
     * error response (via [AnalyzeUrl.getErrStrResponse]) and rewrite it — book sources
     * use this to detect 401/403/expired-session and trigger a silent re-login.
     *
     * Behavior:
     *  - Success path: run loginCheckJs against the real response, return
     *  - Throw + no loginCheckJs: rethrow (caller handles)
     *  - Throw + has loginCheckJs: feed errResponse to JS; if JS returns 500-coded
     *    response (signal "I couldn't recover"), rethrow original; otherwise return JS-rewritten.
     */
    private suspend fun fetchWithLoginCheck(
        analyzeUrl: AnalyzeUrl,
        bookSource: BookSource,
        jsStr: String? = null,
        sourceRegex: String? = null,
    ): StrResponse {
        val checkJs = bookSource.loginCheckJs
        return runCatching {
            val raw = if (jsStr != null || sourceRegex != null) {
                analyzeUrl.getStrResponseAwait(jsStr = jsStr, sourceRegex = sourceRegex)
            } else {
                analyzeUrl.getStrResponseAwait()
            }
            val initial = bypassCloudflareIfBlocked(raw, analyzeUrl, bookSource)
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
                    if (rewritten.code() == 500) throw throwable
                    rewritten
                } catch (_: Throwable) {
                    throw throwable
                }
            } else {
                throw throwable
            }
        }
    }

    /**
     * Cloudflare 拦截自动 fallback —— OkHttp 直连被 CF 403 时，用 [BackstageWebView]
     * 拿浏览器壳内的最终 HTML（WebView 会通过 cf JS challenge 拿 cf_clearance cookie，
     * 之后请求自然放行）。仅当满足下列**全部**条件时触发，避免误伤普通 403：
     *   - 响应 code == 403
     *   - header 含 `cf-ray`（CF 边缘节点必带）
     *   - bookSourceType == 0（普通网页源；漫画 / 音频源结构不同不掺和）
     *   - analyzeUrl.url 非空（构造时已解析出最终 URL）
     *
     * 失败（WebView 加载超时 / 仍然空）时**返回原 403 response**，让上层正常报错；
     * 不抛异常，避免把"CF 被拦"放大成搜索路径整体崩溃。
     *
     * 历史背景：飘天网（piaotia.com）等 CF 保护站点，OkHttp 任何 UA 都拿 403；
     * Legado 用户能用是因为已通过浏览器拿过 cf_clearance。MoRealm 自动 fallback
     * 让用户首次就能搜出书。
     */
    private suspend fun bypassCloudflareIfBlocked(
        response: StrResponse,
        analyzeUrl: AnalyzeUrl,
        bookSource: BookSource,
    ): StrResponse {
        if (response.code() != 403) return response
        if (response.header("cf-ray").isNullOrBlank()) return response
        if (bookSource.bookSourceType != 0) return response
        val targetUrl = analyzeUrl.url.ifBlank { return response }

        AppLog.info(
            "WebBook",
            "CF block detected source='${bookSource.bookSourceName}' code=403" +
                " cf-ray=${response.header("cf-ray")}; fallback BackstageWebView url=$targetUrl",
        )
        return runCatching {
            BackstageWebView(
                url = targetUrl,
                headerMap = analyzeUrl.headerMap.takeIf { it.isNotEmpty() },
                persistCookie = true,
            ).getStrResponse()
        }.onFailure {
            AppLog.warn(
                "WebBook",
                "CF fallback BackstageWebView failed for '${bookSource.bookSourceName}': ${it.message}",
            )
        }.getOrDefault(response)
    }
}
