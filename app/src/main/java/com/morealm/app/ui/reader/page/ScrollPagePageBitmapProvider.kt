package com.morealm.app.ui.reader.page

import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.TextPaint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.entity.Highlight
import com.morealm.epub.render.ScrollChapterLayout
import com.morealm.app.domain.render.layout.ScrollHighlightProjector
import com.morealm.epub.render.ScrollPage
import com.morealm.app.ui.reader.renderer.RevealHighlight
import com.morealm.app.ui.reader.renderer.scroll.PageInfoBarSpec
import com.morealm.app.ui.reader.renderer.scroll.PageInfoCanvasDrawer
import com.morealm.app.ui.reader.renderer.scroll.PageInfoSnapshotProvider
import com.morealm.app.ui.reader.renderer.scroll.ScrollCanvasReaderState
import com.morealm.app.ui.reader.renderer.scroll.drawScrollPageOnCanvas

/**
 * 把 page-level [ScrollPage] 离屏渲染成 [Bitmap] 的工具，专为 SIMULATION 翻页路径设计
 * （[com.morealm.app.ui.reader.page.animhorizontal.SimulationPageTransition] 内部消费）。
 *
 * 不实现 [PageBitmapProvider] 接口 —— 该接口按 `displayIndex` 单章 0..pageCount 语义寻址，
 * 不适合 SIMULATION 这种 prev/cur/next 三块离屏 bitmap 的跨章场景。本类直接接受
 * [ScrollPage] 实例（caller 用 [com.morealm.app.domain.render.layout.ScrollPageFactory.prevPage]
 * / `curPage` / `nextPage` 拿到，已经天然跨章），按 page.chapterIndex 路由到
 * prev/cur/next [ScrollChapterLayout]，使用各章自己的 viewWidth / paddingLeft / 高亮 /
 * 书签 / chapter bg image 渲染。
 *
 * 与 [com.morealm.app.ui.reader.page.animhorizontal.NonePageTransition] 等
 * Compose Canvas 路径的渲染结果**像素级一致**：共用同一个底层 [drawScrollPageOnCanvas]。
 *
 * 调用契约：caller 在内容版本变化时（主题 / 字号 / chapter swap / 高亮增删）应该 remember
 * 出新实例，让 [System.identityHashCode] 不同 → [com.morealm.app.ui.reader.page.animhorizontal.SimulationPageTransition]
 * 内 idle bitmap dedupe key 变化 → 重渲染。
 */
class ScrollPagePageBitmapProvider(
    /**
     * 三块 layout 数据源 —— SIMULATION 跨章翻页期间需要同时持有 prev/cur/next 三章 layout
     * 才能给 SimulationReadView setBitmaps 提供完整的对侧页位图。
     */
    private val state: ScrollCanvasReaderState,
    private val titlePaint: TextPaint,
    private val contentPaint: TextPaint,
    private val chapterNumPaint: TextPaint,
    private val bgColor: Int,
    private val bgBitmap: Bitmap? = null,
    /** 全书 raw 高亮，本类内部按 page.chapterIndex 过滤。 */
    private val chapterHighlightsRaw: List<Highlight> = emptyList(),
    /** 全书 raw 书签，本类内部按 page.chapterIndex 过滤。 */
    private val bookmarksRaw: List<com.morealm.app.domain.entity.Bookmark> = emptyList(),
    private val revealHighlight: RevealHighlight? = null,
    private val searchHighlightChapterIndex: Int = -1,
    private val searchHighlightCpRange: IntRange = IntRange.EMPTY,
    private val searchHighlightArgb: Int = 0x55FFFF00.toInt(),
    private val selectionChapterIndex: Int = -1,
    private val selectionCpRange: IntRange = IntRange.EMPTY,
    private val selectionArgb: Int = 0x4D5B6CFE.toInt(),
    private val pageInfoSnapshotProvider: PageInfoSnapshotProvider? = null,
) {
    private val pageInfoCanvasDrawer = PageInfoCanvasDrawer()

    /** 页栏像素版本，供仿真空闲位图键显式失效。 */
    val infoVersion: Long = pageInfoSnapshotProvider?.infoVersion ?: 0L

    /**
     * 渲染 [page] 到 [Bitmap]，按 page.chapterIndex 路由到 prev/cur/next layout 拿
     * viewWidth / paddingLeft / chapter bg image。
     *
     * @return null 当 page 是空页 / chapterIndex 不属于 prev/cur/next 任一 / 尺寸非法 / OOM
     */
    fun bitmapForScrollPage(page: ScrollPage, w: Int, h: Int): Bitmap? {
        if (w <= 0 || h <= 0) return null
        if (page.chapterIndex < 0) return null
        val layout = locateLayout(page.chapterIndex) ?: return null
        return try {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            // 1. 底色
            canvas.drawColor(bgColor)
            // 2. 用户主题背景图；EPUB section 背景随后由共享页面绘制器按区域叠加。
            val bg = bgBitmap
            if (bg != null && !bg.isRecycled) {
                com.morealm.app.ui.reader.renderer.drawBgBitmap(
                    canvas, bg, w.toFloat(), h.toFloat(),
                )
            }
            // 3. 当前 page 所属章的高亮 / 书签
            val chHighlights = chapterHighlightsRaw.filter { it.chapterIndex == page.chapterIndex }
            val highlightSpecs = if (chHighlights.isEmpty()) emptyList() else
                ScrollHighlightProjector.projectForPage(
                    page = page,
                    chapterViewWidth = layout.viewWidth,
                    chapterHighlights = chHighlights,
                )
            val pageBookmarkCps = if (bookmarksRaw.isEmpty() || page.lines.isEmpty()) emptyList() else {
                val firstCp = page.lines.first().firstChapterPos
                val lastCp = page.lines.last().lastChapterPos
                bookmarksRaw
                    .filter { it.chapterIndex == page.chapterIndex && it.chapterPos in firstCp..lastCp }
                    .map { it.chapterPos }
            }
            val revealForPage = revealHighlight?.takeIf { it.chapterIndex == page.chapterIndex }
            val searchRangeForPage = if (searchHighlightChapterIndex == page.chapterIndex) {
                searchHighlightCpRange
            } else IntRange.EMPTY
            val selectionRangeForPage = if (selectionChapterIndex == page.chapterIndex) {
                selectionCpRange
            } else IntRange.EMPTY
            // 4. 共享 drawer
            drawScrollPageOnCanvas(
                nc = canvas,
                page = page,
                viewHeightF = h.toFloat(),
                contentPaint = contentPaint,
                titlePaint = titlePaint,
                chapterNumPaint = chapterNumPaint,
                chapterViewWidth = layout.viewWidth,
                chapterPaddingLeft = layout.paddingLeft,
                highlightSpecs = highlightSpecs,
                bookmarkCps = pageBookmarkCps,
                revealHighlight = revealForPage,
                searchHighlightCpRange = searchRangeForPage,
                searchHighlightArgb = searchHighlightArgb,
                selectionCpRange = selectionRangeForPage,
                selectionArgb = selectionArgb,
                readerBgArgb = bgColor,
            )
            // 页栏必须最后绘制，确保正文、高亮和书签不会覆盖页眉页脚。
            pageInfoSnapshotProvider?.snapshotFor(page)?.let { snapshot ->
                pageInfoCanvasDrawer.draw(canvas, snapshot, w, h)
            }
            bitmap
        } catch (e: OutOfMemoryError) {
            AppLog.error(
                "ScrollPagePageBitmapProvider",
                "OOM page.chIdx=${page.chapterIndex} page.pgIdx=${page.pageIndex} w=$w h=$h",
                e,
            )
            null
        }
    }

    /** 按 chapterIndex 在 prev/cur/next 三章里找对应 layout；找不到返回 null（caller 应跳过渲染）。 */
    private fun locateLayout(chapterIndex: Int): ScrollChapterLayout? {
        state.currentChapter?.let { if (it.chapterIndex == chapterIndex) return it }
        state.prevChapter?.let { if (it.chapterIndex == chapterIndex) return it }
        state.nextChapter?.let { if (it.chapterIndex == chapterIndex) return it }
        return null
    }
}

