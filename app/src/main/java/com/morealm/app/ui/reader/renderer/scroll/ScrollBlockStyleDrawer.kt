package com.morealm.app.ui.reader.renderer.scroll

import android.graphics.Canvas
import android.graphics.Paint
import com.morealm.app.domain.render.layout.ScrollLine
import com.morealm.epub.compat.BlockStyle

/**
 * **P3-5b Phase 3**：把 [ScrollLine.blockStyle] 的 CSS box 装饰画到 line 内容的包围盒上。
 *
 * 共享 helper —— [ChapterPaneCanvas]（scroll 模式）和 [PagePaneCanvas]（page-level 模式
 * NONE/SLIDE/COVER）都在 `for (line in page.lines)` 循环最先调用。**必须先于文字/图片绘制**，
 * 否则装饰会被文字层覆盖。
 *
 * 几何契约：
 *  - 水平：columns 非空时取所有 columns 最左 start / 最右 end；空 columns（空段 / 图片段）
 *    用 [fallbackLeft]..[fallbackRight]（caller 通常传 0..visibleWidth 让装饰覆盖全宽）
 *  - 垂直：[pageTop] + [ScrollLine.lineTop] / lineBottom（PagePaneCanvas 通常 pageTop=0；
 *    ChapterPaneCanvas 累加每页 height 作为 pageTop）
 *  - padding 向外扩矩形；border 落在矩形边缘（半个 strokeWidth 向外扩，让 border 居中于
 *    rect 边线）
 *
 * border-style：
 *  - DOUBLE：CSS 标准画法 —— 外圈 + 内圈各占 1/3 strokeWidth，间隙 1/3
 *  - SOLID / DASHED / DOTTED：暂统一画 SOLID（视觉差异不大，Phase 3.5+ 用 PathEffect 区分）
 *
 * [BlockStyle.EMPTY] 早退零开销。
 *
 * @param fallbackLeft / [fallbackRight] 空 columns 时的水平范围 fallback；通常 caller 传
 *   `0f..visibleWidth.toFloat()`
 */
internal fun drawScrollLineBlockStyle(
    canvas: Canvas,
    line: ScrollLine,
    pageTop: Float,
    fallbackLeft: Float = 0f,
    fallbackRight: Float = 0f,
) {
    val bs = line.blockStyle
    if (bs === BlockStyle.EMPTY) return

    // 水平：columns 优先 extent；空段 / 图片段用 fallback
    val leftX: Float
    val rightX: Float
    if (line.columns.isNotEmpty()) {
        var minL = Float.MAX_VALUE
        var maxR = 0f
        for (col in line.columns) {
            if (col.start < minL) minL = col.start
            if (col.end > maxR) maxR = col.end
        }
        leftX = minL
        rightX = maxR
    } else {
        leftX = fallbackLeft
        rightX = fallbackRight
    }
    if (leftX >= rightX) return

    val lineTop = pageTop + line.lineTop
    val lineBottom = pageTop + line.lineBottom
    val halfBorder = bs.borderWidthPx / 2f
    val rectLeft = leftX - bs.paddingLeftPx - halfBorder
    val rectTop = lineTop - bs.paddingTopPx - halfBorder
    val rectRight = rightX + bs.paddingRightPx + halfBorder
    val rectBottom = lineBottom + bs.paddingBottomPx + halfBorder
    // **阶段 2-D**：BORDER_RADIUS_CIRCLE (POSITIVE_INFINITY) sentinel → 自适应圆角 = box 边长 50%。
    // SampleLN qipao `border-radius: 100%` 让 box 成圆/椭圆 (参考图 41 「啊啊...」橙底椭圆)。
    val rectW = rectRight - rectLeft
    val rectH = rectBottom - rectTop
    val r = if (bs.borderRadiusPx.isInfinite()) minOf(rectW, rectH) / 2f else bs.borderRadiusPx
    val paint = Paint().apply { isAntiAlias = true }

    bs.backgroundColor?.let { bgArgb ->
        paint.style = Paint.Style.FILL
        paint.color = bgArgb
        canvas.drawRoundRect(rectLeft, rectTop, rectRight, rectBottom, r, r, paint)
    }

    val bc = bs.borderColor
    if (bc != null && bs.borderWidthPx > 0f) {
        paint.style = Paint.Style.STROKE
        paint.color = bc
        when (bs.borderStyle) {
            BlockStyle.BorderStyle.DOUBLE -> {
                val third = bs.borderWidthPx / 3f
                paint.strokeWidth = third
                canvas.drawRoundRect(rectLeft, rectTop, rectRight, rectBottom, r, r, paint)
                val innerOff = 2f * third
                val innerR = (r - innerOff).coerceAtLeast(0f)
                canvas.drawRoundRect(
                    rectLeft + innerOff, rectTop + innerOff,
                    rectRight - innerOff, rectBottom - innerOff,
                    innerR, innerR, paint,
                )
            }
            BlockStyle.BorderStyle.SOLID,
            BlockStyle.BorderStyle.DASHED,
            BlockStyle.BorderStyle.DOTTED,
            -> {
                paint.strokeWidth = bs.borderWidthPx
                canvas.drawRoundRect(rectLeft, rectTop, rectRight, rectBottom, r, r, paint)
            }
        }
    }
}
