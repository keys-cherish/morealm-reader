package com.morealm.app.domain.reader.runtime

import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.reader.scroll.ScrollChapterContent
import com.morealm.app.domain.render.pageanim.expandBackgroundOnlyScrollPage
import com.morealm.epub.render.ScrollChapterLayout
import com.morealm.epub.render.ScrollLayoutEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 章节内容 → 排版产物的唯一加载路径：fetch → ScrollLayoutEngine 排版 →
 * 背景专页展开。并发去重不在此处 —— 由 [ReaderWindowStore] 的
 * `Loading(requestId)` 门控（同 unit 只会 Started 一次）。
 */
class ArtifactLoader(
    private val loadChapterContent: suspend (Int) -> ScrollChapterContent?,
    private val engine: ScrollLayoutEngine,
) {
    /** 失败（含内容为空）返回 null；取消照常抛出。 */
    suspend fun load(chapterIndex: Int): ScrollChapterLayout? = try {
        val content = withContext(Dispatchers.IO) { loadChapterContent(chapterIndex) }
        if (content == null) {
            null
        } else {
            AppLog.info(
                "ArtifactLoader",
                // 不读 content.content：那是 lazy flatten，走结构化排版时本不必产生。
                "loaded idx=$chapterIndex blocks=${content.structuredContent?.blocks?.size ?: -1} " +
                    "plainLen=${content.plainContent?.length ?: -1}",
            )
            withContext(Dispatchers.Default) {
                content.structuredContent?.let { structured ->
                    val layout = engine.layoutStructuredChapter(
                        content.chapterIndex,
                        content.title,
                        structured,
                        // EPUB 的标题属于 XHTML 正文。目录标题只用于导航和页眉，不能
                        // 再生成一个视觉标题，否则含 h1 的页面会出现重复标题。
                        omitChapterTitleBlock = true,
                    )
                    // 背景专页属于 EPUB 内容语义，不属于某一种翻页动画。所有模式都
                    // 走同一展开逻辑，避免滚动能显示、平移/覆盖/仿真只剩空白页。
                    expandBackgroundOnlyScrollPage(
                        layout = layout,
                        content = structured,
                        chapterTitle = content.title,
                        pageWidth = engine.viewWidth,
                        resolveImageDimensions = engine.imageDimensionsResolver::resolve,
                    )
                } ?: engine.layoutChapter(content.chapterIndex, content.title, content.content)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        AppLog.warn("ArtifactLoader", "load FAILED idx=$chapterIndex: ${e.message}", e)
        null
    }
}
