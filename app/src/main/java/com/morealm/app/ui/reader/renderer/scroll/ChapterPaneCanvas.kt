package com.morealm.app.ui.reader.renderer.scroll

import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.text.TextPaint
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.entity.Highlight
import com.morealm.app.domain.render.scroll.ScrollChapterLayout
import com.morealm.app.domain.render.scroll.ScrollHighlightDrawSpec

/**
 * 单章 Canvas 子树 —— [ScrollCanvasRenderer] 三块面板的子组件。
 *
 * 三层绘制（按顺序，后画覆盖先画）：
 *   1. **背景层**：KIND_BACKGROUND 高亮 rect
 *   2. **文字层**：按 ScrollColumn 坐标 drawText；KIND_TEXT_COLOR 命中 cp 时
 *      paint.color 替换为高亮 argb；其他文字用默认 paint
 *   3. **下划线层**：KIND_UNDERLINE 在 rect.bottom 下方画线，4 种线型：
 *      SOLID / DASHED / DOTTED / WAVY（quadraticTo 二次贝塞尔波浪）
 *
 * 高度契约：Modifier 由父 Layout（[ScrollCanvasRenderer]）强制
 * `Constraints.fixed(viewWidth, totalHeight)`，Canvas drawScope size 等同。
 *
 * 当前 paint hardcoded；M2.7 接入 ReaderStyle / 主题切换时替换为 ViewModel 注入。
 *
 * @param chapter 整章已排版结果
 * @param highlightSpecs 高亮 spec 列表（由 [com.morealm.app.domain.render.scroll.ScrollHighlightProjector]
 *                       投影得到）；该章不含高亮传 emptyList
 * @param viewportTop 当前 viewport 上界（相对章顶 y）—— M2.7 视口剔除用
 * @param viewportBottom 当前 viewport 下界（相对章顶 y）—— M2.7 视口剔除用
 */
