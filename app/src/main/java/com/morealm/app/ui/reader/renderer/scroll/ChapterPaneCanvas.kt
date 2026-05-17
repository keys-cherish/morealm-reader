package com.morealm.app.ui.reader.renderer.scroll

import android.graphics.Color
import android.graphics.Typeface
import android.text.TextPaint
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.morealm.app.domain.render.scroll.ScrollChapterLayout

/**
 * 单章 Canvas 子树 —— [ScrollCanvasRenderer] 三块面板的子组件。
 *
 * 渲染 [ScrollChapterLayout] 整章像素级排版结果：按行遍历，按
 * [com.morealm.app.domain.render.scroll.ScrollColumn] 坐标调 `nativeCanvas.drawText`
 * 绘制。三种 paint：
 *   - **content paint**：正文（黑色 48f）
 *   - **title paint**：章首主标题（黑色加粗 72f）
 *   - **chapterNum paint**：章首序号（橙色 36f）
 *
 * 高度契约：Modifier 由父 Layout（[ScrollCanvasRenderer]）强制 `Constraints.fixed(viewWidth, totalHeight)`，
 * Canvas drawScope 的 size 即等于 `(viewWidth, totalHeight)`。
 *
 * M2.3 范围：基础文字绘制，**不含**：
 *   - 视口剔除（M2.7 性能优化阶段补，当前画整章所有行）
 *   - 图片段绘制（M2.5 接入 Coil 异步加载）
 *   - 装饰横条（章末块下方 accent bar，M2.7）
 *   - 高亮 / TTS / 选区背景（M3-M4）
 *
 * 当前 paint hardcoded；M2.7 接入 ReaderStyle / 主题切换时替换为 ViewModel 注入的 paint。
 *
 * @param chapter 整章已排版结果
 * @param viewportTop 当前 viewport 上界（相对章顶 y）—— M2.7 视口剔除用
 * @param viewportBottom 当前 viewport 下界（相对章顶 y）—— M2.7 视口剔除用
 */
@Composable
fun ChapterPaneCanvas(
    chapter: ScrollChapterLayout,
    @Suppress("UNUSED_PARAMETER") viewportTop: Float,
    @Suppress("UNUSED_PARAMETER") viewportBottom: Float,
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
    // 预算 baseline 偏移：lineTop + (-fontMetrics.ascent) = lineTop 到字符 baseline 的距离。
    // ascent 是负值（baseline 之上的距离），取负转正。fontMetrics 在 paint 不变时稳定，remember 避免重算。
    val contentAscent = remember { -contentPaint.fontMetrics.ascent }
    val titleAscent = remember { -titlePaint.fontMetrics.ascent }
    val chapterNumAscent = remember { -chapterNumPaint.fontMetrics.ascent }

    Canvas(modifier) {
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            var pageOffsetY = 0f
            for (page in chapter.pages) {
                val pageTop = pageOffsetY
                for (line in page.lines) {
                    // 图片段 M2.5 接入；空段（columns 空）天然 skip 因为内层 for 不执行
                    if (line.isImage) continue
                    val paint: TextPaint
                    val ascent: Float
                    when {
                        line.isChapterNum -> { paint = chapterNumPaint; ascent = chapterNumAscent }
                        line.isTitle -> { paint = titlePaint; ascent = titleAscent }
                        else -> { paint = contentPaint; ascent = contentAscent }
                    }
                    val baselineY = pageTop + line.lineTop + ascent
                    for (col in line.columns) {
                        nc.drawText(col.charData, col.start, baselineY, paint)
                    }
                }
                pageOffsetY += page.height
            }
        }
    }
}
