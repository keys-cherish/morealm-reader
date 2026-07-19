package com.morealm.app.domain.render.pageanim

import com.morealm.epub.compat.ChapterBlock
import com.morealm.epub.compat.InlineImageSpan
import com.morealm.epub.compat.StructuredChapterContent
import com.morealm.epub.compat.TextSpan
import com.morealm.epub.css.EpubBackgroundImage
import com.morealm.epub.css.EpubBackgroundRepeatMode
import com.morealm.epub.css.EpubBackgroundSize
import com.morealm.epub.render.ScrollChapterLayout
import com.morealm.epub.render.ScrollPageSectionRegion

/**
 * 将“整张人物海报作为 body 背景”的短空章节展开成等比滚动页。
 *
 * 这类 EPUB 的正文只有隐藏章节名和空白占位，真实内容已经画在背景图里。继续按文本
 * 行高计算 section 会把 2400px 图片压进几百像素区域，并让下一章提前接上来。
 */
internal fun expandBackgroundOnlyScrollPage(
    layout: ScrollChapterLayout,
    content: StructuredChapterContent,
    chapterTitle: String,
    pageWidth: Int,
    resolveImageDimensions: (src: String, targetWidth: Int) -> Pair<Int, Int>?,
): ScrollChapterLayout {
    if (pageWidth <= 0 || content.sections.size != 1) return layout
    val section = content.sections.single()
    val layer = section.background.layers.singleOrNull() ?: return layout
    val image = layer.image as? EpubBackgroundImage.Url ?: return layout
    if (layer.size != EpubBackgroundSize.Cover ||
        layer.repeat.x != EpubBackgroundRepeatMode.NO_REPEAT ||
        layer.repeat.y != EpubBackgroundRepeatMode.NO_REPEAT
    ) {
        return layout
    }
    if (!content.blocksFor(section).containsOnlyChapterTitle(chapterTitle)) return layout

    val (intrinsicWidth, intrinsicHeight) =
        resolveImageDimensions(image.uri, pageWidth) ?: return layout
    if (intrinsicWidth <= 0 || intrinsicHeight <= 0) return layout
    val imageHeight = pageWidth.toFloat() * intrinsicHeight / intrinsicWidth
    if (!imageHeight.isFinite() || imageHeight <= 0f) return layout

    val sourcePage = layout.pages.firstOrNull() ?: return layout
    val region = ScrollPageSectionRegion(
        sectionIndex = section.sectionIndex,
        top = 0f,
        bottom = imageHeight,
        sectionOffsetY = 0f,
        sectionHeight = imageHeight,
        background = section.background,
    )
    val page = sourcePage.copy(
        pageIndex = 0,
        lines = emptyList(),
        height = imageHeight,
        sectionIndex = section.sectionIndex,
        background = section.background,
        sectionRegions = listOf(region),
    )
    val sectionLayout = layout.sections.singleOrNull()?.copy(
        firstPageIndex = 0,
        pageCount = 1,
        totalHeight = imageHeight,
        background = section.background,
    )
    return layout.copy(
        pages = listOf(page),
        totalHeight = imageHeight,
        chapterBgImageSrc = null,
        sections = sectionLayout?.let(::listOf).orEmpty(),
    )
}

private fun List<ChapterBlock>.containsOnlyChapterTitle(chapterTitle: String): Boolean {
    val title = chapterTitle.normalizedPageText()
    if (title.isEmpty()) return false
    val fragments = ArrayList<String>()
    for (block in this) {
        if (!block.collectTextFragments(fragments)) return false
    }
    val meaningful = fragments.map(String::normalizedPageText).filter(String::isNotEmpty)
    return meaningful.isEmpty() || meaningful.all { it == title }
}

/** false 表示块内有真实图片等可见内容，不能把它当成纯背景页。 */
private fun ChapterBlock.collectTextFragments(target: MutableList<String>): Boolean {
    return when (this) {
        is ChapterBlock.Paragraph -> true.also { target.add(text) }
        is ChapterBlock.Heading -> true.also { target.add(text) }
        is ChapterBlock.RichText -> {
            for (span in spans) {
                when (span) {
                    is TextSpan -> target.add(span.text)
                    is InlineImageSpan -> return false
                }
            }
            true
        }
        is ChapterBlock.Image -> false
        is ChapterBlock.Table -> rows.all { row ->
            row.cells.all { cell -> cell.content.all { it.collectTextFragments(target) } }
        }
        is ChapterBlock.Container -> children.all { it.collectTextFragments(target) }
    }
}

private fun String.normalizedPageText(): String = buildString(length) {
    for (char in this@normalizedPageText) {
        if (!char.isWhitespace() && char != '\u00A0') append(char)
    }
}