@Composable
fun ChapterPaneCanvas(
    chapter: ScrollChapterLayout,
    /**
     * 正文 paint —— 必须与排版时 [com.morealm.app.domain.render.scroll.ScrollLayoutEngine]
     * 使用的 contentPaint 是同一份（同 fontSize / typeface / letterSpacing / bold），
     * 否则字符宽度与 ScrollColumn.start/end 错位，画出来的字会偏移甚至吃字。
     */
    contentPaint: TextPaint,
    titlePaint: TextPaint,
    chapterNumPaint: TextPaint,
    highlightSpecs: List<ScrollHighlightDrawSpec> = emptyList(),
    /**
     * 跳转后呼吸高亮（V1 RevealHighlight 等价）。命中本章时按 cp 范围画半透明 rect 覆盖
     * 文字（在 highlightSpecs 之上、文字之下），alpha 由 caller 持有的 Animatable 驱动。
     */
    revealHighlight: com.morealm.app.ui.reader.renderer.RevealHighlight? = null,
    /** 搜索高亮 cp 范围；EMPTY = 不画 */
    searchHighlightCpRange: IntRange = IntRange.EMPTY,
    /** 搜索高亮 argb */
    searchHighlightArgb: Int = 0x55FFFF00.toInt(),
    /** Viewport y 范围 lambda（相对章顶）。null = 该章完全不在 viewport，整章 skip。 */
    viewportRangeProvider: () -> Pair<Float, Float>? = { null },
    modifier: Modifier = Modifier,
) {
    val contentAscent = remember(contentPaint) { -contentPaint.fontMetrics.ascent }
    val titleAscent = remember(titlePaint) { -titlePaint.fontMetrics.ascent }
    val chapterNumAscent = remember(chapterNumPaint) { -chapterNumPaint.fontMetrics.ascent }

    // 高亮 spec 按 kind 预分组（避免每帧再 filter）
    val bgSpecs = remember(highlightSpecs) { highlightSpecs.filter { it.kind == Highlight.KIND_BACKGROUND } }
    val textColorSpecs = remember(highlightSpecs) { highlightSpecs.filter { it.kind == Highlight.KIND_TEXT_COLOR } }
    val underlineSpecs = remember(highlightSpecs) { highlightSpecs.filter { it.kind == Highlight.KIND_UNDERLINE } }

    // KIND_TEXT_COLOR：cp → argb 映射，O(1) 查；同 cp 多个 spec 取最后一个（后写的覆盖）
    val textColorByCp = remember(textColorSpecs) {
        val map = HashMap<Int, Int>()
        for (spec in textColorSpecs) {
            for (cp in spec.cpRangeFirst..spec.cpRangeLast) {
                map[cp] = spec.argb
            }
        }
        map
    }

    val bgFillPaint = remember {
        Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }
    }

    // 诊断日志：每章首帧记录 Canvas drawScope 实际尺寸 vs layout viewWidth，
    // 看是否吻合 —— 若 drawScope.width < chapter.viewWidth → 排版按 view 宽，画
    // 出来的字超出 canvas 实际边被截 = "右边吃掉字"。
    val firstDrawLogged = remember(chapter.chapterIndex, chapter.viewWidth) { booleanArrayOf(false) }

    Canvas(modifier) {
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            if (!firstDrawLogged[0]) {
                firstDrawLogged[0] = true
                AppLog.info(
                    "ChapterPaneCanvas",
                    "DRAW idx=${chapter.chapterIndex} canvasSize=${size.width.toInt()}x${size.height.toInt()} " +
                        "layoutViewWidth=${chapter.viewWidth} paddingLeft=${chapter.paddingLeft} " +
                        "overflow=${chapter.viewWidth > size.width.toInt()}",
                )
            }
            // paddingLeft 偏移：column.start 是相对 paddingLeft 内侧的 x（0..visibleWidth），
            // translate 让所有 drawText / drawRect 整体右移 paddingLeft，避免内容贴屏幕左缘。
            nc.save()
            nc.translate(chapter.paddingLeft.toFloat(), 0f)

            // ─── 视口剔除：viewport 范围 lambda 在 draw scope 内读 state，
            //              享受 draw-only re-execution（pixelOffset 变化只重 draw 不 measure）
            val range = viewportRangeProvider()
            if (range == null) {
                nc.restore()
                return@drawIntoCanvas
            }

            // 给一点缓冲（200px）避免边界 page 突然出现/消失闪烁
            val viewportTop = range.first - 200f
            // 兜底：viewportHeightPx 在首帧 onSizeChanged 触发前 = 0，range.second == range.first
            // → 视口范围 ±200 让首页一半被剔除（用户反馈：屏幕显示一半）。
            // 检测到视口高度过小（< 400）时跳过剔除（画整章），首帧不掉内容。
            val rawHeight = range.second - range.first
            val viewportBottom = if (rawHeight < 400f) Float.MAX_VALUE else range.second + 200f

            // ─── 层 1：背景高亮 rect（仅画与 viewport 相交的 rect）───
            for (spec in bgSpecs) {
                bgFillPaint.color = spec.argb
                for (rect in spec.rects) {
                    if (rect.bottom < viewportTop || rect.top > viewportBottom) continue
                    nc.drawRect(rect.left, rect.top, rect.right, rect.bottom, bgFillPaint)
                }
            }

            // ─── 层 1.5：搜索高亮（与背景同层但偏前；alpha 通常较高让用户一眼看到命中）───
            if (!searchHighlightCpRange.isEmpty()) {
                bgFillPaint.color = searchHighlightArgb
                drawCpRangeRects(
                    nc, chapter, searchHighlightCpRange.first, searchHighlightCpRange.last + 1,
                    viewportTop, viewportBottom, bgFillPaint,
                )
            }

            // ─── 层 1.6：RevealHighlight 跳转后呼吸高亮（alpha Animatable 衰减）───
            // 与搜索同款 rect 算法，但 argb 用 reveal.currentArgb()（含 alpha 缩放）。
            revealHighlight?.let { rev ->
                val argb = rev.currentArgb()
                if ((argb ushr 24) > 0) {
                    bgFillPaint.color = argb
                    drawCpRangeRects(
                        nc, chapter, rev.startChapterPos, rev.endChapterPos,
                        viewportTop, viewportBottom, bgFillPaint,
                    )
                }
            }

            // ─── 层 2：文字（按 line 类型选 paint；KIND_TEXT_COLOR cp 命中时替换 paint.color） ───
            var pageOffsetY = 0f
            for (page in chapter.pages) {
                val pageTop = pageOffsetY
                val pageBottom = pageTop + page.height
                pageOffsetY = pageBottom
                // 视口剔除：page 完全在 viewport 之上 / 之下 → 整页 skip
                if (pageBottom < viewportTop || pageTop > viewportBottom) continue
                for (line in page.lines) {
                    if (line.isImage) continue  // M2.5 接入图片绘制
                    val paint: TextPaint
                    val ascent: Float
                    val defaultColor: Int
                    when {
                        line.isChapterNum -> {
                            paint = chapterNumPaint
                            ascent = chapterNumAscent
                            defaultColor = paint.color
                        }
                        line.isTitle -> {
                            paint = titlePaint
                            ascent = titleAscent
                            defaultColor = paint.color
                        }
                        else -> {
                            paint = contentPaint
                            ascent = contentAscent
                            defaultColor = paint.color
                        }
                    }
                    val baselineY = pageTop + line.lineTop + ascent
                    for (col in line.columns) {
                        val overrideColor = textColorByCp[col.chapterPosition]
                        if (overrideColor != null) {
                            paint.color = overrideColor
                            nc.drawText(col.charData, col.start, baselineY, paint)
                            paint.color = defaultColor
                        } else {
                            nc.drawText(col.charData, col.start, baselineY, paint)
                        }
                    }
                }
                // pageOffsetY 已在循环顶累加（move 到顶为视口剔除提前）
            }

            // ─── 层 3：下划线（4 种线型：SOLID / DASHED / DOTTED / WAVY），按 viewport 剔除 ───
            for (spec in underlineSpecs) {
                val linePaint = underlinePaintFor(spec.argb, spec.underlineStyle)
                for (rect in spec.rects) {
                    if (rect.bottom < viewportTop || rect.top > viewportBottom) continue
                    val underlineY = rect.bottom - 2f  // 字符 baseline 下方 2px 处
                    when (spec.underlineStyle) {
                        Highlight.UNDERLINE_STYLE_WAVY -> drawWavyUnderline(
                            nc, linePaint, rect.left, rect.right, underlineY,
                        )
                        else -> nc.drawLine(rect.left, underlineY, rect.right, underlineY, linePaint)
                    }
                }
            }

            nc.restore()  // 平衡前面 nc.save() + translate(paddingLeft)
        }
    }
}

