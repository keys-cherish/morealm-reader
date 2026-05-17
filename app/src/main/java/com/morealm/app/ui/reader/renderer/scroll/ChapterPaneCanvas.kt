package com.morealm.app.ui.reader.renderer.scroll

import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.TextPaint
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
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
    highlightSpecs: List<ScrollHighlightDrawSpec> = emptyList(),
    @Suppress("UNUSED_PARAMETER") viewportTop: Float = 0f,
    @Suppress("UNUSED_PARAMETER") viewportBottom: Float = 0f,
    modifier: Modifier = Modifier,
) {
    val contentPaint = remember {
        TextPaint().apply {
            textSize = 48f
            color = Color.BLACK
            isAntiAlias = true
        }
    }
    val titlePaint = remember {
        TextPaint().apply {
            textSize = 72f
            color = Color.BLACK
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
    }
    val chapterNumPaint = remember {
        TextPaint().apply {
            textSize = 36f
            color = Color.parseColor("#FF9800")
            isAntiAlias = true
        }
    }
    val contentAscent = remember { -contentPaint.fontMetrics.ascent }
    val titleAscent = remember { -titlePaint.fontMetrics.ascent }
    val chapterNumAscent = remember { -chapterNumPaint.fontMetrics.ascent }

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

    Canvas(modifier) {
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas

            // ─── 层 1：背景高亮 rect ───
            for (spec in bgSpecs) {
                bgFillPaint.color = spec.argb
                for (rect in spec.rects) {
                    nc.drawRect(rect.left, rect.top, rect.right, rect.bottom, bgFillPaint)
                }
            }

            // ─── 层 2：文字（按 line 类型选 paint；KIND_TEXT_COLOR cp 命中时替换 paint.color） ───
            var pageOffsetY = 0f
            for (page in chapter.pages) {
                val pageTop = pageOffsetY
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
                pageOffsetY += page.height
            }

            // ─── 层 3：下划线（4 种线型：SOLID / DASHED / DOTTED / WAVY） ───
            for (spec in underlineSpecs) {
                val linePaint = underlinePaintFor(spec.argb, spec.underlineStyle)
                for (rect in spec.rects) {
                    val underlineY = rect.bottom - 2f  // 字符 baseline 下方 2px 处
                    when (spec.underlineStyle) {
                        Highlight.UNDERLINE_STYLE_WAVY -> drawWavyUnderline(
                            nc, linePaint, rect.left, rect.right, underlineY,
                        )
                        else -> nc.drawLine(rect.left, underlineY, rect.right, underlineY, linePaint)
                    }
                }
            }
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
