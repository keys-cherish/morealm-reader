package com.morealm.app.ui.reader.renderer.scroll

import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import com.morealm.app.ui.reader.renderer.adaptDecorationBgForReaderBg
import com.morealm.epub.render.TextRun

/**
 * **Step 9.2 Phase B / Phase 4** —— 字符级 inline 背景盒子绘制（封面/简介页「内容简介」单字
 * 粉色方块等场景）。
 *
 * 在 drawText **之前**调用：[TextRun.inlineBgArgb] 非空时按 box 几何 drawRect 画底层背景方块；
 * 为 null 时直接返回，纯文本 fast path 零开销。
 *
 * 几何（design.md Section 5.2，与 emit 阶段 `width = padL + textAdvance + padR` 对齐）：
 *  - box 左 = atomX（= 当前 atom 起点 x，含 CSS text-align 偏移）
 *  - box 右 = atomX + padL + textAdvance + padR（= atomX + run.width）
 *  - box 上 = baselineY + fontMetrics.ascent  - padT（ascent 为负）
 *  - box 下 = baselineY + fontMetrics.descent + padB
 *  - 文字 baseline 不变，textX 由 caller 偏移成 atomX + padL
 *
 * [textPaint] 须为已按 [TextRun.sizeScale] 调过 textSize 的 paint，让 fontMetrics / measureText
 * 反映实际字号。
 */

/** 文件级单例 —— inline bg 矩形 paint。绘制均在主线程，复用避免每 atom new。 */
private val inlineBgPaint = Paint().apply { style = Paint.Style.FILL }

internal fun drawInlineBg(
    canvas: Canvas,
    run: TextRun,
    atomX: Float,
    baselineY: Float,
    textPaint: TextPaint,
    readerBgArgb: Int,
) {
    val bg = run.inlineBgArgb ?: return
    inlineBgPaint.color = adaptDecorationBgForReaderBg(bg, readerBgArgb)
    val fm = textPaint.fontMetrics
    val textAdvance = textPaint.measureText(run.text)
    val boxRight = atomX + run.inlineBgPaddingLeftPx + textAdvance + run.inlineBgPaddingRightPx
    val boxTop = baselineY + fm.ascent - run.inlineBgPaddingTopPx
    val boxBottom = baselineY + fm.descent + run.inlineBgPaddingBottomPx
    val radius = if (run.inlineBgBorderRadiusPx.isInfinite()) {
        minOf(boxRight - atomX, boxBottom - boxTop) / 2f
    } else {
        run.inlineBgBorderRadiusPx.coerceAtLeast(0f)
    }
    canvas.drawRoundRect(atomX, boxTop, boxRight, boxBottom, radius, radius, inlineBgPaint)
}
