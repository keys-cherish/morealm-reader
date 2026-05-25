package com.morealm.app.ui.reader.page

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect

/**
 * Fake [PageBitmapProvider] —— 渲染**固定色块** Bitmap，每页颜色与序号映射可见，
 * 末页画 X 标记。用于：
 *
 *  1. **P3-2 androidTest** 动画快照测试 —— FakeProvider 让 Compose UI test 验证
 *     翻页手势 / 阈值 / 回弹 / 双击行为时**完全脱离真实渲染**，动画 bug
 *     可以单独复现
 *  2. **dogfood 调试** —— 设置面板（debug build）可切到 fake mode 验证翻页层
 *     行为不依赖渲染层
 *
 * 颜色分配（默认 [defaultMarker]）：
 *  - page 0 → 红
 *  - page 1 → 绿
 *  - page 2 → 蓝
 *  - page 3 → 黄
 *  - 其他 → 灰
 *  - 末页（[pageCount] - 1）→ 在中心叠加大 X
 *
 * @param pageCount 总页数（≥ 1）
 * @param markers 序号到颜色的映射；默认 [defaultMarker]
 */
class FakePageBitmapProvider(
    override val pageCount: Int = 14,
    private val markers: (Int) -> Int = ::defaultMarker,
) : PageBitmapProvider {

    override fun bitmapAt(displayIndex: Int, w: Int, h: Int): Bitmap? {
        if (displayIndex !in 0 until pageCount) return null
        if (w <= 0 || h <= 0) return null
        val bg = markers(displayIndex)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(bg)

        // 大序号显示，便于动画快照视觉判断当前是哪一页
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = (h / 8f).coerceAtLeast(24f)
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val cx = w / 2f
        val cy = h / 2f - (labelPaint.ascent() + labelPaint.descent()) / 2f
        canvas.drawText("page $displayIndex", cx, cy, labelPaint)

        // 末页画 X 标识结尾
        if (displayIndex == pageCount - 1) {
            val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                strokeWidth = (h / 40f).coerceAtLeast(4f)
                style = Paint.Style.STROKE
            }
            val pad = w / 8f
            canvas.drawLine(pad, h - pad, w - pad, pad, xPaint)
            canvas.drawLine(pad, pad, w - pad, h - pad, xPaint)
        }

        // 边框，便于 swipe 时辨识页边界
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        canvas.drawRect(Rect(2, 2, w - 2, h - 2), borderPaint)

        return bmp
    }

    companion object {
        fun defaultMarker(index: Int): Int = when (index % 5) {
            0 -> 0xFFEF5350.toInt() // red 400
            1 -> 0xFF66BB6A.toInt() // green 400
            2 -> 0xFF42A5F5.toInt() // blue 400
            3 -> 0xFFFFEE58.toInt() // yellow 400
            else -> 0xFF90A4AE.toInt() // blue-gray 400
        }
    }
}
