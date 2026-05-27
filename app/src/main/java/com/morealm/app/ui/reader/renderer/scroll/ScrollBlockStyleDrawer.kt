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
    /**
     * **阶段 2-H bugfix v3 (参考图样验证)**：仅对 box 装饰字段缩放。
     *
     * 设计基线：epub-compat ChapterReader.readTree 用 rootFontSizePx=16f 解析 CSS em
     * (cache 稳定不跟随 user 字号变；ScrollLayoutEngine 主循环按设计 px 算 margin
     * layout)。但 box 装饰 (qipao widthPx/heightPx/padding/borderRadius/borderWidth)
     * 需要按 user 字号缩放才能跟字符大小协调 — 参考图 52 显示 qipao 220px 直径 ≈
     * 3.5em × user 字号 ≈ 56 × (user 字号 / 16)。
     *
     * 不缩放：margin* (ScrollLayoutEngine 主循环用 D1.a path，设计 -16 = 参考微间距)。
     * 缩放：widthPx / heightPx / paddingTop/Right/Bottom/LeftPx / borderRadiusPx /
     *      borderWidthPx (装饰盒尺寸)。
     *
     * 默认 1f = 16f 设计字号；用户 24sp×3 = 72px → 4.5x → qipao 252px 圆 (≈ 参考 220)。
     */
    fontSizeScale: Float = 1f,
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
    // **阶段 2-H bugfix v3**：box 装饰字段按 fontSizeScale 缩放（设计 16f px → user 字号 px）。
    // margin 在 ScrollLayoutEngine 主循环用 D1.a path（不在此处理，保持设计微间距）。
    val borderWidthScaled = bs.borderWidthPx * fontSizeScale
    val halfBorder = borderWidthScaled / 2f
    val padLeft = bs.paddingLeftPx * fontSizeScale
    val padRight = bs.paddingRightPx * fontSizeScale
    val padTop = bs.paddingTopPx * fontSizeScale
    val padBottom = bs.paddingBottomPx * fontSizeScale
    val widthScaled = bs.widthPx?.let { it * fontSizeScale }
    val heightScaled = bs.heightPx?.let { it * fontSizeScale }
    // **方案 C inline-block container**：line.cells 非 null + bs.widthPx 非 null →
    // 用 cells[0] 的 contentLeft / contentWidth 算 box 中心（ScrollLayoutEngine
    // layoutInlineBlockContainer 已正确设 cell.contentLeft = marginLeft × scale，
    // contentWidth = boxW，atoms 按 cellLocalX 排在 cell 内）。让 qipao 圆球覆盖
    // cell 内字符（参考图 41 文字在椭圆内）。
    //
    // 退回路径：line.cells == null 时用 line.columns 范围中心（普通段 bg 装饰）。
    val ibCell = if (widthScaled != null) line.cells?.firstOrNull() else null
    val rectLeft: Float
    val rectTop: Float
    val rectRight: Float
    val rectBottom: Float
    if (ibCell != null && widthScaled != null) {
        // box 由 cell 几何确定（contentLeft 已含 margin-left × scale）
        rectLeft = ibCell.contentLeft - padLeft - halfBorder
        rectRight = ibCell.contentLeft + widthScaled + padRight + halfBorder
    } else if (widthScaled != null) {
        val cx = (leftX + rightX) / 2f
        rectLeft = cx - widthScaled / 2f - padLeft - halfBorder
        rectRight = cx + widthScaled / 2f + padRight + halfBorder
    } else {
        rectLeft = leftX - padLeft - halfBorder
        rectRight = rightX + padRight + halfBorder
    }
    if (ibCell != null && heightScaled != null) {
        // box 垂直填满 line（cells path lineHeight = max(boxH, contentH+padding)）
        val cy = (lineTop + lineBottom) / 2f
        rectTop = cy - heightScaled / 2f - padTop - halfBorder
        rectBottom = cy + heightScaled / 2f + padBottom + halfBorder
    } else if (heightScaled != null) {
        val cy = (lineTop + lineBottom) / 2f
        rectTop = cy - heightScaled / 2f - padTop - halfBorder
        rectBottom = cy + heightScaled / 2f + padBottom + halfBorder
    } else {
        rectTop = lineTop - padTop - halfBorder
        rectBottom = lineBottom + padBottom + halfBorder
    }
    // **阶段 2-D**：BORDER_RADIUS_CIRCLE (POSITIVE_INFINITY) sentinel → 自适应圆角 = box 边长 50%。
    // 某轻小说 qipao `border-radius: 100%` 让 box 成圆/椭圆。配合 widthPx/heightPx 后 box 真成圆。
    val rectW = rectRight - rectLeft
    val rectH = rectBottom - rectTop
    val r = if (bs.borderRadiusPx.isInfinite()) minOf(rectW, rectH) / 2f
            else bs.borderRadiusPx * fontSizeScale
    // **EpubW5H/CircleBox/Draw diag (2026-05-27)** — 装饰盒最终几何：rect + radius + scale 来源。
    // 配 EpubW5H/CircleBox/Emit 对比 emit 阶段算出的 boxW/H 与 drawer 阶段的 rectW/H 是否一致，
    // 以及圆 sentinel 是否生效。仅装饰段（widthPx 非 null 或 borderRadius CIRCLE）fire。
    if (bs.widthPx != null || bs.borderRadiusPx.isInfinite()) {
        com.morealm.app.core.log.AppLog.info(
            "EpubW5H/CircleBox/Draw",
            "rect=($rectLeft,$rectTop)-($rectRight,$rectBottom) " +
                "size=${rectW}x${rectH} r=$r isCircleSentinel=${bs.borderRadiusPx.isInfinite()} " +
                "fontSizeScale=$fontSizeScale ibCell=${ibCell != null} " +
                "padL=$padLeft padT=$padTop borderW=$borderWidthScaled " +
                "family='${bs.fontFamily}'",
        )
    }
    val paint = Paint().apply { isAntiAlias = true }

    bs.backgroundColor?.let { bgArgb ->
        paint.style = Paint.Style.FILL
        paint.color = bgArgb
        canvas.drawRoundRect(rectLeft, rectTop, rectRight, rectBottom, r, r, paint)
    }

    val bc = bs.borderColor
    if (bc != null && borderWidthScaled > 0f) {
        paint.style = Paint.Style.STROKE
        paint.color = bc
        when (bs.borderStyle) {
            BlockStyle.BorderStyle.DOUBLE -> {
                val third = borderWidthScaled / 3f
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
                paint.strokeWidth = borderWidthScaled
                canvas.drawRoundRect(rectLeft, rectTop, rectRight, rectBottom, r, r, paint)
            }
        }
    }
}
