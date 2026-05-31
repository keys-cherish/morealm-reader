package com.morealm.app.ui.reader.page.animhorizontal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.morealm.app.domain.render.layout.ScrollHighlightDrawSpec
import com.morealm.app.domain.render.layout.ScrollPageFactory
import com.morealm.app.ui.reader.renderer.scroll.PagePaneCanvas
import com.morealm.app.ui.reader.renderer.scroll.ScrollCanvasReaderState

/**
 * 无动画翻页 Transition —— 最简的 page-level 横向 Transition。
 *
 * 参考成熟开源阅读器实现的无动画翻页代理：
 * onDraw 直接画 currentPage，nextPageByAnim / prevPageByAnim 内部 `readView.fillPage`
 * 瞬切。**没有 fling / drag 偏移动画**。
 *
 * MoRealm 实现策略：本 Transition 只**画当前 page**，翻页交互（zone tap 触发
 * pageFactory.moveToPrev/Next）在 [PageLevelReaderHost] 共享层处理。
 *
 * @param state V2 page-level state（共享）
 * @param pageFactory page 序列管理器（共享）
 * @param backgroundColor 阅读背景色（PagePaneCanvas 不画自己背景，由本 Transition 加 Modifier.background）
 */
@Composable
fun NonePageTransition(
    state: ScrollCanvasReaderState,
    pageFactory: ScrollPageFactory,
    backgroundColor: Color,
    contentPaint: android.text.TextPaint,
    titlePaint: android.text.TextPaint,
    chapterNumPaint: android.text.TextPaint,
    revealHighlight: com.morealm.app.ui.reader.renderer.RevealHighlight? = null,
    searchHighlightChapterIndex: Int = -1,
    searchHighlightCpRange: IntRange = IntRange.EMPTY,
    searchHighlightArgb: Int = 0x55FFFF00.toInt(),
    selectionChapterIndex: Int = -1,
    selectionCpRange: IntRange = IntRange.EMPTY,
    selectionArgb: Int = 0x4D5B6CFE.toInt(),
    curPageHighlightSpecs: List<ScrollHighlightDrawSpec> = emptyList(),
    curPageBookmarkCps: List<Int> = emptyList(),
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(backgroundColor)) {
        val curPage = pageFactory.curPage
        val chapterViewWidth = state.currentChapter?.viewWidth ?: 1080
        val chapterPaddingLeft = state.currentChapter?.paddingLeft ?: 0
        val revealForCur = revealHighlight?.takeIf { it.chapterIndex == curPage.chapterIndex }
        val searchRangeForCur = if (searchHighlightChapterIndex == curPage.chapterIndex) {
            searchHighlightCpRange
        } else IntRange.EMPTY
        val selectionRangeForCur = if (selectionChapterIndex == curPage.chapterIndex) {
            selectionCpRange
        } else IntRange.EMPTY

        PagePaneCanvas(
            page = curPage,
            chapterViewWidth = chapterViewWidth,
            chapterPaddingLeft = chapterPaddingLeft,
            contentPaint = contentPaint,
            titlePaint = titlePaint,
            chapterNumPaint = chapterNumPaint,
            highlightSpecs = curPageHighlightSpecs,
            bookmarkCps = curPageBookmarkCps,
            revealHighlight = revealForCur,
            searchHighlightCpRange = searchRangeForCur,
            searchHighlightArgb = searchHighlightArgb,
            selectionCpRange = selectionRangeForCur,
            selectionArgb = selectionArgb,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
