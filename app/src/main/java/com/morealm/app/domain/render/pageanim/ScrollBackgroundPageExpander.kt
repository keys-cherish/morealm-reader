package com.morealm.app.domain.render.pageanim

import com.morealm.epub.compat.ChapterBlock
import com.morealm.epub.compat.InlineImageSpan
import com.morealm.epub.compat.StructuredChapterContent
import com.morealm.epub.compat.StructuredContentSection
import com.morealm.epub.compat.TextSpan
import com.morealm.epub.css.EpubBackground
import com.morealm.epub.css.EpubBackgroundImage
import com.morealm.epub.css.EpubBackgroundRepeatMode
import com.morealm.epub.css.EpubBackgroundSize
import com.morealm.epub.render.ScrollChapterLayout
import com.morealm.epub.render.ScrollPage
import com.morealm.epub.render.ScrollPageSectionRegion

/**
 * 把「整张海报作为 body 背景」的 spine section 展开成等比整页。
 *
 * 这类 Content Document 的正文只有隐藏章节名和空白占位，真实内容已经画在背景图里。
 * 继续按文本行高计算 section，会把 2400px 的图压进几百像素，并让下一份 XHTML 提前接上来。
 *
 * **按 section 而非按章处理**：一个 TOC 章可以跨多个 spine item —— 没有独立 navPoint 的
 * spine 项会被并进相邻章（整页插画页恰恰常常没有目录条目）。按章判定时这类章一律因
 * 「不止一个 section」被跳过，展开逻辑一次都不会执行。
 */
internal fun expandBackgroundOnlyScrollPage(
    layout: ScrollChapterLayout,
    content: StructuredChapterContent,
    chapterTitle: String,
    pageWidth: Int,
    resolveImageDimensions: (src: String, targetWidth: Int) -> Pair<Int, Int>?,
): ScrollChapterLayout {
    if (pageWidth <= 0 || content.sections.isEmpty()) return layout

    val plates = HashMap<Int, PlateSpec>()
    for (section in content.sections) {
        val height = section.plateHeightOrNull(content, chapterTitle, pageWidth, resolveImageDimensions)
        if (height != null) plates[section.sectionIndex] = PlateSpec(height, section.background)
    }
    if (plates.isEmpty()) return layout

    // content 只有一个 section 时，任何页都必然属于它 —— 用于页尚未被 section 标注的
    // layout（未走 attachContinuousSectionRegions 的路径）。
    val soleSectionIndex = content.sections.singleOrNull()?.sectionIndex

    // section 边界即页边界（ScrollLayoutEngine 在 spine 起点 flushPage），故整页板 section
    // 独占一页，改这一页的高度即可；页与页紧贴，pageIndex 无需重排。
    var delta = 0f
    val pages = layout.pages.map { page ->
        val sectionIndex = page.owningSectionIndex(soleSectionIndex) ?: return@map page
        val plate = plates[sectionIndex] ?: return@map page
        if (page.height == plate.height && page.lines.isEmpty()) return@map page
        delta += plate.height - page.height
        val regions = if (page.sectionRegions.isEmpty()) {
            listOf(
                ScrollPageSectionRegion(
                    sectionIndex = sectionIndex,
                    top = 0f,
                    bottom = plate.height,
                    sectionOffsetY = 0f,
                    sectionHeight = plate.height,
                    background = plate.background,
                ),
            )
        } else {
            page.sectionRegions.map { region ->
                if (region.sectionIndex != sectionIndex) {
                    region
                } else {
                    region.copy(top = 0f, bottom = plate.height, sectionOffsetY = 0f, sectionHeight = plate.height)
                }
            }
        }
        page.copy(
            height = plate.height,
            // 只有空白占位段，撑开后不再需要——留着会在图上叠一行空行高。
            lines = emptyList(),
            sectionIndex = sectionIndex,
            background = plate.background,
            sectionRegions = regions,
        )
    }
    if (delta == 0f) return layout

    return layout.copy(
        pages = pages,
        totalHeight = (layout.totalHeight + delta).coerceAtLeast(0f),
        chapterBgImageSrc = null,
        sections = layout.sections.map { section ->
            plates[section.sectionIndex]?.let { section.copy(totalHeight = it.height, background = it.background) }
                ?: section
        },
    )
}

private class PlateSpec(val height: Float, val background: EpubBackground)

/**
 * 该页归属的 section —— 无法唯一确定时返回 null（宁可不展开，也不能张冠李戴）。
 *
 * [soleSectionIndex] 是「整章只有一个 section」时的兜底：此时页即便没有 section 标注，
 * 归属也是确定的。
 */
private fun ScrollPage.owningSectionIndex(soleSectionIndex: Int?): Int? = when {
    sectionIndex >= 0 -> sectionIndex
    sectionRegions.size == 1 -> sectionRegions.single().sectionIndex
    sectionRegions.isEmpty() -> soleSectionIndex
    else -> null
}

/**
 * 该 section 若是「整页板」，返回它按图片原始宽高比铺满 [pageWidth] 所需的高度；否则 null。
 *
 * 判据对齐 CSS 语义：单个 cover + no-repeat 背景图层，且正文除章节标题外没有任何可见内容。
 */
private fun StructuredContentSection.plateHeightOrNull(
    content: StructuredChapterContent,
    chapterTitle: String,
    pageWidth: Int,
    resolveImageDimensions: (src: String, targetWidth: Int) -> Pair<Int, Int>?,
): Float? {
    val layer = background.layers.singleOrNull() ?: return null
    val image = layer.image as? EpubBackgroundImage.Url ?: return null
    if (layer.size != EpubBackgroundSize.Cover ||
        layer.repeat.x != EpubBackgroundRepeatMode.NO_REPEAT ||
        layer.repeat.y != EpubBackgroundRepeatMode.NO_REPEAT
    ) {
        return null
    }
    if (!content.blocksFor(this).containsOnlyChapterTitle(chapterTitle)) return null

    val (intrinsicWidth, intrinsicHeight) = resolveImageDimensions(image.uri, pageWidth) ?: return null
    if (intrinsicWidth <= 0 || intrinsicHeight <= 0) return null
    val height = pageWidth.toFloat() * intrinsicHeight / intrinsicWidth
    return height.takeIf { it.isFinite() && it > 0f }
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