/**
 * 按线型返回 Paint。SOLID 无 pathEffect；DASHED / DOTTED 用 [DashPathEffect]；
 * WAVY 用默认 Paint（绘制时走 [drawWavyUnderline] 单独 Path 路径）。
 *
 * 每次调用 new Paint：调用频率 = 下划线 spec 数（同章一般几个），可接受。
 * 若 M6 性能压测发现热点可改 remember 缓存。
 */
private fun underlinePaintFor(argb: Int, underlineStyle: Int): Paint = Paint().apply {
    color = argb
    strokeWidth = 3f
    style = Paint.Style.STROKE
    isAntiAlias = true
    pathEffect = when (underlineStyle) {
        Highlight.UNDERLINE_STYLE_DASHED -> DashPathEffect(floatArrayOf(12f, 6f), 0f)
        Highlight.UNDERLINE_STYLE_DOTTED -> DashPathEffect(floatArrayOf(2f, 6f), 0f)
        else -> null
    }
}

/**
 * 波浪下划线 —— 用 quadraticTo 二次贝塞尔曲线段拼接 sin 波形。
 *
 * 振幅 ≈ 3px，周期 ≈ 12px（每周期 = 1 个完整 sin 波）。每半周期一段贝塞尔，
 * 控制点交替在 baseline 上下 amplitude 处，达到平滑波浪视觉。
 */
