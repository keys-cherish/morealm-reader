package com.morealm.app.ui.reader.renderer.scroll

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import com.morealm.app.domain.render.ImageCache
import com.morealm.epub.render.ScrollLine
import com.morealm.app.ui.reader.renderer.adaptDecorationBgForReaderBg
import com.morealm.epub.compat.BlockStyle
import com.morealm.epub.layout.BoxGeometry

/**
 * **2026-05-28 Container box group 绘制** —— 给一页内所有连续同 [ScrollLine.boxGroupId] 的
 * line 段画**一次性**外层装饰 box（border + background + border-radius + padding）。
 *
 * **必须在 [drawScrollLineBlockStyle] 之前调用** —— group box 是最外层，per-line box 是叠在
 * 内部的小装饰；group box 先画作背景，per-line box 后画在 group box 之上。
 *
 * 几何契约：
 *  - 水平：固定 [fallbackLeft]..[fallbackRight]（通常 0..visibleWidth，让 group box 覆盖
 *    可见宽 padding 内部一格栅）。padding-left / padding-right 缩到 group box 内侧。
 *  - 垂直：group 内首行 [ScrollLine.lineTop] 减 paddingTop（向外），末行 [ScrollLine.lineBottom]
 *    加 paddingBottom。pageTop 同 [drawScrollLineBlockStyle]，加在每行 y 上。
 *
 * **跨页边界**：若 group lines 横跨两页（page-level 模式下 group 可能末行被切到下一页），
 * 本函数仅处理传入 lines（即当前页 lines），group 末行在下一页时本页 group 末行就当作 box
 * 底部裁断。下一页 caller 看到 boxGroupId 仍非 null 时画一次"上裁断"的 box（无圆角顶 +
 * 圆角底）。Phase 2 最小实现先按"每页独立画 box，跨页时上下边可能 squared"处理；视觉精
 * 致跨页裁断留 follow-up（用户报告 Step 9 真机后再加）。
 *
 * @param lines 一页内的所有 line（page.lines）
 * @param groupStyles 章级 [com.morealm.app.domain.render.layout.ScrollChapterLayout.boxGroupStyles]
 *   字典，groupId -> 装饰 [BlockStyle]
 * @param fallbackLeft / [fallbackRight] group box 水平范围（通常 0..visibleWidth）
 */
internal fun drawScrollContainerBoxes(
    canvas: Canvas,
    lines: List<ScrollLine>,
    groupStyles: Map<Int, BlockStyle>,
    pageTop: Float,
    fallbackLeft: Float,
    fallbackRight: Float,
    fontSizeScale: Float = 1f,
    readerBgArgb: Int = Color.WHITE,
) {
    if (groupStyles.isEmpty() || lines.isEmpty()) return
    var runStart: Int = -1
    var runGroupId: Int? = null
    fun flush(endIdxExclusive: Int) {
        val gid = runGroupId ?: return
        val style = groupStyles[gid] ?: return
        if (runStart < 0 || endIdxExclusive <= runStart) return
        drawSingleContainerBox(
            canvas = canvas,
            lines = lines,
            fromIdx = runStart,
            toIdxInclusive = endIdxExclusive - 1,
            style = style,
            pageTop = pageTop,
            fallbackLeft = fallbackLeft,
            fallbackRight = fallbackRight,
            fontSizeScale = fontSizeScale,
            readerBgArgb = readerBgArgb,
        )
    }
    for (i in lines.indices) {
        val gid = lines[i].boxGroupId
        if (gid != runGroupId) {
            flush(i)
            runStart = if (gid != null) i else -1
            runGroupId = gid
        }
    }
    flush(lines.size)
}

/**
 * 按段落绘制块级 CSS 装饰。一个 `<p>` 换行后仍只有一个 border/background 盒；
 * 若逐条 [ScrollLine] 绘制，`border-top` 会错误地复制到每一行。
 */