/**
 * Compose-friendly 构造入口 —— remember keys 涵盖一切可能让 bitmap 输出变化的输入。
 *
 * 关键：state 字段（prev/cur/next layout）必须作 remember key 让章节切换后 provider 重建。
 * 其他视觉态（paint / bg / 高亮 / 书签 / search / reveal）同理。
 */
@Composable
fun rememberScrollPagePageBitmapProvider(
    state: ScrollCanvasReaderState,
    titlePaint: TextPaint,
    contentPaint: TextPaint,
    chapterNumPaint: TextPaint,
    bgColor: Int,
    bgBitmap: Bitmap? = null,
    chapterHighlightsRaw: List<Highlight> = emptyList(),
    bookmarksRaw: List<com.morealm.app.domain.entity.Bookmark> = emptyList(),
    revealHighlight: RevealHighlight? = null,
    searchHighlightChapterIndex: Int = -1,
    searchHighlightCpRange: IntRange = IntRange.EMPTY,
    searchHighlightArgb: Int = 0x55FFFF00.toInt(),
    selectionChapterIndex: Int = -1,
    selectionCpRange: IntRange = IntRange.EMPTY,
    selectionArgb: Int = 0x4D5B6CFE.toInt(),
    pageInfoBarProvider: ((ScrollPage) -> PageInfoBarSpec?)? = null,
    pageInfoVersion: Long = 0L,
): ScrollPagePageBitmapProvider {
    val density = LocalDensity.current
    return remember(
        state.currentChapter, state.prevChapter, state.nextChapter,
        titlePaint, contentPaint, chapterNumPaint,
        bgColor, bgBitmap,
        chapterHighlightsRaw, bookmarksRaw,
        revealHighlight,
        searchHighlightChapterIndex, searchHighlightCpRange, searchHighlightArgb,
        selectionChapterIndex, selectionCpRange, selectionArgb,
        pageInfoBarProvider, pageInfoVersion, density.density, density.fontScale,
    ) {
        val snapshotProvider = pageInfoBarProvider?.let { provider ->
            PageInfoSnapshotProvider(
                infoVersion = pageInfoVersion,
                density = density.density,
                fontScale = density.fontScale,
                specProvider = provider,
            )
        }
        ScrollPagePageBitmapProvider(
            state = state,
            titlePaint = titlePaint,
            contentPaint = contentPaint,
            chapterNumPaint = chapterNumPaint,
            bgColor = bgColor,
            bgBitmap = bgBitmap,
            chapterHighlightsRaw = chapterHighlightsRaw,
            bookmarksRaw = bookmarksRaw,
            revealHighlight = revealHighlight,
            searchHighlightChapterIndex = searchHighlightChapterIndex,
            searchHighlightCpRange = searchHighlightCpRange,
            searchHighlightArgb = searchHighlightArgb,
            selectionChapterIndex = selectionChapterIndex,
            selectionCpRange = selectionCpRange,
            selectionArgb = selectionArgb,
            pageInfoSnapshotProvider = snapshotProvider,
        )
    }
}