private fun drawWavyUnderline(
    canvas: android.graphics.Canvas,
    paint: Paint,
    left: Float,
    right: Float,
    baselineY: Float,
) {
    if (right - left <= 0f) return
    val amplitude = 3f
    val period = 12f
    val halfPeriod = period / 2f
    val path = Path()
    path.moveTo(left, baselineY)
    var x = left
    var phaseUp = true  // 控制点交替在 baseline 上方 / 下方
    while (x < right) {
        val nextX = minOf(x + halfPeriod, right)
        val controlX = (x + nextX) / 2f
        val controlY = baselineY + if (phaseUp) -amplitude else amplitude
        path.quadTo(controlX, controlY, nextX, baselineY)
        x = nextX
        phaseUp = !phaseUp
    }
    canvas.drawPath(path, paint)
}

/**
 * 把 [startCp, endCp) 范围的字符按行画成 rect，已视口剔除。用于搜索高亮 /
 * RevealHighlight 等"按 cp 范围实时算 rect"场景（与 ScrollHighlightDrawSpec.rects
 * 预算缓存的场景不同 —— 那些是稳定状态，这些是动态状态没必要每帧重算 spec）。
 *
 * 算法（与 ScrollHighlightProjector 等价）：
 *   1. 遍历 chapter.pages 累加 pageOffsetY
 *   2. 每页内遍历 line；按 line.firstChapterPos / lastChapterPos 与 [startCp, endCp)
 *      求交，命中行扫 columns 找 [leftX, rightX]，画 rect(leftX, pageTop+lineTop,
 *      rightX, pageTop+lineBottom)
 *   3. 视口剔除：page 或 line 完全在 viewport 之外 → skip
 */
private fun drawCpRangeRects(
    canvas: android.graphics.Canvas,
    chapter: ScrollChapterLayout,
    startCp: Int,
    endCp: Int,  // exclusive
    viewportTop: Float,
    viewportBottom: Float,
    paint: Paint,
) {
    if (endCp <= startCp) return
    var pageOffsetY = 0f
    for (page in chapter.pages) {
        val pageTop = pageOffsetY
        val pageBottom = pageTop + page.height
        pageOffsetY = pageBottom
        if (pageBottom < viewportTop || pageTop > viewportBottom) continue
        for (line in page.lines) {
            // 行 cp 范围与目标范围求交：line.firstChapterPos..line.lastChapterPos 与
            // [startCp, endCp) 重叠才命中
            if (line.lastChapterPos < startCp || line.firstChapterPos >= endCp) continue
            val rectTop = pageTop + line.lineTop
            val rectBottom = pageTop + line.lineBottom
            if (rectBottom < viewportTop || rectTop > viewportBottom) continue
            if (line.columns.isEmpty()) {
                // 空段 / 图片段：整行宽 rect（用 chapter visibleWidth 作宽度近似）
                canvas.drawRect(0f, rectTop, (chapter.viewWidth - chapter.paddingLeft * 2).toFloat(), rectBottom, paint)
                continue
            }
            var leftX: Float? = null
            var rightX: Float? = null
            for (col in line.columns) {
                if (col.chapterPosition >= startCp && col.chapterPosition < endCp) {
                    if (leftX == null) leftX = col.start
                    rightX = col.end
                }
            }
            if (leftX != null && rightX != null) {
                canvas.drawRect(leftX, rectTop, rightX, rectBottom, paint)
            }
        }
    }
}