internal fun drawScrollParagraphBlockStyles(
    canvas: Canvas,
    lines: List<ScrollLine>,
    pageTop: Float,
    fallbackLeft: Float = 0f,
    fallbackRight: Float = 0f,
    fontSizeScale: Float = 1f,
    readerBgArgb: Int = Color.WHITE,
) {
    if (lines.isEmpty()) return
    var runStart = 0
    fun flush(endExclusive: Int) {
        if (endExclusive <= runStart) return
        val style = lines[runStart].blockStyle
        if (style === BlockStyle.EMPTY) return
        drawScrollBlockStyleRun(
            canvas = canvas,
            lines = lines,
            fromIdx = runStart,
            toIdxInclusive = endExclusive - 1,
            style = style,
            pageTop = pageTop,
            fallbackLeft = fallbackLeft,
            fallbackRight = fallbackRight,
            fontSizeScale = fontSizeScale,
            readerBgArgb = readerBgArgb,
        )
    }
    for (i in 1 until lines.size) {
        val previous = lines[i - 1]
        val current = lines[i]
        if (current.paragraphNum != previous.paragraphNum || current.blockStyle != previous.blockStyle) {
            flush(i)
            runStart = i
        }
    }
    flush(lines.size)
}

private fun drawScrollBlockStyleRun(
    canvas: Canvas,
    lines: List<ScrollLine>,
    fromIdx: Int,
    toIdxInclusive: Int,
    style: BlockStyle,
    pageTop: Float,
    fallbackLeft: Float,
    fallbackRight: Float,
    fontSizeScale: Float,
    readerBgArgb: Int,
) {
    val firstLine = lines[fromIdx]
    val lastLine = lines[toIdxInclusive]
    val uniformBorderWidth = style.borderWidthPx * fontSizeScale
    val halfBorder = uniformBorderWidth / 2f
    val padLeft = style.paddingLeftPx * fontSizeScale
    val padRight = style.paddingRightPx * fontSizeScale
    val padTop = style.paddingTopPx * fontSizeScale
    val padBottom = style.paddingBottomPx * fontSizeScale
    val widthScaled = style.widthPx?.let { it * fontSizeScale }
    val heightScaled = style.heightPx?.let { it * fontSizeScale }

    var minContentLeft = Float.MAX_VALUE
    var maxContentRight = -Float.MAX_VALUE
    for (i in fromIdx..toIdxInclusive) {
        for (column in lines[i].columns) {
            minContentLeft = minOf(minContentLeft, column.start)
            maxContentRight = maxOf(maxContentRight, column.end)
        }
    }
    val hasContentBounds = minContentLeft != Float.MAX_VALUE
    val ibCell = if (widthScaled != null) firstLine.cells?.firstOrNull() else null
    val rectLeft: Float
    val rectRight: Float
    when {
        ibCell != null && widthScaled != null -> {
            rectLeft = ibCell.contentLeft - padLeft - halfBorder
            rectRight = ibCell.contentLeft + widthScaled + padRight + halfBorder
        }
        widthScaled != null && hasContentBounds -> {
            val centerX = (minContentLeft + maxContentRight) / 2f
            rectLeft = centerX - widthScaled / 2f - padLeft - halfBorder
            rectRight = centerX + widthScaled / 2f + padRight + halfBorder
        }
        else -> {
            // p/div/table 的 auto width 应填满包含块，不能退化成字形包围盒。
            rectLeft = fallbackLeft - halfBorder
            rectRight = fallbackRight + halfBorder
        }
    }

    val naturalTop = pageTop + firstLine.lineTop
    val naturalBottom = pageTop + lastLine.lineBottom
    val rectTop: Float
    val rectBottom: Float
    if (heightScaled != null) {
        val centerY = (naturalTop + naturalBottom) / 2f
        rectTop = centerY - heightScaled / 2f - padTop - halfBorder
        rectBottom = centerY + heightScaled / 2f + padBottom + halfBorder
    } else {
        rectTop = naturalTop - padTop - halfBorder
        val effectiveBottom = if (
            fromIdx == toIdxInclusive && !firstLine.textBottomRel.isNaN() && isBottomOnlyBorder(style)
        ) {
            naturalTop + firstLine.textBottomRel
        } else {
            naturalBottom
        }
        rectBottom = effectiveBottom + padBottom + halfBorder
    }
    if (rectLeft >= rectRight || rectTop >= rectBottom) return
    drawBoxDecorations(
        canvas = canvas,
        style = style,
        rectLeft = rectLeft,
        rectTop = rectTop,
        rectRight = rectRight,
        rectBottom = rectBottom,
        fontSizeScale = fontSizeScale,
        readerBgArgb = readerBgArgb,
    )
}

