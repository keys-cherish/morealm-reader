package com.morealm.app.ui.reader.renderer.scroll

import com.morealm.epub.render.ScrollChapterLayout

/** 图片命中结果：src + 是否整页图（弹层与「查看大图」共用）。 */
data class EpubImageHit(val src: String, val isFullPage: Boolean)

/**
 * EPUB 结构化布局的图片命中测试 —— tap「查看大图」与长按图片弹层共用。
 *
 * 坐标约定与 `ScrollChapterLayout.findColumnByPixel` 一致：x 是内容区坐标
 * （view-x − paddingLeft），y 是章内累计 Y（宿主已做 page/pageOffset 换算）。
 *
 * 命中口径（对齐参照阅读器的整图幅灵敏度，宁可宽勿窄）：
 *  - 整页图：该页任意位置都算命中——绘制时本就 cover 铺满物理页；
 *  - 块级图：整个 slot 矩形（imageLeftPx/RightPx 声明了定宽容器区间就用它，
 *    否则整个内容宽都算）± 容差；
 *  - 内联图：字符格矩形 ± 容差（注号小图靠容差把点击热区放大到可用）。
 */
object EpubImageHitTester {

    fun findImageAt(
        layout: ScrollChapterLayout,
        x: Float,
        yInChapter: Float,
        tolerancePx: Float,
    ): EpubImageHit? {
        var pageTop = 0f
        for (page in layout.pages) {
            val yInPage = yInChapter - pageTop
            pageTop += page.height
            if (yInPage < -tolerancePx || yInPage > page.height + tolerancePx) continue
            for (line in page.lines) {
                if (line.isImage) {
                    val src = line.imageSrc ?: continue
                    if (line.isFullPageImage) return EpubImageHit(src, isFullPage = true)
                    if (yInPage < line.lineTop - tolerancePx || yInPage > line.lineBottom + tolerancePx) continue
                    val hasSlot = line.imageRightPx > line.imageLeftPx
                    val xHit = !hasSlot ||
                        (x >= line.imageLeftPx - tolerancePx && x <= line.imageRightPx + tolerancePx)
                    if (xHit) return EpubImageHit(src, isFullPage = false)
                } else {
                    if (line.columns.isEmpty()) continue
                    if (yInPage < line.lineTop - tolerancePx || yInPage > line.lineBottom + tolerancePx) continue
                    val col = line.columns.firstOrNull { c ->
                        c.inlineImageSrc != null &&
                            x >= c.start - tolerancePx && x <= c.end + tolerancePx
                    } ?: continue
                    return EpubImageHit(col.inlineImageSrc!!, isFullPage = false)
                }
            }
        }
        return null
    }
}
