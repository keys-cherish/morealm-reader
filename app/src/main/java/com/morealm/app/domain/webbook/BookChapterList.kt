package com.morealm.app.domain.webbook

import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.analyzeRule.AnalyzeRule
import com.morealm.app.domain.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import com.morealm.app.domain.analyzeRule.AnalyzeUrl
import com.morealm.app.domain.entity.BookSource
import com.morealm.app.domain.entity.rule.TocRule
import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * 章节信息
 */
data class ChapterResult(
    val title: String,
    val url: String,
    val isVolume: Boolean = false,
    val isVip: Boolean = false,
    val isPay: Boolean = false,
    val tag: String? = null,
)

/**
 * 获取目录列表
 *
 * 防御策略：
 * - 单字段/单条目解析失败 → 跳过该字段/条目，不影响其他章节
 * - 翻页中某一页失败 → break 翻页循环，使用已抓到的章节
 * - 顶层任何未捕获异常 → 返回空列表，让 UI 显示"无章节"而非白屏
 */
object BookChapterList {

    private const val TAG = "BookChapterList"

    suspend fun analyzeChapterList(
        bookSource: BookSource,
        bookUrl: String,
        tocUrl: String,
        redirectUrl: String,
        body: String?,
    ): List<ChapterResult> {
        if (body.isNullOrBlank()) {
            AppLog.warn(TAG, "empty body for ${bookSource.bookSourceName}: $tocUrl")
            return emptyList()
        }
        return try {
            doAnalyze(bookSource, bookUrl, tocUrl, redirectUrl, body)
        } catch (e: Exception) {
            AppLog.warn(
                TAG,
                "analyzeChapterList failed for ${bookSource.bookSourceName}@$tocUrl: ${e.message?.take(160)}"
            )
            emptyList()
        }
    }

    private suspend fun doAnalyze(
        bookSource: BookSource,
        bookUrl: String,
        tocUrl: String,
        redirectUrl: String,
        body: String,
    ): List<ChapterResult> {
        val chapterList = ArrayList<ChapterResult>()
        val tocRule = bookSource.getTocRule()
        val nextUrlList = arrayListOf(redirectUrl)
        var reverse = false
        var listRule = tocRule.chapterList ?: ""
        if (listRule.startsWith("-")) { reverse = true; listRule = listRule.substring(1) }
        if (listRule.startsWith("+")) { listRule = listRule.substring(1) }

        var chapterData = analyzeChapterPage(bookUrl, tocUrl, redirectUrl, body, tocRule, listRule, bookSource)
        chapterList.addAll(chapterData.first)

        // 处理下一页
        when (chapterData.second.size) {
            0 -> Unit
            1 -> {
                var nextUrl = chapterData.second[0]
                while (nextUrl.isNotEmpty() && !nextUrlList.contains(nextUrl)) {
                    nextUrlList.add(nextUrl)
                    try {
                        val analyzeUrl = AnalyzeUrl(mUrl = nextUrl, source = bookSource, coroutineContext = coroutineContext)
                        val res = analyzeUrl.getStrResponseAwait()
                        val nextBody = res.body
                        if (nextBody.isNullOrBlank()) {
                            AppLog.warn(TAG, "next-toc-page empty body: $nextUrl")
                            break
                        }
                        chapterData = analyzeChapterPage(bookUrl, nextUrl, nextUrl, nextBody, tocRule, listRule, bookSource)
                        nextUrl = chapterData.second.firstOrNull() ?: ""
                        chapterList.addAll(chapterData.first)
                    } catch (e: Exception) {
                        AppLog.warn(TAG, "next-toc-page fetch failed: $nextUrl: ${e.message?.take(120)}")
                        break
                    }
                }
            }
        }

        if (!reverse) chapterList.reverse()
        val lh = LinkedHashSet(chapterList)
        val list = ArrayList(lh)
        list.reverse()

        // formatJs: 格式化章节标题
        val formatJs = tocRule.formatJs
        if (!formatJs.isNullOrBlank()) {
            try {
                org.mozilla.javascript.Context.enter().use {
                    val bindings = ScriptBindings()
                    bindings["gInt"] = 0
                    list.forEachIndexed { index, chapter ->
                        bindings["index"] = index + 1
                        bindings["title"] = chapter.title
                        runCatching {
                            RhinoScriptEngine.eval(formatJs, RhinoScriptEngine.getRuntimeScope(bindings))
                                ?.toString()?.let { newTitle ->
                                    if (newTitle.isNotBlank() && newTitle != chapter.title) {
                                        list[index] = chapter.copy(title = newTitle)
                                    }
                                }
                        }
                    }
                }
            } catch (e: Exception) {
                AppLog.warn(TAG, "formatJs failed for ${bookSource.bookSourceName}: ${e.message?.take(120)}")
            }
        }

        AppLog.info(TAG, "${bookSource.bookSourceName}: ${list.size} chapters parsed")
        return list
    }

