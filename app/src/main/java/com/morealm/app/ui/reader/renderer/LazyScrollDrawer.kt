package com.morealm.app.ui.reader.renderer

import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import com.morealm.app.domain.render.ImageColumn
import com.morealm.app.domain.render.ScrollParagraph
import com.morealm.app.domain.render.TextBaseColumn
import com.morealm.app.domain.render.TextHtmlColumn

/**
 * 把一个 [ScrollParagraph] 画到 canvas 上，对应 LazyColumn item 内部 Canvas。
 *
 * ── 与 [drawPageContent] 的关系 ──
 *
 * 本函数借鉴 [drawPageContent] 的 line 级绘制路径（背景高亮 → 装饰条 → 字符），
 * 但去掉以下页级元素：
 *   - 书签三角（[drawPageContent] 的 `hasBookmark`）—— LazyColumn 中由
 *     专门的 overlay 段提供（如 BOOKMARK contentType，或顶部 floating UI）
 *   - 页内 `paddingTop` 累加 —— 段坐标系已在 [ScrollParagraph.linePositions] 处理
 *   - search/selection/aloud 高亮 —— Phase 4 补；当前段绘制只画静态文字 + 装饰条
 *
 * ── 坐标系 ──
 *
 * Canvas 输入坐标：item 自己的 (0,0) 在段顶（含 [ScrollParagraph.paddingTop]）。
 * 行 i 的 baseline Y = `linePositions[i] + (line.lineBase - line.lineTop)`。
 *
 * line 的 [com.morealm.app.domain.render.TextLine.lineTop] / `lineBase` / `lineBottom`
 * 是 page-local 坐标（来自排版器原始输出，本函数不修改这些值，避免污染其他模块）。
 * 段内 Y 通过 `linePositions[i] + (lineBase - lineTop)` 这种相对量算出。
 *
 * ── 性能 ──
 *
 * 1. paint 复用本文件内 lazy 单例（`drawerHighlightPaint` / `drawerSpacingPaint`），
 *    UI 主线程独占访问，无并发风险
 * 2. 仅绘制段内行；LOADING 占位段（lines 为空）直接 return，零绘制成本
 * 3. 每行绘制路径与 [drawPageContent] 内层等价，主线程一次 drawText 一次列循环
 */
internal fun drawScrollParagraphContent(
    canvas: Canvas,
    paragraph: ScrollParagraph,
    titlePaint: TextPaint,
    contentPaint: TextPaint,
    chapterNumPaint: TextPaint?,
) {
    if (paragraph.lines.isEmpty()) return // LOADING 段或异常空段

    val highlightPaint = drawerHighlightPaint
    val spacingPaint = drawerSpacingPaint
    val lines = paragraph.lines
    val positions = paragraph.linePositions

    for (i in lines.indices) {
        val line = lines[i]
        val paint = when {
            line.isChapterNum && chapterNumPaint != null -> chapterNumPaint
            line.isTitle -> titlePaint
            else -> contentPaint
        }

        // 段内 Y：linePositions[i] 是行的「段内 top」，加上 (lineBase - lineTop) 得到段内 baseline。
        // 这样保留了排版器为每行算好的 baseline 偏移（与字号/行距/字体度量绑定），不重新计算。
        val paragraphLineTop = positions[i]
        val paragraphLineBottom = paragraphLineTop + (line.lineBottom - line.lineTop)
        val paragraphLineBase = paragraphLineTop + (line.lineBase - line.lineTop)

        // 1. 装饰条（章号末行下方的 accent bar）—— 与 drawPageContent L347-355 等价
        if (line.isTitleEnd && chapterNumPaint != null) {
            val densityScale = (contentPaint.textSize / 18f).coerceIn(1f, 3f)
            val barWidth = 32f * densityScale
            val barHeight = 2f
            val barY = paragraphLineBottom + contentPaint.textSize * 0.35f
            val barX = line.columns.firstOrNull()?.start ?: 0f
            highlightPaint.color = chapterNumPaint.color
            canvas.drawRect(barX, barY, barX + barWidth, barY + barHeight, highlightPaint)
        }

        // 2. 文字 / 图片
        if (line.isImage) {
            for (col in line.columns) {
                if (col is ImageColumn) {
                    drawImageColumn(canvas, col, line)
                }
            }
        } else {
            // letterSpacing 路径：line 自己声明了 extraLetterSpacing 时，临时改写一份 spacingPaint
            // 防止把共享 paint 弄脏。否则直接用基础 paint。
            val drawPaint = if (line.extraLetterSpacing != 0f) {
                spacingPaint.set(paint)
                spacingPaint.letterSpacing = paint.letterSpacing + line.extraLetterSpacing
                spacingPaint
            } else {
                paint
            }
            for (col in line.columns) {
                if (col is TextBaseColumn) {
                    val actualPaint = if (col is TextHtmlColumn) {
                        spacingPaint.set(drawPaint)
                        spacingPaint.textSize = col.textSize
                        col.textColor?.let { spacingPaint.color = it }
                        spacingPaint
                    } else {
                        drawPaint
                    }
                    canvas.drawText(
                        col.charData,
                        col.start + line.extraLetterSpacingOffsetX,
                        paragraphLineBase,
                        actualPaint,
                    )
                }
            }
        }
    }
}

// ── 文件内独占的共享 paint —— 不与 [PageContentDrawer] 共用，避免跨文件可见性 ──

/** 高亮矩形 paint（装饰条等用）。UI 主线程独占。 */
private val drawerHighlightPaint by lazy {
    Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
}

/** letterSpacing / TextHtmlColumn 临时 paint。UI 主线程独占。 */
private val drawerSpacingPaint by lazy { TextPaint() }
