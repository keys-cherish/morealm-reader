package com.morealm.app.ui.reader.page

import android.graphics.Bitmap
import android.text.TextPaint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.render.TextPage
import com.morealm.app.ui.reader.renderer.HighlightSpan
import com.morealm.app.ui.reader.renderer.LocalReaderRenderTheme
import com.morealm.app.ui.reader.renderer.PageInfoOverlaySpec
import com.morealm.app.ui.reader.renderer.renderPageToBitmap

/**
 * **P3-5a**：[PageBitmapProvider] 的真渲染实现 —— 把单章 [TextPage] 列表 +
 * 主题 paint + 高亮渲染数据 包成 caller 友好的 provider 接口。
 *
 * ── 当前阶段（P3-5a）：单章模式 ──
 *
 *  - `pageCount = pages.size.coerceAtLeast(1)` —— 跟 [com.morealm.app.ui.reader.renderer.ReaderPageFactory.pageCount]
 *    一致（pages 暂空时给 caller 一个 placeholder slot 不崩，pagerState 至少能 mount）
 *  - 单页 [TextPage] → [renderPageToBitmap] → [Bitmap]
 *  - 越界 / 0 尺寸 / OOM 返回 `null`，让 [BitmapPageContent] 画透明占位
 *
 * ── 未来阶段（P3-5b+） ──
 *
 *  - 跨章 unified 模式（prev + cur + next 三章 → unified pageCount）适配 SLIDE / COVER
 *  - 直接消费 [com.morealm.epub.compat.StructuredChapterContent] 而非 TextPage
 *    （TextPage 是 ChapterProvider 排版后的产物，把契约挂到 ChapterBlock 更上游可以
 *    让 EPUB / TXT / 网络书共用同一条 bitmap 渲染流水线）
 *  - 异步预渲染 + LRU cache（避免每次 `bitmapAt` 都跑 [renderPageToBitmap]）
 *
 * ── 调用契约 ──
 *
 * Caller 在内容版本变化时（主题切、字号变、章节切、高亮增删）应该 [rememberRichPageBitmapProvider]
 * 通过 key 派生新实例，让 [System.identityHashCode] 不同 → SimulationPager 等下游的
 * dedupe 机制（[com.morealm.app.ui.reader.page.animation.SimulationExternalIdleKey]）自然失效 →
 * 触发重渲染。
 */
class RichPageBitmapProvider(
    private val pages: List<TextPage>,
    private val titlePaint: TextPaint,
    private val contentPaint: TextPaint,
    private val chapterNumPaint: TextPaint? = null,
    private val bgColor: Int,
    private val bgBitmap: Bitmap? = null,
    private val pageInfoOverlay: PageInfoOverlaySpec? = null,
    private val chapterHighlights: List<HighlightSpan> = emptyList(),
    private val chapterTextColorSpans: List<HighlightSpan> = emptyList(),
    private val chapterUnderlines: List<HighlightSpan> = emptyList(),
) : PageBitmapProvider {

    override val pageCount: Int = pages.size.coerceAtLeast(1)

    override fun bitmapAt(displayIndex: Int, w: Int, h: Int): Bitmap? {
        if (displayIndex !in pages.indices) return null
        if (w <= 0 || h <= 0) return null
        val page = pages[displayIndex]
        return try {
            // 按 page chapter range 过滤章级 highlights / textColorSpans / underlines。
            // 与 [com.morealm.app.ui.reader.renderer.PageContentBox] / SimulationPager
            // 的 pageHighlights 逻辑同源 —— 三处保持一致，避免后续维护 drift。
            val pageStart = page.chapterPosition
            val pageEnd = pageStart + page.lines.sumOf { it.charSize }
            val pageHighlights = if (chapterHighlights.isEmpty()) emptyList() else
                chapterHighlights.filter { it.startChapterPos < pageEnd && it.endChapterPos > pageStart }
            val pageTextColor = if (chapterTextColorSpans.isEmpty()) emptyList() else
                chapterTextColorSpans.filter { it.startChapterPos < pageEnd && it.endChapterPos > pageStart }
            val pageUnderlines = if (chapterUnderlines.isEmpty()) emptyList() else
                chapterUnderlines.filter { it.startChapterPos < pageEnd && it.endChapterPos > pageStart }
            renderPageToBitmap(
                w, h, bgColor, page,
                titlePaint, contentPaint,
                chapterNumPaint = chapterNumPaint,
                reuseBitmap = null, bgBitmap = bgBitmap,
                pageInfoOverlay = pageInfoOverlay,
                highlights = pageHighlights,
                textColorSpans = pageTextColor,
                underlines = pageUnderlines,
            )
        } catch (e: OutOfMemoryError) {
            AppLog.error(
                "RichPageBitmapProvider",
                "OOM at idx=$displayIndex w=${w} h=${h}",
                e,
            )
            null
        }
    }
}

/**
 * Compose-friendly 构造入口 —— 等价 [com.morealm.app.ui.reader.page.animation.rememberSimulationParams]
 * 但生成 [RichPageBitmapProvider]。从 [LocalReaderRenderTheme] 拿 paint / bgArgb / bgBitmap，
 * caller 只提供 pages + 高亮 / overlay 即可。
 *
 * `remember` 的 keys 涵盖所有可能改变 bitmap 输出的输入；任一变化 → 重建 provider →
 * provider identity 变 → 下游 dedupe 失效 → 新一帧 bitmap 出图。
 */
@Composable
fun rememberRichPageBitmapProvider(
    pages: List<TextPage>,
    chapterHighlights: List<HighlightSpan> = emptyList(),
    chapterTextColorSpans: List<HighlightSpan> = emptyList(),
    chapterUnderlines: List<HighlightSpan> = emptyList(),
    pageInfoOverlay: PageInfoOverlaySpec? = null,
): RichPageBitmapProvider {
    val theme = LocalReaderRenderTheme.current
    return remember(
        pages,
        theme.titlePaint,
        theme.contentPaint,
        theme.chapterNumPaint,
        theme.bgArgb,
        theme.bgBitmap,
        pageInfoOverlay,
        chapterHighlights,
        chapterTextColorSpans,
        chapterUnderlines,
    ) {
        RichPageBitmapProvider(
            pages = pages,
            titlePaint = theme.titlePaint,
            contentPaint = theme.contentPaint,
            chapterNumPaint = theme.chapterNumPaint,
            bgColor = theme.bgArgb,
            bgBitmap = theme.bgBitmap,
            pageInfoOverlay = pageInfoOverlay,
            chapterHighlights = chapterHighlights,
            chapterTextColorSpans = chapterTextColorSpans,
            chapterUnderlines = chapterUnderlines,
        )
    }
}