private fun drawSingleContainerBox(
    canvas: Canvas,
    lines: List<ScrollLine>,
    fromIdx: Int,
    toIdxInclusive: Int,
    style: BlockStyle,
    pageTop: Float,
    fallbackLeft: Float,
    fallbackRight: Float,
    fontSizeScale: Float,
    readerBgArgb: Int,
) {
    if (style === BlockStyle.EMPTY) return
    val firstLine = lines[fromIdx]
    val lastLine = lines[toIdxInclusive]
    val borderWidthScaled = style.borderWidthPx * fontSizeScale
    val halfBorder = borderWidthScaled / 2f
    val padLeft = style.paddingLeftPx * fontSizeScale
    val padRight = style.paddingRightPx * fontSizeScale
    val padTop = style.paddingTopPx * fontSizeScale
    val padBottom = style.paddingBottomPx * fontSizeScale
    // **2026-05-30 widthPx 定宽 box bg 矩形**（几何公式提取到 epub-layout [BoxGeometry]，与
    // ScrollLayoutEngine contentInset 共用同核心 → bg 框两边与文字内缩严格一致）。padding 已 ×
    // fontSizeScale 传入。declWidthPx>0 在 fallback 区间居中窄框；否则满宽减 padding。
    val (rectLeft, rectRight) = BoxGeometry.bgRectX(
        declWidthPx = style.widthPx,
        fontScale = fontSizeScale,
        paddingLeftScaled = padLeft,
        paddingRightScaled = padRight,
        fallbackLeft = fallbackLeft,
        fallbackRight = fallbackRight,
        halfBorder = halfBorder,
    )
    val rectTop = pageTop + firstLine.lineTop - padTop - halfBorder
    val rectBottom = pageTop + lastLine.lineBottom + padBottom + halfBorder
    com.morealm.app.core.log.AppLog.info(
        "BoxGroup/Draw",
        "drawSingleContainerBox lines=$fromIdx..$toIdxInclusive (count=${toIdxInclusive - fromIdx + 1}) " +
            "rect=($rectLeft,$rectTop)-($rectRight,$rectBottom) " +
            "bg=${style.backgroundColor} bc=${style.borderColor} bw=$borderWidthScaled br=${style.borderRadiusPx} " +
            "bgImg=${style.backgroundImageSrc != null} sidedBorder=${hasSidedBorder(style)} " +
            "pageTop=$pageTop fontSizeScale=$fontSizeScale",
    )
    if (rectLeft >= rectRight || rectTop >= rectBottom) return
    drawBoxDecorations(
        canvas = canvas,
        style = style,
        rectLeft = rectLeft,
        rectTop = rectTop,
        rectRight = rectRight,
        rectBottom = rectBottom,
        fontSizeScale = fontSizeScale,
        readerBgArgb = readerBgArgb,
    )
}

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
    readerBgArgb: Int = Color.WHITE,
) {
    val bs = line.blockStyle
    if (bs === BlockStyle.EMPTY) return

    // 块元素 auto width 占满包含块；只有显式 width 才围绕内容定位。
    val leftX: Float
    val rightX: Float
    if (bs.widthPx != null && line.columns.isNotEmpty()) {
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
        // **2026-05-29 #2 横线贴文字视觉底**：bottom-only border（introduction `.jj { border-bottom }`）
        // 且 em 段（[ScrollLine.textBottomRel] 非 NaN，文字 top-aligned 在行内留底部 leading 空隙）时，
        // 横线贴文字视觉底而非 line.lineBottom（含行高 leading，离标题远 ~24px）。其他段 textBottomRel=
        // NaN → effBottom == lineBottom 零行为变化；整框 border（uniform / 多边）不进此判定。
        val effBottom = if (!line.textBottomRel.isNaN() && isBottomOnlyBorder(bs)) {
            lineTop + line.textBottomRel
        } else {
            lineBottom
        }
        rectBottom = effBottom + padBottom + halfBorder
    }
    // **EpubW5H/CircleBox/Draw diag (2026-05-27)** — 装饰盒最终几何：rect + radius + scale 来源。
    // 配 EpubW5H/CircleBox/Emit 对比 emit 阶段算出的 boxW/H 与 drawer 阶段的 rectW/H 是否一致，
    // 以及圆 sentinel 是否生效。仅装饰段（widthPx 非 null 或 borderRadius CIRCLE）fire。
    if (bs.widthPx != null || bs.borderRadiusPx.isInfinite()) {
        val rectW = rectRight - rectLeft
        val rectH = rectBottom - rectTop
        com.morealm.app.core.log.AppLog.info(
            "EpubW5H/CircleBox/Draw",
            "rect=($rectLeft,$rectTop)-($rectRight,$rectBottom) " +
                "size=${rectW}x${rectH} isCircleSentinel=${bs.borderRadiusPx.isInfinite()} " +
                "fontSizeScale=$fontSizeScale ibCell=${ibCell != null} " +
                "padL=$padLeft padT=$padTop borderW=$borderWidthScaled " +
                "family='${bs.fontFamily}'",
        )
    }
    drawBoxDecorations(
        canvas = canvas,
        style = bs,
        rectLeft = rectLeft,
        rectTop = rectTop,
        rectRight = rectRight,
        rectBottom = rectBottom,
        fontSizeScale = fontSizeScale,
        readerBgArgb = readerBgArgb,
    )
}

