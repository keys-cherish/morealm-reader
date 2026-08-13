package com.morealm.app.ui.reader.renderer.scroll

import android.graphics.Canvas
import android.graphics.Paint
import com.morealm.epub.render.EmphasisMark

/**
 * **v23 着重号绘制** —— 在单个字符旁画 CSS `text-emphasis` 记号（点 / 圈 / 芝麻点 ×
 * 实心 / 空心 × 字下 / 字上）。
 *
 * 几何口径（相对该字符绘制时的有效字号 [textSizePx]）：
 *  - dot 半径 0.09×，circle 半径 0.14×，sesame 椭圆 0.10×0.18× 旋转 45°
 *  - 字下：记号中心 = baseline + 0.24×（CJK descent 区之下、行距空隙内）
 *  - 字上：记号中心 = baseline − 0.98×（字面顶之上）
 *
 * 复用调用方的 [paint]（绘制后恢复 color / style / strokeWidth），零分配。
 * 记号颜色由调用方解析好传入（text-emphasis-color 经夜间适配 > 字符前景色）。
 */
internal fun drawEmphasisMark(
    canvas: Canvas,
    mark: EmphasisMark,
    centerX: Float,
    baselineY: Float,
    textSizePx: Float,
    paint: Paint,
    colorArgb: Int,
) {
    val savedColor = paint.color
    val savedStyle = paint.style
    val savedStroke = paint.strokeWidth
    paint.color = colorArgb
    val strokeW = (textSizePx * 0.045f).coerceAtLeast(1f)
    if (mark.filled) {
        paint.style = Paint.Style.FILL
    } else {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeW
    }
    val cy = if (mark.under) baselineY + textSizePx * 0.24f else baselineY - textSizePx * 0.98f
    when (mark.shape) {
        'c' -> canvas.drawCircle(centerX, cy, textSizePx * 0.14f, paint)
        's' -> {
            // 芝麻点（、形）：小椭圆旋转 45°
            val rx = textSizePx * 0.10f
            val ry = textSizePx * 0.18f
            val save = canvas.save()
            canvas.rotate(45f, centerX, cy)
            canvas.drawOval(centerX - rx / 2f, cy - ry / 2f, centerX + rx / 2f, cy + ry / 2f, paint)
            canvas.restoreToCount(save)
        }
        else -> canvas.drawCircle(centerX, cy, textSizePx * 0.09f, paint)
    }
    paint.color = savedColor
    paint.style = savedStyle
    paint.strokeWidth = savedStroke
}