    private suspend fun analyzeChapterPage(
        bookUrl: String,
        baseUrl: String,
        redirectUrl: String,
        body: String,
        tocRule: TocRule,
        listRule: String,
        bookSource: BookSource,
    ): Pair<List<ChapterResult>, List<String>> {
        val analyzeRule = AnalyzeRule(null, bookSource)
        analyzeRule.setContent(body).setBaseUrl(baseUrl)
        analyzeRule.setRedirectUrl(redirectUrl)
        analyzeRule.setCoroutineContext(coroutineContext)

        val chapterList = arrayListOf<ChapterResult>()
        val elements = try {
            analyzeRule.getElements(listRule)
        } catch (e: Exception) {
            AppLog.warn(TAG, "list rule failed: ${e.message?.take(120)}")
            emptyList()
        }

        val nextUrlList = arrayListOf<String>()
        val nextTocRule = tocRule.nextTocUrl
        if (!nextTocRule.isNullOrEmpty()) {
            try {
                analyzeRule.getStringList(nextTocRule, isUrl = true)?.let {
                    for (item in it) { if (item != redirectUrl) nextUrlList.add(item) }
                }
            } catch (e: Exception) {
                AppLog.warn(TAG, "nextTocUrl rule failed: ${e.message?.take(120)}")
            }
        }
        coroutineContext.ensureActive()

        if (elements.isNotEmpty()) {
            val nameRule = analyzeRule.splitSourceRule(tocRule.chapterName)
            val urlRule = analyzeRule.splitSourceRule(tocRule.chapterUrl)
            val vipRule = analyzeRule.splitSourceRule(tocRule.isVip)
            val payRule = analyzeRule.splitSourceRule(tocRule.isPay)
            val upTimeRule = analyzeRule.splitSourceRule(tocRule.updateTime)
            val isVolumeRule = analyzeRule.splitSourceRule(tocRule.isVolume)

            elements.forEachIndexed { index, item ->
                coroutineContext.ensureActive()
                try {
                    analyzeRule.setContent(item)
                    val title = try { analyzeRule.getString(nameRule) } catch (_: Exception) { "" }
                    if (title.isEmpty()) return@forEachIndexed
                    var url = try { analyzeRule.getString(urlRule) } catch (_: Exception) { "" }
                    val tag = try { analyzeRule.getString(upTimeRule) } catch (_: Exception) { "" }
                    val isVolume = try {
                        analyzeRule.getString(isVolumeRule).let { it == "true" || it == "1" }
                    } catch (_: Exception) { false }
                    if (url.isEmpty()) {
                        url = if (isVolume) title + index else baseUrl
                    }
                    val isVip = try {
                        analyzeRule.getString(vipRule).let { it == "true" || it == "1" }
                    } catch (_: Exception) { false }
                    val isPay = try {
                        analyzeRule.getString(payRule).let { it == "true" || it == "1" }
                    } catch (_: Exception) { false }
                    chapterList.add(
                        ChapterResult(
                            title = title,
                            url = getChapterAbsoluteUrl(redirectUrl, url, title, isVolume),
                            isVolume = isVolume,
                            isVip = isVip,
                            isPay = isPay,
                            tag = tag,
                        )
                    )
                } catch (e: Exception) {
                    AppLog.warn(TAG, "chapter[$index] parse failed: ${e.message?.take(120)}")
                }
            }
        }
        return Pair(chapterList, nextUrlList)
    }

    private fun getChapterAbsoluteUrl(
        baseUrl: String,
        url: String,
        title: String,
        isVolume: Boolean,
    ): String {
        if (url.startsWith(title) && isVolume) return baseUrl
        val urlMatcher = AnalyzeUrl.paramPattern.matcher(url)
        val urlBefore = if (urlMatcher.find()) url.substring(0, urlMatcher.start()) else url
        val absoluteUrlBefore = AnalyzeRule.getAbsoluteURL(baseUrl, urlBefore)
        return if (urlBefore.length == url.length) {
            absoluteUrlBefore
        } else {
            "$absoluteUrlBefore," + url.substring(urlMatcher.end())
        }
    }
}