/**
 * **2026-05-28 Step 9.5 + 9.2 Phase A** —— 统一盒子装饰绘制：bg image → bg color → border → done.
 *
 * 调用顺序（z-order from back to front）：
 *  1. background-image (按 box rect contain 等比放图)
 *  2. background-color (兜底 fill；图不填满 rect 时透出 bg color)
 *  3. border:
 *     - 4 角全 null + 无 sided border → 旧 uniform `drawRoundRect` 路径
 *     - 4 角任一非 null → Path.addRoundRect(rect, FloatArray(8))
 *     - sided border 非空 → 4 条边各画 drawLine（直边）/ Path arc（圆角）
 *
 * @param fontSizeScale 装饰盒尺寸缩放（border width / radius / padding 已由 caller scale；
 *   单边 border width / 4 角 radius 在此函数内 scale）
 */
internal fun drawBoxDecorations(
    canvas: Canvas,
    style: BlockStyle,
    rectLeft: Float,
    rectTop: Float,
    rectRight: Float,
    rectBottom: Float,
    fontSizeScale: Float,
    readerBgArgb: Int = Color.WHITE,
) {
    val rectW = rectRight - rectLeft
    val rectH = rectBottom - rectTop
    if (rectW <= 0f || rectH <= 0f) return
    val uniformR = if (style.borderRadiusPx.isInfinite()) minOf(rectW, rectH) / 2f
                   else style.borderRadiusPx * fontSizeScale
    // Step 9.2 Phase A: 4 角 radius FloatArray(8)：[TL_x, TL_y, TR_x, TR_y, BR_x, BR_y, BL_x, BL_y]
    val cornerRadii: FloatArray? = buildCornerRadii(style, uniformR, fontSizeScale)
    val paint = Paint().apply { isAntiAlias = true }

    // 1. background-color — fill 在最底层（CSS spec：bg color 在 bg image 之下）
    style.backgroundColor?.let { bgArgb ->
        val fill = adaptDecorationBgForReaderBg(bgArgb, readerBgArgb)
        paint.color = fill
        paint.alpha = (fill ushr 24) and 0xFF
        drawBoxFill(canvas, rectLeft, rectTop, rectRight, rectBottom, uniformR, cornerRadii, paint)
    }

    // 2. background-image (element-level) — 在 bg color 之上，按 box rect contain 等比绘制；
    // image 加载失败时仅 bg color 兜底（绘制顺序已经先 bg color 再 image，自动满足）
    style.backgroundImageSrc?.takeIf { it.isNotEmpty() }?.let { src ->
        val bmp = ImageCache.get(src, rectW.toInt().coerceAtLeast(1))
        if (bmp != null && !bmp.isRecycled) {
            drawContainBitmap(canvas, bmp, rectLeft, rectTop, rectRight, rectBottom, paint)
        }
    }

    // 3. border —— 4 边 color/width/style 全等 → uniform path（保 DOUBLE 路径 + drawRoundRect 优化）；
    // 任一边不等 / 部分边 null → sided path
    if (hasSidedBorder(style) && !allSidesIdentical(style)) {
        drawSidedBorder(canvas, style, rectLeft, rectTop, rectRight, rectBottom, cornerRadii, uniformR, fontSizeScale, paint)
    } else {
        drawUniformBorder(
            canvas, style, rectLeft, rectTop, rectRight, rectBottom,
            uniformR, cornerRadii, fontSizeScale, paint,
        )
    }
}

