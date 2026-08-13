package com.morealm.app.ui.reader.renderer.scroll

import androidx.compose.ui.geometry.Rect
import com.morealm.app.domain.render.ImageCache
import com.morealm.epub.render.ScrollChapterLayout
import com.morealm.epub.render.ScrollLine

/**
 * 图片命中结果。
 *
 * @property src 图源（引擎所见 file:// / mobi-img://）
 * @property isFullPage 整页图（封面 cover 语义）
 * @property drawnRect 图片**实际绘制矩形**（与命中输入同坐标系：x=内容区坐标、
 *   y=章内累计坐标）。fit 数学与绘制端同款（slot 内等比居中，原图尺寸来自
 *   [ImageCache.getBounds] 头信息）；dims 不可得退化为整个 slot。整页图为 null
 *   （全屏即选中语义，宿主不画选中框）。选中态压暗/描边按它画，弹层与图有视觉关联。
 */
data class EpubImageHit(
    val src: String,
    val isFullPage: Boolean,
    val drawnRect: Rect? = null,
)

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
        /** 内容区宽（viewWidth − 左右 padding）。图行未声明 slot 时的绘制基准，与绘制端同款。 */
        contentWidthPx: Float = Float.NaN,
    ): EpubImageHit? {
        var pageTop = 0f
        for (page in layout.pages) {
            val yInPage = yInChapter - pageTop
            val pageTopSnapshot = pageTop
            pageTop += page.height
            if (yInPage < -tolerancePx || yInPage > page.height + tolerancePx) continue
            for (line in page.lines) {
                if (line.isImage) {
                    val src = line.imageSrc ?: continue
                    if (line.isFullPageImage) return EpubImageHit(src, isFullPage = true)
                    if (yInPage < line.lineTop - tolerancePx || yInPage > line.lineBottom + tolerancePx) continue
                    val hasSlot = line.imageRightPx > line.imageLeftPx
                    val slotLeft = if (hasSlot) line.imageLeftPx else 0f
                    val slotRight = when {
                        hasSlot -> line.imageRightPx
                        !contentWidthPx.isNaN() && contentWidthPx > 0f -> contentWidthPx
                        else -> Float.NaN
                    }
                    val xHit = slotRight.isNaN() ||
                        (x >= slotLeft - tolerancePx && x <= slotRight + tolerancePx)
                    if (xHit) {
                        return EpubImageHit(
                            src,
                            isFullPage = false,
                            drawnRect = blockImageDrawnRect(line, pageTopSnapshot, slotLeft, slotRight),
                        )
                    }
                } else {
                    if (line.columns.isEmpty()) continue
                    if (yInPage < line.lineTop - tolerancePx || yInPage > line.lineBottom + tolerancePx) continue
                    val col = line.columns.firstOrNull { c ->
                        c.inlineImageSrc != null &&
                            x >= c.start - tolerancePx && x <= c.end + tolerancePx
                    } ?: continue
                    return EpubImageHit(
                        col.inlineImageSrc!!,
                        isFullPage = false,
                        drawnRect = Rect(
                            left = col.start,
                            top = pageTopSnapshot + line.lineTop,
                            right = col.end,
                            bottom = pageTopSnapshot + line.lineBottom,
                        ),
                    )
                }
            }
        }
        return null
    }

    /**
     * 块级图实际绘制矩形 —— 与 PagePaneCanvas 段落图分支同款 fit 数学：
     * slot 内等比缩放居中。slotRight 为 NaN（caller 未传内容宽的旧调用）或
     * dims 不可得时退化为整个 slot / 行矩形，选中框略宽但仍指向正确的图。
     */
    private fun blockImageDrawnRect(
        line: ScrollLine,
        pageTop: Float,
        slotLeft: Float,
        slotRight: Float,
    ): Rect? {
        val top = pageTop + line.lineTop
        val bottom = pageTop + line.lineBottom
        if (slotRight.isNaN()) return null
        val slotW = slotRight - slotLeft
        val slotH = bottom - top
        val dims = ImageCache.getBounds(line.imageSrc.orEmpty())
        if (dims == null || dims.first <= 0 || dims.second <= 0) {
            return Rect(slotLeft, top, slotRight, bottom)
        }
        val (w, h) = dims
        val scale = minOf(slotW / w, slotH / h)
        val drawW = w * scale
        val drawH = h * scale
        val left = slotLeft + (slotW - drawW) / 2f
        val topY = top + (slotH - drawH) / 2f
        return Rect(left, topY, left + drawW, topY + drawH)
    }
}
