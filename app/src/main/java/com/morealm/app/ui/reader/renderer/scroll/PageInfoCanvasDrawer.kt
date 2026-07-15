package com.morealm.app.ui.reader.renderer.scroll

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.text.TextPaint
import android.text.TextUtils
import kotlin.math.max

/** 原生 Canvas 页眉页脚绘制器；实例内复用 Paint/Path，避免每次生成整页位图时制造临时对象。 */
internal class PageInfoCanvasDrawer {
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val batteryDrawer = BatteryIconCanvasDrawer()

    fun draw(
        canvas: Canvas,
        snapshot: PageInfoSnapshot,
        width: Int,
        height: Int,
    ) {
        if (width <= 0 || height <= 0) return
        textPaint.color = snapshot.textColorArgb
        textPaint.textSize = snapshot.textSizePx
        textPaint.typeface = android.graphics.Typeface.DEFAULT

        snapshot.header?.let { line ->
            val contentTop = snapshot.topInsetPx
            val contentBottom = contentTop + max(0f, snapshot.lineHeightPx - HEADER_BOTTOM_PADDING_DP * snapshot.density)
            drawLine(
                canvas = canvas,
                line = line,
                left = snapshot.paddingHorizontalPx,
                right = width - snapshot.paddingHorizontalPx,
                top = contentTop,
                bottom = contentBottom,
                density = snapshot.density,
            )
        }
        snapshot.footer?.let { line ->
            val contentBottom = height - snapshot.bottomInsetPx
            val contentTop = contentBottom - max(0f, snapshot.lineHeightPx - FOOTER_TOP_PADDING_DP * snapshot.density)
            val horizontal = snapshot.paddingHorizontalPx + snapshot.cornerInsetPx
            drawLine(
                canvas = canvas,
                line = line,
                left = horizontal,
                right = width - horizontal,
                top = contentTop,
                bottom = contentBottom,
                density = snapshot.density,
            )
        }
    }

    private fun drawLine(
        canvas: Canvas,
        line: PageInfoLineSnapshot,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        density: Float,
    ) {
        if (right <= left || bottom <= top) return
        val cellWidth = (right - left) / 3f
        drawSlot(canvas, line.left, left, left + cellWidth, top, bottom, Paint.Align.LEFT, density)
        drawSlot(canvas, line.center, left + cellWidth, left + cellWidth * 2f, top, bottom, Paint.Align.CENTER, density)
        drawSlot(canvas, line.right, left + cellWidth * 2f, right, top, bottom, Paint.Align.RIGHT, density)
    }

    private fun drawSlot(
        canvas: Canvas,
        value: PageInfoSlotValue,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        align: Paint.Align,
        density: Float,
    ) {
        if (value === PageInfoSlotValue.None || right <= left) return
        val save = canvas.save()
        canvas.clipRect(left, top, right, bottom)
        when (value) {
            PageInfoSlotValue.None -> Unit
            is PageInfoSlotValue.Text -> drawText(canvas, value.value, left, right, top, bottom, align)
            is PageInfoSlotValue.Battery -> {
                val batteryWidth = BATTERY_WIDTH_DP * density
                val batteryHeight = BATTERY_HEIGHT_DP * density
                val x = alignedStart(left, right, batteryWidth, align)
                val y = (top + bottom - batteryHeight) / 2f
                batteryDrawer.draw(
                    canvas, x, y, batteryWidth, batteryHeight,
                    value.level, value.charging, textPaint.color, density,
                )
            }
            is PageInfoSlotValue.TimeBattery -> drawTimeBattery(
                canvas, value, left, right, top, bottom, align, density,
            )
        }
        canvas.restoreToCount(save)
    }

    private fun drawText(
        canvas: Canvas,
        text: String,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        align: Paint.Align,
    ) {
        val available = (right - left).coerceAtLeast(0f)
        if (available <= 0f) return
        val display = TextUtils.ellipsize(text, textPaint, available, TextUtils.TruncateAt.END).toString()
        textPaint.textAlign = align
        val x = when (align) {
            Paint.Align.LEFT -> left
            Paint.Align.CENTER -> (left + right) / 2f
            Paint.Align.RIGHT -> right
        }
        val fm = textPaint.fontMetrics
        val baseline = (top + bottom - fm.ascent - fm.descent) / 2f
        canvas.drawText(display, x, baseline, textPaint)
    }