/**
 * 4 边 sided border 字段全等（同 color + 同 width + 同 style）—— CSS `border: 1px solid red`
 * shorthand 经 Shorthand expander 拆成 4 边 longhand 时 4 边相同。此场景走 uniform path 保留
 * DOUBLE 路径 + 性能（一次 drawRoundRect 而非 4 条 drawLine）。
 */
private fun allSidesIdentical(style: BlockStyle): Boolean {
    val tc = style.borderTopColor ?: return false
    return tc == style.borderRightColor &&
        tc == style.borderBottomColor &&
        tc == style.borderLeftColor &&
        style.borderTopWidthPx == style.borderRightWidthPx &&
        style.borderTopWidthPx == style.borderBottomWidthPx &&
        style.borderTopWidthPx == style.borderLeftWidthPx &&
        style.borderTopStyle == style.borderRightStyle &&
        style.borderTopStyle == style.borderBottomStyle &&
        style.borderTopStyle == style.borderLeftStyle
}

/**
 * 4 角任一 override 非空 → 返回 FloatArray(8)；否则 null（caller 走 uniform drawRoundRect 路径）。
 *
 * FloatArray(8) 布局（Path.addRoundRect 契约）：[TL_x, TL_y, TR_x, TR_y, BR_x, BR_y, BL_x, BL_y]。
 * 圆角 x/y 相同（不支持椭圆）。null override 用 uniform [defaultR] 兜底。
 */
private fun buildCornerRadii(style: BlockStyle, defaultR: Float, fontSizeScale: Float): FloatArray? {
    val tl = style.borderTopLeftRadiusPx
    val tr = style.borderTopRightRadiusPx
    val br = style.borderBottomRightRadiusPx
    val bl = style.borderBottomLeftRadiusPx
    if (tl == null && tr == null && br == null && bl == null) return null
    // override 是设计 px（rootFontSize=16f 解析），需 scale；null 时用 already-scaled defaultR 兜底
    val tlR = tl?.times(fontSizeScale) ?: defaultR
    val trR = tr?.times(fontSizeScale) ?: defaultR
    val brR = br?.times(fontSizeScale) ?: defaultR
    val blR = bl?.times(fontSizeScale) ?: defaultR
    return floatArrayOf(tlR, tlR, trR, trR, brR, brR, blR, blR)
}

