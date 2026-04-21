package com.morealm.app.domain.webbook

import com.morealm.app.domain.analyzeRule.AnalyzeRule
import com.morealm.app.domain.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import com.morealm.app.domain.analyzeRule.AnalyzeUrl
import com.morealm.app.domain.entity.BookSource
import com.morealm.app.domain.entity.rule.TocRule
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
 */
object BookChapterList {

    suspend fun analyzeChapterList(
        bookSource: BookSource,
        bookUrl: String,
        tocUrl: String,
        redirectUrl: String,
        body: String?,
    ): List<ChapterResult> {
        body ?: throw Exception("获取网页内容失败: $tocUrl")
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
                    val analyzeUrl = AnalyzeUrl(mUrl = nextUrl, source = bookSource, coroutineContext = coroutineContext)
                    val res = analyzeUrl.getStrResponseAwait()
                    res.body?.let { nextBody ->
                        chapterData = analyzeChapterPage(bookUrl, nextUrl, nextUrl, nextBody, tocRule, listRule, bookSource)
                        nextUrl = chapterData.second.firstOrNull() ?: ""
                        chapterList.addAll(chapterData.first)
                    }
                }
            }
        }

        if (!reverse) chapterList.reverse()
        val lh = LinkedHashSet(chapterList)
        val list = ArrayList(lh)
        list.reverse()
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
        val elements = analyzeRule.getElements(listRule)

        val nextUrlList = arrayListOf<String>()
        val nextTocRule = tocRule.nextTocUrl
        if (!nextTocRule.isNullOrEmpty()) {
            analyzeRule.getStringList(nextTocRule, isUrl = true)?.let {
                for (item in it) { if (item != redirectUrl) nextUrlList.add(item) }
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
                analyzeRule.setContent(item)
                val title = analyzeRule.getString(nameRule)
                var url = analyzeRule.getString(urlRule)
                val tag = analyzeRule.getString(upTimeRule)
                val isVolume = analyzeRule.getString(isVolumeRule).let { it == "true" || it == "1" }
                if (url.isEmpty()) {
                    url = if (isVolume) title + index else baseUrl
                }
                if (title.isNotEmpty()) {
                    val isVip = analyzeRule.getString(vipRule).let { it == "true" || it == "1" }
                    val isPay = analyzeRule.getString(payRule).let { it == "true" || it == "1" }
                    chapterList.add(ChapterResult(title, url, isVolume, isVip, isPay, tag))
                }
            }
        }
        return Pair(chapterList, nextUrlList)
    }
}