    private fun drawTimeBattery(
        canvas: Canvas,
        value: PageInfoSlotValue.TimeBattery,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        align: Paint.Align,
        density: Float,
    ) {
        val batteryWidth = BATTERY_WIDTH_DP * density
        val batteryHeight = BATTERY_HEIGHT_DP * density
        val gap = INFO_ITEM_GAP_DP * density
        val availableText = (right - left - batteryWidth - gap).coerceAtLeast(0f)
        val time = TextUtils.ellipsize(
            value.time,
            textPaint,
            availableText,
            TextUtils.TruncateAt.END,
        ).toString()
        val textWidth = textPaint.measureText(time)
        val totalWidth = (textWidth + gap + batteryWidth).coerceAtMost(right - left)
        val start = alignedStart(left, right, totalWidth, align)
        val batteryX: Float
        val textLeft: Float
        if (value.batteryFirst) {
            batteryX = start
            textLeft = start + batteryWidth + gap
        } else {
            textLeft = start
            batteryX = start + textWidth + gap
        }
        drawText(canvas, time, textLeft, textLeft + textWidth, top, bottom, Paint.Align.LEFT)
        batteryDrawer.draw(
            canvas = canvas,
            left = batteryX,
            top = (top + bottom - batteryHeight) / 2f,
            width = batteryWidth,
            height = batteryHeight,
            level = value.level,
            charging = value.charging,
            color = textPaint.color,
            density = density,
        )
    }

    private fun alignedStart(left: Float, right: Float, width: Float, align: Paint.Align): Float =
        when (align) {
            Paint.Align.LEFT -> left
            Paint.Align.CENTER -> (left + right - width) / 2f
            Paint.Align.RIGHT -> right - width
        }
}

/** Compose 与位图页栏共用的电池图元。 */
internal class BatteryIconCanvasDrawer {
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val batteryTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val boltPath = Path()

    fun draw(
        canvas: Canvas,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        level: Int,
        charging: Boolean,
        color: Int,
        density: Float,
    ) {
        if (width <= 0f || height <= 0f) return
        val safeLevel = level.coerceIn(0, 100)
        val stroke = density
        val capWidth = 2f * density
        val bodyWidth = width - capWidth - stroke
        val inset = stroke / 2f

        bodyPaint.color = color
        bodyPaint.style = Paint.Style.STROKE
        bodyPaint.strokeWidth = stroke
        canvas.drawRect(
            left + inset,
            top + inset,
            left + bodyWidth - inset,
            top + height - inset,
            bodyPaint,
        )
        bodyPaint.style = Paint.Style.FILL
        val capHeight = height * 0.4f
        canvas.drawRect(
            left + bodyWidth,
            top + (height - capHeight) / 2f,
            left + bodyWidth + capWidth,
            top + (height + capHeight) / 2f,
            bodyPaint,
        )
        val fillInset = stroke + density
        val fillWidth = (bodyWidth - fillInset * 2f).coerceAtLeast(0f) * safeLevel / 100f
        if (fillWidth > 0f) {
            canvas.drawRect(
                left + fillInset,
                top + fillInset,
                left + fillInset + fillWidth,
                top + height - fillInset,
                bodyPaint,
            )
        }

        if (charging) {
            val boltWidth = (bodyWidth - stroke * 2f) * 0.45f
            val boltHeight = (height - stroke * 2f) * 0.85f
            val cx = left + (bodyWidth - stroke) / 2f
            val cy = top + height / 2f
            val boltLeft = cx - boltWidth / 2f
            val boltTop = cy - boltHeight / 2f
            boltPath.reset()
            boltPath.moveTo(boltLeft + boltWidth * 0.55f, boltTop)
            boltPath.lineTo(boltLeft, boltTop + boltHeight * 0.55f)
            boltPath.lineTo(boltLeft + boltWidth * 0.45f, boltTop + boltHeight * 0.55f)
            boltPath.lineTo(boltLeft + boltWidth * 0.30f, boltTop + boltHeight)
            boltPath.lineTo(boltLeft + boltWidth, boltTop + boltHeight * 0.40f)
            boltPath.lineTo(boltLeft + boltWidth * 0.55f, boltTop + boltHeight * 0.40f)
            boltPath.close()
            detailPaint.style = Paint.Style.FILL
            detailPaint.color = Color.argb(242, 255, 255, 255)
            canvas.drawPath(boltPath, detailPaint)
            detailPaint.style = Paint.Style.STROKE
            detailPaint.strokeWidth = 0.6f * density
            detailPaint.color = color
            canvas.drawPath(boltPath, detailPaint)
        } else {
            batteryTextPaint.color = if (safeLevel > 50) Color.WHITE else color
            batteryTextPaint.textSize = height * 0.75f
            batteryTextPaint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            batteryTextPaint.textAlign = Paint.Align.CENTER
            val baseline = top + height / 2f -
                (batteryTextPaint.fontMetrics.ascent + batteryTextPaint.fontMetrics.descent) / 2f
            canvas.drawText(
                safeLevel.toString(),
                left + (bodyWidth - stroke) / 2f,
                baseline,
                batteryTextPaint,
            )
        }
    }
}

private const val HEADER_BOTTOM_PADDING_DP = 4f
private const val FOOTER_TOP_PADDING_DP = 4f
private const val BATTERY_WIDTH_DP = 22f
private const val BATTERY_HEIGHT_DP = 11f
private const val INFO_ITEM_GAP_DP = 6f