/** 用 uniformR 或 cornerRadii 画 fill —— 后者走 Path 路径（drawRoundRect 不支持 4 角不等）。 */
private fun drawBoxFill(
    canvas: Canvas,
    rectLeft: Float, rectTop: Float, rectRight: Float, rectBottom: Float,
    uniformR: Float,
    cornerRadii: FloatArray?,
    paint: Paint,
) {
    paint.style = Paint.Style.FILL
    if (cornerRadii == null) {
        canvas.drawRoundRect(rectLeft, rectTop, rectRight, rectBottom, uniformR, uniformR, paint)
    } else {
        val path = Path()
        path.addRoundRect(RectF(rectLeft, rectTop, rectRight, rectBottom), cornerRadii, Path.Direction.CW)
        canvas.drawPath(path, paint)
    }
}

/** Uniform border 绘制（4 边相同 color/width/style）—— 旧路径保持兼容。 */
private fun drawUniformBorder(
    canvas: Canvas,
    style: BlockStyle,
    rectLeft: Float, rectTop: Float, rectRight: Float, rectBottom: Float,
    uniformR: Float,
    cornerRadii: FloatArray?,
    fontSizeScale: Float,
    paint: Paint,
) {
    // CSS `border: 2px solid red` shorthand 经 Shorthand expander 同时填 uniform border-color/width/style
    // 与 sided border-{top,right,bottom,left}-{color,width,style}。先 uniform 字段；fallback sided
    // 字段（其中 top 当代表 —— allSidesIdentical 保证 4 边相同）。
    val bc = style.borderColor ?: style.borderTopColor ?: return
    val borderWidthScaled = (
        if (style.borderWidthPx > 0f) style.borderWidthPx else style.borderTopWidthPx
    ) * fontSizeScale
    if (borderWidthScaled <= 0f) return
    val effectiveStyle = if (style.borderColor != null) style.borderStyle else style.borderTopStyle
    paint.style = Paint.Style.STROKE
    paint.color = bc
    paint.alpha = (bc ushr 24) and 0xFF
    paint.pathEffect = null
    paint.strokeCap = Paint.Cap.BUTT
    when (effectiveStyle) {
        BlockStyle.BorderStyle.DOUBLE -> {
            val third = borderWidthScaled / 3f
            paint.strokeWidth = third
            if (cornerRadii == null) {
                canvas.drawRoundRect(rectLeft, rectTop, rectRight, rectBottom, uniformR, uniformR, paint)
                val innerOff = 2f * third
                val innerR = (uniformR - innerOff).coerceAtLeast(0f)
                canvas.drawRoundRect(
                    rectLeft + innerOff, rectTop + innerOff,
                    rectRight - innerOff, rectBottom - innerOff,
                    innerR, innerR, paint,
                )
            } else {
                val path = Path()
                path.addRoundRect(RectF(rectLeft, rectTop, rectRight, rectBottom), cornerRadii, Path.Direction.CW)
                canvas.drawPath(path, paint)
                val innerOff = 2f * third
                val innerRadii = FloatArray(8) { (cornerRadii[it] - innerOff).coerceAtLeast(0f) }
                val innerPath = Path()
                innerPath.addRoundRect(
                    RectF(rectLeft + innerOff, rectTop + innerOff, rectRight - innerOff, rectBottom - innerOff),
                    innerRadii, Path.Direction.CW,
                )
                canvas.drawPath(innerPath, paint)
            }
        }
        BlockStyle.BorderStyle.SOLID,
        BlockStyle.BorderStyle.DASHED,
        BlockStyle.BorderStyle.DOTTED,
        -> {
            paint.strokeWidth = borderWidthScaled
            configureBorderPattern(paint, effectiveStyle, borderWidthScaled)
            if (cornerRadii == null) {
                canvas.drawRoundRect(rectLeft, rectTop, rectRight, rectBottom, uniformR, uniformR, paint)
            } else {
                val path = Path()
                path.addRoundRect(RectF(rectLeft, rectTop, rectRight, rectBottom), cornerRadii, Path.Direction.CW)
                canvas.drawPath(path, paint)
            }
        }
    }
}

/**
 * **2026-05-28 Step 9.2 Phase A** —— 4 边各自 color/width/style 不同的 sided border 绘制。
 *
 * 实现：每边一条 drawLine 直边（忽略圆角部分；圆角部分由相邻 2 边共同贡献的 arc 暂不实现）。
 * 这是 Step 9.2 Phase A MVP：示例 LN A introduction `.jj { border-bottom: 2px solid #c27c88 }`
 * 一条横线 + 时间介绍 U 型盒（border-top/left/right + border-radius 顶 2 角）。
 *
 * U 型盒视觉策略：rectangle 直边足够近似（圆角部分相邻 2 边的 join 用 Path 处理太复杂，
 * 现版本接受"折线 vs 圆弧"小视觉差）。后续真要画完美圆角 sided 可换成 Path + arcTo。
 *
 * @param cornerRadii FloatArray(8) 仅用于决定哪些角"内陷"画线时不连边到角；null 时按矩形直角画
 */
private fun drawSidedBorder(
    canvas: Canvas,
    style: BlockStyle,
    rectLeft: Float, rectTop: Float, rectRight: Float, rectBottom: Float,
    cornerRadii: FloatArray?,
    uniformR: Float,
    fontSizeScale: Float,
    paint: Paint,
) {
    paint.style = Paint.Style.STROKE
    // 各边 width / color / style 优先用 sided 字段；缺省回 uniform border 字段 fallback（兼容
    // CSS `border: 1px solid red` 同时填了 uniform + 4 边 longhand 但解析器没分边的旧路径）。
    val topColor = style.borderTopColor ?: style.borderColor
    val topW = (if (style.borderTopColor != null) style.borderTopWidthPx else style.borderWidthPx) * fontSizeScale
    val rightColor = style.borderRightColor ?: style.borderColor
    val rightW = (if (style.borderRightColor != null) style.borderRightWidthPx else style.borderWidthPx) * fontSizeScale
    val bottomColor = style.borderBottomColor ?: style.borderColor
    val bottomW = (if (style.borderBottomColor != null) style.borderBottomWidthPx else style.borderWidthPx) * fontSizeScale
    val leftColor = style.borderLeftColor ?: style.borderColor
    val leftW = (if (style.borderLeftColor != null) style.borderLeftWidthPx else style.borderWidthPx) * fontSizeScale

    // 角内陷量 — 4 角各自的 radius（cornerRadii FloatArray 取 x 维度即可，已确保 x==y）
    val tlInset = cornerRadii?.get(0) ?: uniformR
    val trInset = cornerRadii?.get(2) ?: uniformR
    val brInset = cornerRadii?.get(4) ?: uniformR
    val blInset = cornerRadii?.get(6) ?: uniformR

    // 画 top 边（从 TL 圆角 right 端到 TR 圆角 left 端）
    if (topColor != null && topW > 0f) {
        paint.color = topColor
        paint.alpha = (topColor ushr 24) and 0xFF
        paint.strokeWidth = topW
        configureBorderPattern(paint, style.borderTopStyle, topW)
        canvas.drawLine(rectLeft + tlInset, rectTop, rectRight - trInset, rectTop, paint)
    }
    // 画 right 边
    if (rightColor != null && rightW > 0f) {
        paint.color = rightColor
        paint.alpha = (rightColor ushr 24) and 0xFF
        paint.strokeWidth = rightW
        configureBorderPattern(paint, style.borderRightStyle, rightW)
        canvas.drawLine(rectRight, rectTop + trInset, rectRight, rectBottom - brInset, paint)
    }
    // 画 bottom 边
    if (bottomColor != null && bottomW > 0f) {
        paint.color = bottomColor
        paint.alpha = (bottomColor ushr 24) and 0xFF
        paint.strokeWidth = bottomW
        configureBorderPattern(paint, style.borderBottomStyle, bottomW)
        canvas.drawLine(rectLeft + blInset, rectBottom, rectRight - brInset, rectBottom, paint)
    }
    // 画 left 边
    if (leftColor != null && leftW > 0f) {
        paint.color = leftColor
        paint.alpha = (leftColor ushr 24) and 0xFF
        paint.strokeWidth = leftW
        configureBorderPattern(paint, style.borderLeftStyle, leftW)
        canvas.drawLine(rectLeft, rectTop + tlInset, rectLeft, rectBottom - blInset, paint)
    }
}

private fun configureBorderPattern(
    paint: Paint,
    style: BlockStyle.BorderStyle,
    strokeWidth: Float,
) {
    val unit = strokeWidth.coerceAtLeast(1f)
    paint.strokeCap = Paint.Cap.BUTT
    paint.pathEffect = when (style) {
        BlockStyle.BorderStyle.DASHED -> DashPathEffect(floatArrayOf(unit * 4f, unit * 2f), 0f)
        BlockStyle.BorderStyle.DOTTED -> {
            paint.strokeCap = Paint.Cap.ROUND
            DashPathEffect(floatArrayOf(0.1f, unit * 2.2f), 0f)
        }
        BlockStyle.BorderStyle.SOLID,
        BlockStyle.BorderStyle.DOUBLE -> null
    }
}

private fun hasSidedBorder(style: BlockStyle): Boolean =
    style.borderTopColor != null || style.borderRightColor != null ||
        style.borderBottomColor != null || style.borderLeftColor != null

/**
 * **2026-05-29** —— 仅 bottom 边有 border（其余三边 null）—— introduction `.jj { border-bottom }`
 * 一条横线场景。#2 横线贴文字视觉底只对此场景生效，整框 / U 型盒不受影响。
 */
private fun isBottomOnlyBorder(style: BlockStyle): Boolean =
    style.borderBottomColor != null &&
        style.borderTopColor == null &&
        style.borderLeftColor == null &&
        style.borderRightColor == null

/**
 * **2026-05-28 Step 9.5** —— CSS `background-size: contain` 风格 —— 等比缩放图到 rect 范围内
 * 不裁剪居中绘制。dst rect 完全在 rect 范围内（contain 不填满时四周留白透出 bg color）。
 *
 * 等比算法：scale = min(rectW/imgW, rectH/imgH)，dstW = imgW × scale，dstH = imgH × scale，
 * dst 居中。
 */
private fun drawContainBitmap(
    canvas: Canvas,
    bmp: Bitmap,
    rectLeft: Float, rectTop: Float, rectRight: Float, rectBottom: Float,
    paint: Paint,
) {
    val rectW = rectRight - rectLeft
    val rectH = rectBottom - rectTop
    val imgW = bmp.width.toFloat()
    val imgH = bmp.height.toFloat()
    if (imgW <= 0f || imgH <= 0f || rectW <= 0f || rectH <= 0f) return
    val scale = minOf(rectW / imgW, rectH / imgH)
    val dstW = imgW * scale
    val dstH = imgH * scale
    val dstLeft = rectLeft + (rectW - dstW) / 2f
    val dstTop = rectTop + (rectH - dstH) / 2f
    val src = Rect(0, 0, bmp.width, bmp.height)
    val dst = RectF(dstLeft, dstTop, dstLeft + dstW, dstTop + dstH)
    // 复用 paint 但确保 alpha=255 + filter on
    paint.style = Paint.Style.FILL
    paint.color = -0x1  // 0xFFFFFFFF（不影响 bitmap pixel，drawBitmap 忽略 paint.color）
    paint.alpha = 0xFF
    paint.isFilterBitmap = true
    canvas.drawBitmap(bmp, src, dst, paint)
}
